package com.offline.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Simple JSON-file based persistence so the outbox, seen-ids, and chat history
 * survive an app kill / phone restart. This matters a lot for store-and-forward:
 * a phone might carry messages for hours (bus/train ride between two cities)
 * and the app or even the OS could kill it in the background during that time.
 *
 * Deliberately not using a full database - message volumes here are small
 * (protest-scale chat, not enterprise scale) so flat JSON files are simpler
 * and easier to inspect/debug.
 *
 * Supports a "profile" prefix so the real chat history and a duress/decoy
 * history (see MainActivity's lock screen) live in completely separate files
 * on disk - entering the duress PIN never touches the real profile's files.
 */
object PersistentStore {

    private const val PINNED_KEYS_FILE = "mesh_pinned_keys.json"

    private lateinit var dir: File
    private var initialized = false
    private var profile: String = "main"

    fun init(context: Context, profile: String = "main") {
        dir = context.filesDir
        this.profile = profile
        initialized = true
    }

    private fun name(base: String) = if (profile == "main") base else "${profile}_$base"

    private fun readFile(fileName: String): String? {
        val f = File(dir, fileName)
        return if (f.exists()) f.readText() else null
    }

    private fun writeFile(fileName: String, content: String) {
        File(dir, fileName).writeText(content)
    }

    fun loadOutbox(): MutableList<MeshMessage> {
        val text = readFile(name("mesh_outbox.json")) ?: return mutableListOf()
        val arr = JSONArray(text)
        val list = mutableListOf<MeshMessage>()
        for (i in 0 until arr.length()) {
            MeshMessage.fromJson(arr.getString(i))?.let { list.add(it) }
        }
        return list
    }

    fun saveOutbox(messages: List<MeshMessage>) {
        val arr = JSONArray()
        for (m in messages) arr.put(m.toJson())
        writeFile(name("mesh_outbox.json"), arr.toString())
    }

    fun loadSeenIds(): MutableSet<String> {
        val text = readFile(name("mesh_seen_ids.json")) ?: return mutableSetOf()
        val arr = JSONArray(text)
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) set.add(arr.getString(i))
        return set
    }

    fun saveSeenIds(ids: Collection<String>) {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        writeFile(name("mesh_seen_ids.json"), arr.toString())
    }

    fun loadDisplayed(): MutableList<MeshMessage> {
        val text = readFile(name("mesh_displayed.json")) ?: return mutableListOf()
        val arr = JSONArray(text)
        val list = mutableListOf<MeshMessage>()
        for (i in 0 until arr.length()) {
            MeshMessage.fromJson(arr.getString(i))?.let { list.add(it) }
        }
        return list
    }

    fun saveDisplayed(messages: List<MeshMessage>) {
        val arr = JSONArray()
        for (m in messages) arr.put(m.toJson())
        writeFile(name("mesh_displayed.json"), arr.toString())
    }

    /** TOFU-pinned sender public keys - senderId -> Base64 public key. Not profile-scoped
     *  on purpose: identity trust is a device-wide concept, not tied to a chat profile. */
    fun loadPinnedKeys(): MutableMap<String, String> {
        val text = readFile(PINNED_KEYS_FILE) ?: return mutableMapOf()
        val obj = JSONObject(text)
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { k -> map[k] = obj.getString(k) }
        return map
    }

    fun savePinnedKeys(keys: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in keys) obj.put(k, v)
        writeFile(PINNED_KEYS_FILE, obj.toString())
    }

    /** Deletes all persisted mesh data files FOR THE CURRENT PROFILE ONLY - used by panic-wipe. */
    fun wipeAll() {
        listOf("mesh_outbox.json", "mesh_seen_ids.json", "mesh_displayed.json").forEach { base ->
            File(dir, name(base)).delete()
        }
    }
}
