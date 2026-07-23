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

    private var wifiDirect: WifiDirectManager? = null
    @Volatile private var wifiPeerCount = 0

    @Volatile private var lowPowerMode = false
    private var batteryReceiverRegistered = false

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
        registerReceiver(batteryReceiver, filter)
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
    }

    // ---------------- ADVERTISING (so peers can find us) ----------------

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (lowPowerMode) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            )
            .setTxPowerLevel(
                if (lowPowerMode) AdvertiseSettings.ADVERTISE_TX_POWER_LOW
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
                if (lowPowerMode) ScanSettings.SCAN_MODE_LOW_POWER
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
                    gatt.close()
                    broadcastPeerCount()
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // Peer is ready - flush anything queued in our outbox to them.
                // Only text-sized payloads go over BLE; images wait for Wi-Fi Direct.
                flushOutboxTo(gatt)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun flushOutboxTo(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(MESSAGE_CHAR_UUID) ?: return
        for (msg in MessageStore.currentOutbox()) { // already URGENT-first ordered
            if (msg.approxSizeBytes() > MeshMessage.BLE_SAFE_PAYLOAD_BYTES) continue // too big for BLE
            char.value = msg.toJson().toByteArray(Charsets.UTF_8)
            gatt.writeCharacteristic(char)
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
            if (msg.senderId != myUserId) {
                RollCallManager.noteKnownParticipant(msg.senderId, msg.senderName)
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
                else -> {}
            }
            val intent = Intent(ACTION_MESSAGE_RECEIVED)
            intent.putExtra(EXTRA_MESSAGE_JSON, msg.toJson())
            intent.putExtra(EXTRA_TRUST, trust.name)
            sendBroadcast(intent)
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
            for (gatt in connectedPeers.values) {
                val service = gatt.getService(SERVICE_UUID) ?: continue
                val char = service.getCharacteristic(MESSAGE_CHAR_UUID) ?: continue
                char.value = msg.toJson().toByteArray(Charsets.UTF_8)
                gatt.writeCharacteristic(char)
            }
        }
        // Wi-Fi Direct: no practical size limit, always send here too (redundant
        // delivery is fine - the receiver dedups by message id)
        wifiDirect?.send(msg.toJson())
    }

    /** Builds + signs + stores + relays a new outgoing message of any type. */
    private fun createAndSend(type: MessageType, plainPayload: String, priority: Priority): MeshMessage {
        val (encrypted, epoch) = CryptoUtils.encryptForNow(plainPayload, groupPassphrase)
        var msg = MeshMessage(
            senderId = myUserId,
            senderName = myUserName,
            ttl = adaptiveTtl(),
            type = type,
            priority = priority,
            epoch = epoch,
            payload = encrypted,
            senderPubKey = KeyManager.myPublicKeyBase64()
        )
        val signature = KeyManager.sign(KeyManager.signableBytes(msg))
        msg = msg.copy(signature = signature)

        MessageStore.markSeenIfNew(msg)
        MessageStore.addDisplayed(msg)
        MessageStore.addToOutbox(msg)
        relayToAllPeers(msg)
        broadcastPeerCount() // also refreshes pending-outbox count in the UI
        return msg
    }

    /** Call this from the UI to send a new outgoing text message. */
    fun sendMessage(text: String, priority: Priority = Priority.NORMAL) {
        createAndSend(MessageType.TEXT, text, priority)
    }

    /**
     * Call this from the UI to send an image. [base64Image] should already be
     * downscaled/compressed by the caller - see MainActivity's image picker,
     * which resizes before encoding so this doesn't produce huge payloads.
     */
    fun sendImage(base64Image: String, priority: Priority = Priority.NORMAL) {
        createAndSend(MessageType.IMAGE, base64Image, priority)
    }

    /** Call this from the UI to send a short voice note. [base64Audio] should already
     *  be compressed (see MainActivity's recorder, low-bitrate AAC/3GP). */
    fun sendAudio(base64Audio: String, priority: Priority = Priority.NORMAL) {
        createAndSend(MessageType.AUDIO, base64Audio, priority)
    }

    /** Call this from the UI to share a GPS pin. [payload] is "lat,lon" as plain text
     *  before encryption - kept tiny so it always fits over BLE too. */
    fun sendLocation(lat: Double, lon: Double, priority: Priority = Priority.NORMAL) {
        createAndSend(MessageType.LOCATION, "$lat,$lon", priority)
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
    fun sendDetainedAlert(details: String) {
        createAndSend(MessageType.DETAINED_ALERT, details, Priority.URGENT)
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
        connectedPeers.values.forEach {
            try { it.disconnect() } catch (e: Exception) { }
        }
        connectedPeers.clear()
        broadcastPeerCount()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
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
