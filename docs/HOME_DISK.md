# One Stream home disk: implementation spec (revision 1)

Status: **binding** for everything marked **[Decided]**. Items marked **[Open]** need the user (product
questions, listed again in §21); items marked **[Measure]** are decided provisionally and get confirmed
or replaced by a number from the devices. Plan of work and effort per phase: `docs/HOME_DISK_PLAN.md`.
Engine behaviour stays governed by `docs/STREAMING_V2.md`; this spec adds the home-disk parts and names
the engine changes it needs (§8.8, §18). Written 2026-10-21 against libtorrent4j 2.1.0-39, media3 1.11.1,
NanoHTTPD 2.3.1 and org.codelibs:jcifs 3.0.4 (APIs checked in the jars, §20).

Hard rules, unchanged and binding here too:
- **No BitTorrent traffic from the work Mac or the office network.** The phone's own downloads follow the
  network rule in §5.3; the TV only ever downloads at home. Tests that move P2P traffic run at home only.
- Built-in sources stay legal. Nothing here adds sources.
- Pure logic lives in `:core` (`core/homedisk/`), with JVM tests; nothing in a test opens a torrent session
  or talks to a real share.
- Nothing is ever reachable from the public internet: no port forwarding, no Tailscale Funnel, no public
  links. Private (paired devices) by default; selected people only through Tailscale node sharing (§12).

---

## 1. Goals and measurable targets

The user's four goals, in priority order:
1. **Best case: Download → At home, from anywhere.** The phone sends an encrypted request to the TV (over
   the home Wi-Fi, or over Tailscale when away). The TV downloads it in small slices to its own storage and
   moves verified data to the USB disk on the router (the "conveyor"). The phone shows progress and Done.
2. **Fallback: the phone downloads itself** (TV unreachable, or the user picks "On this phone"). When the
   phone is next at home, finished downloads are copied to the disk in the background. The phone keeps its
   copy until the user deletes it; per title the user deletes from phone, from the home disk, or both.
3. **Watch.** At home, phone / TV / Mac play straight from the disk (no seeders needed). Away, the TV
   serves the home library to the phone over Tailscale; private, optionally shared with selected people.
4. **Set up now, before the disk exists**: Settings → Home storage, the remembered Download choice,
   Downloads badges and delete choices; everything says "Not connected" until the disk is there.

| Target (ReelStats field, §16) | Phone | TV |
|---|---|---|
| Finished title from the disk at home: `ttff` (1080p MKV) | ≤ 2.5 s | ≤ 3 s |
| Finished title from the disk: seek outside the buffer, p50 | ≤ 1.5 s | ≤ 2 s |
| Rebuffers per hour playing from the disk, file ≤ 80 Mbit/s | 0 | 0 |
| Away through the TV over Tailscale, direct path: `ttff` | ≤ 6 s | — |
| Away: rebuffers per hour when throughput ≥ 1.3 × file bitrate | 0 | — |
| Conveyor: effective rate vs plain download of the same swarm (`conv-done avgDown / plain`) | — | ≥ 0.8 **[Measure]** |
| Conveyor: local bytes held (`conv-tick localMb`) | — | ≤ slice budget + 1 piece |
| Conveyor: data ever uploaded that we no longer hold | — | 0 by construction (§8.4) |
| Kill / power loss mid-job → resume with no corrupt byte in the library | 100 % | 100 % |
| Phone → disk copy rate on home Wi-Fi (`hd-copy mbps`) | ≥ 10 MB/s **[Measure]** | — |
| Request made with the TV unreachable reaches it after it becomes reachable | ≤ 2 min | — |
| SMB sign-in attempts on a network that isn't home (`hd-connect` with `home=0`) | 0 | 0 |

---

## 2. Architecture

```
              HOME (router LAN 192.168.1.x)                                    AWAY
 ┌──────────────────────────────────────────────────────────────┐
 │ Router + USB disk ── SMB share "…/OneStream/" ─────────────┐ │
 │        ▲  ▲  ▲                                             │ │
 │   SMB  │  │  │ SMB (finished titles)            Mac: Finder / VLC on library/
 │        │  │  └──────────────── Phone at home: SmbDataSource, copy job (§7)
 │        │  │                                                │ │
 │  Acer R4 TV (always on, network standby)                   │ │
 │   • SyncServer :47821 (paired RPC, §9)  ◄── LAN ──────────── Phone at home
 │   • HomeStreamServer :47822 (tokens, §11)                  │ │
 │   • Conveyor (libtorrent → local slice → disk, §8)         │ │        Phone on mobile data / other Wi-Fi
 │   • Retention (§13), library index                         │ │          │ Tailscale (WireGuard)
 │   • Tailscale app (100.x / MagicDNS) ◄────────────────────────────────────┘  :47821 RPC, :47822 stream
 └──────────────────────────────────────────────────────────────┘
```

Roles **[Decided]**:
- **The TV is the home agent**: it is always on and at home, so it owns at-home downloads, the library
  index, retention, disk deletes when reachable, and every connection from outside.
- **The phone is the controller and the fallback downloader**: it sets things up (typing a password with a
  remote is painful, §6), asks the TV for downloads, downloads itself when it must, copies its own
  downloads to the disk, and plays.
- **The router disk is passive storage** (an SMB share). Nothing runs on the router.
- **The Mac** uses the share like any network folder; nothing is built for it. `library/` is laid out for
  browsing (§3.1).

Data paths:
- At-home download: phone → (LAN or Tailscale) → TV RPC `home.download` → TV conveyor → disk `incoming/`
  → verified → `library/`.
- Phone download: phone libtorrent (allowed networks only, §5.3) → phone storage → at home: copy job →
  disk `incoming/` → verified → `library/`.
- Play at home, finished title: device → SMB → `SmbDataSource` (§10).
- Play at home, title still being conveyed: phone → TV `HomeStreamServer` → hybrid reader (§10.4).
  The TV itself uses the hybrid reader in-process.
- Play away: phone → Tailscale → TV `HomeStreamServer` → SMB / hybrid reader (§11).

---

## 3. Disk layout, manifests and the piece map

### 3.1 Layout **[Decided]**

```
<share>/OneStream/
  README.txt                      "Managed by One Stream. library/ holds your titles and is safe to copy or
                                   play. Don't change cache/, incoming/ or the .onestream folders."
  .onestream/disk.json            { schema, diskId (random 128-bit hex), createdAt, createdBy }
  library/                        finished titles the user keeps (never evicted)
    Movies/<Title> (<Year>)/<original file name>
    Shows/<Show> (<Year>)/Season <NN>/<original file name>
    Other/<torrent name>/<torrent paths>         (no TMDB origin, e.g. a pasted magnet)
    …/<title folder>/.onestream/<key>.json       manifest, one per torrent key in that folder
  incoming/<key>/                 in progress: at-home downloads and phone copies
    <torrent-relative paths>      sparse data files at their final size
    .onestream/manifest.json
    .onestream/parts/<fileIndex>.parts           piece map per data file (§3.3)
    .onestream/lease.json                        single-writer lease (§3.4)
  cache/<key>/                    stream cache on the disk (retention §13), same inner layout as incoming/
  .trash/<key>-<yyyymmddHHmm>/    recent deletes, purged after 3 days or when space is needed (§14)
```

- `<key>` is the canonical torrent key (`TorrentKey.canonical`, 40 hex, STREAMING_V2 §3.3).
- Names **[Decided]**: `<Title>` / `<Show>` / `<Year>` come from the download origin (TMDB; the same data
  as `DownloadOrigin`). Each path component is cleaned for SMB file systems: `< > : " / \ | ? *` and control
  characters become a space, runs of spaces collapse, trailing dots and spaces are trimmed, at most 120
  characters per component (cut before the extension), total path under the share ≤ 240 characters. A
  name already used by a different key gets ` (2)`, ` (3)`, … (file systems on routers are case-insensitive,
  so the comparison ignores case).
- A file is never visible in `library/` before it is complete and verified (§3.5 invariant I5), so the Mac
  never opens a half-written file.
- Data files in `incoming/` and `cache/` are created at their final size (`setLength`, sparse where the file
  system allows), so writes at any offset are plain random-access writes.
- Only wanted files exist on the disk. Bytes of an unwanted neighbour that share an edge piece are dropped.

### 3.2 Title manifest (JSON, `schema: 1`) **[Decided]**

(The `//` notes are for this spec only; the file is plain JSON.)

```json
{
  "schema": 1,
  "key": "c12fe1c06bba254a9dc9f519b335aa7c1367a88a",
  "name": "Dune.Part.Two.2024.2160p.WEB-DL",              // torrent name
  "origin": { "type": "movie", "tmdbId": 693134, "season": null, "episode": null, "label": "Dune: Part Two (2024)" },
  "kind": "library",                                     // library | cache
  "state": "incoming",                                   // incoming | complete
  "pieceLength": 4194304, "totalSize": 25165824000,      // torrent layout, to map pieces ↔ files
  "files": [ { "index": 0, "offset": 0, "size": 25165824000,
               "path": "Dune.Part.Two.2024.2160p.WEB-DL.mkv",                 // inside incoming/<key>/
               "libraryPath": "Movies/Dune Part Two (2024)/Dune.Part.Two.2024.2160p.WEB-DL.mkv" } ],
  "writer": { "device": "tv:9f2c11aa", "via": "conveyor" },   // conveyor | phone-copy
  "verify": "pieces-v1",                                 // pieces-v1 | chunks-sha256 (phone copies of v2-only torrents)
  "chunkHashes": null,                                   // phone copies of v2-only torrents: 8 MiB SHA-256 list
  "createdAt": 1761040000000, "completedAt": null, "lastUsedAt": 1761040000000
}
```

- Written with the same atomic replace as the piece map (§3.3), so a reader sees the old or the new file.
- `offset` is the file's start in the torrent's byte space (the `FileSpan` math in `core/stream/FileSpan`).
- On completion the manifest moves to `<title folder>/.onestream/<key>.json` with `state: complete`.
- **Library listing**: the TV scans `library/**/.onestream/*.json` (depth ≤ 4), `incoming/*/.onestream/manifest.json`
  and `cache/*/.onestream/manifest.json`, keeps an in-memory index, and re-scans every 10 min, on
  `home.libraryChanged` (§9) and after its own changes. There is no central index file to corrupt.

### 3.3 Piece map: `.onestream/parts/<fileIndex>.parts` **[Decided]**

One map per data file, recording which byte ranges of that file are written, flushed and verified. Byte
ranges (not piece numbers) keep it independent of the torrent (phone copies use the same format) and let a
reader decide from one small file. For a torrent, "piece p is on the disk" ⇔ every file range of p (FileSpan
math) lies inside that file's map.

Binary, big-endian:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `OSPM` (0x4F 0x53 0x50 0x4D) |
| 4 | 2 | version = 1 |
| 6 | 2 | flags (bit 0 = complete: one range `[0, fileSize)`) |
| 8 | 8 | fileSize |
| 16 | 8 | generation (+1 on every rewrite) |
| 24 | 8 | updatedAtMs (writer's clock, informational only) |
| 32 | 4 | N = number of ranges |
| 36 | 16·N | ranges: `start` u64, `endExclusive` u64; sorted, non-overlapping, non-touching, inside `[0, fileSize]` |
| 36 + 16·N | 4 | CRC-32 (IEEE) of bytes `[0, 36 + 16·N)` |

- Sequential downloads and copies give a handful of ranges; the worst case (rarest-first, 5,000 pieces) is
  80 KB.
- **Write**: `X.parts.tmp` → `renameTo(X.parts.prev, replace)` of the current file → `renameTo(X.parts, replace)`
  of the tmp. **Read**: `X.parts`; if missing, short or CRC-bad, `X.parts.prev`; else empty. An older map
  only means re-copying, never trusting unwritten data.
- Pure codec + interval set in `core/homedisk/PartsMap.kt` (merge, contains, firstGap, encode/decode, CRC),
  with tests: round-trip, CRC damage → prev, overlapping inserts merge, `contains` across range edges,
  size 0, a 5,000-range file.

### 3.4 Single-writer lease **[Decided]**

- One writer per title folder at a time (conveyor, phone copy, eviction or delete). `lease.json` =
  `{ device, deviceName, op, counter, since }`.
- Acquire: `createNewFile` (fails when it exists). Renew: rewrite every 60 s with `counter + 1`.
- Stale: another device that sees the same `counter` for 10 min of **its own** monotonic time may delete it
  and acquire. Counters, not timestamps, so the devices' clocks never matter.
- Release on finish, cancel or clean stop. Readers never need the lease.

### 3.5 Invariants (every component, every phase)

- **I1 Data before map.** A range enters a `.parts` file only after its bytes were written and the durability
  point (§4.4) returned.
- **I2 Map before free.** The TV deletes local data only for pieces that a persisted map already covers (§8).
- **I3 One writer per title** (§3.4).
- **I4 Never read past the map.** Readers read only mapped ranges, or files whose manifest is `complete`.
- **I5 library/ is complete and verified** (§7.4, §8.6).
- **I6 No sign-in to an SMB server off the home network** (§5.1): a stranger's server could capture the
  NTLMv2 response and crack the password offline.

---

## 4. SMB client

### 4.1 Library **[Decided]**: `org.codelibs:jcifs:3.0.4`

The maintained successor of jcifs-ng (same design, package `org.codelibs.jcifs.smb`). Why, from the jar
checks in §20.1:
- **SMB 2.0.2 – 3.1.1, with SMB1 as an opt-in fallback.** The router is unknown (Phase 0); many router
  firmwares only speak SMB1 or SMB 2.0.2. smbj has no SMB1 at all.
- **Large reads and writes.** 3.0.4 charges SMB2 credits per payload and reads / writes up to 1 MiB per
  request (`jcifs.client.maxTransferSize`, default 1 MiB) when the server offers large MTU. jcifs-ng
  2.1.10 always charges one credit, so on SMB 2.1+ it is held to 64 KiB per round trip.
- **Maintained** (3.0.4: 2026-09-27; jcifs-ng's last release is 2.1.10 from 2023-10-15).
- **Pure Java** (no native code, so armeabi-v7a is no different from arm64). Java 17 bytecode; D8 desugars
  it for minSdk 28 (the only post-28 JDK references are the lambda / string-concat bootstraps D8 rewrites).
- SMB3 signing and encryption exist (BouncyCastle lightweight AES, pure Java, slow on the TV). Neither is
  needed on the home LAN; both stay off unless the server requires them.

Fallback if 3.0.4 misbehaves on a device in Phase 1.2: `eu.agno3.jcifs:jcifs-ng:2.1.10` (same API family,
package `jcifs`), with 64 KiB requests and the parallel read-ahead of §10.2 doing the pipelining.

### 4.2 Build integration **[Decided]**

- `implementation("org.codelibs:jcifs:3.0.4")` in `:app` only (the `:core` logic talks to an interface,
  `RemoteFile` §10.2, so core tests need no SMB). It brings `bcprov-jdk18on:1.85` and `slf4j-api:1.7.36`.
- R8 rules (`app/proguard-rules.pro`), all required:
  ```
  -dontwarn javax.servlet.**                 # jcifs NTLM-over-HTTP helpers, never used
  -dontwarn javax.security.auth.**           # Kerberos / JAAS paths, never used
  -dontwarn org.ietf.jgss.**
  -dontwarn jakarta.**
  -dontwarn org.slf4j.impl.**
  # NTLM needs MD4, which jcifs gets from a BouncyCastleProvider instance by name lookup; R8 can't see it.
  -keep class org.bouncycastle.jcajce.provider.digest.MD4** { *; }
  -keep class org.bouncycastle.crypto.digests.MD4Digest { *; }
  ```
  Without the two keep rules a release build fails every sign-in with "no such algorithm: MD4 for provider
  BC" (reproduced in the spike, §20.1).
- Packaging excludes (bcprov ships ≈ 1.3 MB of non-class resources and multi-release class copies):
  `META-INF/versions/**`, `org/bouncycastle/**/*.properties`, `org/bouncycastle/**/*.bin*`.
- Size: ≈ 680 KiB of dex, ≈ 350 KiB per APK after compression (§20.1). Accepted: it is the feature.
- slf4j has no binding, so jcifs logs nothing (and can't leak paths or user names); one "no binder" line on
  stderr at first use is expected.

### 4.3 Configuration **[Decided]**

```
jcifs.client.minVersion=SMB202        (SMB1 only if the Test found an SMB1-only server, §4.5)
jcifs.client.maxVersion=SMB311
jcifs.client.disableSMB1=true         (false only in SMB1 mode)
jcifs.client.signingPreferred=false   jcifs.client.signingEnforced=false
jcifs.client.encryptionEnabled=false  (true only if the server demands it; the Test says so)
jcifs.client.maxTransferSize=1048576
jcifs.client.connTimeout=5000
jcifs.client.responseTimeout=30000    (a sleeping USB disk can take 5–10 s to spin up)
jcifs.client.soTimeout=35000
jcifs.client.dfs.disabled=true
jcifs.resolveOrder=DNS                (the address is an IP; no NetBIOS broadcasts)
jcifs.lmCompatibility=3               (NTLMv2 only)
```
Credentials go in `NtlmPasswordAuthenticator`, never in a URL (so they can't reach a log or a report).

### 4.4 I/O rules **[Decided]**

- One `CIFSContext` per device (`HomeDisk`), rebuilt after a network change, a server error that kills the
  session, or a settings change.
- Reads: one `SmbRandomAccessFile` per open data source, reused for sequential reads; any `SmbException`
  closes it and the next read reopens (reconnect is transparent to the caller up to the retry budget).
- Writes: one handle per data file while a job writes it. **Durability point** = SMB2 FLUSH of that file
  (a short-lived `SmbFileOutputStream(append = true).flush()`, which sends FLUSH in 3.0.4), then the
  `.parts` update. On SMB1 the durability point is close + reopen, and the post-reconnect re-verify (§8.5,
  §7.3) carries the guarantee. Router file systems (FUSE NTFS, exFAT) don't promise ordering anyway, which
  is why every job re-verifies its newest data after any interruption and verifies everything at the end.
- Never more than 4 concurrent SMB requests per device (read-ahead + one writer); the transport multiplexes
  them on one TCP connection.

### 4.5 Connection manager, health and the Test

`HomeDisk` (app) exposes `state: StateFlow<HomeDiskState>`:
`NotSetUp | Away | Checking | Connected(proto, freeBytes, totalBytes, smb1) | NotConnected(reason)` with
reasons `NoAnswer` (nothing at the address), `NoShare`, `PasswordRejected`, `Full`, `Error(text)`.
- Health check (connect, `getDiskFreeSpace`, stat `.onestream/disk.json`): every 60 s while a job or a
  playback uses the disk; every 15 min when home and idle; on every network change; when Settings opens.
- Failures back off 5 s, 15 s, 30 s, 60 s, then every 5 min. **`PasswordRejected` stops all attempts**
  until the settings change (a router can lock the account), at most 3 sign-ins per hour in any case.
- **Test** (Settings button; also Phase 1.1's probe on the Mac): connect and report the dialect; write
  64 MiB to `OneStream/.onestream/test.bin`, read it back, report MB/s and the hash check; 200 random 64 B
  reads (round-trip time); a **big-file check** (`setLength(4 GiB + 1)` then one byte written at the end:
  a FAT32 disk fails here, and 4K files would fail later); rename with replace; delete. The result is one
  plain line, e.g. "Connected · SMB 3.1.1 · write 11 MB/s · read 24 MB/s · 412 GB free · big files OK".
  If only SMB1 answers, SMB1 mode is switched on for this share and the line says "(old SMB1: slower, fine
  at home)". The first successful Test also writes `disk.json` and records the home network (§5.2).

---

## 5. Home network detection and the phone's P2P network rule

### 5.1 What the platform allows (checked, §20.3)
- SSID / BSSID from `WifiInfo` need ACCESS_FINE_LOCATION and location switched on.
  NEARBY_WIFI_DEVICES covers hotspot / Aware / P2P / RTT APIs, not the connected network's identity.
- The gateway's MAC through `/proc/net/arp` or `ip neigh` is closed to apps since Android 10 / 11.
- LAN access itself needs ACCESS_LOCAL_NETWORK (already in the manifest; enforced for targetSdk 37, and the
  Pixel needed it even at 36 for NSD).

### 5.2 Home detection **[Decided]** (no location permission)

`HomeNetwork.isHome()` is true when **either** holds:
1. **Paired-TV proof on the LAN**: the paired TV, found by NSD (`_reel._tcp`, TXT `id = tvId`) at a
   private LAN address, answers the existing HMAC challenge (`SyncClient.hello` with `challenge`). Nobody
   else can produce that proof, so this is the strong signal. It costs one NSD lookup (≤ 6 s) and one
   request, and is cached for the life of the network.
2. **Fingerprint match**: the current non-VPN default network has transport Wi-Fi or Ethernet and its
   fingerprint equals the one saved at setup. Fingerprint = SHA-256 of: IPv4 gateway address(es), DNS
   servers, IPv4 prefix (address & mask), `privateDnsServerName`, `domains` (the same function as
   STREAMING_V2 §7.5 NetworkGate; implemented once in `core/homedisk/NetworkFingerprint.kt`, which
   NetworkGate reuses). Needs only ACCESS_NETWORK_STATE.

- The fingerprint is saved when a Test succeeds ("Home network: saved"), and refreshed whenever (1) holds
  and the fingerprint differs (router settings changed). Settings shows "Forget home network".
- **I6 enforcement**: `HomeDisk` never opens an SMB connection unless `isHome()` is true **on the network
  the socket will use**. Over Tailscale the phone never talks SMB; it talks to the TV.
- The TV is always home: it is only set up there and never moves. It still signs in only while its own
  fingerprint matches the saved one; a replaced or reset router changes the fingerprint, and the TV then
  shows "New router? Test the disk again" instead of sending the password to whatever answers.
- VPN: the rule looks at the underlying network (`NetworkCapabilities` with NOT_VPN, or
  `getUnderlyingNetworks()`), because Tailscale on the phone is a VPN.

### 5.3 The phone's P2P network rule **[Decided]** (defaults decided by the user 2026-10-21)

Applies to every phone download that moves BitTorrent traffic ("On this phone", Keep, Save as download),
evaluated on the network that carries the traffic (non-VPN):

| Network | Phone P2P |
|---|---|
| Home (§5.2) | allowed |
| Mobile data | waits for Wi-Fi by default; allowed when "Download on this phone over mobile data" is on (off by default), or per download with **Start now anyway** **[Decided]** |
| Wi-Fi the user has allowed (per fingerprint, "Allow downloads on this Wi-Fi") | allowed |
| Wi-Fi the user marked "Never here (work)" | never |
| Any other Wi-Fi (office, café, unknown) | **never automatically.** **Ask once per network** (answer remembered by fingerprint): "Allow this Wi-Fi" / "Never here (work)" / "Download at home instead", with the line "Never allow this on a work network". Until answered the download waits ("Waiting for home Wi-Fi") **[Decided]**. The office is "Never here (work)": never, and a blocked download offers "Download at home instead" (or "Download when I'm home"); Start now anyway never works there (hard rule) |

- Waiting downloads start by themselves when an allowed network appears (network callback + the §7.2 job).
- Pure logic: `core/homedisk/P2pRule.kt` (inputs: transport, metered, isHome, fingerprint, allow / block
  sets, user settings; output: ALLOW | WAIT(reason)), with a test per row.
- **Streaming (Play) follows the rule too [Decided 2026-10-21]**: it is P2P. On a work Wi-Fi Play is refused
  ("Not on this network") with "Download at home instead"; on an unknown Wi-Fi it asks the same once-per-network
  question (one answer for downloads and plays); on mobile data and at home Play is unchanged. Gated at every
  Play entry point outside the player (Sources, Title resume, Continue watching, Downloads, Add link, launcher
  links); the player's own next-episode autoplay is not gated (it follows a play that already passed the rule).
- Applied by `homedisk/P2pGate` (phone): downloads on a network that says wait are paused (and tracked in
  `files/home/held.json`); new and restored downloads start paused through `TorrentEngine.downloadHold`, so
  no traffic happens before the rule looks; they resume by themselves on an allowed network. Home here is
  §5.2 (TV proof saves the home fingerprint, so home is known even when the TV is off later).
- First Test with no home network saved: the app asks "Are you at home?" and only then signs in; that Test
  saves the network as home (I6 stays: never on mobile data, another known network or a work Wi-Fi). The
  TV's first Test runs from `home.config` when the phone has just proved it is at home with this TV
  (`HomeConfig.atHome`).

---

## 6. Credentials **[Decided]**

- Stored per device in `HomeSecrets`: an AES-256-GCM key in the **Android Keystore** (alias
  `onestream-home`, non-exportable, no user authentication), ciphertext (iv ‖ data) in
  `noBackupFilesDir/home_secret.bin`. `allowBackup` is already false. Jetpack security-crypto is not used
  (deprecated).
- Plain settings (address, share, user, SMB1 mode, home fingerprint) live in a normal preferences file.
- **Set up on the phone, delivered to the TV**: the phone sends `home.config` (§9) through the paired RPC,
  encrypted end to end with the pairing secret (AES-GCM, as today's sync). The TV stores it in its own
  Keystore. The TV's Settings page can also edit the fields (remote-friendly), but the hint says "Easier on
  your phone".
- The password never appears in logs, problem reports, URLs, ReelStats or exceptions. `ReportPolicy`'s
  secret scrubber gets the user name and share path as extra redactions.
- Phase 2.2 also moves the existing pairing secrets (`sync_tv` / `sync_phone` preferences, plain today)
  into `HomeSecrets`, since they now protect the SMB password in transit.

---

## 7. Phone → disk copy job (the fallback path)

### 7.1 What gets copied **[Decided]**
- Every finished engine download on the phone (DOWNLOAD mode, all wanted files done), when "Copy phone
  downloads to the home disk" is on (default on). Direct-link downloads (Android DownloadManager) are not
  in v1: they have no key or origin record today; they come later with a file-hash key.
- Destination `incoming/<key>/` → `library/…` when verified. If the library already has `<key>` complete
  with the same files, nothing is copied and the title is marked "Phone + home disk".

### 7.2 Scheduling **[Decided]**: platform JobScheduler (no WorkManager)
- One persisted job id `home-copy`: `setRequiredNetworkType(NETWORK_TYPE_UNMETERED)`,
  `setRequiresBatteryNotLow(true)`, `setPersisted(true)`, exponential backoff 30 s → 5 h, estimated bytes.
  It is enqueued when a phone download finishes, when the app starts, and from a
  `ConnectivityManager.registerNetworkCallback(request, PendingIntent)` for unmetered Wi-Fi (re-registered
  at app start), so joining the home Wi-Fi starts it within seconds even with the app closed.
- The job first checks `HomeNetwork.isHome()`; not home → it ends without work (the callback brings it
  back). Home → it copies until done or until the system stops it (background jobs get ~10 min on
  Android 14+); the copy is chunked and resumable, so a stop only costs the current chunk.
- "Copy now" in the title's menu (app visible) schedules a **user-initiated data transfer job**
  (`setUserInitiated(true)`, API 34+, RUN_USER_INITIATED_JOBS) with a progress notification and no 10 min
  limit.
- Why not WorkManager: it adds a dependency (Room) for features the platform already has here (both
  devices are API ≥ 28; the copying device is a Pixel 8 on Android 16+), and WorkManager has no
  user-initiated transfer. The decision logic (`CopyPlan`, §7.3) is pure and tested in `:core`; the
  JobService is a thin adapter.

### 7.3 Copy algorithm (pure `core/homedisk/CopyPlan.kt` + app `HomeCopier`)
```
for each title in the queue (oldest first), per wanted file:
  acquire the lease on incoming/<key>/ (§3.4); create the folder, manifest (state incoming), and the data file at its size
  map = read parts/<i>.parts
  if this job was interrupted before (job state "dirty"): re-verify the last 8 chunks before the map's end; drop any that fail
  for each 8 MiB chunk not in map (in order):
     read source bytes (1 MiB buffer) → SHA-256 of the chunk → SMB write at the chunk's offset (1 MiB requests)
     every 64 MiB and at file end: durability point (§4.4) → map.add(written ranges) → write .parts → persist job state
         (chunk hashes kept in the phone's job file files/home/copy/<key>.json)
  verify pass: read every chunk back from SMB, hash, compare; a mismatch rewrites that chunk (≤ 3 tries) and re-verifies;
     torrent files whose torrent has v1 piece hashes are verified per piece against the torrent instead (same loop)
  manifest state complete → move files to their library paths (SMB rename, same share) → manifest to the title folder
  → delete incoming/<key>/ → release the lease → Downloads badge "Phone + home disk", notification "Copied … to your home disk"
```
- Source changed or deleted mid-copy (user deleted the phone copy) → cancel, delete `incoming/<key>/`.
- Lease held by a live TV job on the same key (the user also asked for it at home) → this copy waits;
  when the TV finishes first, the copy finds the title complete and skips.
- Conflict in `library/` (same folder name, other key) → ` (2)` suffix (§3.1).
- Partial copies stay in `incoming/` with their maps; retention (§13) deletes an `incoming/` folder with no
  lease and no progress for 14 days.
- Seeding on the phone continues while copying (reads don't disturb it).

### 7.4 Done means verified
"Phone + home disk" appears only after the verify pass. Until then the badge is "Copying to home disk 63 %"
and "Delete from phone" warns that the home copy isn't finished (§14).

---

## 8. The TV conveyor (download to the disk through the TV's small storage)

### 8.1 The problem
libtorrent writes only to storage Android has mounted, and Android (no root) cannot mount an SMB share. The
TV has ~1.7 GB free, so a film passes through it in slices: download a slice locally, copy verified pieces
to the disk, free the local copy. The hard part is freeing local space **while libtorrent keeps a correct
picture of what it holds**: libtorrent advertises every verified piece to peers and serves it on request,
and libtorrent4j has no call that makes it forget a piece (checked, §20.4).

### 8.2 Options evaluated

| Option | Frees space by | libtorrent's view after freeing | Cost | Verdict |
|---|---|---|---|---|
| **A. Punch holes** (`fallocate(PUNCH_HOLE)` via a small JNI helper; NDK 28.2 is installed) | zeroing copied ranges inside the local file | still "has" them: keeps sending HAVEs and **serves zeros** to peers (their hash checks fail, smart-ban bans us); any recheck turns them into missing pieces and re-downloads gigabytes | native build for two ABIs; plus an upload block that libtorrent can't scope to pieces | rejected (bad swarm citizen by construction) |
| **B1. `move_storage`** (`RESET_SAVE_PATH_UNCHECKED` to an empty folder) | nothing by itself; deleting the old folder afterwards | still "has" every piece, files gone → file errors on upload, recheck on restart | none | rejected |
| **B2. Rolling reset: remove + re-add with trimmed resume data** | `removeTorrent(DELETE_FILES)` after every verified local piece is on the disk, then re-add from resume data with have / verified / unfinished cleared and piece priority 0 for pieces on the disk | **exactly right**: holds nothing it can't serve, never re-requests what is on the disk; peers come back from the peer list kept in resume data | a pause of a few seconds per slice (drain + reset + reconnect) | **chosen** |
| **C. Per-slice part file** (file priority 0, pieces raised one by one so libtorrent writes them into its `.parts` file) | — the part file never shrinks; slots are only freed when a file's priority is raised (export), and the format is libtorrent-private | same as B2 after a re-add, with no gain | private format | rejected |
| D. Pure-Java client with storage straight on SMB (e.g. atomashpolskiy/bt) | no local copy at all | own | second engine, unmaintained, no v2 / uTP, worse on the 32-bit TV | rejected |

**Decision [Decided]: B2, the rolling reset**, with a drain phase so in-flight pieces are finished instead of
thrown away. No native code. libtorrent's state is truthful at every moment, so the swarm only ever gets
real data and restarts / rechecks can't corrupt or re-download anything. If Phase 5 measures the reset
overhead above 20 % of throughput (`conv-reset` events, §16), revisit option A **with** a permanent upload
block on conveyor torrents.

Expected overhead (to be measured): slice 512 MiB, swarm 4 MB/s → 128 s of downloading per slice; drain
(finishing partial pieces, still useful work) ≈ 5–15 s; reset itself ≈ 2–5 s; reconnect ramp ≈ 5–15 s →
≈ 10–20 % lost. Download and copying overlap the rest of the time (the mover copies every piece as soon as
it is verified).

### 8.3 Budget **[Decided]**
```
budget = min(setting (default 512 MiB; 256 MiB … 1 GiB),
             free + conveyorLocalBytes − hardFloor(TV 1 GiB, CachePolicy) − 256 MiB)
budget must be ≥ 64 MiB + 4 × pieceLength, else the job waits: "Waiting for space on the TV"
softMark = 0.70 × budget   → start draining
hardMark = budget          → no new piece starts (only partial pieces finish) until the reset
```
Re-evaluated every 10 s and on every reset (the TV's own stream cache can grow meanwhile). A TV stream
of something else takes priority (STREAMING_V2 GUARD pauses unclaimed DOWNLOAD torrents; the conveyor is
one of them).

### 8.4 Algorithm (pure `core/homedisk/ConveyorPolicy.kt` decides; app `Conveyor` applies on `reel-engine`, I/O on `reel-home`)

```
job start (TV): addMagnet / addTorrentFile with mode DOWNLOAD, meta.home = HomeMeta(jobId, dest, gen = 0),
   savePath = files/conveyor/<key>, flags − AUTO_MANAGED, DEFAULT_DONT_DOWNLOAD + explicit wanted-file priorities (FileChoice),
   SEQUENTIAL_DOWNLOAD over the wanted span (keeps in-flight pieces together and allows play-while-downloading)
   pieces already on the disk (maps of an earlier attempt) → priority 0 before resume (added paused, prioritised, resumed)

PIECE_FINISHED(p) on a conveyor torrent → mover queue (ordered by p)

mover (single thread "reel-home"):
   for p: read p's bytes from the local file(s) (RandomAccessFile; the TV is POSIX, no flush gate) in 1 MiB slices,
          hash incrementally; v1 piece hash known → compare (catches a bad local read); split across files by FileSpan;
          SMB write each slice at its file offset (bytes of unwanted files dropped)
   every 32 MiB or 10 s: durability point per touched file (§4.4) → maps += ranges → write .parts → diskSet += pieces
          → persist files/home/conveyor/<key>.json (diskSet bitfield, gen)

tick (1 s) / events:
   local = Σ size of pieces verified since the last reset + bytes of partial pieces (getDownloadQueue on the 5 s tick)
   if local ≥ softMark and state == RUN       → DRAIN: prioritizePieces(only partial pieces > 0, everything else 0)
   if DRAIN and (no partial pieces or 20 s)  → wait for the mover to cover every verified local piece → RESET
   if local ≥ hardMark                        → as DRAIN, and setDownloadLimit(1 KiB/s) until the reset
   all wanted pieces in diskSet               → FINISH

RESET (crash-safe order):
   1 pause(); saveResumeData(SAVE_INFO_DICT) → bytes (≤ 5 s, else retry next tick)
   2 atp = libtorrent.read_resume_data_ex(bytes); set_have_pieces(empty); set_verified_pieces(empty);
     set_unfinished_pieces(empty map); set_piece_priorities(p ∈ diskSet or unwanted → 0, else the plan's priority);
     flags = PAUSED, − AUTO_MANAGED, + SEQUENTIAL_DOWNLOAD; peers and merkle trees are kept as they are
   3 write the trimmed resume (AtomicFile, torrents/<key>.resume) and meta.home.resetPending = true, gen + 1
   4 removeTorrent(h, DELETE_FILES) → wait TORRENT_DELETED (≤ 10 s; on DELETE_FAILED delete files/conveyor/<key> on reel-io)
   5 addTorrent(trimmed) (synchronous add, as at restore); gate.pieces.clearAll(); prioritizePieces(plan) again;
     path generation + 1 (open local readers reopen, STREAMING_V2 §5.1); resume(); resetPending = false
   6 ReelStats ev=conv-reset (§16)
   Restart with resetPending = true → finish from step 4 (the trimmed resume is already the stored one).

FINISH:
   remove the torrent with DELETE_FILES (the TV keeps nothing; TV seeding policy is "stop at finish" anyway)
   verify pass: read every piece back from the disk (1 MiB reads, low priority, paused while anything plays),
     check its hash; a failed piece goes back to priority > 0 in a short re-run of the job (same key, same maps)
   manifest complete → move to library/ (dest = LIBRARY) or keep in cache/ (dest = CACHE) → release lease → job DONE
```

- **Seeding policy [Decided]**: a conveyor torrent uploads only pieces it holds locally (normal tit-for-tat,
  inside the TV's governor caps), never anything from the disk, and stops at finish. No `addPiece` from the
  disk back into libtorrent.
- **One conveyor at a time** on the TV (the budget is small); further jobs wait in FIFO order
  ("Queued at home · #2").
- v2-only torrents (no v1 piece hashes): the mover trusts libtorrent's own verification (PIECE_FINISHED
  means libtorrent hashed it) and the verify pass compares against hashes captured from the local bytes at
  move time (kept in the job file).
- **Disk unavailable** (§15): the conveyor keeps downloading until `hardMark`, then holds; nothing is reset
  while the mover is behind, so no data is lost.

### 8.5 Interplay with streaming, governor and restore
- **Playing a title that is being conveyed** (TV in-process, or the phone through §10.4): the player claims
  the conveyor torrent like a Play-now download (STREAMING_V2 §4.10: base plan all DEFAULT, deadlines for
  urgent pieces). The zone scheduler's `have` input becomes `local ∪ disk` (a `UnionPieceSet`), so pieces
  on the disk are never requested. Resets are deferred while a player claim is ACTIVE, unless `local ≥
  hardMark + 10 %`; a forced reset skips the drain.
- GovernorPolicy: conveyor torrents are unclaimed DOWNLOAD torrents (paused in GUARD while the TV streams
  something else, as today).
- CachePolicy / SpaceGuard: conveyor local data is not stream cache and is not evicted by them; the
  conveyor's own budget replaces SpaceGuard for these torrents.
- RestorePolicy: a new first branch for `meta.home != null`: `saved − AUTO_MANAGED − SEQUENTIAL_DOWNLOAD
  + PAUSED`; the Conveyor resumes it after the disk's health check, finishing a pending reset first.
- After any reconnect to the disk, the mover re-verifies the last 16 pieces it wrote before trusting the
  map again (router file systems don't promise write ordering).

### 8.6 Engine changes this needs (for STREAMING_V2; added there before Phase 5 code) **[Decided]**
- `TorrentMeta.home: HomeMeta? = null` (`jobId`, `dest`, `gen`, `resetPending`), additive.
- `TorrentSnapshot.home: Boolean = false`, additive (Downloads shows "Downloading to home disk").
- `TorrentEngine.addHomeJob(job): String`, `conveyorReset(key, onDisk: PieceSet)`, a PIECE_FINISHED tap for
  conveyor keys, and `UnionPieceSet` as the planner's `have` for claimed conveyor torrents.
- New alerts in the listener: TORRENT_DELETED, TORRENT_DELETE_FAILED.
- RestorePolicy branch (above). Tests: `ConveyorPolicyTest` (budget, marks, drain → reset decision, trimmed
  resume facts: have / verified / unfinished empty, priorities 0 exactly for disk ∪ unwanted, finish
  detection, deferral while claimed, forced reset at hardMark + 10 %), `RestorePolicyTest` new branch.

---

## 9. Sync protocol additions (phone ↔ TV)

### 9.1 Transport **[Decided]**
- Same server (SyncServer, port 47821), same pairing, same crypto (HMAC proof of the TV, AES-GCM to a
  single-use nonce). `SyncProtocol.VERSION = 3`; `hello` keeps its fields and adds
  `addrs: List<String>` (the TV's LAN IPv4 and its Tailscale 100.x address when present, found by listing
  network interfaces for an address in 100.64.0.0/10) and `home: HomeSummary?` (disk state, free bytes,
  active job count). A phone talking to a v2 TV shows "Update One Stream on the TV".
- New endpoint `POST /sync/v3/rpc`, headers `X-Reel-Phone`, `X-Reel-Nonce`, body
  `AES-GCM(secret, RpcRequest, aad = nonce)`, reply `AES-GCM(secret, RpcReply, aad = "reply:" + nonce)`.
  `RpcRequest = { id: String /* UUID, idempotency key */, op: String, body: JsonElement }`,
  `RpcReply = { id, ok: Boolean, body: JsonElement?, error: String? }`. Body cap stays 256 KiB.
- **Source filter (defence in depth)**: SyncServer and HomeStreamServer answer only peers in 10/8,
  172.16/12, 192.168/16, 169.254/16, 127/8 or 100.64/10 (Tailscale); anything else is dropped before
  parsing. Nothing is forwarded on the router, so this never sees the internet anyway.
- **Addresses**: the phone stores `PairedTv.lanHost` (from NSD, as today) and `tailnetHost` (from
  `hello.addrs`, or a MagicDNS name the user types in Settings as a fallback). Order: last address that
  worked, then LAN when `isHome()`, then tailnet. Connect timeout 3 s per address.

### 9.2 Operations **[Decided]**

| op | body → reply | Notes |
|---|---|---|
| `home.download` | `{ magnet? , torrentB64? , fileIdx?, fileHint?, fullTorrent, origin, dest: LIBRARY\|CACHE, requestedAt }` → `{ jobId, state: QUEUED\|STARTED\|DUPLICATE\|REJECTED, reason? }` | `.torrent` only if ≤ 200 KiB, else the magnet (the TV fetches metadata) |
| `home.jobs` | `{ since: Long }` → `{ now, jobs: [ { jobId, requestId, key, label, state, bytesOnDisk, wantedBytes, downBps, etaSec, queuePos, error?, updatedAt } ] }` | `state`: QUEUED, METADATA, DOWNLOADING, VERIFYING, DONE, WAITING_DISK, DISK_FULL, WAITING_SPACE, FAILED, CANCELLED |
| `home.cancel` | `{ jobId }` → `{ state }` | removes the torrent and `incoming/<key>/` |
| `home.library` | `{ sinceVersion }` → `{ version, titles: [ { key, label, origin, kind, state, files: [ { index, size, name } ], sizeOnDisk, lastUsedAt } ] }` | from the TV's index (§3.2) |
| `home.delete` | `{ key }` → `{ ok }` | moves the folder to `.trash/` (§14) |
| `home.stream` | `{ key, fileIdx }` → `{ urls: [lan?, tailnet?], expiresAt }` | token URLs for §11 |
| `home.config` | `{ address, share, user, password, smb1, retentionDays, cacheShare }` → `{ ok, test? }` | the TV runs its own Test and returns the line |
| `home.libraryChanged` | `{ key }` → `{ ok }` | the phone tells the TV it finished a copy (best effort) |
| `guest.*` | §12 | Phase 9 |

- **Idempotency**: the TV keeps `files/home/requests.json` (`requestId → jobId`, 30 days). A repeated
  `home.download` with the same id returns the same job. A new id for a key the TV already has merges the
  wanted files into that job (like `addMagnet(DOWNLOAD)` merging, never narrowing) and returns its jobId
  with `DUPLICATE`. A key already complete in the library returns `DUPLICATE` with state DONE.
- Pure message types and the idempotency map: `core/sync/HomeRpc.kt` (+ tests: duplicate ids, merge of
  wanted files, unknown op → error, v2 TV detection).

### 9.3 The phone's outbox **[Decided]**
- `files/home/outbox.json`: requests not yet acknowledged (`home.download`, `home.cancel`, `home.delete`,
  `home.config`), each with its id, created time and attempts.
- Sent on: app start, network change, the Downloads screen opening, and a persisted JobScheduler job
  `home-outbox` (any network, backoff 30 s → 30 min). Gives up after 7 days ("Couldn't reach your TV for a
  week", with Retry and "Download on this phone instead").
- Phone states shown: *Waiting to reach your TV* → *Queued at home* → *Downloading at home 42 %* →
  *Checking* → *On home disk*. Polling `home.jobs`: every 10 s while Downloads is visible; every 15 min in
  the background while any at-home job is active (same job), for the progress notification and Done.

---

## 10. Playback from the disk

### 10.1 Routing **[Decided]**
A title's play button picks the first that works:
1. Finished on this device → local file (as today).
2. Finished on the disk and `isHome()` → `reel-hd://<key>/<fileIdx>` through `SmbDataSource`.
3. On the disk (finished or partial) and the TV reachable → `home.stream` → HTTP from the TV (§11), on the
   LAN at home, over Tailscale away. At home this path is used only for partial titles.
4. Otherwise the normal source flow (stream from the swarm, P2P rule permitting).

`ReelDataSourceFactory`'s router gets the `reel-hd` scheme; the HTTP path uses the existing OkHttp
source. Start position, Continue watching and subtitles behave as for any other source.

### 10.2 The buffered reader (the 64 KiB lesson, applied to the network) **[Decided]**
The 29 s → 5 s fix (DECISIONS 2026-10-20) came from parsers reading MKV Cues a few bytes at a time, each
read a syscall. Over SMB each unbuffered small read is a network round trip (≈ 2–5 ms on Wi-Fi): 10,000
Cues reads would cost 20–50 s. So:
- `core/homedisk/ChunkReader.kt` (pure, over `interface RemoteFile { val size: Long; fun read(pos: Long,
  buf: ByteArray, off: Int, len: Int): Int }`): 1 MiB chunks aligned to 1 MiB, an LRU of 8 chunks per open
  file (header, Cues / moov at the end and the playhead all stay warm), small reads served from memory.
- Read-ahead: after two sequential chunk misses, a background fetcher keeps 2 requests in flight (4 on the
  TV for 4K) ahead of the reader; a jump of more than 2 chunks cancels it.
- `SmbDataSource` (app, media3 `BaseDataSource(isNetwork = true)`) wraps it; `open()` resolves the file
  through the manifest, honours `DataSpec.position/length`, and throws `IOException` subclasses that the
  load error policy retries (SMB timeouts) up to its default count.
- Test (`ChunkReaderTest`, fake remote with 3 ms latency and a call counter): an MKV-Cues-like pattern of
  10,000 small reads around the end and start makes ≤ 24 remote calls; a sequential read of 64 MiB makes
  ≤ 66 calls; a far seek cancels the read-ahead; a remote error surfaces on the next read, not silently.

### 10.3 LoadControl
Disk sources use the streaming LoadControl of STREAMING_V2 §6.2 (TV 64 MiB target bytes). No extra tuning
until Phase 3 numbers say otherwise.

### 10.4 Hybrid reader for titles still arriving (TV only) **[Decided]**
- `core/homedisk/HybridReaderCore.kt`: per piece run, read from the **disk** when the maps cover it
  (`ChunkReader`), else from the **local** libtorrent file through the existing `StreamReaderCore` / PieceGate
  wait (the player's claim gives the piece a deadline, §8.5), else it waits like any torrent read.
  The disk is checked first: a piece moved during a reset is already mapped (I2).
- The phone never runs this: for a partial title it plays the TV's HTTP stream (§11), which uses it.

---

## 11. Watching away: the TV serves the library over Tailscale

### 11.1 Tailscale **[Decided]**
- The user installs the Tailscale app on the phone and the TV and signs both into their own tailnet (the TV
  signs in with a code shown on screen). One Stream does not embed Tailscale or call its API; it just
  connects to the TV's 100.x address or MagicDNS name, which the VPN routes.
- Tailscale only routes tailnet addresses; BitTorrent on the TV keeps using the normal internet route.
  **Never use an exit node on the TV.**
- Nothing is opened on the router. Funnel and Serve are never used.

### 11.2 HomeStreamServer **[Decided]**
- NanoHTTPD on the TV, fixed port **47822** (so the tailnet ACL can name it), bound to all interfaces with
  the §9.1 source filter. Same patterns as LocalStreamServer: socket bound per handler, disconnect probe,
  `closeConnection(true)`, no gzip, unbounded async runner (readers may block), at most 4 open streams per
  device (the 5th gets 503).
- Routes: `GET|HEAD /h/v1/f/<token>[/<name.ext>]` (Range, 206 / 200 / 416, `Accept-Ranges`, the file's MIME
  type); `GET /h/v1/ping` → 204 (reachability only, no data).
- **Token** = `v1.<deviceId>.<key>.<fileIdx>.<expirySec>.<mac>`, `mac` = first 16 bytes of
  HMAC-SHA256(`streamKey`, everything before it), base64url. `streamKey` is 32 random bytes in the TV's
  Keystore-backed store; "Sign out all devices" rotates it. Issued only by `home.stream` to a paired phone
  (or a guest, §12); valid 6 h; the phone asks for a fresh one when < 30 min remain. Forgetting a phone in
  Settings makes its tokens fail (deviceId no longer paired). Tokens in the path work with external
  players too ("Open in VLC").
- Body: finished titles through `ChunkReader` over SMB (4 in-flight 1 MiB reads); partial titles through
  the hybrid reader (§10.4).
- Plain HTTP is fine: at home it never leaves the LAN, away it rides inside WireGuard.

### 11.3 Bandwidth **[Measure]**
- The ceiling is the slowest of: the home internet upload, the Tailscale path (direct, or a DERP relay when
  both ends sit behind strict NAT, e.g. mobile carrier CGNAT), and userspace WireGuard on the 32-bit TV CPU.
  The TV's Wi-Fi carries each byte twice (disk → TV, TV → router).
- On open the phone measures throughput over the first 8 MiB; if the file's average bitrate
  (size / runtime) > 0.8 × throughput it shows "This file may pause away from home (needs ~40 Mbit/s, you
  have ~12)" with "Other sources" and "Play anyway" **[Decided 2026-10-21: warn and suggest a smaller
  source; Phase 7, no setting in Phase 2]**.
- No transcoding (the R4 can't transcode 4K in real time). The phone LoadControl's 50 s buffer applies.

---

## 12. Sharing with selected people (Phase 9) **[Decided mechanism, Open scope]**
- Only through **Tailscale node sharing** of the TV, done by the user in the Tailscale admin console. The
  recipient needs their own Tailscale account; a shared machine is quarantined (it can receive connections,
  not make them); the share can be revoked. Settings shows the ACL lines to paste once: shared users may
  reach only ports 47821–47822 on the TV.
- **Guest pairing** inside One Stream: on the owner's phone, Settings → Home storage → People →
  "Invite someone" calls `guest.create` on the TV → an invite link (`onestream://invite?d=…`, one-time,
  expires in 48 h) sent however the owner likes. The guest's One Stream redeems it with the TV
  (`guest.redeem`, over the shared node) and gets a guest record: `{ guestId, name, secret, rights, expiresAt }`.
- Guest rights: `home.library` (filtered) and `home.stream` only. No downloads, deletes, settings or jobs;
  one stream at a time per guest. `guest.revoke` from the owner's phone; revoking in Tailscale cuts the
  network path too.
- **[Decided 2026-10-21]**: guests see only titles the owner picks; guests can't request downloads; One
  Stream app or a VLC link. All Phase 9; no settings placeholder in Phase 2 (§17.1 lists People as Phase 9).

---

## 13. Retention and eviction (TV) **[Decided]** (defaults decided 2026-10-21)

- **Defaults [Decided 2026-10-21]**: what the TV plays is kept in the disk cache **7 days** (setting "Keep what
  the TV plays", on); the cache may use **40 %** of the disk (setting); **auto-cache when opening details: off**
  by default (setting). The settings exist from Phase 2 (`RetentionSettings`); eviction and caching run from Phase 8.
- **Only `cache/` is ever evicted automatically.** `library/` is never touched; `incoming/` only when
  abandoned (no lease, no progress for 14 days); `.trash/` after 3 days.
- `RetentionPolicy` (pure, `core/homedisk/`), run by the TV daily at 04:00, after every finished job, and
  whenever free space < floor:
  ```
  floor        = max(20 GB, 5 % of the disk)                         (always left free)
  cacheBudget  = setting, default 40 % of the disk                    [Decided]
  expired      = cache titles with now − max(lastUsedAt, completedAt) > retentionDays (default 7; 1/3/7/14/30/until space is needed)
  evict        = expired, then oldest-first while cacheBytes > cacheBudget or free < floor
  never        = titles with a live lease, being played, being conveyed, or marked Keep
  ```
- "Keep" on a cache title moves it to `library/` (SMB rename, same share).
- Evicting = move to `.trash/` (§14), then purge; ReelStats `hd-evict`.
- Settings changes go phone → TV in `home.config`; the TV is the only device that evicts.

---

## 14. Delete-where semantics **[Decided]**

| The title is… | Delete offers |
|---|---|
| on the phone only | **Delete** (from this phone) |
| on the home disk only | **Delete from home disk** |
| on both | **Delete from phone** (keeps the home copy) · **Delete from home disk** (keeps the phone copy) · **Delete from both** |
| on the phone, still copying to the disk | **Delete from phone** (warns: "The home copy isn't finished; it will be removed too") · **Stop copying** |
| downloading at home | **Cancel download at home** (removes what's there) |

- Phone deletes are immediate (existing engine `remove(deleteFiles)`).
- Disk deletes go to the TV (`home.delete`, through the outbox when unreachable: the row shows "Deleting at
  home…"). When the TV is unreachable but the phone is at home, the phone deletes over SMB itself, unless a
  live lease exists.
- A disk delete moves the title folder to `.trash/<key>-<time>/` (instant rename); a 10 s **Undo** in the UI
  moves it back; `.trash/` is purged after 3 days or when space is needed.
- Phone-only titles show no disk option; with the disk not set up, disk options show "Home disk not
  connected" and are disabled.
- Pure: `core/homedisk/DeleteWhere.kt` (where → options → actions) with a test per row.

---

## 15. Failure modes **[Decided]**

| Failure | Detected by | Behaviour | Recovery |
|---|---|---|---|
| Disk unplugged / share gone | SMB `NoShare` / path not found | jobs → WAITING_DISK; conveyor downloads to its hard mark, then holds; copy job stops; disk playback fails over to "Other sources"; Settings "Not connected" | health check with backoff; everything resumes by itself; post-reconnect re-verify (§8.5, §7.3) |
| Router reboot | connect timeouts, TCP resets | as above, for ~1–3 min | as above |
| TV Wi-Fi drop | ConnectivityManager callback | libtorrent idles; SMB paused | `reopenNetworkSockets` (STREAMING_V2 §8.1), health check, resume |
| TV asleep / unreachable | phone RPC fails on every address | phone requests wait in the outbox; job rows say "Last update 2 h ago" | outbox retries; a job resumes when the TV wakes (restore + resetPending) |
| TV reboots / app updated mid-job | restore | conveyor torrent restored paused (§8.5) | Conveyor finishes a pending reset, health check, resumes |
| Phone killed mid-copy | job state + `.parts` | nothing past the map is trusted | job resumes at the map's end after re-verifying the last 8 chunks |
| Disk full | `STATUS_DISK_FULL` on write / set length | job → DISK_FULL; retention runs once | if still full: notification "Home disk is full"; resumes when space returns |
| Password changed on the router | `STATUS_LOGON_FAILURE` | **stop** (no retries, avoids lockout); "Password rejected" in Settings and on jobs | resumes after the user saves a new password |
| Router file-system damage (unplugged while writing) | verify pass / CRC of maps | damaged ranges dropped and re-fetched / re-copied | if `disk.json` is unreadable: "Check the disk on a computer" |
| Both devices try to write one title | lease | the second waits | stale-lease takeover after 10 min of no counter change |
| Tailscale off / relay too slow | RPC / stream timeouts | phone falls back to waiting (requests) or "can't reach your TV" (play) | automatic once reachable |
| A swarm with no seeds | conveyor stall (STREAMING_V2 stalled counter) | job shows "No one is sharing this right now" at 120 s | continues when peers appear; Cancel |

---

## 16. Telemetry (ReelStats, one key=value line per event; `EngineStats.log`)

```
ReelStats ev=hd-connect dev=phone|tv home=1 proto=smb311|smb302|smb210|smb202|smb1 ms=… ok=1|0 err=NoAnswer|NoShare|Password|Full|Other
ReelStats ev=hd-health free=… total=… rttMs=… spinUpMs=…
ReelStats ev=hd-test writeMBps=… readMBps=… smallReadMs=… bigFile=1|0
ReelStats ev=start … src=disk|tv-lan|tv-tailnet     (the STREAMING_V2 start line gains src; ttff/seek/rebuf lines unchanged)
ReelStats ev=hd-read calls=… bytes=… hits=… raInFlight=… mbps=…            (every 10 s while a disk source reads, and at end)
ReelStats ev=hd-copy key=… bytes=… mbps=… chunks=… rewrites=… verifyMs=… result=done|stopped|failed
ReelStats ev=conv-tick key=… down=… copyMBps=… localMb=… budgetMb=… onDisk=a/b state=run|drain|reset|wait   (every 5 s)
ReelStats ev=conv-reset key=… gen=… localMb=… drainMs=… resetMs=… rampMs=… lostKb=…
ReelStats ev=conv-done key=… mins=… avgDown=… resets=… verifyMs=… badPieces=…
ReelStats ev=rpc op=… via=lan|tailnet ms=… ok=1|0
ReelStats ev=outbox sent=… waitedMin=…
ReelStats ev=remote-open via=lan|tailnet rttMs=… mbps=… bitrate=… warned=0|1
ReelStats ev=hd-evict n=… freedGb=… reason=age|budget|floor
```
`StreamStats` aggregates the new `src` field; the device round (`tool/device_round.sh`) gains a disk play
case once Phase 3 ships.

---

## 17. Screens (follow DESIGN_STYLE: tokens only, 48 dp on the phone, visible TV focus)

### 17.1 Settings → Devices → **Home storage** (phone)
- **Status card** (top): "Connected · 412 GB free of 931 GB" / "Not connected — nothing at 192.168.1.1 yet.
  You can set everything up now." / "Away from home · your TV can reach the disk" / "Password rejected" /
  "Not set up".
- **Disk**: Address (default `192.168.1.1`), Shared folder, User, Password (masked; "Saved on this phone,
  encrypted"), **Test** (result line, §4.5), "Home network: saved · Forget".
- **Downloads**: "When I tap Download": *Download at home* / *Download on this phone* / *Ask me* (the
  remembered choice); "Copy phone downloads to the home disk when I'm home" (on); "Download on this phone
  over mobile data" (off **[Decided]**); "Other Wi-Fi networks: ask first, never at work" with the list of
  allowed / blocked networks.
- **Stream cache on the disk**: "Keep cached titles for 7 days"; "Use up to 40 % of the disk".
- **Away from home**: "Watch your home library away" with status ("Your TV is reachable as kame-tv" /
  "Install Tailscale on this phone and the TV" with a short how-to), optional MagicDNS name field.
- **People** (Phase 9): invited people, Invite, Remove.

### 17.2 Settings → Devices → **Home storage** (TV)
Status card; the same disk fields (filled from the phone, hint "Easier on your phone"); Test; **At-home
downloads** (title, state, %, Cancel); retention (mirrors the phone; changes sync both ways through
`home.config`). On the TV, **Download** goes to the home disk when it is connected (the TV's own storage is
too small to be a useful choice); otherwise to the TV as today.

### 17.3 Download where? (phone, Sources page in download mode)
A bottom sheet with two large rows and a remember switch:
- **At home** — "Your TV downloads it to the home disk. Works from anywhere." Subtitle shows the state:
  "TV ready" / "Starts when your TV is reachable" / "Set up home storage first" (row disabled, links to
  Settings).
- **On this phone** — "Copied to the home disk when you're home." Subtitle: "Waits for home Wi-Fi (this is
  not an allowed network)" when §5.3 says WAIT.
- **Remember my choice** (on by default). With a remembered choice the sheet is skipped; a small "At home ·
  change" line under the Download button reopens it.

### 17.4 Downloads (phone)
- Badges on each row: **Phone**, **Home disk**, **Phone + home disk**, **Copying to home disk 63 %**,
  **Downloading at home 42 %**, **Queued at home #2**, **Waiting to reach your TV**, **Waiting for home Wi-Fi**.
- Titles that exist only on the disk appear too (from `home.library`), with Play (§10.1 routing).
- Delete opens the delete-where dialog (§14). A filter chip "Home disk" joins the existing chips.

### 17.5 Notifications (phone)
"Downloading at home · Dune: Part Two · 42 %" (ongoing, silent, only while a job is active) · "Dune: Part
Two is ready on your home disk" (tap opens the title) · "Couldn't download … at home: <reason>" · "Home
disk is full" · "Copied 3 downloads to your home disk". The TV posts none (nobody sees them); its engine
notification text says "Downloading to home disk".

---

## 18. Files, owners and dependencies

**core/homedisk** (new, pure, tested): `DiskLayout` (paths, name cleaning, suffixes), `TitleManifest`,
`PartsMap`, `Lease` (counter / staleness), `NetworkFingerprint`, `HomeNetwork` (decision from facts),
`P2pRule`, `CopyPlan`, `ConveyorPolicy`, `UnionPieceSet`, `ChunkReader` (+ `RemoteFile`),
`HybridReaderCore`, `RetentionPolicy`, `DeleteWhere`, `StreamToken`, `Outbox`.
**core/sync**: `HomeRpc` (messages, idempotency), SyncServer v3 endpoint + source filter, SyncClient `rpc()`.

**app/homedisk** (new): `HomeDisk` (connection manager, Test), `HomeSecrets` (Keystore), `HomeCopier` +
`HomeJobService` (JobScheduler), `Conveyor` (applies ConveyorPolicy on reel-engine), `HomeStreamServer`,
`SmbDataSource`, `HomeIndex` (TV library index), `HomeClient` (phone RPC + outbox).

**Changes in files other sessions may own** (ask / coordinate before editing, as STREAMING_V2 §14):
`engine/TorrentEngine.kt`, `EngineModels.kt` (§8.6), `player/` data-source routing (§10.1),
`ui/settings/SettingsScreen.kt` (new page under Devices), `ui/sources/SourceState.download` + the
Download-where sheet (§17.3), `ui/DownloadsScreen.kt` (§17.4), `data/sync/*` (addresses, RPC),
`AndroidManifest.xml` (JobService, RUN_USER_INITIATED_JOBS, network-callback receiver),
`app/build.gradle.kts` (jcifs, packaging excludes), `app/proguard-rules.pro` (§4.2),
`docs/STREAMING_V2.md` (§8.6 addendum before Phase 5).

---

## 19. Build order (effort per phase; details and "done when" in HOME_DISK_PLAN)

| Phase | What | Needs the disk? | Effort |
|---|---|---|---|
| 0 | You: disk on the router, share user; Tailscale on phone + TV (before 6.4) | — | — |
| 1 | Spec (this file) | no | Extra high (done) |
| 1.1–1.3 | Router probe from the Mac (`SmbProbe`), library check on the TV and phone, TV / phone SMB numbers | yes | High |
| 2 | **Set up now**: core models + tests, Keystore credentials, HomeDisk + home detection + Test, Settings (phone + TV), Download-where sheet + P2P rule, Downloads badges + delete-where, sync v3 RPC skeleton + config push | no | High |
| 3 | Play from the disk: ChunkReader, SmbDataSource, routing, home-disk browser, device numbers | yes | High |
| 4 | Phone → disk copy job (fallback path complete) | yes | High |
| 5 | TV conveyor (STREAMING_V2 addendum first) | yes | **Extra high** |
| 6 | Download at home end to end: RPC ops, TV job queue, outbox, polling, notifications, Tailscale address for the RPC | yes | High |
| 7 | Away: HomeStreamServer + tokens, remote play, bandwidth check; hybrid reader for partial titles | yes | High (hybrid reader **Extra high**) |
| 8 | Retention + stream cache on the disk (+ auto-cache if wanted) | yes | Medium (eviction High) |
| 9 | Sharing with selected people | yes | High |
| 10 | TV always-on hardening (locks, overnight job, reboot) | yes | High |
| 11 | Multi-agent review of 2–10, then fixes | — | Ultracode, fixes High |
| 12 | Device verification + release | yes | Medium |

Order: 2 (now) → 1.1–1.3 (when the disk exists) → 3 → 4 → 5 → 6 → 10 → 7 → 8 → 9 → 11 → 12.

---

## 20. Feasibility spike (2026-10-21, Mac only, no P2P)

### 20.1 SMB client
- Maven Central: `eu.agno3.jcifs:jcifs-ng` latest **2.1.10** (2023-10-15, class files Java 7);
  `org.codelibs:jcifs` **3.0.4** (2026-09-27, Java 17; its 2.x line ends at 2.1.40, 2025-06-20, Java 8);
  `com.hierynomus:smbj` **0.15.0** (2026-08-21, Java 8, SMB2/3 only).
- Android API check (every JDK method / class the jars reference, against the SDK's `api-versions.xml`,
  minSdk 28): nothing above API 28 except `LambdaMetafactory` / `StringConcatFactory` (D8 desugars them)
  and Kerberos / JAAS / servlet classes used only by code paths we never call (hence the `-dontwarn`s).
- R8 (AGP 9.1's R8, `--release --min-api 28`, a probe as the only entry point): jcifs 3.0.4 + the
  BouncyCastle subset + slf4j = **679 KiB dex, 347 KiB deflated**; jcifs-ng 2.1.10 = 628 KiB / 316 KiB.
  R8 also copies ≈ 1.3 MB of BouncyCastle resources plus multi-release class copies unless excluded.
- **R8 trap reproduced**: after shrinking, `Crypto.getMD4()` fails with "no such algorithm: MD4 for provider
  BC" (NTLM sign-in would always fail in release builds); with the two keep rules of §4.2 it returns the
  right digest.
- Large MTU: jcifs-ng 2.1.10 / codelibs 2.1.40 charge one credit per request → 64 KiB per round trip on
  SMB 2.1+; codelibs 3.0.4 computes the credit charge per payload and defaults to 1 MiB transfers.
  Encryption classes exist only in 3.x (BouncyCastle lightweight AES-GCM / CCM). Both lines keep SMB1
  (`DialectVersion.SMB1 … SMB311`). SMB2 FLUSH is used by `SmbFileOutputStream.flush()` in 3.0.4.
- The probe initialises and fails cleanly against a closed loopback port (JDK 17).
- **Not measured: real SMB throughput.** No SMB server can run on this Mac without a system change:
  macOS file sharing needs System Settings; Homebrew Samba needs root or Samba's test wrappers; and the
  suggested impacket `smbserver` was **not installed on purpose** — it is an offensive-security toolkit
  that antivirus and web-filtering products commonly flag as a hacking tool, and this is a managed work
  laptop with HTTPS inspection. The probe is ready for Phase 1.1 at home against the real router:
  `SMB_PASS=… java -cp … SmbProbe smb://192.168.1.1/<share>/OneStream/probe/ <user> 50 [smb1]` (write /
  read 50 MiB with hash check, 200 random small reads, rename, list, delete).
- Small-read arithmetic (why §10.2): 10,000 unbuffered reads × 3 ms ≈ 30 s; with 1 MiB chunks and an
  8-chunk LRU ≈ 20 fetches × ~50 ms ≈ 1 s.

### 20.2 libtorrent4j 2.1.0-39 (from the jar)
- `TorrentHandle`: `moveStorage(path, MoveFlags)` (incl. `RESET_SAVE_PATH_UNCHECKED`),
  `prioritizePieces/Files`, `piecePriority`, `getDownloadQueue`, `saveResumeData`, `flushCache`, `havePiece`,
  `readPiece`, `addPiece`, `setSequentialRange`, `setUploadLimit`, `forceRecheck`, `renameFile`.
  **No call removes a piece from what the torrent holds** — freeing space truthfully needs remove + re-add.
- Resume editing: `libtorrent.read_resume_data_ex(byte_vector, ec)` / `write_resume_data_buf_ex(atp)`;
  `add_torrent_params.set_have_pieces / set_verified_pieces / set_unfinished_pieces / set_piece_priorities /
  set_file_priorities`, peers (`getPeers/setPeers`) and merkle trees kept. `TorrentFlags.NO_VERIFY_FILES`
  exists. `SessionHandle.removeTorrent(h, DELETE_FILES | DELETE_PARTFILE)`.
- NDK 28.2 is installed (a punch-hole helper would build), but option A is rejected on swarm grounds (§8.2).
- To confirm on the TV in Phase 5: piece priorities from `add_torrent_params` are applied on add (belt and
  braces: the add is paused and `prioritizePieces` runs before resume anyway), and the reconnect ramp after a
  re-add (peers come from the resume data).

### 20.3 Android (Pixel 8, Android 16+, targetSdk 36 → 37)
- SSID / BSSID: location permission; NEARBY_WIFI_DEVICES does not cover them (developer docs list
  hotspot / Aware / P2P / RTT only). ARP / neighbour tables closed since Android 10 / 11.
- ACCESS_LOCAL_NETWORK: mandatory for LAN access when targeting 37 (part of NEARBY_DEVICES); already
  declared; the phone needed it for NSD already.
- Chosen: paired-TV HMAC proof + LinkProperties fingerprint (§5.2); JobScheduler with unmetered constraint
  + network-callback PendingIntent + user-initiated transfer jobs (§7.2).

### 20.4 Tailscale (documentation level)
- Official Android client: Android 8.0+, supports Android TV (sign in with a code shown on the TV);
  armeabi-v7a builds are published (APKMirror / F-Droid list arm64-v8a and armeabi-v7a).
- Apps reach a peer by its 100.x address or MagicDNS name; nothing app-specific is needed. Node sharing:
  recipient needs their own tailnet, the shared machine is quarantined (receives, can't initiate), ACLs can
  restrict shared users to ports (`autogroup:shared`), revocable.
- Unverified (Phase 6 / 10 device checks): the VPN staying up through the TV's network standby, the
  Android TV "always-on VPN" setting, userspace WireGuard throughput on the R4, inbound connections to our
  servers through the TV's VPN interface.

---

## 21. Open questions

**For the user (product):** answered 2026-10-21 (DECISIONS.md) except Q1, which waits for the disk:
1. Router model and what the share speaks (Phase 0 / 1.1). The disk must be exFAT or NTFS, not FAT32 (4 GB
   file limit; the Test checks).
2. ~~Mobile data~~ **[Decided]** wait for Wi-Fi by default; "Start now anyway" per download; a switch.
3. ~~Other Wi-Fi~~ **[Decided]** ask once per network (remembered); office: never ("Download at home instead").
4. ~~Play~~ **[Decided]** follows the rule: blocked at work (offer Download at home), unknown Wi-Fi asks once.
5. ~~TV viewing in the cache / auto-cache~~ **[Decided]** keep 7 days (setting); auto-cache off (setting).
6. ~~Cache share~~ **[Decided]** 40 % (setting).
7. ~~Big 4K away~~ **[Decided]** warn + suggest a smaller source (Phase 7).
8. ~~Sharing~~ **[Decided]** only titles you pick; no download requests; One Stream app or a VLC link (Phase 9).

**To measure** (decided provisionally): conveyor overhead (§8.2), TV SMB read / write MB/s, phone copy rate,
Tailscale throughput from the R4, reconnect ramp after a reset, disk spin-up delay.

---

## 22. Rejected alternatives (with reasons)

| Alternative | Why not |
|---|---|
| Mount the share on Android | Impossible without root |
| Punch holes in the TV's local file | libtorrent keeps advertising and serving the zeroed pieces; recheck re-downloads them; native code (§8.2 A) |
| Only `move_storage` | Moves or relabels; frees nothing truthfully (§8.2 B1) |
| libtorrent part-file slicing | Never shrinks; private format (§8.2 C) |
| A second, Java BitTorrent client writing to SMB | Second engine, unmaintained, slower on the TV (§8.2 D) |
| smbj | No SMB1; the router may need it |
| jcifs-ng 2.1.10 as first choice | Unmaintained; 64 KiB per request on SMB 2.1+ (kept as fallback) |
| SSID / gateway-MAC home detection | Location permission / closed since Android 10 |
| Port forwarding, Funnel, public links | Never public (hard requirement) |
| WorkManager | Extra dependency for what JobScheduler does here; no user-initiated transfers |
| Seeding from the disk | Extra traffic and complexity for a personal library; the TV stops at finish |
| Transcoding on the TV for away playback | The 32-bit TV can't do it in real time |
| A small always-on box (Pi / NAS) next to the router | Would make all of this simpler, but it is new hardware; noted for the user if the conveyor disappoints |
