package com.offline.mesh

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class EvidenceType { PHOTO, VIDEO }

data class EvidenceItem(
    val id: String,
    val ts: Long,
    val type: EvidenceType,
    val note: String,
    val fileName: String // encrypted blob filename inside the vault's namespace dir
)

/**
 * Encrypted evidence gallery, deliberately kept COMPLETELY SEPARATE from the mesh
 * chat: separate PIN, separate derived encryption key, separate files on disk,
 * separate panic-wipe scope. The point is that incident photos/videos survive
 * even if someone forces a look at the chat, and can be wiped on their own
 * without having to nuke the whole chat history too (or vice versa).
 *
 * Two independent PIN-derived namespaces:
 *  - "real": unlocked by the vault's own Access PIN, holds actual evidence.
 *  - "decoy": unlocked by the vault's own Duress PIN (separate from the chat's
 *    duress PIN - they don't have to match). This namespace is just a second,
 *    genuinely-separate vault that starts empty - entering the duress PIN can
 *    never reveal or touch anything in the "real" namespace.
 *
 * Honest limitation: like CryptoUtils elsewhere in this app, the encryption key
 * is PBKDF2-derived straight from the PIN - there's no hardware-backed secret
 * backing it up. A short/guessable PIN is the weak point, not the crypto.
 */
object EvidenceVaultManager {

    private const val PREFS_NAME = "vault_prefs"
    // Different salts than CryptoUtils' chat salt AND from each other, so the
    // vault's key space never collides with chat keys even if the same secret
    // string were reused as both a group passphrase and a vault PIN.
    private const val SALT_REAL = "OfflineMeshVaultSaltReal_v1"
    private const val SALT_DECOY = "OfflineMeshVaultSaltDecoy_v1"

    const val NAMESPACE_REAL = "real"
    const val NAMESPACE_DECOY = "decoy"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun sha256(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun isAccessPinSet(context: Context): Boolean =
        !prefs(context).getString("vault_access_pin_hash", null).isNullOrEmpty()

    fun setAccessPin(context: Context, pin: String) {
        prefs(context).edit().putString("vault_access_pin_hash", sha256(pin)).apply()
    }

    fun hasDuressPin(context: Context): Boolean =
        !prefs(context).getString("vault_duress_pin_hash", null).isNullOrEmpty()

    fun setDuressPin(context: Context, pin: String) {
        prefs(context).edit().putString("vault_duress_pin_hash", sha256(pin)).apply()
    }

    fun clearDuressPin(context: Context) {
        prefs(context).edit().remove("vault_duress_pin_hash").apply()
    }

    /** Returns NAMESPACE_REAL, NAMESPACE_DECOY, or null (wrong PIN) for [pin]. */
    fun checkPin(context: Context, pin: String): String? {
        if (pin.isEmpty()) return null
        val p = prefs(context)
        val hash = sha256(pin)
        return when {
            hash == p.getString("vault_access_pin_hash", null) -> NAMESPACE_REAL
            hash == p.getString("vault_duress_pin_hash", null) -> NAMESPACE_DECOY
            else -> null
        }
    }

    private fun keyFor(pin: String, namespace: String): ByteArray {
        val salt = if (namespace == NAMESPACE_DECOY) SALT_DECOY else SALT_REAL
        return CryptoUtils.stretchPassphraseWithSalt(pin, salt)
    }

    private fun dir(context: Context, namespace: String): File {
        val d = File(context.filesDir, "evidence_vault_$namespace")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun manifestFile(context: Context, namespace: String) = File(dir(context, namespace), "manifest.enc")

    fun loadManifest(context: Context, pin: String, namespace: String): MutableList<EvidenceItem> {
        val f = manifestFile(context, namespace)
        if (!f.exists()) return mutableListOf()
        val encrypted = f.readText()
        val json = CryptoUtils.decryptWithKey(encrypted, keyFor(pin, namespace)) ?: return mutableListOf()
        val arr = JSONArray(json)
        val list = mutableListOf<EvidenceItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                EvidenceItem(
                    id = o.getString("id"),
                    ts = o.getLong("ts"),
                    type = EvidenceType.valueOf(o.getString("type")),
                    note = o.optString("note", ""),
                    fileName = o.getString("fileName")
                )
            )
        }
        return list
    }

    private fun saveManifest(context: Context, pin: String, namespace: String, items: List<EvidenceItem>) {
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("id", it.id); o.put("ts", it.ts); o.put("type", it.type.name)
            o.put("note", it.note); o.put("fileName", it.fileName)
            arr.put(o)
        }
        val encrypted = CryptoUtils.encryptWithKey(arr.toString(), keyFor(pin, namespace))
        manifestFile(context, namespace).writeText(encrypted)
    }

    /** Encrypts [bytes] at rest and adds it as a new evidence item. Returns the updated list. */
    fun addItem(
        context: Context, pin: String, namespace: String,
        type: EvidenceType, note: String, bytes: ByteArray
    ): MutableList<EvidenceItem> {
        val id = UUID.randomUUID().toString()
        val fileName = "$id.enc"
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val encrypted = CryptoUtils.encryptWithKey(b64, keyFor(pin, namespace))
        File(dir(context, namespace), fileName).writeText(encrypted)
        val items = loadManifest(context, pin, namespace)
        items.add(EvidenceItem(id, System.currentTimeMillis(), type, note, fileName))
        saveManifest(context, pin, namespace, items)
        return items
    }

    fun readItemBytes(context: Context, pin: String, namespace: String, item: EvidenceItem): ByteArray? {
        val f = File(dir(context, namespace), item.fileName)
        if (!f.exists()) return null
        val encrypted = f.readText()
        val b64 = CryptoUtils.decryptWithKey(encrypted, keyFor(pin, namespace)) ?: return null
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    fun deleteItem(context: Context, pin: String, namespace: String, item: EvidenceItem) {
        File(dir(context, namespace), item.fileName).delete()
        val items = loadManifest(context, pin, namespace).filter { it.id != item.id }
        saveManifest(context, pin, namespace, items)
    }

    /** Wipes ONLY [namespace] ("real" or "decoy") - the other namespace, and the
     *  mesh chat's own data entirely, are untouched. */
    fun wipeNamespace(context: Context, namespace: String) {
        dir(context, namespace).deleteRecursively()
    }
}
