# One Stream: rules for every Claude session

## What this is
- **One Stream** (package `com.kirtanya.reel`, formerly "Reel"; Gradle project still `Reel`): a streaming-first
  movie / TV app (TMDB catalogue, sources from plugins + Stremio addons, libtorrent engine, ExoPlayer).
- Personal app for **one user**, a designer who never reads code, on **two devices**:
  - **Pixel 8** phone, display size raised → **360 × 800 dp** (every layout must fit it), arm64-v8a.
  - **Acer R4 Google TV**, 960 × 540 dp, **32-bit armeabi-v7a**, ~1.7 GB free storage.
- Claude writes, reads, tests and ships everything. The user only judges the app. Ask them about the
  product (what it does / looks like), never about code.
- Plans: `docs/MACHINE_FIRST_PLAN.md` (current roadmap), `docs/PLAN.md` (product + architecture),
  `docs/STREAMING_V2.md` (engine spec), `docs/DESIGN_STYLE.md` (design language),
  `docs/HOME_DISK_PLAN.md` (parked), `docs/DECISIONS.md` (decision log: add an entry for each real decision).

## Machine-first rules
- **Specs in `docs/` are the source of truth.** For engine / streaming behaviour change
  `docs/STREAMING_V2.md` first (it is binding), then the code. UI follows `docs/DESIGN_STYLE.md`
  (tokens only, no hard-coded colours; 48 dp touch areas on phone; TV focus always visible).
- **Comments explain WHY**, not what. No one reads them but Claude, so keep them true.
- **Every bug fix comes with a test.** Put the logic in `:core` (pure JVM, unit tests in
  `core/src/test`) whenever possible so it can be tested without a device.
- **Telemetry on slow paths:** emit `ReelStats` events through `engine/EngineStats.kt`
  (one key=value line per event, aggregated by `core/stream/StreamStats`) for anything that can be slow.
- **Delete unused code freely.** Build for exactly these two devices; no code for other devices / users.
- **Readable release stacks:** `-dontobfuscate` in `app/proguard-rules.pro` is intentional; keep it.
- Record non-trivial decisions in `docs/DECISIONS.md` (newest first: date, decision, why, evidence).

## Hard rules
- **Built-in sources stay legal.** The built-in Internet Archive plugin
  (`app/src/main/assets/plugins/internet-archive.js`) only searches `collection:feature_films`.
  Never ship, add or suggest piracy addons / plugins.
- **No BitTorrent traffic from the work Mac or the office network.** P2P tests only on the phone / TV
  at home. Mac-side work = unit tests, builds, screen renders.
- **Never print, read out or commit `tmdb.key`** (gitignored; read by `app/build.gradle.kts`).
- **Commit strictly by path** (several sessions share this tree):
  `git -c user.name="Kirtanya" -c user.email="kirtanya.k@peepal.co" commit -m "…" -- <paths>`
  New files: `git add -- <paths>` first. Never `git add -A`, `git add .` or `commit -a`. Messages end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Never disturb playback.** Before opening the app or sending keys on the TV, check it is idle:
  `adb shell dumpsys media_session` (no `PLAYING`) and `adb shell dumpsys activity activities | grep topResumedActivity`.
  Drive the phone only when idle too.
- **APKs only in `~/Desktop/APKs`**, never loose on the Desktop.
- `reports/` (pulled problem reports) is gitignored; never commit it.

## Build & test
```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :core:test                          # pure JVM unit tests (fast; run after every change)
./gradlew :app:compileDebugKotlin             # compile check
bash tool/check_design.sh                     # design rules on every screen (see "Design rules")
tool/build_release.sh                         # design rules + core tests + release → ~/Desktop/APKs/OneStream-<ver>-phone.apk / -tv.apk
./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.ScreenRenders'   # screen renders → app/build/screens/
```
- Version: `versionCode` / `versionName` in `app/build.gradle.kts` (bump both for every release).
- One APK per ABI: phone = arm64-v8a, TV = armeabi-v7a. Release is signed with the debug key (sideload).
- Screen renders: Robolectric SDK 34 (`app/src/test/resources/robolectric.properties`) + Roborazzi;
  `ScreenRenders.kt` (phone + TV screens, light/dark, font scales), `player/PhoneControlsRender.kt`,
  `player/TvControlsRender.kt` (scenes in `player/PlayerScenes.kt`). Look at the PNGs before claiming a
  layout is fine.
- adb: `~/Library/Android/sdk/platform-tools/adb`. TV: `adb connect 192.168.1.128:5555`.
  Phone: wireless adb; find its address with `adb mdns services`. Use `-s <serial>` when both are on.
- Install: `adb -s <serial> install -r ~/Desktop/APKs/OneStream-<ver>-<phone|tv>.apk`.

## Design rules (automatic checks)
`bash tool/check_design.sh` (≈ 6 min, Mac only) checks the user's design rules on every screen render and
prints `rule → pass / N baselined / N new`. `tool/build_release.sh` runs it first and stops on a new violation.
- **Rules** (suite: `app/src/test/kotlin/com/kirtanya/reel/design/`, pure logic: `core/.../core/design/`):
  1. 48 dp touch areas on the phone (the area that really takes the touch: Compose grows small targets to
     48 dp unless a neighbour is in the way; Views get only their bounds). TV is exempt.
  2. No clipping at 360 dp (phone, font 100 % and 130 %): text cut off, a short label (≤ 24 chars on a
     button / chip / tab / row) ending in "…", text cut by a non-scrolling parent, anything past the screen
     edge outside a sideways scroller. Long names may end in "…" (DESIGN_STYLE §4.7).
  3. Text contrast from the rendered pixels (ring 1–2.5 dp around the glyphs, worst 10 %): 4.5:1 under
     18 sp, 3:1 from 18 sp. Text covered by something else (under the nav bar) and disabled text are skipped.
  4. Tokens only: no hard-coded colours in `ui/**`, `player/**`, `MainActivity.kt` outside `ui/theme/` and
     `player/PlayerColors.kt` (`Color.Transparent` / `TRANSPARENT` and bit masks are fine).
  5. TV focus always visible: each focusable element (≤ 3 per row) focused vs not must change pixels
     worth ≥ 2 % of its area within 16 dp of it (ring / lift / glow), and must not look the same as a
     focusable neighbour having focus; includes the player strip and settings panel.
- **Baselines** `app/src/test/resources/design-baseline/<rule>.txt`: violations that existed when the rule
  arrived (`screen | element | measured`; the measured value is ignored when matching). Only violations
  beyond the baseline fail. When you fix one, `bash tool/check_design.sh --prune` drops it (prune never adds).
- **Never grow a baseline to silence a new violation without the user's OK.** Fix the screen instead.
  New screens / states go into `ScreenRenders.kt`; the design rules pick them up automatically.
- Run one class quickly: `./gradlew :app:testDebugUnitTest --tests 'com.kirtanya.reel.design.PlayerDesignRules' --rerun`;
  findings land in `app/build/design-rules/<rule>/`.

## Device tools
- **SourcesProbe** (adb-only broadcast, results in logcat tag `ReelProbe`):
  `adb shell am broadcast -n com.kirtanya.reel/.data.SourcesProbe <extras>`
  - `--ei tmdb <id> [--es type tv --ei season N --ei episode N]` source search for a title
  - `--es addAddon <manifest url>` / `--es removeAddon <url>` around the search
  - `--ei playSample 0..2` cold-start test on a legal sample (Blender open movies)
  - `--ez playTest true --es url <direct> [--ei tmdb --ei season --ei episode --ei nextEpisode --el startMs --el durationMs]`
  - `--ez history true` dump watch history + Continue watching
  - `--es setPosition <titleKey> --el ms <position>` put a watch position back
  - `--es removeTitle <titleKey>` remove a title from watch history
  - `--ez mainThreadTest true` subtitle + source search on the main thread
  - `--es sync pair|push|open|add|remove [--es code … --es link …]` phone → TV sync checks
  - `--ez report true [--es note <text>]` manual problem report;
    `--es reportTest crash|freeze` test the automatic ones
- **Undo test effects:** tests that play something change the user's Continue watching. Run
  `--ez history true` before, then restore with `setPosition` / `removeTitle` afterwards.
- **Streaming numbers:** `adb logcat -s ReelStats` (ttm / ttff / rebuffers / seeks / stalls, see
  STREAMING_V2.md §11). Engine flags: `files/engine_flags.properties` on the device (§11.2).

## "Check my reports"
1. `tool/pull_reports.sh` (every connected device → `reports/<model>/`, one line per new report;
   `--clear` deletes pulled reports from the devices afterwards).
2. For each new report read `report.json` first (kind, screen, player + engine state, last ReelStats),
   then `logs.txt` (last 2 min of our logcat), `stack.txt` (crash / freeze / ANR / player error),
   `screen.png`. Start the fix from what they show; the fix gets a test.

## Map of the code
`:core` (pure Kotlin/JVM, tested) — `core/src/main/kotlin/com/kirtanya/reel/core/`
- `stream/` engine v2 pure logic: ZoneScheduler, StreamPlan, PieceGate, StreamReaderCore, ContainerProbe,
  SessionTuning, SchedulerProfile, CachePolicy, RestorePolicy, StreamStats, TorrentKey, FileChoice.
- `sources/` source search: Nuvio JS plugins (PluginRuntime / PluginJs / DomBridge), Stremio addons,
  SourceAggregator + SourceRanker, Quality, Subtitles.
- `sync/` phone → TV sync: SyncServer / SyncClient, SyncCrypto, SourcesBundle, SourceLinks.
- `tmdb/` TMDB models, parser, paths. `magnet/` magnet parsing + trackers. `playback/` Playback,
  WatchHistory. `library/` Watchlist. `store/` AddonCatalog. `report/` ReportPolicy, LogWindow, ShakeGate.

`:app` — `app/src/main/kotlin/com/kirtanya/reel/`
- `ReelApp.kt` app singletons; `MainActivity.kt` picks phone or TV UI.
- `engine/` TorrentEngine (libtorrent4j, `reel-engine` thread), EngineService, ReelSession, EngineFlags,
  EngineStats (ReelStats), EngineModels.
- `player/` PlayerActivity (ExoPlayer setup, lifecycle, errors), TorrentDataSource (in-app reads),
  PlayerKit (icons, seek bar, settings panel), TvControls (bottom strip), PhoneControls (gestures, PiP).
- `server/LocalStreamServer.kt` token-URL HTTP server for external players only.
- `report/` ReportCenter, FreezeWatchdog, ExitReasons, ScreenCapture, ShakeDetector (problem reports).
- `data/` stores (Sources, Addon, WatchHistory, Watchlist, PlaybackSettings), TmdbRepository,
  ProviderApps, SourcesProbe; `data/sync/` PhoneSync, TvSync, Nsd.
- `ui/` `phone/` + `tv/` screens (Home, Search, Title), `common/` shared components (ReelButton,
  Cinema, Posters, ContinueRow, States…), `theme/` (Accent, Tokens, Theme), `sources/`, `settings/`,
  `store/`, `title/`, `nav/`, DownloadsScreen, Samples (legal sample torrents).
- Assets: `app/src/main/assets/plugins/` built-in plugins.

## Effort rule
Plan steps carry an effort level (see the tables in `docs/MACHINE_FIRST_PLAN.md`,
`docs/HOME_DISK_PLAN.md`). Run each step through a subagent at that effort. Never ask the user to
switch effort.

## Finishing a step
Tests pass → device check when it touches the app (only when idle, at home for P2P) → release APK
in `~/Desktop/APKs` if shipped → commit by path → decision log updated when something was decided.
