# API catalog: One Stream streaming stack (taken from the real jars, 2026-10-20)

Every signature below comes from `javap` on the cached jars. **NOT AVAILABLE** marks an API that does not exist or cannot be used. **GOTCHA** marks a behaviour read from the bytecode that will break a reasonable assumption.

**Artifacts**
- `~/.gradle/caches/modules-2/files-2.1/org.libtorrent4j/libtorrent4j/2.1.0-39/499e9a14…/libtorrent4j-2.1.0-39.jar`. The native library reports `libtorrent/2.1.0.0`.
- Native .so files:
  - arm64: `~/.gradle/caches/9.3.1/transforms/3ffa33ea…/arm64-v8a/libtorrent4j.so`
  - armeabi-v7a: `~/.gradle/caches/9.3.1/transforms/7e257da1…/armeabi-v7a/libtorrent4j.so`
- media3 1.11.1 AARs are under `modules-2/files-2.1/androidx.media3/media3-{exoplayer,datasource,datasource-okhttp,common}/1.11.1/`. Their `classes.jar` files are extracted to `/private/tmp/claude-502/-Users-kirtanya-k/7dc59e73-6348-40d5-b02e-db6b5a3f4203/scratchpad/api/m3/*/classes.jar`.
- `modules-2/files-2.1/org.nanohttpd/nanohttpd/2.3.1/a8d54d1c…/nanohttpd-2.3.1.jar`
- Disassembly dumps: `…/scratchpad/api/sm.txt` (SessionManager) and `…/scratchpad/api/th.txt` (TorrentHandle).

---

## 1. libtorrent4j `org.libtorrent4j.*`

### SessionManager
```
SessionManager() / SessionManager(boolean logging)     // no-arg = logging=false
swig.session swig()                                    // raw session → session_handle API (§1.13)
void addListener(AlertListener) / removeListener(AlertListener)
void start() / start(SessionParams) / stop() / restart() / boolean isRunning()
void pause() / resume() / boolean isPaused()
SessionStats stats(); long downloadRate(), uploadRate(), totalDownload(), totalUpload(), dhtNodes()
boolean isFirewalled(); String externalAddress(); List<String> listenEndpoints()
SettingsPack settings()                    // = session.get_settings() (full pack)
void applySettings(SettingsPack)           // EXISTS → session.apply_settings(pack) then onApplySettings(pack). Throws IAE if pack is null; no-op if not started
int downloadRateLimit()/void downloadRateLimit(int); uploadRateLimit idem
int maxActiveDownloads()/void maxActiveDownloads(int); maxActiveSeeds idem
int maxConnections()/void maxConnections(int); int maxPeers()/void maxPeers(int)
String listenInterfaces()/void listenInterfaces(String)
void postSessionStats(); postDhtStats(); postTorrentUpdates()
boolean isDhtRunning(); startDht(); stopDht()
TorrentHandle find(Sha1Hash)               // null if invalid
void download(TorrentInfo, File saveDir, File resumeFile, Priority[], List<TcpEndpoint> peers, swig.torrent_flags_t)
void download(String magnet, File saveDir, swig.torrent_flags_t)   // async_add_torrent; silently RETURNS if already present
void download(TorrentInfo, File)
void remove(TorrentHandle) / remove(TorrentHandle, swig.remove_flags_t)
byte[] fetchMagnet(String, int timeoutSec, File)
void dhtAnnounce(Sha1Hash) / dhtAnnounce(Sha1Hash,int port,byte flags); ArrayList<TcpEndpoint> dhtGetPeers(Sha1Hash,int)
void moveStorage(File)                     // moves ALL torrents
byte[] saveState()                         // EXISTS: write_session_params_buf(session_state()) — all state; null if not running
void reopenNetworkSockets()                // EXISTS: session.reopen_network_sockets()
String magnetPeers(); Throwable lastAlertError()
protected hooks: onBeforeStart(), onAfterStart(), onBeforeStop(), onAfterStop(), onApplySettings(SettingsPack), String defaultDhtBootstrapNodes()
```

**What `start(SessionParams)` does, read from the bytecode, in order:**
1. `onBeforeStart()`, then `resetState()`.
2. It **unconditionally overwrites `alert_mask`** with `alertMask(logging)`. That value is `alert.all_categories & ~(session_log|torrent_log|peer_log|dht_log|port_mapping_log|picker_log)` when logging is false. With logging true it is all categories.
   - **GOTCHA:** `TorrentEngine.sessionParams()` sets a custom `alert_mask` at line 138. That setting is thrown away. The effective mask includes BLOCK_PROGRESS, PEER, UPLOAD and others, which costs CPU on the TV.
   - Fix: after `start()`, call `session.applySettings(SettingsPack().setInteger(int_types.alert_mask.swigValue(), mask))`, or do the same from an overridden `onAfterStart()`.
3. `max_metadata_size` is set to **2 097 152 (2 MiB) only if not already set** (`hasValue`).
4. `dht_bootstrap_nodes` is set to `defaultDhtBootstrapNodes()` only if not already set. The method is overridable.
5. It creates `new swig.session(params)`, then starts the alerts thread `SessionManager-alertsLoop` (a daemon thread).
6. It installs a port filter that blocks peer ports 0–79, 81–442 and 444–1023. After that, `onAfterStart()`.

**The alerts loop (`SessionManager$5`):**
- It calls `wait_for_alert_ms(500)` and then `pop_alerts`.
- Each alert is cast only if some listener's `types()` contains it. SESSION_STATS, STATE_UPDATE and SESSION_STATS_HEADER are special types.
- An exception thrown by a listener is caught, logged as "Error calling alert listener" and stored in `lastAlertError()`.
- **About once a second the loop calls `postSessionStats()` and `postTorrentUpdates()` itself.** A STATE_UPDATE alert therefore arrives roughly every second for free. `post_torrent_updates()` with no arguments means all status flags, so it includes the `pieces` bitfield.
- Alert objects wrap native memory that is only valid until the next `pop_alerts`. Copy values out synchronously inside `alert()` and never keep the alert object.

**`stop()`** posts session stats, sleeps 750 ms, joins the alerts thread and deletes the session. It is blocking. **`restart()`** keeps `session_state()`, calls `stop()`, sleeps 1 s and starts again with the saved state.

### SessionParams
```
SessionParams() / (SettingsPack) / (swig.session_params) / (File) / (byte[] savedState)
SettingsPack getSettings(); void setSettings(SettingsPack)
void setPosixDiskIO()      // set_posix_disk_io_constructor
void setDefaultDiskIO()    // set_default_disk_io_constructor
```
**NOT AVAILABLE:** a `setMmapDiskIO()` method. The default constructor is the only way to get mmap.

**Native fact:** the **armeabi-v7a .so contains only `posix_disk_io`**, with no `mmap_disk_io` symbol. The arm64 .so contains both. On the TV, posix is used whether or not `setPosixDiskIO()` is called.

### SettingsPack (a sparse delta: only keys that are set get applied)
```
SettingsPack() / (swig.settings_pack); static SettingsPack defaultSettings()
boolean getBoolean(int)/SettingsPack setBoolean(int,boolean)
int getInteger(int)/SettingsPack setInteger(int,int)
String getString(int)/SettingsPack setString(int,String); byte[] getBytes(int)/void setBytes(int,byte[])
void clear() / clear(int); boolean hasValue(int)
```
Typed wrappers and the setting each one maps to:

| Wrapper | Setting |
|---|---|
| `downloadRateLimit` | download_rate_limit |
| `uploadRateLimit` | upload_rate_limit |
| `activeDownloads` | active_downloads |
| `activeSeeds` | active_seeds |
| `activeChecking` | active_checking |
| `activeDhtLimit` | active_dht_limit |
| `activeTrackerLimit` | active_tracker_limit |
| `activeLsdLimit` | active_lsd_limit |
| `activeLimit` | active_limit |
| `connectionsLimit` | connections_limit |
| `maxPeerlistSize` | max_peerlist_size |
| `maxQueuedDiskBytes` | max_queued_disk_bytes |
| `sendBufferWatermark` | send_buffer_watermark |
| `tickInterval` | tick_interval |
| `inactivityTimeout` | inactivity_timeout |
| `seedingOutgoingConnections(bool)` | seeding_outgoing_connections |
| `anonymousMode(bool)` | anonymous_mode |
| `isEnableDht`/`setEnableDht` | enable_dht |
| `isEnableLsd`/`setEnableLsd` | enable_lsd |
| `get`/`setMaxMetadataSize` | max_metadata_size |
| `get`/`setDhtBootstrapNodes` | dht_bootstrap_nodes |
| `get`/`setWebtorrentStunServer` | webtorrent_stun_server |
| `listenInterfaces` | listen_interfaces |
| `stopTrackerTimeout` | stop_tracker_timeout |
| `alertQueueSize` | alert_queue_size |
| `getAnnouncePort`/`setAnnouncePort` | announce_port |
| `get`/`setPeerFingerprint` | peer_fingerprint |

All the int-returning wrappers return `SettingsPack` for chaining, except the `set*` void variants. To set a key by name, use `settings_pack.int_types.X.swigValue()` or `settings_pack.bool_types.X.swigValue()`.

### swig `settings_pack` enum names that exist in 2.1.0 (relevant subset)

**bool_types**
- Announce: announce_to_all_tiers, announce_to_all_trackers, prefer_udp_trackers, use_dht_as_fallback, smooth_connects, validate_https_trackers, ssrf_mitigation
- Transports: enable_outgoing_utp, enable_incoming_utp, enable_outgoing_tcp, enable_incoming_tcp, enable_upnp, enable_natpmp, enable_lsd, enable_dht
- DHT: dht_aggressive_lookups, dht_prefer_verified_node_ids, dht_restrict_routing_ips, dht_restrict_search_ips, dht_extended_routing_table, dht_privacy_lookups, dht_enforce_node_id, dht_ignore_dark_internet, dht_read_only
- Picker: prioritize_partial_pieces, strict_end_game_mode, piece_extent_affinity, auto_sequential, use_parole_mode
- Connections: allow_multiple_connections_per_ip, close_redundant_connections, seeding_outgoing_connections, no_connect_privileged_ports, send_redundant_have
- Queueing: auto_manage_prefer_seeds, dont_count_slow_torrents, incoming_starts_queued_torrents
- Disk/other: no_recheck_incomplete_resume, rate_limit_ip_overhead, anonymous_mode, disable_hash_checks, no_atime_storage, enable_set_file_valid_data, disk_disable_copy_on_write, enable_ip_notifier

**int_types**
- Announce/tracker: tracker_completion_timeout, tracker_receive_timeout, stop_tracker_timeout, min_announce_interval, num_want, tracker_backoff, max_concurrent_http_announces, auto_scrape_interval, auto_scrape_min_interval, local_service_announce_interval, dht_announce_interval
- Connections: connections_limit, connections_slack, connection_speed, peer_connect_timeout, torrent_connect_boost, max_peerlist_size, max_paused_peerlist_size, min_reconnect_time, max_failcount, listen_queue_size, peer_turnover, peer_turnover_cutoff, peer_turnover_interval, connect_seed_every_n_download, max_rejects
- Timeouts: piece_timeout, request_timeout, peer_timeout, inactivity_timeout, handshake_timeout, urlseed_timeout, close_file_interval
- Request queue: request_queue_time, max_allowed_in_request_queue, max_out_request_queue, whole_pieces_threshold, initial_picker_threshold, allowed_fast_set_size, predictive_piece_announce, suggest_mode, max_suggest_pieces
- Disk: max_queued_disk_bytes, aio_threads, hashing_threads, checking_mem_usage, file_pool_size, disk_io_write_mode, disk_io_read_mode (both are io_buffer_mode_t), disk_write_mode (mmap_write_mode_t), mmap_file_size_cutoff, optimistic_disk_retry
- Buffers: send_buffer_low_watermark, send_buffer_watermark, send_buffer_watermark_factor, send_not_sent_low_watermark, recv_socket_buffer_size, send_socket_buffer_size, max_peer_recv_buffer_size
- Rate limits: upload_rate_limit, download_rate_limit, dht_upload_rate_limit
- Queueing: active_downloads, active_seeds, active_checking, active_dht_limit, active_tracker_limit, active_lsd_limit, active_limit, auto_manage_interval, auto_manage_startup, inactive_down_rate, inactive_up_rate
- Choking and seeding goals: choking_algorithm, seed_choking_algorithm, unchoke_slots_limit, unchoke_interval, optimistic_unchoke_interval, num_optimistic_unchoke_slots, rate_choker_initial_threshold, seed_time_limit, share_ratio_limit, seed_time_ratio_limit, seeding_piece_quota
- Mixed mode and uTP: mixed_mode_algorithm, utp_target_delay, utp_connect_timeout, utp_min_timeout, utp_syn_resends, utp_num_resends
- DHT: dht_max_peers_reply, dht_search_branching, dht_max_fail_count, dht_max_torrents, dht_max_peers, dht_block_timeout, dht_block_ratelimit, dht_max_torrent_search_reply
- Misc: alert_mask, alert_queue_size, max_metadata_size, tick_interval, metadata_token_limit, max_piece_count, max_pex_peers

**string_types:** user_agent, announce_ip, handshake_client_version, outgoing_interfaces, listen_interfaces, peer_fingerprint, dht_bootstrap_nodes, webtorrent_stun_server

**Sub-enums** (use `.swigValue()`; their numeric values come from native code):
- `choking_algorithm_t`: fixed_slots_choker, rate_based_choker
- `seed_choking_algorithm_t`: round_robin, fastest_upload, anti_leech
- `bandwidth_mixed_algo_t`: prefer_tcp, peer_proportional
- `suggest_mode_t`: no_piece_suggestions, suggest_read_cache
- `mmap_write_mode_t`: always_pwrite, always_mmap_write, auto_mmap_write
- `io_buffer_mode_t`: enable_os_cache, disable_os_cache, write_through

**Removed in 2.x and NOT AVAILABLE:** cache_size, cache_expiry, use_read_cache, request_queue_size, strict_super_seeding, lazy_bitfields.

### TorrentHandle (wrapper)
```
static: OVERWRITE_EXISTING(add_piece_flags_t); QUERY_DISTRIBUTED_COPIES, QUERY_ACCURATE_DOWNLOAD_COUNTERS,
  QUERY_LAST_SEEN_COMPLETE, QUERY_PIECES, QUERY_VERIFIED_PIECES, QUERY_TORRENT_FILE, QUERY_NAME, QUERY_SAVE_PATH (status_flags_t);
  FLUSH_DISK_CACHE, SAVE_INFO_DICT, ONLY_IF_MODIFIED (resume_data_flags_t); IGNORE_MIN_INTERVAL (reannounce_flags_t);
  ALERT_WHEN_AVAILABLE (deadline_flags_t); PIECE_GRANULARITY (file_progress_flags_t)
void addPiece(int, byte[]) / addPiece(int, byte[], add_piece_flags_t)
void readPiece(int)                               // see the read_piece row in the swig section: data is NOT reachable from Java
void setSequentialRange(int first, int last) / setSequentialRange(int first)   // EXISTS (native symbol present on both ABIs)
boolean havePiece(int)
List<PeerInfo> peerInfo(); TorrentInfo torrentFile()  // null if handle invalid or no metadata
TorrentStatus status()                    // = status(false)
TorrentStatus status(boolean force)       // GOTCHA: CACHED for 500 ms per TorrentHandle instance; uses STATUS_FLAGS_ZERO → pieces() EMPTY
TorrentStatus status(swig.status_flags_t) // uncached; pass QUERY_PIECES to get the bitfield
ArrayList<PartialPieceInfo> getDownloadQueue()
Sha1Hash infoHash()
void pause(); resume()
swig.torrent_flags_t getFlags(); setFlags(flags) [ORs in]; setFlags(flags, mask); unsetFlags(flags)
void flushCache(); boolean needSaveResumeData(); saveResumeData(); saveResumeData(resume_data_flags_t)
int queuePosition(); queuePositionUp/Down/Top/Bottom(); queuePositionSet(int)
boolean isValid(); boolean inSession(); String makeMagnetUri(); String savePath(); String getName()
int getUploadLimit()/setUploadLimit(int); getDownloadLimit()/setDownloadLimit(int)
void forceRecheck()
void forceReannounce() / (int seconds) / (int seconds, int trackerIdx) / (int, int, reannounce_flags_t) / (int, String url) / (int, String, reannounce_flags_t)
void forceDHTAnnounce(); forceLSDAnnounce(); scrapeTracker() / (int) / (String)
List<AnnounceEntry> trackers(); replaceTrackers(List<AnnounceEntry>); addTracker(AnnounceEntry)
addUrlSeed(String); removeUrlSeed(String); List<String> urlSeeds()
int[] pieceAvailability()
void piecePriority(int, Priority); Priority piecePriority(int); prioritizePieces(Priority[] fullArray); Priority[] piecePriorities()
void filePriority(int, Priority); Priority filePriority(int); prioritizeFiles(Priority[]); Priority[] filePriorities()
void setPieceDeadline(int piece, int ms) / setPieceDeadline(int, int, swig.deadline_flags_t)
void resetPieceDeadline(int); clearPieceDeadlines()
long[] fileProgress() / fileProgress(file_progress_flags_t)
void moveStorage(String) / moveStorage(String, MoveFlags); renameFile(int, String)
```
- `setSequentialRange` comes from the libtorrent header. Its documentation says: it sets the first and last piece of the range for the picker and starts "downloading from the specified piece in sequential download mode, which is enabled". If the last piece is omitted, the range runs to the end of the file that the first piece belongs to. It does nothing before metadata arrives.
  - The wording suggests it switches sequential mode on. Treat that as **unverified on device**: check `getFlags()` for SEQUENTIAL_DOWNLOAD after calling it.
  - There is no "clear range" call. Unset `SEQUENTIAL_DOWNLOAD` to stop.

### swig `torrent_handle`: extras the wrapper does not expose
Reach these with `(handle.swig() as torrent_handle)`.
```
static flags: graceful_pause (pause_flags_t); if_counters_changed, if_download_progress, if_config_changed,
  if_state_changed, if_metadata_changed (resume_data_flags_t); high_priority (reannounce_flags_t)
void clear_error()                                       // only in swig
void connect_peer(tcp_endpoint) / (tcp_endpoint, peer_source_flags_t) / (tcp_endpoint, peer_source_flags_t, pex_flags_t)
info_hash_t info_hashes()
void set_max_connections(int); int max_connections(); set_max_uploads(int); max_uploads(); clear_peers()
void pause(pause_flags_t)
void post_status(status_flags_t); post_download_queue(); post_peer_info(); post_file_progress(...); post_piece_availability(); post_trackers()
void force_reannounce(int seconds, reannounce_flags_t)   // only in swig
add_torrent_params get_resume_data(resume_data_flags_t)  // synchronous
boolean need_save_resume_data(resume_data_flags_t)
void prioritize_pieces_ex(int_byte_pair_vector)          // sparse (index,prio) update — avoids full-array rewrite
byte piece_priority_ex(int); void piece_priority_ex(int, byte); byte_vector get_piece_priorities_ex()
long id(); boolean eq/ne/lt(torrent_handle)
```
- `read_piece(int)` exists, but **`ReadPieceAlert` exposes only `long bufferPtr()` and `int size()`, a raw native pointer**. No Java helper copies the bytes; the only accessor in the jar is `read_piece_alert.buffer_ptr()`. **read_piece is therefore NOT USABLE from Java without our own JNI.**
  - Alternative: keep reading the payload file on disk after `PieceFinishedAlert` or `havePiece()`.
  - The same applies to `set_piece_deadline(.., alert_when_available)`: the alert carries no reachable data.

### TorrentStatus (wrapper over a snapshot)
```
ErrorCode errorCode(); String name(); String currentTracker(); long nextAnnounce()
long totalDownload/Upload/PayloadDownload/PayloadUpload/FailedBytes/RedundantBytes/Done/Wanted/WantedDone(), total()
PieceIndexBitfield pieces(), verifiedPieces()     // empty unless queried with QUERY_PIECES (or via STATE_UPDATE)
float progress(); int progressPpm()
int downloadRate(), uploadRate(), downloadPayloadRate(), uploadPayloadRate()
int numSeeds(), numPeers(), numComplete(), numIncomplete(), listSeeds(), listPeers(), connectCandidates()
int numPieces(), numConnections(), connectionsLimit(), numUploads(), uploadsLimit(), blockSize()
int upBandwidthQueue(), downBandwidthQueue(), seedRank(), queuePosition(); float distributedCopies()
TorrentStatus.State state(); boolean isSeeding(), isFinished(), hasMetadata(), hasIncoming(), isMovingStorage()
boolean announcingToTrackers(), announcingToLsd(), announcingToDht()
InfoHash getInfoHashes(); torrent_flags_t flags(); resume_data_flags_t needSaveResumeData()
long addedTime(), completedTime(), lastSeenComplete(), lastUpload(), lastDownload(), activeDuration(), finishedDuration(), seedingDuration()
swig.torrent_status swig()     // extra: getError_file(), getSave_path(), getHandle()
```
`State` values: CHECKING_FILES, DOWNLOADING_METADATA, DOWNLOADING, FINISHED, SEEDING, CHECKING_RESUME_DATA, UNKNOWN. Each has `int swig()` and `static fromSwig(int)`.

### TorrentInfo / FileStorage
```
TorrentInfo(File) / (byte[]) / (MappedByteBuffer) / (swig.torrent_info); static TorrentInfo bdecode(byte[])
FileStorage files(); long totalSize(); int pieceLength(); int numPieces(); int pieceSize(int); int numFiles()
Sha1Hash infoHash(); InfoHash infoHashes(); boolean hasV1(), hasV2(), isValid(), isPrivate(); String name()
ArrayList<FileSlice> mapBlock(int piece, long offset, int size); PeerRequest mapFile(int file, long offset, int size)
FileStorage: int numFiles(); long totalSize(); int numPieces(); int pieceLength(); int pieceSize(int)
  String filePath(int) / filePath(int, String savePath); String fileName(int); long fileSize(int); long fileOffset(int)
  boolean padFileAt(int); file_flags_t fileFlags(int); int fileIndexAtOffset(long); int fileIndexAtPiece(int)
  int lastFileIndexAtPiece(int); int pieceIndexAtFile(int); int lastPieceIndexAtFile(int)
  ArrayList<FileSlice> mapBlock(int,long,int); PeerRequest mapFile(int,long,int)
  consts FLAG_PAD_FILE, FLAG_HIDDEN, FLAG_EXECUTABLE, FLAG_SYMLINK
PeerRequest: int piece(), start(), length()
```

### AddTorrentParams
```
AddTorrentParams() / (swig.add_torrent_params); static parseMagnetUri(String); static Entry writeResumeData(atp); static byte[] writeResumeDataBuf(atp)
get/setTorrentInfo, get/setTrackers(List<String>), get/setTrackerTiers(List<Integer>), get/setDhtNodes(List<Pair<String,Integer>>)
get/setName, get/setSavePath, get/setStorageMode, get/setInfoHashes(InfoHash), get/setMaxUploads, get/setMaxConnections
get/setUploadLimit, get/setDownloadLimit, swig.torrent_flags_t getFlags()/setFlags(...)
get/setUrlSeeds, Priority[] filePriorities()/filePriorities(Priority[]), piecePriorities()/piecePriorities(Priority[])
ArrayList<TcpEndpoint> peers()/peers(List<TcpEndpoint>), bannedPeers()/bannedPeers(List)
```
- The swig class adds `setPart_file_dir(String)`, `set_have_pieces(bitfield)`, `set_verified_pieces(bitfield)` and `getUserdata`/`setUserdata`.
- Static helpers in `swig.libtorrent`: `read_resume_data_ex(byte_vector, error_code[, load_torrent_limits])`, `write_resume_data_buf_ex(atp)`, `parse_magnet_uri(String, error_code)`, `load_torrent_buffer(...)`, and the presets `min_memory_usage()`, `high_performance_seed()`, `default_settings()`.
- For a synchronous add, use `session.swig().add_torrent(atp.swig(), error_code)`, which returns a `torrent_handle`. The other path is `async_add_torrent` followed by `AddTorrentAlert`.

### TorrentFlags (all constants are `swig.torrent_flags_t`)
SEED_MODE, UPLOAD_MODE, SHARE_MODE, APPLY_IP_FILTER, PAUSED, AUTO_MANAGED, DUPLICATE_IS_ERROR, UPDATE_SUBSCRIBE, SUPER_SEEDING, SEQUENTIAL_DOWNLOAD, STOP_WHEN_READY, NEED_SAVE_RESUME, DISABLE_DHT, DISABLE_LSD, DISABLE_PEX, NO_VERIFY_FILES, DEFAULT_DONT_DOWNLOAD, I2P_TORRENT, ALL.

Flag operations, available on every swig `*_flags_t` type: `or_`, `and_`, `xor_`, `inv`, `non_zero`, `eq`, `ne`, `to_int`, `static from_int`, `static all`.

### Small types
- **Priority** enum and its swig byte value: IGNORE 0, LOW 1, TWO 2, THREE 3, DEFAULT 4, FIVE 5, SIX 6, TOP_PRIORITY 7. Methods: `byte swig()`, `static fromSwig(int)`, `static Priority[] array(Priority, int n)`.
- **PieceIndexBitfield**: `getBit`, `setBit`, `clearBit`, `isAllSet`, `isNoneSet`, `size`, `isEmpty`, `count`, `findFirstSet`, `findLastClear`, `resize(int[,bool])`, `setAll`, `clearAll`, `clear`.
- **PartialPieceInfo**: `pieceIndex()`, `blocksInPiece()`, `finished()`, `writing()`, `requested()`. There is no per-block list in the wrapper. The swig `partial_piece_info.getBlocks()` returns only the first `block_info`, so per-block detail is unusable.
- **BlockInfo**: `peer()`, `bytesProgress()`, `blockSize()`, `state()` (NONE, REQUESTED, WRITING, FINISHED, UNKNOWN), `numPeers()`.
- **InfoHash**: `hasV1()`, `hasV2()`, `getBest()`, `getV1()`.
- **Sha1Hash**: `toHex()`, `static parseHex(String)`, `static fromBytes(byte[])`, `isAllZeros()`, `equals`, `hashCode`, `compareTo`, `clone`.
- **MoveFlags**: ALWAYS_REPLACE_FILES, FAIL_IF_EXIST, DONT_REPLACE, RESET_SAVE_PATH, RESET_SAVE_PATH_UNCHECKED.
- **AnnounceEntry**: `(String url)`, `url()`, `trackerId()`, `tier()`, `tier(short)`, `failLimit()`, `failLimit(short)`, `source()`, `isVerified()`, `endpoints()`.
- **TcpEndpoint**: `(String ip, int port)`, `(Address, int)`, `swig()`. Pass `.swig()` to `connect_peer`.
- **Address**: `static parseIp(String)`.
- **peer source flags** live on `swig.peer_info`: tracker, dht, pex, lsd, resume_data, incoming.
- **AlertListener** interface: `int[] types()` (return `null` for all types), `void alert(Alert<?>)`.

### Session-level swig (`session_handle`)
- Save flags (`save_state_flags_t`): save_settings, save_dht_state, save_extension_state, save_ip_filter. Use them as `session_state(save_state_flags_t)` and `session_params.write_session_params_buf(params, flags)`, or `new SessionParams(byte[])` to read state back.
- Remove flags: delete_files, delete_partfile.
- Reopen flags: `reopen_map_ports` (`reopen_network_sockets(reopen_network_flags_t)`).
- Methods: `post_torrent_updates(status_flags_t)`, `refresh_torrent_status(...)`, `get_torrents()`, `find_torrent(sha1_hash)`, `add_torrent(atp, ec)`, `async_add_torrent(atp)`, `dht_announce_ex(sha1[,port[,flags]])`, `dht_get_peers(sha1)`, `apply_settings`, `get_settings`, `set_alert_notify_callback`.

### Alerts (`org.libtorrent4j.alerts`)
**AlertType constants:** TORRENT_FINISHED, TORRENT_REMOVED, TORRENT_DELETED, TORRENT_PAUSED, TORRENT_RESUMED, TORRENT_CHECKED, TORRENT_ERROR, TORRENT_NEED_CERT, INCOMING_CONNECTION, ADD_TORRENT, SAVE_RESUME_DATA, FASTRESUME_REJECTED, BLOCK_FINISHED, METADATA_RECEIVED, METADATA_FAILED, FILE_COMPLETED, FILE_RENAMED, FILE_RENAME_FAILED, FILE_ERROR, HASH_FAILED, PORTMAP, PORTMAP_ERROR, PORTMAP_LOG, TRACKER_ANNOUNCE, TRACKER_REPLY, TRACKER_WARNING, TRACKER_ERROR, READ_PIECE, STATE_CHANGED, DHT_REPLY, DHT_BOOTSTRAP, DHT_GET_PEERS, EXTERNAL_IP, LISTEN_SUCCEEDED, STATE_UPDATE, SESSION_STATS, SCRAPE_REPLY, SCRAPE_FAILED, LSD_PEER, PEER_BLOCKED, PERFORMANCE, PIECE_FINISHED, SAVE_RESUME_DATA_FAILED, STORAGE_MOVED, TORRENT_DELETE_FAILED, URL_SEED, INVALID_REQUEST, LISTEN_FAILED, PEER_BAN, PEER_CONNECT, PEER_DISCONNECTED, PEER_ERROR, PEER_SNUBBED, PEER_UNSNUBBED, REQUEST_DROPPED, UDP_ERROR, BLOCK_DOWNLOADING, BLOCK_TIMEOUT, CACHE_FLUSHED, DHT_ANNOUNCE, STORAGE_MOVED_FAILED, TRACKERID, UNWANTED_BLOCK, DHT_ERROR, DHT_PUT, DHT_MUTABLE_ITEM, DHT_IMMUTABLE_ITEM, I2P, DHT_OUTGOING_GET_PEERS, LOG, TORRENT_LOG, PEER_LOG, LSD_ERROR, DHT_STATS, INCOMING_REQUEST, DHT_LOG, DHT_PKT, DHT_GET_PEERS_REPLY, DHT_DIRECT_RESPONSE, PICKER_LOG, SESSION_ERROR, DHT_LIVE_NODES, SESSION_STATS_HEADER, DHT_SAMPLE_INFOHASHES, BLOCK_UPLOADED, ALERTS_DROPPED, SOCKS5, FILE_PRIO, OVERSIZED_FILE, TORRENT_CONFLICT, PEER_INFO, FILE_PROGRESS, PIECE_INFO, PIECE_AVAILABILITY, TRACKER_LIST, UNKNOWN. Each has `int swig()` and `static fromSwig(int)`.

**Alert base:** `swig()`, `timestamp()`, `type()`, `what()`, `message()`, `category()`. Category constants (`Alert.*_NOTIFICATION`): ERROR, PEER, PORT_MAPPING, STORAGE, TRACKER, CONNECT, STATUS, IP_BLOCK, PERFORMANCE_WARNING, DHT, SESSION_LOG, TORRENT_LOG, PEER_LOG, INCOMING_REQUEST, DHT_LOG, DHT_OPERATION, PORT_MAPPING_LOG, PICKER_LOG, FILE_PROGRESS, PIECE_PROGRESS, UPLOAD, BLOCK_PROGRESS, ALL_CATEGORIES. `TorrentAlert` adds `TorrentHandle handle()`.

| Class | Accessors |
|---|---|
| PieceFinishedAlert | `int pieceIndex()` |
| BlockFinishedAlert (PeerAlert) | `blockIndex()`, `pieceIndex()` |
| BlockDownloadingAlert / BlockTimeoutAlert | `blockIndex()`, `pieceIndex()` |
| MetadataReceivedAlert | `handle()` only |
| MetadataFailedAlert | `ErrorCode getError()` |
| StateUpdateAlert | `List<TorrentStatus> status()` (changed torrents only) |
| SaveResumeDataAlert | `AddTorrentParams params()` |
| SaveResumeDataFailedAlert | `ErrorCode error()` |
| StorageMovedAlert | `String storagePath()` |
| StorageMovedFailedAlert | `ErrorCode error()`, `String filePath()`, `Operation operation()` |
| FileErrorAlert | `ErrorCode error()`, `String filename()`, `Operation operation()` |
| TorrentErrorAlert | `ErrorCode error()`, `String filename()` |
| ReadPieceAlert | `ErrorCode error()`, `long bufferPtr()`, `int piece()`, `int size()` (no byte[]; unusable) |
| AddTorrentAlert | `params()`, `error()` |
| HashFailedAlert | `pieceIndex()` |
| StateChangedAlert | `getState()`, `getPrevState()` |
| TrackerReplyAlert / DhtReplyAlert | `int numPeers()` |
| SessionStatsAlert | `long value(int)` |
| PerformanceAlert | `getWarningCode()` |
| TorrentRemovedAlert | `InfoHash getInfoHashes()` |

`PerformanceWarning` values: OUTSTANDING_DISK_BUFFER_LIMIT_REACHED, OUTSTANDING_REQUEST_LIMIT_REACHED, UPLOAD_LIMIT_TOO_LOW, DOWNLOAD_LIMIT_TOO_LOW, SEND_BUFFER_WATERMARK_TOO_LOW, TOO_MANY_OPTIMISTIC_UNCHOKE_SLOTS, TOO_HIGH_DISK_QUEUE_LIMIT, TOO_FEW_OUTGOING_PORTS, TOO_FEW_FILE_DESCRIPTORS. These are cheap tuning signals worth logging.

`ErrorCode`: `getValue()`, `getMessage()`, `isError()`.

---

## 2. media3 1.11.1

### Datasource
```
abstract BaseDataSource(boolean isNetwork)   // protected ctor
  final addTransferListener(TransferListener); protected final transferInitializing(DataSpec), transferStarted(DataSpec),
  bytesTransferred(int), transferEnded()
DataSource: long open(DataSpec) throws IOException; int read(byte[],int,int) [DataReader; return C.RESULT_END_OF_INPUT=-1];
  Uri getUri(); default Map<String,List<String>> getResponseHeaders(); void close()
DataSource.Factory: DataSource createDataSource()
DataSpec fields: uri, uriPositionOffset, httpMethod, httpBody, httpRequestHeaders, absoluteStreamPosition, position,
  length (C.LENGTH_UNSET=-1), key, flags, customData; FLAG_ALLOW_GZIP, FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN,
  FLAG_ALLOW_CACHE_FRAGMENTATION, FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED; isFlagSet(int), subrange(long[,long]), buildUpon(), withUri(Uri)
DataSourceException(int reason | String,int | Throwable,int | String,Throwable,int); field reason; POSITION_OUT_OF_RANGE;
  static isCausedByPositionOutOfRange(IOException)
HttpDataSource.HttpDataSourceException(IOException, DataSpec, int errorCode, int type) …; TYPE_OPEN/READ/CLOSE
OkHttpDataSource.Factory(okhttp3.Call.Factory): setDefaultRequestProperties(Map), setUserAgent, setCacheControl,
  setContentTypePredicate, setTransferListener, createDataSource()
DefaultHttpDataSource.Factory(): setConnectTimeoutMs, setReadTimeoutMs, setUserAgent, setDefaultRequestProperties,
  setAllowCrossProtocolRedirects, setContentTypePredicate, setTransferListener, setKeepPostFor302Redirects
```
`OkHttpDataSource.Factory` has **no timeout setters**. Timeouts come from the `OkHttpClient` (`readTimeout`, `connectTimeout`, `callTimeout`).

### DefaultLoadControl.Builder (all setters)
- `setAllocator(DefaultAllocator)`
- `setBufferDurationsMs(min, max, bufferForPlayback, bufferForPlaybackAfterRebuffer)`. It sets **both** the streaming and the local values.
- `setBufferDurationsMsForStreaming(...)` and `setBufferDurationsMsForLocalPlayback(...)`
- `setTargetBufferBytes(int)` and `setPlayerTargetBufferBytes(String playerName, int)`
- `setPrioritizeTimeOverSizeThresholds(bool)`, plus `…ForStreaming` and `…ForLocalPlayback` variants
- `setBackBuffer(int ms, boolean retainFromKeyframe)`
- `build()`

**GOTCHA: what counts as "local".** It is decided by the media item's URI scheme. Local schemes are file, content, data, android.resource, rawresource and asset. **`http://127.0.0.1` is classed as streaming**, so the streaming values apply.

Defaults:

| Constant | Streaming | Local |
|---|---|---|
| min buffer (ms) | 50 000 | 1 000 |
| max buffer (ms) | 50 000 | 50 000 |
| buffer for playback (ms) | 1 000 | 1 000 |
| buffer for playback after rebuffer (ms) | 2 000 | 1 000 |
| prioritize time over size | false | true |
| video buffer size | 131 072 000 | 19 660 800 |

Other defaults: target bytes −1, back buffer 0. Validation requires bufferForPlayback ≥ 0, after-rebuffer ≥ 0, min ≥ both of those, and max ≥ min.

### LoadControl (1.11.1)
- Abstract: `Allocator getAllocator(PlayerId)`.
- Defaults: `onPrepared(PlayerId)`, `onTracksSelected(Parameters, TrackGroupArray, ExoTrackSelection[])`, `onStopped(PlayerId)`, `onReleased(PlayerId)`, `getBackBufferDurationUs(PlayerId)`, `retainBackBufferFromKeyframe(PlayerId)`, `boolean shouldContinueLoading(Parameters)`, `boolean shouldStartPlayback(Parameters)`, `shouldContinuePreloading(PlayerId, Timeline, MediaPeriodId, long)`. Older overloads without PlayerId are still present.
- `LoadControl.Parameters` fields: playerId, timeline, mediaPeriodId, playbackPositionUs, bufferedDurationUs, playbackSpeed, playWhenReady, rebuffering, targetLiveOffsetUs, lastRebufferRealtimeMs.
- To wrap `DefaultLoadControl`, delegate and override `shouldStartPlayback` and `shouldContinueLoading`.

### Error policy
- `DefaultLoadErrorHandlingPolicy()` or `(int minRetryCount)`. Overridable methods: `getRetryDelayMsFor(LoadErrorInfo)`, `getMinimumLoadableRetryCount(int dataType)`, `getFallbackSelectionFor(FallbackOptions, LoadErrorInfo)`, `protected isEligibleForFallback(IOException)`. The interface also has a default `onLoadTaskConcluded(long)`.
- Default behaviour:
  - Retry delay is `min((errorCount-1)*1000, 5000)` ms.
  - It returns `C.TIME_UNSET` (fatal) if any cause is ParserException, FileNotFoundException, CleartextNotPermittedException, Loader.UnexpectedLoaderException, or DataSourceException with reason 2008 (READ_POSITION_OUT_OF_RANGE).
  - Min retry count is 3; it is 6 only for dataType 7 (progressive live).
  - Constants: DEFAULT_MIN_LOADABLE_RETRY_COUNT=3, …_PROGRESSIVE_LIVE=6, DEFAULT_TRACK_EXCLUSION_MS=60000, DEFAULT_LOCATION_EXCLUSION_MS=300000.
- `LoadErrorInfo` fields: loadEventInfo, mediaLoadData, exception (IOException), errorCount.
- `LoadEventInfo` fields: loadTaskId, dataSpec, uri, responseHeaders, elapsedRealtimeMs, loadDurationMs, bytesLoaded.

### Player and source setup
- **SeekParameters**: EXACT, CLOSEST_SYNC, PREVIOUS_SYNC, NEXT_SYNC, DEFAULT; constructor `(toleranceBeforeUs, toleranceAfterUs)`.
- **ExoPlayer**: `setWakeMode(int)` with C.WAKE_MODE_NONE 0, LOCAL 1, NETWORK 2. WAKE_LOCK is already in the manifest. Also `setSeekParameters(SeekParameters)`, `getSeekParameters()`, `addAnalyticsListener`, `setForegroundMode`, `setScrubbingModeEnabled(bool)`, `setPriority(int)`.
- **ExoPlayer.Builder**: setMediaSourceFactory, setLoadControl, setBandwidthMeter, setWakeMode, setSeekParameters, setSeekBackIncrementMs, setSeekForwardIncrementMs, setReleaseTimeoutMs, setHandleAudioBecomingNoisy, setPlaybackLooper, setPriority, setScrubbingModeParameters, setSuppressPlaybackOnUnsuitableOutput.
- **DefaultMediaSourceFactory**: constructors `(Context)`, `(DataSource.Factory)`, `(DataSource.Factory, ExtractorsFactory)`, `(Context, ExtractorsFactory)`. Methods `setDataSourceFactory`, `setLoadErrorHandlingPolicy`, `createMediaSource(MediaItem)`.
- **ProgressiveMediaSource.Factory**: constructors `(DataSource.Factory)`, `(DataSource.Factory, ExtractorsFactory)`, `(DataSource.Factory, ProgressiveMediaExtractor.Factory[, DrmSessionManagerProvider, LoadErrorHandlingPolicy, int continueLoadingCheckIntervalBytes])`. Methods `setLoadErrorHandlingPolicy`, `setContinueLoadingCheckIntervalBytes(int)`, `setDownloadExecutor(...)`, `setLoadOnlySelectedTracks(bool)`, `createMediaSource`.

### Player.Listener and analytics
- Player.Listener (all default methods): `onEvents`, `onTimelineChanged(Timeline, int reason)` (TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED 0, SOURCE_UPDATE 1), `onIsLoadingChanged(bool)`, `onPlaybackStateChanged(int)` (STATE_IDLE 1, BUFFERING 2, READY 3, ENDED 4), `onPlayerError(PlaybackException)`, `onPlayerErrorChanged`, `onPositionDiscontinuity(PositionInfo old, PositionInfo new, int reason)`, `onRenderedFirstFrame()`, `onTracksChanged`, `onVideoSizeChanged`, `onPlaybackSuppressionReasonChanged`.
- Discontinuity reasons: AUTO_TRANSITION 0, SEEK 1, SEEK_ADJUSTMENT 2, SKIP 3, REMOVE 4, INTERNAL 5, SILENCE_SKIP 6.
- AnalyticsListener, for instrumentation: `onLoadStarted`, `onLoadCompleted`, `onLoadCanceled`, `onLoadError(EventTime, LoadEventInfo, MediaLoadData, IOException, boolean wasCanceled)`, `onBandwidthEstimate(EventTime, int, long, long)`, `onRenderedFirstFrame(EventTime, Object, long)`, `onDroppedVideoFrames`, `onPlaybackStateChanged`, `onIsLoadingChanged`.
- PlaybackException codes: UNSPECIFIED 1000, TIMEOUT 1003, IO_UNSPECIFIED 2000, IO_NETWORK_CONNECTION_FAILED 2001, IO_NETWORK_CONNECTION_TIMEOUT 2002, IO_BAD_HTTP_STATUS 2004, IO_FILE_NOT_FOUND 2005, IO_READ_POSITION_OUT_OF_RANGE 2008, PARSING_CONTAINER_MALFORMED 3001, PARSING_CONTAINER_UNSUPPORTED 3003.

---

## 3. NanoHTTPD 2.3.1 (`fi.iki.elonen`)
- `NanoHTTPD(int port)` or `(String host, int port)`; `start()`, `start(int timeout)`, `start(int timeout, boolean daemon)`; `stop()`; `closeAllConnections()`.
- `public static final int SOCKET_READ_TIMEOUT = 5000`. It is applied with `setSoTimeout` to every accepted socket and only affects reading the request or an idle keep-alive connection. It does not affect time spent inside `serve()`.
- `protected ClientHandler createClientHandler(Socket, InputStream)` and `protected ServerRunnable createServerRunnable(int timeout)` are overridable.
- `protected AsyncRunner asyncRunner` and `setAsyncRunner(AsyncRunner)`. The `AsyncRunner` interface has `exec(ClientHandler)`, `closed(ClientHandler)` and `closeAll()`.
- `DefaultAsyncRunner` starts **one new daemon Thread per connection**, named "NanoHttpd Request Processor (#n)", with no pool or limit. It has `getRunning()`.
- `ClientHandler(NanoHTTPD, InputStream, Socket)` is a public Runnable with `close()` and `run()`. It loops `HTTPSession.execute()` while the socket is open, which is keep-alive.
- **Response:**
  - `closeConnection(boolean)` and `isCloseConnection()` exist.
  - `setKeepAlive(bool)`, `setChunkedTransfer(bool)`, `setGzipEncoding(bool)`, `addHeader`, `getHeader`, `setData(InputStream)`, `getData()`, `setStatus`, `close()`.
  - `protected send(OutputStream)`. The body is copied in 16 384-byte chunks, at most `min(pending,16384)` per read; a write error raises SocketException and closes the connection.
- `newFixedLengthResponse(IStatus, String mime, InputStream, long len)` and `newChunkedResponse(IStatus, String, InputStream)`.
- `protected boolean useGzipWhenAccepted(Response)`. Override it to return false for video.

---

## 4. Findings the implementer must act on
1. **The alert_mask in `TorrentEngine.kt:138` is ignored** because `SessionManager.start` overwrites it. Re-apply it with `applySettings` after `start()`, or override `onAfterStart()`. Use `max_metadata_size` and `dht_bootstrap_nodes` freely: start only fills them in when they are unset.
2. **STATE_UPDATE comes for free about once a second**, because the alerts loop calls `postTorrentUpdates()` itself. It includes the pieces bitfield. Prefer it over polling `status()`.
3. **`TorrentHandle.status()` is cached for 500 ms per instance and returns no pieces.** Use `status(TorrentHandle.QUERY_PIECES)` when you need the bitfield.
4. **`read_piece` and alert_when_available are unusable from Java**, since the alert gives only a native pointer. Keep reading from disk.
5. **`setSequentialRange(first[,last])` exists** on both ABIs. It turns on sequential mode per the header docs; verify on device.
6. **Sparse piece priorities:** use the swig `prioritize_pieces_ex(int_byte_pair_vector)`. `clear_error`, `connect_peer`, `info_hashes`, per-torrent `set_max_connections` and `force_reannounce(int, reannounce_flags_t)` are also swig-only.
7. **The 32-bit .so has no mmap_disk_io**, so the TV always uses posix disk I/O. `mmap_write_mode` and `mmap_file_size_cutoff` do nothing there.
8. **media3 treats `http://127.0.0.1` as streaming**, so the streaming buffer values apply. `setBufferDurationsMs` sets both the streaming and local values.
9. **Default error policy:** ParserException and READ_POSITION_OUT_OF_RANGE (2008) are fatal. Everything else gets at least 3 retries with a delay of `min((n-1)*1000, 5000)` ms.
10. **NanoHTTPD spawns one unbounded thread per connection.** Its 5 s socket timeout only covers reading the request, not time spent waiting in `serve()`.