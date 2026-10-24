# One Launcher API (binding)

One Launcher (the user's own launcher: `com.kirtanya.one_launcher` on the phone,
`com.kirtanya.one_launcher.tv` on the TV) reads One Stream locally and opens it at a title.
The client is built against this page: **never rename a path, parameter, column or link.**
Additions are fine; bump `api` only for a breaking change. Code: core `launcher/` (LauncherApi,
LauncherLink, LauncherCaller, tests in `LauncherApiTest`), app `data/LauncherProvider.kt`,
`MainActivity.handleLauncherLink`.

## Who may call
The provider is exported with no permission (the two apps are signed with different keys, so a
signature permission can't work). Every `query()` checks the caller first:
- the calling uid's packages include `com.kirtanya.one_launcher` or `com.kirtanya.one_launcher.tv`, **and**
- that package is signed with One Launcher's certificate:
  `PackageManager.hasSigningCertificate(pkg, sha256, CERT_INPUT_SHA256)` with SHA-256
  `423fb6e70fe5a2f2a39f0b98eb8207b92e36b48700409b19a4e73208674f8c11` (`LauncherCaller.CERT_SHA256`).
- One Stream's own uid is also allowed (self-calls in tests).
- Anything else, or any lookup failure → `SecurityException`.
- One Stream's manifest `<queries>` lists both packages so the lookup works on Android 11+.

## ContentProvider
Authority `com.kirtanya.reel.launcher`. insert / update / delete throw (actions go through `call()`, Part 2). Parameters
are URI query parameters; projection, selection, selection args and sort order are ignored.

| URI | Rows |
|---|---|
| `content://com.kirtanya.reel.launcher/info` | 1 row: `api` (int, = 1), `version` (string, versionName) |
| `…/search?q=<text>&limit=<n, default 20>` | TMDB multi search (the app's Search screen), films + shows, TMDB's order (best first). Empty `q` → empty cursor. |
| `…/continue?limit=<n, default 20>` | The app's Continue watching row (same selection), newest first, with progress. Local only. |
| `…/watchlist?limit=<n, default 40>` | My list, newest first. Local only. |
| `…/title?type=movie\|tv&imdb=<tt…>` or `&tmdb=<id>` | 0 or 1 row. `tmdb` wins when both are given; with only `imdb`, TMDB find (falls back to the other type if TMDB files it differently). |

`limit` is capped at 200; a missing / bad value uses the default. An unknown path → empty cursor with
the row columns. Network queries (`search`, `title`) run on the binder thread with a hard 6 s
deadline; on timeout or any failure the cursor is empty (never an exception). Torrent files with no
TMDB info are left out of `continue`.

### Row columns (search / continue / watchlist / title), in this order, always all present
| # | Column | Type | Value |
|---|---|---|---|
| 0 | `key` | string | `movie:<tmdb>` / `tv:<tmdb>`; continue rows for an episode `tv:<tmdb>:s<N>e<M>` (the episode the card plays: for "Up next" the next one) |
| 1 | `type` | string | `movie` or `tv` |
| 2 | `tmdb` | long | TMDB id |
| 3 | `imdb` | string / null | IMDb id when known (title rows, continue rows that recorded it); null for search / watchlist |
| 4 | `title` | string | |
| 5 | `subtitle` | string / null | continue episode rows: `S1 · E3 · Episode name` (or `S1 · E3`); otherwise the year |
| 6 | `year` | int / null | |
| 7 | `rating` | real / null | TMDB vote average (null on continue / watchlist rows: not stored locally) |
| 8 | `poster` | string / null | `https://image.tmdb.org/t/p/w342/…` |
| 9 | `backdrop` | string / null | `https://image.tmdb.org/t/p/w780/…` (continue episodes without a backdrop: the episode still) |
| 10 | `progress` | real | 0..1 for a continue row being resumed; -1 otherwise (incl. "Up next") |
| 11 | `remaining_min` | int | minutes left (≥ 1) for a resumed continue row; -1 if unknown |
| 12 | `link` | string | continue rows `onestream://resume/<key>`; all others `onestream://title/<type>/<tmdb>` |

## Deep links
`ACTION_VIEW`, category `DEFAULT`, into `MainActivity` (singleTop; `onNewIntent` handles them too).
Start with `FLAG_ACTIVITY_NEW_TASK` from the launcher. The manifest lists the hosts (`title`, `imdb`,
`resume`, `search`, `play`, `cast`); a new host needs a manifest entry too.

| Link | Does |
|---|---|
| `onestream://title/movie/<tmdb>`, `onestream://title/tv/<tmdb>` | Opens the title page (phone and TV UI) |
| `onestream://imdb/<tt…>?type=movie\|tv` | Finds the title on TMDB (loading state, then the title page; "Couldn't load this title" + Try again on failure). No `type` → movie first. |
| `onestream://resume/<key>` | What tapping that Continue watching card does (resume in the player at its position; "Up next" opens Sources for that episode). An older episode key or a bare `tv:<id>` matches the show's card. Not in history → the title page. |
| `onestream://search?q=<text>` | The Search tab with that query (URL-encoded; `+` and `%20` both mean a space) |

A malformed `onestream://` link with one of those hosts just brings the app to the front.

---

## Part 2: the title page inside the launcher
One Launcher draws a title's whole page itself; One Stream works in the background and only its
player comes to the front. Same caller check on every query and action.
Code: core `launcher/LauncherPage.kt` (rows, tokens, languages, states; tests in `LauncherPageTest`).

### More queries
| URI | Rows |
|---|---|
| `…/details?type=movie\|tv&tmdb=<id>` | 0 or 1 row (6 s deadline) |
| `…/episodes?tmdb=<id>&season=<n>` | one row per episode, by number (6 s deadline) |
| `…/sources?type=movie\|tv&tmdb=<id>[&season=&episode=]` | the Sources screen's own search: every enabled addon and plugin (built-in + the user's), ranked by its ranker, same order. Shows need `season` + `episode` (else empty). Waits up to 25 s, then answers with what has arrived. |
| `…/downloads` | the Downloads tab's downloads (not the stream cache). Only while the engine runs (it starts with the app, a stream, a download or `prepare`); otherwise empty — a query never starts P2P. Direct-link downloads (Android's download manager) aren't listed. |

`details` columns: the 13 row columns above, then
`overview` (string\|null), `genres` (string\|null, "Drama, Fantasy"), `runtime_min` (int\|null),
`seasons` (int\|null: regular seasons of a show, null for films), `status` (string\|null, TMDB: "Released",
"Returning Series", "Ended"…), `in_watchlist` (0/1), `resume_key` (string\|null: the title's Continue
watching key, as in `/continue`, usable with `onestream://resume/<key>`), `resume_progress` (real, -1 when
not being resumed, incl. "Up next").

`episodes` columns: `season` (int), `episode` (int), `name` (string), `overview` (string\|null),
`still` (string\|null, w300 URL), `air_date` (string\|null, yyyy-MM-dd), `aired` (0/1: by air date, else by
the show's latest aired episode), `progress` (real: 0..1, 1 when watched, -1 never played),
`link` (string: `onestream://play?source=…` for the best source if the launcher already ran `/sources`
for that episode in this process, else `onestream://title/tv/<tmdb>`). Episodes don't search sources
themselves (that is one 25 s search per episode).

`sources` columns: `token` (string, opaque, valid for this One Stream process; up to 500 kept, least
recently used dropped), `name` (release name: the first line of the description), `provider` (addon /
plugin name), `quality` (string\|null: "4K HDR", "1080p", … — null for service links), `size_bytes`
(long, -1), `seeds` (int, -1), `kind` ("torrent"\|"direct"\|"service"), `languages` (string\|null: read
from the release text — "Hindi, English", "Dual audio", "Multi"; One Stream doesn't track languages
otherwise), `best` (0/1: the Sources screen's Best match, the first non-service source), `play_link`
(`onestream://play?source=<token>&type=…&tmdb=…[&season=&episode=]`), `downloadable` (0/1: torrents and
direct links, like the app's Download button), then (added later, always at the end) `hash` (string\|null:
the torrent's btih info-hash, 40 lowercase hex; null for direct / service), `file` (int: the torrent's file
index, -1 when unknown or not a torrent), `url` (string\|null: a direct source's URL; null otherwise).
`hash` / `file` / `url` are what a cast link's hint carries (Part 3).

`downloads` columns: `key` (info-hash), `title`, `subtitle` (string\|null, "1.2 GB of 4.0 GB"),
`progress` (0..1; 1 when done), `state` ("queued"\|"downloading"\|"paused"\|"done"\|"failed"),
`size_bytes` (long, -1 unknown), `play_link` (`onestream://play?torrent=<info-hash>`).

### Actions: `ContentResolver.call(Uri("content://com.kirtanya.reel.launcher"), method, arg, extras)`
Every one returns a Bundle `{ok: Boolean, message: String?}` and never throws for bad input
(a caller that fails the check gets `SecurityException`). `type` / `tmdb` extras may be int, long or string.

| method | extras | does |
|---|---|---|
| `watchlist_add` | `type`, `tmdb` | adds to My list (fetches the title from TMDB, 6 s) |
| `watchlist_remove` | `type`, `tmdb` | removes from My list |
| `download` | `token` | the app's Download button for that source (torrent → engine download with its notification; direct → Android's download manager). Service links: ok=false. |
| `prepare` | `token` | torrents: the player's own stream start in the background (stream mode, the opening pieces first), no player; if nobody plays it, it stops after 5 min without reads, like a stream handed to an external player. Direct links: ok=true, nothing to do. Service links: ok=false. Returns at once ("Preparing"). |
| `cancel_download` | `key` (info-hash) | the Downloads tab's Delete (removes the download and its files) |
| `stop_engine` | none | TV "Speed up": if no One Stream screen is open, nothing is streaming and no download is transferring, saves resume data, stops the engine's foreground service and ends One Stream's process (the next launch starts fresh). Otherwise `ok=false` with the reason ("One Stream is open", "Something is playing", "2 downloads are running"); running downloads are never paused or stopped by this call. |

### Play link
`onestream://play?source=<token>[&type=&tmdb=&season=&episode=]` → the player straight away with that
source (what picking it on the Sources screen does; service links open their app). Unknown token
(One Stream restarted) → the title page from `type` + `tmdb`.
`onestream://play?torrent=<info-hash>` → plays that download (Downloads tab's Play); if the engine
hasn't loaded it yet, the Downloads tab. Unknown hashes never start a new torrent (any app can send links).

---

## Part 3: Play / Download on TV (cast links)
One Launcher on the **phone** shows a title's sources and offers "Play on TV" / "Download on TV". It
sends an Android TV remote **app link** (the remote protocol's app-link launch → `ACTION_VIEW` of the URI
on the TV) to One Stream on the TV. Both links are exported VIEW filters with `DEFAULT` + `BROWSABLE`.
They work on the phone too, but are meant for the TV. Code: core `launcher/CastLink.kt` (`CastRequest`
parse / build, `CastChoice` hint matching + choice; tests in `CastLinkTest`), app `ui/sources/CastScreen.kt`,
`CastDownloadActivity`, `data/CastDownloads.kt`.

| Link | Does |
|---|---|
| `onestream://cast/play?type=movie\|tv&tmdb=<id>[&season=N&episode=M][&hash=<btih>][&file=<idx>][&url=<direct url>]` | `MainActivity`: a "Starting on your TV…" screen (title, poster, Cancel; Back cancels), runs the app's own source search, picks a source (below) and opens the player as the Sources page would (resumes from history). Nothing found → "Couldn't find a source" + **Open Sources** for that film / episode. The screen then becomes the title page (Back from the player lands there). |
| `onestream://cast/download?…same parameters…` | `CastDownloadActivity`: invisible (Theme.NoDisplay, own task, no history, not in Recents), finishes at once, so the TV keeps showing what it was showing (the player keeps playing). `EngineService` searches (25 s cap, then what has arrived), picks (below) and starts the app's own Download (with its download origin, so Change source works). Toast: "Downloading <title> on this TV" or why not ("Couldn't find a source to download …"). |

- Shows need `season` + `episode` (else the link is malformed); films ignore them. `type` and `tmdb` are required.
- `hash`: 40 hex or 32 base32 (normalised to lowercase hex). `file`: ≥ 0. `url`: http(s) only. Bad hint
  values are dropped; the link still works without them.
- **Security:** the hint never plays or downloads anything by itself. It only selects among the sources
  the TV's own search returned (the user's own addons + plugins); an unknown hash / URL is ignored.
- Choice, play: the search result matching the hint (same hash + same file; a hint or source without a
  file index matches by hash; another file of the same torrent never does; direct links by exact URL),
  if this device can stream it (TV storage limit); otherwise the Sources page's Recommended pick (one
  that fits; if none fits, the ranker's winner). A hinted source starts as soon as it arrives; without
  one the choice waits for the whole search (25 s cap). Services are never picked.
- Choice, download: the hinted source if downloadable (torrent, or direct with a URL), else the ranker's
  best downloadable source. Size doesn't matter.

