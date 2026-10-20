# Machine-first One Stream: plan of action

One Stream is built for one person (you) on two devices (Pixel 8 at 360 dp, Acer R4 TV). No human
reads the code: Claude writes, reads, tests and ships it; you judge only the app itself. This plan
turns that into a working loop. Each step names the Claude **effort** to switch to before it starts.

## Order and effort

| # | Step | Effort | Why this order |
|---|------|--------|----------------|
| 1 | **Problem capture**: "Something's wrong" (long-press in the player / shake on phone) saves screenshot + last 2 min of logs + stream stats + screen; crashes, freezes, long buffering and failed starts are saved automatically. Reports pulled to the Mac with one command; "check my reports" starts every fix session | **High** | Every later step gets faster when real data replaces descriptions |
| 2 | **Session rules**: `CLAUDE.md` in the repo (specs first, why-comments, every fix gets a test, telemetry on slow paths, delete unused code, legal sources only, no P2P off the home network) + a decision log `docs/DECISIONS.md` | **Medium** | Small; makes every future session behave the same way |
| 3 | **Your design rules as automatic checks**: 48 dp touch areas, contrast, no clipping at 360 dp, tokens only (no hard-coded colours), TV focus always visible; run on every screen render, fail the build when broken | **High** | Design quality is enforced forever without you checking |
| 4 | **Automated device round**: one script drives TV + phone (only when idle): open, play a legal sample, seek, pause, PiP, back; collects stats + screenshots; compares with the last release | **High** | Releases are verified like today's test, but repeatable |
| 5 | ~~**Auto-delivery with rollback**~~ *Postponed: saves a minute per release, adds risk.*: verified build installs on the TV when idle and on the phone when charging at home; rolls back if crashes / stalls rise | **High** | Removes manual installs; safe because of 1 + 4 |
| 6 | **Built for two devices**: measure and tune layouts, buffers, image sizes per device; remove code that only exists for other devices / users | **High** | Speed and simplicity, guided by 1 + 4 numbers |
| 7 | **Self-tuning streaming**: the engine tries small, bounded variations (buffers, read-ahead, peer limits) and keeps what performs better on your network; source ranking learns which sources start fast for you | **Extra high** | Biggest felt win, but it changes engine behaviour on its own, so it needs the safety net of 1, 4, 5 |
| 8 | **Learns your habits**: auto subtitle / quality / source preferences; predicts tonight's title and prepares it | **High** | Comfort features once the base is solid |
| 9 | ~~**Lifecycle as a checked state machine**~~ *Dropped: the machine-style experiment has a negligible payoff (user decision 2026-10-20).* (player first, engine later): spec page → generated code → automatic path checks | **Extra high** | The machine-first coding experiment, on the part with the most past bugs |
| 10 | **Review** of the steps built with a multi-agent workflow, then fixes | **Ultracode**, fixes **High** | One deep check before calling it done |

## Step 1 in use: problem reports

When something goes wrong the app saves a report on the device: `report.json` (what happened, app
version, device, screen and player state, stream / engine state, engine flags, last `ReelStats` lines),
`logs.txt` (the app's own logcat, last 2 minutes, secrets removed), `screen.png` (manual, error and
stall reports) and `stack.txt` (crashes, freezes, player errors, ANRs).

- **Saved automatically**: crash, freeze (main thread stuck ≥ 5 s), Android's exit records on the next
  start (ANR, native crash, low-memory kill), buffering ≥ 15 s, every error the player shows. Automatic
  reports are limited to one per kind and title every 10 minutes; the device keeps the newest 40 (≤ 80 MB).
- **By hand**: shake the phone firmly twice (any screen, not in PiP) → optional note → Save;
  player settings → "Report a problem" (TV saves at once; phone asks for a note).
- **"Check my reports"** = run `tool/pull_reports.sh` (every connected device → `reports/<model>/`,
  one line per new report), then read each new report's `report.json` first, then `logs.txt`,
  `stack.txt` and `screen.png`, and start the fix from what they show. `--clear` deletes the pulled
  reports from the devices afterwards.
- **Testing over adb**: `adb shell am broadcast -n com.kirtanya.reel/.data.SourcesProbe --ez report true`
  (manual), `--es reportTest crash` / `--es reportTest freeze` (automatic ones).

## Step 3 in use: design rules

Every screen render (phone 360 × 800 dp light / dark at font 100 % and 130 %, TV, player controls) is
checked against your rules: 48 dp touch areas on the phone, no clipped text or cut-off short labels at
360 dp, text contrast (4.5:1, 3:1 for big text), colours only from the theme tokens, and TV focus always
visible.

- **Run it**: `bash tool/check_design.sh` prints one line per rule: pass, how many old problems are
  on the waiting list (baseline), and how many are new. Releases (`tool/build_release.sh`) run it first
  and stop when something new breaks a rule.
- **Waiting list**: problems that existed before the checks are listed in
  `app/src/test/resources/design-baseline/` (one line per screen + element + measurement). They are
  fixed over time and the list only shrinks; nothing new is added to it without your OK.

## Step 4 in use: automated device round

One command replaces the hand testing of a release on the TV and the phone:
`tool/device_round.sh` (≈ 3–4 min per device once the sample is playing).

- **Only idle devices.** Each device is checked first and skipped (with the reason printed) unless it
  is awake, unlocked, nothing plays, and its launcher or One Stream's home screen is on top. A locked
  phone is never unlocked, a sleeping TV is never woken. Before every key press / tap the round checks
  that its own player is still in front; if someone picks the device up, it stops pressing anything.
- **What it does per device**: installs the newest `~/Desktop/APKs/OneStream-<ver>-<phone|tv>.apk`
  (skipped when that version is already installed; `--force-install`), opens the app, plays the legal
  Tears of Steel sample, waits for the first frame (≤ 2 min), then — TV: OK for the strip, D-pad to
  the seek bar, scrub forward, open Settings, Back, Back; phone: tap for the controls, double-tap right
  (+10 s), swipe-scrub, open the settings sheet, close it. A screenshot after each step.
- **Cleans up**: leaves the player, goes to the launcher, and takes the sample out of Continue
  watching (or puts back your own position if you had it there).
- **Collects** first-frame time, seeks (count, median, slowest 10 %), rebuffers, reader waits,
  "outstanding" engine warnings, crashes, ANRs and any problem report saved during the round
  (pulled with `tool/pull_reports.sh`).
- **Compares with the last clean round** per device and prints `metric · before · now · verdict`.
  Swarms are noisy, so a number is only *worse* when it is both 1.5× and clearly more (first frame
  + 5 s, seek + 2–3 s, rebuffers + 2). A start from cached pieces is not compared with a cold one.
  The round fails (exit 1) only on a crash, an ANR or playback that never started.
- **Files** (not committed): `rounds/<time>/<device>/NN-<step>.png`, `reelstats.txt`, `crash.txt`,
  `meta.json`; `rounds/<time>/summary.json`; `rounds/latest.json` (the comparison point).
- **Options**: `--only phone|tv`, `--phone-apk` / `--tv-apk <file>`, `--dry-run` (safety check and
  plan only, touches nothing).

Parked separately: home disk (`docs/HOME_DISK_PLAN.md`) — fits after step 7.

## Rules for every step
- Work only on the home network for anything torrent-related; drive the TV / phone only when idle.
- Each step ends with: tests pass, device check (when it touches the app), commit by path, memory updated.
- You are asked only for decisions about the product (what it should do / look like), never about code.
