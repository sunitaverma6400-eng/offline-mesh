package com.offline.mesh

import android.content.Context
import android.content.SharedPreferences

/**
 * Group sub-channels — one passphrase/group still shares ONE mesh + ONE encryption
 * key, channels are just a label on each [MeshMessage] (see MeshMessage.channel) so
 * the UI can filter the feed. This is intentionally simple/honest:
 *
 *  - NOT a security boundary. Anyone in the group with the passphrase can decrypt
 *    and read every channel's messages - #medic and #legal are for reducing noise
 *    in a busy chat, not for hiding content from other group members.
 *  - Every device still relays every message on every channel regardless of which
 *    channel the person is currently viewing (dropping relay of "channels I'm not
 *    watching" would break store-and-forward for other people watching them).
 *  - Older builds that don't know about channels just show everything mixed
 *    together in one feed (their MeshMessage.fromJson defaults channel to
 *    "general" if the field is missing) - channel filtering degrades gracefully.
 */
object ChannelsAndAnonymity {

    const val DEFAULT_CHANNEL = "general"

    /** Suggested default channels - the person can still type a custom one. */
    val DEFAULT_CHANNELS = listOf("general", "medic", "legal")

    private const val PREFS_NAME = "mesh_channel_prefs"
    private const val KEY_ACTIVE_CHANNEL = "active_channel"
    private const val KEY_KNOWN_CHANNELS = "known_channels"
    private const val KEY_ANON_DEFAULT = "anonymous_default"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Which channel the person currently has selected in the compose box / feed filter. */
    fun getActiveChannel(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_CHANNEL, DEFAULT_CHANNEL) ?: DEFAULT_CHANNEL

    fun setActiveChannel(context: Context, channel: String) {
        val clean = channel.trim().lowercase().ifBlank { DEFAULT_CHANNEL }
        prefs(context).edit().putString(KEY_ACTIVE_CHANNEL, clean).apply()
        rememberChannel(context, clean)
    }

    /** Every channel name this device has seen/used, so the picker can list them
     *  even if someone typed a custom one (e.g. "#supplies"). */
    fun getKnownChannels(context: Context): List<String> {
        val stored = prefs(context).getStringSet(KEY_KNOWN_CHANNELS, null)
        val set = linkedSetOf<String>()
        set.addAll(DEFAULT_CHANNELS)
        stored?.let { set.addAll(it) }
        return set.toList()
    }

    fun rememberChannel(context: Context, channel: String) {
        val current = prefs(context).getStringSet(KEY_KNOWN_CHANNELS, null)?.toMutableSet()
            ?: mutableSetOf()
        current.add(channel)
        prefs(context).edit().putStringSet(KEY_KNOWN_CHANNELS, current).apply()
    }

    /** "Sticky" anonymous toggle default - if the person leaves it on, next message
     *  composed also defaults to anonymous (they can still uncheck per-message). */
    fun getAnonymousDefault(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANON_DEFAULT, false)

    fun setAnonymousDefault(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANON_DEFAULT, value).apply()
    }
}
