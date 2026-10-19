# Reel: product + technical plan

> Working name **Reel** (package `com.kirtanya.reel`). Rename any time.
> Background research: [RESEARCH.md](RESEARCH.md). This doc is what we build and how each piece works.

---

## 0. Prime use case

**You sit in front of the TV, find a film or episode, press OK, and it plays in a few seconds without being saved to the TV.**

- **TV first, streaming first.** Your TV (Acer R4 Google TV, Android 14, 32-bit ARM) has little free storage, so on TV the default is *stream*, cache only what's needed, and clean it up afterwards.
- **Phone second.** Same app, same library. On the phone you can stream *or* download, and send things to the TV ("Play on TV").
- **Downloading still exists everywhere** (TorrDroid-style), but on TV it's a secondary action with a storage check.
- **One Launcher integration.** The TV launcher you built already has TMDB search and title pages. Its "Watch on" list gets a **Reel** option that opens our title page directly.

### Who does what, where

| Action | TV | Phone |
|---|---|---|
| Browse / search titles | ✅ main way in | ✅ |
| Where to watch (Netflix, Prime, Hotstar…) | ✅ opens the app on TV | ✅ |
| Pick a source → **Stream** | ✅ **primary** | ✅ |
| Pick a source → **Download** | ⚠️ secondary, storage-checked | ✅ primary |
| Torrent file search (TorrDroid-style) | ✅ (simple) | ✅ (full) |
| Paste or open magnet / `.torrent` | via phone → TV | ✅ |
| **Play on TV** (send from phone) | receives | sends |
| Continue watching | ✅ | ✅ (separate per device) |
| Settings: plugins, addons, engine | ✅ (basic) | ✅ (full) |

---

## 1. Features and how each one works

### 1.1 Home
- **TV:** cinematic layout like One Launcher's TV home. A full-bleed backdrop of the focused poster, then rows: **Continue watching**, **Trending movies**, **Trending shows**, then **Downloads** (only if any).
- **Phone:** the same rows as cards, plus a bottom bar: Home · Search · Downloads · Settings.
- Data: TMDB `trending/{movie|tv}/week` (region IN) plus local Continue-watching from the database.

### 1.2 Search
Two tabs (chips on TV):
1. **Titles:** TMDB `search/multi`, giving posters for films and shows. Selecting one opens the title page.
2. **Files:** TorrDroid-style torrent search across enabled search providers. Pick a category (Video/Audio/Ebook/Software/Any) and get a results list (seeders, size, source, age). There's an **Auto** toggle: run the search and start the best match. On TV the action is Stream; on phone it's Download.

On TV you type with the system keyboard or voice (`ACTION_SEARCH` / mic), or send text from the phone.

### 1.3 Title page
- Backdrop, title, year, rating, runtime, genres, overview, cast. For shows: a season picker and episode list.
- **Where to watch (IN):** TMDB `watch/providers` grouped Stream / Free / Rent / Buy, with logos. On TV, selecting a service launches that app if it's installed (we can reuse the provider→package map from One Launcher). Otherwise it opens the JustWatch link. Credit line: "Data by JustWatch".
- **Sources:** ranked list from all enabled addons and plugins (1.4). Each row shows quality (4K/1080p/720p), size, seeders, type (Torrent/Direct), source name, and a **"Fits on this device"** check for torrents on TV.
  - Main button: **▶ Play**. That's a stream.
  - Secondary button (long-press OK on TV, `⋮` on phone): **⬇ Download**, **Play on TV** (phone), **Copy magnet**, **Open in external player**.
- **Auto-play best source** setting: OK on an episode plays the top-ranked source straight away (Nuvio-like "just play").

### 1.4 Sources: plugins + addons
Two extension types, both installed by URL:

| | **Plugins** (Nuvio format) | **Addons** (Stremio protocol) |
|---|---|---|
| Install | Repo URL → `manifest.json` listing JS files | Addon URL → `manifest.json` |
| Runs | **On device**, in a QuickJS sandbox | **Remote** HTTP server |
| Contract | `getStreams(tmdbId, type, season, episode)` → `[{title, url \| infoHash, quality, size, seeders, …}]` | `GET /stream/{type}/{imdbId[:s:e]}.json` → `{streams:[…]}` |
| Extras (ours) | optional `search(query, category)` for Files search | catalogs and subtitles routes too |

**How a source request works**
1. Title page opens. We already have the TMDB id; get the IMDb id from TMDB `external_ids`, because addons use `tt…` IDs.
2. Fan out **in parallel**: every enabled plugin (up to 10 at once, 30 s timeout each) and every addon with a `stream` resource for that type.
3. **Stream results in progressively.** Rows appear as each source answers; TV shows a "3 of 7 sources…" line.
4. Normalise everything to one `Source` model. **De-duplicate** by info-hash or URL.
5. **Rank:** quality preference (setting, default 1080p) → playable on this device (codec hints like HEVC/DV/AV1 vs device decoders) → fits storage (TV) → seeders (torrents) → source trust order (user can drag to reorder) → size sanity.
6. Cache results for 30 min per title/episode.

**Plugin sandbox (QuickJS)**
- One fresh JS context per call, with a 30 s timeout and a memory cap.
- Host APIs we provide: `fetch` (OkHttp underneath), `URL`, `URLSearchParams`, `atob`/`btoa`, `console`, a tiny cheerio-like `parseHTML` (via jsoup), `crypto` (md5/sha/aes via Java), `SCRAPER_SETTINGS`, `TMDB_API_KEY`.
- Every request is logged per plugin, so Settings → Plugin → "Network" shows the domains it hit.
- Repos refresh every 6 h. Each plugin can be switched on/off and has a version shown.

**Built-in sources (legal):** an **Internet Archive** plugin (public-domain films, CC content; `archive.org` items all have `.torrent`s), **Blender open movies** (test content), and your own **Jellyfin/Emby**, if added later. Anything else you add yourself.

### 1.5 Playback (Stream)

```
Source chosen
   ├─ Direct URL ──────────────────────────────────────────► Player(url, headers)
   └─ Torrent (infoHash + fileIdx)
        ├─ Engine: add magnet (default + source trackers), mode = STREAM
        ├─ wait for metadata (show "Finding peers… 12 peers")
        ├─ choose file: fileIdx ▸ filename hint ▸ biggest video file
        ├─ storage check (TV): file size vs cache budget → warn / suggest smaller source
        ├─ prioritise: first ~8 MB + last ~4 MB (container index), deadline window
        └─ Local server http://127.0.0.1:{port}/s/{hash}/{fileIdx} ─► Player
```

- **Player:** Media3 ExoPlayer with custom controls; TV controls are D-pad friendly.
  - D-pad left/right: seek ±10 s, holding speeds it up.
  - OK: show controls / pause.
  - Up: audio and subtitle tracks.
  - Down: source info (speed, peers, buffer).
  - Back: exit, saving position.
- **Buffer screen:** "Preparing: 23% · 4.2 MB/s · 38 peers", then autoplay once there's enough buffer (default 15 s of video or 8 MB).
- **Resume:** position saved every 10 s and on exit. Continue watching shows titles 5%–92% watched.
- **Next episode:** near the end, a "Next episode in 10 s" card. It plays the same source group (`bingeGroup` / same plugin and quality).
- **Subtitles:** embedded tracks, plus subtitles returned by sources, plus any subtitle addon (e.g. OpenSubtitles). Settings for size and background.
- **Fallbacks:** if the codec isn't supported or there's no video after 20 s → "Try next source" or **Open in external player** (VLC, MX, Just Player) with the same localhost URL. It works because our server keeps running.
- Later option: a **libmpv** engine for formats ExoPlayer can't decode.

### 1.6 Torrent engine: one engine, two modes
One libtorrent session (libtorrent4j) inside a foreground service. Every torrent is in one of two modes:

| | **STREAM** | **DOWNLOAD** |
|---|---|---|
| Piece order | Deadline window ahead of the playhead; seek re-targets | Normal (rarest-first) |
| Files | Only the playing file | User-selected files |
| Saved to | App cache `cache/streams/{hash}` | Downloads folder (`/sdcard/Download/Reel/…` or chosen) |
| After playback | Kept for **"Recently streamed"** within the cache budget, then evicted (oldest first, never the playing one) | Kept until the user deletes it |
| Seeding | Stops when playback stops (setting: seed while watching only) | Per settings (ratio / time / off) |

**Switching modes:**
- **Keep / Download** while streaming: move storage to Downloads, select the file(s), go to rarest-first. Pieces already fetched are kept.
- **Play now** on a download: add a STREAM session on the same torrent and file; the deadline window takes priority.

**Streaming read path (local HTTP server):**
1. The player requests `Range: bytes=a-b`.
2. Map the byte range to pieces: `piece = (fileOffset + byte) / pieceLength`.
3. Re-target the deadline window: clear old deadlines, set `setPieceDeadline(p, i * 150ms)` for the next N pieces. N is sized to about 20–40 MB, based on bitrate (file size / runtime).
4. For each piece: if we have it, read it from disk; otherwise suspend on a per-piece `CompletableDeferred`, completed by `PieceFinishedAlert`. Timeout → keep waiting while showing "Buffering…".
5. Stream the bytes out in 64 KB chunks. A closed connection means a seek or stop, so cancel.

**Storage on TV (the big risk):** libtorrent writes every piece it downloads to disk, so a streamed file can grow to its full size.
- Before playing, compare the file size with `min(cache budget, free space − 1 GB)`. If it won't fit, show "This file is 18 GB, TV has 6 GB free" and offer smaller sources (ranking already prefers ones that fit).
- When playback ends, the cache is trimmed back to budget.
- **Fallback plan** if this hurts in practice: add a second engine on TV with a **RAM rolling cache** (TorrServer, which is GPL and what TV streaming apps like Lampa use), behind the same `TorrentEngine` interface. We decide after testing on the real TV.

**Engine settings:**
- Profile: Soft / Balanced / Fast (connection limits).
- Upload: on / limited / off-after-play.
- Speed limits.
- Wi-Fi/Ethernet only (phone).
- Cache budget: TV 2 GB default / phone 5 GB.
- Encryption: prefer.
- DHT, LSD, UPnP, NAT-PMP, uTP: all on.

### 1.7 Downloads (TorrDroid side)
- Add via: magnet or `.torrent` link opened from a browser, shared text, a `.torrent` file, paste, Files search, or ⬇ on a source.
- **Add sheet:** name, size, file list with a **search box** and type filters ("Videos only"), folder, "Start now" vs "Queue".
- **List:** Active / Completed / Queued. Each row shows progress, speed, ETA, peers/seeds.
  - **Seeder health:** green, amber or red, with a **stall warning** after 2 min at 0 B/s ("No one is sharing this. Try another source").
- **Detail:** Overview · Files · Peers · Trackers. Actions: pause, resume, delete (with or without files), share magnet, **Play** (streams instantly even mid-download).
- Rules:
  - Max active downloads (default 3); the rest queue.
  - Wi-Fi-only pauses on mobile data.
  - The foreground notification shows totals; each torrent has its own progress notification on phone.
- **Watch and wait:** a saved Files search re-runs every 6 h (WorkManager) and starts the best match when it appears.

### 1.8 Play on TV (phone → TV)
- The TV app advertises itself on the LAN with NSD (`_reel._tcp`) while it's running or in the background (a small service).
- Phone: **Play on TV** on any source → pick a TV (auto if there's only one) → send JSON `{source, title, tmdbId, season, episode, resumeMs}` over HTTP to the TV.
- **The TV streams it itself.** The phone isn't a relay, so the phone can sleep.
- Mini remote on the phone: play/pause, ±10 s, stop, subtitle toggle. These are small commands on the same channel.
- Pairing: the TV shows a 4-digit code the first time; the phone stores the TV's key. Only paired phones can send.
- Also accepts a **magnet sent from the phone**, so you can find something on phone and play it on TV.

### 1.9 One Launcher integration (TV)
- Reel handles the deep link `reel://title?tmdb=550&type=movie[&s=1&e=3]` and opens our title page with sources loading.
- In One Launcher TV's title page, add **"Play in Reel"** to "Watch on". This is a small change in the `~/one_launcher` repo, done in its own session since that repo is shared with other sessions.
- Reel publishes **Watch Next** programs for its own Continue watching (Android allows an app to write its own), so Google TV's system row can show them.

### 1.10 Settings
- **Playback:** preferred quality, auto-play best source, autoplay next episode, subtitle style, default audio language, external player.
- **Sources:** plugin repos (add/remove/refresh, per-plugin toggle and settings), addons (add by URL, reorder), Files-search providers (Torznab URL+key, Internet Archive, RSS), source priority order.
- **Engine:** profile, upload, limits, cache budget (with a "Clear cache" button showing GB used), network rules, ports.
- **Downloads:** folder, max active, seeding rules.
- **Devices:** paired TVs/phones, this device's name.
- **Account-free:** no login, no analytics, no ads. TMDB key built in from a local file (not in git).
- **Backup/restore:** export settings, repos and addons as JSON, so you can move them between phone and TV.

### 1.11 Consent and legal
- First run: a one-screen notice explaining that P2P streaming/downloading also uploads to other peers, and that you're responsible for what you play. This is a single acceptance stored locally.
- The app ships only legal default sources. No piracy repos are bundled or suggested.

---

## 2. Technical architecture

### 2.1 One APK, two UIs
- Single app, single package. The manifest declares both `LAUNCHER` and `LEANBACK_LAUNCHER` (TV banner), and `touchscreen` / `leanback` as `required=false`.
- At start: `UiModeManager.currentModeType == TV` (or the leanback feature) → `TvRoot` (Compose for TV, focus-driven). Otherwise → `PhoneRoot` (Material 3, touch).
- Both UIs share **all ViewModels, repositories and the engine**. Only the screens differ.
- ABIs: `arm64-v8a` (phone) + `armeabi-v7a` (TV). We build per-ABI APKs so each stays small.

### 2.2 Modules
```
torrent_app/
├─ core/                 Kotlin/JVM, no Android → fast unit tests
│   ├─ model/            Title, Episode, Source, TorrentInfo, StreamRequest…
│   ├─ magnet/           magnet parse/build, info-hash normalise (hex/base32)
│   ├─ rank/             SourceRanker, quality/codec parser ("2160p HEVC DV…")
│   ├─ stream/           ByteRange ↔ piece math, deadline window planner
│   ├─ addons/           Stremio manifest + stream JSON models, URL builder
│   └─ plugins/          Nuvio manifest models, result normaliser
└─ app/                  Android
    ├─ engine/           TorrentEngine (libtorrent4j), EngineService (FGS), alerts → flows
    ├─ server/           LocalStreamServer (NanoHTTPD) – Range → piece waits
    ├─ player/           Media3 ExoPlayer wrapper, resume, tracks, external player
    ├─ data/
    │   ├─ tmdb/         TMDB client (Ktor/OkHttp + kotlinx.serialization)
    │   ├─ sources/      AddonClient, PluginHost (QuickJS), SourceAggregator
    │   ├─ search/       Files-search providers (Torznab, Internet Archive, RSS, plugin.search)
    │   ├─ db/           Room: torrents, progress, repos, addons, history
    │   └─ prefs/        DataStore settings
    ├─ cast/             NSD discovery, pairing, send/receive, mini remote
    ├─ ui/
    │   ├─ theme/        tokens (colour, type, spacing, radius, motion) shared by both UIs
    │   ├─ phone/        screens: Home, Search, Title, Sources, Player, Downloads, Settings
    │   └─ tv/           screens: Home, Search, Title, Player, Downloads, Settings
    └─ App.kt / MainActivity.kt (picks TvRoot / PhoneRoot), DI (manual, no Hilt)
```

### 2.3 Libraries
| Need | Library |
|---|---|
| UI | Jetpack Compose (BOM 2026.09) + Material 3 · **androidx.tv:tv-material 1.1** for TV |
| Torrent | **libtorrent4j 2.1.0-39** (+ `android-arm`, `android-arm64`) |
| Local HTTP | NanoHTTPD 2.3.1 (tiny, proven for Range streaming) |
| Player | Media3 1.11 (exoplayer, ui-compose / ui, hls, datasource-okhttp, session) |
| JS plugins | quickjs-kt 1.0.15 |
| HTML parse for plugins | jsoup |
| Network / JSON | OkHttp + kotlinx.serialization |
| Images | Coil 3 |
| DB / settings | Room 2.8 + DataStore |
| Background | Foreground service + WorkManager |
| Build | AGP 9.1, Kotlin 2.4, Gradle 9.3.1 (already cached on this Mac), JDK 17 |

### 2.4 Data model (Room)
- `torrent(infoHash PK, name, mode STREAM|DOWNLOAD, savePath, totalBytes, selectedFilesJson, state, addedAt, lastActiveAt, tmdbId?, season?, episode?, sourceJson?)`
- `progress(key PK = "tmdb:550" or "tmdb:1399:s1e3" or "hash:…/idx", positionMs, durationMs, updatedAt, title, posterUrl, sourceJson)`
- `plugin_repo(url PK, name, version, lastUpdated)` · `plugin(id PK, repoUrl, name, version, filename, types, enabled, code, settingsJson)`
- `addon(url PK, manifestJson, enabled, order)`
- `search_provider(id PK, kind, configJson, enabled, order)`
- `paired_device(id PK, name, host, port, key)`
- `saved_search(id PK, query, category, lastRunAt)` (watch-and-wait)

### 2.5 Threads and lifetimes
- **EngineService** (foreground; `mediaPlayback` while playing, else `specialUse`) owns the session and the stream server. The UI binds to it.
- On Android 15+ `onTimeout`: save resume data and pause. Our sideload uses `specialUse`, which isn't time-limited.
- Resume data is saved every 60 s, on pause and on shutdown, and restored at start.
- TV: when playback stops and there are no downloads, the engine stops after 2 min idle, to save TV RAM.

### 2.6 Design
- Dark, cinematic, poster-led, in the same spirit as One Launcher TV, but with Reel's own tokens: `ui/theme/Tokens.kt` (colours, type scale, spacing 4-pt grid, radii, motion). No hard-coded values in screens.
- **TV:** 10-foot UI. Overscan-safe margins 48/27 dp, focus = scale 1.06 + bright outline, one primary action per screen, remembered focus per row.
- **Phone:** mobile-first at 375 dp, 48 dp touch targets, bottom sheets for actions.

---

## 3. Milestones

| # | Milestone | What "done" means |
|---|---|---|
| **M0** | Project scaffold | Builds one APK per ABI; opens on phone (touch UI) and TV (leanback UI); theme tokens; debug screen |
| **M1** | Engine | Paste a magnet → torrent downloads with progress; pause/resume/delete; resume after app restart |
| **M2** | Stream | Same magnet → ▶ plays in ExoPlayer within seconds; seek to the middle works; cache in app storage, trimmed after |
| **M3** | Title browsing | TMDB home rows, search, title page, seasons/episodes, where-to-watch (IN) |
| **M4** | Sources | Addon client + plugin host + aggregator/ranker; Internet Archive plugin; Play/Download from a source |
| **M5** | Player polish | Resume, continue watching, next episode, subtitles, tracks, external player fallback |
| **M6** | Downloads + Files search | Full TorrDroid feature set: add sheet with file search, queue, Wi-Fi only, stall warning, Torznab/IA/RSS search, auto-pick, watch-and-wait |
| **M7** | Play on TV | NSD discovery, pairing, send source/magnet, mini remote |
| **M8** | Integration + polish | `reel://` deep links + One Launcher "Play in Reel", Watch Next, backup/restore, settings complete |

Order rationale: M1–M2 prove the riskiest part (streaming on a 32-bit TV with little storage) before we build UI on top of it.

## 4. Testing
- **`core` unit tests (JVM):** magnet parsing, range↔piece math, deadline window planner, ranker, quality parser, addon/plugin JSON parsing.
- **Engine and stream tests on the real devices.** Legal test content only: Blender open movies (Big Buck Bunny, Sintel, Tears of Steel, all CC-BY) and Internet Archive items.
- **Network caveat:** this Mac is the work laptop on the office network (Netskope). We won't run BitTorrent traffic from it. P2P tests run on your phone or TV on your home network.
- TV checks: D-pad only (no touch), overscan, 32-bit ARM build, the storage check with low free space.
- Each milestone ends with an install on the device(s) plus screenshots, at a time you're not using them.

## 5. Risks and decisions
| Risk | Mitigation |
|---|---|
| TV storage too small for big files | Size gating + ranking + cache trimming. Fallback: a RAM-cache engine (TorrServer) on TV behind the same interface |
| ExoPlayer can't decode some audio (DTS/TrueHD) or video (DV profile) on TV | Codec-aware ranking; external player fallback; libmpv later |
| Plugins break or are malicious | Sandbox, timeouts, per-plugin network log, only trusted repos, kill switch |
| 32-bit TV RAM pressure | Soft engine profile on TV, a single active stream, idle shutdown |
| Office network blocks P2P | Test at home on the devices; never on the work network |
| Android FGS limits | `specialUse` for the sideload; `mediaPlayback` during playback; handle `onTimeout` |

## 6. Out of scope (for now)
iOS, desktop, Play Store release, user accounts or cloud sync, debrid services, Trakt sync (maybe later), creating torrents, RSS auto-download (beyond watch-and-wait).

---

## 7. Status

| Milestone | State (2026-10-19) |
|---|---|
| M0 Scaffold | ✅ Built: one app, phone + TV launcher entries, tokens, per-ABI APKs (~10 MB release) |
| M1 Engine | 🟡 Code done (`engine/TorrentEngine.kt`, `EngineService`): add magnet / stream / download / pause / keep / delete, resume after restart. **Not yet run on a device.** |
| M2 Stream | 🟡 Code done (`server/LocalStreamServer.kt`, `player/PlayerActivity.kt`): local Range server, piece deadlines, ExoPlayer, external-player fallback. **Not yet run on a device.** |
| `core` tests | ✅ 30/30 passing (magnets, ranges, piece planning, file picking) |

Next: install on the phone and the TV at home → test the three open movies (stream, seek, download, keep) → fix issues → M3.
