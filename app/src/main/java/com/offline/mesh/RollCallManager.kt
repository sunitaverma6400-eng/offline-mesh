package com.offline.mesh

import java.util.Collections

/**
 * Tracks (a) which sender IDs we've seen chatting in this group ("known
 * participants" - a name-only roster reconstructed from message traffic,
 * since there's no separate roster/contacts feature), and (b) the state of
 * roll-call round(s) *we* started, so the organizer can see live who has
 * confirmed safe and who hasn't yet.
 *
 * Honest limitation: this can only know about devices that have sent (or
 * relayed) at least one message we've personally seen since this app
 * instance started - it is NOT persisted across app restarts, and it's
 * cluster/session scale by design, same as the rest of this app. A phone
 * that never sent anything won't show up as "missing" - it simply isn't
 * known yet. Roll call is a best-effort safety signal, not a guaranteed
 * headcount.
 */
object RollCallManager {

    // senderId -> senderName, most-recently-seen name wins
    private val knownParticipants = Collections.synchronizedMap(mutableMapOf<String, String>())

    data class Round(
        val roundId: String,
        val startedAt: Long,
        // Snapshot of who we knew about at the moment the round started - this
        // is the "expected" list the organizer's missing-list is computed against.
        val expectedParticipantIds: Set<String>,
        val responded: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    )

    // roundId -> Round; only rounds *we* started (organizer side) are tracked here.
    private val ownRounds = Collections.synchronizedMap(mutableMapOf<String, Round>())

    fun noteKnownParticipant(senderId: String, senderName: String) {
        if (senderId.isEmpty()) return
        knownParticipants[senderId] = senderName
    }

    fun knownParticipantCount(): Int = knownParticipants.size

    /** Call when WE start a roll call round (we're the organizer). [roundId] is
     *  the id of the ROLLCALL_REQUEST message we just sent. */
    fun startRound(roundId: String) {
        ownRounds[roundId] = Round(
            roundId = roundId,
            startedAt = System.currentTimeMillis(),
            expectedParticipantIds = knownParticipants.keys.toSet()
        )
    }

    /** Records that [senderId] responded to [roundId]. Returns true if this was
     *  a round we're tracking (i.e. we started it) so the caller can decide
     *  whether to notify the UI. */
    fun recordResponse(roundId: String, senderId: String): Boolean {
        val round = ownRounds[roundId] ?: return false
        round.responded.add(senderId)
        return true
    }

    fun isOwnRound(roundId: String): Boolean = ownRounds.containsKey(roundId)

    /** senderId -> name of everyone in the round's expected snapshot who has NOT
     *  yet responded. Only meaningful for rounds we started. */
    fun missingFor(roundId: String): Map<String, String> {
        val round = ownRounds[roundId] ?: return emptyMap()
        return round.expectedParticipantIds
            .filter { it !in round.responded }
            .associateWith { knownParticipants[it] ?: "Unknown device" }
    }

    fun respondedNamesFor(roundId: String): Map<String, String> {
        val round = ownRounds[roundId] ?: return emptyMap()
        return round.responded.associateWith { knownParticipants[it] ?: "Unknown device" }
    }

    fun expectedCountFor(roundId: String): Int = ownRounds[roundId]?.expectedParticipantIds?.size ?: 0
    fun respondedCountFor(roundId: String): Int = ownRounds[roundId]?.responded?.size ?: 0
    fun startedAtFor(roundId: String): Long = ownRounds[roundId]?.startedAt ?: 0L

    /** Wiped on panic-wipe - this is live session state, not evidence, but it can
     *  still reveal who was around, so it goes away with everything else. */
    fun wipeAll() {
        knownParticipants.clear()
        ownRounds.clear()
    }
}
