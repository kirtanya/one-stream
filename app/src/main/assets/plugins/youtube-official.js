// Full films that official rights-holder channels upload to YouTube for free. Never plays or
// extracts anything: each result is an external link that opens the YouTube app on that video.
//
// Only these channels are ever queried (CLAUDE.md "Built-in sources stay legal"). Channels are
// addressed by their immutable id, not the handle, so a re-registered handle can't redirect us,
// and every video must also be owned by that same id.
const CHANNELS = [
  { id: 'UCBOmfqgTZi7yDp4-3Lr_3lA', label: 'Shemaroo Movies', handle: '@shemaroomovies', hindiOnly: true },
  { id: 'UCOF23vGxkbhN4wl7ROrgXsA', label: 'Goldmines Bollywood', handle: '@GoldminesBollywood', hindiOnly: true },
  { id: 'UCyoXW-Dse7fURq30EWl_CUA', label: 'Goldmines Telefilms', handle: '@GoldminesTelefilms' },
  { id: 'UCObyBrdrtQ20BU9PxHQDY9w', label: 'Pen Multiplex', handle: '@PenMultiplex' },
  { id: 'UCEKWXRsfUHkan-D_ljU8Asw', label: 'Rajshri', handle: '@Rajshri' },
];

const INDIAN_LANGUAGES = ['hi', 'bn', 'ta', 'te', 'ml', 'kn', 'mr', 'gu', 'pa', 'ur', 'or', 'as', 'bho'];
const MIN_MINUTES = 70;
const MAX_RESULTS = 2;
// The channel page is only a list when it is asked like a desktop browser (a phone agent gets a
// differently escaped payload); SOCS skips the EU consent redirect.
const HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
  'Accept-Language': 'en',
  'Cookie': 'SOCS=CAI',
};

// Words channels add around a film's name; dropped on both sides before comparing.
const NOISE = new Set(['hindi', 'full', 'movie', 'movies', 'film', 'hd', '4k', 'uhd', '1080p', 'new', 'superhit', 'bollywood',
  'blockbuster', 'dubbed', 'south', 'hit', 'eng', 'srt', 'sub', 'subtitles', 'restored', 'classic', 'released']);

function words(s) {
  return String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/&/g, ' and ')
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim().split(' ')
    .filter(w => w && !NOISE.has(w));
}
const YEAR = /^(19|20)\d\d$/;
// Some channels write the year bare ("Ghar Ho To Aisa 1990"): key() drops it, bareYear() reads it back.
function key(s) {
  const w = words(s);
  if (w.length > 1 && YEAR.test(w[w.length - 1])) w.pop();
  if (w[0] === 'the') w.shift();
  return w.join(' ');
}
function bareYear(s) {
  const w = words(s);
  return w.length > 1 && YEAR.test(w[w.length - 1]) ? Number(w[w.length - 1]) : null;
}

// "2:24:38" → minutes; null when unreadable.
function minutes(text) {
  const p = String(text || '').split(':').map(Number);
  if (!p.length || p.length > 3 || p.some(isNaN)) return null;
  return p.reduce((total, n) => total * 60 + n, 0) / 60;
}

// Does the video's title name this film (and no other)? Channels write "Film (1975) Full Movie - cast | director",
// so the film is the first segment; a later segment counts only when the video's own year agrees (or, if we have no
// year, when it says "full movie").
function titleMatches(videoTitle, filmTitle, filmYear) {
  const wanted = key(filmTitle);
  if (!wanted) return false;
  const yearMatch = /[\(\[\{]\s*((?:19|20)\d\d)\s*[\)\]\}]/.exec(videoTitle);
  const segments = String(videoTitle).split(/\s+[|–—-]\s+|\s*\|\s*/);
  const unbracketed = raw => raw.replace(/[\(\[\{][^\)\]\}]*[\)\]\}]/g, ' ');
  const videoYear = yearMatch ? Number(yearMatch[1]) : bareYear(unbracketed(segments[0]));
  if (videoYear && filmYear && Math.abs(videoYear - filmYear) > 1) return false;
  return segments.some((raw, i) => {
    if (key(unbracketed(raw)) !== wanted) return false;
    // Without a year in a later segment the cast line could belong to a remake of the same name.
    return i === 0 || (filmYear ? !!videoYear : /full\s+(movie|film)/i.test(raw));
  });
}

function videoRenderers(node, out) {
  if (Array.isArray(node)) { node.forEach(n => videoRenderers(n, out)); return out; }
  if (node && typeof node === 'object') {
    if (node.videoRenderer) out.push(node.videoRenderer);
    Object.keys(node).forEach(k => { if (k !== 'videoRenderer') videoRenderers(node[k], out); });
  }
  return out;
}

// The channel's search page → its videos [{ id, title, minutes, owner }]. Null when the page isn't that channel's.
function parseSearchPage(html, channelId) {
  const start = html.indexOf('var ytInitialData = ');
  if (start < 0) return null;
  const from = start + 'var ytInitialData = '.length;
  const end = html.indexOf(';</script>', from);
  if (end < 0) return null;
  let data;
  try { data = JSON.parse(html.slice(from, end)); } catch (e) { return null; }
  const meta = data.metadata && data.metadata.channelMetadataRenderer;
  if (!meta || meta.externalId !== channelId) return null;
  return videoRenderers(data.contents, []).map(v => {
    const owner = (v.ownerText && v.ownerText.runs && v.ownerText.runs[0] && v.ownerText.runs[0].navigationEndpoint || {}).browseEndpoint;
    return {
      id: v.videoId,
      title: ((v.title && v.title.runs) || []).map(r => r.text).join(''),
      minutes: minutes(v.lengthText && v.lengthText.simpleText),
      owner: owner && owner.browseId,
    };
  });
}

function matchingFilms(videos, channel, filmTitle, filmYear) {
  return videos.filter(v => v.id && /^[\w-]{11}$/.test(v.id) && v.owner === channel.id &&
    v.minutes !== null && v.minutes > MIN_MINUTES && titleMatches(v.title, filmTitle, filmYear));
}

async function searchChannel(channel, filmTitle, filmYear) {
  try {
    const url = 'https://www.youtube.com/channel/' + channel.id + '/search?query=' + encodeURIComponent(filmTitle);
    const res = await fetch(url, { headers: HEADERS });
    if (!res.ok) return [];
    const videos = parseSearchPage(await res.text(), channel.id);
    return videos ? matchingFilms(videos, channel, filmTitle, filmYear).map(v => ({ channel: channel, video: v })) : [];
  } catch (e) {
    console.warn(channel.label + ': ' + e.message);
    return [];
  }
}

// TMDB's original_language, or null when it can't be read.
async function originalLanguage(tmdbId) {
  try {
    const res = await fetch('https://api.themoviedb.org/3/movie/' + tmdbId + '?api_key=' + TMDB_API_KEY);
    return res.ok ? (await res.json()).original_language || null : null;
  } catch (e) { return null; }
}

function duration(m) {
  const h = Math.floor(m / 60), r = Math.round(m % 60);
  return h ? h + 'h ' + r + 'm' : r + 'm';
}

async function getStreams(tmdbId, mediaType) {
  if (mediaType !== 'movie' || !REEL.title) return [];
  const language = await originalLanguage(tmdbId);
  // Non-Indian films are not on these channels. When TMDB can't say, ask only the two Hindi-film
  // channels so an unknown language costs two page loads, not five.
  if (language && INDIAN_LANGUAGES.indexOf(language) < 0) return [];
  const channels = language ? CHANNELS : CHANNELS.filter(c => c.hindiOnly);
  const found = (await Promise.all(channels.map(c => searchChannel(c, REEL.title, REEL.year)))).reduce((a, b) => a.concat(b), []);

  const seen = {};
  return found.filter(f => !seen[f.video.id] && (seen[f.video.id] = true)).slice(0, MAX_RESULTS).map(f => ({
    name: 'Free on YouTube',
    provider: f.channel.label,
    title: REEL.title + (REEL.year ? ' (' + REEL.year + ')' : '') + '\nFull film · ' + duration(f.video.minutes) + ' · opens YouTube',
    externalUrl: 'https://www.youtube.com/watch?v=' + f.video.id,
  }));
}

module.exports = { getStreams };
