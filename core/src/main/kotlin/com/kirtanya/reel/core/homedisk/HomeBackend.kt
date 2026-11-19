package com.kirtanya.reel.core.homedisk

import kotlinx.serialization.Serializable

/**
 * Where the home library lives (docs/HOME_DISK.md §23): a USB drive plugged into the TV, or a shared folder
 * on the router. One is active at a time; the other one's settings stay saved.
 */
@Serializable
enum class HomeBackend(val label: String, val blurb: String) {
    USB("USB disk on this TV", "A drive plugged into the TV. No network folder needed."),
    NETWORK("Network folder", "A shared folder on your router."),
}
