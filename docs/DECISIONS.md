# Decision log

Newest first. Each entry: date, decision, why, evidence. Add one whenever something is decided
(product, engine, tooling); link the commit or spec section that carries it.

---

**2026-11-06 · TV clean redesign (0.16.0): side rail, settings panel, 50/50 title page, source panel**
Decision (user): the TV app was too busy ("everything is highlighted and nothing is in focus"). Top bar → icon-only
left rail; Settings → right-side panel over the screen (changes show live); "On your services" removed (TV + phone);
home card shape × size setting (default Landscape Small); offline state gets "View downloads"; the title page is 50/50
(artwork right, content left) with a film's sources on the page and a series' episodes opening a source panel, so the
Sources page is no longer a step on TV. Spec: `docs/TV_CLEAN_REDESIGN.md`. Built by two agents (shell 8668021, title
b4dba11), merged a90504c. Cut from the TV title page: ratings/reviews rows, extras, browse by genre, per-episode story.

---

**2026-11-06 · Samsung phones use the posix disk backend, not mmap (0.15.3)**
Decision: `DiskBackend.forDevice` picks POSIX on Samsung even on 64-bit. Why: on the S24, every launch resumed the two
downloads in the app's external folder; ~3 s later Samsung's MediaProvider FUSE daemon aborted
(`Check failed: active_nodes_.find(node)`, pf_open) and Android killed One Stream with it, so the app closed on every
open (19 MediaProvider deaths, 21 One Stream deaths in one log). Evidence: with 0.15.3 (POSIX) the same downloads write
for 100 s+ with no crash. The Pixel keeps mmap (no such crash there). Test: `core/.../stream/DiskBackendTest.kt`.

**2026-11-06 · Release builds signed with One Stream's own key (rotated from the debug key)**
Decision (user): stop shipping with the Android debug key, which Play Protect treats as suspicious. New key
`CN=One Stream` (RSA 4096) in `~/.one-stream-signing/` (keystore + `signing.properties` + `debug-to-release.lineage`;
never in git, must be backed up). `tool/build_release.sh` re-signs both APKs with APK Signature Scheme v3 key
rotation (debug → release, `--rotation-min-sdk-version 28`), so installed copies take it as a normal update and
keep their data; no uninstall. Gradle still signs with the debug key first, so an APK built without the script
won't install over a rotated install.
Evidence: 0.15.1 re-signed; Samsung S24 and Pixel 8 updated in place (firstInstallTime kept; `dumpsys package`
shows the new signer current and the debug key as past); Pixel watch history and Continue watching read back
identical after the update. TV not updated yet (not reachable from the office).

---

**2026-11-04 · Change source opens inside the player, as a panel page**
Decision (user): the Change source button / Settings row no longer leave the player for the Sources page; a panel
page (like Subtitles) lists the sources best first with quality, size, provider, seed health or "Direct", the playing
one marked. Picking one hands over at the current position (new player, same as the Sources page's hand-over);
"All sources…" keeps the old full page. Same search + ranking as the Sources page (shared cache), row text in
`core/player/PlayerSources` (tested). A torrent picked on the phone while a direct link plays opens the full page
instead, because the phone's network-rule question lives there.

---

**2026-11-04 · Smooth TV: measure on the TV, optimise for this one device (docs/SMOOTH_UI_PLAN.md)**
Decision (user): One Stream should feel as smooth as Netflix / JioHotstar on the Acer R4 and only needs to be
tuned for this TV and the Pixel 8. Findings and fixes (82e0f8e):
- GPU (Mali-G31) was the bottleneck on Home: 61 ms/frame. Three full-screen gradient scrims cost ~25 ms; now
  baked into one small bitmap (`HeroScrims`). Settings (no hero) was already 8 ms.
- The TV runs three accessibility services (Button Mapper, One Launcher x2), so Compose diffed its semantics tree
  every frame (20-50 ms main thread). `ComposeA11y` empties Compose's cached service list for our window only
  (system setting and services untouched; nothing reads One Stream's screen).
- Sideloads ran mostly interpreted: `tool/install_tv.sh` compiles with `-m speed` (row build 740 → 190 ms).
- Result on the D-pad route (`tool/tv_jank.sh`): janky frames 100 % → 17 %, median 105 → 30 ms.
Rejected: rendering at 720p via game-mode downscaling (would also scale the player's video surface).

---

**2026-10-31 · English-only subtitles; ±20 s subtitle delay; Subtitles button = every subtitle setting; volume & dialogue boost; no "Where to watch"**
Decisions (user):
- Subtitles: only English is listed or attached. Addons (OpenSubtitles v3) answer every language and can't be asked
  for one, so `Subtitles.searchDetailed` keeps English (up to 6 tracks, `PER_LANGUAGE`); the source's own files too;
  tracks inside the file: English, or unlabelled ones when there's no English (`Subtitles.embeddedToList`).
- Subtitle delay ±20 s (was ±5 s), 0.25 s steps; holding ←/→ moves 1 s at a time after ~½ s.
- TV Subtitles button: one page with the tracks (focus on the one showing), then style, delay, search again.
- Night mode / voice boost removed: Android's DynamicsProcessing was refused on the TV. Replaced by the player's own
  processing in the audio sink (`core/player/AudioBoost`, `BoostAudioProcessor`): Volume boost Off / Low +3 / Medium +6 /
  High +9 dB with a soft limiter, and Dialogue boost (centre / mid lifted with a 2.5 kHz presence lift, the rest −3 dB).
  Applies at once to decoded audio; Dolby / DTS passed through to the TV only when a boost was on at the start.
- Title pages: "Where to watch (in India)" removed, TV and phone.

---

**2026-10-31 · TV player: Back twice to exit**
Decision (user: Back was easy to hit by accident mid-film; then: "two Back presses", not OK). With the controls hidden,
the first Back shows "Press Back again to exit" at the bottom for 3 s; a second Back while it shows leaves. Holding Back
never leaves. Open controls / panels still close on Back first; an error screen still leaves on Back. Phone unchanged.
Logic: `core/input/PlayerExitGuard` (+ tests), chip: `PlayerOverlays.exitChip`.

---

**2026-10-31 · Black & white, pure black canvas (TV always, phone default); Netflix-style TV top bar and title page**
Decision (user): the TV felt grey and blue, with the logo sitting oddly next to the tabs. Now:
- `AccentPreset.Mono` = a hand-made black/white scheme (`AccentState.mono`): #000 canvas, neutral greys, white accent.
  Default on the phone (other colours stay selectable), the only look on the TV (no Appearance page there). No poster glow.
- TV top bar: text tabs, selected white + underline, no pill, no logo. Seasons use the same tabs.
- TV buttons: focused = solid white + an outer ring (a ring on a white button was invisible; the ripple washed it grey).
- TV title header sits lower (64 dp), facts line under the logo, the backdrop goes to 90 % black once focus leaves it.
Evidence: Netflix moved its TV nav to a top bar in 2024 (macrumors.com/2024/06/06/netflix-tv-app-redesign); renders in
`app/build/screens/tv/` (home, title-film, title-show, title-show-episodes, settings).

---

**2026-10-31 · New app icon family "Glass O, centred" (shared with One Launcher, One Watch, One Canvas)**
Decision (user picked it on the comparison page): One Stream's icon is a blue tile with a thin near-white glass ring and the
play triangle in it. Source of truth is `~/one-logos` (`generate.py` writes the SVGs, `make.sh` renders PNGs with WebKit).
- Adaptive icon layers are now bitmaps (`drawable-nodpi/ic_launcher_bg/fg/mono.png`, 432 px): vectors can't blur the glow.
  The foreground keeps the ring inside the 66 dp safe zone; `BrandMark` scales it back up to the tile.
- TV banner (`drawable-xhdpi/banner.png`, 640 × 360) and `docs/logo.png` come from the same folder. The `brandBanner` /
  `brandLogo` renders in `ScreenRenders.kt` are not used for the files any more.

**2026-10-31 · TV engine stops itself when idle; One Launcher warms it before Play**
Decision (user goal: a faster TV). The TV's engine ends after 10 min with nothing in use (`StopEngine.idleCheck`,
`EngineIdle.watch`); One Launcher's TV title page sends `warm_engine`. `stop_engine` now also keeps downloads waiting
for the night window (the night alarm can't restart a dead engine from the background).
- Why: on the 2 GB Acer R4 (≈420 MB available, 560 MB of swap in use) the idle engine kept One Stream at 82 MB as a
  Perceptible process all day, even under YouTube. Not merging One Stream into One Launcher for the same reason: the
  home process would carry that memory permanently.
- Evidence: cold engine start 1.1 s (process ~0.7 s + engine ~0.4 s), warm 0 s (SourcesProbe `--ez warmEngine true`).
  Tests: `StopEngineTest`.

**2026-10-31 · Player: up-front Change source / Audio, Next chip, grouped settings, English first**
Decision (user request). Logic + tests: `core/player/TrackPick.kt` (`TrackPickTest`); UI: `PhoneControls`, `TvControls`,
`PlayerKit` (`NextChip`, `SettingsPanel`), `PlayerActivity` (`applyLanguagePicks`, `onNextChip`).
- Change source and Audio are buttons in the band / strip (Audio only with 2+ audio tracks, Change source only for a TMDB
  title); the panel rows stay. TV puts them first in the strip (D-pad: left of back 10 s). The old Next button became a
  very small "Next · S1 E4" chip just above the title (phone: its 48 dp box grows up, so the touch rule holds; portrait
  gives the title block its own line, it had none). It shows for any episode with a next one; a tap before the background
  lookup finished starts the lookup and plays the moment it is ready (same path as Up next's Play now).
- Settings panel: Playback (speed, auto-play next, sleep timer), Audio (track, delay, night mode, voice boost), Subtitles
  (track, style shortcut = the four presets, delay, search again), Picture, More. Every row shows its value.
- English first: audio = remembered language (TitlePrefs) else English, never commentary / audio description, beats the
  file's "default" flag; subtitles = remembered language / Settings language (English by default), full set before SDH
  before forced-only, Off stays Off, a language that isn't there means none. Applied on every track change until the
  user picks by hand. ExoPlayer also gets `setPreferredAudioLanguage` so the first audio is already right.
- Focus is outline only in the player: no 1.12 scale, no soft fill on the round buttons, panel rows and crop buttons.
- TV Next chip sits on a dark glass pill (`PlayerColors.CHIP_SCRIM`): 12 sp white on a bright picture fails the contrast
  rule and the strip has no shade; the baseline was not grown.
- The settings panel's ScrollView now also clips with a plain rectangle: the rounded outline clip is ignored by software
  canvases, so the design checks saw rows from below the panel drawn over the strip.

**2026-10-31 · TV lag: outline-only focus, no poster-following theme, no idle animation, one click per held OK**
Decision (user report: app laggy when navigating, worst on the Acer R4). Done in one pass:
- Focus = outline only, phone and TV: no scale, no accent glow (`focusCard`, `ReelButton`; `Motion.focusScale` /
  `posterFocusScale` deleted). TV page transitions and Settings page slides are off; the TV title page shows one
  backdrop (no 8 s cross-fade); the TV title page's dim layer switches at once; Ken Burns zoom deleted.
- A held remote OK fires one click (or the long press and no click): `core/input/OkKeyGuard` + `Modifier.okGuard`
  (in `focusCard` and `ReelButton`). Cause: Compose clicks on key-up, so the repeats + release of the press that opened
  a page landed on the page's focused element. Tests: `OkKeyGuardTest`, `OkHoldTest` (reproduced the double click
  before the fix).
- Continue watching cards open the title page (shows: that episode's season preselected); long-press sheet unchanged.
- **Poster-following Material theme removed** ("Match what's on screen" setting, `MediaAccent`, `adaptive`, the
  600 ms scheme cross-fade). The scheme is the chosen preset only (Tonal spot, system accent by default).
  Why: measured on TV Home (Robolectric, composable executions via `CompositionTracer`, one focus move right):
  - the seed changed on every focus move (poster colour → `AccentHost` rebuilt two schemes);
  - 35 978 composable executions over the following 3.2 s (52 of 200 frames recomposing, up to 904 in one frame)
    vs 1 672 with the switch off, and 226 after the change (idle: 0 either way);
  - one `dynamicColorScheme` call = 1.4 ms on the Mac JVM (warm), ×5-10 on the ARMv7 TV = 7-14 ms, twice per change
    (target + player "night" scheme) → a dropped frame or two on every focus settle, then 36 frames of full-screen
    recomposition from the animated scheme.
  Kept: the poster-coloured Afterglow light on TV Home / Title / Sources, now read in the draw phase
  (`AmbientGlow(color: () -> Color)`) so its 600 ms animation no longer recomposes the screen.

**2026-10-28 · Debrid fallback checks links before switching (Debridio kept loading)**
Decision (user report: Debridio "doesn't load properly / keeps loading"). Logic + tests: `core/player/Placeholder.kt`
(`PlaceholderFallback.candidates`, `ReadyCheck`, `Readiness`; `PlaceholderTest`). Player: `PlayerActivity.findAlternative`.
Why: on a real search (Avengers: Age of Ultron, TV) Debridio's Scraper AD listed ~700 links with no cache markers; of the
first 8 opened, 7 redirected to `static.debridio.com/…/downloading.mp4` and the one cached file was AV1 (no decoder on the
TV). The fallback picked one unknown link at a time, each a whole new player that played the clip again, up to 3 hops
plus a 15 s search each → a minute of loading, then Not ready.
Also decided (Claude):
- After a placeholder, up to 8 candidates (cached first, ranker order; AV1 skipped where `avoidAv1`) are checked with one
  1-byte ranged request each that follows the redirects, 3 at a time, ≤ 12 s; the best-ranked ready one is played. Not-ready
  ones are marked (Sources page: "Fetching…", listed last). Opening an uncached link makes the addon start fetching it, as
  playing it did, so only the few that would have been tried are opened.
- Telemetry `placeholderCheck` (candidates, checked, notReady, failed, found, ms). Hosts / links never logged.
Evidence: core tests (20/20 PlaceholderTest); Mac check of Debridio links (redirect ≈ 1.7–2.3 s each).

**2026-10-24 · Artwork for raw files: match the release name on TMDB**
Decision: Continue watching entries played from a magnet / .torrent / download / link (no TMDB id, "file:<hash>:<idx>"
or "url:…" keys) get their title's artwork, name and ids from TMDB, so the card shows a still / backdrop instead of the
letter tile, and subtitles (IMDb id) and Up next work for them.
- Name → title (`core/artmatch/ReleaseName`): last path segment, extension, leading [tags] / www.site.tld; the first
  SxxEyy / 1x02 / "Season 1 Episode 2" (or a lone S01 / "Season 2" = season pack) ends the title; then the first
  quality / source / codec / audio / release token; the year is the last 1900…next-year number before that (so
  "Blade Runner 2049", "2001 A Space Odyssey 1968", "1917 2019" come out right); "(2018)" / "(US)" survive the cut at
  the first bracket; trailing US / UK / AU / NZ / CA = a country hint. Nothing similar existed (QualityParser /
  SourceFacts read tags, not titles).
- Scoring (`TitleMatcher`, documented there): 0.70 × title similarity (normalised edit distance, TMDB title or
  original title, leading article ignored) + 0.20 × year (same 1.0, ±1 0.6, unknown 0.4, further = out) + 0.10 ×
  log popularity, country hint +0.05 / −0.10. Accept only similarity ≥ 0.85, score ≥ 0.80 and no other title within
  0.03 (no year + two popular namesakes → no match). Search/movie or search/tv by whether the name has a season;
  one retry with the year. Prefer no match over a wrong one: the letter tile stays.
- Applying (`ArtMatch.apply`): title, year, tmdbType / tmdbId, imdbId (details), poster / backdrop, and for an episode
  the episode, "S1 E2 · name", the TMDB still and the next episode. Key, titleKey and source never change (resume and
  per-title prefs hang off them). A season pack without an episode number gets artwork + name only (a TMDB id without
  an episode would send "tt…:null:null" source / subtitle requests).
- When: a background pass (app start + 4 s, and when the Continue row shows a letter tile; passes ≥ 60 s apart,
  ≤ 24 TMDB calls a pass, 300 ms apart, stops at the first network error) and at play start (alongside finding
  peers, waited ≤ 1.5 s; a later answer still fills the history entry). Answers incl. "no match" cached 7 days
  (files/art-match.json, ≤ 400); network errors cache nothing. Telemetry: `ev=artMatch result=matched|none|error
  why score ms via cached calls type title` (clean title only, never the raw name / path).
- Touches outside the player/UI (small, additive; One stream session's area): `WatchHistoryStore.enrich(key)` (in
  place: same order, position, updatedAt) and `save` keeps an existing match when a bare Playback for the same key is
  saved (`ArtMatch.keepMatch`); `TmdbRepository.matchCandidates`; `TmdbPaths.searchTitle`; `Watched.fromHistory`
  skips matched file entries (no ticks under "file:" keys, as before).
Also: the Continue card's detail line moved textTertiary → textSecondary (over a bright focused backdrop on the TV
it was 3.2:1; the tv/home baseline entry for it is pruned).
Evidence: ArtMatchTest (32 cases: ~40 real-looking names, scoring, resolver with fakes, cache); renders
`home-art-matched` (phone + TV) vs `home`; live TMDB samples below.
Live TMDB from the Mac (real resolver, 10 names): Tears of Steel 2012 → movie 133701 (0.92), Avengers Infinity War
(2018) [BluRay] → 299536 (0.99), Game.of.Thrones.S01E01 → tv 1399 S1 E1 "Winter Is Coming" + still, next S1 E2 (0.88),
The.Office.US.S02E03 → tv 2316 (US, not the UK one) "Office Olympics" (0.93), Sintel.2010 → 45745 (0.93),
Interstellar.2014 → 157336 (0.99), Breaking Bad 1x02 → tv 1396 S1 E2 (0.88); no match, as intended:
big_buck_bunny_1080p (no year, several TMDB entries of that name → ambiguous), The.Lion.King.1080p (1994 vs 2019),
Random.Home.Video.2019 (no results). 0.3–2 s per name with 1–3 calls.

---

**2026-10-24 · Edge layout: one line through the camera hole (replaces the flank as the default)**
Decision (user): everything on the camera side in ONE vertical line in the cutout strip, centred on the hole's
horizontal middle: back at the top, the hole, then back 10 / play / forward 10 from the hole's bounding rect + 8 dp,
8 dp apart. On the user's 360 dp-tall screen that only fits by (a) letting the line run down over the seek bar's
touch area (the buttons sit on top and take their own 48 dp; the bar keeps the rest of its 48 dp, stopping 8 dp above
its hairline) and (b) shrinking the spacing (to ≈ 4 dp). The time on that side moves in past the line. Still too tall
(or back doesn't fit above the hole) → the flank arrangement below; no cutout → the same line on the left edge,
transport centred. 48 dp buttons are kept on screen (≈ 4 dp off the hole's centre in a 40 dp strip).
Evidence: `PhoneLayout.holeColumn` + PhoneLayoutTest (roomy, user's phone, exact fit, short-screen fallback);
renders `phone_edge_cutout_left/right`, `phone_edge_130`; PlayerDesignRules pass.

---

**2026-10-24 · Edge layout: skip buttons flank the camera hole, in the cutout strip**
Decision (user follow-up after 0.11.1): back 10 directly above the camera hole and forward 10 directly below it,
centred on the hole's horizontal middle (DisplayCutout.boundingRects), ≥ 8 dp clear of its rect; play / pause stays
at the vertical centre just past the cutout inset, back at the top of the strip. Neither skip may overlap play's
rows (they'd share touch area), so they move further out when 8 dp isn't enough; a 48 dp button is wider than the
≈ 40 dp strip, so it is kept on screen (its middle ≈ 4 dp off the hole's). No hole / no room → the previous stack.
The phone player window now asks for LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES (API 28, same as ALWAYS for a
short-edge hole); targeting Android 15+ already drew there, so Classic (padded by the cutout inset) is unchanged.
Evidence: `PhoneLayout.flankHole` + PhoneLayoutTest; renders `phone_edge_cutout_left/right`; PlayerDesignRules pass.

---

**2026-10-24 · Phone player: Edge layout (opt-in, Settings panel → Layout: Classic / Edge)**
Decision: a second phone player layout built around the Pixel 8's camera hole, chosen per user in the player's
settings panel (PanelToggle "Layout"), remembered in PlaybackPrefs.phoneLayout, default Classic, applied at once.
- Cutout side (from the display-cutout insets; left when there is none): back at the top, back 10 / play / forward 10
  stacked on the screen's middle just past the cutout's safe inset. Every other button (next, send to TV, lock,
  subtitles, settings) in one column on the opposite edge. Title + "Ends at" centred at the top between them.
- Seek bar: SeekStrip `edge` (TV hairline on the very bottom edge, played part only; touched: thickens, thumb, halo,
  time bubble) in a 48 dp touch area; elapsed / remaining just above its ends. Columns never reach into that area.
- Low shades (SHADE_BAND tokens) along the edges that carry controls, so the "10"s, title and times pass contrast.
- Portrait always shows Classic (the edge strips only exist sideways); turning the phone switches live.
- One set of views is re-parented between the band and the edge frame, so listeners / refresh don't fork.
Why: the user wants the controls in the empty strips at the screen's edges, off the picture.
Evidence: `core/player/PhoneLayout` + PhoneLayoutTest; renders `phone_edge_*` (hole left / right, scrub, 130 %, panel,
portrait); PlayerDesignRules `phoneEdge*` pass with no baseline growth. Limit: the bottom few dp are Android's
gesture-nav zone; a drag starting right on the edge may bring up the system bars instead of seeking (start a little higher).

---

**2026-10-24 · Ratings + reviews: IMDb pills on posters, a ratings row and Reviews on title pages**
Decision (user-approved design, mockup `~/one_launcher/docs/mockups/title_page.html`, in One Stream's own style):
- Posters (rows, grids, search, wide cards): an IMDb pill (brand yellow / black) top-left; nothing when IMDb has no
  rating. The TMDB ★ under posters is gone (meta line is now "Film · 2024"), so a card never shows two different
  scores; the meta line moved from textTertiary to textSecondary (fixes the baselined light-mode 4.2:1 contrast).
- Title page (phone + TV): eyebrow (FILM / SERIES · year or run "2016–2023" / "2016–present" · runtime or seasons ·
  certificate · 2 genres), logo / name, ratings row (IMDb pill · Rotten Tomatoes tomato / splat · Metascore square;
  only what exists; TMDB's own ★ labelled "TMDB" only when none exist), plot, the existing actions, then
  **Reviews · N** (TMDB, sideways cards; a card opens the whole review: phone bottom sheet, TV right panel with ↑ ↓
  scrolling and Back). Everything else (where to watch, episodes, extras, cast, franchise, more like this) is kept.
- Series: season picker + episode list as before, each episode now "E9 · Name", runtime · full air date, 2-line
  story (TV cards too) and its own small IMDb pill from OMDb's season endpoint. For series, Reviews come after the
  episodes (watching is the main job there); for films, straight under the actions as in the mockup.
Data (all quiet on failure; no screen waits for ratings; `data/RatingsRepository.kt`, logic in `core/ratings/`):
- Poster IMDb: TMDB `/external_ids` → IMDb id → Cinemeta `imdbRating` (no key, no daily limit). Only for posters
  being composed (lazy rows = on screen), max 4 at once on the phone, 2 on the TV; a poster scrolled away gives up
  its queue place; the same title asked twice shares one fetch. Cached on disk (`files/ratings-cache.json`) per
  TMDB id: hit 7 days, miss 2 days; the IMDb id is kept forever (a refresh = one Cinemeta call). Pill appears at
  once when cached, else fades in. Done on the TV too (2 at a time is light; cached for a week).
- Title page: OMDb (`omdb.key` → `BuildConfig.OMDB_KEY`, like tmdb.key) per IMDb id, 7 days; if OMDb fails, Cinemeta's
  IMDb stands in for a day. Season episode ratings: one OMDb call per (show, season) shown, 7 days. OMDb is never
  called for posters (1,000/day free tier). A title page's IMDb also refreshes that title's poster pill.
- Reviews: TMDB `/{type}/{id}/reviews` (first page, English), kept in TmdbRepository's 15-minute cache; markdown /
  HTML stripped to plain text in `core` (`ReviewText`).
- Metascore number is black, not Metacritic's white: white on its green is 2.1:1 and fails the contrast rule;
  black passes on all three colours. Brand colours live in `ui/theme/Brand.kt` (exception to tokens-only).
- Telemetry: `ratings` ReelStats lines (src = poster / omdb / cinemeta / season, ok, ms).
Evidence: `RatingsTest` (core), renders `title-*`, `title-rotten`, `title-reviews`, `title-show-episodes`, `review-open`.

---

**2026-10-24 · Crop bars: cut baked-in black bars, then the picture mode fills the screen**
Decision (user request: Stretch / Fill can't remove black bars that are inside the video). Settings panel → Playback →
"Crop bars" (between Picture and Cinema mode): the same share off the top and the bottom, 0–20 % of the video's height
per side, remembered per title (`TitlePrefs.cropPercent`, null = off). Logic + tests: `core/player/CropBars.kt`
(`CropBarsTest`); player: `player/CropControls.kt`, `PlayerKit` (PanelCrop), `PhoneControls`, `TvControls`, `AmbientGlow`.
Also decided (Claude):
- Layout: the content frame takes the shape of what's left (video shape / (1 − 2c)), the video surface is scaled
  1 / (1 − 2c) vertically inside it and the frame clips; then Fit / Fill / Stretch / forced shapes work as before on the
  cropped picture. Stays on the SurfaceView (no TextureView switch: the R4 is weak and SurfaceView keeps HDR /
  hardware composition). Where an Android version doesn't clip a SurfaceView by its parent, the cut rows still don't
  show: the surface is under the window, which is opaque outside the frame.
- Phone: tapping the row closes the panel and the band and shows a small adjust bar at the bottom (title + value,
  − slider +, Auto, Reset, Done; 0.5 % steps), so the picture stays in view and changes live. Tap on the picture /
  Back = Done. TV: ←/→ on the row change it in place (1 % steps); OK opens its page (value, slider, Auto, Reset).
- Auto: 3 PixelCopy frames (128 × 200) 0.3 s apart. A bar row is flat black (≤ 28 luma, mean ≤ 20, spread ≤ 6) over
  97 % of the row, or over half of it with both ends black (burnt-in subtitles in the bar). Per frame the smaller of
  top / bottom (both bars needed); a frame whose film averages < 24 luma is skipped as too dark; across frames the
  smallest wins (a dark scene can only inflate bars). Rounded up to 0.5 %, < 1 % = "No bars found", all dark = "Too
  dark to tell". Telemetry `crop` (pct, how = slider / reset / auto / start; Auto adds result, frames, per-frame, ms).
- Captions: the SubtitleView fills the cropped frame, so they sit on the picture; when the frame runs past the screen
  (Fill) or under the phone band, `CaptionSpace` lifts them to stay visible. Cinema mode keeps working: the glow
  reads the frame's bounds (not the scaled surface) and takes its strips from inside the crop.
Evidence: before / after renders `CropRender` (letterboxed 2.4:1-in-16:9 stand-in), PlayerDesignRules crop scenes (phone
land / portrait, 100 / 130 %, TV) pass with no new violations. Not yet checked on the devices.

**2026-10-22 · Debrid "downloading" placeholder clips: detect, stop, fall back**
Decision (user report: a Debridio link played "[Debridio] Downloading to Provider — Please wait" instead of the film).
Logic + tests: `core/player/Placeholder.kt` (PlaceholderCheck, PlaceholderFallback, NotReadySources),
`core/sources/DebridCache.kt` (`PlaceholderTest`). Player: `player/PlayerActivity`, `PlayerOverlays`, `NotReadyMemory`.
Also decided (Claude):
- Detection only for direct http(s) links of a film / episode (TMDB id), never trailers / extras or links from other
  apps. Primary: media under 3 min where over 10 min is expected (TMDB episode → show / film runtime, else a history
  duration ≥ 10 min — a shorter one may be a saved placeholder — else assumed). While the runtime is fetched (≤ 2.5 s)
  the player holds playback and saves nothing. Secondary: the final URL after redirects (OkHttp application
  interceptor) has a placeholder file name (`downloading*.mp4`, `not_cached`, `uncached`, `torrent_not_downloaded`,
  `/static/videos|exceptions/…`); the stem must be letters / `_` / `-` only, so release names never match. Only host +
  pattern name are logged; addon URLs carry the debrid key.
- On a hit: stop at once, no watch progress (guards in saveProgress / STATE_ENDED / onStart), source marked "not ready"
  for 30 min (in memory, by source key). Fallback: a fresh source search (≤ 15 s, whatever answered) and the best
  **direct** link that isn't that file, not-ready or marked uncached — cached first, then unknown. Torrents are never
  auto-picked (P2P rules, slow start; the user can pick one from Other sources). It plays from the same start position
  through Change source's hand-over, with a chip "<Addon> is still fetching that one — playing <provider quality>
  instead". At most 3 switches in a row. Nothing found → the Not ready card (Try again = same source resolved afresh,
  Other sources, Back), not the generic error.
- Sources page (UI / One stream sessions' files, kept additive): Stremio streams carry `Source.cache` from the stream
  name + description (⚡ / RD+ / [AD+] / Instant (name only) / Cached vs ⏳ / ⬇️ / [RD download] / Download /
  Uncached / Not cached; both → unknown). Uncached rows get a "Not cached" tag (SourceBrowser.view) and −50 in
  SourceRanker (below the same resolution cached, above the next lower one). Not-ready rows get "Fetching…", go last
  and are never the Recommended card unless nothing else is left (SourcesScreen, 3 small edits).
Telemetry: ReelStats `placeholder` (host, reason duration|pattern, pattern, expectedMin, actualS, marked, addon, hops),
`placeholderFallback` (found, ms, to, cache), `placeholderRetry`.
Device test: SourcesProbe `--ez placeholderTest true [--ei runtimeMin 90] [--ez fallback true]` (10 s CC-BY Big Buck Bunny
clip as a "Debridio" link; fallback = the full film on archive.org). Unverified on devices.

**2026-10-22 · Engine: download only overnight, speed cap while watching, stream-cache limit**
Decision (user): build the engine items of roadmap A. Spec: STREAMING_V2 §8.3 (addendum). Logic + tests:
`core/stream/DownloadRules.kt`, `CachePolicy.budget(limit)`, `RestorePolicy` (`DownloadRulesTest`).
Also decided (Claude):
- The overnight window (default off, 01:00–07:00) holds DOWNLOAD torrents only, as a condition *next to* the home-disk
  P2P rule: a release asks `downloadHold` first, so the window opening never moves traffic on a network where
  downloads wait. Resume outside the window (the user's or the P2P gate's) keeps it waiting; "Start now" exempts that
  download for good. Finished downloads (seeding) and a download being played are never held.
- Edges: the engine re-checks every 30 s, plus an inexact `setAndAllowWhileIdle` alarm to the running EngineService (no
  exact-alarm permission, no new manifest entry); a few minutes late under Doze is fine for downloads.
- A partial wake lock ("reel:night", 10 min re-armed) only while the window is on and open and a download is
  fetching, so a phone left on the charger keeps downloading with the screen off. The narrow part of R6; nothing else.
- Speed cap while watching replaces 8.1's GUARD / SHARE pauses for now: a cap is gentler than pausing and the user
  picks it (No limit / 500 KB/s / 1 MB/s / 2 MB/s / Auto = 25 % of the 60 s peak link rate, default Auto); others'
  upload 32 KiB/s. "Watching" uses `playWhenReady`, so a rebuffering player still counts. Lifts 5 s after pause/stop.
- The stream-cache limit replaces the automatic size but keeps the free-space reserve; the two most recently stopped
  titles stay protected as before (so a small limit can be exceeded by them).
Telemetry: ReelStats `night`, `nightStartNow`, `gov`.

**2026-10-22 · Player: skip intro / credits, auto-play next, sleep timer, per-title memory, audio tools**
Decision (user): build the player items of the feature brainstorm (roadmap A).
Built (Claude): Skip intro / recap / credits chip, Up next at the credits, countdown 5 / 10 / 20 s, Still watching?,
sleep timer, per-title tracks / picture / speed / delays, speeds 0.75–2×, skip step 10 / 15 / 30 s, night mode,
voice boost, audio delay, subtitle delay, Search subtitles again. Logic in `core/player/` (tests:
`PlayerFeaturesTest`); player in `player/` (PlayerOverlays, PlayerStore, DelayRenderers, AudioEffects).
Also decided (Claude):
- Intro learning uses only the user's own skips. A burst of seeks (remote presses less than 2.5 s apart) is one jump.
  A forward jump of 30–150 s that starts in the first 6 minutes of an episode is an intro skip. The window is the
  median start and median end over the last 8 episodes, kept per show. So the chip shows from the 2nd episode.
  The chip's own skip is never learned (it would only confirm itself). MKV chapters come first: media3 1.11 puts
  them in each track's Format.metadata as `Chapter`, and titles like Intro / Opening / OP / Recap / Credits / ED
  count. MP4 chapters aren't exposed by media3, so they aren't used.
- Credits: when the user starts the next episode by hand (Play now, Skip credits, Next) with 10 s – 10 min left, that
  remaining time is stored. Up next then appears at the median from the next episode on (never later than the last
  30 s), its button reads "Skip credits", and the countdown starts there.
- Still watching?: counts episodes that ended with no key press or touch, carried from one player to the next through
  the intent. After 3 it asks instead of auto-playing. No answer in 60 s pauses and closes the player, so the TV can
  go to its screensaver.
- Sleep timer: minutes run on the clock and carry into the next episode. End of episode stops auto-play. The last
  10 s fade the volume, then playback pauses.
- Per-title tracks, picture and speed go in the shared `TitlePrefsStore` (data session, `0ff0396`), and are written only
  on an explicit panel choice: automatic picks never become "remembered". Picture also stays the global default.
  The shared store has no delay or skip fields, so the player keeps its own `files/player_memory.json`
  (`core/player/PlayerMemory`: per-title audio / subtitle delay, per-show learned intro / credits).
  **Proposal to the data session:** add `audioDelayMs` / `subtitleDelayMs` to `TitlePrefs` (and sync it phone → TV),
  then the player moves its delays there. Learned skips can stay per device.
- Countdown, skip step, night mode and voice boost are app-wide `PlayerOptions` in SharedPreferences "player_options"
  (`player/PlayerStore`), not in `data/PlaybackSettings`, which belongs to the data session.
- Audio delay: media3 1.11 has no audio offset. A ForwardingAudioSink reports its position + the delay; video follows
  the audio clock, so the sound lands later. Subtitle delay: the text renderer (a ForwardingRenderer) is asked for cues
  at position − delay. Both are live and per title.
- Night mode / voice boost: `DynamicsProcessing` on the player's audio session (API 28+). Night mode is one
  full-band compressor (4:1 above −28 dB, +9 dB make-up gain). Voice boost is a pre-EQ (+5 dB at 1–4 kHz, −3 dB
  under 250 Hz). Either one adds a limiter at −1 dB. If they're on when a video starts, Dolby / DTS is decoded
  in-app (PCM only), because effects never touch bitstream passthrough. If the device refuses the effect, the rows
  turn into "Not available on this device" (ReelStats `audioFx ok=false`). Unverified on the R4 (no device run).
- Design suite: the TV focus check now measures a control where focus scrolls it to. The panel got taller than the
  TV screen, and the old check measured the pre-scroll spot (0.9 % / out-of-bounds).
Telemetry: ReelStats `skipChip`, `skipUsed`, `introLearned`, `creditsLearned`, `upNextAtCredits`, `chapters`,
`stillWatching`, `sleepTimer`, `audioFx`, `subsResearch`, `titlePrefs`, `titlePrefSaved`.

**2026-10-22 · Home you can arrange, TV start screen, screensaver, browse filters**
Decision (user): build the rest of the feature brainstorm. Split by file owner: player items to the player session,
stores / TMDB to the data session, screens here (`b505e3f`, `acbaff3`, this commit).
Built (Claude): Settings → Home screen (`ui/home/HomeLayout.kt`: hero source, auto-advance, posters / wide rows, size,
row order + hide, per device); Home rows Because you watched, New episodes (calendar, `core/browse/AirDates`); genre grids
page through TMDB discover with sort / rating / decade chips (`core/browse/TitleFilter`), every grid also filters what's
loaded; History + Not interested, Source preferences, auto-download settings; voice search; franchise row and
"3 of 10 watched" on title pages; TV opens on Home, Search or Downloads; TV screensaver (My list / Continue watching
backdrops + clock) after 2 / 5 / 10 idle minutes on browse screens.
Why: "Continue watching" as a TV start screen was dropped: it's already the first row on Home, so it equals Home. Our own
screensaver comes before the system's so the screen shows your titles; any key only wakes it (never clicks through).
Filter chips step to the next choice on each press because menus are slow with a remote.

**2026-10-21 · Long-press opens an options sheet**
Decision (user): long-pressing a Continue watching card shows more options (first item from the feature brainstorm).
Built (Claude): one `OptionsSheet` (`ui/common/OptionsSheet.kt`). On the phone it's a bottom sheet with the title art on top.
On TV it's a right-side panel in its own window, so focus can't leave it and Back closes it. Long-press used to act
straight away (remove from Continue watching, toggle My list); now it opens the sheet. Continue watching: Resume, Start over,
Play next episode, Mark as watched, Choose another source, Go to title, Remove from row. Posters: My list, Trailer, Share,
Go to title. Episodes: Play from start, Download, Mark watched / unwatched. Downloads → More gains Go to title.
Also decided (Claude): Remove hides the card at once and shows an Undo snackbar (app root, `UndoHost`); the history
entry is deleted only when the snackbar goes. That replaces the old confirm dialog. On TV, Undo takes focus and
focus goes back to the row afterwards. Mark watched uses `history.markWatched` (an unplayed episode gets
`Playback.watchedMarker`, with the next episode so Continue watching moves on). A watched episode offers Mark unwatched
instead. Play from start falls back to Sources for an episode that was only marked.
Evidence: renders continue-options(-show), poster-options, episode-options, continue-undo, downloads-more (phone and TV).

---

**2026-10-21 · Caption style (phone and TV)**
Decision (user): customise how subtitles look, on both devices.
Built (Claude): `core/playback/CaptionStyle` (size Small–Extra large, text colour, background None / Light box /
Box / Solid, outline None / Outline / Shadow / Raised / Sunken, font, position Low / Normal / High, or Android's
own caption style), presets Classic · Cinema · Yellow · Boxed (size and position stay the user's), saved as
`key=value` in prefs "playback" (old `subtitleSize` carries over). Settings → Subtitles → Style shows a live
preview drawn by Media3's SubtitleView via `captions/CaptionLook` — the same call the player makes, so the preview
is what plays. Also decided (Claude): no box + no outline is shown with a shadow (unreadable on bright scenes);
embedded ASS/SSA styles and sizes are ignored so the user's choice wins.
Evidence: CaptionStyleTest, renders settings-subtitles (phone, TV).

---

**2026-10-21 · Phone: floating, icon-only tab bar (iOS-style)**
Decision (user): replace the full-width Material bottom bar with a floating, fully rounded bar like iOS; no labels
on any tab.
Built (Claude): a 236 × 60 dp capsule centred 12 dp above the gesture bar (`Space.barGap`), four 56 × 48 dp
icon-only tabs (each icon keeps its name for TalkBack: "Home", "Search", "Downloads", "Settings"). Surface
`barGlass` = surfaceContainerHigh at 90 % (not the 72 % `glass`: there is no backdrop blur, and at 72 % row titles
read through it), a hairline and a soft shadow (`barShadow`, scrim at 32 %): the one shadow in the app, because the
bar hovers over scrolling content. Selected = filled icon in the accent on a soft accent pill (`barIndicator`,
primary at 16 %) that slides between tabs over `Motion.stateMs`; the rest outlined in `textSecondary`. Still hidden
on stacked pages (title, sources, grids). `Space.bottomBarClearance` is now system bar + gap + bar (read from the
insets, so 3-button navigation is covered too) and every tab list keeps its extra space under the last row.
Blur: skipped. Compose can only blur its own content; a real backdrop blur means drawing the screen twice
(e.g. the Haze library), a cost on every scroll frame for a small gain over a 90 % surface.
Checks: icon-only tabs had no words for the contrast rule, so the rule now also measures their icons
(`core/design/IconContrast`, WCAG non-text 3:1, coverage read from the pixels around the icon). New renders scroll
Home, Search, Downloads and Settings to the end (`*-end`) to prove the last row clears the bar.
The checks also learnt two things the floating bar exposed: rows that sit next to / partly under the bar at a
given scroll position are skipped there (touch and text contrast; the same rows are checked where they sit clear),
and rows a lazy list keeps composed but unplaced after a scroll are ignored (they reported stale positions).
Measured icon contrast: unselected 6.4–8.7:1, selected 4.2:1 (light) to 6.4:1 (dark).
Open (user): the end renders showed the poster meta line ("★ 7.4 · 1998", `textTertiary`) at 4.27:1 in light mode on
Home and Search too, the same known issue baselined on the collection grids; Home / Search end renders run dark
only until the user decides on that grey (baselines not grown).
Evidence: renders `phone/home|search|downloads|settings(-end)-<dark|light>-<100|130>.png`; `tool/check_design.sh`.

**2026-10-21 · Subtitles on in English by default, OpenSubtitles built in, Change source in the player**
Decision (user): subtitles didn't show even with a subtitle addon added; English should be the default with
OpenSubtitles available out of the box; the player needs a way to change the source.
Root cause (Claude, from the code): the player asked the subtitle addons but attached their tracks only when a
subtitle language was set (`prefs.subtitleLanguage != null`) or Android captions were on. The setting defaulted
to null (= Off), so with defaults every addon answer was fetched and thrown away, and the Subtitles menu said
"No subtitles". Late answers were also attached only then. Titles opened without an IMDb id (OpenSubtitles
needs `tt…`) were never asked.
Built (Claude): every found track is attached and listed in the menu; the setting only decides which shows by
itself (`Subtitles.merge` / `autoSelect`). The 400 ms start wait stays, only when a language should show. Late
tracks join before the first frame at once (re-prepare is invisible then); after it they wait for a natural
break (a seek, a pause with `keepContentOnPlayerReset`, the return from the background), because ExoPlayer can
only add side-loaded subtitles by preparing the item again, which shows as a restart mid-film. A missing IMDb id
comes from TMDB details when the TMDB id is known (a raw magnet / file has none: nothing to ask).
Setting: unset = English ("en"); Off is now stored as its own value ("off", `SubtitleLanguagePref`), so it is never
confused with "never chosen". (Before, Off removed the key, so earlier Off choices read as English once.)
OpenSubtitles v3 (`https://opensubtitles-v3.strem.io/manifest.json`, Stremio's official addon, subtitles only) is
installed once on new and existing installs from its shipped manifest (`core/store/BuiltInAddons`, flag
`sources_steps/done`); it's an ordinary addon in Settings → Addons, and a removal stays removed.
Change source: a row in the player's settings panel (phone + TV) opens the Sources page for this film / episode
with the current position; the first pick there plays from exactly that position (`ContinueWatching.startPositionMs`),
later picks resume from history. The old player saves and closes first (owner-checked `stopStream`), history keeps
one entry per key and the new player saves at its first frame, so the new source is the remembered one. A raw
magnet / file shows the row off with "No other sources for a file". The error panel's "Other sources" carries the
position too. Also fixed: the settings panel didn't clip its scrolled list (parents allow overdraw), and the
View-based design checks now skip rows partly scrolled out of view, as the Compose checks already did.
Diagnostics: ReelStats `subs` / `subsAttached` / `subsLate` / `subsSelected` per playback; SourcesProbe
`--ez subsTest true` lists each subtitle addon, request URL (addon config hidden), status, tracks per language, error.
Evidence: SubtitleDefaultsTest (core), PlayerDesignRules phone/tv settings-source + settings-file, renders
`phone_controls_settings_source`, `…_file`, `tv_controls_settings_source`, `…_file`; from the Mac (plain HTTPS)
OpenSubtitles v3 answered 200 for Big Buck Bunny (tt1254207: 1 English + 1 Indonesian), The Shawshank Redemption
(39 tracks, 3 English) and Game of Thrones S1E1 (94 tracks, 7 English); the subtitle file downloads as
`application/x-subrip`.

---

**2026-10-21 · Trailers are never the best match; source tags and a long-press menu**
Decision (user): trailers / teasers counted as real sources and were often recommended — sources must
prioritise the film / episode itself and keep extras separate. Also: tags on sources (e.g. last played) and a
long-press menu (download, stream only, play, favourite, star, share).
Built (Claude): `core/sources/SourceExtras` marks trailers, teasers, TV spots, featurettes, behind the scenes,
deleted scenes, bloopers, interviews, promos, clips, samples, extras and YouTube links (`Source.extra`); words
that are part of the searched title don't count ("Trailer Park Boys", "Extras"). The ranker puts extras last;
Recommended, Up next, the launcher's best and cast picks skip them; the Sources page lists them under
"Trailers & extras". `core/library/SourceMarks` + `data/SourceMarksStore`: one **favourite** per film / episode
(becomes Recommended when it comes back; for shows it follows the same release to other episodes via binge
group) and any number of **starred** sources (listed first). Row tags, before release tags: Last played / Same as
last episode (history), Favourite, Starred, Downloaded / Downloading N %. Long-press (hold OK on TV) or the ⋯ on
Recommended opens Play · Download file · Stream only (not saved) · Make favourite · Star · Share link.
Also decided here (Claude): "Only download for stream" = **Stream only**: the player's own start in stream mode
(stream cache, not Downloads), stopped after 20 min if nobody plays it (like the launcher's prepare, longer);
it follows the network rule like Play. Favourite vs star: favourite = the pick, star = a bookmark that sorts first.
Share sends the magnet / direct link / service page (a debrid link includes the user's key; their choice).
Why: the user's report and request. Evidence: SourceExtrasTest, SourceMarksTest, renders sources-extras,
sources-personal-tags, source-options.

---

**2026-10-21 · Home disk phase 2 (set up now, no disk) and the product answers**
Decision (user, product questions of HOME_DISK §21): "On this phone" on mobile data waits for Wi-Fi by default,
with "Start now anyway"; an unknown Wi-Fi is asked once per network (answer remembered by fingerprint); the
office (a Wi-Fi marked "Never here (work)") never moves BitTorrent traffic, a blocked download offers
"Download at home instead"; **Play follows the same rule** (blocked at work, ask once on unknown Wi-Fi,
unchanged on mobile data and at home); what the TV plays stays in the disk cache 7 days (setting); auto-cache
from details off (setting); cache share 40 % (setting); big 4K files away: warn + smaller source (Phase 7);
sharing: guests see only picked titles, can't request downloads, app or VLC link (Phase 9, no placeholder now).
Built (Claude): `core/homedisk/` (DiskLayout, TitleManifest, PartsMap, Lease, NetworkFingerprint + HomeNetwork,
P2pRule, DeleteWhere, HomeBadges, DownloadWhereSheet, HomeSettings, RetentionPolicy, StreamToken, Outbox,
DiskTest + ConnectPolicy), `core/sync/HomeRpc` (sync v3: request / ack / progress / done, TV-side HomeAgent with
idempotency), SyncServer v3 endpoint + private-peer filter, SyncClient.rpc; app `homedisk/` (HomeSecrets in the
Android Keystore — pairing secrets moved there too —, NetworkWatch, HomeDisk + jcifs Test, P2pGate with the
`TorrentEngine.downloadHold` hook, HomeClient outbox, TvHomeAgent); Settings → Devices → Home storage on phone
and TV, Download-where sheet, Downloads badges / held downloads / at-home rows / delete-where; problem reports
drop password lines and mask the share user and folder (`Redactor`).
Also decided here (Claude): the very first Test, with no home network saved, signs in only after "Are you at
home?" and then saves that network (I6 otherwise unchanged); a TV proof on the LAN also saves the home
fingerprint, so home is known when the TV is off later; the outbox never stores the SMB password (added at
send time to `home.config`); the outbox is sent while the app runs (start, network change, Downloads, every
minute) — the JobScheduler job comes with Phase 6.
Why: the user wants everything set up before the disk arrives, and no P2P on the office network is a hard rule.
Evidence: core tests (HomeDiskTest, HomeRpcTest incl. v3 over real HTTP), design rules, renders
`phone/settings-homestorage*`, `download-where*`, `downloads-home`, `downloads-mobile`, `downloads-delete-where`,
`network-*`, `tv/settings-homestorage*`. Not yet tried on the devices; the Test says "Not connected" until the disk exists.

---

**2026-10-21 · Play / Download on TV from the phone (cast links)**
Decision: One Launcher on the phone sends an Android TV remote app link to the TV:
`onestream://cast/play|download?type&tmdb[&season&episode][&hash&file&url]` (LAUNCHER_API.md Part 3).
The TV runs its **own** source search and the link's hash / file / url only selects among those results,
never plays a URI by itself (any app or remote can send links). Play shows "Starting on your TV…" then
opens the player (hinted source if this TV can stream it, else Recommended); nothing found → message +
Open Sources. Download goes through an invisible trampoline activity (Theme.NoDisplay, empty task
affinity, noHistory, excludeFromRecents) into EngineService, so the player or whatever is on screen is
not interrupted; toast "Downloading <title> on this TV". `/sources` rows gain `hash`, `file`, `url` at
the end. The MainActivity `onestream` filter now lists its hosts: a catch-all would also match
`cast/download` and Android would ask which activity to open.
Why: the user searches on the phone and wants the film to start (or download) on the TV without the
remote. Evidence: `CastLinkTest`, renders `tv/cast-starting.png`, `tv/cast-none.png`; design rules pass
with no baseline change. Not yet tried on the devices.

---

**2026-10-21 · Home disk spec (docs/HOME_DISK.md)**
Decision: the router disk plan gets a binding spec; main choices:
- Roles: the TV is the home agent (at-home downloads, library index, retention, remote serving); the phone
  sets up, asks, downloads as fallback and copies its own downloads; the router disk is a passive SMB share.
- TV conveyor = **rolling reset**: every ~512 MiB, once every verified piece is on the disk, remove the
  torrent with its local files and re-add it from resume data with have / verified / unfinished cleared and
  priority 0 for pieces on the disk. libtorrent4j has no "forget a piece" call; punch-holing the local file
  (native helper) would keep libtorrent advertising and serving zeros (peers hash-fail and ban us, a recheck
  re-downloads), `move_storage` frees nothing truthfully, and the part file never shrinks. Expected cost
  10–20 % of throughput, to be measured (`conv-reset`); revisit only above 20 %.
- SMB client: org.codelibs:jcifs 3.0.4 (maintained jcifs-ng successor; SMB 2.0.2–3.1.1 with SMB1 opt-in for
  old routers; 1 MiB requests where jcifs-ng 2.1.10 is held to 64 KiB on SMB 2.1+; pure Java). smbj has no
  SMB1. Two R8 keep rules are mandatory (shrunk builds lose BouncyCastle's MD4 → every NTLM sign-in fails).
- Home detection without location: the paired TV's HMAC proof on the LAN, or the saved LinkProperties
  fingerprint; the app never signs in to a share off the home network (an impostor could crack the
  NTLMv2 response).
- Phone P2P network rule: home Wi-Fi yes; mobile data and other Wi-Fi only with the user's explicit
  allowance; the office network never (downloads wait and offer "Download at home instead").
- On-disk format: `library/` (finished, Mac-friendly names) / `incoming/` / `cache/` / `.trash/`, a JSON
  manifest per torrent key, a per-file binary piece map (`.parts`: byte ranges + CRC, atomic replace) and a
  counter-based single-writer lease. Invariants: data before map, map before free, library only verified.
- Phone → disk copy: platform JobScheduler (unmetered + network-callback trigger, user-initiated transfer
  for "Copy now"), chunked and resumable, full read-back verify before "Phone + home disk". No WorkManager.
- Requests: SyncServer protocol v3, one encrypted RPC endpoint, idempotent by request id, phone outbox;
  away over Tailscale (no port forwarding). Away playback: TV HomeStreamServer on port 47822 with HMAC
  tokens, LAN / Tailscale sources only; sharing only via Tailscale node sharing + guest invites.
- Playback from SMB through 1 MiB chunks with an 8-chunk LRU and read-ahead (the 29 s → 5 s lesson: an
  unbuffered Cues parse would be ~30 s of round trips).
Why: the user's four goals (download at home from anywhere, phone fallback with copy + delete-where, watch
at home and away, set up now without the disk) with the hard rules kept (no P2P from the office, never
public).
Evidence: HOME_DISK.md §20 (spike: Maven versions, Android API check, R8 dex size 679 KiB / 347 KiB
deflated, MD4 failure reproduced and fixed, libtorrent4j API from the jar, Android / Tailscale docs).
No SMB throughput yet: no unprivileged SMB server on the work Mac (impacket deliberately not installed:
security tools commonly flag it on a managed laptop); measured in phase 1.1 at home.

**2026-10-21 · Sources page, addon store filters, Change source + download ↔ stream cache**
Decision (Sources page): one page for Play and Download. A Play | Download switch under the title sets
what every card does (the page opens on Play from a title; Change source opens it fixed on download), so
each card has one obvious action and one D-pad stop; the old per-row download button is gone. The ranker's
pick is the Recommended card (one big button); the list under it never repeats it, and filters never hide
it. Chips: a Sort chip that cycles Best → Smallest → Most seeds (one stop on TV, short row on the phone),
quality (4K / 1080p / 720p / SD, unknown counts as SD), type (Torrent / Direct / Service), provider (short
names: "Comet | Real-Debrid" → Comet) and language, each only when there's a choice; several chips in a
group = OR, across groups = AND; counts are per chip over the list. Seed health from seeders: 50+ strong,
10–49 OK, 1–9 weak, 0 none, as three bars; the colour sits on the bars only (amber text misses 4.5:1 on
light cards). Tags parsed from the release name (Dolby Vision, HDR10+/HDR, 10-bit, HEVC, AV1, Atmos,
TrueHD, DTS-HD/DTS, DD+, 7.1/5.1, IMAX, REMUX, BluRay, WEB-DL, WEBRip, CAM). Languages: one reader shared
with the launcher API (words, upper-case codes only, flags; "Multi"/"Dual audio" only when none named).
Logic: `core/sources/SourceFacts`, `SourceBrowser` (+ `SourceBrowserTest`). Phone hero 300 → 230 dp so
the card and chips show without scrolling.
Decision (store): search + chips from what manifests say: kind (Streams / Subtitles / Catalogs = also
offers catalogs), status (Added / Not added / Needs setup; added wins), content type (Movies, Series, Live
TV, Channels, Anime… from `types`) and catalog (only with 2+ catalogs). Catalog-only addons stay hidden
(they do nothing in One Stream); nothing new is listed. Logic: `AddonCatalog.facets/search/prune`.
Decision (downloads): "Change source" (More menu) opens the Sources page for the same title / episode in
download mode; the origin is stored per info-hash in `files/download_origins.json` when a download starts
from Sources or the launcher (magnet-link downloads have none, so no Change source). A different torrent or
a direct link replaces the download after a confirmation that says how much is lost; the new one starts
first and the old one is deleted only after that succeeds; another file of the same torrent is added
(nothing lost). "Keep as stream cache" converts a download **in place** (no file move: a move to
`files/streams` crosses mounts = a full copy needing the file's size again in free space); it becomes a
paused STREAM entry under the normal cache policy, `lastUsedAt = now`, not protected like a mid-watch
stop. "Save as download" on a cache entry is the existing Keep (pieces reused, skips the move when the data
already sits in Downloads). Deferred while a player holds the torrent; the later wish wins. Spec:
STREAMING_V2 §7.3a; logic `core/stream/ModeChange`, `core/library/SourceSwap` (+ `DownloadChangesTest`,
pure — no test opens a session). Stream cache is its own section under the downloads.
Also: light-mode `warn` darkened to #9A4507 and settings subtitles / breadcrumb / search placeholder moved
from textTertiary to textSecondary — the new states would otherwise repeat old contrast failures; 38
baselined contrast violations were fixed and pruned (baseline only shrank).

**2026-10-21 · One Launcher API part 2 (the title page in the launcher)**
Decision: the provider also serves details / episodes / sources / downloads and takes actions through
`call()` (watchlist add/remove, download, prepare, cancel_download); `onestream://play` opens the player
with a source the provider found. Sources are the Sources screen's own search (same providers, ranker
and Best match); each gets a random 24-hex token mapped in memory (LRU 500) to the Playback the Sources
screen would build — unguessable, so only a caller that passed the certificate check can play one; a
token from an earlier process falls back to the title page. `onestream://play?torrent=` only plays a
download the engine already has (any app can send a link; it must not start new P2P).
"prepare" = the player's own `startStream` without a player (stream mode, startup pieces queued by the
normal scheduler), then `stopWhenIdle(5 min)` — the same stop an external-player stream gets — so a
prepare nobody plays pauses itself and stays in the stream cache (trimmed as usual); a later Play
reuses the pieces (prepareStream supersedes the idle stream, so the idle watcher stands down). No new
engine code path. `/downloads` never starts the engine (a launcher query mustn't start P2P), so it is
empty until One Stream's engine runs. Languages are read from release text (flags, names, ENG/HIN…),
since sources carry no language field.

**2026-10-21 · One Launcher API (provider + onestream:// links)**
Decision: One Stream answers the user's own launcher locally: an exported ContentProvider
`com.kirtanya.reel.launcher` (info / search / continue / watchlist / title) and `onestream://` links
into MainActivity (title, imdb, resume, search). Binding contract: docs/LAUNCHER_API.md. Access: no
signature permission — One Stream keeps its current signing key, so the two apps don't share one;
instead every query checks the calling package name AND One Launcher's certificate SHA-256
(`hasSigningCertificate`, core `LauncherCaller`, tested), with both packages in `<queries>` so the
lookup works on Android 11+; any failure denies. Network queries wait at most 6 s on the binder thread
(the TMDB call runs on the app scope so a stuck read can't hold the binder) and return an empty cursor
instead of throwing. IMDb-only links get a small lookup screen (title-page loading / error look) that
swaps itself for the title page, so Back doesn't land on it. Row building / link parsing in core
`launcher/` with JVM tests.

**2026-10-21 · Picture modes (like VLC / MX Player)**
Decision: player Settings → Picture picks how the video fills the screen: Fit (default), Fill (crop),
Stretch, or a forced 16:9 / 4:3 / 21:9 shape for files that report the wrong one (core `PictureMode`;
Media3 resize modes + the content frame's aspect ratio, re-applied after each video-size change).
Remembered between videos (PlaybackPrefs.picture). Phone keeps YouTube's gesture — pinch out fills,
pinch in fits — and the gesture, the fit button and the setting stay in sync. Cinema mode re-checks for
bars after a change (Fill / Stretch leave none). 0.8.5.

**2026-10-21 · TV player controls always white**
Decision: the TV strip no longer samples the picture to switch between white and near-black; it is
always white with a small, tight shadow (CONTROL_SHADOW 40 % black, 1.5 dp blur, 1 dp down; icons get a
blurred copy behind them) — the user wants consistent white controls and no halo. Trade-off accepted by
the user: on very bright pictures the text falls below the contrast rule; those six `player/tv-bright`
cases were added to the contrast baseline with the user's OK (0.8.2).

**2026-10-21 · Cinema mode (bars glow with the video) + TV strip redesign**
Decision: when a video leaves black bars (≥ 3 % of the screen), the bars glow with the picture's own
colours, like YouTube's ambient mode. How: PixelCopy a 48×27 copy of the frame every 700 ms while
playing, blur (3 box passes ≈ Gaussian) and dim to 55 % in `core/playback/Ambient` (tested), stretch it
over the whole screen under a see-through PlayerView, crossfade 600 ms. Chosen over a second live,
blurred video (ExoPlayer renders to one surface; a TextureView + RenderEffect copy would cost the 32-bit
TV's GPU every frame) and over a single average colour (flat; the user asked for colours from the
picture). A video that fills the screen, a paused one, or DRM (copy refused) costs nothing.
Revised the same night (0.8.1) after the user found 0.8.0 "too much colour": like YouTube, light now
spills out of the picture — the blurred copy is drawn a little larger than the video (grown by the
widest bar) and each bar fades on an ease-out curve from a faint glow at the video edge to black at the
screen edge (`Ambient.falloff`). 0.8.3: the user saw the glow not matching the video's boundary (a
whole-frame enlargement put mid-picture colours next to the edge). Now each bar shows the blurred 25 %
strip of the picture next to it (`Ambient.EDGE_SHARE`), mirrored outward, so the glow starts in the
boundary's own colours (render test `AmbientGlowRender`). Checked on real frames (the user asked; TV
screenshots, run locally via AMBIENT_FRAME, never committed): the square ease-out died within a few
pixels on mostly dark frames, so the fade is now a Gaussian (σ 0.42 of the bar) reaching 0 at the screen
edge, and the levels are Subtle 45 % / Medium 65 % / Strong 85 % at the video edge. Strength in Settings panel → Cinema mode: Off / Subtle (28 % at the
edge, default) / Medium (45 %) / Strong (65 %) (PlaybackPrefs.cinemaGlow). Same day, at the user's request, the
TV strip became: hairline seek bar on the bottom edge, times above its ends, title + ends-at left, all
controls right at one size, all 40 dp from the edges (0.7.11–0.7.12).

**2026-10-20 · Automated device round (machine-first step 4)**
Decision: `tool/device_round.sh` verifies a build on the TV and phone the way today's hand test did:
install, play the legal Tears of Steel sample, drive the player (TV: D-pad strip, scrub, settings;
phone: tap, double-tap, swipe-scrub, settings sheet; PiP left out), screenshot each step, collect
ReelStats + crashes / ANRs / problem reports, clean up, compare with the last clean round. Choices:
- Safety gate is strict: awake, unlocked, no key / touch / wake-up for 90 s (`dumpsys power`), nothing
  playing, and only the launcher or our *home* screen on top (our player on top, even paused, counts as
  busy). The 90 s rule came from the first real run: the user was browsing One Stream on the TV, which
  "our home screen on top" alone called idle. Re-checked before every input; if anything else comes to
  the front the round stops pressing keys on that device (and force-stops only our own sample player).
- uiautomator is not used: it never sees the player idle (controls refresh twice a second) and hung on
  both devices. TV strip / settings panel are read from screenshot pixels (the R4's screencap shows the
  video plane black, so bright = our UI; `core/round/ScreenProbe`). The TV strip comes up by itself at
  playback start, so the round waits for it to hide and shows it with ↓ (OK would pause). Phone settings
  are tapped at the measured spot (2304, 96 px) and left to the screenshot.
- Logs stream to the Mac during the round: the phone's shared 256 KiB log buffer dropped the first
  minute of the first phone round. The `ev=end` summary also backs up lost single events.
- Thresholds tolerate swarm noise: worse only when ≥ 1.5× AND clearly more (ttff + 5 s, seek p50 + 2 s,
  p90 + 3 s, rebuffer time + 5 s, longest wait + 3 s; rebuffers + 2; waits / outstanding warnings 2× and
  + 5 / + 3). Cold vs cached starts aren't compared (the sample's pieces stay in the cache after a
  round, so later rounds usually start cached). Exit 1 only for crash, ANR, or no first frame.
- Only a clean, complete round becomes `rounds/latest.json` (the comparison point), so a failed or
  interrupted round never lowers the bar.
- The decisions live in `:core` (`core/round/`: IdleGate, RoundLog, RoundCompare, unit-tested); the shell
  script only drives adb and runs them with plain `java` (classpath from `:core:deviceRoundClasspath`).
- Continue watching is restored exactly: the sample is removed, or the user's own position on it put back.
Why: releases get verified the same way every time without a person or a long Claude session.
Evidence: `docs/MACHINE_FIRST_PLAN.md` "Step 4 in use"; `core/src/test/.../DeviceRoundTest.kt`.

**2026-10-20 · Trimmed the machine-first plan; short names again**
Decision: no "machine-only" code style (the user's call: compilers strip names, so it gives no speed and makes
Claude worse). Plan step 9 (state-machine experiment) dropped, step 5 (auto-install) postponed. Release
builds use short names again; mappings are kept per version and report stacks are decoded on the Mac.
Evidence: `-dontobfuscate` grew the dex 5.77 → 6.41 MB (+0.64 MB) for readability alone; the report
feature itself is ~0.05 MB. TV keeps at most 20 reports / 20 MB.

**2026-10-20 · Design rules as automatic checks (machine-first step 3)**
Decision: the user's five design rules (48 dp touch areas on the phone, no clipping at 360 dp, text
contrast 4.5:1 / 3:1 from 18 sp, tokens only, TV focus always visible) run on every screen render
(`tool/check_design.sh`, ~6 min on the Mac) and before every release (`tool/build_release.sh` stops on a
new violation). Violations that already existed in screens owned by other work live in baselines
(`app/src/test/resources/design-baseline/`) so only new ones fail; baselines only shrink without the
user's OK. Measurement choices: touch = the area that really takes the touch (Compose's own 48 dp
growth counts); contrast from rendered pixels against the worst 10 % of a 1–2.5 dp ring around the
glyphs (DESIGN_STYLE has no exception for big display text, so none is made); ellipsis only fails on
short labels you press; `Color.Transparent` is not a colour; player colours moved to
`player/PlayerColors.kt` (its token file); player touch areas now 48 dp with unchanged drawings.
Why: design quality is enforced on every build without the user checking screens.
Evidence: `docs/MACHINE_FIRST_PLAN.md` step 3; CLAUDE.md "Design rules".

**2026-10-20 · Session rules + decision log (machine-first step 2)**
Decision: `CLAUDE.md` holds the rules every session follows; this file records decisions.
Why: nobody reads the code, so sessions must behave the same way and know why things are as they are.
Evidence: `docs/MACHINE_FIRST_PLAN.md` step 2.

**2026-10-20 · Release builds keep real names (`-dontobfuscate`)** — superseded the same day (see above)
Decision: R8 still shrinks and optimizes, but class / method names stay readable.
Why: crash, freeze and ANR stacks in problem reports must be readable without a mapping file.
Evidence: `app/proguard-rules.pro`; ef9df95 (0.7.8).

**2026-10-20 · Problem reports on the device (machine-first step 1)**
Decision: crashes, freezes (≥ 5 s), Android exit records, buffering ≥ 15 s and player errors save a
report automatically; shake / "Report a problem" saves one by hand; `tool/pull_reports.sh` pulls them.
Why: real data (logs, stats, screen, stack) replaces descriptions in every fix session.
Evidence: dfaeee9, ef9df95; tested on the Pixel 8 (manual, freeze 7.5 s, crash, exit-record reports).

**2026-10-20 · Home disk parked**
Decision: the router-disk library / cache (`docs/HOME_DISK_PLAN.md`) waits; it fits after step 7.
Why: the machine-first loop (reports, checks, device rounds, auto-delivery) comes first; the plan
also needs the user's router / disk set-up (phase 0).
Evidence: 5462899; `docs/MACHINE_FIRST_PLAN.md` ("Parked separately").

**2026-10-20 · PlayerView controller off on phone and TV**
Decision: ExoPlayer's built-in controller is disabled on both; our own controls draw everything.
Why: one consistent player design (own icons, gestures, settings panel) on both devices.
Evidence: `PlayerActivity.kt` (`useController = false` for TV and phone); d92162f (0.7.5).

**2026-10-20 · Own player kit + TV bottom strip with adaptive contrast**
Decision: `PlayerKit` (icons, seek bar, settings panel) shared by `TvControls` and `PhoneControls`;
TV controls are one bottom strip, no centre buttons, no scrim; strip colour (white / near-black)
follows the picture via PixelCopy sampling twice a second.
Why: keep the picture visible and the controls readable on bright and dark scenes.
Evidence: 10796bd (0.7.4), d92162f (0.7.5), 5be0e1f (0.7.7, phone gestures + PiP checked on the Pixel 8).

**2026-10-20 · `max_out_request_queue` 1500 (TV) / 2000 (phone)**
Decision: raise libtorrent's outstanding request limit from its default 500.
Why: the TV logged `OUTSTANDING_REQUEST_LIMIT_REACHED` with 500.
Evidence: f9079ea (0.7.6), measured on the Acer R4 with ReelStats.

**2026-10-20 · Paused deadline floor only when `playWhenReady` is false**
Decision: relaxed deadlines apply only to a real pause, not to startup or rebuffering.
Why: the floor applied whenever the player wasn't playing, so pieces arrived one at a time.
Evidence: f9079ea (0.7.6); Big Buck Bunny cold start 5.1 s with 5 peers.

**2026-10-20 · Reader serves small reads from a 64 KiB buffer**
Decision: `StreamReaderCore` buffers verified bytes and skips seeks when the file pointer is already there.
Why: parsers read MKV Cues a few bytes at a time; each read was a seek + read syscall (26 s of a 29 s resume).
Evidence: f9079ea (0.7.6); Spider-Man resume on the TV 29 s → 5.0 s.

**2026-10-20 · Streaming v2: zone scheduler**
Decision: urgent pieces after the reader get deadlines timed against the real playhead; everything
ahead is fetched in file order via sequential range; container-aware startup (MP4 moov / MKV Cues).
Why: in-flight requests are never cancelled on a normal tick, and startup reads only the header +
container index it needs (a faststart MP4 needs no tail).
Evidence: `docs/STREAMING_V2.md` §4; 7220968 (0.7.2), review fixes 08ce69d (0.7.3).

**2026-10-20 · In-app player reads torrents directly, not over HTTP**
Decision: `TorrentDataSource` on a `reel-torrent://` URI; the HTTP server is only for external players.
Why: the loopback had an 8 s read timeout, so a slow piece became "This device can't play this file"
and seeks froze up to 8 s; now a slow piece is a buffering pause and a seek interrupts the read.
Evidence: `docs/STREAMING_V2.md` §5; d8fff2d (0.7.1).

**2026-10-20 · Streaming v2 spec is binding**
Decision: engine / streaming changes go into `docs/STREAMING_V2.md` first; phases 0–3 shipped as 0.7.0–0.7.3.
Why: an audit of the streaming path found 86 issues; one spec keeps engine work consistent.
Evidence: 0c96a8b (0.7.0) and later; ReelStats lines (`adb logcat -s ReelStats`) measure the results.

**2026-10-20 · Renamed to One Stream (0.6.1)**
Decision: new name, logo and TV banner; package id `com.kirtanya.reel`, the Download/Reel folder and
the sync service name stay.
Why: updates install in place and phone ↔ TV pairing keeps working.
Evidence: 89cf85e.

**2026-10-20 · Material 3 colour from the poster (0.6.0)**
Decision: MaterialKolor builds the whole M3 scheme from one seed: the poster on screen (Content
style), else the system accent or a chosen colour (Tonal spot); light / dark follow the system, TV dark.
Why: the screen takes on the title's colour; tokens map to M3 roles so nothing is hard-coded.
Evidence: 9560070; `docs/DESIGN_STYLE.md` §1.
