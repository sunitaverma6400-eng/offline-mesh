package com.offline.mesh

import android.content.Context
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Keeps: (a) messages already shown/seen so we never relay or display a duplicate,
 *        (b) a pending outbox that BleMeshService drains whenever a peer connects,
 *        (c) the visible chat history for the UI,
 *        (d) a rolling window of "who have we seen lately" used for adaptive TTL.
 *
 * Everything here is backed by [PersistentStore] so a phone can be carried
 * between two clusters (sneakernet) - killed, restarted, whatever happens in
 * between - and still deliver what it was holding once it meets the next peer.
 *
 * Call [init] with a profile name ("main" or a duress/decoy profile) - each
 * profile is backed by completely separate files on disk.
 */
object MessageStore {

    private val seenIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private const val MAX_SEEN = 5000

    private val outbox = Collections.synchronizedList(mutableListOf<MeshMessage>())
    val displayedMessages = Collections.synchronizedList(mutableListOf<MeshMessage>())

    // senderId -> last-seen-at-ms, used only to gauge how "dense" the mesh is
    // right now for adaptive TTL. Not persisted - it's a live signal, not history.
    private val recentSenderSightings = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private const val DENSITY_WINDOW_MS = 15 * 60 * 1000L // last 15 minutes

    private var loadedProfile: String? = null

    /** Call once per profile switch, before using the store. Safe to call again to switch profiles. */
    fun init(context: Context, profile: String = "main") {
        if (loadedProfile == profile) return
        PersistentStore.init(context.applicationContext, profile)
        seenIds.clear(); seenIds.addAll(PersistentStore.loadSeenIds())
        outbox.clear(); outbox.addAll(PersistentStore.loadOutbox())
        displayedMessages.clear(); displayedMessages.addAll(PersistentStore.loadDisplayed())
        pruneExpiredOutbox()
        loadedProfile = profile
    }

    /** Returns true if this is a new message we haven't processed before. */
    fun markSeenIfNew(message: MeshMessage): Boolean {
        synchronized(seenIds) {
            if (seenIds.contains(message.id)) return false
            seenIds.add(message.id)
            if (seenIds.size > MAX_SEEN) {
                val iterator = seenIds.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        PersistentStore.saveSeenIds(seenIds)
        noteSenderSighting(message.senderId)
        return true
    }

    private fun noteSenderSighting(senderId: String) {
        recentSenderSightings[senderId] = System.currentTimeMillis()
    }

    /** How many distinct senders we've heard from in the last [DENSITY_WINDOW_MS] - a proxy
     *  for "how crowded/well-connected is this mesh right now". Used for adaptive TTL. */
    fun recentUniqueSenderCount(): Int {
        val cutoff = System.currentTimeMillis() - DENSITY_WINDOW_MS
        synchronized(recentSenderSightings) {
            recentSenderSightings.entries.removeAll { it.value < cutoff }
            return recentSenderSightings.size
        }
    }

    fun addToOutbox(message: MeshMessage) {
        outbox.add(message)
        evictIfOverBudget()
        PersistentStore.saveOutbox(outbox)
    }

    /** Outbox sorted URGENT-first (then oldest-first within a priority) - this is the
     *  order BleMeshService should actually send/relay in when a peer connects. */
    fun currentOutbox(): List<MeshMessage> {
        pruneExpiredOutbox()
        return outbox.sortedWith(
            compareByDescending<MeshMessage> { it.priority == Priority.URGENT }.thenBy { it.ts }
        )
    }

    /** How many messages are still waiting to reach someone - shown in the UI. */
    fun pendingCount(): Int {
        pruneExpiredOutbox()
        return outbox.size
    }

    /** Drop anything that's been sitting undelivered for too long (see MAX_OUTBOX_AGE_MS). */
    private fun pruneExpiredOutbox() {
        val removed = outbox.removeAll { it.isExpired(MeshMessage.MAX_OUTBOX_AGE_MS) }
        if (removed) PersistentStore.saveOutbox(outbox)
    }

    /** Smart eviction: if the outbox grows past MAX_OUTBOX_BYTES (e.g. lots of undelivered
     *  photos piling up while no peer is around), drop the OLDEST NORMAL-priority messages
     *  first - URGENT messages and recent messages are protected as long as possible. */
    private fun evictIfOverBudget() {
        var totalBytes = outbox.sumOf { it.approxSizeBytes().toLong() }
        if (totalBytes <= MeshMessage.MAX_OUTBOX_BYTES) return

        val byEvictionOrder = outbox
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<MeshMessage>> { it.value.priority == Priority.URGENT }
                    .thenBy { it.value.ts }
            )
        val toRemove = mutableSetOf<Int>()
        for ((index, item) in byEvictionOrder) {
            if (totalBytes <= MeshMessage.MAX_OUTBOX_BYTES) break
            if (item.priority == Priority.URGENT) continue // last resort only
            toRemove.add(index)
            totalBytes -= item.approxSizeBytes().toLong()
        }
        if (toRemove.isNotEmpty()) {
            val kept = outbox.filterIndexed { idx, _ -> idx !in toRemove }
            outbox.clear()
            outbox.addAll(kept)
        }
    }

    /** Once we're confident a message reached a peer, we could remove it - kept
     *  simple for now: we rely on receiver-side dedup rather than tracking
     *  per-peer ack, so messages stay in the outbox until they expire. */
    fun addDisplayed(message: MeshMessage) {
        displayedMessages.add(message)
        PersistentStore.saveDisplayed(displayedMessages)
    }

    /** Mesh health signal for the UI: how many distinct senders we've heard from
     *  recently. Same underlying data adaptive TTL uses, just exposed for display. */
    fun recentUniqueSenderCountForDisplay(): Int = recentUniqueSenderCount()

    /** Auto-wipe: drops displayed chat history older than [maxAgeMillis]. Used by the
     *  optional "auto-delete messages after N hours" setting. Does NOT touch the
     *  outbox (undelivered messages still need to reach peers) - only the visible
     *  history. Returns true if anything was actually removed. */
    fun purgeDisplayedOlderThan(maxAgeMillis: Long): Boolean {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        val removed = displayedMessages.removeAll { it.ts < cutoff }
        if (removed) PersistentStore.saveDisplayed(displayedMessages)
        return removed
    }

    /** Panic-wipe: erase every message THIS PROFILE holds, in memory and on disk.
     *  Does not affect other devices' copies, and does not affect other profiles
     *  (e.g. wiping the duress/decoy profile never touches the real one). */
    fun wipeAll() {
        seenIds.clear()
        outbox.clear()
        displayedMessages.clear()
        recentSenderSightings.clear()
        PersistentStore.wipeAll()
    }
}
