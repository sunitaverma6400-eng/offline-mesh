package com.offline.mesh

import org.json.JSONObject
import java.util.UUID

enum class MessageType {
    TEXT, IMAGE, AUDIO, LOCATION,
    // Organizer broadcasts this to ask "confirm you're safe"; the message's own
    // [MeshMessage.id] IS the round id (see RollCallManager) - no separate field needed.
    ROLLCALL_REQUEST,
    // payload (pre-encryption) is the roundId string being responded to.
    ROLLCALL_RESPONSE,
    // payload is human-readable "name — last seen at location/time" detention detail.
    DETAINED_ALERT,
    // payload is the current pinned dispersal-point text; latest one replaces the last.
    PINNED_DISPERSAL,
    // Sent automatically by a device when it receives+decrypts a "real" content message
    // (TEXT/IMAGE/AUDIO/LOCATION/DETAINED_ALERT) from someone else. Payload (pre-encryption)
    // is the id of the message being acknowledged. Never shown as a chat bubble itself -
    // BleMeshService intercepts it and turns it into a "✓ delivered" mark on the original.
    DELIVERY_ACK
}

/** URGENT messages jump the queue: sent/relayed before NORMAL ones when a peer connects. */
enum class Priority { NORMAL, URGENT }

/**
 * A single chat message that hops across the mesh.
 *
 * id          - unique id so every device can dedup (avoid re-relaying / re-showing the same msg)
 * senderId    - random id generated once per install, identifies "who sent this" in the UI
 * ttl         - hop count remaining. Decremented every relay; message stops spreading at 0.
 * ts          - device-local timestamp (ms) at time of creation, for ordering in the UI
 * type        - TEXT, IMAGE, AUDIO, or LOCATION
 * priority    - NORMAL or URGENT; URGENT is flushed/relayed first
 * epoch       - which RatchetManager time-epoch this payload was encrypted under
 * payload     - Base64 AES-GCM ciphertext of: chat text / image bytes / audio bytes / "lat,lon"
 * senderPubKey- Base64 device public key (empty on messages from older/unsigned builds)
 * signature   - Base64 ECDSA signature over the rest of the fields, see KeyManager.signableBytes
 * appVersion  - sender's AppVersion.CODE at send time. Missing/0 on messages from builds
 *               before this field existed - treated as "unknown, assume older".
 * channel     - sub-channel within the group passphrase (e.g. "general", "medic", "legal").
 *               Purely a client-side filter/label - does NOT change encryption/routing,
 *               so older builds without this field just show everything in one feed
 *               (they default to "general" via optString below, see fromJson).
 * anonymous   - if true, UI should show senderName as "Someone in group" instead of the
 *               real senderName. senderName itself is still transmitted as-is (needed for
 *               older clients / signature verification) - hiding it is a DISPLAY decision
 *               made per-client, not real sender-anonymity at the protocol level. See
 *               ChannelsAndAnonymity.kt for the honest limitations write-up.
 */
data class MeshMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    var ttl: Int,
    val ts: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val priority: Priority = Priority.NORMAL,
    val epoch: Long = 0L,
    val payload: String,
    val senderPubKey: String = "",
    val signature: String = "",
    val appVersion: Int = AppVersion.CODE,
    val channel: String = ChannelsAndAnonymity.DEFAULT_CHANNEL,
    val anonymous: Boolean = false
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("senderId", senderId)
        obj.put("senderName", senderName)
        obj.put("ttl", ttl)
        obj.put("ts", ts)
        obj.put("type", type.name)
        obj.put("priority", priority.name)
        obj.put("epoch", epoch)
        obj.put("payload", payload)
        obj.put("senderPubKey", senderPubKey)
        obj.put("signature", signature)
        obj.put("appVersion", appVersion)
        obj.put("channel", channel)
        obj.put("anonymous", anonymous)
        return obj.toString()
    }

    /** What to show as the sender label in the UI, honoring anonymous mode. */
    fun displayName(): String =
        if (anonymous) "Someone in group" else senderName

    /** True if this message is older than [maxAgeMillis] and should be dropped from the outbox. */
    fun isExpired(maxAgeMillis: Long): Boolean {
        return System.currentTimeMillis() - ts > maxAgeMillis
    }

    /** Rough size in bytes - used to decide which transport (BLE vs Wi-Fi Direct) can carry it. */
    fun approxSizeBytes(): Int = payload.length

    companion object {
        // Bumped up from 5: in a sneakernet/store-and-forward setup, a single
        // carrier phone travelling between two clusters effectively "uses up"
        // a hop each time it meets a new cluster, so we allow a longer chain.
        const val DEFAULT_TTL = 20

        // How long an undelivered message sits in the outbox waiting for a new
        // peer before we give up on it (prevents unbounded storage growth).
        const val MAX_OUTBOX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        // BLE GATT writes are only reliable up to a few hundred bytes without
        // an MTU negotiation dance. Anything bigger (e.g. images) should go
        // over Wi-Fi Direct instead, which has no such limit.
        const val BLE_SAFE_PAYLOAD_BYTES = 500

        // Total outbox size cap (bytes of Base64 payload) - once exceeded, the
        // oldest NORMAL-priority messages are evicted first (see MessageStore).
        const val MAX_OUTBOX_BYTES = 8L * 1024 * 1024 // 8 MB

        fun fromJson(json: String): MeshMessage? {
            return try {
                val obj = JSONObject(json)
                MeshMessage(
                    id = obj.getString("id"),
                    senderId = obj.getString("senderId"),
                    senderName = obj.getString("senderName"),
                    ttl = obj.getInt("ttl"),
                    ts = obj.getLong("ts"),
                    type = MessageType.valueOf(obj.optString("type", "TEXT")),
                    priority = Priority.valueOf(obj.optString("priority", "NORMAL")),
                    epoch = obj.optLong("epoch", 0L),
                    payload = obj.getString("payload"),
                    senderPubKey = obj.optString("senderPubKey", ""),
                    signature = obj.optString("signature", ""),
                    appVersion = obj.optInt("appVersion", 0),
                    channel = obj.optString("channel", ChannelsAndAnonymity.DEFAULT_CHANNEL),
                    anonymous = obj.optBoolean("anonymous", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
