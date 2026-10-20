package com.kirtanya.reel.core.magnet

/**
 * Public trackers added to every magnet (and to non-private .torrent files) so DHT-only links
 * still find peers quickly. The engine announces to all of them at once.
 *
 * Taken from ngosang/trackerslist "trackers_best" on 2026-10-20; refresh by hand now and then
 * (the app never fetches a list at runtime).
 */
val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://tracker-udp.gbitt.info:80/announce",
    "udp://tracker.tryhackx.org:6969/announce",
    "udp://tracker.gmi.gd:6969/announce",
    "udp://tracker.skynetcloud.site:6969/announce",
    "http://tracker.qu.ax:6969/announce",
    "http://tracker.renfei.net:8080/announce",
)
