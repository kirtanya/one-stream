# One Stream engine v2: final implementation spec (revision 2)

Status: **binding**. Implement it as written, in the order of section 12. Basis: Design 1 (zone scheduler, intent-derived priorities, sentinel, incremental diff), with these grafts:
- **From Design 2:** adds-before-removes diff ordering, volatile PlayerSignals, data path early, resume demands, error and subtitle policy, TV byte target.
- **From Design 3:** RestorePolicy, LeaseTable, STARTUP phase, explicit persisted governor pauses, own-copy Keep, TV single stream, RandomAccessFile.

Revision 2 applies all 41 review issues; the review log (section 16) records how each was handled. Every API name below was checked against libtorrent4j-2.1.0-39, media3 1.11.1 and NanoHTTPD 2.3.1 on 2026-10-20. Re-verified for this revision from the jar bytecode:
- `MoveFlags.RESET_SAVE_PATH_UNCHECKED` exists.
- `InfoHash.hasV1/hasV2/getBest/getV1` exist.
- NanoHTTPD `ClientHandler` is a public class with a public `(NanoHTTPD, InputStream, Socket)` constructor and a non-final `run()`.

Skeptic caveats from the audit are applied throughout.

Hard rules, unchanged:
- No BitTorrent traffic from the office network. All P2P validation happens at home. Speculative traffic (prewarm, autoWarm, hedge) is also gated by `p2pNetworkGate` (7.5).
- core/stream stays pure JVM Kotlin, with tests in `core/src/test`.
- Changes to files owned by other sessions are listed as requests (section 14); the engine keeps working without them.

---

## 1. Goals and measurable targets

Goals, in priority order:
1. TTFF.
2. Seek latency.
3. Zero rebuffering when swarm payload rate ≥ 1.2 × media bitrate.
4. No fatal error caused by a slow piece.
5. Download throughput and robustness.

Each applies to both device kinds:
- **Phone:** arm64, mmap.
- **TV:** armeabi-v7a, posix on the network thread, about 100–120 connections, little storage.

Device behaviour follows two independent axes, `formFactor` and `disk` (4.1). A 64-bit Google TV is `formFactor = TV, disk = MMAP`.

All targets are medians over 3 titles, measured with `ReelStats` (section 11). Baseline is the Phase 0 APK.

| Metric (ReelStats field) | Baseline (est.) | Target phone | Target TV |
|---|---|---|---|
| Cold magnet `ttm` (time to metadata), healthy swarm | 2–30 s (dead first tracker) | ≤ 3 s | ≤ 4 s |
| Cold magnet `ttff` (1080p MKV, 4 MiB pieces, ≥3 MB/s swarm) | 10–25 s | ≤ 8 s | ≤ 10 s |
| Warm resume `ttff` (cache intact) | 3–5 s | ≤ 1.5 s | ≤ 2 s |
| Next episode same pack `ttff` | 4–10 s | ≤ 2 s | ≤ 3 s |
| Next episode other torrent `ttff` (prewarmed) | 10–30 s | ≤ 3 s | ≤ 4 s |
| Seek inside buffer, `seek` p50 | 0.2–1 s | ≤ 0.3 s | ≤ 0.5 s |
| Seek outside buffer, `seek` p50 / p90 | 2–10 s / frozen up to 8 s extra | ≤ 2.5 s / ≤ 6 s | ≤ 3.5 s / ≤ 8 s |
| Fatal errors from a slow piece (`fatalSlow`) | > 0 | 0 | 0 |
| `rebuf` count per hour, swarm ≥ 1.2 × bitrate | several | 0 | 0 |
| Steady-state `jni/s` (sync JNI per second, stream, no reader blocked) | 6–180 per piece boundary | ≤ 1 | ≤ 1 |
| `alerts/s` while streaming | ~1,300 | ≤ 60 | ≤ 60 |
| `missedWake` (havePiece safety net hits) | n/a | 0 | 0 |
| Hybrid v1+v2 torrent plays | no (90 s timeout) | yes | yes |
| Download keeps user pause across restart | no | yes | yes |

---

## 2. Architecture and files

```
PlayerActivity ── MediaItem(reel-torrent://key/file/token/name.ext) ──► ExoPlayer
  │ PlayerSignals (volatile, 2 Hz + events)        DefaultMediaSourceFactory(ReelDataSourceFactory)
  │ engine.onPlayerEvent(owner, ev) (post)            ├─ reel-torrent → TorrentDataSource ──┐
  ▼                                                   └─ other → OkHttp / DefaultDataSource │
TorrentEngine (facade, non-blocking) ◄────────────────────────────────────────────────────────┤
  reel-engine (single thread; owns all engine state + every libtorrent call)                  ▼
    TorrentEntry per key: PieceGate, EntryPlanner (PlanMerge → PlanDiff → PlanApplier),  StreamReader (core StreamReaderCore,
      StreamController per stream (ZoneScheduler.plan), overlays (prefetch/prewarm)     RandomAccessFile) ── PieceGate.await
    LeaseTable, Governor, CacheManager, Prewarm/Hedge, NetworkGate, 1 s tick                     ▲
  libtorrent4j alert thread: O(1) → key from info_hashes(), gate.markVerified / pre-gate queue,  │
                             Deferred.complete, copy + post                                       │
  reel-io (single thread): AtomicFile writes, deletes, StatFs, container probe reads, keep copies │
LocalStreamServer (NanoHTTPD, owned by the engine, external players only) ── StreamReader(HTTP) ──┘
ReelSession : SessionManager(false)
```

### 2.1 Files

Owner key:
- **E**: this engineer.
- **R**: request to another session (section 14).

**core/stream** (E, pure, all tested)

| File | Status | Contents |
|---|---|---|
| `Units.kt` | new (Phase 1) | `KiB`, `MiB`, `GiB`, `MB` constants; StreamTest imports `MB` from here |
| `FileSpan.kt` | extend | byte-accurate piece math (3.3) |
| `PieceSet.kt` | new | `AtomicLongArray` bitset |
| `PieceGate.kt` | new | lock/condition waits, flush state, readable runs |
| `StreamReaderCore.kt` | new | the blocking read loop shared by both transports |
| `TorrentStreamRef.kt` | new | `reel-torrent://` URI format and parse |
| `MediaRate.kt` | new | bytes/s model |
| `SchedulerProfile.kt` | new | `FormFactor`, `DiskBackend`, profile constants |
| `ContainerProbe.kt` | new | MP4 box walk, MKV EBML |
| `StartupPlanner.kt` | new | header / index / resume targets |
| `ZoneScheduler.kt` | new | `plan()`, span-indexed, per stream |
| `PlanMerge.kt` | new | per-torrent merge of the primary plan, overlays, baseline and guard |
| `PlanDiff.kt` | new | `AppliedState` (torrent-indexed), `PlanOp`, `diff()` |
| `LeaseTable.kt` | new | claims and activity |
| `GovernorPolicy.kt` | new | bandwidth decisions |
| `CachePolicy.kt` | new | budgets, eviction, allowance |
| `SpaceGuard.kt` | new | DOWNLOAD free-space decisions |
| `StallPolicy.kt` | new | stall thresholds |
| `FileChoice.kt` | new | stream-file pick, wanted-files merge, explicit priorities for keep/DOWNLOAD |
| `RestorePolicy.kt` | new | restored flags and key migration |
| `HedgePolicy.kt` | new | candidate racing |
| `PrewarmPolicy.kt` | new | prewarm gates |
| `TorrentKey.kt` | new | canonical key rule |
| `SessionTuning.kt` | new | the settings table as data, over both device axes |
| `StreamStats.kt` | new | stats accumulator and line formatting |
| `SnapshotReducer.kt` | new | stalled counters and threshold re-emit |
| `StreamPlan.kt` | removed | deleted at the end of Phase 3; until then made byte-accurate |
| `ByteRange.kt`, `VideoFiles.kt` | unchanged | |

**core/magnet** (E, claimed)
- `Trackers.kt`: refreshed list; drop `exodus.desync.com` and `tracker.tiny-vps.com`. The replacement list is picked at home from the ngosang trackers_best list, never fetched at runtime.
- `Magnet.kt`: parse signature unchanged; lowercase v1 hex is kept.

**app/engine** (E)

| File | Status | Contents |
|---|---|---|
| `TorrentEngine.kt` | rewritten | facade |
| `ReelSession.kt` | new | SessionManager subclass |
| `EngineThread.kt` | new | `reel-engine` and `reel-io` executors |
| `Registry.kt` | new | `TorrentEntry` per key, pre-gate PIECE_FINISHED queues (no `idToKey`) |
| `StreamController.kt` | new | one per active stream, with its own `closedReason` and `fileReady` |
| `EntryPlanner.kt` | new | one per TorrentEntry: merge, diff, `AppliedState` |
| `PlanApplier.kt` | new | `PlanOp` → libtorrent calls, one per TorrentEntry |
| `StreamReader.kt` | new | Android adapter over `StreamReaderCore` |
| `Governor.kt` | new | applies `GovernorPolicy` |
| `CacheManager.kt` | new | trim, make-room, keep |
| `Prewarm.kt` | new | prewarm tickets, Up next |
| `NetworkGate.kt` | new | network fingerprint, trusted P2P networks |
| `Persistence.kt` | new | AtomicFile, latch, session.dht |
| `EngineStats.kt` | new | ReelStats logcat |
| `EngineFlags.kt` | new | feature flags |
| `StreamBench.kt` | new | headless bench |
| `EngineModels.kt` | changed | additive fields only |
| `EngineService.kt` | changed, **only after R6** | locks, network callback, notification; `quit()` path unchanged |

**app/server** (E)
- `LocalStreamServer.kt`: external players only, over `StreamReader`. One instance, owned and started lazily by `TorrentEngine`. PlayerActivity stops using `ReelApp.streamServer`; that lazy property stays but is never built, so ReelApp needs no change.

**app/player** (E)

| File | Status | Contents |
|---|---|---|
| `TorrentDataSource.kt` | new | in-app reads |
| `ReelDataSourceFactory.kt` | new | scheme routing |
| `ReelLoadErrorPolicy.kt` | new | load error policy |
| `PlayerSignals.kt` | new | volatile player state |
| `ReelAnalyticsListener.kt` | new | AnalyticsListener for stats (named to avoid media3's `PlaybackStatsListener`) |
| `PlayerActivity.kt` | changed | player setup, stream-open and external-handoff parts only |

**app/ui/sources** (E)
- `SourceState.kt`:
  - `rankPrefs` stays TV-only. It counts reclaimable cache through the same function as the prepare size check.
  - `alternatesFor()`.
  - Opt-in Sources prewarm.
  - HLS Download guard.

**Requests (R):**
- `data/SourcesProbe.kt` (bench hook, R1).
- `EngineService.kt` claim (R6).
- `ui/DownloadsScreen.kt` (optional, R4).
- Home visibility hook (optional, R7).
- The PlaybackSettings toggle (R3).
- An invariant acknowledgement (R2).

---

## 3. Exact Kotlin APIs

### 3.1 TorrentEngine public surface (old → new)

| Old | New | Note |
|---|---|---|
| `fun start()` | same | Idempotent and non-blocking from any thread. Calls `EngineService.start` inside `runCatching` (invariant), then posts session init to `reel-engine`. |
| — | `suspend fun awaitReady()` | Throws `EngineException("Torrent engine couldn't start: …")`. |
| `fun shutdown()` | same | Blocks for at most 8 s. Called on "reel-quit" (invariant). |
| `val torrents: StateFlow<List<TorrentSnapshot>>` | same | Built from STATE_UPDATE plus the 1 s `SnapshotReducer` tick (10). |
| `suspend fun addMagnet(uri, mode, onlyFileIndex = null, onlyFileHint = null): String` | same signature | Runs on the engine dispatcher. Merges wanted files. On a DEFAULT_DONT_DOWNLOAD torrent it sets explicit file priorities (7.3). |
| `suspend fun addTorrentFile(bytes, mode): String` | same | Canonical key; adds trackers only when not private. |
| `fun pause(hash)` / `fun resume(hash)` | same | Post. Persist `userPaused`. `resume` on a queued torrent calls `queuePositionTop()`. `resume` during GUARD sets `userOverride` (8.1). |
| `fun remove(hash, deleteFiles)` / `fun keep(hash)` | same | Post. Keep is deferred while claimed. |
| — | `fun keepAsCache(hash)` | Post. "Keep as stream cache" (7.3a): a download becomes a stream-cache entry in place. Deferred while claimed; `keep` and `keepAsCache` cancel each other's pending wish. |
| `fun files(hash): List<TorrentFileEntry>` | same | Cached at metadata, no JNI. |
| `fun snapshot(hash): TorrentSnapshot?` | same | |
| `fun downloadsDir(): File` | same | |
| `fun trimCache(budgetBytes: Long)` | same | Called **on main** (DownloadsScreen): posts. The engine task first marks the chosen entries `evicting`, then posts the deletes. Called **off main** (SettingsScreen on IO): submits and waits up to 2 s (`Future.get`) for that decision, so a following `cacheBytes()` already excludes them. |
| `fun cacheBytes(): Long` | same | From snapshots, no JNI. Excludes `evicting` entries. |
| `suspend fun prepareStream(...)` | **private** | |
| `suspend fun startStream(magnet, fileIdx, filenameHint): StreamInfo` | kept | Wraps `startStream(StreamRequest(listOf(cand)))`. Its `tapElapsedMs` is measured at the wrapper (a documented under-count; PlayerActivity uses the new overload). |
| — | `suspend fun startStream(req: StreamRequest): StreamInfo` | Hints and hedging. |
| `fun streamInfo(hash)`, `fun isStreaming(hash)` | same | Read ConcurrentHashMaps. |
| `fun hasReaders(hash, owner)` | `fun hasReaders(hash, owner, kind: ReaderKind? = ReaderKind.HTTP)` | Default counts only external (HTTP) readers. `null` counts all kinds. |
| `fun suspendStream(hash, owner)` / `fun resumeStream(hash, owner)` | same signature | Owner-checked with `===`. Background → grace (section 7). |
| `fun stopStream(hash, owner = null)` | same | Owner-checked. Player claim → Grace claim. |
| `fun stopWhenIdle(hash, owner, idleMs = 10 * 60_000L)` | same | External claim. |
| `fun openReader(hash, filePos): Long`, `readerAt`, `closeReader`, `awaitPiece`, `awaitPieceBlocking` | **removed** | Only LocalStreamServer used them. |
| — | `fun openReader(ref: TorrentStreamRef, position: Long, kind: ReaderKind, socket: java.net.Socket? = null): StreamReader` | Throws `StreamGoneException`. Registers synchronously in a ConcurrentHashMap, then posts `kick(OPEN)`. |
| — | `fun playerSignals(owner: StreamInfo): PlayerSignals` | The same instance for the life of the stream. |
| — | `fun onPlayerEvent(owner: StreamInfo, ev: PlayerEvent)` | Post. |
| — | `fun streamStatus(owner: StreamInfo): StateFlow<StreamStatus>` | |
| — | `fun httpUrlFor(info: StreamInfo): String` | Starts the engine-owned server lazily. |
| — | `fun prewarm(req: PrewarmRequest): PrewarmTicket?` | Null when gated. |
| — | `fun prefetchNext(owner: StreamInfo, fileIdx: Int?, filenameHint: String?)` | Same torrent; an overlay on the entry (7.5). |
| — | `fun onHomeVisible()` | Post. The only trigger for autoWarm (7.5). Optional caller (R7). |
| — | `fun startNow(hash)`, `fun retry(hash)`, `fun reclaimableCacheBytes(): Long`, `fun debugDump(): String` | |
| — | `fun bench(req: BenchRequest, log: (String) -> Unit): kotlinx.coroutines.Job` | Section 11.3. |

### 3.2 Models (all additive with defaults; `TorrentPhase` gets no new constants)

```kotlin
// engine/EngineModels.kt
data class StreamInfo(                       // owner token: identity (===) checks unchanged
    val infoHash: String, val file: TorrentFileEntry, val span: FileSpan, val mimeType: String, val path: java.io.File,
    val token: Long = 0L,                    // unique per startStream; embedded in the URI; superseded → readers fail fast
    val uri: String = "",                    // reel-torrent://<key>/<fileIdx>/<token>/<urlenc name.ext>
    val candidateIndex: Int = 0,
)
data class StreamCandidate(val magnet: String, val fileIdx: Int?, val filename: String?, val label: String? = null)
data class StreamHints(val startMs: Long = 0, val durationMs: Long? = null, val sizeBytes: Long? = null,
                       val tapElapsedMs: Long = android.os.SystemClock.elapsedRealtime())   // PlayerActivity passes its onCreate time
data class StreamRequest(val candidates: List<StreamCandidate>, val hints: StreamHints = StreamHints())
enum class ReaderKind { IN_APP, HTTP, BENCH }
sealed interface PlayerEvent {
    data class Ready(val atMs: Long) : PlayerEvent
    data class FirstFrame(val atMs: Long) : PlayerEvent
    data class RebufferStart(val positionMs: Long) : PlayerEvent
    data object RebufferEnd : PlayerEvent
    data class Seek(val fromMs: Long, val toMs: Long, val leftBuffer: Boolean) : PlayerEvent
    data class Duration(val ms: Long) : PlayerEvent
    data object Handoff : PlayerEvent                                     // in-app player stopped for an external player
}
sealed interface StreamStatus {
    data class FindingPeers(val peers: Int, val attempt: Int, val candidateLabel: String?) : StreamStatus
    data object Preparing : StreamStatus
    data class Buffering(val downBps: Int, val peers: Int, val aheadSec: Int) : StreamStatus
    data class Stalled(val forSec: Int, val downBps: Int, val peers: Int) : StreamStatus   // non-fatal
    data class Failed(val kind: StreamErrorKind, val message: String) : StreamStatus
    data object Playing : StreamStatus
}
enum class StreamErrorKind { NO_METADATA, STORAGE_FULL, FILE_ERROR, TORRENT_ERROR, GONE }
class StreamGoneException(msg: String) : java.io.IOException(msg)                       // superseded / removed / released / client left
class StreamErrorException(val kind: StreamErrorKind, msg: String) : java.io.IOException(msg)

enum class PrewarmLevel { METADATA, STARTUP }
enum class PrewarmReason { UP_NEXT, CONTINUE_AUTO, SOURCES_BEST, HEDGE }
data class PrewarmRequest(val magnet: String, val fileIdx: Int?, val filename: String?, val level: PrewarmLevel,
                          val reason: PrewarmReason, val startMs: Long = 0, val durationMs: Long? = null,
                          val ttlMs: Long = 5 * 60_000L)
class PrewarmTicket internal constructor(val key: String, internal val token: Long) { fun release() /* starts TTL */ }

data class BenchRequest(val magnet: String, val fileIdx: Int? = null, val filename: String? = null, val startMs: Long = 0,
                        val durationMs: Long? = null, val seeksMs: List<Long> = emptyList(), val seconds: Int = 120)

// TorrentSnapshot additive:
//   fileIndex: Int? = null, queued: Boolean = false, queuePosition: Int = -1, userPaused: Boolean = false,
//   notice: String? = null, streaming: Boolean = false
// TorrentMeta additive (@Serializable, ignoreUnknownKeys already on):
//   wantedFiles: List<Int> = emptyList(), wantedHints: List<String> = emptyList(), fullTorrent: Boolean = false,
//   pendingFileIntent: Boolean = false /* explicit priorities still owed once metadata arrives */,
//   userPaused: Boolean = false, pausedForStream: Boolean = false, pausedForSpace: Boolean = false,
//   seedingDone: Boolean = false, keepPending: Boolean = false, wasAutoManaged: Boolean? = null,
//   cachePending: Boolean = false /* keepAsCache asked while claimed (7.3a) */,
//   createdBy: String = "user" /* user|stream|prewarm|hedge */, lastStoppedAt: Long = 0, lastPosFrac: Float? = null,
//   durationMs: Long? = null, savePath: String? = null, name: String? = null, schema: Int = 2
// Migration on load (schema < 2), in Phase 1:
//   onlyFileIndex → wantedFiles; onlyFileHint → wantedHints;
//   DOWNLOAD with neither → fullTorrent = true;
//   savePath := resume save_path (AddTorrentParams.getSavePath()) when absent;
//   infoHash := canonical key (9.2).
```

### 3.3 core/stream pure classes

```kotlin
// Units.kt (Phase 1)
const val KiB = 1024L; const val MiB = 1024L * KiB; const val GiB = 1024L * MiB; const val MB = MiB   // StreamTest compatibility

// FileSpan additions (existing members kept)
fun byteStartOf(piece: Int): Long          // file-relative, coerceAtLeast(0)
fun byteEndOf(piece: Int): Long            // exclusive, coerceAtMost(size)
fun piecesForBytes(from: Long, toExclusive: Long): IntRange   // empty when from >= toExclusive or size == 0
val pieceRange: IntRange                   // firstPiece..lastPiece

// PieceSet.kt
interface PieceSet { val numPieces: Int; fun has(p: Int): Boolean
    fun firstMissing(from: Int, toInclusive: Int): Int        // -1 if none
    fun lastMissing(from: Int, toInclusive: Int): Int         // -1 if none
    fun runEnd(from: Int, limitInclusive: Int): Int }         // raw have-run; NOT used by readers (use readableRunEnd)
class AtomicPieceSet(override val numPieces: Int) : PieceSet {
    fun set(p: Int): Boolean; fun seed(has: (Int) -> Boolean); fun clearAll() }

// PieceGate.kt: one per TorrentEntry. Never closed on supersede; close() only on entry removal (remove/trim/key migration).
class PieceGate(numPieces: Int, val needsFlush: Boolean, private val nanoTime: () -> Long = System::nanoTime) {
    val pieces: AtomicPieceSet
    fun markVerified(p: Int)                             // alert thread: set bit, record finishedAt, signalAll under the lock
    fun markFlushed(p: Int)                              // engine thread: set flushed, signalAll under the lock
    fun isReadable(p: Int, flushGraceNanos: Long = 2_000_000_000L): Boolean   // has && (!needsFlush || flushed || age > grace)
    fun readableRunEnd(from: Int, limitInclusive: Int): Int  // last q ≥ from with isReadable for all of from..q; from-1 if none
    fun flushPending(p: Int): Boolean
    @Throws(InterruptedException::class)
    fun await(p: Int, sliceMs: Long = 250, ready: () -> Boolean, onSlice: () -> Unit)
        // loop: lock; while (!(ready() && isReadable(p)) && !closed) cond.await(slice); unlock; if satisfied return; onSlice()
        // onSlice throws to abort (alive() / probe); ready() is the per-stream fileReady flag
    fun close(reason: java.io.IOException)               // entry removal only: wakes all; await rethrows reason
    fun signalAll()
}

// StreamReaderCore.kt
fun interface FileOpener { fun open(): java.io.RandomAccessFile }   // re-resolves the path (generation changes after keep)
interface ReaderListener { fun onBlocked(p: Int); fun onUnblocked(p: Int, waitedMs: Long); fun onPieceCrossed(pos: Long)
                           fun needFlush(p: Int) /* posts a coalesced flush poll */
                           fun longBlock(p: Int) /* posts at most once per 2 s while blocked: havePiece safety net */ }
class StreamReaderCore(val id: Long, val kind: ReaderKind /* core copy of the enum */, val span: FileSpan,
                       private val gate: PieceGate, private val opener: FileOpener,
                       private val fileReady: () -> Boolean,     // the stream's readable gate (4.7)
                       private val alive: () -> Unit,            // throws the controller's closedReason or this reader's deadReason
                       private val probe: (() -> Unit)?,         // HTTP disconnect probe; null for in-app and bench
                       private val listener: ReaderListener) : java.io.Closeable {
    @Volatile var position: Long; @Volatile var blockedPiece: Int; @Volatile var lastProgressNanos: Long
    @Volatile var bytesRead: Long; val openedNanos: Long
    @Volatile var deadReason: java.io.IOException?           // set by the engine (markDead); never closes the file itself
    fun markDead(reason: java.io.IOException)                // sets deadReason; caller then gate.signalAll()
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int   // section 5.1
    override fun close()                                     // owning thread only: closes the RandomAccessFile
}

// TorrentStreamRef.kt
data class TorrentStreamRef(val key: String, val fileIndex: Int, val token: Long, val name: String) {
    fun format(): String; companion object { fun parse(uri: String): TorrentStreamRef? } }

// MediaRate.kt
enum class RateSource { PLAYER_DURATION, HINT_DURATION, GUESS }
data class MediaRate(val bytesPerSec: Long, val source: RateSource) { val known get() = source != RateSource.GUESS
    companion object { fun of(size: Long, playerDurationMs: Long?, hintDurationMs: Long?): MediaRate } }
// order: size*1000/playerDuration → size*1000/hintDuration → GUESS clamp(size/5400, 256 KiB, 10 MiB).
// Never from reader throughput (caveat).

// ContainerProbe.kt
enum class ContainerKind { MKV, MP4, FMP4, TS, AVI, OTHER }
data class ContainerLayout(val kind: ContainerKind, val headerEnd: Long, val index: LongRange?, val needTail: Boolean)
sealed interface ProbeStep { data class Need(val range: LongRange) : ProbeStep; data class Done(val layout: ContainerLayout) : ProbeStep }
class ContainerProbe(val size: Long, val extension: String) {
    fun step(read: (pos: Long, len: Int) -> ByteArray?): ProbeStep }   // at most 3 Need steps, then Done (fallback layout)

// StartupPlanner.kt
data class StartupSet(val header: LongRange, val index: LongRange?, val resumeGuess: LongRange?)
object StartupPlanner {
    fun initial(span: FileSpan, ext: String, startMs: Long, rate: MediaRate): StartupSet          // before the probe
    fun refine(set: StartupSet, layout: ContainerLayout, span: FileSpan): StartupSet }            // after the probe

// ZoneScheduler.kt, PlanMerge.kt and PlanDiff.kt: section 4.
// Policies: LeaseTable (7.1), GovernorPolicy (8.1), CachePolicy (7.4), SpaceGuard (8.1), StallPolicy (6.6), FileChoice (7.3),
// RestorePolicy (9.2), HedgePolicy (7.6), PrewarmPolicy (7.5), SessionTuning (8.2), StreamStats (11), SnapshotReducer (10).
object TorrentKey {
    private val ZERO = "0".repeat(40)
    fun canonical(v1Hex: String?, bestHex: String): String =
        (v1Hex?.lowercase()?.takeIf { it != ZERO } ?: bestHex).lowercase()
}
// Android adapter (engine): val ih = InfoHash(h.swig().info_hashes())
//   key = TorrentKey.canonical(if (ih.hasV1()) ih.v1.toHex() else null, ih.best.toHex())
```

### 3.4 TorrentDataSource and factory

```kotlin
class TorrentDataSource(private val engine: TorrentEngine) : BaseDataSource(/* isNetwork = */ false) {
    private var reader: StreamReader? = null; private var uri: Uri? = null; private var pos = 0L; private var remaining = 0L; private var opened = false
    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        val ref = TorrentStreamRef.parse(spec.uri.toString()) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val r = engine.openReader(ref, spec.position, ReaderKind.IN_APP)        // throws StreamGoneException
        if (spec.position > r.size) { r.close(); throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) }
        reader = r; uri = spec.uri; pos = spec.position
        remaining = if (spec.length == C.LENGTH_UNSET.toLong()) r.size - pos else minOf(spec.length, r.size - pos)
        opened = true; transferStarted(spec); return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt())   // InterruptedIOException on cancel
        pos += n; remaining -= n; bytesTransferred(n); return n
    }
    override fun getUri(): Uri? = uri
    override fun close() { reader?.close(); reader = null; uri = null; if (opened) { opened = false; transferEnded() } }
    class Factory(private val engine: TorrentEngine) : DataSource.Factory { override fun createDataSource() = TorrentDataSource(engine) }
}
class ReelDataSourceFactory(private val torrent: DataSource.Factory, private val other: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(torrent, other)
}
```

`RoutingDataSource` is a private `DataSource`:
- `addTransferListener(l)` stores `l` in a list, and also adds it to the current branch if one exists. Media3 calls it before `open()`.
- `open(spec)` picks the branch from `spec.uri.scheme == "reel-torrent"`, creates it if needed, adds every stored listener to a newly created branch, then delegates.
- `read`, `getUri`, `getResponseHeaders` and `close` delegate to the current branch.

It is passed to `DefaultMediaSourceFactory(factory)`, so `MediaItem.subtitleConfigurations` still merge (caveat).

`TorrentDataSource` keeps `isNetwork = false`: local disk reads must not feed the shared bandwidth meter.

### 3.5 Player → engine feedback

```kotlin
class PlayerSignals {   // written on main (2 Hz loop + listener callbacks), read on reel-engine; no locks, no JNI
    @Volatile var positionMs = 0L; @Volatile var bufferedPositionMs = 0L; @Volatile var durationMs = 0L
    @Volatile var playWhenReady = false; @Volatile var playing = false; @Volatile var rebuffering = false
    @Volatile var firstFrame = false; @Volatile var updatedAtElapsedMs = 0L
    fun fresh(now: Long) = now - updatedAtElapsedMs < 3_000
}
```
- Discrete events (first frame, seek, rebuffer, duration, handoff) go through `engine.onPlayerEvent` (post).
- The engine samples the signals on its tick and on every kick. It never runs on the playback thread.
- The 2 Hz loop runs only while the activity is STARTED, so the signals go stale (`fresh == false`) in the background and after a handoff.

### 3.6 Prewarm and lease API
`prewarm(req)` returns a `PrewarmTicket` (claim `Prewarm`) or null when gated. Gates:
- unmetered;
- `NetworkGate.trusted()` (7.5): the current network has had a user-started stream before;
- at most 2 live (TV: 1, and 0 while a stream is in STARTUP);
- free space > reserve + 256 MiB;
- the Sources reason needs the opt-in setting.

`ticket.release()` starts the TTL. `startStream` on the same key consumes the claim and lifts the per-torrent connection cap (`set_max_connections(-1)`). When the TTL expires unclaimed:
- `createdBy == "prewarm"` and never streamed → `remove(deleteFiles = true)`;
- otherwise `pause()`.

---

## 4. Scheduler

### 4.1 SchedulerProfile constants

Two independent axes:
- `formFactor = if (app.isTv) TV else PHONE`. It drives storage budgets and reserves, LoadControl and back buffer, grace, governor mode, the TV single-stream rule, prewarm limits and connection limits.
- `disk = if (is64Bit()) MMAP else POSIX`. It drives `setPosixDiskIO`, the flush gate (`needsFlush = disk == MMAP`), `max_queued_disk_bytes`, and the network-thread caveats.

`SchedulerProfile.of(formFactor, disk)` and `SessionTuning.pack(formFactor, disk)`. Both values are logged in `ev=start` (`dev=tv disk=mmap`).

The PHONE and TV columns below are `formFactor`.

| Field | PHONE | TV | Why |
|---|---|---|---|
| `urgentSec` | 10 s | 10 s | covers bufferForPlaybackAfterRebuffer (5 s) plus a margin |
| `urgentMinPieces` / `urgentMaxPieces` | 2 / 16 | 2 / 12 | small, bounded critical list |
| `urgentMaxBytes` | 48 MiB | 32 MiB | |
| `safetyMs` | 2 000 | 3 000 (2 000 if disk = MMAP) | TV network-thread jitter (posix only) |
| `pausedDeadlineFloorMs` | 10 000 | 10 000 | while paused, nothing is urgent |
| `maxDeadlineMs` | 60 000 | 60 000 | sentinel deadline |
| `tcHorizonMs` | max(3 000, 2·pieceMs) | same | estimate of libtorrent's time-critical horizon (avg + 4·dev + 1 s); only pieces whose deadline falls inside it get a deadline and priority 7 |
| `deadlineSlackMs` (re-set only if moved more than this) | 1 000 | 1 000 | |
| `ladderStepMs` (no playhead: external or bench) | max(250, pieceMs/2) | same | pieceMs = clamp(L·1000/B, 100, 4000) |
| `keepBehindSec` (priority DEFAULT) | 30 s | 15 s | D-pad rewinds |
| behind priority | 1 if fullFile else 0 | 0 | |
| ahead extent | EOF if fullFile else A + aheadCap | same rule | |
| `aheadCap` (windowed: metered only) | max(B·300 s, 64 MiB) | same | bandwidth limiter, not disk limiter (7.4) |
| `farSeekBytes` | max(64 MiB, 2·urgentBytes) | same | one deliberate clear |
| `seqReassertMs` | 5 000 | 5 000 | a no-op until connections are initialised |
| `seqMoveMinPieces` | 8 | 8 | |
| `fullVectorThreshold` | 2 048 changes or > numPieces/4 | same | |
| replan throttle (piece events) | 250 ms | 500 ms | OPEN/BLOCKED/SEEK are immediate; CLOSE uses the ghost (4.9) |
| `ghostCloseMs` | 500 | 500 | a closed primary stays the anchor until an OPEN or this timeout |
| `secondaryMax` / pieces | 3 / 2 at SIX | same | blocked HTTP secondary: its piece at deadline 0 |
| startup head before the probe | 2 MiB | 2 MiB | byte-accurate |
| startup tail before the probe | MKV/WebM/MP4/AVI/other 4 MiB, TS 1 MiB | same | fetched in parallel (caveat) |
| header after the probe (cap) | 32 MiB | 32 MiB | |
| `startupExtraPieces` (DEFAULT after the head and the anchor) | 2·K | 2·K | K = urgent piece count |
| startup exit | FirstFrame, or all startup pieces had and primary bytesRead ≥ 2·K·L, or 30 s | same | |
| resume guess | 2 pieces (≤ 8 MiB unless L > 4 MiB) at SIX, no deadline; only when size ≤ 8 GiB | same | VBR caveat |
| `guardRepickMs` | max(10 000, 2·L·1000/B) | same | guard creep ≤ B/2 (4.5a) |
| `guardMaxBytes` | max(2·L, 32 MiB) beyond the last wanted piece | same | bound on guard overshoot |

B = `MediaRate.bytesPerSec`, floored at 128 KiB/s. L = piece length.

### 4.2 Inputs and outputs

```kotlin
enum class StreamPhase { PREWARM, STARTUP, PLAYING, BACKGROUND }
data class ReaderView(val id: Long, val kind: ReaderKind, val pos: Long, val openedAt: Long, val lastProgressAt: Long,
                      val blockedPiece: Int /* -1 */, val bytesRead: Long, val superseded: Boolean,
                      val ghost: Boolean = false /* closed < ghostCloseMs ago, no OPEN since */)
data class PlaybackView(val positionMs: Long, val bufferedPositionMs: Long, val durationMs: Long,
                        val playing: Boolean, val playWhenReady: Boolean, val fresh: Boolean)
data class StorageAllowance(val fullFile: Boolean, val aheadCapBytes: Long, val metered: Boolean)
data class PlanInput(
    val span: FileSpan, val have: PieceSet, val profile: SchedulerProfile, val phase: StreamPhase, val download: Boolean,
    val readers: List<ReaderView>, val lastAnchor: Long?, val playback: PlaybackView?, val rate: MediaRate,
    val startup: StartupSet?, val storage: StorageAllowance,
    val baseline: (Int) -> Int,          // intent-derived priority per torrent piece (file priorities → max over overlapping files)
    val nowMs: Long)
data class Plan(val anchor: Long, val primaryId: Long?, val deadlines: Map<Int, Int>, // torrent piece → ms from now (all inside span)
                val sentinel: Int?,                                                  // its deadline is maxDeadlineMs
                val prio: ByteArray /* index = piece - span.firstPiece */, val seq: IntRange?, val aheadEndPiece: Int)
```

Pieces outside the span are never planned by `ZoneScheduler`. Overlays (prefetch, prewarm, CONTINUE_AUTO) and the baseline are merged per torrent in `PlanMerge` (4.5a).

### 4.3 `ZoneScheduler.plan`

```
fun plan(i): Plan
  L = span.pieceLength; B = max(i.rate.bytesPerSec, 128 KiB); P = i.profile
  live = readers.filter { !superseded }
  prim = live.filter { (it.kind == BENCH) || (it.kind == IN_APP && playback?.fresh == true && playback.playWhenReady) }
             .maxByOrNull { openedAt }
         ?: live.filter { kind == HTTP }.maxByOrNull { max(openedAt, lastProgressAt) }      // external handoff, HTTP-only streams
         ?: live.filter { kind == IN_APP }.maxByOrNull { openedAt }                         // paused in-app player, or ghost
  A = prim?.pos ?: i.lastAnchor ?: startup?.resumeGuess?.first ?: 0
  aP = span.pieceOf(A)
  aheadEnd = if (storage.fullFile) span.lastPiece else span.pieceOf(min(size - 1, A + storage.aheadCapBytes))
  H = playheadByte(i, prim)          // 4.4; null when unknown
  horizon = P.tcHorizonMs
  D = LinkedHashMap<Int, Int>()      // deadlines
  prio = ByteArray(span pieces) init by phase:

  // ---- base priorities (stream-file pieces only)
  when {
    phase == PREWARM  -> all 0
    phase == STARTUP && !download -> all 0, then DEFAULT(4) on [headEndPiece+1 .. +2K] and [aP .. aP+2K]
    download          -> all DEFAULT(4)                                 // never shrink a download
    else              -> [first .. pieceOf(A - B*keepBehindSec)-1] = behindPrio
                         [pieceOf(A - B*keepBehindSec) .. aheadEnd] = 4
                         (aheadEnd .. last] = 0
  }
  header/index pieces: never below 4 after they are had (cheap to keep; ExoPlayer re-reads them)

  // ---- startup set (not PREWARM/BACKGROUND, until startupDone); seq is null in STARTUP, so 7s here are not skipped by a range
  if (startup != null && !startupDone && phase in {STARTUP, PLAYING}):
     k = 0
     for p in span.piecesForBytes(header) where !have(p): D[p] = 50 * k++ ; prio[p] = 7
     j = 0
     for p in span.piecesForBytes(index) where !have(p): D.putIfAbsent(p, 25 + 50 * j++); prio[p] = 7
     if (startup.resumeGuess != null && (prim == null || prim.pos < header.last))
         for p in span.piecesForBytes(resumeGuess) where !have(p): prio[p] = max(prio[p], 6)
  if (phase == PREWARM && level == STARTUP): header + index pieces = 6, no deadlines

  // ---- urgent zone of the primary reader (not PREWARM/BACKGROUND)
  // Only pieces inside the time-critical horizon (or blocked) get a deadline and 7. The rest get 6 with no deadline, so the
  // sequential walk from aP (which skips 7s) fetches them first; a 7 outside the horizon would be a hole at the reader frontier.
  if (prim != null && phase in {STARTUP, PLAYING}):
     budget = max(2L*L, min(B * P.urgentSec, P.urgentMaxBytes)); n = 0; bytes = 0; p = aP; last = -1
     while (p <= span.lastPiece && n < P.urgentMaxPieces && (n < P.urgentMinPieces || bytes < budget)):
        if (!have(p)):
           d = when {
             p == prim.blockedPiece                 -> 0
             H != null                              -> clamp((byteStartOf(p) - H) * 1000 / B - P.safetyMs, 0, P.maxDeadlineMs)
             else                                   -> n * P.ladderStepMs }
           if (playback != null && !playback.playing && p != prim.blockedPiece) d = max(d, P.pausedDeadlineFloorMs)
           d = max(d, last)                        // non-decreasing
           if (p == prim.blockedPiece || d <= horizon) { D.merge(p, d, ::minOf); prio[p] = 7; last = d }
           else prio[p] = max(prio[p], 6)
        n++; bytes += L; p++
  // sentinel: keeps libtorrent's time-critical list non-empty so cancel_non_critical never fires in steady state.
  // It is the LAST missing piece ≤ aheadEnd, so its hole sits at the tail of the read-ahead, not at the frontier.
  sentinel = null
  if (prim != null && phase in {STARTUP, PLAYING}):
     s = have.lastMissing(aP, aheadEnd)
     if (s >= 0 && s !in D) { D[s] = P.maxDeadlineMs; prio[s] = 7; sentinel = s }

  // ---- secondary readers (≤ 3, newest first)
  for r in (live - prim).filter { !it.ghost }.sortedByDescending { max(openedAt, lastProgressAt) }.take(3):
     q = span.pieceOf(r.pos)
     if (r.kind == HTTP && r.blockedPiece >= 0 && !have(r.blockedPiece)) { D.merge(r.blockedPiece, 0, ::minOf); prio[r.blockedPiece] = 7 }
     else for x in q .. q+1 where !have(x): prio[x] = max(prio[x], 6)

  // ---- edge pieces shared with other wanted files never go below their baseline (season-pack boundary)
  for x in {span.firstPiece, span.lastPiece}: prio[x] = max(prio[x], baseline(x))

  // ---- every deadline piece carries 7 in any vector
  for x in D.keys: prio[x] = 7

  seq = if (phase in {PLAYING, BACKGROUND}) (have.firstMissing(aP, aheadEnd).takeIf { it >= 0 } ?: null)?.let { it..aheadEnd } else null
  return Plan(A, prim?.id, D, sentinel, prio, seq, aheadEnd)
```

Why the base priorities have few levels:
- In sequential mode, libtorrent's picker first takes top-priority pieces only while they form a prefix of its sorted piece list.
- It then walks `[cursor, reverse_cursor)` in index order, skipping priority 0 and priority 7. Inside the range, levels 1..6 therefore behave the same.
- Contiguous read-ahead therefore comes from the range.
- Urgency comes from deadlines timed against the playhead. The once-per-second time-critical pass serves only pieces inside its horizon, which is why only those pieces get 7.
- Levels 4/6/7 matter outside the range: in STARTUP, in DOWNLOAD mode, and before connections are initialised.

Debug check (Phase 3, debug builds): once per stream after the first PLAYING plan, log `piecePriorities()` for `[aP-2, aP+K+4]` and `getDownloadQueue()` piece indices, to confirm the frontier is being fetched.

### 4.4 Playhead byte

```
playheadByte(i, prim):
  if (prim != null && prim.kind == IN_APP && playback?.fresh == true && playback.bufferedPositionMs >= playback.positionMs)
     return max(0, prim.pos - (playback.bufferedPositionMs - playback.positionMs) * B / 1000)   // anchored at the reader: VBR-tolerant
  if (playback?.fresh == true && rate.known && playback.durationMs > 0)
     return size * playback.positionMs / playback.durationMs                                  // linear fallback
  return null                                                                                  // external / bench without signals → ladder
```

### 4.5 `PlanDiff.diff` (pure) and op order

#### 4.5a `PlanMerge.merge` (pure, per torrent)

```kotlin
data class Overlay(val owner: Long /* prefetch/prewarm/continue token */, val targets: Map<Int, Int> /* torrent piece → prio */)
data class GuardState(val piece: Int, val pickedAtMs: Long, val extendedBytes: Long)
data class MergedPlan(val anchor: Long?, val deadlines: Map<Int, Int> /* torrent-indexed */, val sentinel: Int?,
                      val vector: ByteArray /* numPieces */, val seq: IntRange?, val guard: GuardState?,
                      val finishedAllowed: Boolean)
object PlanMerge {
    fun merge(primary: Plan?, primarySpan: FileSpan?, overlays: List<Overlay>, baseline: (Int) -> Int, have: PieceSet,
              numPieces: Int, streamMode: Boolean, prevGuard: GuardState?, profile: SchedulerProfile, rate: MediaRate,
              nowMs: Long): MergedPlan
}
```

```
vector[q] = max( (q in primarySpan) ? primary.prio[q - first] : baseline(q),  max over overlays of targets[q] ?: 0 )
for x in primary.deadlines.keys: vector[x] = 7

// guard: a claimed STREAM (or prewarm) torrent must never reach "finished". Reaching it makes libtorrent send upload_only
// to every peer, and remote seeds drop us; with close_redundant_connections it also drops our upload-only peers.
if (streamMode && no q with vector[q] > 0 && !have(q) excluding prevGuard?.piece):
   if (prevGuard != null && !have(prevGuard.piece))            → keep it (vector[g] = max(vector[g], 1))
   else if (prevGuard == null || now - prevGuard.pickedAt ≥ guardRepickMs) && extendedBytes + L ≤ guardMaxBytes:
        g = first missing q > (last q with vector[q] > 0), else first missing from the span start, else any missing
        vector[g] = max(vector[g], 1); guard = GuardState(g, now, prev.extendedBytes + L)
   else finishedAllowed = true                                 // bounded: log ev=finishedWhileClaimed; recovery in 7.1
guard state resets (extendedBytes = 0) whenever a non-guard wanted piece is missing again (the window moved)
```

Overshoot is bounded. A guard re-pick happens at most once per max(10 s, 2·L/B), which is at most B/2. Total guard extension is at most `guardMaxBytes` until the window moves on.

The PREWARM METADATA level has no wanted pieces at all, so its guard is the first piece of the chosen file. It is a useful header byte.

#### 4.5b `PlanDiff.diff`

```kotlin
data class AppliedState(val dueAt: Map<Int, Long> /* torrent piece → absolute elapsed ms */, val vector: ByteArray? /* numPieces */,
                        val seq: IntRange?, val seqAtMs: Long, val anchor: Long?, val sentinel: Int?)
data class DiffContext(val nowMs: Long, val forceFull: Boolean, val forceClear: Boolean /* supersede */,
                       val have: PieceSet, val profile: SchedulerProfile, val numPieces: Int)
sealed interface PlanOp {
    data object ClearDeadlines : PlanOp
    data class SetDeadline(val piece: Int, val ms: Int) : PlanOp
    data class ResetDeadline(val piece: Int) : PlanOp
    data class SetPriorities(val changes: List<Pair<Int, Int>>) : PlanOp     // sparse, torrent-indexed
    data class SetAllPriorities(val vector: ByteArray) : PlanOp             // whole torrent: the merged vector
    data class SetSequential(val first: Int, val last: Int) : PlanOp
    data object UnsetSequential : PlanOp
}
fun diff(prev: AppliedState?, plan: MergedPlan, ctx: DiffContext): Pair<List<PlanOp>, AppliedState>
```

```
ops = []
full  = prev == null || ctx.forceFull                // first apply, FILE_PRIO ack, ALERTS_DROPPED, recheck, STORAGE_MOVED, phase change, supersede
far   = ctx.forceClear || (prev?.anchor != null && plan.anchor != null && |plan.anchor - prev.anchor| > farSeekBytes)
applied = prev?.copy() ?: empty
if (far && applied.dueAt.isNotEmpty()):              // one deliberate cancel_non_critical: frees peer queues held by the old region
   ops += ClearDeadlines; for p in applied.dueAt.keys where !have(p): applied.vector[p] = 1; applied.dueAt = {}
// 1. adds and moves FIRST, so the critical list never empties on a normal tick
for (p, ms) in plan.deadlines where !have(p):
   abs = now + ms; old = applied.dueAt[p]
   if (p == plan.sentinel && p == applied.sentinel && old != null && old - now > maxDeadlineMs / 2) continue   // don't re-push every tick
   if (full || old == null || |abs - old| > deadlineSlackMs): ops += SetDeadline(p, ms); applied.vector[p] = 7
// 2. removals (had pieces leave libtorrent's list by themselves: no op)
for p in applied.dueAt.keys - plan.deadlines.keys where !have(p): ops += ResetDeadline(p); applied.vector[p] = 1   // reset leaves prio 1
// 3. priorities (torrent-wide: span plan, baseline and overlays are already merged)
if (full) ops += SetAllPriorities(plan.vector)
else { changes = [(q, plan.vector[q]) for q in 0 until numPieces where !have(q) && plan.vector[q] != applied.vector[q]]
       ops += if (changes.size > fullVectorThreshold) SetAllPriorities(plan.vector) else if (changes.nonEmpty) SetPriorities(changes) }
// 4. sequential
if (plan.seq == null) { if (applied.seq != null) ops += UnsetSequential }
else if (full || applied.seq == null || plan.seq.first != applied.seq.first
         || |plan.seq.last - applied.seq.last| >= seqMoveMinPieces || now - applied.seqAtMs >= seqReassertMs)
     ops += SetSequential(plan.seq.first, plan.seq.last)
dueAt' = plan.deadlines.mapValues { (p, ms) -> if (p was skipped as sentinel) applied.dueAt[p] else now + ms }
return ops, AppliedState(dueAt', plan.vector.copy(), plan.seq, seqAt, plan.anchor, plan.sentinel)
```

All the resulting calls are async posts executed in order on the network thread. Had pieces are never written.

### 4.6 PlanApplier mapping (reel-engine only, one EntryPlanner + PlanApplier + AppliedState per TorrentEntry)

Each TorrentEntry owns exactly one `EntryPlanner`, which holds the entry's `AppliedState`, overlays and guard state. On every replan it:
- asks the **primary** stream controller of the key (the newest non-superseded one) for its `Plan`;
- merges overlays (`prefetchNext`, prewarm, CONTINUE_AUTO) and the baseline;
- runs one `diff`.

Superseded or secondary controllers on the same key never apply ops of their own.

| PlanOp | libtorrent4j call |
|---|---|
| ClearDeadlines | `h.clearPieceDeadlines()` |
| SetDeadline | `h.setPieceDeadline(p, ms)` |
| ResetDeadline | `h.resetPieceDeadline(p)` |
| SetPriorities | `(h.swig()).prioritize_pieces_ex(int_byte_pair_vector(changes.map { int_byte_pair(it.first, it.second.toByte()) }))`, then call `.delete()` on the vector |
| SetAllPriorities | `h.prioritizePieces(Array(n) { Priority.fromSwig(v[it].toInt()) })` |
| SetSequential | the first time per entry activation: `h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)`; then `h.setSequentialRange(first, last)` |
| UnsetSequential | `h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)` |

Device checks (Phase 3 debug log):
- After the first SetSequential, log `h.getFlags().and_(SEQUENTIAL_DOWNLOAD).non_zero()`.
- In a debug build, dump `piecePriorities()` once per stream to confirm `prioritize_pieces_ex` behaves as a sparse update.

When the plan is applied:
- **Replan triggers**, coalesced per entry on reel-engine:
  - immediate: reader OPEN, BLOCKED; PlayerEvent.Seek; PlayerEvent.Handoff; supersede (with `forceClear` + `forceFull`);
  - CLOSE of the primary reader: no replan. The reader becomes a ghost (`ReaderView.ghost`), keeping the anchor and the urgent zone. A delayed kick fires after `ghostCloseMs`, unless an OPEN on the same stream arrives first. CLOSE of a non-primary reader is throttled.
  - throttled (250 ms phone / 500 ms TV): PIECE_FINISHED of a piece in `applied.dueAt`; the primary reader crossing a piece boundary;
  - FILE_PRIO ack completing the barrier (4.7) → `forceFull`;
  - the 1 s tick.
- **Steady state:** 0–3 async calls per second and zero sync calls.

### 4.7 FILE_PRIO barrier and readable gate
- `prioritizeFiles` is async. `on_file_priority` rewrites every piece priority and then posts FILE_PRIO. libtorrent may defer and coalesce vectors, so the first FILE_PRIO can belong to an earlier vector.
- Each entry counts `filePrioIssued` (++ on every `prioritizeFiles` / `filePriority(i, p)` call) and `filePrioAcked` (++ on every FILE_PRIO, and on FILE_ERROR for that torrent).
- The barrier holds while `acked < issued`. The planner keeps sending deadlines but no priority ops.
- The barrier is released when `acked == issued`, or after 1.5 s when `h.filePriorities()` equals the last vector. Release sets `forceFull` and kicks.
- Skip `prioritizeFiles` entirely when the intended vector equals the current one (cached in the entry).
- **Readable gate (per stream).** Each `StreamController` has `@Volatile var fileReady`.
  - It is set at prepare if the stream file's priority is already non-IGNORE and no barrier is open.
  - Otherwise it is set when the barrier releases with the stream file's priority non-zero.
  - Then `gate.signalAll()`.
  - Readers **block** (they do not fail) while `fileReady == false`: the predicate is passed into `PieceGate.await` as `ready`. Pieces fetched while the file had priority 0 (prewarm, prefetch) live in the part file until libtorrent exports them.

### 4.8 Startup (container- and resume-aware)
1. `StartupPlanner.initial` sets the targets:
   - header = `[0, min(size, 2 MiB))`;
   - index = byte-accurate tail by extension (4.1);
   - resumeGuess (only when startMs > 0, size ≤ 8 GiB and the rate is known) = the pieces around `size·startMs/durationMs`.

   Header and index are fetched in parallel with interleaved deadlines.
2. When the header pieces are readable, reel-io runs `ContainerProbe.step` with RandomAccessFile reads.
   - **MP4:** walk the top-level boxes; size==1 means largesize, size==0 means to EOF.
     - moov before mdat → `index = moov`, `needTail = false`, `headerEnd = moovEnd + 1 MiB`.
     - mdat first → `index = [mdatEnd, min(size, mdatEnd + 32 MiB))`.
     - moof or sidx → FMP4, `needTail = false` unless an mfra is found in the tail (1 MiB).
   - **MKV:**
     - Find EBML, then Segment (`0x18538067`, vint size, data start S).
     - SeekHead (`0x114D9B74`) → Seek (`0x4DBB`) → SeekID (`0x53AB`) == Cues `0x1C53BB6B` with SeekPosition (`0x53AC`) gives `cues = S + pos`, so `index = [cues, min(size, cues + 4 MiB))`.
     - Walk the top-level elements by size until the first Cluster (`0x1F43B675`) to get `headerEnd = cluster + 1 MiB` (cap 32 MiB). Big Attachments are skipped by their size; when the next element header lies beyond the bytes already read, return Need(range).
     - Without a SeekHead → keep the tail.
   - **TS:** tail 1 MiB. **AVI:** tail 4 MiB. **Other:** tail 4 MiB.
3. `refine` swaps the targets in. The diff resets deadlines on tail pieces that are no longer needed.
4. When the first real reader opens more than 2 pieces away from the header, the resume guess is dropped.
5. The startup set leaves the plan at startup exit (4.1).

### 4.9 Multiple readers, external players, bench
- **Primary reader** (4.3), in this order:
  1. the newest BENCH reader, or the newest IN_APP reader whose player signals are fresh with `playWhenReady`;
  2. otherwise the HTTP reader that most recently made progress (external handoff, HTTP-only streams);
  3. otherwise the newest IN_APP reader (paused in-app player, or a ghost).
- **Ghost:** a primary that closed less than `ghostCloseMs` ago with no OPEN since. It keeps the anchor and the urgent zone, so ExoPlayer's close/reopen on every extractor seek (MKV Cues, MP4 moov at the end) does not reset deadlines.
- **Superseded readers** (the old token after a season-pack switch) are excluded and fail at their next wait slice, through their controller's `closedReason`.
- **HTTP readers are never killed for being idle:** VLC and MX keep an index connection open. They are demoted only. They exit when the socket probe sees the client leave.

### 4.10 Download-mode streams ("Play now")
At prepare:
- remember `meta.wasAutoManaged` (persisted);
- `unsetFlags(AUTO_MANAGED)`;
- `resume()`. This happens **before** awaitMetadata, so a queued magnet can fetch metadata.

If the played file is IGNORE, raise it to DEFAULT for this stream only (`session.raisedFile`) and go through the barrier (4.7).

The base plan is all DEFAULT (4.3), so the download is never shrunk. No guard is used: a DOWNLOAD torrent finishing is legitimate.

When the last claim ends:
1. `clearPieceDeadlines()`;
2. `SetAllPriorities(baseline)`;
3. `unsetFlags(SEQUENTIAL_DOWNLOAD)`;
4. lower the raised file back;
5. restore AUTO_MANAGED and PAUSED as saved; re-pause if `userPaused`.

### 4.11 Paused and idle behaviour
- **Player paused:** deadlines get the 10 s floor, which is beyond the horizon, so only the blocked piece and the sentinel carry deadlines. The range and priorities stay, so the buffer keeps filling.
- **Background:** PlanInput phase BACKGROUND: no deadlines; the range is kept; `setDownloadLimit(16 KiB/s)` (section 7). The guard stays.
- **No readers (in-app, after `player.stop()`, past the ghost window):** the anchor is `lastAnchor`. The range stays, there are no deadlines, and the sentinel is not set.

---

## 5. Data path

### 5.1 `StreamReaderCore.read` (shared by DataSource, HTTP and bench)

```
read(pos, buf, off, len):
  alive()                                             // controller closedReason (superseded/removed/released/Failed) or deadReason
  if (pos >= size) return -1
  if (pos !in readableStart until readableEnd):
     p = span.pieceOf(pos)
     if (!(fileReady() && gate.isReadable(p))):
        blockedPiece = p; t0 = now; listener.onBlocked(p)               // engine: deadline 0 at once
        if (gate.flushPending(p)) listener.needFlush(p)
        lastLong = t0
        try gate.await(p, 250, ready = fileReady) {
              alive(); probe?.invoke()
              if (now - lastLong >= 2000) { lastLong = now; listener.longBlock(p) }   // havePiece safety net
              if (gate.flushPending(p)) listener.needFlush(p) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException() }
        finally { blockedPiece = -1 }; listener.onUnblocked(p, now - t0)
     last = gate.readableRunEnd(p, span.pieceOf(min(size - 1, pos + 64 MiB)))   // flush-checked, never the raw have-run
     readableStart = span.byteStartOf(p); readableEnd = span.byteEndOf(last)
  raf = file ?: opener.open().also { file = it }      // RandomAccessFile: an interrupt can't close it (FileChannel gotcha)
  n = min(len, readableEnd - pos); raf.seek(pos); r = raf.read(buf, off, n)
  alive()                                             // an engine-side stop between seek and read surfaces as the typed exception
  if (r < 0) throw StreamErrorException(FILE_ERROR, "short file")
  position = pos + r; bytesRead += r; lastProgressNanos = now
  if (span.pieceOf(position) != lastPiece) { lastPiece = …; listener.onPieceCrossed(position) }   // volatile + conflated post, no JNI
  return r
```

- **Opener.** It resolves `entry.path(fileIndex)` each time it is called. The path generation increases after Keep; on a generation change the reader closes its file and reopens. A missing file is retried once after 1 s, then raises `StreamErrorException(FILE_ERROR)`. Because of the readable gate, a part-file-only piece is never read through the real path.
- **No time-based failure.** A read never fails because it is slow. It fails only on interrupt, on `alive()` (superseded, removed, released, `StreamStatus.Failed`, or reader marked dead), or when the probe reports a disconnect.
- **Engine-side stop never closes the file.** `stopStream`, supersede and removal set the controller's `closedReason` (or the reader's `deadReason`) and call `gate.signalAll()`. The owning thread closes its RandomAccessFile in `close()`. Readers therefore fail with `StreamGoneException`, never a generic EBADF IOException.
- **Flush gate (disk = MMAP only).**
  - `markVerified(p)` happens on the alert thread; `needsFlush` is a gate property (`disk == MMAP`).
  - When a reader is blocked on a `flushPending` piece, the engine polls `h.getDownloadQueue().none { it.pieceIndex() == p }` for **that piece only**. Polls back off at 20 ms, 50 ms, then 100 ms, coalesced across readers of the entry, for at most 2 s, using `schedule` (never sleep). Each poll counts in `jni/s` and in `flushWaits`.
  - When the piece has left the queue, the engine calls `gate.markFlushed(p)`, which signals waiters.
  - Pieces verified more than 2 s ago are readable without polling.
  - With disk = POSIX, `needsFlush = false`.
- **Gate creation and have-bitset seeding.** On reel-engine:
  - **Metadata known at add** (`.torrent`, restore with an info dict in the resume data, or `AddTorrentParams.getTorrentInfo()` non-null): create the gate **before** `addTorrent`, from `TorrentInfo.numPieces()`, and publish it in the entry map.
  - **Magnet without metadata:** the alert thread appends PIECE_FINISHED indices for keys with no gate to a per-key `ConcurrentLinkedQueue`, bounded at 4096 entries; on overflow it flags a re-seed. At METADATA_RECEIVED the engine:
    1. creates the gate;
    2. publishes it;
    3. runs `h.status(TorrentHandle.QUERY_PIECES)` and ORs in the bits;
    4. drains the queue into the gate;
    5. calls `signalAll()`.
  - **Re-seed** with `status(QUERY_PIECES)` (OR bits, then `signalAll()`): after a recheck, after STORAGE_MOVED, on ALERTS_DROPPED, and on **TORRENT_CHECKED**. Resume-loaded pieces post PIECE_FINISHED during CHECKING_RESUME_DATA, so a prepare during checking would otherwise see a partial bitfield.
- **Missed-wake safety net.** `longBlock(p)` posts to the engine at most once per 2 s per blocked reader. The engine calls `h.havePiece(p)` once (one sync call, counted). If it returns true, the engine calls `gate.markVerified(p)` and logs `ev=missedWake p=…`. The target is `missedWake = 0`; a non-zero count is a bug to investigate, not a normal path.

### 5.2 HTTP server (external players)
- **Ownership:** a single `LocalStreamServer` owned by `TorrentEngine`, started lazily on the first `httpUrlFor`.
- **URL:** `http://127.0.0.1:<port>/s/<key>/<fileIdx>/<token>`. A wrong or superseded token gets 404 at once.
- **Body:** `serve()` returns `newFixedLengthResponse(PARTIAL_CONTENT|OK, mime, ReaderInputStream(StreamReader(kind = HTTP)), len)`.
  - Always call `response.closeConnection(true)`, so an aborted body ends at EOF and does not stall keep-alive for 5 s.
  - `useGzipWhenAccepted` → false.
- **Socket binding.** `createClientHandler` runs on the accept thread, so the socket is bound on the handler's own thread:
  ```kotlin
  override fun createClientHandler(s: Socket, i: InputStream): ClientHandler = object : ClientHandler(i, s) {
      override fun run() { CURRENT_SOCKET.set(s); try { super.run() } finally { CURRENT_SOCKET.remove() } }
  }
  ```
  `serve()` (which runs on that thread) reads `CURRENT_SOCKET.get()` and passes it to `engine.openReader(…, socket = s)`, so the probe holds the socket itself and never depends on which thread reads. A null socket means no probe.

  If Kotlin rejects the anonymous subclass of the Java inner class, use a 10-line Java subclass in `app/server`.
- **Disconnect probe.** In a wait slice, at most once per second:
  1. `val old = s.soTimeout; s.soTimeout = 1`;
  2. `s.getInputStream().read()`:
     - −1 or an IOException means the client is gone: throw `StreamGoneException("client left")`;
     - a `SocketTimeoutException` means it is alive;
     - a byte ≥ 0 means the client sent data; treat that as alive (the connection is closed after this body anyway);
  3. restore `soTimeout = old`.
- **Threads:** `DefaultAsyncRunner` stays unbounded (caveat: a bounded pool can deadlock with blocked readers). Stale readers now exit within about 1 s.
- **readableUntil:** extended by the same `readableRunEnd` logic. NanoHTTPD copies in 16 KiB chunks from the stream.

### 5.3 In-app seek
1. ExoPlayer cancels the load and `Loader` interrupts the thread.
2. `PieceGate.await` throws, the reader throws `InterruptedIOException`, and `onLoadCanceled` fires.
3. `close()` posts CLOSE. The primary becomes a ghost, with no replan yet.
4. The new `open(position)` posts OPEN and is planned at once: urgent zone at the new byte, deadline 0 when blocked, and a far-seek ClearDeadlines only when the jump is larger than `farSeekBytes`.

No seek-hint byte guess is used. `PlayerEvent.Seek` feeds only stats and the stall clock.

---

## 6. Player integration (PlayerActivity, player setup only)

### 6.1 Data sources and policies
```kotlin
val direct = OkHttpDataSource.Factory(app.http.newBuilder()
        .connectTimeout(10, SECONDS).readTimeout(15, SECONDS).retryOnConnectionFailure(false).build())
    .setUserAgent(CHROME_UA)                                   // source headers stay in the existing ResolvingDataSource, never sent to subtitle hosts
val other = DefaultDataSource.Factory(this, ResolvingDataSource.Factory(direct) { spec -> /* existing header logic */ })
val dsf = ReelDataSourceFactory(TorrentDataSource.Factory(app.engine), other)
val msf = DefaultMediaSourceFactory(dsf).setLoadErrorHandlingPolicy(ReelLoadErrorPolicy())
```
- The `media3-datasource-okhttp:1.11.1` dependency must be in `app/build.gradle.kts`. Add it if missing.
- The loopback scheme never goes through OkHttp.

`ReelLoadErrorPolicy : DefaultLoadErrorHandlingPolicy()`:
- **Retry counts stay at the default**, so subtitles never retry forever (caveat).
- **`getRetryDelayMsFor(info)`:**
  - if the cause chain contains `StreamGoneException` or `StreamErrorException` → `C.TIME_UNSET` (fatal now, with the engine's reason);
  - otherwise `super`.

  Slowness never reaches the policy, because reads block.

### 6.2 LoadControl, back buffer, seek parameters

Values are keyed by `formFactor`.

| | Phone | TV |
|---|---|---|
| `setBufferDurationsMs(min, max, forPlayback, afterRebuffer)` | 50 000, 50 000, 2 000, 5 000 | 30 000, 30 000, 2 000, 5 000 |
| `setTargetBufferBytes` | default | 64 MiB (`64 * 1024 * 1024`) |
| `setPrioritizeTimeOverSizeThresholds` | false | false |
| `setBackBuffer(ms, true)` | `clamp(32 MiB * 1000 / B, 5_000, 30_000)` | `clamp(12 MiB * 1000 / B, 3_000, 10_000)` |

Unit test (`BackBufferTest`, core):
- Phone: B = 1 / 4 / 12 MB/s → 30 000 / 8 388 / 5 000 ms.
- TV: → 10 000 / 3 145 / 3 000 ms.

- Setting min = max gives continuous pulling, which is safe only now that reads cannot time out.
- B comes from `hints.sizeBytes / hints.durationMs`. Fallback by quality label: 2160p → 25 Mbps, 1080p → 8 Mbps, otherwise 3 Mbps.
- The `reel-torrent` scheme is not in the local-playback schemes, so the streaming values apply.
- Direct links keep their existing LoadControl.

Player setup:
- `exo.setWakeMode(C.WAKE_MODE_NETWORK)`.
- `exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)`. This stays the resting value.
- D-pad:
  - backward: `setSeekParameters(PREVIOUS_SYNC)`, `seekTo(target)`, then immediately `setSeekParameters(CLOSEST_SYNC)`;
  - forward: CLOSEST_SYNC is already set.

  Player messages are handled in order, so the seek uses PREVIOUS_SYNC and every other seek source (ffwd button, scrubbing) sees CLOSEST_SYNC. The resume position is unaffected (caveat).
- `ExoPlayer.Builder.setStuckBufferingDetectionTimeoutMs` stays at the default (600 000).

### 6.3 Error policy (`onPlayerError`, unwrap `error.cause`)

| Cause | User sees | Action |
|---|---|---|
| `StreamErrorException(STORAGE_FULL)` | the engine's message, e.g. "Needs 6.2 GB, 3.1 GB free. Free some space or pick a smaller source." | "Clear stream cache" + "Other sources" |
| `StreamErrorException(other)` | `message` | "Other sources" |
| `StreamGoneException` | nothing if finishing, else "This stream stopped" | — |
| `HttpDataSourceException` whose `dataSpec.uri` matches a `MediaItem.subtitleConfigurations` URI (any source kind) | nothing (silent) | rebuild the MediaItem without that subtitle, `setMediaItem(item, currentPosition)` + `prepare()`; log `ev=subDropped` |
| `errorCode == 1003` (TIMEOUT, stuck buffering) on torrent | "Waiting for peers…" stall panel | "Other sources" + "Retry" (`exo.prepare()`) |
| DIRECT 2000–2999 | "Reconnecting…" | `exo.prepare()` with 1/2/4/8 s backoff, at most 4 attempts; `InvalidResponseCodeException.responseCode` 403/410 → "This link expired" + "Other sources" |
| 3000–4999 | "This device can't play this file" | "Open in another player" + "Other sources" |

The subtitle row is checked before the DIRECT and 3000–4999 rows.

### 6.4 Lifecycle
- **onCreate:** `tapElapsedMs = intent.getLongExtra(EXTRA_TAP_MS, SystemClock.elapsedRealtime())`, passed as `StreamHints.tapElapsedMs` through `startStream(StreamRequest)`. The extra is set only if R4/R7 add it at the tap.
- **openExternal():**
  - uses `engine.httpUrlFor(preparedStream!!)` and `preparedStream.mimeType`; DIRECT sources keep their URL;
  - `errorExternal` visibility is `preparedStream != null || source is DIRECT`.
- **onStart:**
  - `engine.resumeStream(hash, preparedStream)`;
  - if `exo.playbackState == STATE_IDLE && prepared && !(externalHandoff && stoppedSinceHandoff)`, then `exo.prepare()`. After a handoff the player stays idle; the PlayerView play button prepares an idle player.
- **onStop:**
  - `externalHandoff` (the chooser really opened another app):
    1. `stoppedSinceHandoff = true`;
    2. `saveProgress()`;
    3. `exo.stop()`, so the IN_APP reader closes and the signals go stale;
    4. `engine.onPlayerEvent(owner, Handoff)`;
    5. no `suspendStream`.

    The HTTP reader then becomes primary (4.9).
  - otherwise:
    1. `saveProgress()`;
    2. `exo.stop()` (the item and position are kept; decoders and the loader are released);
    3. if `!isFinishing && !leavingForNext`, then `engine.suspendStream(hash, preparedStream)`.
- **onDestroy:**
  - `externalHandoff && engine.hasReaders(hash, preparedStream /* kind = HTTP */)` → `stopWhenIdle`;
  - otherwise `stopStream(hash, preparedStream)` (owner-checked);
  - release `nextTicket` if not `leavingForNext`.
- **Signals.**
  - A 2 Hz loop under `repeatOnLifecycle(STARTED)` writes `signals.{positionMs, bufferedPositionMs, durationMs, playing, playWhenReady, updatedAtElapsedMs}`.
  - `onPositionDiscontinuity(old, new, DISCONTINUITY_REASON_SEEK)` → `onPlayerEvent(Seek(old.positionMs, new.positionMs, leftBuffer))`, where `leftBuffer` uses the bufferedPosition sampled **before** the seek (caveat).
  - `ReelAnalyticsListener` sends FirstFrame, Ready, RebufferStart and RebufferEnd, and logs `onLoadCanceled` timing.

### 6.5 Subtitles and HLS
- **Subtitle wait.** Wait for addon subtitles only when a preferred subtitle language is set or `CaptioningManager.isEnabled`, and then for at most 400 ms.
- **Late addon tracks:**
  - before FirstFrame → `setMediaItem(itemWithSubs, startMs)` + `prepare()` (local re-read, cheap);
  - after FirstFrame → attach at the next user seek that leaves the buffer (`setMediaItem(item, currentPosition)`);
  - never during uninterrupted playback.

  A dead subtitle URL on any of these paths is dropped silently (6.3).
- **HLS without an extension (DIRECT only, unknown extension).** In parallel with the subtitle wait, send `GET Range: bytes=0-1023` with a 1.5 s cap through the derived client. If the Content-Type contains `mpegurl` or the body starts with `#EXTM3U`, call `setMimeType(MimeTypes.APPLICATION_M3U8)`.

### 6.6 Stall UI (from `engine.streamStatus(owner)`; StallPolicy, 1 Hz)

| Condition | Status | UI / action |
|---|---|---|
| Blocked ≥ 3 s | `Buffering` | "Buffering · ↓x/s · N peers" |
| Blocked ≥ 30 s, stream rate < 16 KiB/s, no progress | `Stalled` | "Waiting for peers that have this part" + Other sources; `forceReannounce(0, -1, IGNORE_MIN_INTERVAL)` + `forceDHTAnnounce()` once |
| Blocked ≥ 120 s with 0 peers | still `Stalled` | playback keeps waiting; Back always works |
| Metadata missing | `FindingPeers` | "Finding peers… N connected" |

Metadata deadline: 45 s with 0 connected peers, else 90 s → `Failed(NO_METADATA)`.

---

## 7. Lifecycle and cache

### 7.1 LeaseTable (pure; applied on reel-engine)

```kotlin
sealed interface Claim { val token: Long }
data class Starting(override val token: Long, val createdTorrent: Boolean) : Claim
data class Player(override val token: Long, val backgroundSince: Long? = null) : Claim
data class External(override val token: Long, val openReaders: Int, val lastProgressAt: Long, val idleLimitMs: Long) : Claim
data class Prewarm(override val token: Long, val expiresAt: Long?, val reason: PrewarmReason) : Claim
data class Grace(override val token: Long, val expiresAt: Long) : Claim
data class KeepPending(override val token: Long) : Claim
enum class Activity { ACTIVE, BACKGROUND, PREWARM, GRACE, PAUSED }
fun desired(claims: List<Claim>, now: Long, metered: Boolean, cfg: LeaseConfig): Pair<Activity, Long /* next wake-up */>
```

`External.openReaders` counts registered HTTP readers that are not superseded and have not failed the socket probe. **Blocked readers count as open.**

Resolution, highest first:

| Claim state | Activity | What it means |
|---|---|---|
| `Starting`, `Player(backgroundSince == null)`, or `External` with `openReaders > 0` or `lastProgressAt` within 60 s | ACTIVE | unpaused; limit from the governor |
| `Player(backgroundSince)` within `bgGraceMs` | BACKGROUND | no deadlines; `setDownloadLimit(16 KiB/s)`; connections kept |
| `External` with no open reader, idle 60 s to `idleLimitMs` | BACKGROUND | same throttle |
| `Prewarm` | PREWARM | |
| `Grace` | GRACE | no deadlines, 16 KiB/s |
| none | PAUSED | `clearPieceDeadlines()` → `SetAllPriorities(baseline)` → `unsetFlags(SEQUENTIAL_DOWNLOAD)` → `pause()` → `saveResumeData(SAVE_INFO_DICT)` → `meta.lastStoppedAt = now`, `lastPosFrac`, `durationMs` → trim (7.4) |

Grace durations:
- `bgGraceMs`: phone 60 s, TV 90 s.
- `exitGraceMs`: 30 s.
- On a metered network both are 0, so the torrent pauses at once.
- `EngineFlags.grace = 0` restores strict pause everywhere.

Return within grace: `setDownloadLimit(0)` and a full replan.

Return after a real pause:
1. `resume()`;
2. `setFlags(UPLOAD_MODE)` then `unsetFlags(UPLOAD_MODE)`, which resets `last_connected` for an immediate redial (peers are already gone, so its upload_only side effect costs nothing here);
3. `forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL)`;
4. `forceDHTAnnounce()`.

UPLOAD_MODE is never left set while streaming.

**Finished while claimed** (guard exhausted, 4.5a): when the torrent un-finishes on the next window move, call `forceReannounce(0, -1, IGNORE_MIN_INTERVAL)` + `forceDHTAnnounce()` (no UPLOAD_MODE toggle: peers may still be connected) and log `ev=finishedWhileClaimed`.

**close_redundant_connections:** set to `false` with a delta pack while any Starting, Player, External, Prewarm or Grace claim exists. It goes back to the default (`true`) when the last such claim ends.

**TV: one stream at a time** (`formFactor == TV`). When a new ACTIVE stream starts, every other STREAM torrent in BACKGROUND or GRACE is paused at once.

### 7.2 Flows
**startStream** (engine dispatcher; it suspends, never blocks):
1. `token = next`.
2. For each candidate, through HedgePolicy (7.6), `addOrGet(cand)` returns `AddResult(key, created)`. A fresh STREAM add:
   - flags `defaults − AUTO_MANAGED − PAUSED`, plus `DEFAULT_DONT_DOWNLOAD` **only when `fileIdx` is known**, with `AddTorrentParams.filePriorities(Array(idx+1){ IGNORE }.also { it[idx] = DEFAULT })`. With `fileIdx` unknown, files keep the default priority until prepare sets the vector. Otherwise the torrent would be "finished" the moment metadata arrives (no wanted pieces) and seeds would drop us.
   - trackers = magnet trackers ∪ `DEFAULT_TRACKERS`;
   - `handle.forceDHTAnnounce()`.
3. Add a `Starting` claim and consume any Prewarm claim on the key.
4. `awaitMetadata` (7.3).
5. `prepare`:
   - FileChoice pick: idx → hint → `meta.streamFileIndex ?: wantedFiles.singleOrNull() ?: onlyFileIndex` → wantedHints/onlyFileHint → largest non-sample video whose priority is not IGNORE → largest video;
   - **size check** (7.4): fail fast with `Failed(STORAGE_FULL, "Needs X GB, Y GB free")` before any piece is fetched;
   - intended file priorities, then the barrier when they changed (4.7);
   - make sure the gate exists and is seeded (5.1);
   - make room (7.4);
   - **supersede** the previous stream on the key:
     1. set the old controller's `closedReason = StreamGoneException("superseded")` and invalidate its token;
     2. `gate.signalAll()` (old readers fail within one slice, new readers are unaffected);
     3. the EntryPlanner's next diff runs with `forceClear + forceFull`;
   - `(h.swig() as torrent_handle).set_max_connections(-1)`, which lifts any prewarm or hedge cap;
   - `resume()` if paused (plus reannounce if paused > 60 s); `setDownloadLimit(0)`;
   - first full plan (phase STARTUP);
   - kick the governor.
6. Replace `Starting` with `Player(token)` and return `StreamInfo(token, uri)`. Log `ev=prepared conns=<max_connections()>`.

`finally` runs under `withContext(NonCancellable + engineDispatcher)`. If prepare did not complete:
- remove `Starting`;
- if no claim is left: when `created && totalDone == 0`, call `session.remove(h, SessionHandle.DELETE_FILES)`; otherwise `clearPieceDeadlines()` + `pause()`.

**Other flows:**
- **suspendStream:** sets `backgroundSince`. **resumeStream:** clears it.
- **stopStream(owner):** only when `entry.stream.info === owner`.
  - The Player claim becomes `Grace(exitGraceMs)`.
  - The controller sets `closedReason = StreamGoneException("released")` and calls `gate.signalAll()`. It never closes reader files itself (5.1).
  - A Starting or Player claim held by another token on the same key leaves the torrent untouched (invariant: a season-pack switch never pauses the shared torrent).
- **stopWhenIdle:** adds an `External` claim (idle limit 10 min, invariant kept).

### 7.3 Metadata waiter, DEFAULT_DONT_DOWNLOAD, Keep
- **Metadata waiter.** One `CompletableDeferred` per entry, created before the add and completed only by METADATA_RECEIVED or an ADD_TORRENT that already has metadata. Waiters never remove it.
  1. Register.
  2. Check `h.status(true).hasMetadata()`; `status(true)` forces a fresh query (`status()` is cached for 500 ms).
  3. Loop `withTimeoutOrNull(1000) { deferred.await() }`, re-checking `h.status(true).hasMetadata()`.

  Only `TimeoutCancellationException` maps to NO_METADATA; `CancellationException` is rethrown.
- **DEFAULT_DONT_DOWNLOAD side effects (Phase 1).** `keep()` and `addMagnet(DOWNLOAD)` on a torrent added with `DEFAULT_DONT_DOWNLOAD` must set file priorities explicitly through `FileChoice.explicitPriorities(meta, numFiles)`:
  - `fullTorrent` → all DEFAULT;
  - `wantedFiles` / `wantedHints` → those files;
  - keep with no wanted files → all DEFAULT.

  Before metadata, store the intent (`meta.pendingFileIntent = true`, persisted) and apply it on METADATA_RECEIVED.
- **Keep (own copy, never on the TV network thread):**
  1. If any ACTIVE, BACKGROUND, External or Starting claim exists → `keepPending = true` (persisted). It runs when the entry reaches PAUSED.
  2. `performKeep`: `pause()`, `flushCache()`, wait for TORRENT_PAUSED (≤ 5 s).
  3. On reel-io, the move set is **every entry in the stream save dir** (`File(meta.savePath)`). That includes the hidden part file `.<hex>.parts`, which holds the pieces of IGNORE'd files, season-pack boundary pieces and prefetched pieces.
     - Check for name conflicts in the destination; fail with "A file named … already exists".
     - Move the part file first, then `File.renameTo` each remaining entry.
     - On cross-mount failure: check free space ≥ bytes + 256 MiB, then copy (part file first; `FileChannel.transferTo` in 8 MiB chunks, `fsync`), and verify sizes.
  4. On engine: `h.moveStorage(dest, MoveFlags.RESET_SAVE_PATH_UNCHECKED)`. Data is already in place, so there is no recheck; a recheck would stall the TV network thread.
  5. On STORAGE_MOVED:
     - meta `mode = DOWNLOAD`, `savePath = dest`, `keepPending = false`;
     - `saveResumeData(SAVE_INFO_DICT)`;
     - explicit file priorities (FileChoice);
     - `setFlags(AUTO_MANAGED)`; `resume()` unless `userPaused`;
     - path generation++ (readers reopen); delete the source after a copy;
     - device-check log: `status(QUERY_PIECES).pieces().count()` must equal the pre-keep count and `state != CHECKING_FILES`, logged as `ev=keep pieces=a/b state=…`.
  6. On failure: stay STREAM, set `notice = "Couldn't save: …"`, delete the partial copy.

### 7.3a Converting between download and stream cache (Downloads screen)
Two user actions on the Downloads screen move a torrent between the modes. The decisions are pure (`core/stream/ModeChange`); the engine only applies them.

- **Save as download** (a stream-cache entry → Downloads) is Keep (7.3), unchanged: the pieces already fetched are reused and the whole torrent (or the wanted files) continues downloading as a DOWNLOAD (AUTO_MANAGED, resumed unless `userPaused`). If the entry's `savePath` already is the downloads folder (it was a download kept as cache, below), no storage move is issued.
- **Keep as stream cache** (a download → stream cache), `keepAsCache(hash)`:
  1. `ModeChange.toCache(ModeFacts(isDownload, claimed, keepPending, cachePending))`:
     - `NONE` (already STREAM, nothing pending) → nothing;
     - `CANCEL_PENDING` (STREAM with `keepPending`) → `keepPending = false` (the later wish wins);
     - `DEFER` (a player claims it: ACTIVE, BACKGROUND, External or Starting) → `cachePending = true` (persisted), applied in `stopStream` where `keepPending` is applied;
     - `NOW` → step 2.
  2. **In place, no file move.** The data stays where it is (`savePath` unchanged). A move from the public Downloads folder to `files/streams/<key>` crosses mounts on both devices, i.e. a full copy needing the file's size again in free space, and the user asked to *stop keeping* the file, not to spend space on it. Eviction deletes through `remove(DELETE_FILES)` on the entry's own `savePath`, and the orphan sweep (7.4) only touches `streams/*`, so an entry outside `streams/` is safe.
  3. `unsetFlags(AUTO_MANAGED)`, `pause()` (a stream-cache entry that no player reads is paused, 7.2).
  4. Meta: `mode = STREAM`, `fullTorrent = false`, `wantedFiles = wantedHints = []`, `pendingFileIntent = false`, `userPaused = pausedForSpace = seedingDone = false`, `keepPending = cachePending = false`, `streamFileIndex = ModeChange.cacheStreamFile(streamFileIndex, wantedFiles, snapshot.fileIndex)`, `lastUsedAt = ModeChange.cacheLastUsedAt(now, lastUsedAt)`; `lastStoppedAt` is left alone, so the entry is not protected like a title stopped mid-watch. `saveResumeData(SAVE_INFO_DICT)`.
  5. File priorities are left as they are; the next `prepareStream` sets the STREAM vector (only the played file wanted).
  6. Post `trimCache(DEFAULT)`: from now on the normal cache policy (7.4) applies, so a converted download larger than the budget can be evicted on that trim (LRU: older entries go first).
- **Change source** (Downloads → Sources page in download context for the same film / episode; `core/library/SourceSwap`): the download's origin (`type`, `tmdbId`, `season`, `episode`) is stored by the app in `files/download_origins.json` when the download starts from a Sources page or the launcher API; downloads added from a magnet link have none and get no Change source.
  - `SAME_FILE` → nothing.
  - `SAME_TORRENT` (another file of the same torrent) → `addMagnet(DOWNLOAD, fileIdx)`: added to the wanted files (never narrows, 3.2), nothing fetched is lost.
  - `NEW_TORRENT` / `DIRECT` → after the user confirms ("The X downloaded so far will be lost"): start the new download first (`addMagnet(DOWNLOAD)` or the download manager); only when that succeeds, `remove(old, deleteFiles = true)`. A failed start leaves the old download untouched.
- **Tests:** JVM only (`DownloadChangesTest`): every ModeStep branch, the cache file choice, swap kinds and confirmation text, origin round-trip. No test creates a session or adds a torrent, so none can start P2P traffic.

### 7.4 Cache policy (pure `CachePolicy`)
```
budget PHONE = min(clamp(0.15*(free+cache), 5 GiB, 20 GiB), free + cache − 2 GiB)
budget TV    = min(4 GiB, free + cache − 1.5 GiB)
hardFloor    = TV 1 GiB, PHONE 2 GiB (free)
candidates   = STREAM entries with no claim, !keepPending, !evicting
protected    = the 2 most recent lastStoppedAt + (lastPosFrac in [0.02, 0.95) && lastUsedAt within 30 days)
               + the top 5 of ReelApp.history.continueWatching() whose key is cached (read-only)
evict: unprotected by LRU (max(lastUsedAt, lastStoppedAt)), then protected by LRU only while free < hardFloor,
       until used ≤ budget && free ≥ hardFloor
trimCache(0) = all candidates, ignoring protection (claims still win)
reclaimable  = Σ size of candidates (protection ignored)
```

- **When it runs** (on reel-engine, deletes on reel-io): on the transition to PAUSED, at prepare (make room), every 60 s when `free < hardFloor`, on ENOSPC, on a free-space-guard request (8.1), and on an explicit clear.
- **Eviction decision** happens on reel-engine and marks entries `evicting` before posting deletes. `cacheBytes()` and `reclaimableCacheBytes()` exclude `evicting` entries.
- **Deletion:** `session.remove(h, SessionHandle.DELETE_FILES)` only. The redundant `deleteRecursively` is gone.
- **Orphan sweep:** 30 s after restore, on reel-io.
  - It is skipped entirely when any restore failed.
  - Keep set = `{ File(meta.savePath ?: handle.savePath()).canonicalPath }` over all registered entries.
  - It deletes only `streams/*` directories whose canonical path is not in the keep set, and never derives paths from `streams/<key>`.
- **Allowance at prepare** (`CachePolicy.allowance(need, free, reclaimable, hardFloor, metered, B)`):
  ```
  need = fileSize − doneInFile
  if need > free + reclaimable − hardFloor       → Failed(STORAGE_FULL, "Needs ${need} , ${free + reclaimable − hardFloor} free")
  make room: evict until free − hardFloor ≥ need
  if metered → StorageAllowance(fullFile = false, aheadCap = max(B*300 s, 64 MiB), metered = true)
  else       → StorageAllowance(fullFile = true,  aheadCap = Long.MAX_VALUE, metered = false)
  ```
  - Window mode is a bandwidth limiter only, used on metered networks. Disk is guaranteed by the size check.
  - The allowance is re-evaluated on the network callback when `metered` flips.
  - There is no `clamp(lo, hi)` with a possibly inverted range anywhere in CachePolicy.
  - Mid-play ENOSPC (another app filled the disk) is best-effort until Phase 7 skeleton eviction: `clear_error`, make room, `resume`.
- **rankPrefs** (SourceState):
  - TV only; `if (!isTv) return RankPrefs()` is unchanged.
  - `maxBytes = CachePolicy.maxStreamableBytes(free, engine.reclaimableCacheBytes(), hardFloor)`, the same function the prepare size check uses.
  - No JNI on main.

### 7.5 Prefetch and prewarm
- **Same torrent (season pack), `prefetchNext(owner, idx, hint)`.**
  - Allowed only when `aheadSec ≥ 60` or the current file is complete, the network is unmetered, and `NetworkGate.trusted()`.
  - The next file's file priority **stays IGNORE** (caveat).
  - Its header (2 MiB) and byte-accurate tail (4 MiB) pieces become an **overlay on the entry** (`Overlay(token, targets = piece → 6)`), merged torrent-wide by `PlanMerge`. Every full re-apply keeps them at 6, and the current stream's deadline pieces stay at 7. There is no second controller.
  - The data lands in the part file. When the next stream raises its file priority, its readable gate (4.7) waits for the barrier.
  - The overlay is removed on cancel, leave or source change.
- **Different torrent:** `prewarm(PrewarmRequest(level = STARTUP, reason = UP_NEXT, ttlMs = 10 min))`.
  - Add with `DEFAULT_DONT_DOWNLOAD`, not auto-managed, `swig().set_max_connections(40 phone / 20 TV)`.
  - Wait for metadata, pick the file, then header and tail pieces at 6 as an overlay with the file at IGNORE, plus the guard (4.5a).
  - The next player's `startStream` consumes the claim and calls `set_max_connections(-1)`.
- **Continue watching (`EngineFlags.autoWarm`, default OFF until validated at home).**
  - When enabled, it is triggered only by `engine.onHomeVisible()` (R7), at most once per 30 min. It never fires on engine ready or a service restart.
  - Requires unmetered and `NetworkGate.trusted()`.
  - It takes the most recent STREAM meta whose `lastPosFrac` is in [0.02, 0.95), last used within 7 days, and still cached.
  - `prewarm(METADATA, CONTINUE_AUTO, ttl 10 min)`.
  - The resume window `[pos − 10 s·B, pos + 60 s·B]` becomes an overlay at priority 4, so seeds stay interested and connected.
  - TV: only when no stream is active.
- **Sources (opt-in, default off; needs request R3):** 1.5 s after `search.done`, `rememberSourceSearch` calls `prewarm(best, METADATA, SOURCES_BEST)` (and STARTUP level on unmetered). `onDispose` → `release()`.
- **NetworkGate (`EngineFlags.p2pNetworkGate = 1`).**
  - Fingerprint = SHA-256 of the default network's `LinkProperties`: interface name, gateway addresses of the default routes, DNS servers, `privateDnsServerName` and `domains`. Only ACCESS_NETWORK_STATE is needed; the SSID needs a location permission and is not used.
  - The fingerprint is recorded in `files/p2p_networks.txt` when a **user-started** stream reaches FirstFrame on that network.
  - `trusted()` = the current fingerprint is in the set. Prewarm, autoWarm, hedge and prefetch run only on trusted networks, so the office network never qualifies unless the user plays there.

### 7.6 Hedged start (pure `HedgePolicy`)
- Candidates: `SourceState.alternatesFor(playback)` from `searchCache[key]`. TORRENT kind, a different infoHash, the same resolution or one step lower, fits `rankPrefs`, top 2. Each carries its own `fileIdx` and filename.
- Hedging runs only when `NetworkGate.trusted()`; otherwise a single candidate runs.
- At most **one** extra live candidate:
  - t = 8 s with no metadata (or 0 peers at t = 5 s) → add #1 with `set_max_connections(40 phone / 20 TV)`;
  - t = 20 s with neither having metadata → abandon #1 and add #2;
  - per-candidate cap 45 s;
  - the first with metadata wins, and gets `set_max_connections(-1)` in prepare.
- Losers are abandoned (7.2): removed if created and empty.
- `StreamInfo.candidateIndex` lets PlayerActivity write the winning source into history.
- With no alternates, a single candidate runs with the 45 s / 90 s metadata rule.

---

## 8. Bandwidth governance and session settings

### 8.1 GovernorPolicy (pure, every 2 s on reel-engine)

Modes:
- **IDLE:** no ACTIVE stream for ≥ 5 s.
- **GUARD:**
  - TV (`formFactor`): always while any stream is ACTIVE;
  - phone: any stream in STARTUP, blocked > 500 ms, or `aheadSec < 30`.
- **SHARE** (phone only): every stream has `aheadSec ≥ 90` or is complete, no block for 20 s, and ≥ 20 s since the last flip.

GUARD and SHARE act only on **DOWNLOAD-mode torrents that hold no Starting, Player, External, Prewarm or Grace claim**. Claimed and prewarm torrents have their own limits.

| Mode | Other DOWNLOAD torrents | Seeds | Session knobs |
|---|---|---|---|
| IDLE | for each `pausedForStream`: `setDownloadLimit(0)`, `setUploadLimit(0)`; if `!userPaused`, `setFlags(AUTO_MANAGED)` + `resume()`; clear the flag; clear `userOverride` | seeding policy | phone `upload_rate_limit` 1 MiB/s on Wi-Fi (32 KiB/s metered), unchoke 8; TV 256 KiB/s, unchoke 4 |
| GUARD | each downloading or seeding unclaimed DOWNLOAD torrent that is not userPaused: if `userOverride` → stay running with `setDownloadLimit(64 KiB/s)` on the TV (phone: 128 KiB/s); else `meta.pausedForStream = true` (persisted), `unsetFlags(AUTO_MANAGED)`, `pause()`, snapshot `notice = "Paused while you watch"` | paused the same way | phone upload 512 KiB/s on Wi-Fi (32 KiB/s metered), unchoke 4; TV 128 KiB/s, unchoke 2 |
| SHARE (phone) | resume the `pausedForStream` ones with `setDownloadLimit(clamp((downEwma5s − 1.5·ΣB − 256 KiB)/n, 64 KiB, 4 MiB))` and `setUploadLimit(32 KiB)`; rewrite only when the value changes by > 25 % | stay paused | as GUARD |

- `userOverride`: a user `resume()` during GUARD sets it (in memory). It is cleared in IDLE.
- Explicit pauses avoid the `active_downloads = 0` traps: it would pause the streamed DOWNLOAD torrent, and the `dont_count_slow_torrents` loophole lets slow torrents run.
- A limit of 0 means unlimited, so every intended cap is ≥ 1 KiB. `SessionTuning` tests enforce this.

**Seeding policy** (on TORRENT_FINISHED and every 60 s):
- Applies only to DOWNLOAD-mode torrents with no Starting, Player, External or Prewarm claim. TORRENT_FINISHED for a claimed STREAM torrent is ignored (logged as `ev=finishedWhileClaimed`).
- TV: stop at finish.
- Phone on metered: stop.
- Phone on unmetered: stop at `allTimeUpload() ≥ totalWanted()` or `seedingDuration() ≥ 3600`.
- Stop = `unsetFlags(AUTO_MANAGED)`, `pause()`, `meta.seedingDone = true`.
- The notification shows "Sharing · ↑x/s".

**Free-space guard** (pure `SpaceGuard`; on METADATA_RECEIVED for DOWNLOAD, on every `resume()`, and every 10 s on reel-io through StatFs of the save path):
- `freeEff = free + reclaimableCacheBytes()`; reserve TV 3 GiB, phone 2 GiB.
- If `need > free − reserve` but `need ≤ freeEff − reserve` → run CachePolicy make-room for `need + reserve − free`, then re-check.
- If `need > freeEff − reserve`:
  - `pausedForSpace = true`, unset AUTO_MANAGED, pause;
  - `notice` (also mirrored in `error`) = "Needs X GB, Y GB free".
- Recovery every 30 s when `freeEff` allows, with make-room first.
- `resume()`, `retry()` and `remove()` clear the error and call `swig().clear_error()`.

**Metered networks:**
- the stream is windowed (7.4);
- `upload_rate_limit` is 32 KiB/s in every mode (still ≥ 1 KiB, keeping tit-for-tat alive);
- no grace, prewarm or seeding.

`metered` is refreshed on the network callback, and the allowance and governor are re-run.

**Queue:**
- `TorrentSnapshot.queued = PAUSED && AUTO_MANAGED && !userPaused`, plus `queuePosition`.
- `resume()` on a queued torrent → `queuePositionTop()`.
- `startNow(hash)` → `queuePositionTop()` + resume.
- Phone only: `inactive_down_rate` / `inactive_up_rate` = 32 KiB/s (TV keeps the defaults; caveat).

**Locks and network** (EngineService; implemented only after request R6 is granted, with `quit()` / reel-quit / killProcess unchanged):
- `PARTIAL_WAKE_LOCK "reel:engine"`, `setReferenceCounted(false)`, `acquire(15 min)` re-armed by the evaluation.
  - Held while a DOWNLOAD torrent moved bytes in the last 60 s, or a stream is BACKGROUND or External with readers.
  - Released after 30 s idle, after 10 min at zero rate, and in `quit()`/`onDestroy`.
  - Never held for seeding only.
- `WIFI_MODE_FULL_HIGH_PERF` only on API < 34, and only while the wake lock is held on Wi-Fi.
- `MulticastLock` while any torrent is active on Wi-Fi (LSD).
- `ConnectivityManager.registerDefaultNetworkCallback`: on a network change, debounce 2 s, then `session.reopenNetworkSockets()`, refresh `metered` and the NetworkGate fingerprint.

Until R6 is granted, the network callback is registered from `TorrentEngine` (application context), and the locks are not taken.

### 8.2 Session settings (`SessionTuning.pack(formFactor, disk)`, applied in params before start; later changes go through `applySettings` with delta packs)

| Setting | Axis | PHONE | TV |
|---|---|---|---|
| alert_mask (re-applied in `onAfterStart`) | — | `alert.error_notification or status_notification or storage_notification or piece_progress_notification or performance_warning` (`.to_int()`) | same |
| alert_queue_size | — | 4000 | 4000 |
| announce_to_all_trackers / announce_to_all_tiers | — | true / true | true / true |
| max_metadata_size | formFactor | 30 MiB | 16 MiB |
| connections_limit | formFactor | 200 | 100 |
| connection_speed | formFactor | 60 | 40 |
| torrent_connect_boost (uint8) | formFactor | 80 | 40 |
| smooth_connects | disk | false (MMAP) | true (POSIX) |
| peer_connect_timeout | formFactor | 7 | 8 |
| handshake_timeout | — | 10 | 10 |
| min_reconnect_time | — | 20 | 20 |
| piece_timeout / request_timeout | disk | 15 / 30 (MMAP) | 15 / 40 (POSIX) |
| request_queue_time | — | 2 | 2 |
| max_queued_disk_bytes | disk | 8 MiB (MMAP) | default 1 MiB (POSIX) |
| unchoke_slots_limit | formFactor | governor (8 / 4) | governor (4 / 2) |
| upload_rate_limit | formFactor + metered | governor | governor |
| active_downloads / active_seeds / active_limit | formFactor | 3 / 2 (0 metered) / 10 | 2 / 0 / 4 |
| inactive_down_rate / inactive_up_rate | formFactor | 32768 / 32768 | default |
| close_redundant_connections | dynamic | false while any stream/prewarm claim exists, else default (true) | same |
| dht_announce_interval | — | 300 | 300 |
| stop_tracker_timeout | — | 2 | 2 |
| max_peerlist_size / max_paused_peerlist_size | formFactor | default | 2000 / 500 |
| enable_dht / lsd / upnp / natpmp | — | true | true |
| disk backend | disk | default (mmap) | `setPosixDiskIO()` (invariant; the armv7 .so only has posix) |

`SessionTuningTest` covers all four (formFactor × disk) combinations.

Left at the defaults on purpose: `mixed_mode_algorithm`, `auto_sequential`, `strict_end_game_mode`, `whole_pieces_threshold`, `aio_threads`, `hashing_threads`, `dht_upload_rate_limit`, `suggest_mode`. These names are removed in 2.x and must not be used: `cache_size`, `request_queue_size`.

- Per-torrent `swig().set_max_connections`: prewarm and hedge candidates 40 phone / 20 TV, lifted to −1 (unlimited) when consumed by `startStream` or on a hedge win. RestorePolicy also resets it (9.2).
- Trackers: `DEFAULT_TRACKERS` is added for `.torrent` files only when `!TorrentInfo.isPrivate()`.

### 8.3 Download rules (addendum 2026-10-22, roadmap A engine items)

Per-device settings in the engine-owned `engine/DownloadRulesStore` (SharedPreferences `download_rules`):
`DownloadRules(night = NightWindow(enabled = false, startHour = 1, endHour = 7), watchCap = WatchCap.AUTO)`.
Pure logic in `core/stream/DownloadRules.kt` (`NightRule`, `WatchGovernor`); tests `DownloadRulesTest`.

**A. Overnight window (`NightRule`).**
- In window: local minute-of-day in `[start, end)`, wrapping midnight when `start > end`; `start == end` = all day.
- *Held* = DOWNLOAD mode ∧ window on ∧ outside it ∧ `!meta.startNow` ∧ not finished ∧ no Starting / Player claim.
  STREAM torrents are never held. It is an extra condition next to the home-disk P2P rule (`downloadHold`), never a replacement.
- Hold: `unsetFlags(AUTO_MANAGED)`, `pause()`, `meta.pausedForNight = true` (persisted; not `userPaused`). A user-paused,
  space-paused or seeding-done torrent is left as it is.
- Release (inside the window, or the rule turned off): clear `pausedForNight`; if `userPaused` stay paused; else if
  `downloadHold(key)` stay paused (P2pGate resumes it on an allowed network); else `AUTO_MANAGED` + `resume()` (the queue decides).
- `resume()` on a held torrent (the user's Resume, P2pGate's release) moves no traffic: it stays paused with `pausedForNight`.
- `startNow(key)`: `meta.startNow = true` for the torrent's life, `queuePositionTop()` + resume, unless the P2P rule holds it.
- A claimed (streamed) download is skipped while claimed; after `stopStream` the rule is re-run, so it goes back to waiting.
- Runs on reel-engine: after restore, every 30 s, on a settings change, after `add` / `stopStream` / Keep, and at the
  window edges through `AlarmManager.setAndAllowWhileIdle` → `EngineService` (`ACTION_RULES`). Inexact: Doze may delay an edge by minutes.
- Restart: `RestoreMeta.nightHold` (the rule, evaluated at restore without the finished check) → paused, not auto-managed;
  `pausedForNight && !nightHold` → back to the queue (like `pausedForStream`).
- `PARTIAL_WAKE_LOCK "reel:night"` (narrow part of R6): only while the window is on and open and an unclaimed DOWNLOAD
  torrent is fetching; 10 min timeout re-armed by each run; released otherwise and in `shutdown()`.
- Snapshot: `waitingForNight`, `notice = "Waiting for night (01:00)"`. The notification says "N waiting for night (01:00)" when nothing else runs.
- ReelStats: `ev=night state=hold|release n=… win=01-07`, `ev=nightStartNow key=…`.

**B. Speed cap while watching (`WatchGovernor`).** This is the governor of 8.1 for now, with caps instead of the
GUARD / SHARE pauses on both devices (the user picks the cap); `EngineFlags.govern = 0` turns it off.
- Watching = some stream not BACKGROUND and (in STARTUP, or in-app `signals.fresh && playing`, or an HTTP reader open,
  or HTTP activity in the last 10 s).
- Capped = DOWNLOAD torrents with no Starting / Player claim that are running (DOWNLOADING, METADATA, FINISHED, SEEDING).
- Total download cap: `K500` 500 KiB/s, `M1` 1 MiB/s, `M2` 2 MiB/s, `AUTO` = max(64 KiB/s, 25 % of `linkPeak`), where
  `linkPeak` = the highest session payload download rate (sum over torrents) in the last 60 s of 2 s samples.
  Per torrent = max(16 KiB/s, total / n fetching). Upload per capped torrent 32 KiB/s. Every cap ≥ 1 KiB (0 = unlimited).
  A per-torrent value is rewritten only when it changes by more than 25 %.
- Lift: not watching for ≥ 5 s (pause, stop, background) → `setDownloadLimit(0)` + `setUploadLimit(0)` on each capped
  torrent; a capped torrent that becomes claimed is lifted at once. `OFF` = no caps. Restore already resets limits (9.2).
- Snapshot notice "Slowed while you watch". ReelStats `ev=gov mode=cap|adjust|lift choice=… cap=… per=… up=… n=… link=…`.

**C. Stream-cache limit.** `CachePolicy.budget(formFactor, free, cache, limit)`: `limit` (from the data session's
`AutoDownloadSettings.cacheLimitGb`, null = automatic) replaces the automatic size but still leaves the reserve
(phone 2 GiB, TV 1.5 GiB free): `min(limit, free + cache − reserve)`. Eviction is unchanged (LRU, the two most recently
stopped protected unless free < hardFloor, claimed never). The engine trims again when the limit changes.

---

## 9. Persistence

### 9.1 Files

| File | Writer | When |
|---|---|---|
| `torrents/<key>.json` | reel-io, `android.util.AtomicFile` | every meta change |
| `torrents/<key>.resume` | reel-io, AtomicFile, serialised per key; checks the key is still registered at write time | SAVE_RESUME_DATA |
| `torrents/session.dht` | engine: `session.swig().session_state(SessionHandle.SAVE_DHT_STATE)` → `session_params.write_session_params_buf(p, SessionHandle.SAVE_DHT_STATE)`; reel-io: AtomicFile | every 10 min, at the first stop of each stream, at shutdown |
| `files/p2p_networks.txt` | reel-io, AtomicFile | when a user stream reaches FirstFrame on a new network |
| `files/engine_flags.properties` | the bench broadcast (R1) | on demand |

- **SAVE_RESUME_DATA:** on the alert thread, `bytes = AddTorrentParams.writeResumeDataBuf(alert.params())` is copied out synchronously and posted to reel-io.
- **Periodic resume saves:** every 60 s (TV 30 s), `saveResumeData(SAVE_INFO_DICT.or_(ONLY_IF_MODIFIED))` for entries whose STATE_UPDATE `needSaveResumeData()` is non-zero.
- **Start:**
  1. `runCatching { SessionParams(bytes) }.getOrNull() ?: SessionParams()`;
  2. `setSettings(SessionTuning.pack(formFactor, disk))`;
  3. `setPosixDiskIO()` when disk = POSIX;
  4. `ReelSession.start(params)`.

### 9.2 RestorePolicy (pure) and restore order
```
restoreFlags(saved, meta):
  DOWNLOAD: f = saved − SEQUENTIAL_DOWNLOAD
     userPaused || seedingDone || pausedForSpace          → f − AUTO_MANAGED + PAUSED
     pausedForStream                                       → f + AUTO_MANAGED − PAUSED    // the stream died with the process
     wasAutoManaged != null                                → (wasAutoManaged ? f + AUTO_MANAGED − PAUSED : f − AUTO_MANAGED)
     saved has AUTO_MANAGED                                → f − PAUSED                   // queue-paused comes back queued
     else                                                  → f (kept verbatim)
     then: pausedForStream := false; wasAutoManaged := null
  STREAM:   f = saved − AUTO_MANAGED − SEQUENTIAL_DOWNLOAD + PAUSED
params: setDownloadLimit(0), setUploadLimit(0), setMaxConnections(-1),
        swig().set_piece_priorities(byte_vector())        // libtorrent persists limits, connection caps and residue
```

The branches are evaluated top to bottom; the first match wins.

Restore order:
1. Run on reel-engine (not main), metas sorted by `addedAt`.
2. **Migrate keys.** When the canonical key from the resume `getInfoHashes()` (3.3 rule) differs from the file name:
   - rename both files atomically;
   - rewrite `meta.infoHash` to the new key;
   - set `meta.savePath` from the resume `AddTorrentParams.getSavePath()` when absent. The old `streams/<oldKey>` directory is kept as the save path, never renamed.
3. If the resume data carries an info dict, create the entry's PieceGate. Then add synchronously with `SessionHandle(session.swig()).addTorrent(atp, ErrorCode)` and cache the handle.
4. A missing or corrupt `.resume` re-adds `magnet:?xt=urn:btih:<key>` with `meta.savePath`. STREAM gets `DEFAULT_DONT_DOWNLOAD`; DOWNLOAD gets its explicit file priorities once metadata arrives (`pendingFileIntent`). Any failure here marks `restoreHadFailure`, which disables the orphan sweep.
5. Seed each entry's snapshot once with `status()`.
6. Complete `ready`.

### 9.3 Shutdown (on "reel-quit"; invariant)
1. Post to engine:
   1. `session.pause()`;
   2. compute `keys` = entries that need a save;
   3. publish `pendingFinal = ConcurrentHashMap.newKeySet<String>().apply { addAll(keys) }` and `latch = CountDownLatch(keys.size)`;
   4. **then** issue `saveResumeData(SAVE_INFO_DICT.or_(FLUSH_DISK_CACHE))` for each key.
2. The reel-io writer and the SAVE_RESUME_DATA_FAILED path call `latch.countDown()` only if `pendingFinal.remove(key)` returns true. This includes a write skipped because the key is no longer registered. Unrelated in-flight periodic saves never count down. Wait at most 5 s.
3. Save the DHT state, drain reel-io for at most 2 s, then `session.stop()`.

EngineService then runs `stopSelf` and `killProcess` as today.

**Idle stop and warm-up (TV, 0.12.1; `engine/EngineIdle.kt`, rule in core `launcher/StopEngine`).** On the TV, once
the engine exists, a check every 30 s asks `StopEngine.decide` (no One Stream screen started, nothing streaming, no
download transferring or waiting for the night window). After `StopEngine.IDLE_STOP_MS` (10 min) of uninterrupted
"nothing in use" it runs the shutdown above, stops EngineService and ends the process, like One Launcher's
`stop_engine`. Why: an idle engine kept the whole process (~82 MB) pinned by the foreground service on a 2 GB TV.
One Launcher's TV title page calls `warm_engine` (starts the engine off the main thread), so Play still finds it
running: measured cold start 1.1 s (process ~0.7 s + engine ~0.4 s), warm 0. The phone is unchanged.

### 9.4 Keys and alert routing
- The key is computed on the alert thread from the handle itself, with no id cache:
  - `val ih = InfoHash(h.swig().info_hashes())` (a direct field read);
  - `key = TorrentKey.canonical(if (ih.hasV1()) ih.v1.toHex() else null, ih.best.toHex())`.

  `torrent_handle::id()` is the torrent object's address and can be reused after a remove, so it is never used for routing.
- Alerts whose handle is invalid (`!h.isValid`) are ignored, except TORRENT_REMOVED, which uses `TorrentRemovedAlert.getInfoHashes()`.
- An alert for a key with no registered entry is dropped. PIECE_FINISHED for a registered entry without a gate goes to the pre-gate queue (5.1).
- `addTorrentFile` keys by `InfoHash(info.swig().info_hashes())` with the same rule.
- A magnet keeps its v1 btih key.

---

## 10. Threading model

| Thread | Calls libtorrent | Blocks? | Owns | Never |
|---|---|---|---|---|
| main | no | no | UI | JNI, file IO, engine locks; every engine call is a post or `suspend` (except `trimCache` off main: bounded 2 s wait, 3.1) |
| `reel-engine` (`Executors.newSingleThreadScheduledExecutor`, daemon, priority NORM+1, `asCoroutineDispatcher()`) | **all** mutations (async) plus bounded sync calls: `status(QUERY_PIECES)` at gate creation/recheck/move/ALERTS_DROPPED/TORRENT_CHECKED, `status(true)` in the metadata wait, `torrentFile()`, `filePriorities()` fallback, `getDownloadQueue()` flush polls (MMAP, blocked piece only), `havePiece()` safety net (blocked ≥ 2 s), `max_connections()` once per prepare, `session_state()`, sync `addTorrent` at restore | at most ~50 ms per task; uses `schedule`, never `sleep` | Registry, LeaseTable, controllers, EntryPlanners and AppliedState, governor, cache decisions | file IO, deletes |
| libtorrent4j alert thread | getters only: `h.isValid`, `h.swig().info_hashes()`, alert accessors | no | — | sync JNI round trips, file IO, locks other than a gate signal |
| `reel-io` (single thread) | no | yes (file IO) | AtomicFile writes, deletes, StatFs, probe reads, keep copies | engine state (it posts results back) |
| ExoPlayer loader / NanoHTTPD threads / bench | no | yes (`PieceGate.await`, interruptible) | their `StreamReaderCore` and its RandomAccessFile | JNI |
| ExoPlayer playback thread | no | — | — | engine calls of any kind |
| `reel-quit` | through posts to engine | yes (bounded) | shutdown | |

Locks and state sharing:
- `streamLock` and every `synchronized(stream)` are removed.
- Each `PieceGate` has one `ReentrantLock` with one `Condition`. No lost wake-up:
  - the alert thread sets the bit atomically and then signals under the lock;
  - `markFlushed`, `fileReady` changes and controller closes also signal under the lock;
  - waiters re-check `ready() && isReadable(p)` under the lock.
- Maps read cross-thread (`streams`, `metas`, snapshots, gates, pre-gate queues) are `ConcurrentHashMap`s mutated only on reel-engine. The alert thread may add to a pre-gate queue.
- `openReader` registers the reader synchronously in the controller's `ConcurrentHashMap` (so `hasReaders` is exact) and posts OPEN.
- **Kick conflation**, per EntryPlanner:
  - an `AtomicBoolean pending`;
  - a replan task begins with `pending.getAndSet(false)` **before** sampling readers, signals or the have-set;
  - immediate kinds (OPEN, BLOCKED, SEEK, Handoff, supersede) `execute()` a task directly;
  - a throttled kick inside the window `schedule()`s exactly one task at the window end (never dropped);
  - CLOSE schedules the ghost kick at `ghostCloseMs`.

**SnapshotReducer tick (1 s, no JNI):**
- advances `stalledForSec` for entries in DOWNLOADING whose last-known payload rate is 0, and resets it on any non-zero rate;
- re-emits the snapshot list when a counter crosses 30/60/120 s, or when STATE_UPDATE changed anything.

DownloadsScreen's "No one is sharing" warning (≥ 120 s) keeps working.

Alert diet (`ReelSession : SessionManager(false)`):
- `override fun onAfterStart() { applySettings(SettingsPack().setInteger(settings_pack.int_types.alert_mask.swigValue(), MASK)) }`.
- `override fun postTorrentUpdates() { swig()?.post_torrent_updates(status_flags_t()) }`. No pieces bitfield; name and save path are cached in meta.
- `override fun postSessionStats() { if (EngineFlags.verboseStats) super.postSessionStats() }`.
- Listener `types()`: PIECE_FINISHED, METADATA_RECEIVED, METADATA_FAILED, ADD_TORRENT, FILE_PRIO, FILE_ERROR, TORRENT_ERROR, TORRENT_CHECKED, TORRENT_REMOVED, SAVE_RESUME_DATA, SAVE_RESUME_DATA_FAILED, TORRENT_FINISHED, TORRENT_PAUSED, STORAGE_MOVED, STORAGE_MOVED_FAILED, HASH_FAILED, ALERTS_DROPPED, STATE_UPDATE, PERFORMANCE.
- Alert objects are never stored. Values are copied inside `alert()`.

---

## 11. Instrumentation

### 11.1 ReelStats (logcat tag `ReelStats`, one key=value line per event, aggregated by pure `StreamStats`)

`t` = ms since `hints.tapElapsedMs`. PlayerActivity records it in `onCreate`, or from the tap extra when provided, so `ttff` includes activity launch and the subtitle wait.

```
ReelStats ev=start key=… dev=phone|tv disk=mmap|posix build=<phase> flags=inapp1,zone1,grace1 cand=0/2 warm=cold|cached|prewarmed resume=0|1 size=… pl=… pieces=… durHint=… net=trusted|new
ReelStats ev=meta t=… peers=… cached=0|1
ReelStats ev=peer1 t=…
ReelStats ev=prepared t=… mode=full|window ahead=… B=… rateSrc=player|hint|guess conns=…
ReelStats ev=piece1 t=… p=…
ReelStats ev=head t=… / ev=index t=… kind=mkv-cues|mp4-moov|tail|none
ReelStats ev=byte1 t=… pos=…
ReelStats ev=ready t=… ; ev=ttff t=…
ReelStats ev=wait p=… ms=… pl=… fileReady=0|1 flush=0|1      (each reader block > 200 ms)
ReelStats ev=rebuf n=… ms=… pos=… ahead=… down=… peers=…
ReelStats ev=seek from=… to=… left=0|1 ms=…              (seek → next READY)
ReelStats ev=stall state=… ms=… ; ev=gov mode=… paused=… upCap=… ; ev=hedge … ; ev=grace start|end ; ev=perf code=…
ReelStats ev=missedWake p=… ; ev=finishedWhileClaimed key=… ; ev=keep pieces=a/b state=… ; ev=subDropped ; ev=handoff
ReelStats ev=tick peers=… seeds=… conns=… down=… up=… streamDown=… B=… aheadSec=… blockedMs=… deadlines=… ops/s=… jni/s=… alerts/s=… tcEmpty=… flushWaits=… guard=… heapMb=… freeMb=…   (every 5 s while ACTIVE)
ReelStats ev=end ttm=… ttfp=… ttff=… rebuf=n/Σms/max seeks=n p50=… p90=… waits=n/max fatalSlow=0 missedWake=0 avgDown=… peakPeers=… bytes=… govPaused=… grace=…
```
- **Counters:** sync JNI calls are counted in a wrapper that every sync call goes through, including flush polls and havePiece. Alerts are counted in the listener.
- **tcEmpty:** the number of SetDeadline ops issued while `applied.dueAt` was empty.
- **heapMb:** `Runtime.totalMemory() − freeMemory()`.

### 11.2 EngineFlags (read at engine start from `files/engine_flags.properties`; written by R1)
- `inapp=1`: 0 makes the in-app player use the legacy HTTP loopback. This is a **diagnostic mode** kept for one release: it uses a `DefaultHttpDataSource` with a 60 s read timeout and the default retry policy, and it is not expected to meet `fatalSlow = 0`.
- `zone=1`: 0 falls back to the interim planner.
- `seq=1`, `grace=1`, `govern=1`, `prewarm=1`, `autoWarm=0`, `hedge=1`, `p2pNetworkGate=1`, `verboseStats=0`.

### 11.3 Bench command (at home only, never on the office network)
`TorrentEngine.bench(req, log)` (engine/StreamBench.kt) runs headless:
1. `startStream(StreamRequest(listOf(cand), StreamHints(startMs, durationMs)))`.
2. Opens a BENCH reader at 0, reads 2 MiB (header), then the 4 MiB tail (simulating the extractor), then reopens at the resume byte.
3. Consumes at B for `seconds` against a synthetic PlayerSignals: position advances in real time; buffered = position + min(50 s, lead).
4. Performs each seek in `seeksMs` by closing and reopening at the matching byte. Logs `ev=bench-seek ms=…`.
5. Emits the ReelStats lines plus an `ev=end` summary, then `stopStream`.

At start and every 10 s, if `ActivityManager.RunningAppProcessInfo.importance` is not `IMPORTANCE_FOREGROUND`, the bench logs `ev=bench-warn bg=1`. Results with that line are discarded.

Requested hook in SourcesProbe (R1):
```
adb shell am broadcast -n com.kirtanya.reel/.data.SourcesProbe --ez engineBench true --es magnet '<magnet>' [--ei file 3] [--el startMs 0] [--el durationMs 5400000] [--es seeks 600000,1800000] [--ei seconds 120]
adb shell am broadcast -n com.kirtanya.reel/.data.SourcesProbe --es engineFlags 'zone=0,inapp=1'
adb shell am broadcast -n com.kirtanya.reel/.data.SourcesProbe --ez engineDump true
adb logcat -s ReelStats ReelProbe
```
The real-player TTFF uses the existing `--ez playTest true` extended with `--es magnet <m> [--ei file]` (R1). It builds a TORRENT `Source` and launches PlayerActivity.

**Field protocol:**
- Run with the Reel app **in the foreground** on screen (open it first, then send the broadcast); this avoids the Android 12+ freezer and blocked service starts.
- Matrix: phone and TV × {cold magnet, warm resume, cold resume, 10 seeks, next episode same pack, next episode other torrent} × 3 titles.
- Compare the medians and p90 of the `ev=end` lines between the Phase 0 APK and each phase APK.

---

## 12. Phased plan (each phase ships an APK; JVM tests in `core/src/test`)

**Phase 0: baseline instrumentation (no behaviour change).**
- `StreamStats` (core); `EngineStats`.
- ReelStats lines on the current path: startStream, METADATA, first PIECE_FINISHED, server first byte.
- `ReelAnalyticsListener` in PlayerActivity; tap time from `onCreate`; `debugDump()`; EngineFlags reader; file request R1.
- Tests: `StreamStatsTest`, covering TTFF from tap, rebuffers that exclude seek-induced buffering, seek latency p50/p90, line format.
- Device: record the baseline (field protocol).

**Phase 1: session and correctness quick wins (low risk, large gains).**
- `Units.kt`; StreamTest imports `MB` from it.
- `ReelSession`: mask in `onAfterStart`, explicit `types()`, narrow `postTorrentUpdates`, stats off.
- `SessionTuning` table over (formFactor, disk): announce-all, max_metadata, connect ramp.
- Trackers refresh, plus the private check for `.torrent`.
- `TorrentKey` canonical keys (all-zero v1 → best), info_hashes-based routing, key migration with `infoHash`/`savePath` rewrite.
- Metadata waiter fix.
- `DEFAULT_DONT_DOWNLOAD` (only with a known `fileIdx`) + `filePriorities` at add + abandon in `finally`, **together with** the explicit file priorities for `keep()` / `addMagnet(DOWNLOAD)` on such torrents, `pendingFileIntent`, and the `wantedFiles`/`fullTorrent` meta migration.
- `FileChoice` fallback (Play-now / Downloads wrong file).
- Byte-accurate `FileSpan` + `StreamPlanner` (unaligned head/tail, size 0).
- `trimCache` / `cacheBytes` non-blocking with the `evicting` mark and the off-main 2 s wait (posted to an IO dispatcher for now).
- AtomicFile writes, serialised per key; shutdown latch with `pendingFinal`; DHT state persistence.
- `RestorePolicy`: keep userPaused, explicit pausedForStream branch, reset limits and max connections, clear residue, sort by addedAt, magnet re-add.
- PERFORMANCE alert logging.

Tests:
- `TorrentKeyTest`: v1 preferred, v2-only uses best, v1 = 40 zeros → best, migration rename decision.
- `FileChoiceTest`: fallback order, hint-only, IGNORE-file skip, merge never lowers, fullTorrent stays full, keep with no onlyFile → all DEFAULT, keep before metadata → deferred (`pendingFileIntent`).
- `SessionTuningTest`: four (formFactor × disk) combinations, boost ≤ 255, caps ≥ 1 KiB when intended, TV ≤ phone limits.
- `RestorePolicyTest`: every flag case, including queue-paused, user-paused, seedingDone, pausedForStream → AUTO_MANAGED − PAUSED, wasAutoManaged, setMaxConnections(−1), migrated key keeps the old save dir.
- `FileSpanTest`: offset 15.9 MiB with 16 MiB pieces; size = k·L + 100 KiB; size 0; byteStartOf/byteEndOf/piecesForBytes empty-safe.

Device: `alerts/s` drops by about 95 %; a hybrid torrent plays; a dead first tracker no longer adds 10 s or more to `ttm`; user pauses survive a relaunch; Keep and Download on a stream torrent make progress.

**Phase 2: engine thread and in-process data path (removes every fatal, frozen-seek and zombie-reader path).**
- `EngineThread` (reel-engine, reel-io); every libtorrent call moved onto it; `streamLock` removed.
- `start()` off main + `awaitReady`; STATE_UPDATE snapshots + `SnapshotReducer` tick (pollLoop sync calls deleted).
- Data path:
  - `PieceSet`, `PieceGate` (per entry, `readableRunEnd`, `ready` predicate, signalling `markFlushed`);
  - `StreamReaderCore` (per-stream `closedReason`, `deadReason`, alive-after-read);
  - `TorrentStreamRef`, `StreamReader`;
  - tokens/supersede without closing the gate;
  - gate creation ordering + pre-gate queue + TORRENT_CHECKED re-seed + havePiece safety net;
  - flush gate with backoff;
  - readable gate (`fileReady` + issued/acked counters).
- `TorrentDataSource` + `ReelDataSourceFactory` (listener replay) + `ReelLoadErrorPolicy`.
- Player setup:
  - LoadControl table and back buffer (unit-correct, keyed by formFactor), CLOSEST_SYNC + D-pad reset, wake mode;
  - `onStop` → `stop()` / `onStart` → `prepare()`; `leavingForNext` guard;
  - external handoff (`httpUrlFor`, `exo.stop()`, `Handoff` event, `hasReaders(kind = HTTP)`);
  - error mapping incl. the subtitle drop; conditional subtitle wait.
- LocalStreamServer, engine-owned and external-only: token URL, `closeConnection(true)`, ClientHandler socket binding, socket probe.
- **Interim scheduler:** the byte-accurate `StreamPlanner`, called only on OPEN, CLOSE (ghost) and BLOCKED, applied through one per-entry minimal incremental diff (adds first, resets for departed pieces, no clear). `EngineFlags.inapp=0` diagnostic fallback.

Tests:
- `PieceGateTest`:
  - signal-before-await race (2 threads × 100k iterations);
  - interrupt → InterruptedException within 50 ms;
  - `close` wakes waiters with its reason;
  - `onSlice` abort propagates;
  - flush-pending readability with a fake clock;
  - `markFlushed` wakes a waiter;
  - bits {p, p+1} set with p+1 flush-pending → `readableRunEnd(p)` = p;
  - `ready == false` blocks on a set bit and proceeds after the flag flips + signal;
  - stream A's `closedReason` does not affect stream B waiting on the same gate.
- `StreamReaderCoreTest` (temp files):
  - never reads past the readable run or EOF;
  - extends across consecutive readable pieces;
  - reopen on generation change;
  - superseded throws `StreamGoneException`;
  - `markDead` during a blocked wait throws `StreamGoneException`, not IOException;
  - `longBlock` fires every 2 s while blocked.
- `PieceSetTest`: runEnd/firstMissing/lastMissing across gaps and bounds.
- `TorrentStreamRefTest`; `BackBufferTest`; `SnapshotReducerTest` (stalled counter grows without STATE_UPDATE; re-emit at 120 s).

Device: zero `fatalSlow` on a throttled swarm; seek p90 drops; `jni/s` ≤ 1; `missedWake` = 0; VLC handoff plays and its readers exit within about 1 s of closing VLC.

**Phase 3: zone scheduler and container-aware startup.**
- `SchedulerProfile` (two axes), `MediaRate`, `StartupPlanner`, `ContainerProbe`, `ZoneScheduler`, `PlanMerge`, `PlanDiff`, `EntryPlanner`, `PlanApplier`.
- `PlayerSignals` + `onPlayerEvent`; FILE_PRIO barrier with counters; STARTUP/PLAYING/BACKGROUND/PREWARM phases; horizon-limited deadlines; last-missing sentinel; guard piece; ghost primary; far-seek clear; supersede `forceClear`.
- Baseline vectors at prepare and stop (residue fix); Play-now save/restore; allowance (size check + metered window).
- `close_redundant_connections` delta while claims exist; ignore TORRENT_FINISHED for claimed torrents.
- Delete `StreamPlanner`; rewrite the StreamTest planner tests against `ZoneScheduler`, keeping the ByteRange, FileSpan and VideoFiles tests (they import `Units.MB`).

Tests (numbers fixed in the tests):
- urgent K for 256 KiB / 4 MiB / 16 MiB pieces at 5, 20 and 80 Mbps;
- blocked piece → 0;
- a deadline is set only when d ≤ tcHorizonMs or the piece is blocked; other urgent pieces are 6 with no deadline;
- playhead deadlines: 50 s buffer gives no urgent deadlines (sentinel only), 5 s buffer gives near ones;
- paused floor of 10 s (so only blocked + sentinel carry deadlines);
- deadlines non-decreasing and ≤ maxUrgentPieces + header + index + sentinel + 3;
- sentinel = last missing ≤ aheadEnd, present when the urgent zone is complete, not re-pushed while > maxDeadlineMs/2 remains;
- the steady reader walk never makes `applied.dueAt` empty (property test over random walks);
- normal ticks emit SetDeadline before ResetDeadline;
- a far seek emits exactly one ClearDeadlines;
- CLOSE followed within 300 ms by an OPEN within farSeekBytes emits no ResetDeadline or ClearDeadlines;
- had pieces never appear in ops; no ops when nothing changed;
- pieces exclusive to IGNORE files are 0 in every op, and edge pieces ≥ baseline;
- DOWNLOAD never sets 0 inside the file;
- metered window bounds `aheadEnd` and sets beyond and behind to 0;
- startup order header → index; the resume guess is SIX with no deadline and is dropped after a far open;
- PlanMerge:
  - a full re-apply after prefetch keeps next-file header/tail at 6 and current deadline pieces at 7;
  - supersede emits ClearDeadlines before the new plan;
  - in STARTUP, window and prewarm (both levels), a merged vector always has ≥ 1 missing piece with priority > 0 until `guardMaxBytes` is spent;
  - guard re-pick respects `guardRepickMs`;
- primary selection: an HTTP reader becomes primary once the IN_APP reader closes or its signals go stale;
- `ContainerProbeTest` with synthetic fixtures: faststart MP4, moov-at-end, largesize mdat, fMP4, MKV SeekHead→Cues, front Cues, no SeekHead, a 12 MiB attachment before the first Cluster (Need step), TS and AVI rules;
- `MediaRateTest`.

Device: `rebuf` → 0 on good swarms; `ttff` and `seek` against the targets; `tcEmpty` ≈ number of seeks; one log line verifies `setSequentialRange` set the flag; the frontier debug dump shows no prio-7 holes at aP.

**Phase 4: lifecycle and storage.**
- `LeaseTable` (External activity counts blocked readers), grace (flag), TV single stream, season-pack supersede.
- `CachePolicy` (budgets, protection, hard floor, make-room, allowance without inverted clamps, `maxStreamableBytes`), orphan sweep by canonical save paths.
- Keep via own rename/copy including the part file + `RESET_SAVE_PATH_UNCHECKED`.
- `StallPolicy` + `streamStatus`.
- Best-effort ENOSPC recovery (`clear_error`, make room, `resume`); `retry()`.
- `rankPrefs` reclaimable space in SourceState (TV only).

Tests:
- `LeaseTableTest`: every transition; metered → no grace; owner semantics; a finishing player never pauses a torrent another token is starting; an External claim with a blocked open reader for 5 min stays ACTIVE; external idle with no readers → BACKGROUND.
- `CachePolicyTest`:
  - budgets per formFactor;
  - protection versus the hard floor;
  - explicit clear ignores protection but not claims;
  - allowance with allowance −1 GiB, 100 MiB and 300 MiB (no exception; STORAGE_FULL vs fits);
  - need > free + reclaimable − hardFloor → Failed with the size message;
  - metered → window `max(B·300 s, 64 MiB)`;
  - the rankPrefs and prepare checks agree.
- `StallPolicyTest`.

**Phase 5: bandwidth and downloads (EngineService parts need R6).**
- `GovernorPolicy` + Governor (persisted pausedForStream, notice, userOverride, claimed-torrent exemption, hysteresis, upload caps incl. metered 32 KiB/s, seeding policy for unclaimed DOWNLOAD only).
- Queue (`resume` on queued → top; `startNow`; snapshot `queued`); userPaused; wantedFiles merge; `SpaceGuard` with reclaimable cache.
- Wake, Wi-Fi and multicast locks; network callback `reopenNetworkSockets` + metered + fingerprint; "Sharing" notification.
- Direct links through OkHttp; HLS probe; Download guard for HLS direct sources in `SourceState.download` (toast).

Tests:
- `GovernorPolicyTest`:
  - TV always GUARD while streaming;
  - phone hysteresis (30/90 s, 20 s minimum dwell);
  - streamed and Prewarm/Starting/Grace torrents exempt;
  - userOverride → TV 64 KiB/s cap instead of pause;
  - limits never 0 when meant as caps;
  - seeding stop rules ignore claimed STREAM torrents;
  - metered upload 32 KiB/s.
- `SpaceGuardTest`: a cache-full TV resumes the download after eviction; real shortage → pausedForSpace with the size notice.

**Phase 6: prewarm and hedging (flags).**
- `NetworkGate`; `prefetchNext` same torrent (overlay); prewarm of a different torrent for Up next; CONTINUE_AUTO via `onHomeVisible` (flag default off); opt-in SOURCES_BEST.
- `HedgePolicy` + `alternatesFor`; `PrewarmPolicy`; connection cap lifted on consume or win.

Tests:
- `HedgePolicyTest`: 8/20/45 s timeline, one extra at most, the first metadata wins, losers abandoned with remove-if-created-and-empty, no hedge on an untrusted network.
- `PrewarmPolicyTest`: gates (metered, untrusted network, space, live count, TV during STARTUP), TTL, consume on start, autoWarm never from engine ready.

**Phase 7: experiments (flags default off, enabled only after measurement).**
- Responsive block reads.
- StartGate (engine-aware start decision).
- MKV Cues → ByteTimeMap.
- `mixed_mode_algorithm = prefer_tcp`.
- 16 MiB `max_queued_disk_bytes` on phones with 6 GB or more RAM.
- Metadata-only skeleton eviction (`set_have_pieces(bitfield())`, `set_verified_pieces`, `set_unfinished_pieces`), which would make mid-play ENOSPC recoverable.
- Same-mount stream cache (`getExternalFilesDir(null)/streams`).

---

## 13. Explicitly deferred (with reasons)

| Item | Reason |
|---|---|
| Custom libtorrent4j build with a threaded/pread disk backend, or a second RAM-cache engine, for the TV | No release ships it. Decide only if ReelStats shows the TV throughput plateauing. The mitigations ship (governor GUARD, upload cap, unchoke 2, 100 connections, alert diet, own-copy Keep). |
| `read_piece` / `ALERT_WHEN_AVAILABLE` | `ReadPieceAlert` exposes only a native pointer; it would need our own JNI. |
| Responsive (pre-hash) block reads | Needs BLOCK_PROGRESS alerts (the alert flood on the TV) and lets unverified bytes reach the decoder. Phase 7 flag. |
| StartGate, MKV Cues ByteTimeMap | Their gain is unmeasured and the LoadControl delegation is risky. Phase 7 flag. |
| Multi-connection direct downloads and HLS offline download | Feature work outside streaming; DownloadManager's Doze, retry and notification handling would have to be rebuilt. Only the HLS Download guard ships. |
| Remote "best trackers" fetch | Outside-service dependency, an inspected office network, and `data/` belongs to another session. The static list is refreshed. |
| SimpleCache for direct links | TV storage and cache-key collision risk. |
| BOOT_COMPLETED receiver | Manifest not owned; Android 15 rules on starting foreground services from boot need a device check. Downloads resume on the next app open. |
| TvSync direct LAN dial (`connect_peer`) | Needs a port and hash exchange in `core/sync` (another session). MulticastLock ships. |
| Same-mount stream cache relocation | FUSE overhead on some TVs is unmeasured. Phase 7 flag. Keep is safe with the own copy. |
| Metadata-only skeleton eviction (and with it, reliable mid-play ENOSPC recovery) | Moderate complexity; Phase 7. Until then the prepare size check prevents the normal case. |
| SSID-based trusted networks | Needs a location permission; the LinkProperties fingerprint is used instead. |
| `dht_upload_rate_limit`, `handshake_timeout` < 10, `prefer_tcp` by default | Skeptic: little value or risk to cellular/MSE peers. |
| Mid-play automatic source switch | Needs a re-prepare with a different file layout. Mid-play stalls offer "Other sources" without being fatal. |
| Snapping the resume position to a keyframe | SeekParameters do not affect the initial position (skeptic). |
| A late-subtitle hook on opening the CC menu | Needs a UI hook. Before-first-frame swap and next-seek attach ship. |
| Bounded NanoHTTPD thread pool | Can deadlock with blocked readers (skeptic). The probe and `closeConnection` fix the leak instead. |

---

## 14. Requests to other sessions (engine defaults work without them)

- **R1, One stream: `data/SourcesProbe.kt`.** Add extras that call engine-owned APIs:
  - `--ez engineBench true` → `app.engine.bench(BenchRequest(magnet, file, filename, startMs, durationMs, seeks, seconds)) { Log.i("ReelProbe", it) }`;
  - `--es engineFlags "k=v,..."` → `EngineFlags.write(context, map)`;
  - `--ez engineDump true` → `Log.i("ReelProbe", app.engine.debugDump())`;
  - `playTest` accepts `--es magnet <m> [--ei file]` and builds a TORRENT `Source`.
- **R2, One stream: acknowledge an invariant refinement.** "A backgrounded player stops downloading its stream-mode torrent" becomes "throttled to ≤ 16 KiB/s for at most 60 s (phone) / 90 s (TV) on unmetered networks, then paused; immediate pause on metered". `EngineFlags.grace = 0` restores the strict rule.
- **R3, One stream (PlaybackSettings + Settings UI):** `val prewarmOnSources: Boolean = false`, with the toggle "Get the best source ready on Wi-Fi". Until then Sources prewarm is off.
- **R4, Cinematic UI: `ui/DownloadsScreen.kt` (optional):**
  - `PlayerActivity.forTorrent(context, t.infoHash, t.name, fileIndex = t.fileIndex)`, optionally with `EXTRA_TAP_MS = SystemClock.elapsedRealtime()`;
  - show `t.notice`;
  - label `t.queued` as "Queued · #${t.queuePosition + 1}" with "Start now" → `engine.startNow(t.infoHash)`;
  - for `TorrentPhase.ERROR`, "Retry" → `engine.retry(t.infoHash)`.

  No new `TorrentPhase` value is added. Today's Resume on a queued torrent already starts it. The stalled warning keeps working with no change (10).
- **R5, One stream: `core/sources/SourceRanker` (optional):** `RankPrefs.unknownSizeFits: Boolean = true`. The TV passes `false`.
- **R6, One stream: claim `EngineService.kt`** for:
  - the wake/Wi-Fi/multicast locks;
  - the network callback;
  - the "Sharing" notification.

  `quit()`, the reel-quit thread, `engine.shutdown()`, `stopSelf` and `killProcess` stay exactly as they are. Until granted, the engine registers its network callback itself and takes no locks.
- **R7, Cinematic UI (optional): Home / Continue-watching row visible** → `app.engine.onHomeVisible()` (signature `fun onHomeVisible()`, a post; safe on main). Optionally put `EXTRA_TAP_MS` on play intents. Without it, autoWarm never runs, which is the default anyway.
- **No request for `SettingsScreen.kt`:** `trimCache` off main waits for the engine decision (3.1), so the following `cacheBytes()` is already correct.
- **No request for `ReelApp.kt`:** `ReelApp.streamServer` is left in place, unused and never constructed.
- **Manifest:** no change needed. WAKE_LOCK, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE and CHANGE_WIFI_MULTICAST_STATE are present.

## 15. Invariants check

| Invariant | Status |
|---|---|
| Owner-checked `stopStream` and the `starting` semantics | Kept: Starting and Player claims carry tokens; checks use `===` on StreamInfo. Supersede invalidates only the old controller, never the shared torrent or gate. |
| suspend/resume behaviour | Refined per R2. |
| `stopWhenIdle` | Kept (External claim, 10 min), decided from external (HTTP) readers only. |
| posix disk I/O on 32-bit | Kept (`disk` axis). |
| Quit path | Kept, with a keyed latch instead of a blind 1.5 s sleep. EngineService edits wait for R6 and leave the quit path untouched. |
| `start()` starts EngineService | Kept. |
| StreamTest | ByteRange, FileSpan and VideoFiles tests kept (now importing `Units.MB`); planner tests rewritten in Phase 3. |
| `Magnet.parse` signature and lowercase v1 hex | Unchanged. |
| No P2P from the office network | Kept, and strengthened by `p2pNetworkGate` and autoWarm off by default. |

---

## 16. Review log

All 41 review issues were checked against the code and the jars. None was rejected outright; 9 were accepted with changes, as noted below.

**Correctness lens**
1. *PieceGate closed on supersede (blocker).* Accepted. The gate is per entry and never closed on supersede. Liveness is per stream (`closedReason`) plus `signalAll` (3.3, 7.2), with a test.
2. *`id()` reuse in routing.* Accepted. `idToKey` was removed; the key comes from `info_hashes()` on the alert thread, and invalid handles are ignored (9.4).
3. *Sequential picker skips prio-7 pieces.* Accepted. Deadlines and 7 are set only within `tcHorizonMs` or for the blocked piece, and the sentinel is the last missing piece ≤ aheadEnd (4.3).
4. *Torrent reaches "finished" while claimed.* Accepted, with one change and one addition.
   - Change: re-picking "the next missing piece" each time it is had is not bounded to one piece; it creeps. The guard is therefore rate-limited (`guardRepickMs`) and capped (`guardMaxBytes`), with a logged, recoverable fallback (4.5a, 7.1).
   - Addition: `DEFAULT_DONT_DOWNLOAD` is now used only with a known `fileIdx`. Otherwise a magnet would be "finished" at the moment metadata arrives (7.2).
5. *Readable gate not in the wait predicate.* Accepted. `fileReady` is part of `await`, set by issued/acked FILE_PRIO counters (4.7, 5.1).
6. *Raw `runEnd` bypasses the flush check.* Accepted. `readableRunEnd`, a signalling `markFlushed`, and a 20/50/100 ms backoff on the blocked piece only (5.1).
7. *Missed PIECE_FINISHED.* Accepted. Gate-first ordering, a pre-gate queue, TORRENT_CHECKED re-seed, and a 2 s `havePiece` safety net, counted as `missedWake` (5.1). The earlier "no `havePiece()` anywhere" rule was dropped.
8. *ThreadLocal set on the accept thread.* Accepted. `ClientHandler.run` binds the socket, and the reader captures it at construction. Verified in the jar: public class, public constructor, non-final `run()` (5.2).
9. *More than one controller drives one handle.* Accepted. One EntryPlanner, PlanApplier and AppliedState per torrent; overlays and the baseline are merged torrent-indexed; supersede forces a clear (4.5a, 4.6).
10. *v2-only key collides on all zeros.* Accepted (3.3).
11. *CLOSE/OPEN churn.* Accepted. A ghost primary for 500 ms (4.6, 4.9).
12. *Kick lost update.* Accepted. `getAndSet` first; throttled kicks are scheduled, never dropped (10).
13. *Shutdown latch race.* Accepted. `pendingFinal` is published before the saves are issued (9.3).
14. *Closing the file from reel-engine.* Accepted. Mark dead + signal; the owner thread closes the file; `alive()` runs after every read (5.1).
15. *RoutingDataSource drops listeners.* Accepted (3.4).
16. *Blocked external reader counted as idle.* Accepted. Open readers, including blocked ones, keep the claim ACTIVE (7.1).

**Device lens**
17. *External handoff (blocker).* Accepted, and verified at PlayerActivity.kt:543 and :549–554.
    - Change: the audit caveat "skip `player.stop()` on the externalHandoff path" is superseded. It was written for the old shared HTTP loopback. In v2 the in-app reader is in-process and would otherwise stay primary. `exo.stop()` runs in onStop only after the chooser actually led away (so a dismissed chooser is unaffected), and onStart does not auto-prepare after a handoff (6.4).
18. *Inverted clamp in `aheadCap`.* Accepted. There is no clamp; there is an explicit failure (7.4).
19. *Window mode does not bound disk use.* Accepted.
    - A size check at prepare, counting reclaimable cache. Window mode is used only as a metered bandwidth limiter.
    - `rankPrefs` stays TV-only (verified at SourceState.kt:67–71) and shares the same function.
    - Change: the check applies on phones too, so the failure is fast and explicit rather than a fatal ENOSPC mid-film.
20. *Device axes.* Accepted. `formFactor` and `disk` (4.1, 8.2).
21. *Keep leaves the part file and triggers a recheck.* Accepted. The part file moves first; `RESET_SAVE_PATH_UNCHECKED` (verified in the MoveFlags class); a device-check log (7.3).
22. *DEFAULT_DONT_DOWNLOAD before the keep fix.* Accepted. Moved into Phase 1. Verified at TorrentEngine.kt:206 and :266–274.
23. *Migration vs orphan sweep.* Accepted. `savePath`/`infoHash` rewrite and a canonical-path sweep, skipped after any restore failure (7.4, 9.2). Verified that `saveDirFor` keys by the old hash.
24. *Connection cap never lifted.* Accepted. `set_max_connections(-1)` at prepare and on a hedge win, plus `setMaxConnections(-1)` in restore (7.2, 9.2).
25. *Prefetch wiped by full re-applies.* Accepted. Merged with issue 9 as torrent-level overlays (4.5a, 7.5).
26. *autoWarm sends traffic from the office network.* Accepted. Off by default, triggered only by `onHomeVisible()`, plus `p2pNetworkGate`.
    - Change: the fingerprint is built from LinkProperties instead of the SSID, because the SSID needs a location permission (7.5).
27. *Free-space guard ignores reclaimable cache.* Accepted (8.1).
28. *Metered data use.* Accepted. A metered window, 32 KiB/s upload, and re-evaluation on network change (7.4, 8.1).
29. *GUARD UX.* Accepted. A notice, `userOverride`, and claim exemptions (8.1).
30. *trimCache read-after-write.* Accepted. Both mechanisms ship: the engine-side `evicting` mark and an off-main bounded wait. Verified at SettingsScreen.kt:505–506 (IO) and DownloadsScreen.kt:117 (main).
31. *stalledForSec stops growing.* Accepted. A no-JNI 1 s SnapshotReducer tick (10). Verified at DownloadsScreen.kt:208 and TorrentEngine.kt:569–574.
32. *Back-buffer units.* Accepted, with a test (6.2).
33. *D-pad leaves PREVIOUS_SYNC set.* Accepted (6.2).
34. *Dead subtitle URL is fatal.* Accepted. A table row, checked first (6.3).
35. *Ownership.* Accepted. R6 for EngineService; the engine owns the server; ReelApp is untouched (2.1, 14). Verified at ReelApp.kt:31 and PlayerActivity.kt:189.
36. *`inapp=0` fallback.* Accepted with a change: documented as a diagnostic mode with a 60 s read timeout. Unbounded retries cannot be limited to the torrent item, because `getMinimumLoadableRetryCount` sees only the data type, which subtitles share (11.2).
37. *pausedForStream restore.* Accepted. An explicit branch, ordered before the verbatim case (9.2).
38. *StreamTest imports `StreamPlanner.MB`.* Accepted. `Units.kt` in Phase 1. Verified at StreamTest.kt:6 and :34.
39. *Instrumentation.* Accepted. Tap time from onCreate or an extra, a foreground-only bench with `bench-warn`, and the rename to `ReelAnalyticsListener` (6.4, 11).

The 41 issues include two more not numbered above: the device-lens items on the TV single-stream and GUARD exemptions for prewarm, merged into item 29, and the External/blocked overlap, merged into item 16. Both were applied as stated.