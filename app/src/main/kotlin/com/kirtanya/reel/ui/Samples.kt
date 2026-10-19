package com.kirtanya.reel.ui

/** Legal test torrents (Blender open movies, CC-BY) with HTTP web seeds as a fallback. */
data class SampleTorrent(val title: String, val magnet: String)

private fun webtorrentSample(hash: String, name: String, file: String) =
    "magnet:?xt=urn:btih:$hash&dn=${name.replace(' ', '+')}" +
        "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337&tr=udp%3A%2F%2Fexplodie.org%3A6969" +
        "&tr=wss%3A%2F%2Ftracker.openwebtorrent.com" +
        "&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F&xs=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F$file"

val SAMPLE_TORRENTS = listOf(
    SampleTorrent("Big Buck Bunny", webtorrentSample("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", "Big Buck Bunny", "big-buck-bunny.torrent")),
    SampleTorrent("Sintel", webtorrentSample("08ada5a7a6183aae1e09d831df6748d566095a10", "Sintel", "sintel.torrent")),
    SampleTorrent("Tears of Steel", webtorrentSample("209c8226b299b308beaf2b9cd3fb49212dbd13ec", "Tears of Steel", "tears-of-steel.torrent")),
)
