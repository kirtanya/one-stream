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
Authority `com.kirtanya.reel.launcher`. Read-only (insert / update / delete throw). Parameters
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
Start with `FLAG_ACTIVITY_NEW_TASK` from the launcher.

| Link | Does |
|---|---|
| `onestream://title/movie/<tmdb>`, `onestream://title/tv/<tmdb>` | Opens the title page (phone and TV UI) |
| `onestream://imdb/<tt…>?type=movie\|tv` | Finds the title on TMDB (loading state, then the title page; "Couldn't load this title" + Try again on failure). No `type` → movie first. |
| `onestream://resume/<key>` | What tapping that Continue watching card does (resume in the player at its position; "Up next" opens Sources for that episode). An older episode key or a bare `tv:<id>` matches the show's card. Not in history → the title page. |
| `onestream://search?q=<text>` | The Search tab with that query (URL-encoded; `+` and `%20` both mean a space) |

A malformed `onestream://` link just brings the app to the front.
