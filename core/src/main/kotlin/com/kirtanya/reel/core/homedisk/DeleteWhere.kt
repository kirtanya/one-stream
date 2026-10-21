package com.kirtanya.reel.core.homedisk

/** Where a title is, as far as this phone knows. */
enum class DiskPresence {
    /** Complete and verified on the home disk. */
    ON_DISK,
    /** Known not to be on the disk. */
    NOT_ON_DISK,
    /** No disk connected (or not set up yet): nobody can tell. */
    UNKNOWN,
    /** This phone's copy is still being copied there. */
    COPYING,
    /** The TV is downloading it to the disk. */
    DOWNLOADING_AT_HOME,
}

enum class DeleteAction { PHONE, DISK, BOTH, STOP_COPY, CANCEL_AT_HOME }

data class DeleteOption(val action: DeleteAction, val label: String, val enabled: Boolean = true, val note: String? = null)

data class DeleteChoice(val body: String, val options: List<DeleteOption>, val warning: String? = null)

/**
 * The delete-where choices per title (docs/HOME_DISK.md §14). Disk options appear disabled with
 * "Home disk not connected" while nobody can tell what the disk holds (no disk yet, not connected);
 * once the disk is known, a phone-only title offers a plain Delete.
 */
object DeleteWhere {
    const val NOT_CONNECTED = "Home disk not connected"

    fun choice(onPhone: Boolean, disk: DiskPresence): DeleteChoice = when {
        disk == DiskPresence.DOWNLOADING_AT_HOME && !onPhone -> DeleteChoice(
            "Your TV is downloading this to the home disk.",
            listOf(DeleteOption(DeleteAction.CANCEL_AT_HOME, "Cancel download at home")),
        )
        onPhone && disk == DiskPresence.COPYING -> DeleteChoice(
            "This phone is still copying it to the home disk.",
            listOf(DeleteOption(DeleteAction.PHONE, "Delete from phone"), DeleteOption(DeleteAction.STOP_COPY, "Stop copying")),
            warning = "The home copy isn't finished; it will be removed too.",
        )
        onPhone && disk == DiskPresence.ON_DISK -> DeleteChoice(
            "It's on this phone and on your home disk.",
            listOf(
                DeleteOption(DeleteAction.PHONE, "Delete from phone", note = "Keeps the home copy"),
                DeleteOption(DeleteAction.DISK, "Delete from home disk", note = "Keeps the phone copy"),
                DeleteOption(DeleteAction.BOTH, "Delete from both"),
            ),
        )
        onPhone && disk == DiskPresence.UNKNOWN -> DeleteChoice(
            "This removes the torrent and its downloaded files from this phone.",
            listOf(
                DeleteOption(DeleteAction.PHONE, "Delete from phone"),
                DeleteOption(DeleteAction.DISK, "Delete from home disk", enabled = false, note = NOT_CONNECTED),
                DeleteOption(DeleteAction.BOTH, "Delete from both", enabled = false, note = NOT_CONNECTED),
            ),
        )
        onPhone -> DeleteChoice(
            "This removes the torrent and its downloaded files from this phone.",
            listOf(DeleteOption(DeleteAction.PHONE, "Delete")),
        )
        disk == DiskPresence.ON_DISK -> DeleteChoice(
            "It's only on your home disk.",
            listOf(DeleteOption(DeleteAction.DISK, "Delete from home disk")),
        )
        else -> DeleteChoice("It isn't on this phone.", emptyList())
    }
}
