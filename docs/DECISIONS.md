# Decision log

Newest first. Each entry: date, decision, why, evidence. Add one whenever something is decided
(product, engine, tooling); link the commit or spec section that carries it.

---

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
