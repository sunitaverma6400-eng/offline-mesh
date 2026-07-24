package com.offline.mesh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * "Aapka phone hi critical relay point ban gaya hai" alert.
 *
 * A phone is treated as a "critical relay point" using a simple, honest local
 * heuristic (no phone can see the true global mesh topology - see
 * MeshTopologyView's doc comment for why): if THIS phone currently has several
 * peers connected at once (so it's actively bridging multiple devices' messages
 * to each other right now) AND its battery is low, warn before it dies and the
 * mesh loses that bridge. This deliberately does NOT try to detect "am I the
 * ONLY path between two clusters" - that would need topology knowledge no
 * single phone has - it's a conservative proxy: "several people currently rely
 * on me + I'm about to die" is worth a warning even if it sometimes fires when
 * there was another path anyway. Better a few unnecessary nudges than the mesh
 * silently losing a bridge node.
 */
object BatteryRelayMonitor {

    private const val CHANNEL_ID = "offline_mesh_battery_relay"
    private const val NOTIF_ID = 1002

    // Below this many simultaneously-connected peers, we don't consider this
    // phone a meaningful relay hub (a single 1:1 chat dying isn't "the mesh
    // losing a bridge", it's just two friends' link dropping).
    private const val RELAY_PEER_THRESHOLD = 3

    // Battery pct at/below which we consider it worth an interrupt.
    private const val CRITICAL_BATTERY_PCT = 15

    // Don't spam - only re-notify if battery has dropped further since last warning,
    // or if it's been a while.
    private var lastWarnedPct: Int = 101
    private var lastWarnedAtMs: Long = 0L
    private const val RENOTIFY_INTERVAL_MS = 20L * 60 * 1000 // 20 min

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Mesh relay battery alerts", NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Warns when your phone is a busy relay point and battery is low"
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Call periodically (BleMeshService's existing periodic check loop is a good
     * spot - it already re-evaluates battery for adaptive-TTL purposes) with the
     * current connected-peer count. Fires a high-priority notification at most
     * once per [RENOTIFY_INTERVAL_MS] unless battery has dropped further.
     */
    fun check(context: Context, connectedPeerCount: Int) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct < 0) return // property unsupported on this device

        val isCriticalRelay = connectedPeerCount >= RELAY_PEER_THRESHOLD && pct <= CRITICAL_BATTERY_PCT
        if (!isCriticalRelay) {
            // Reset so we warn again next time it becomes critical (e.g. after a recharge dip/rise cycle).
            if (pct > CRITICAL_BATTERY_PCT) lastWarnedPct = 101
            return
        }

        val now = System.currentTimeMillis()
        val droppedFurther = pct < lastWarnedPct
        val staleEnough = now - lastWarnedAtMs > RENOTIFY_INTERVAL_MS
        if (!droppedFurther && !staleEnough) return

        lastWarnedPct = pct
        lastWarnedAtMs = now
        ensureChannel(context)

        val text = "Battery $pct% hai aur abhi $connectedPeerCount devices aapke phone se " +
            "mesh mein connected hain — agar aapka phone band ho gaya to unka connection toot sakta hai. " +
            "Jaldi charge dhoondo ya kisi aur ko relay lene do."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Aap critical relay point ho — battery $pct%")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }
}
