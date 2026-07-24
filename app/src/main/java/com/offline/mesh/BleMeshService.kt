package com.offline.mesh

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Foreground service that turns the phone into one node of the mesh, over TWO
 * transports in parallel:
 *
 *  - BLE (short range, ~10-50m, low power, works with just Bluetooth on)
 *  - Wi-Fi Direct (longer range, ~100-200m, higher throughput, needed for images)
 *
 * Both transports feed into the same de-duplicated, TTL-limited message
 * pipeline, so the app doesn't care which one delivered a message.
 *
 * Also handles: per-device message signing/verification, epoch-based key
 * rotation, adaptive TTL based on how dense the mesh currently looks, and
 * battery-aware scan/advertise tuning.
 */
class BleMeshService : Service() {

    companion object {
        private const val TAG = "BleMeshService"
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val MESSAGE_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        // Standard Bluetooth SIG "Client Characteristic Configuration" descriptor -
        // this is how a GATT client subscribes to notifications from a server.
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val NOTIF_CHANNEL_ID = "offline_mesh_channel"
        private const val NOTIF_ID = 1001

        const val ACTION_MESSAGE_RECEIVED = "com.offline.mesh.MESSAGE_RECEIVED"
        const val EXTRA_MESSAGE_JSON = "message_json"
        const val EXTRA_TRUST = "trust_result" // one of PeerTrustStore.TrustResult names
        const val ACTION_PEER_COUNT_CHANGED = "com.offline.mesh.PEER_COUNT_CHANGED"
        const val EXTRA_PEER_COUNT = "peer_count"
        const val EXTRA_PENDING_COUNT = "pending_count"
        const val EXTRA_PROFILE = "profile" // "main" or "duress" - which storage namespace to load

        // Someone else started a roll call - UI should prompt "confirm you're safe".
        const val ACTION_ROLLCALL_PROMPT = "com.offline.mesh.ROLLCALL_PROMPT"
        const val EXTRA_ROLLCALL_ROUND_ID = "rollcall_round_id"
        // A response came in for a round WE organized - UI should refresh its live tally.
        const val ACTION_ROLLCALL_UPDATE = "com.offline.mesh.ROLLCALL_UPDATE"

        // A new pinned dispersal-point broadcast arrived - UI should update the fixed banner.
        const val ACTION_PIN_UPDATED = "com.offline.mesh.PIN_UPDATED"
        const val EXTRA_PIN_TEXT = "pin_text"
        const val EXTRA_PIN_SENDER = "pin_sender"

        // A DELIVERY_ACK came back for a message WE sent - UI should mark it "✓ delivered".
        // Only fired for the specific message id being acked, never rendered as its own bubble.
        const val ACTION_DELIVERY_ACK = "com.offline.mesh.DELIVERY_ACK"
        const val EXTRA_ACKED_MESSAGE_ID = "acked_message_id"

        // Message types worth confirming receipt of - roll call/pin/ack messages have their
        // own tracking already (round tally, replace-on-new-pin) so they're excluded here.
        val ACKABLE_TYPES = setOf(
            MessageType.TEXT, MessageType.IMAGE, MessageType.AUDIO,
            MessageType.LOCATION, MessageType.DETAINED_ALERT
        )

        // Low/high water marks for adaptive TTL: fewer distinct senders around
        // recently => we're probably a sparser/more spread-out mesh, so we push
        // TTL up to try to reach farther. Lots of distinct senders => dense
        // cluster, so we can afford a lower TTL and cut down on radio congestion.
        private const val SPARSE_SENDER_THRESHOLD = 2
        private const val DENSE_SENDER_THRESHOLD = 8
        private const val TTL_SPARSE = 25
        private const val TTL_NORMAL = MeshMessage.DEFAULT_TTL
        private const val TTL_DENSE = 12

        private const val LOW_BATTERY_PCT = 20

        // How often we re-check mesh density (for congestion throttling) and the
        // dead-man's-switch timer, while the foreground service is alive.
        private const val PERIODIC_CHECK_INTERVAL_MS = 5L * 60 * 1000
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): BleMeshService = this@BleMeshService
    }

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    // Connected peer GATT clients (we are central) keyed by device address
    private val connectedPeers = mutableMapOf<String, BluetoothGatt>()

    // Devices that connected to US as their GATT server AND subscribed to
    // notifications (we are peripheral here) - keyed by device address. This is
    // what makes the link two-way: connectedPeers lets us WRITE to a peer when
    // we're the client; this map lets us NOTIFY a peer when we're the server.
    // Without both, only whichever side happened to become "client" for a given
    // pair of phones can actually get its messages through - see startGattServer.
    private val subscribedCentrals = mutableMapOf<String, BluetoothDevice>()

    // BLE allows only ONE in-flight GATT operation per connection at a time -
    // calling writeCharacteristic/notifyCharacteristicChanged again before the
    // previous one's completion callback fires silently drops it (no exception,
    // no error - it just doesn't arrive). Both write directions get queued and
    // pumped one-at-a-time via the completion callbacks below, so a burst of
    // several queued messages (e.g. flushing to a peer who just connected)
    // actually all get delivered instead of just the first one.
    private val clientWriteQueues = mutableMapOf<String, ArrayDeque<ByteArray>>() // key: device address
    private val clientWriteInFlight = mutableMapOf<String, Boolean>()
    private val serverNotifyQueues = mutableMapOf<String, ArrayDeque<ByteArray>>() // key: device address
    private val serverNotifyInFlight = mutableMapOf<String, Boolean>()

    private var wifiDirect: WifiDirectManager? = null
    @Volatile private var wifiPeerCount = 0

    @Volatile private var lowPowerMode = false
    private var batteryReceiverRegistered = false

    // Separate from lowPowerMode (which is battery-driven): when the mesh gets dense
    // (lots of distinct senders heard recently), we ALSO drop to low-power scan/advertise
    // settings purely to cut radio congestion - this stacks with adaptive TTL, same idea.
    @Volatile private var congestionMode = false

    private val periodicHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val periodicChecker = object : Runnable {
        override fun run() {
            checkMeshCongestion()
            checkDeadManSwitch()
            BatteryRelayMonitor.check(applicationContext, connectedPeers.size + subscribedCentrals.size)
            periodicHandler.postDelayed(this, PERIODIC_CHECK_INTERVAL_MS)
        }
    }

    /**
     * Nodes for [MeshTopologyView]. Honest approximation, documented in that view's
     * own doc comment: there's no address->senderId map kept for GATT links (a link
     * can carry messages from several senders being relayed through it), so
     * "connected now" here means "a message from this senderId was decrypted in the
     * last ~3 minutes", not "there is definitely a live GATT link to them this exact
     * second". Good enough for the visualizer's purpose (rough liveness), not meant
     * for anything that needs byte-exact link state.
     */
    fun getTopologyNodes(): List<MeshTopologyView.Node> {
        val now = System.currentTimeMillis()
        val recentWindowMs = 3L * 60 * 1000
        return ContactGraph.topContacts(20).map { c ->
            MeshTopologyView.Node(
                id = c.peerId,
                label = c.peerLabel,
                connectedNow = (now - c.lastSeenMs) < recentWindowMs
            )
        }
    }

    var myUserId: String = ""
    var myUserName: String = "Anonymous"
    var groupPassphrase: String = "changeme"
    var profile: String = "main"

    override fun onCreate() {
        super.onCreate()
        KeyManager.init(applicationContext)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()
        registerBatteryReceiver()
        periodicHandler.postDelayed(periodicChecker, PERIODIC_CHECK_INTERVAL_MS)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        profile = intent?.getStringExtra(EXTRA_PROFILE) ?: profile
        MessageStore.init(applicationContext, profile)
        startForeground(NOTIF_ID, buildNotification())
        startMesh()
        return START_STICKY
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startMesh() {
        val adapter = bluetoothAdapter
        if (adapter != null && adapter.isEnabled) {
            val hasBlePerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hasPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) &&
                hasPermission(android.Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            } else true

            if (hasBlePerms) {
                startGattServer()
                startAdvertising()
                startScanning()
            } else {
                Log.w(TAG, "Missing BLE runtime permissions")
            }
        } else {
            Log.w(TAG, "Bluetooth is off or unavailable - BLE transport skipped")
        }

        startWifiDirect()
    }

    // ---------------- Battery-aware scan/advertise tuning ----------------

    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Same Android 14 mandatory-flag requirement as WifiDirectManager's
        // receiver - see the comment there. Missing this crashes the app on
        // Android 14 the moment the mesh service starts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        batteryReceiverRegistered = true
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                BatteryManager.BATTERY_STATUS_CHARGING
            if (level < 0 || scale <= 0) return
            val pct = (level * 100) / scale
            val shouldBeLowPower = pct < LOW_BATTERY_PCT && !charging
            if (shouldBeLowPower != lowPowerMode) {
                lowPowerMode = shouldBeLowPower
                Log.d(TAG, "Battery ${pct}% charging=$charging -> lowPowerMode=$lowPowerMode, restarting BLE radio")
                restartAdvertisingAndScanning()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertisingAndScanning() {
        val adapter = bluetoothAdapter ?: return
        if (adapter.isEnabled) {
            try { scanner?.stopScan(scanCallback) } catch (e: Exception) { }
            try { advertiser?.stopAdvertising(advertiseCallback) } catch (e: Exception) { }
            startAdvertising()
            startScanning()
        }
    }

    // ---------------- Wi-Fi Direct transport ----------------

    private fun startWifiDirect() {
        val canUseWifiDirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!canUseWifiDirect) {
            Log.w(TAG, "Missing Wi-Fi Direct permissions")
            return
        }
        wifiDirect = WifiDirectManager(
            context = applicationContext,
            onLineReceived = { line -> handleIncomingMessageJson(line) },
            onPeerCountChanged = { count ->
                wifiPeerCount = count
                broadcastPeerCount()
            },
            onPeerConnected = { flushOutboxOverWifiDirect() }
        )
        wifiDirect?.start()
    }

    private fun flushOutboxOverWifiDirect() {
        for (msg in MessageStore.currentOutbox()) {
            wifiDirect?.send(msg.toJson())
        }
    }

    // ---------------- Serialized GATT write queues (see field comments above) ----------------

    @SuppressLint("MissingPermission")
    private fun enqueueClientWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, bytes: ByteArray) {
        val address = gatt.device.address
        synchronized(clientWriteQueues) {
            val queue = clientWriteQueues.getOrPut(address) { ArrayDeque() }
            queue.addLast(bytes)
            if (clientWriteInFlight[address] != true) {
                pumpClientWriteQueue(gatt, char)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pumpClientWriteQueue(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        synchronized(clientWriteQueues) {
            val queue = clientWriteQueues[address]
            val next = queue?.removeFirstOrNull()
            if (next == null) {
                clientWriteInFlight[address] = false
                return
            }
            clientWriteInFlight[address] = true
            char.value = next
            val started = gatt.writeCharacteristic(char)
            if (!started) {
                // Couldn't even start this write (e.g. connection hiccup) - don't
                // get stuck waiting for a callback that will never come; try the
                // next queued item instead of stalling the whole queue forever.
                clientWriteInFlight[address] = false
                pumpClientWriteQueue(gatt, char)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueServerNotify(device: BluetoothDevice, char: BluetoothGattCharacteristic, bytes: ByteArray) {
        val address = device.address
        synchronized(serverNotifyQueues) {
            val queue = serverNotifyQueues.getOrPut(address) { ArrayDeque() }
            queue.addLast(bytes)
            if (serverNotifyInFlight[address] != true) {
                pumpServerNotifyQueue(device, char)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pumpServerNotifyQueue(device: BluetoothDevice, char: BluetoothGattCharacteristic) {
        val address = device.address
        synchronized(serverNotifyQueues) {
            val queue = serverNotifyQueues[address]
            val next = queue?.removeFirstOrNull()
            if (next == null) {
                serverNotifyInFlight[address] = false
                return
            }
            serverNotifyInFlight[address] = true
            char.value = next
            val started = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
            if (!started) {
                serverNotifyInFlight[address] = false
                pumpServerNotifyQueue(device, char)
            }
        }
    }

    // ---------------- GATT SERVER (peripheral side: receives writes from peers) ----------------

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val messageChar = BluetoothGattCharacteristic(
            MESSAGE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        // Required for any client to be able to subscribe to notifications on
        // this characteristic - see gattServerCallback.onDescriptorWriteRequest.
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        messageChar.addDescriptor(cccd)
        service.addCharacteristic(messageChar)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Peer connected to our GATT server: ${device.address}")
                broadcastPeerCount()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCentrals.remove(device.address)
                synchronized(serverNotifyQueues) {
                    serverNotifyQueues.remove(device.address)
                    serverNotifyInFlight.remove(device.address)
                }
                broadcastPeerCount()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MESSAGE_CHAR_UUID) {
                val json = String(value, Charsets.UTF_8)
                handleIncomingMessageJson(json)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabling = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabling) {
                    subscribedCentrals[device.address] = device
                    Log.d(TAG, "Peer subscribed for notifications: ${device.address}")
                    flushOutboxToSubscriber(device)
                } else {
                    subscribedCentrals.remove(device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val service = gattServer?.getService(SERVICE_UUID) ?: return
            val char = service.getCharacteristic(MESSAGE_CHAR_UUID) ?: return
            pumpServerNotifyQueue(device, char)
        }
    }

    // ---------------- ADVERTISING (so peers can find us) ----------------

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val throttled = lowPowerMode || congestionMode
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (throttled) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            )
            .setTxPowerLevel(
                if (throttled) AdvertiseSettings.ADVERTISE_TX_POWER_LOW
                else AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertise failed: $errorCode")
        }
    }

    // ---------------- SCANNING (finding peers) ----------------

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (lowPowerMode || congestionMode) ScanSettings.SCAN_MODE_LOW_POWER
                else ScanSettings.SCAN_MODE_LOW_LATENCY
            )
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!connectedPeers.containsKey(device.address)) {
                connectToPeer(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
        }
    }

    // ---------------- GATT CLIENT (central side: we write messages out to peers) ----------------

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BluetoothDevice) {
        device.connectGatt(this, false, object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedPeers[device.address] = gatt
                    gatt.discoverServices()
                    broadcastPeerCount()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedPeers.remove(device.address)
                    synchronized(clientWriteQueues) {
                        clientWriteQueues.remove(device.address)
                        clientWriteInFlight.remove(device.address)
                    }
                    gatt.close()
                    broadcastPeerCount()
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // Peer is ready - flush anything queued in our outbox to them.
                // Only text-sized payloads go over BLE; images wait for Wi-Fi Direct.
                flushOutboxTo(gatt)

                // Subscribe to notifications so THIS peer can push messages back to
                // us over the same connection, even though we're the one who
                // initiated it (we're the GATT client here). Without this, the
                // link only works one-way: us -> them. See gattServerCallback on
                // the other end for the matching half of this.
                val service = gatt.getService(SERVICE_UUID) ?: return
                val char = service.getCharacteristic(MESSAGE_CHAR_UUID) ?: return
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == MESSAGE_CHAR_UUID) {
                    val json = String(characteristic.value, Charsets.UTF_8)
                    handleIncomingMessageJson(json)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid == MESSAGE_CHAR_UUID) {
                    pumpClientWriteQueue(gatt, characteristic)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun flushOutboxTo(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(MESSAGE_CHAR_UUID) ?: return
        for (msg in MessageStore.currentOutbox()) { // already URGENT-first ordered
            if (msg.approxSizeBytes() > MeshMessage.BLE_SAFE_PAYLOAD_BYTES) continue // too big for BLE
            enqueueClientWrite(gatt, char, msg.toJson().toByteArray(Charsets.UTF_8))
        }
    }

    /** Same idea as [flushOutboxTo] but for the server->client (notify) direction -
     *  called right after a peer subscribes, so they get anything already queued,
     *  not just messages created after they subscribed. */
    @SuppressLint("MissingPermission")
    private fun flushOutboxToSubscriber(device: BluetoothDevice) {
        val service = gattServer?.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(MESSAGE_CHAR_UUID) ?: return
        for (msg in MessageStore.currentOutbox()) {
            if (msg.approxSizeBytes() > MeshMessage.BLE_SAFE_PAYLOAD_BYTES) continue
            enqueueServerNotify(device, char, msg.toJson().toByteArray(Charsets.UTF_8))
        }
    }

    // ---------------- Adaptive TTL ----------------

    /** Picks a TTL for a newly-created message based on how dense the mesh looks right now. */
    private fun adaptiveTtl(): Int {
        val density = MessageStore.recentUniqueSenderCount()
        return when {
            density <= SPARSE_SENDER_THRESHOLD -> TTL_SPARSE
            density >= DENSE_SENDER_THRESHOLD -> TTL_DENSE
            else -> TTL_NORMAL
        }
    }

    /** Called every [PERIODIC_CHECK_INTERVAL_MS] - if the mesh looks dense right now,
     *  drop scan/advertise to low-power settings purely to reduce radio congestion
     *  (separate reason from battery-driven [lowPowerMode], same mechanism). */
    private fun checkMeshCongestion() {
        val density = MessageStore.recentUniqueSenderCount()
        val shouldThrottle = density >= DENSE_SENDER_THRESHOLD
        if (shouldThrottle != congestionMode) {
            congestionMode = shouldThrottle
            Log.d(TAG, "Mesh density=$density -> congestionMode=$congestionMode, restarting BLE radio")
            restartAdvertisingAndScanning()
        }
    }

    /** Dead-man's switch (opt-in, Settings): if enabled and this device hasn't recorded
     *  an active "check-in" (I'm-safe tap or app-unlock, see MainActivity) in the
     *  configured window, broadcast a URGENT alert to the group once. Resets/re-arms
     *  the next time MainActivity records a check-in - see MainActivity.recordCheckIn(). */
    private fun checkDeadManSwitch() {
        val prefs = getSharedPreferences("mesh_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("deadman_enabled", false)) return
        val hours = prefs.getLong("deadman_hours", 0L)
        if (hours <= 0) return
        if (prefs.getBoolean("deadman_triggered", false)) return // already fired since last check-in
        val lastCheckin = prefs.getLong("deadman_last_checkin_ts", System.currentTimeMillis())
        val overdueMs = hours * 60 * 60 * 1000
        if (System.currentTimeMillis() - lastCheckin > overdueMs) {
            createAndSend(
                MessageType.TEXT,
                "⏱️ Dead-man's switch: $myUserName ne $hours ghante mein check-in nahi kiya — inka safety confirm karo / last known location dhoondo.",
                Priority.URGENT
            )
            prefs.edit().putBoolean("deadman_triggered", true).apply()
        }
    }

    // ---------------- Message handling shared by BLE + Wi-Fi Direct paths ----------------

    private fun handleIncomingMessageJson(json: String) {
        val msg = MeshMessage.fromJson(json) ?: return
        val isNew = MessageStore.markSeenIfNew(msg)
        if (!isNew) return // already seen this one, don't show or relay again

        // Signature check (TOFU): tells the UI whether this sender's key matches
        // what we've pinned for them before, or whether it's a fresh sender.
        val trust = if (msg.signature.isNotEmpty() && msg.senderPubKey.isNotEmpty()) {
            val sigOk = KeyManager.verify(KeyManager.signableBytes(msg), msg.signature, msg.senderPubKey)
            if (!sigOk) PeerTrustStore.TrustResult.MISMATCH
            else PeerTrustStore.check(msg.senderId, msg.senderPubKey)
        } else {
            PeerTrustStore.TrustResult.UNSIGNED
        }

        val plainText = CryptoUtils.decryptForEpoch(msg.payload, groupPassphrase, msg.epoch)
        if (plainText != null) {
            MessageStore.addDisplayed(msg)
            // Mesh intelligence: record this decrypted contact for routing hints + the
            // debug/health screen (see ContactGraph). Also track on-wire size for the
            // compression/efficiency display (see CompressionStats).
            CompressionStats.recordReceived(msg)
            if (msg.senderId != myUserId) {
                RollCallManager.noteKnownParticipant(msg.senderId, msg.senderName)
                VersionTracker.note(msg.senderId, msg.senderName, msg.appVersion)
                ContactGraph.recordEncounter(msg.senderId, msg.senderName)
            }
            when (msg.type) {
                MessageType.ROLLCALL_REQUEST -> {
                    if (msg.senderId != myUserId) {
                        val prompt = Intent(ACTION_ROLLCALL_PROMPT)
                        prompt.putExtra(EXTRA_ROLLCALL_ROUND_ID, msg.id)
                        sendBroadcast(prompt)
                    }
                }
                MessageType.ROLLCALL_RESPONSE -> {
                    val roundId = plainText
                    if (RollCallManager.recordResponse(roundId, msg.senderId)) {
                        val update = Intent(ACTION_ROLLCALL_UPDATE)
                        update.putExtra(EXTRA_ROLLCALL_ROUND_ID, roundId)
                        sendBroadcast(update)
                    }
                }
                MessageType.PINNED_DISPERSAL -> {
                    val pinUpdate = Intent(ACTION_PIN_UPDATED)
                    pinUpdate.putExtra(EXTRA_PIN_TEXT, plainText)
                    pinUpdate.putExtra(EXTRA_PIN_SENDER, msg.senderName)
                    sendBroadcast(pinUpdate)
                }
                MessageType.DELIVERY_ACK -> {
                    // plainText here is the id of OUR message being acknowledged.
                    // Never rendered as a chat bubble - just tells the UI to mark it delivered.
                    if (msg.senderId != myUserId) {
                        val ackUpdate = Intent(ACTION_DELIVERY_ACK)
                        ackUpdate.putExtra(EXTRA_ACKED_MESSAGE_ID, plainText)
                        sendBroadcast(ackUpdate)
                    }
                }
                else -> {}
            }
            // DELIVERY_ACK is plumbing, not something a human should see as a bubble -
            // every other type still goes to the UI as before.
            if (msg.type != MessageType.DELIVERY_ACK) {
                val intent = Intent(ACTION_MESSAGE_RECEIVED)
                intent.putExtra(EXTRA_MESSAGE_JSON, msg.toJson())
                intent.putExtra(EXTRA_TRUST, trust.name)
                sendBroadcast(intent)
            }
            // Auto-ack: let the sender know their message actually reached at least one
            // other device. Best-effort only (mesh is flood-based, no guaranteed path back) -
            // honest limitation, same spirit as everything else in this app.
            if (msg.senderId != myUserId && msg.type in ACKABLE_TYPES) {
                createAndSend(MessageType.DELIVERY_ACK, msg.id, Priority.NORMAL)
            }
        }

        // Relay onward if this message still has hops left
        if (msg.ttl > 0) {
            val relay = msg.copy(ttl = msg.ttl - 1)
            MessageStore.addToOutbox(relay)
            relayToAllPeers(relay)
        }
    }

    @SuppressLint("MissingPermission")
    private fun relayToAllPeers(msg: MeshMessage) {
        // BLE: only if it fits in a safe GATT write
        if (msg.approxSizeBytes() <= MeshMessage.BLE_SAFE_PAYLOAD_BYTES) {
            val bytes = msg.toJson().toByteArray(Charsets.UTF_8)
            // Direction 1: we're the GATT client for this peer - write to them.
            for (gatt in connectedPeers.values) {
                val service = gatt.getService(SERVICE_UUID) ?: continue
                val char = service.getCharacteristic(MESSAGE_CHAR_UUID) ?: continue
                enqueueClientWrite(gatt, char, bytes)
            }
            // Direction 2: we're the GATT server and this peer is our client and
            // subscribed - notify them. Without this half, a peer that connected
            // TO us can receive our messages fine, but never gets anything we
            // send AFTER they connected (the exact "unka msg mujhe dikhta hai,
            // mera unko nahi" bug).
            if (subscribedCentrals.isNotEmpty()) {
                val service = gattServer?.getService(SERVICE_UUID)
                val char = service?.getCharacteristic(MESSAGE_CHAR_UUID)
                if (char != null) {
                    for (device in subscribedCentrals.values) {
                        enqueueServerNotify(device, char, bytes)
                    }
                }
            }
        }
        // Wi-Fi Direct: no practical size limit, always send here too (redundant
        // delivery is fine - the receiver dedups by message id)
        wifiDirect?.send(msg.toJson())
    }

    /** Builds + signs + stores + relays a new outgoing message of any type. */
    private fun createAndSend(
        type: MessageType,
        plainPayload: String,
        priority: Priority,
        channel: String = ChannelsAndAnonymity.DEFAULT_CHANNEL,
        anonymous: Boolean = false
    ): MeshMessage {
        val (encrypted, epoch) = CryptoUtils.encryptForNow(plainPayload, groupPassphrase)
        var msg = MeshMessage(
            senderId = myUserId,
            senderName = myUserName,
            ttl = adaptiveTtl(),
            type = type,
            priority = priority,
            epoch = epoch,
            payload = encrypted,
            senderPubKey = KeyManager.myPublicKeyBase64(),
            channel = channel,
            anonymous = anonymous
        )
        val signature = KeyManager.sign(KeyManager.signableBytes(msg))
        msg = msg.copy(signature = signature)

        MessageStore.markSeenIfNew(msg)
        MessageStore.addDisplayed(msg)
        MessageStore.addToOutbox(msg)
        relayToAllPeers(msg)
        broadcastPeerCount() // also refreshes pending-outbox count in the UI
        CompressionStats.recordSent(msg, CompressionStats.plaintextSizeOf(plainPayload))
        return msg
    }

    /** Call this from the UI to send a new outgoing text message. Returns the new
     *  message's id so the UI can later show a "✓ delivered" mark once an ACK comes back.
     *  [channel] and [anonymous] are additive (default to "general"/false) so existing
     *  callers that don't know about sub-channels or anonymous mode keep working as-is. */
    fun sendMessage(
        text: String,
        priority: Priority = Priority.NORMAL,
        channel: String = ChannelsAndAnonymity.DEFAULT_CHANNEL,
        anonymous: Boolean = false
    ): String {
        return createAndSend(MessageType.TEXT, text, priority, channel, anonymous).id
    }

    /**
     * Call this from the UI to send an image. [base64Image] should already be
     * downscaled/compressed by the caller - see MainActivity's image picker,
     * which resizes before encoding so this doesn't produce huge payloads.
     */
    fun sendImage(base64Image: String, priority: Priority = Priority.NORMAL): String {
        return createAndSend(MessageType.IMAGE, base64Image, priority).id
    }

    /** Call this from the UI to send a short voice note. [base64Audio] should already
     *  be compressed (see MainActivity's recorder, low-bitrate AAC/3GP). */
    fun sendAudio(base64Audio: String, priority: Priority = Priority.NORMAL): String {
        return createAndSend(MessageType.AUDIO, base64Audio, priority).id
    }

    /** Call this from the UI to share a GPS pin. [payload] is "lat,lon" as plain text
     *  before encryption - kept tiny so it always fits over BLE too. */
    fun sendLocation(lat: Double, lon: Double, priority: Priority = Priority.NORMAL): String {
        return createAndSend(MessageType.LOCATION, "$lat,$lon", priority).id
    }

    /**
     * Starts a roll call round: broadcasts a URGENT "confirm you're safe" ask to
     * the whole group and begins tracking who has responded (see RollCallManager).
     * Returns the round id (== the sent message's id) so the UI can poll/refresh
     * the tally as ROLLCALL_RESPONSE messages come back.
     */
    fun startRollCall(): String {
        val msg = createAndSend(MessageType.ROLLCALL_REQUEST, "Roll call — confirm you're safe", Priority.URGENT)
        RollCallManager.startRound(msg.id)
        return msg.id
    }

    /** Call this from the UI when the person taps "I'm safe" on a roll call prompt. */
    fun sendRollCallResponse(roundId: String) {
        createAndSend(MessageType.ROLLCALL_RESPONSE, roundId, Priority.URGENT)
    }

    /** Broadcasts a detained-person alert to the whole group at highest priority
     *  so it reaches a legal team/family contact carrying the app as fast as the
     *  mesh can move it. [details] is expected to already contain who/where/when. */
    fun sendDetainedAlert(details: String): String {
        return createAndSend(MessageType.DETAINED_ALERT, details, Priority.URGENT).id
    }

    /** Broadcasts/re-broadcasts the group's pinned dispersal point. The latest one
     *  replaces any earlier pin in every receiver's fixed banner (see MainActivity) -
     *  it does not scroll away with the rest of the chat. */
    fun sendPinnedDispersal(text: String) {
        createAndSend(MessageType.PINNED_DISPERSAL, text, Priority.URGENT)
    }

    private fun broadcastPeerCount() {
        val intent = Intent(ACTION_PEER_COUNT_CHANGED)
        intent.putExtra(EXTRA_PEER_COUNT, connectedPeers.size + wifiPeerCount)
        intent.putExtra(EXTRA_PENDING_COUNT, MessageStore.pendingCount())
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Offline Mesh", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Offline Mesh active")
            .setContentText("Listening for nearby devices via Bluetooth + Wi-Fi Direct")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    /** Wipes everything this service/profile knows about. Used by the panic-wipe button. */
    fun panicWipe() {
        MessageStore.wipeAll()
        RollCallManager.wipeAll()
        VersionTracker.wipeAll()
        connectedPeers.values.forEach {
            try { it.disconnect() } catch (e: Exception) { }
        }
        connectedPeers.clear()
        subscribedCentrals.clear()
        clientWriteQueues.clear()
        clientWriteInFlight.clear()
        serverNotifyQueues.clear()
        serverNotifyInFlight.clear()
        broadcastPeerCount()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        periodicHandler.removeCallbacks(periodicChecker)
        scanner?.stopScan(scanCallback)
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        for (gatt in connectedPeers.values) gatt.close()
        wifiDirect?.stop()
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
        super.onDestroy()
    }
}
