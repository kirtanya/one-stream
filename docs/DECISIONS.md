# Decision log

Newest first. Each entry: date, decision, why, evidence. Add one whenever something is decided
(product, engine, tooling); link the commit or spec section that carries it.

---

**2026-10-20 · Session rules + decision log (machine-first step 2)**
Decision: `CLAUDE.md` holds the rules every session follows; this file records decisions.
Why: nobody reads the code, so sessions must behave the same way and know why things are as they are.
Evidence: `docs/MACHINE_FIRST_PLAN.md` step 2.

**2026-10-20 · Release builds keep real names (`-dontobfuscate`)**
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
