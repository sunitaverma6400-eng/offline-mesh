package com.offline.mesh

/**
 * Single source of truth for "which version of the app is this". Included in
 * every outgoing mesh message so peers can tell if someone in the group is on
 * a different build - useful because new message types (roll call, detained
 * alert, pinned dispersal, etc.) only work if everyone in the group has
 * updated. Bump CODE whenever you cut a new release/APK.
 */
object AppVersion {
    const val CODE = 5
    const val NAME = "v5"
}
