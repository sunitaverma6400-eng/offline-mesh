package com.offline.mesh

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-windowed key rotation on top of the shared, PBKDF2-stretched group key.
 *
 * HONEST LIMITATION (read before assuming more than this gives you):
 * This is NOT full forward secrecy like Signal's Double Ratchet. Because every
 * device only shares one static passphrase (no per-pair Diffie-Hellman
 * handshake - that would need pairwise sessions between every pair of mesh
 * nodes, which is a much bigger redesign), anyone who knows the passphrase
 * can always re-derive any epoch's key just by re-running this same function.
 * So this does NOT protect you if the passphrase itself leaks.
 *
 * What it DOES give you:
 * - Ciphertext is bucketed into ~6-hour "epochs" with a different derived key
 *   each time, instead of one key forever, so old traffic isn't all decryptable
 *   with a single key sitting in memory.
 * - It limits how much a passive recorder of BLE/Wi-Fi Direct traffic can
 *   decrypt in one shot if they later obtain the passphrase, since each
 *   epoch needs its own (cheap) re-derivation - a speed bump for casual
 *   bulk analysis, not cryptographic forward secrecy.
 *
 * If real forward secrecy matters for your use case, the honest answer is:
 * rotate the group passphrase itself periodically (re-share via QR) and treat
 * each passphrase as a fully separate group/history.
 */
object RatchetManager {

    // Roughly 6 hours per epoch. Devices need only loosely-synced clocks
    // (phone clocks are auto-synced via cell/NTP even without data - and even
    // when they drift, we keep a small window of neighboring epochs to try).
    private const val EPOCH_LENGTH_MS = 6L * 60 * 60 * 1000

    // How many neighboring epochs (current +/- this many) we'll attempt when
    // decrypting, to tolerate modest clock drift between devices.
    private const val EPOCH_TOLERANCE = 1

    /** Which epoch "now" falls into. Deterministic across devices with roughly synced clocks. */
    fun currentEpoch(): Long = System.currentTimeMillis() / EPOCH_LENGTH_MS

    /** Mixes an epoch number into an already-stretched key to get that epoch's AES key. */
    fun mixEpoch(stretchedKey: ByteArray, epoch: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(stretchedKey, "HmacSHA256"))
        return mac.doFinal("offline-mesh-epoch:$epoch".toByteArray(Charsets.UTF_8))
    }

    /** Try to decrypt [payload] against the current epoch and a small tolerance window either side. */
    fun decryptWithTolerance(payload: String, stretchedKey: ByteArray, msgEpoch: Long): String? {
        for (delta in -EPOCH_TOLERANCE..EPOCH_TOLERANCE) {
            val key = mixEpoch(stretchedKey, msgEpoch + delta)
            val result = CryptoUtils.decryptWithKey(payload, key)
            if (result != null) return result
        }
        return null
    }
}
