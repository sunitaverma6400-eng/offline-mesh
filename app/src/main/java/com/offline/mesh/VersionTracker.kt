package com.offline.mesh

import java.util.Collections

/**
 * Tracks which app version every peer we've seen chatting is on. New message
 * types (roll call, detained alert, pinned dispersal...) only work if
 * everyone in the group is on a build that understands them - a phone on an
 * older build will just show them as a plain/garbled text message (or not at
 * all), not a functioning safety feature. This is purely an informational
 * warning, not a hard block - nothing here refuses to talk to older peers.
 *
 * Same honest limitation as RollCallManager: in-memory/session-only, only
 * knows about devices we've personally seen a message from since app launch.
 */
object VersionTracker {

    // senderId -> (senderName, appVersion)
    private val seen = Collections.synchronizedMap(mutableMapOf<String, Pair<String, Int>>())

    fun note(senderId: String, senderName: String, appVersion: Int) {
        if (senderId.isEmpty()) return
        seen[senderId] = senderName to appVersion
    }

    /** senderId -> (name, version) for every peer whose version differs from ours. */
    fun mismatched(): Map<String, Pair<String, Int>> =
        seen.filterValues { it.second != AppVersion.CODE }

    fun mismatchCount(): Int = mismatched().size

    fun wipeAll() {
        seen.clear()
    }
}
