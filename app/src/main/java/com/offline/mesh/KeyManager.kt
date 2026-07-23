package com.offline.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Per-device identity used to sign outgoing messages, so a receiver can tell
 * "this really came from the device that has been calling itself X" rather
 * than trusting the sender name/id fields blindly (anyone who knows the group
 * passphrase could otherwise forge those).
 *
 * This is NOT a PKI - there's no central authority vouching for who's who.
 * It's Trust-On-First-Use (TOFU), same trust model as SSH host keys:
 *   - the first time we see a senderId, we remember (pin) the public key
 *     that came with it.
 *   - if a later message claims to be from the same senderId but signs with
 *     a DIFFERENT public key, that's suspicious (either a key reset on their
 *     end, or someone else impersonating that name) and we flag it in the UI
 *     instead of silently trusting it.
 *
 * The actual private key never leaves the Android Keystore (hardware-backed
 * on most devices), so even a full disk copy of the phone can't extract it.
 */
object KeyManager {
    private const val TAG = "KeyManager"
    private const val KEYSTORE_ALIAS = "offline_mesh_identity_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val SIGNATURE_ALGO = "SHA256withECDSA"

    private lateinit var appContext: Context
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        ensureKeyPair()
        PeerTrustStore.init(appContext)
        initialized = true
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun ensureKeyPair() {
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) return
        try {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
            Log.d(TAG, "Generated new device identity keypair")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate identity keypair: ${e.message}")
        }
    }

    /** Our own public key, Base64(X.509 SubjectPublicKeyInfo) - safe to broadcast. */
    fun myPublicKeyBase64(): String {
        return try {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
            val pub = entry?.certificate?.publicKey ?: return ""
            Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Reading public key failed: ${e.message}")
            ""
        }
    }

    /** Signs [data] with our device private key (never leaves the Keystore). */
    fun sign(data: ByteArray): String {
        return try {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.PrivateKeyEntry
            val privateKey = entry?.privateKey ?: return ""
            val sig = Signature.getInstance(SIGNATURE_ALGO)
            sig.initSign(privateKey)
            sig.update(data)
            Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Signing failed: ${e.message}")
            ""
        }
    }

    /** Verifies [signatureBase64] over [data] against a Base64-encoded public key. */
    fun verify(data: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean {
        return try {
            val pubKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(pubKeyBytes)
            val publicKey: PublicKey = KeyFactory.getInstance("EC").generatePublic(keySpec)
            val sig = Signature.getInstance(SIGNATURE_ALGO)
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Canonical bytes we sign/verify over for a message: everything except the
     * signature field itself, so the signature covers id/sender/ttl/ts/type/payload.
     */
    fun signableBytes(msg: MeshMessage): ByteArray {
        return "${msg.id}|${msg.senderId}|${msg.senderName}|${msg.ts}|${msg.type}|${msg.epoch}|${msg.payload}"
            .toByteArray(Charsets.UTF_8)
    }
}

/**
 * Trust-On-First-Use pinning: senderId -> first public key we ever saw for them.
 * Persisted to disk so the pinning survives app restarts.
 */
object PeerTrustStore {
    private val pinned = mutableMapOf<String, String>()
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context
        pinned.putAll(PersistentStore.loadPinnedKeys())
    }

    /**
     * Returns SAME (key matches what we've seen before / first time seeing them),
     * MISMATCH (key differs from what we pinned - possible spoof), or UNSIGNED.
     */
    enum class TrustResult { SAME, MISMATCH, UNSIGNED }

    fun check(senderId: String, publicKeyBase64: String): TrustResult {
        if (publicKeyBase64.isEmpty()) return TrustResult.UNSIGNED
        val existing = pinned[senderId]
        return when {
            existing == null -> {
                pinned[senderId] = publicKeyBase64
                PersistentStore.savePinnedKeys(pinned)
                TrustResult.SAME
            }
            existing == publicKeyBase64 -> TrustResult.SAME
            else -> TrustResult.MISMATCH
        }
    }

    fun wipeAll() {
        pinned.clear()
        PersistentStore.savePinnedKeys(pinned)
    }
}
