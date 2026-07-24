package com.offline.mesh

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM encryption for message content.
 *
 * Two layers on top of the raw cipher:
 *  1. [stretchPassphrase] runs the human-entered group passphrase through
 *     PBKDF2 (slow, salted) so a weak/guessable passphrase isn't trivially
 *     brute-forced from captured ciphertext.
 *  2. [RatchetManager] takes that stretched key and mixes in a time-epoch
 *     label (see that file for exactly what this does and does NOT buy you -
 *     short version: it's key rotation, not full forward secrecy).
 *
 * NOTE: this whole scheme protects message content from casual BLE/Wi-Fi
 * eavesdropping, it is NOT a substitute for a full protocol like Signal's.
 * Treat it as "reasonably private", not "unbreakable".
 */
object CryptoUtils {

    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val SALT = "OfflineMeshStaticSaltV1" // fixed salt is fine here since the
    // passphrase itself is the real secret and is shared out-of-band per group

    /** PBKDF2-stretched key bytes derived from the human passphrase. Slow on purpose. */
    fun stretchPassphrase(passphrase: String): ByteArray = stretchPassphraseWithSalt(passphrase, SALT)

    /** Same as [stretchPassphrase] but with a caller-chosen salt, so a different
     *  feature (e.g. EvidenceVaultManager) can derive keys from a PIN/passphrase
     *  in a completely separate key space that never overlaps with the group
     *  chat encryption keys, even if someone reuses the same secret string. */
    fun stretchPassphraseWithSalt(passphrase: String, salt: String): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(
            passphrase.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            ITERATIONS,
            KEY_LENGTH
        )
        return factory.generateSecret(spec).encoded
    }

    /** Returns Base64("nonce" + "ciphertext") using a raw AES-256 key (e.g. from RatchetManager). */
    fun encryptWithKey(plainText: String, keyBytes: ByteArray): String {
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = nonce + cipherBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** Reverses [encryptWithKey]. Returns null if the key is wrong / data is corrupt. */
    fun decryptWithKey(payload: String, keyBytes: ByteArray): String? {
        return try {
            val key = SecretKeySpec(keyBytes, "AES")
            val combined = Base64.decode(payload, Base64.NO_WRAP)
            val nonce = combined.copyOfRange(0, 12)
            val cipherBytes = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Convenience: encrypt directly against the CURRENT epoch key for [passphrase].
     *  Text is Deflate-compressed BEFORE encryption (compressing ciphertext afterward
     *  does nothing - it's already high-entropy). Chat text / JSON-ish payloads
     *  typically shrink 30-60%, which matters a lot over BLE's ~500-byte writes. */
    fun encryptForNow(plainText: String, passphrase: String): Pair<String, Long> {
        val epoch = RatchetManager.currentEpoch()
        val stretched = stretchPassphrase(passphrase)
        val epochKey = RatchetManager.mixEpoch(stretched, epoch)
        val compressedB64 = Base64.encodeToString(compress(plainText), Base64.NO_WRAP)
        return Pair(encryptWithKey(compressedB64, epochKey), epoch)
    }

    /** Convenience: decrypt a message tagged with [msgEpoch], tolerating small clock drift,
     *  then reverses the Deflate compression applied by [encryptForNow]. Returns null on
     *  any failure (wrong key, corrupt data, or a message from a build old enough that it
     *  was never compressed in the first place - see README's version-mismatch warning). */
    fun decryptForEpoch(payload: String, passphrase: String, msgEpoch: Long): String? {
        val stretched = stretchPassphrase(passphrase)
        val decrypted = RatchetManager.decryptWithTolerance(payload, stretched, msgEpoch) ?: return null
        return try {
            decompress(Base64.decode(decrypted, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    private fun compress(text: String): ByteArray {
        val input = text.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val buffer = ByteArray(2048)
        val out = ByteArrayOutputStream(input.size)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun decompress(bytes: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val buffer = ByteArray(2048)
        val out = ByteArrayOutputStream(bytes.size * 2)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && inflater.needsInput()) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toString("UTF-8")
    }
}
