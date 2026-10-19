# Personal media app: research

One Android app, for personal use and sideloaded (not on the Play Store), that merges two references:

- **TorrDroid**: a torrent search engine plus downloader.
- **Nuvio**: a media centre. Search a film or show, see where it's available to watch, then pick a stream from plugins/addons (direct links or torrents) and play it in the app.

Research date: 19 Oct 2026.

---

# Part A: TorrDroid

## A1. What it does

Play Store snapshot (`intelligems.torrdroid`): **last updated 26 Jul 2026** (it's still maintained; it just looks old), 1 Cr+ installs, 4.5★, ads + in-app purchases. The data-safety section says it **shares location, app info and device IDs with third parties**.

**Getting a torrent in:** built-in search (search term + file type: Audio/Video/Ebook/Software, plus a search duration) · **auto-download of the "best" result** · fake/malware torrent filtering · open `.torrent` files · open magnet links and `.torrent` URLs from the browser · share magnet links.

**Downloading:** no speed cap · DHT, LSD, UPnP, NAT-PMP · pick individual files · sequential download (play before it finishes) · up/down speed limits · several downloads at once, with RAM-aware queueing · Wi-Fi-only mode · internal or SD-card storage · notification progress, runs in the background · "watch and wait" (starts the download once peers appear).

**After download:** open, view or delete files · built-in file browser (move/copy/delete/share).

**Review pain points we can fix:** downloads stall because there are no seeders and the app never warns you · no search inside a torrent's file list (painful when it has hundreds of files) · ads and tracking.

## A2. Do we need their APK?
**No.** It's built on **libtorrent**, which is open source. LibreTorrent (GPL-3) is an open-source Android client on the same engine. The only proprietary part is their search backend, and we're designing our own (Part C).

---

# Part B: Nuvio

## B1. What Nuvio is
- Open source, **GPL-3**, on GitHub at `NuvioMedia/NuvioMobile` (about 4k stars, commits daily). There are separate repos for Android TV, webOS/Tizen and desktop (alpha), plus a self-host backend.
- A Stremio-style media centre. It's a **player and organiser that ships no content**. Every source comes from what you install.
- **Tech stack:** Kotlin Multiplatform + Compose Multiplatform (Android + iOS from one codebase). Player: **Media3 / ExoPlayer** (with FFmpeg, AV1 and MPEG-H decoders) **plus mpv** (libmpv) as a second engine. Subtitles via libass.
- Android has two build flavours: **`playstore`**, which has plugins and P2P removed or stubbed, and **`full`** (GitHub APK), which has everything. That split shows the plugin and torrent features are what Play policy objects to.

## B2. How "search a title → see where to watch" works
It's metadata-first. You search **titles**, not files:
1. **TMDB** (The Movie Database) supplies catalogs, search, posters, cast, seasons and episodes.
2. **Where to watch:** TMDB's `/{movie|tv}/{id}/watch/providers` endpoint returns, **per country** (e.g. `IN`), which services have the title as **stream (subscription) / rent / buy / free / ads**, with logos. The data comes from **JustWatch**, and TMDB's terms require you to credit JustWatch. Nuvio also uses provider IDs to build collections like "everything on Netflix".
3. Trakt / Simkl / MDBList handle watch history, lists and "continue watching". Jellyfin/Emby servers can be added as sources too.

> The provider response gives you the service name and a TMDB/JustWatch page link. Opening the actual Netflix or Prime page needs a per-service deep-link table (or the JustWatch link). It doesn't come in the API response.

## B3. The source system: two kinds of extensions

### 1) Stremio addons (remote HTTP services)
Nuvio implements the open **Stremio Addon Protocol**, and keeps a copy of its docs in the repo. An addon is just a web server:

| Route | Returns |
|---|---|
| `/manifest.json` | id, name, version, `types` (movie/series), `resources` (catalog, meta, stream, subtitles), `idPrefixes` (e.g. `tt` for IMDb IDs) |
| `/catalog/{type}/{id}.json` | lists of titles |
| `/meta/{type}/{id}.json` | details for one title |
| `/stream/{type}/{id}.json` | **the playable sources**; for series the id is `tt1234567:season:episode` |
| `/subtitles/{type}/{id}.json` | subtitle files |

A **stream object** can be any one of:
- `url` (direct HTTP/HLS video)
- `infoHash` + `fileIdx` + `sources` (trackers) → **a torrent**
- `ytId` (a YouTube video)
- `externalUrl` (open in another app, such as Netflix)
- `nzbUrl` (Usenet)

It also carries `name`/`description` (quality and size text), `subtitles`, and `behaviorHints` (`bingeGroup` for auto-next-episode, `proxyHeaders`, `filename`, `videoSize`).

You install an addon by pasting its `…/manifest.json` URL. **Anyone can write one.** There's an official SDK (`stremio-addon-sdk`, Node), and thousands of community addons exist.

### 2) Nuvio Plugins, a.k.a. "local scrapers" (JavaScript that runs on the phone)
From the source (`features/plugins/`, `PluginRuntime.kt`, `JsBindings.kt`):

- **Repository = one `manifest.json` URL** (usually a raw GitHub link):
  ```json
  {
    "name": "My Repo", "version": "1.0.0", "author": "me",
    "scrapers": [{
      "id": "example", "name": "Example", "version": "1.0.0",
      "filename": "providers/example.js",
      "supportedTypes": ["movie", "tv"],
      "enabled": true, "hasSettings": false, "logo": "…",
      "contentLanguage": ["en"], "formats": ["mp4", "m3u8"]
    }]
  }
  ```
  In the app: **Settings → Plugins → add repository URL**, then switch individual scrapers on or off. Repos refresh every **6 h** and the JS is cached on the device.
- **Plugin contract:** each JS file exports one async function:
  ```js
  async function getStreams(tmdbId, mediaType, season, episode) {
    // fetch pages/APIs, parse, return:
    return [{ title, name, url, quality, size, language, provider,
              seeders, peers, infoHash, headers, subtitles }];
  }
  module.exports = { getStreams };
  ```
  A result can be a direct `url` **or an `infoHash`**. That's how a "torrent plugin" works: it returns hashes, and Nuvio's P2P engine plays them.
- **Runtime:** **QuickJS** (an embeddable JS engine, via the `quickjs-kt` library), one sandbox per call, **60 s timeout, at most 10 plugins in parallel**, on background threads. The host injects a small standard library: `fetch`, `URL` / `URLSearchParams`, `atob`, a **cheerio**-style HTML parser (DOM bridge), crypto, WASM, `require`, `SCRAPER_SETTINGS` (per-plugin user settings) and `TMDB_API_KEY`.
- **Who writes plugins:** **the community, not Nuvio.** Nuvio ships none. Anyone can host a repo, and there's a `nuvio-plugin` GitHub topic. Most public repos scrape unlicensed streaming sites, and they break or get archived often.

## B4. How Nuvio streams a torrent ("P2P")
- Off by default. Turning it on shows a **consent dialog**, because streaming a torrent also uploads to other peers.
- Engine: **`nuvio-engine`**, their own **C++20 library on top of libtorrent** (GPL-3, marked "not production-ready"). It replaced an older bundled **TorrServer** (Go).
- Flow (`P2pStreamingEngine.android.kt`): `infoHash` → build a magnet with default and addon trackers → `addMagnet` → `prepareStream(fileIndex or filename hint)` → the engine serves the file over a **local HTTP server on 127.0.0.1 with Range support** → Media3/mpv plays that URL.
- **Playback-aware piece scheduling:** pieces just ahead of the playhead get deadlines, and a seek re-prioritises from the new position. It also prefetches the file's last pieces, where MP4/MKV keep their index.
- Settings: profile Soft/Balanced/Fast · upload on/off · **disk cache 0/2/5/10 GB** (old pieces are evicted; the file currently playing is protected) · on-screen torrent stats (peers, speed, buffer).
- An alternative path is **debrid** (Real-Debrid, TorBox, Premiumize). A paid cloud service fetches the torrent and returns a fast HTTP link, so no P2P happens on the phone. It's optional, and we can skip it.

---

# Part C: our merged app

## C1. The key idea
**Streaming a torrent and downloading a torrent are the same libtorrent job with different piece priorities.**
- *Download*: rarest-first, keep the whole file.
- *Stream*: deadline-first from the playhead, cap the cache, serve over localhost.

So we run **one engine and one torrent session**, and every torrent can switch modes:
- Watching and want to keep it → **"Keep / Download"** switches it to full download, and the pieces you've already watched are reused.
- Downloading and impatient → **"Play now"** switches to streaming priority immediately.

Neither TorrDroid nor Nuvio does this. Nuvio only streams (cache gets evicted), and TorrDroid's "play while downloading" is just sequential mode.

## C2. User flow
```
Search ─┬─ Titles (TMDB) ───────► Title page
        │                          ├─ Where to watch (IN): Netflix · Prime · Hotstar …  → open app
        │                          └─ Sources (plugins + addons, ranked)
        │                               ├─ ▶ Stream now   (HTTP link or torrent)
        │                               └─ ⬇ Download     (torrent → Downloads)
        └─ Files (torrent search, TorrDroid-style: category + duration + auto-pick)
Magnet / .torrent / link shared in ──► Add sheet ──► Stream now | Download
Downloads ── Active · Completed ── Play · Open folder · Keep/Stop seeding
```

## C3. Architecture
```
┌──────────────────────── UI (Jetpack Compose) ────────────────────────┐
│ Home · Search · Title page · Sources · Player · Downloads · Files ·  │
│ Settings (Plugins, Addons, Engine, Storage, Network)                 │
└───────────────┬──────────────────────────────────────────────────────┘
                │ ViewModels / StateFlow
┌───────────────▼──────────────────────────────────────────────────────┐
│ MetadataRepo (TMDB + watch providers)                                │
│ SourceRepo ── AddonClient (Stremio protocol, HTTP)                   │
│           └─ PluginHost (QuickJS sandbox, Nuvio-compatible manifest) │
│ TorrentSearchRepo (Torznab · JSON APIs · RSS · Internet Archive)     │
│ Ranker (quality, seeders, size, trust; fake-file filter)             │
│ LibraryRepo (Room: titles, history, downloads, resume positions)     │
└───────┬───────────────────────────────────────────┬──────────────────┘
        │                                           │
┌───────▼──────────────────────────┐   ┌────────────▼─────────────────┐
│ TorrentService (foreground)      │   │ Player                       │
│  libtorrent4j SessionManager     │──►│  Media3 ExoPlayer (+FFmpeg   │
│  mode per torrent: DOWNLOAD /    │   │  audio for AC3/EAC3/DTS)     │
│   STREAM (piece deadlines)       │   │  subtitles, resume, next ep  │
│  LocalHttpServer 127.0.0.1:port  │   └──────────────────────────────┘
│   (Range requests → pieces)      │
│  cache budget + resume data      │
└──────────────────────────────────┘
```

## C4. Stack
| Layer | Choice | Why |
|---|---|---|
| Language/UI | **Kotlin + Jetpack Compose** (Android only) | Same family as Nuvio, so we can read their code directly. No need for multiplatform. |
| Torrent engine | **libtorrent4j** | Mature, has `setPieceDeadline`, sequential mode and file priorities: everything streaming needs. Nuvio's `nuvio-engine` is C++ and "not production-ready". |
| Local stream server | **NanoHTTPD** or **Ktor CIO** on 127.0.0.1 | Answers the player's Range requests by waiting for pieces. |
| Player | **Media3 ExoPlayer** + FFmpeg audio extension | Handles MKV/MP4/HLS. Add libmpv later only if a codec fails. |
| Plugin runtime | **QuickJS via `quickjs-kt`** | What Nuvio uses. Small, fast, sandboxed. Gives us their plugin format for free. |
| Addons | Plain HTTP + kotlinx.serialization | The Stremio protocol is just JSON over HTTP. |
| Metadata | **TMDB API** (free key) | Search, artwork, seasons, where-to-watch. |
| Storage/DB | Room + DataStore | Library, downloads, settings. |

Kotlin rather than Flutter, because the engine, player, foreground service and JS sandbox are all Android or JVM-native. Flutter would put a bridge in front of every one of them.

## C5. Our plugin system
Use **Nuvio's plugin format** (`manifest.json` + `getStreams(tmdbId, type, season, episode)`) and the **Stremio addon** format, both read-only on our side. Our app then works with any repo or addon that follows those formats, and we can write our own.

Additions for the merged app:
- A plugin may return `kind: "torrent" | "http" | "external"`. Torrent results get both **Stream** and **Download** buttons.
- An optional second entry point, `search(query, category)`, so a plugin can also power **TorrDroid-style file search**, not just title→stream.
- A per-plugin **permissions line** in the UI (domains it fetches) and a **kill switch**. These plugins are remote code, so we only add repos we trust and pin versions.

> **Legal note:** This is a personal tool, and the app stays neutral (like Nuvio and qBittorrent): it ships no sources. Most public plugin repos and torrent sites point to unlicensed copies of films and shows. Using them is copyright infringement in India and most other countries, whatever the app is. Built-in defaults will be legal sources only: Internet Archive, public-domain films, Linux ISOs, and your own Jellyfin if you have one. Which repos you add is up to you.

## C6. Android constraints (2026)
| Topic | Plan |
|---|---|
| Foreground service | Apps targeting Android 15+ get only 6 h/day of `dataSync`. Sideloaded, so use `specialUse`; still handle `onTimeout()` by saving resume data. Streaming during playback can run under the `mediaPlayback` type. |
| Storage | `MANAGE_EXTERNAL_STORAGE` (sideload) for downloads. The streaming cache lives in app storage with a GB budget. |
| Notifications / battery | `POST_NOTIFICATIONS` · ask for a battery-optimisation exemption. |
| Links | Intent filters for `magnet:`, `application/x-bittorrent`, `*.torrent`, plus a share target. |
| Min SDK / ABI | 28 (libtorrent4j) · `arm64-v8a`. |
| Upload consent | Same as Nuvio: one-time notice that streaming and downloading torrents also uploads. Upload limit in settings. |

## C7. Feature list
| Feature | From | Phase |
|---|---|---|
| Add magnet / `.torrent` / link; downloads list; file picker **with search**; notifications; resume | TorrDroid | **1. Engine + downloader** |
| Speed limits, Wi-Fi-only, queue, seeder-health + stall warning | TorrDroid+ | 1 |
| **Stream a torrent** (local HTTP + piece deadlines) in ExoPlayer; seek; cache budget | Nuvio | **2. Streaming** |
| Download ↔ Stream switch on the same torrent | ours | 2 |
| TMDB search, title pages, seasons/episodes, **where to watch (IN)** | Nuvio | **3. Media centre** |
| Stremio addon client · Nuvio-format plugin host (QuickJS) · source ranking | Nuvio | **4. Sources** |
| Torrent file search (Torznab/RSS/IA/plugins) + auto-pick + watch-and-wait | TorrDroid | 4 |
| Continue watching, history, subtitles (addons + embedded), auto next episode | Nuvio | 5 |
| File browser, Trakt sync, debrid (optional) | both | later |

## C8. Build order
1. **Engine spike:** libtorrent4j on your phone. Download a legal test torrent (Big Buck Bunny / Ubuntu).
2. **Stream spike:** the same torrent served on `127.0.0.1` and played in ExoPlayer while it downloads, including a seek to the middle. *This is the riskiest piece, so we prove it early.*
3. Foreground service, Room, Downloads UI, add sheet, intent handlers.
4. TMDB title search + where-to-watch.
5. Plugin host (QuickJS) + addon client, then a sample plugin that returns Internet Archive torrents, to exercise both buttons end to end.
6. Torrent file search + ranking + auto-pick.
7. Polish: continue watching, subtitles, file browser.

## C9. Code worth reading (GPL-3, fine for personal use)
- Nuvio: `features/plugins/*`, `plugins/runtime/js/JsBindings.kt` (the JS standard library they give plugins), `features/p2p/P2pStreamingEngine.android.kt`, `features/addons/*`.
- nuvio-engine: playback-aware piece scheduler + local HTTP range server (C++).
- LibreTorrent: libtorrent4j service, notifications and resume handling on Android.

---

## Sources
- TorrDroid on Google Play: https://play.google.com/store/apps/details?id=intelligems.torrdroid&hl=en_IN
- Xataka Android review of TorrDroid: https://www.xatakandroid.com/aplicaciones-android/torrdroid-una-aplicacion-para-buscar-y-descargar-torrents-en-android-minimalista-y-efectiva
- Nuvio Mobile source: https://github.com/NuvioMedia/NuvioMobile (branch `cmp-rewrite`)
- Nuvio Engine: https://github.com/NuvioMedia/nuvio-engine
- Nuvio local scraper overview (DeepWiki): https://deepwiki.com/tapframe/NuvioStreaming/4.3-local-scraper-system
- NuvioTV debrid docs: https://mintlify.wiki/NuvioMedia/NuvioTV/features/debrid
- Stremio addon protocol: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
- Stremio stream object: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/stream.md
- `nuvio-plugin` topic: https://github.com/topics/nuvio-plugin
- libtorrent4j: https://github.com/aldenml/libtorrent4j/releases
- LibreTorrent: https://github.com/proninyaroslav/libretorrent
- Android FGS timeout: https://developer.android.com/develop/background-work/services/fg-service-timeout
- Torznab (SearXNG docs): https://docs.searxng.org/dev/engines/online/torznab.html
- TMDB watch providers / JustWatch attribution (forum): https://www.themoviedb.org/talk/5ff0a2b7176a940045e88665
