# Home disk: execution plan

The goal: a hard disk on the home router becomes One Stream's shared library and streaming cache.
The TV (always on, screen off) downloads to it; the phone can ask for downloads from home or from
outside; every device at home plays from it instantly. Cached titles stay 7 days (adjustable).

This file is the plan of work. Each phase names the **effort** to switch to before it starts; the
whole phase runs at that effort unless a task inside says otherwise.

Effort levels: Medium · High · Extra high · Max · Ultracode (multi-agent workflow, for reviews only).

---

## Facts this plan rests on

- The TV (Acer R4, 32-bit) has **1.7 GB free** of 7.6 GB. It cannot hold a film: it can only pass
  pieces through.
- The torrent engine (libtorrent) writes only to storage Android has mounted. Android (no root)
  cannot mount a network share. So nothing downloads *straight* to the router disk: the TV
  downloads a slice locally, copies it to the disk, frees it ("the conveyor").
- The router shares nothing today (SMB 445/139, FTP 21, DLNA 8200 all closed). Which protocol it
  offers once the disk is attached (SMB1 vs SMB2/3) decides the client library.
- The existing phone ↔ TV link (SyncServer: paired, HMAC + AES-GCM, port 47821) carries the
  "cache this" requests; it already works at home.
- Torrent traffic only ever runs at home (phone at home, or the TV). From outside, the phone only
  sends a small encrypted request.

---

## Phase 0 — Prerequisites (you)

1. Format the disk **exFAT or NTFS** (not APFS / Mac OS Extended). Back up anything on it first.
2. Plug it into the router's USB port; in the router page (192.168.1.1) turn on USB storage /
   file sharing (SMB / "Samba" / "Network share"). Turn **off** guest access and anything named
   "remote access" / "internet access" / FTP from WAN. Set a share user + password.
3. Tell me the router's **model name** and the share user (the password you type into the app
   yourself; it is never written into code or chat).
4. On the TV: keep it in network standby / "stay connected" (screen off, not fully off).

---

## Phase 1 — Spec + router spike · **Extra high** for the spec, **High** for the spike

| # | Task | Effort | Done when |
|---|------|--------|-----------|
| 1.1 | Probe the router share from the Mac (protocol version, auth, speed, file-size limits, max path) | High | We know SMB1 / SMB2 / SMB3, read + write MB/s, and that a 5 GB file can be written and read back |
| 1.2 | Choose the SMB client: jcifs-ng (SMB2/3) or a legacy client if the router only speaks SMB1; check it runs on the 32-bit TV | High | A tiny test app reads and writes the share from the TV and the phone |
| 1.3 | Measure from the TV: sequential read MB/s, random small reads, write MB/s over Wi-Fi | High | Numbers recorded; confirms 4K playback headroom |
| 1.4 | Write **docs/HOME_DISK.md** (binding spec, like STREAMING_V2.md): disk layout, `.parts` piece map format, conveyor algorithm, how local space is freed, hybrid playback, retention, sync messages, failure modes (disk unplugged, Wi-Fi drop, router reboot, TV sleep) | **Extra high** | Spec reviewed by you; open questions answered |

**Key design question to settle in 1.4 (the hard one):** how the TV frees local space for pieces
already copied while libtorrent still thinks it has them. Candidates to evaluate:
- punch holes in the local file (small native helper, `fallocate(PUNCH_HOLE)`) and never upload
  or re-read those pieces locally;
- per-slice file windows (re-add the torrent with only the next slice wanted, resume data trimmed);
- a disk budget so small that slices are tiny, plus copy-on-verify.
The spec picks one with measurements, not guesses.

---

## Phase 2 — Home disk core · **High**

| # | Task | Done when |
|---|------|-----------|
| 2.1 | `:core` model: disk layout (`/OneStream/library`, `/OneStream/cache/<infohash>/…`), title manifest, `.parts` bitfield format, retention policy (pure, unit-tested) | Core tests pass |
| 2.2 | Credentials stored with Android Keystore (encrypted prefs); never logged | Password survives restart; not in logs/backups |
| 2.3 | `HomeDisk` connection manager: "am I home" (gateway MAC / SSID), connect, reconnect with backoff, health check, free/used space | Survives router reboot and Wi-Fi drop without crashing playback |
| 2.4 | Settings → **Home disk** page: connect, test speed, show space, disconnect | Works on phone and TV (remote-friendly) |

---

## Phase 3 — Play from the disk · **High**

| # | Task | Done when |
|---|------|-----------|
| 3.1 | `SmbDataSource` for media3, with the same lessons as the torrent reader: 256 KiB read buffer + read-ahead thread, never a syscall per small read | Big MKV opens in < 3 s on the TV (the 29 s → 5 s lesson) |
| 3.2 | Route it in `ReelDataSourceFactory`; playback picks the disk first when a title is fully there | Title plays with zero peers |
| 3.3 | "On home disk" badge on title / Continue watching; a simple "Home disk" browser for any video already on it | Your existing files play on TV and phone |
| 3.4 | Device test on TV + phone: TTFF, seeks, a 2-hour 4K file end to end | ReelStats numbers recorded |

---

## Phase 4 — The conveyor (download to the disk) · **Extra high**

| # | Task | Done when |
|---|------|-----------|
| 4.1 | `DiskCacheJob`: sequential download in slices within a local budget (TV default ~600 MB) | A file larger than the TV's free space finishes on the disk |
| 4.2 | Piece mover: copy each verified piece range into the remote file at its offset, update `.parts`, fsync-equivalent, then free locally (method from 1.4) | Kill the app mid-copy → resume without corruption (hash-checked) |
| 4.3 | Hybrid playback: a reader that takes pieces from the disk where `.parts` says so, else local / swarm | A half-cached film plays through seamlessly |
| 4.4 | Good swarm citizen: never advertise or upload pieces we no longer hold locally | No hash-fail reports from peers |
| 4.5 | Failure handling: disk full, disk unplugged, router reboot, Wi-Fi drop, TV sleep | Each case pauses and resumes on its own |

---

## Phase 5 — Triggers · **High**

| # | Task | Done when |
|---|------|-----------|
| 5.1 | TV: opening a title's details starts caching the likely source (setting, size cap, home Wi-Fi only) | Opening details then pressing play later starts instantly |
| 5.2 | Phone: **Download at home** button → new SyncServer message (request, ack, queue, progress back) | Phone shows Queued → 42 % → Done |
| 5.3 | Phone downloads at home copy themselves to the disk; "Keep" moves finished downloads to the disk | Phone storage freed, title shows "On home disk" |
| 5.4 | Notifications: done / failed / disk full | One notification per event, tap opens the title |

---

## Phase 6 — Retention + management · **Medium** (High for 6.2)

| # | Task | Effort | Done when |
|---|------|--------|-----------|
| 6.1 | Settings: keep cache for N days (default 7; 1 day … forever), disk budget | Medium | Values persist and sync to the TV |
| 6.2 | Eviction on the TV: oldest first, never a title you're partway through or one marked Keep; runs daily and when full | High | Unit-tested policy + verified on the disk |
| 6.3 | "Cached at home" screen: titles, size, age, Clear one / Clear all | Medium | Works on phone and TV |

---

## Phase 7 — From outside the home · **High** (Extra high for 7.3)

| # | Task | Effort | Done when |
|---|------|--------|-----------|
| 7.1 | Tailscale path: phone + TV on your tailnet; the existing paired link works over it (TV address by MagicDNS name) | High | From mobile data, Download at home reaches the TV |
| 7.2 | Offline queue on the phone: requests wait until the TV is reachable, then send | High | Request made in airplane mode lands later |
| 7.3 | *(Optional, later)* Firebase push to wake the TV app without a VPN; payload encrypted with the pairing key | Extra high | Works with Tailscale off |

---

## Phase 8 — TV always-on hardening · **High**

| # | Task | Done when |
|---|------|-----------|
| 8.1 | Downloads keep running with the screen off: foreground service, Wi-Fi lock, wake lock only while a job runs | An overnight cache job completes |
| 8.2 | Survive TV reboot (resume jobs on boot) and app updates | Job resumes after a reboot |
| 8.3 | Heat / storage safety on the 32-bit TV: CPU and local disk caps | No throttling or full-storage errors |

---

## Phase 9 — Review · **Ultracode** (then **High** for fixes)

| # | Task | Effort |
|---|------|--------|
| 9.1 | Multi-agent adversarial review of phases 2–5 and 8 (data loss, corruption, concurrency, security of credentials and the remote path) | Ultracode |
| 9.2 | Apply confirmed fixes, re-run core tests | High |

---

## Phase 10 — Device verification + release · **Medium**

| # | Task | Done when |
|---|------|-----------|
| 10.1 | End-to-end on TV + phone at home: cache from details, Download at home, play from disk, retention clears | All pass, ReelStats recorded |
| 10.2 | From outside (mobile data + Tailscale): request → done at home → plays when back | Pass |
| 10.3 | Build, install both, commit, update memory | Released |

---

## Effort at a glance

| Phase | Effort |
|---|---|
| 0 Prerequisites | — (you) |
| 1 Spec + spike | **Extra high** (spec) / High (spike) |
| 2 Core | High |
| 3 Play from disk | High |
| 4 Conveyor | **Extra high** |
| 5 Triggers | High |
| 6 Retention | Medium (6.2 High) |
| 7 Outside home | High (7.3 Extra high) |
| 8 TV always-on | High |
| 9 Review | **Ultracode**, fixes High |
| 10 Verify + release | Medium |

Order: 0 → 1 → 2 → 3 → 4 → 5 → 6 → 8 → 7 → 9 → 10. Phases 3 and 6.1/6.3 can overlap; 7 waits for 5.2.

## Decisions I need from you (before or during phase 1)

1. Router model + which share protocol it offers (we find out in 1.1).
2. Auto-cache from the details page: on by default, or off until you enable it?
3. Disk budget for the cache (e.g. 500 GB of a 1 TB disk) and the default retention (7 days).
4. TV local slice budget (default ~600 MB of its 1.7 GB free).
5. Remote access: Tailscale first (recommended), Firebase push later or never?
