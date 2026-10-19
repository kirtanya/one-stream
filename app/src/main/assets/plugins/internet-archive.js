// Public-domain feature films on the Internet Archive (direct HTTP, no torrent needed).
async function getStreams(tmdbId, mediaType) {
  if (mediaType !== 'movie') return [];
  let title = REEL.title, year = REEL.year;
  if (!title) {
    const meta = await fetch('https://api.themoviedb.org/3/movie/' + tmdbId + '?api_key=' + TMDB_API_KEY).then(r => r.json());
    title = meta.title; year = parseInt((meta.release_date || '').slice(0, 4), 10) || null;
  }
  if (!title) return [];

  // Only archive.org's curated "feature_films" collection (public-domain films). Uploader-set
  // licences aren't trusted: copyrighted films get uploaded marked "public domain".
  const q = 'title:("' + title.replace(/"/g, '') + '") AND mediatype:movies AND collection:feature_films' +
    (year ? ' AND year:[' + (year - 1) + ' TO ' + (year + 1) + ']' : '');
  const search = 'https://archive.org/advancedsearch.php?q=' + encodeURIComponent(q) +
    '&fl[]=identifier&fl[]=title&fl[]=year&sort[]=downloads+desc&rows=6&output=json';
  const norm = s => String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  const wanted = norm(title);
  const docs = (((await fetch(search).then(r => r.json())).response || {}).docs || [])
    .filter(d => norm(d.title).startsWith(wanted))
    .filter(d => !/trailer|teaser|clip|promo|review/i.test(d.title + ' ' + d.identifier));

  const out = [];
  for (const doc of docs.slice(0, 2)) {
    const meta = await fetch('https://archive.org/metadata/' + encodeURIComponent(doc.identifier)).then(r => r.json());
    const files = (meta.files || [])
      .filter(f => /\.(mp4|m4v|mkv|webm|ogv)$/i.test(f.name) && Number(f.size) > 50 * 1024 * 1024)
      .sort((a, b) => Number(b.height || 0) - Number(a.height || 0) || Number(b.size) - Number(a.size))
      .slice(0, 3);
    for (const f of files) {
      const h = Number(f.height || 0);
      const quality = h >= 1000 ? '1080p' : h >= 700 ? '720p' : h >= 400 ? '480p' : '';
      out.push({
        name: 'Internet Archive',
        title: (doc.title || title) + (doc.year && String(doc.title).indexOf(doc.year) < 0 ? ' (' + doc.year + ')' : '') +
          '\n' + f.name + (quality ? ' · ' + quality : '') + ' · public domain',
        quality: quality,
        size: Number(f.size),
        url: 'https://archive.org/download/' + encodeURIComponent(doc.identifier) + '/' + f.name.split('/').map(encodeURIComponent).join('/'),
      });
    }
  }
  return out;
}

module.exports = { getStreams };
