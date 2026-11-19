package com.kirtanya.reel.core.homedisk

/** What Android says about a volume's state, folded to what the choice needs. */
enum class VolumeState { MOUNTED, UNMOUNTABLE, OTHER }

/**
 * One storage volume as `StorageManager` describes it (docs/HOME_DISK.md §23.2). [usable] = the app has its own
 * folder on it (a mounted volume the app can write). [id] is the volume UUID, e.g. `37EE-1DEE`.
 */
data class VolumeFacts(
    val id: String,
    val name: String,
    val removable: Boolean,
    val emulated: Boolean,
    val primary: Boolean,
    val state: VolumeState,
    val usable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

/** Which volume is the home disk, and what to say about it. */
object VolumeChoice {
    /** Under this a job can't start (the same floor the conveyor budget keeps clear, §8.3, scaled to a disk). */
    const val MIN_FREE = 2_000_000_000L
    const val PROTOCOL = "USB"

    private fun candidates(volumes: List<VolumeFacts>) =
        volumes.filter { it.state == VolumeState.MOUNTED && it.usable && it.removable && !it.emulated && !it.primary }

    /** The remembered disk when it's here, else the biggest, then the one with most free space, then the smaller id. */
    fun pick(volumes: List<VolumeFacts>, preferredId: String?): VolumeFacts? {
        val c = candidates(volumes)
        return c.firstOrNull { it.id == preferredId }
            ?: c.sortedWith(compareByDescending<VolumeFacts> { it.totalBytes }.thenByDescending { it.freeBytes }.thenBy { it.id }).firstOrNull()
    }

    /** The state the rest of the app shows. Unplugged is a normal state, not an error. */
    fun state(volumes: List<VolumeFacts>, preferredId: String?): HomeDiskState {
        val v = pick(volumes, preferredId)
        return when {
            v != null && v.freeBytes < MIN_FREE -> HomeDiskState.NotConnected(NotConnectedReason.FULL, v.name)
            v != null -> HomeDiskState.Connected(PROTOCOL, v.freeBytes, v.totalBytes, smb1 = false, name = v.name)
            volumes.any { it.removable && !it.emulated && !it.primary && it.state == VolumeState.UNMOUNTABLE } ->
                HomeDiskState.NotConnected(NotConnectedReason.UNREADABLE)
            else -> HomeDiskState.NotConnected(NotConnectedReason.NO_USB)
        }
    }
}
