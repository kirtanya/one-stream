// Blender Foundation open movies (CC-BY). Torrents published by WebTorrent with HTTP web seeds.
const TRACKERS = [
  'udp://tracker.opentrackr.org:1337', 'udp://explodie.org:6969', 'wss://tracker.openwebtorrent.com',
].map(t => '&tr=' + encodeURIComponent(t)).join('');

function magnet(hash, name, torrentFile) {
  return 'magnet:?xt=urn:btih:' + hash + '&dn=' + encodeURIComponent(name) + TRACKERS +
    '&ws=' + encodeURIComponent('https://webtorrent.io/torrents/') +
    '&xs=' + encodeURIComponent('https://webtorrent.io/torrents/' + torrentFile);
}

// TMDB id → torrent.
const MOVIES = {
  '10378': { title: 'Big Buck Bunny', hash: 'dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c', file: 'big-buck-bunny.torrent', video: 'Big Buck Bunny.mp4', size: 276134947 },
  '45745': { title: 'Sintel', hash: '08ada5a7a6183aae1e09d831df6748d566095a10', file: 'sintel.torrent', video: 'Sintel.mp4', size: 129241752 },
  '133701': { title: 'Tears of Steel', hash: '209c8226b299b308beaf2b9cd3fb49212dbd13ec', file: 'tears-of-steel.torrent', video: 'Tears of Steel.webm', size: 571346576 },
};

async function getStreams(tmdbId, mediaType) {
  const m = MOVIES[String(tmdbId)];
  if (!m || mediaType !== 'movie') return [];
  return [{
    name: 'Open movies',
    title: m.title + ' · open movie (CC-BY)\n1080p · web seeded',
    quality: '1080p',
    size: m.size,
    url: magnet(m.hash, m.title, m.file),
    filename: m.video,
  }];
}

module.exports = { getStreams };
