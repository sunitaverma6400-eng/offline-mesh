package com.offline.mesh

import android.util.Base64

/**
 * Rolling stats on how "heavy" mesh traffic is, for the debug/health screen.
 *
 * Honest note on "compression ratio" naming: OfflineMesh does not currently run an
 * explicit compression pass (e.g. gzip) on message payloads - text is AES-GCM
 * encrypted then Base64-encoded for transport, which actually makes it ~33%
 * *bigger* than the plaintext, not smaller. So instead of a fake number, this
 * class reports the numbers that actually matter to an organizer trying to judge
 * mesh efficiency:
 *
 *  - average encoded payload size (what's actually going over BLE/Wi-Fi Direct)
 *  - average plaintext size (what the person typed/attached)
 *  - the resulting overhead ratio (encoded / plaintext) - this is the honest
 *    analogue of a "compression ratio" display: for text messages it settles
 *    around 1.3-1.4x due to Base64 + AES-GCM's 16-byte auth tag + nonce, which
 *    is normal and NOT something to "optimize away" without weakening crypto.
 *
 * If real payload compression (e.g. deflate before encrypt) is added later, this
 * same class is where the "before/after compression" ratio should get wired in -
 * see [recordCompressed] which is already plumbed for that, just unused today.
 */
object CompressionStats {

    private var sentCount = 0L
    private var sentEncodedBytes = 0L
    private var sentPlainBytes = 0L

    private var receivedCount = 0L
    private var receivedEncodedBytes = 0L

    // Reserved for when/if real pre-encryption compression is added.
    private var compressedBeforeBytes = 0L
    private var compressedAfterBytes = 0L

    @Synchronized
    fun recordSent(msg: MeshMessage, plaintextBytes: Int) {
        sentCount++
        sentEncodedBytes += msg.approxSizeBytes()
        sentPlainBytes += plaintextBytes
    }

    @Synchronized
    fun recordReceived(msg: MeshMessage) {
        receivedCount++
        receivedEncodedBytes += msg.approxSizeBytes()
    }

    @Synchronized
    fun recordCompressed(beforeBytes: Int, afterBytes: Int) {
        compressedBeforeBytes += beforeBytes
        compressedAfterBytes += afterBytes
    }

    data class Snapshot(
        val sentCount: Long,
        val avgSentEncodedBytes: Int,
        val avgSentPlainBytes: Int,
        val overheadRatio: Double,
        val receivedCount: Long,
        val avgReceivedEncodedBytes: Int,
        val compressionRatio: Double? // null until recordCompressed() is ever used
    )

    @Synchronized
    fun snapshot(): Snapshot {
        val avgSentEncoded = if (sentCount > 0) (sentEncodedBytes / sentCount).toInt() else 0
        val avgSentPlain = if (sentCount > 0) (sentPlainBytes / sentCount).toInt() else 0
        val overhead = if (sentPlainBytes > 0) sentEncodedBytes.toDouble() / sentPlainBytes else 0.0
        val avgReceivedEncoded = if (receivedCount > 0) (receivedEncodedBytes / receivedCount).toInt() else 0
        val compressionRatio = if (compressedAfterBytes > 0)
            compressedBeforeBytes.toDouble() / compressedAfterBytes else null
        return Snapshot(
            sentCount, avgSentEncoded, avgSentPlain, overhead,
            receivedCount, avgReceivedEncoded, compressionRatio
        )
    }

    /** Human-readable one-liner for the mesh health / debug screen. */
    fun summaryText(): String {
        val s = snapshot()
        val ratioTxt = String.format("%.2fx", s.overheadRatio)
        return "Sent: ${s.sentCount} msgs, avg ${s.avgSentEncodedBytes}B on-wire " +
            "(${s.avgSentPlainBytes}B plain, ${ratioTxt} overhead)  |  " +
            "Received: ${s.receivedCount} msgs, avg ${s.avgReceivedEncodedBytes}B"
    }

    /** Rough plaintext-size estimate for a text message before AES-GCM+Base64. */
    fun plaintextSizeOf(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** Rough plaintext-size estimate for already-decoded binary (image/audio) bytes. */
    fun plaintextSizeOfBase64(base64: String): Int =
        try { Base64.decode(base64, Base64.NO_WRAP).size } catch (e: Exception) { base64.length }
}
