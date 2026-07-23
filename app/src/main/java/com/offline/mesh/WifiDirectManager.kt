package com.offline.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Wi-Fi Direct transport. Compared to BLE this gives:
 *  - much longer range (up to ~100-200m outdoors vs ~10-50m for BLE)
 *  - much higher throughput (good enough for photos, not just text)
 *
 * Trade-off: Wi-Fi Direct groups are a star topology (one "group owner" +
 * several "clients"), not a free-form mesh like BLE. We still get mesh-like
 * behavior overall because BLE keeps doing its own flooding in parallel, and
 * both transports feed into the same de-duplicated message pipeline.
 *
 * Every message received here (from BLE or WiFi Direct) is also rebroadcast
 * to every other connected Wi-Fi Direct peer, so a single group already
 * relays for you.
 */
class WifiDirectManager(
    private val context: Context,
    private val onLineReceived: (String) -> Unit,
    private val onPeerCountChanged: (Int) -> Unit,
    private val onPeerConnected: () -> Unit
) {
    companion object {
        private const val TAG = "WifiDirectManager"
        private const val PORT = 8988
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private var serverSocket: ServerSocket? = null
    private val clientWriters = CopyOnWriteArrayList<OutputStream>()
    private var clientSocketToOwner: Socket? = null
    private var clientWriterToOwner: OutputStream? = null

    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isGroupOwner = false

    @SuppressLint("MissingPermission")
    fun start() {
        val mgr = manager ?: return
        channel = mgr.initialize(context, context.mainLooper, null)
        registerReceiver()
        discoverPeers()
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager?.requestPeers(channel) { peers ->
                        val device = peers.deviceList.firstOrNull()
                        if (device != null && device.status == WifiP2pDevice.AVAILABLE) {
                            connectTo(device)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager?.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed && info.isGroupOwner) {
                            isGroupOwner = true
                            startServerSocket()
                        } else if (info.groupFormed) {
                            isGroupOwner = false
                            connectToOwner(info.groupOwnerAddress.hostAddress ?: return@requestConnectionInfo)
                        }
                    }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (!receiverRegistered) {
            context.registerReceiver(broadcastReceiver, intentFilter)
            receiverRegistered = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Peer discovery started") }
            override fun onFailure(reason: Int) { Log.w(TAG, "Peer discovery failed: $reason") }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Connect requested to ${device.deviceName}") }
            override fun onFailure(reason: Int) { Log.w(TAG, "Connect failed: $reason") }
        })
    }

    // ---- Group owner: run a small TCP server, one thread per connected client ----

    private fun startServerSocket() {
        if (serverSocket != null) return
        executor.execute {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(PORT))
                serverSocket = server
                while (true) {
                    val socket = server.accept()
                    handleClientSocket(socket)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server socket ended: ${e.message}")
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        val writer = socket.getOutputStream()
        clientWriters.add(writer)
        onPeerCountChanged(clientWriters.size)
        onPeerConnected()
        executor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    onLineReceived(line)
                    relayToClientsExcept(writer, line)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Client disconnected: ${e.message}")
            } finally {
                clientWriters.remove(writer)
                onPeerCountChanged(clientWriters.size)
                socket.close()
            }
        }
    }

    private fun relayToClientsExcept(exclude: OutputStream?, line: String) {
        for (w in clientWriters) {
            if (w === exclude) continue
            try {
                w.write((line + "\n").toByteArray(Charsets.UTF_8))
                w.flush()
            } catch (e: Exception) {
                Log.d(TAG, "Write to client failed: ${e.message}")
            }
        }
    }

    // ---- Client (non-owner): connect once to the group owner's socket ----

    private fun connectToOwner(ownerIp: String) {
        if (clientSocketToOwner != null) return
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ownerIp, PORT), 8000)
                clientSocketToOwner = socket
                clientWriterToOwner = socket.getOutputStream()
                onPeerCountChanged(1)
                onPeerConnected()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    onLineReceived(line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection to owner failed: ${e.message}")
            } finally {
                clientSocketToOwner = null
                clientWriterToOwner = null
                onPeerCountChanged(0)
            }
        }
    }

    /** Send a raw JSON line (a MeshMessage.toJson()) to whatever Wi-Fi Direct peers we have. */
    fun send(line: String) {
        executor.execute {
            try {
                if (isGroupOwner) {
                    relayToClientsExcept(null, line)
                } else {
                    clientWriterToOwner?.let {
                        it.write((line + "\n").toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Send failed: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
            clientSocketToOwner?.close()
            if (receiverRegistered) {
                context.unregisterReceiver(broadcastReceiver)
                receiverRegistered = false
            }
            manager?.let { if (Build.VERSION.SDK_INT >= 27) it.removeGroup(channel, null) }
        } catch (e: Exception) {
            Log.w(TAG, "Stop error: ${e.message}")
        }
    }
}
