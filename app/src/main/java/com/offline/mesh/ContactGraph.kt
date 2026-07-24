package com.offline.mesh

import org.json.JSONObject

/**
 * Store-and-forward "contact graph" — tracks how often THIS phone has met each
 * other device (by senderId, since BLE MAC addresses rotate for privacy on
 * modern Android and can't be used as a stable identity).
 *
 * Honest scope of what this actually buys you: OfflineMesh is still fundamentally
 * a FLOOD network - every message still goes to every directly-connected peer,
 * because with only local per-device knowledge (no phone knows the whole mesh's
 * shape) there's no way to compute a real shortest-path route like a normal
 * network router would. What the contact graph *can* do, cheaply and locally:
 *
 *  1. Prioritize which peer to sync outbox to FIRST when several connect at once
 *     (BleMeshService has to serialize GATT operations one at a time anyway - see
 *     its "only ONE in-flight GATT operation" note). A peer met often before is
 *     more likely to be a "regular" (fellow cluster member) worth reaching fast;
 *     a rarely-seen peer might be a one-off carrier passing through, worth
 *     syncing to *especially* because they may not come back - so we actually
 *     weight toward BOTH ends: very-frequent (reliable relay) and very-rare
 *     (possibly a bridge to a new city/cluster we haven't reached yet) peers
 *     get priority over "seen a couple times, probably already fully synced".
 *  2. Feed the debug/health screen with a simple "who do I see most" list, so an
 *     organizer can sanity check the mesh actually looks like their group
 *     (not e.g. mostly strangers' phones due to UUID collision).
 *
 * This does NOT change encryption, does NOT add new radio traffic, and does NOT
 * let one phone see the graph of who ELSE has met whom - it is a purely local,
 * on-device record of this phone's own encounters.
 */
object ContactGraph {

    data class ContactEntry(
        val peerId: String,
        var peerLabel: String,
        var encounterCount: Int,
        var firstSeenMs: Long,
        var lastSeenMs: Long
    )

    private val entries = mutableMapOf<String, ContactEntry>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val json = PersistentStore.loadContactGraph()
        for (peerId in json.keys()) {
            val o = json.getJSONObject(peerId)
            entries[peerId] = ContactEntry(
                peerId = peerId,
                peerLabel = o.optString("label", peerId.take(6)),
                encounterCount = o.optInt("count", 0),
                firstSeenMs = o.optLong("first", System.currentTimeMillis()),
                lastSeenMs = o.optLong("last", System.currentTimeMillis())
            )
        }
    }

    @Synchronized
    private fun persist() {
        val obj = JSONObject()
        for ((peerId, e) in entries) {
            val o = JSONObject()
            o.put("label", e.peerLabel)
            o.put("count", e.encounterCount)
            o.put("first", e.firstSeenMs)
            o.put("last", e.lastSeenMs)
            obj.put(peerId, o)
        }
        PersistentStore.saveContactGraph(obj)
    }

    /** Call whenever a GATT link (as client or server) successfully forms with [peerId]
     *  (a stable senderId learned from a decrypted message on that link, NOT the
     *  rotating BLE MAC). [peerLabel] is a display name if we have one yet. */
    @Synchronized
    fun recordEncounter(peerId: String, peerLabel: String? = null) {
        if (peerId.isBlank()) return
        ensureLoaded()
        val now = System.currentTimeMillis()
        val existing = entries[peerId]
        if (existing == null) {
            entries[peerId] = ContactEntry(peerId, peerLabel ?: peerId.take(6), 1, now, now)
        } else {
            existing.encounterCount += 1
            existing.lastSeenMs = now
            if (!peerLabel.isNullOrBlank()) existing.peerLabel = peerLabel
        }
        persist()
    }

    @Synchronized
    fun getEncounterCount(peerId: String): Int {
        ensureLoaded()
        return entries[peerId]?.encounterCount ?: 0
    }

    /**
     * Routing hint: given several peers connected right now, order them for
     * "who to sync the outbox to first". See class doc for the reasoning -
     * we push both very-frequent AND very-rare (count <= 1, possible new bridge)
     * peers to the front, and de-prioritize the "seen a few times, probably
     * already synced" middle band.
     */
    @Synchronized
    fun rankPeersForSync(peerIds: List<String>): List<String> {
        ensureLoaded()
        fun score(id: String): Int {
            val c = entries[id]?.encounterCount ?: 0
            return when {
                c <= 1 -> 1000 + c            // brand-new/rare -> sync fast, might not see them again
                c >= 20 -> 500 + c             // very frequent -> reliable relay, sync fast too
                else -> c                      // middling familiarity -> lower priority
            }
        }
        return peerIds.sortedByDescending { score(it) }
    }

    /** Top N most-frequently-met contacts, for the debug/health screen. */
    @Synchronized
    fun topContacts(limit: Int = 10): List<ContactEntry> {
        ensureLoaded()
        return entries.values.sortedByDescending { it.encounterCount }.take(limit)
    }

    @Synchronized
    fun totalKnownContacts(): Int {
        ensureLoaded()
        return entries.size
    }
}
