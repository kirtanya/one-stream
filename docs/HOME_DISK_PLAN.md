# Home disk: execution plan

**Binding spec: `docs/HOME_DISK.md`** (disk layout, piece maps, SMB client, home detection, the copy job,
the TV conveyor, sync messages, playback, remote access, sharing, retention, delete choices, failure
modes, telemetry, screens). This file is the plan of work: phases, effort, and when each step is done.

The goal: a USB hard disk on the home router becomes One Stream's shared library and streaming cache.
1. **Download → At home, from anywhere** (best case): the phone sends an encrypted request to the TV (home
   Wi-Fi, or Tailscale when away; no port forwarding). The TV downloads in small slices and moves verified
   data onto the disk ("the conveyor"); the phone shows progress and Done.
2. **Download → On this phone** (fallback, or the TV can't be reached): the phone downloads itself, only on
   allowed networks (home Wi-Fi, mobile data if allowed, Wi-Fi the user approved; never the office). Back on
   the home Wi-Fi, finished downloads are copied to the disk in the background. The phone keeps its copy
   until the user deletes it: per title **Delete from phone / Delete from home disk / Both**.
3. **Watch**: at home every device (phone, TV, Mac) plays straight from the disk. Away, the TV streams the
   home library to the phone over Tailscale. Private; optionally shared with selected people (Tailscale
   node sharing + a guest invite), never public.
4. **Set up now, without the disk**: Settings → Home storage, the remembered Download choice, Downloads
   badges and delete choices, all saying "Not connected" until the disk exists.

Status (2026-10-21): spec written (phase 1.4); **phase 2 built** (set up now, no disk): core models + tests,
Keystore secrets, jcifs + R8 rules + Test, home detection, Settings → Home storage (phone + TV), Download-where
sheet + the P2P network rule (downloads and plays), Downloads badges + delete-where, sync v3 RPC skeleton
(TV answers "Accepted, waiting for home storage"). Not yet tried on the devices. Phases 1.1–1.3 and 3+ wait for
the disk (phase 0). Product questions answered 2026-10-21 (DECISIONS.md).
Phase 2 leftovers for later phases: the `home-outbox` JobScheduler job (the outbox is sent on app start,
network change, Downloads opening and every minute while the app runs), Tailscale address order (6.4).

Effort levels: Medium · High · Extra high · Max · Ultracode (multi-agent workflow, for reviews only).

---

## Facts this plan rests on

- The TV (Acer R4, 32-bit) has **1.7 GB free** of 7.6 GB. It cannot hold a film: it can only pass pieces
  through.
- libtorrent writes only to storage Android has mounted, and Android (no root) cannot mount a network share.
  libtorrent also has no call that makes it forget a piece, so the TV frees space by a **rolling reset**:
  every ~512 MiB, once every verified piece is on the disk, it removes the torrent with its local files and
  re-adds it from trimmed resume data (HOME_DISK §8). No native code; the swarm only ever gets real data.
- The router shares nothing today (SMB 445/139, FTP 21, DLNA 8200 all closed). SMB client:
  **org.codelibs:jcifs 3.0.4** (SMB 2.0.2–3.1.1, SMB1 fallback, 1 MiB requests; ≈ 0.35 MB per APK;
  needs two R8 keep rules or sign-in fails in release builds; HOME_DISK §4, §20).
- Home is detected without a location permission: the paired TV answers its HMAC challenge on the LAN, or
  the network fingerprint matches the one saved at setup. The app never signs in to a share off the home
  network (HOME_DISK §5).
- The existing phone ↔ TV link (SyncServer: paired, HMAC + AES-GCM, port 47821) carries the new requests
  (protocol v3 RPC); away it is reached over Tailscale (HOME_DISK §9).
- Torrent traffic: the TV only at home; the phone only on allowed networks, never the office network.

---

## Phase 0 — Prerequisites (you)

1. Format the disk **exFAT or NTFS** (not FAT32: 4 GB file limit; not APFS / Mac OS Extended). Back up
   anything on it first.
2. Plug it into the router's USB port; in the router page (192.168.1.1) turn on USB storage / file sharing
   (SMB / "Samba" / "Network share"). Turn **off** guest access and anything named "remote access" /
   "internet access" / FTP from WAN. Set a share user + password.
3. Tell me the router's **model name** and the share user (the password you type into the app yourself; it
   is never written into code or chat).
4. On the TV: keep it in network standby / "stay connected" (screen off, not fully off).
5. Before phase 6.4: install **Tailscale** on the phone and the TV and sign both into your account (the TV
   shows a code to enter on tailscale.com). Never pick an "exit node" on the TV.

---

## Phase 1 — Spec + router spike · **Extra high** for the spec, **High** for the spike

| # | Task | Effort | Done when |
|---|------|--------|-----------|
| 1.1 | Probe the router share from the Mac at home with `SmbProbe` (HOME_DISK §20.1): dialect, auth, write/read MB/s, random small reads, big-file check, rename | High | We know SMB1 / SMB2 / SMB3, read + write MB/s, and that a > 4 GB file works |
| 1.2 | Run the same probe inside the app on the TV and the phone (jcifs 3.0.4; jcifs-ng 2.1.10 is the fallback) | High | Both devices read and write the share in a release build (R8 rules in place) |
| 1.3 | Measure from the TV: sequential read MB/s, random small reads, write MB/s over Wi-Fi | High | Numbers recorded; confirms 4K playback headroom |
| 1.4 | Write **docs/HOME_DISK.md** | **Extra high** | Done 2026-10-21; open product questions in HOME_DISK §21 |

---

## Phase 2 — Set up now (no disk needed) · **High**

| # | Task | Done when |
|---|------|-----------|
| 2.1 | `:core` `homedisk/`: DiskLayout + name cleaning, TitleManifest, PartsMap codec, Lease, NetworkFingerprint, HomeNetwork, P2pRule, DeleteWhere, RetentionPolicy, StreamToken, Outbox; `sync/HomeRpc` | Core tests pass (a test per rule / table row) |
| 2.2 | HomeSecrets (Android Keystore); move the pairing secrets into it; redactions in problem reports | Password survives restart; never in logs, reports or backups |
| 2.3 | `HomeDisk` connection manager: home detection, connect, backoff, health, Test, "Not connected" states | Off the home network no sign-in is ever attempted (test + log check) |
| 2.4 | Settings → Devices → **Home storage** on phone and TV (HOME_DISK §17.1–17.2) | Design rules pass; works with the remote on TV |
| 2.5 | Download-where sheet + remembered choice; the P2P network rule on phone downloads ("Waiting for home Wi-Fi") | The office Wi-Fi never starts a phone download unless you allowed it |
| 2.6 | Downloads badges + delete-where dialog (disk options disabled while not connected) | Every row of HOME_DISK §14 renders |
| 2.7 | Sync v3: RPC endpoint skeleton, TV addresses in hello, source filter, `home.config` (phone → TV, encrypted) | TV receives the disk settings from the phone |

---

## Phase 3 — Play from the disk · **High**

| # | Task | Done when |
|---|------|-----------|
| 3.1 | `ChunkReader` (core, 1 MiB chunks, LRU 8, read-ahead) + `SmbDataSource` | Big MKV opens in < 3 s on the TV (the 29 s → 5 s lesson); `ChunkReaderTest` call counts |
| 3.2 | Route `reel-hd://` in the player; playback picks the disk first when a title is complete there | Title plays with zero peers |
| 3.3 | "On home disk" badge on title / Continue watching; a simple "Home disk" browser for any video already on it | Your existing files play on TV and phone |
| 3.4 | Device test on TV + phone: TTFF, seeks, a 2-hour 4K file end to end; device round gains a disk case | ReelStats numbers recorded |

---

## Phase 4 — Phone → disk copy (fallback path) · **High**

| # | Task | Done when |
|---|------|-----------|
| 4.1 | CopyPlan (core) + HomeCopier + JobScheduler job (unmetered, network callback, user-initiated "Copy now") | A finished phone download lands in `library/` on its own when you get home |
| 4.2 | Resume + verify: chunk hashes, `.parts`, re-verify after interruption, full verify before "Phone + home disk" | Kill the app mid-copy → resumes; a flipped byte on the disk is found and rewritten |
| 4.3 | Delete-where actions for phone / disk / both, `.trash` + Undo | Each choice does exactly what it says |

---

## Phase 5 — The conveyor (download to the disk through the TV) · **Extra high**

| # | Task | Done when |
|---|------|-----------|
| 5.0 | STREAMING_V2 addendum (HOME_DISK §8.6): HomeMeta, conveyor API, restore branch | Spec updated first |
| 5.1 | ConveyorPolicy (core: budget, drain, reset facts, finish) + tests | Core tests pass |
| 5.2 | Mover: verified pieces → disk at their offsets, `.parts`, durability point | Pieces on the disk hash-check |
| 5.3 | Rolling reset (crash-safe order) + restore of a pending reset | A file larger than the TV's free space finishes on the disk; kill mid-reset → resumes without corruption |
| 5.4 | Finish: verify pass, move to `library/`, re-run bad pieces | Every piece of the finished file verified |
| 5.5 | Failure handling: disk full, disk unplugged, router reboot, Wi-Fi drop, TV sleep | Each case pauses and resumes on its own |
| 5.6 | Measure the reset overhead (`conv-reset`, target ≤ 20 %) | Numbers recorded; if worse, revisit punch-hole (HOME_DISK §8.2) |

---

## Phase 6 — Download at home, end to end · **High**

| # | Task | Done when |
|---|------|-----------|
| 6.1 | RPC ops `home.download / jobs / cancel / library / delete / libraryChanged` with idempotency | Same request twice → one job |
| 6.2 | TV job queue (one conveyor at a time) + phone outbox (JobScheduler) + polling | Phone shows Waiting → Queued → 42 % → On home disk |
| 6.3 | Notifications: progress, done, failed, disk full | One notification per event; tap opens the title |
| 6.4 | Tailscale address in hello + phone address order (last good → LAN → tailnet) | From mobile data, Download at home reaches the TV |

---

## Phase 7 — Watching away · **High** (7.3 **Extra high**)

| # | Task | Effort | Done when |
|---|------|--------|-----------|
| 7.1 | HomeStreamServer (port 47822, tokens, ranges, source filter) + `home.stream` | High | Phone plays a disk title from mobile data |
| 7.2 | Bandwidth check + "may pause away from home" warning | High | Warning matches measured throughput |
| 7.3 | Hybrid reader: disk / local / swarm for a title still arriving (TV in-process + served to the phone) | **Extra high** | A half-moved film plays through seamlessly |

---

## Phase 8 — Retention + stream cache on the disk · **Medium** (High for 8.2)

| # | Task | Effort | Done when |
|---|------|--------|-----------|
| 8.1 | Settings: keep cache N days (default 7), cache share of the disk; synced to the TV | Medium | Values persist on both devices |
| 8.2 | Eviction on the TV (RetentionPolicy; never library, leases, playing, Keep) | High | Unit-tested policy + verified on the disk |
| 8.3 | Stream cache to the disk (what the TV streams) and auto-cache from details, if you want them (HOME_DISK §21 Q5) | Medium | Opening details then pressing play later starts instantly |

---

## Phase 9 — Sharing with selected people · **High**

| # | Task | Done when |
|---|------|-----------|
| 9.1 | Guest invites (`guest.create / redeem / revoke`), guest rights, ACL text for Tailscale | An invited person plays a title; revoking stops them |

---

## Phase 10 — TV always-on hardening · **High**

| # | Task | Done when |
|---|------|-----------|
| 10.1 | Jobs keep running with the screen off: foreground service, Wi-Fi lock, wake lock only while a job runs | An overnight conveyor job completes |
| 10.2 | Survive TV reboot (resume jobs on boot) and app updates | Job resumes after a reboot |
| 10.3 | Heat / storage safety on the 32-bit TV: CPU and local disk caps; Tailscale stays up in standby | No throttling or full-storage errors; RPC works after a night in standby |

---

## Phase 11 — Review · **Ultracode** (then **High** for fixes)

| # | Task | Effort |
|---|------|--------|
| 11.1 | Multi-agent adversarial review of phases 2–10 (data loss, corruption, concurrency, credentials, remote path, sharing) | Ultracode |
| 11.2 | Apply confirmed fixes, re-run core tests | High |

---

## Phase 12 — Device verification + release · **Medium**

| # | Task | Done when |
|---|------|-----------|
| 12.1 | End to end at home: Download at home, phone copy, play from disk on TV + phone + Mac, delete choices, retention | All pass, ReelStats recorded |
| 12.2 | From outside (mobile data + Tailscale): request → done at home → plays away | Pass |
| 12.3 | Build, install both, commit, update memory | Released |

---

## Effort at a glance

| Phase | Effort |
|---|---|
| 0 Prerequisites | — (you) |
| 1 Spec + spike | **Extra high** (spec, done) / High (spike) |
| 2 Set up now | High |
| 3 Play from disk | High |
| 4 Phone copy | High |
| 5 Conveyor | **Extra high** |
| 6 Download at home | High |
| 7 Away | High (7.3 **Extra high**) |
| 8 Retention | Medium (8.2 High) |
| 9 Sharing | High |
| 10 TV always-on | High |
| 11 Review | **Ultracode**, fixes High |
| 12 Verify + release | Medium |

Order: 2 (now) → 1.1–1.3 (when the disk exists) → 3 → 4 → 5 → 6 → 10 → 7 → 8 → 9 → 11 → 12.

## Decisions I need from you

Product questions are in HOME_DISK §21: router model, mobile data and other Wi-Fi for phone downloads,
whether Play follows the same network rule, stream cache + auto-cache on the disk, cache size, big files
away, and what shared people may do.
