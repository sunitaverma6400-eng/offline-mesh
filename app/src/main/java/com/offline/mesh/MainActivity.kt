package com.offline.mesh

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var meshService: BleMeshService? = null
    private var bound = false

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var inputBox: EditText
    private lateinit var peerCountLabel: TextView
    private lateinit var meshHealthLabel: TextView
    private lateinit var versionWarningLabel: TextView
    private lateinit var urgentCheckbox: CheckBox
    private lateinit var pinnedBannerContainer: LinearLayout
    private lateinit var pinnedBannerText: TextView

    // Tracks which roll-call round's live status dialog (if any) is currently
    // open, so an incoming ACTION_ROLLCALL_UPDATE knows whether/how to refresh it.
    private var openRollCallDialogRoundId: String? = null
    private var openRollCallDialog: AlertDialog? = null
    private var openRollCallStatusView: TextView? = null

    // Which profile we're running as this session - "main" (real data) or
    // "duress" (decoy, separate storage, entered via the duress PIN).
    private var activeProfile: String = "main"
    private var isDecoySession: Boolean = false

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false

    // Persisted (default) so "isMine" stays correct for old messages across app
    // restarts, and so peers can (loosely) recognize the same sender over time.
    //
    // Optional "Rotate device ID every session" (Settings): if enabled, a fresh
    // random id is generated each app launch and NOT persisted, instead of
    // reusing the same fixed id forever. Honest tradeoff: this makes it harder
    // to correlate the same phone's traffic across two different protests/days
    // (each session looks like a new device), but it also means TOFU key-pinning
    // (KeyManager/PeerTrustStore) and the roll-call roster (RollCallManager)
    // both reset every session for everyone using it - "key changed!" warnings
    // and "who's known" become less meaningful for a rotating id. Off by default.
    private val myUserId: String by lazy {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("rotate_device_id", false)) {
            return@lazy UUID.randomUUID().toString()
        }
        var id = prefs.getString("user_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("user_id", id).apply()
        }
        id
    }

    // Only these are actually needed for the mesh itself to work - Bluetooth
    // roles, location (required for BLE scan pre-Android 12), and Wi-Fi Direct
    // discovery. Camera/mic are for specific optional features (QR join, voice
    // notes) and must NOT block the mesh from starting if declined - see the bug
    // note on permissionLauncher below.
    private val essentialPermissions: Array<String>
        get() {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_ADVERTISE
                perms += Manifest.permission.BLUETOOTH_CONNECT
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            perms += Manifest.permission.ACCESS_COARSE_LOCATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            return perms.toTypedArray()
        }

    private val optionalPermissions: Array<String>
        get() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val requiredPermissions: Array<String>
        get() = essentialPermissions + optionalPermissions

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // BUG FIX: this used to require results.values.all { it } - meaning if
        // someone declined just Camera (QR scan) or Mic (voice notes), the mesh
        // service NEVER started at all, even with every Bluetooth/Location
        // permission granted. That looked exactly like "connect hi nahi hota"
        // from the outside, with no error explaining why. Now only the
        // mesh-essential permissions gate starting the service; optional ones
        // just disable their specific feature.
        val essentialGranted = essentialPermissions.all { results[it] == true }
        if (essentialGranted) {
            startMeshService()
            val deniedOptional = optionalPermissions.filter { results[it] == false }
            if (deniedOptional.isNotEmpty()) {
                val names = deniedOptional.joinToString(", ") {
                    if (it == Manifest.permission.CAMERA) "Camera (QR scan)" else "Microphone (voice notes)"
                }
                Toast.makeText(this, "Mesh chalu hai. $names off hai - Settings se baad mein on kar sakte ho.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Bluetooth/Location/Wi-Fi permissions zaroori hain mesh ke liye - inke bina connect nahi ho payega.", Toast.LENGTH_LONG).show()
        }
    }

    // ---- QR scan launcher (for joining a group by scanning someone's passphrase QR) ----
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null) {
            setGroupPassphrase(scanned)
            meshService?.groupPassphrase = scanned
            Toast.makeText(this, "Group joined via QR", Toast.LENGTH_SHORT).show()
            adapter.clear()
            loadPersistedHistory()
        }
    }

    // ---- Image picker launcher (for "Send Photo") ----
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) sendPickedImage(uri)
    }

    // ---- Offline AI ----
    private val aiManager: OfflineAiManager by lazy { OfflineAiManager(applicationContext) }

    // Lets the user pick a previously-downloaded .task model file (e.g. from
    // Downloads) and copies it into app-private storage for OfflineAiManager to use.
    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    OfflineAiManager.modelFile(this).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "AI model imported. 'Ask AI' se try karo.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Model import fail hua: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleMeshService.LocalBinder
            meshService = binder.getService()
            meshService?.profile = activeProfile
            meshService?.myUserId = myUserId
            meshService?.myUserName = "User-${myUserId.take(4)}"
            meshService?.groupPassphrase = getGroupPassphrase()
            meshService?.startMesh()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BleMeshService.ACTION_MESSAGE_RECEIVED -> {
                    val json = intent.getStringExtra(BleMeshService.EXTRA_MESSAGE_JSON) ?: return
                    val msg = MeshMessage.fromJson(json) ?: return
                    val plain = CryptoUtils.decryptForEpoch(msg.payload, getGroupPassphrase(), msg.epoch) ?: return
                    val isMine = msg.senderId == myUserId
                    val trustName = intent.getStringExtra(BleMeshService.EXTRA_TRUST)
                    val trust = trustBadgeFor(isMine, trustName)
                    adapter.addMessage(toDisplayMessage(msg, plain, isMine, trust))
                    recyclerView.scrollToPosition(adapter.itemCount - 1)
                    if (!isMine && msg.priority == Priority.URGENT) vibrateForUrgentIfNotSilent()
                    updateGroupCountLabel()
                }
                BleMeshService.ACTION_PEER_COUNT_CHANGED -> {
                    val count = intent.getIntExtra(BleMeshService.EXTRA_PEER_COUNT, 0)
                    val pending = intent.getIntExtra(BleMeshService.EXTRA_PENDING_COUNT, 0)
                    val prefix = if (isDecoySession) "[Decoy] " else ""
                    peerCountLabel.text = "$prefix Connected peers: $count  |  Pending delivery: $pending"
                    updateGroupCountLabel()
                }
                BleMeshService.ACTION_ROLLCALL_PROMPT -> {
                    val roundId = intent.getStringExtra(BleMeshService.EXTRA_ROLLCALL_ROUND_ID)
                    if (roundId != null) showRollCallPromptDialog(roundId)
                }
                BleMeshService.ACTION_ROLLCALL_UPDATE -> {
                    val roundId = intent.getStringExtra(BleMeshService.EXTRA_ROLLCALL_ROUND_ID)
                    if (roundId != null) refreshRollCallStatusDialogIfShowing(roundId)
                }
                BleMeshService.ACTION_PIN_UPDATED -> {
                    val text = intent.getStringExtra(BleMeshService.EXTRA_PIN_TEXT)
                    val sender = intent.getStringExtra(BleMeshService.EXTRA_PIN_SENDER) ?: ""
                    if (text != null) {
                        savePinnedDispersal(text, sender)
                        updatePinnedBanner()
                    }
                }
                BleMeshService.ACTION_DELIVERY_ACK -> {
                    val ackedId = intent.getStringExtra(BleMeshService.EXTRA_ACKED_MESSAGE_ID)
                    if (ackedId != null) adapter.markDelivered(ackedId)
                }
            }
        }
    }

    // Buzzes the phone when an SOS/urgent message arrives from someone else - unless
    // "Silent mode" is on (Settings), for when any noise/vibration would be risky.
    private fun vibrateForUrgentIfNotSilent() {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("silent_mode", false)) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 200), -1))
        } catch (e: Exception) { }
    }

    private fun trustBadgeFor(isMine: Boolean, trustName: String?): TrustBadge {
        if (isMine) return TrustBadge.MINE
        return when (trustName) {
            PeerTrustStore.TrustResult.SAME.name -> TrustBadge.VERIFIED
            PeerTrustStore.TrustResult.MISMATCH.name -> TrustBadge.MISMATCH
            else -> TrustBadge.UNSIGNED
        }
    }

    private fun toDisplayMessage(msg: MeshMessage, plain: String, isMine: Boolean, trust: TrustBadge): DisplayMessage {
        // Anonymous broadcast mode: displayName() returns "Someone in group" when
        // msg.anonymous is true, otherwise it's identical to msg.senderName - so this
        // is a no-op for every message that isn't using anonymous mode.
        val name = msg.displayName()
        return when (msg.type) {
            MessageType.IMAGE, MessageType.AUDIO ->
                DisplayMessage(name, "", msg.ts, isMine, msg.type, plain, msg.priority, trust, id = msg.id)
            MessageType.ROLLCALL_REQUEST ->
                DisplayMessage(name, "📋 Roll call — $name ne poocha: sab safe ho?", msg.ts, isMine, msg.type, null, msg.priority, trust, id = msg.id)
            MessageType.ROLLCALL_RESPONSE ->
                DisplayMessage(name, "✅ $name ne roll call mein 'safe' confirm kiya", msg.ts, isMine, msg.type, null, msg.priority, trust, id = msg.id)
            MessageType.DETAINED_ALERT ->
                DisplayMessage(name, "🚔 DETAINED ALERT ($name ne bheja) — $plain", msg.ts, isMine, msg.type, null, msg.priority, trust, id = msg.id)
            MessageType.PINNED_DISPERSAL ->
                DisplayMessage(name, "📌 Dispersal point update ($name) — $plain", msg.ts, isMine, msg.type, null, msg.priority, trust, id = msg.id)
            else ->
                DisplayMessage(name, plain, msg.ts, isMine, msg.type, null, msg.priority, trust, id = msg.id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots + hide content from the recent-apps thumbnail/screen
        // recording — chat content should never end up in a gallery or a
        // recording someone else can pull off the phone later.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_main)
        maybeShowLockScreenThenInit()
    }

    // ---------------- Lock screen / duress PIN ----------------

    private fun maybeShowLockScreenThenInit() {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        val appPin = prefs.getString("app_pin", null)
        if (appPin.isNullOrEmpty()) {
            // No lock configured - just run normally.
            recordCheckIn()
            initUiAndMesh()
            return
        }
        val duressPin = prefs.getString("duress_pin", null)
        val inflater = LayoutInflater.from(this)
        val pinInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(pinInput)
            .setCancelable(false)
            .setPositiveButton("Unlock", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = pinInput.text.toString()
                when {
                    entered == appPin -> {
                        activeProfile = "main"
                        isDecoySession = false
                        recordCheckIn()
                        dialog.dismiss()
                        initUiAndMesh()
                    }
                    duressPin != null && entered == duressPin -> {
                        activeProfile = "duress"
                        isDecoySession = true
                        dialog.dismiss()
                        initUiAndMesh()
                    }
                    else -> {
                        pinInput.error = "Galat PIN"
                    }
                }
            }
        }
        dialog.show()
    }

    private fun initUiAndMesh() {
        recyclerView = findViewById(R.id.recyclerMessages)
        inputBox = findViewById(R.id.editMessage)
        peerCountLabel = findViewById(R.id.textPeerCount)
        urgentCheckbox = findViewById(R.id.checkboxUrgent)
        val anonymousCheckbox = findViewById<CheckBox>(R.id.checkboxAnonymous)
        anonymousCheckbox.isChecked = ChannelsAndAnonymity.getAnonymousDefault(this)
        val meshToolsButton = findViewById<Button>(R.id.buttonMeshTools)
        val sendButton = findViewById<Button>(R.id.buttonSend)
        val showQrButton = findViewById<Button>(R.id.buttonShowQr)
        val scanQrButton = findViewById<Button>(R.id.buttonScanQr)
        val sendImageButton = findViewById<Button>(R.id.buttonSendImage)
        val panicButton = findViewById<Button>(R.id.buttonPanicWipe)
        val recordButton = findViewById<Button>(R.id.buttonRecordVoice)
        val locationButton = findViewById<Button>(R.id.buttonSendLocation)
        val settingsButton = findViewById<Button>(R.id.buttonSettings)
        val askAiButton = findViewById<Button>(R.id.buttonAskAi)
        val sosButton = findViewById<Button>(R.id.buttonSos)
        val imSafeButton = findViewById<Button>(R.id.buttonImSafe)
        val knowRightsButton = findViewById<Button>(R.id.buttonKnowRights)
        val rollCallButton = findViewById<Button>(R.id.buttonRollCall)
        val detainedButton = findViewById<Button>(R.id.buttonDetained)
        val detainedLogButton = findViewById<Button>(R.id.buttonDetainedLog)
        val setPinButton = findViewById<Button>(R.id.buttonSetPin)
        val evidenceVaultButton = findViewById<Button>(R.id.buttonEvidenceVault)
        val firstAidButton = findViewById<Button>(R.id.buttonFirstAid)
        pinnedBannerContainer = findViewById(R.id.pinnedBannerContainer)
        pinnedBannerText = findViewById(R.id.textPinnedBanner)
        val clearPinButton = findViewById<Button>(R.id.buttonClearPin)
        meshHealthLabel = findViewById(R.id.textMeshHealth)
        versionWarningLabel = findViewById(R.id.textVersionWarning)

        adapter = ChatAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // MessageStore.init is idempotent per-profile - safe to call here even
        // though the service also calls it, in case the UI opens before the
        // service is bound.
        MessageStore.init(applicationContext, activeProfile)
        loadPersistedHistory()

        sendButton.setOnClickListener {
            val text = inputBox.text.toString().trim()
            if (text.isNotEmpty()) {
                val priority = if (urgentCheckbox.isChecked) Priority.URGENT else Priority.NORMAL
                val channel = ChannelsAndAnonymity.getActiveChannel(this)
                val anonymous = anonymousCheckbox.isChecked
                val sentId = meshService?.sendMessage(text, priority, channel, anonymous) ?: ""
                val displayName = if (anonymous) "Someone in group" else "You"
                adapter.addMessage(DisplayMessage(displayName, text, System.currentTimeMillis(), true, priority = priority, trust = TrustBadge.MINE, id = sentId))
                recyclerView.scrollToPosition(adapter.itemCount - 1)
                inputBox.text.clear()
                urgentCheckbox.isChecked = false
                ChannelsAndAnonymity.setAnonymousDefault(this, anonymousCheckbox.isChecked)
            }
        }

        meshToolsButton.setOnClickListener {
            startActivity(Intent(this, MeshToolsActivity::class.java))
        }

        showQrButton.setOnClickListener { showGroupQrDialog() }
        scanQrButton.setOnClickListener { launchQrScanner() }
        sendImageButton.setOnClickListener { imagePickerLauncher.launch("image/*") }
        panicButton.setOnClickListener { confirmAndPanicWipe() }
        recordButton.setOnClickListener { toggleVoiceRecording(recordButton) }
        locationButton.setOnClickListener { sendCurrentLocation() }
        settingsButton.setOnClickListener { showSettingsDialog() }
        askAiButton.setOnClickListener { showAskAiDialog() }
        sosButton.setOnClickListener { confirmAndSendSos() }
        imSafeButton.setOnClickListener { sendImSafe() }
        knowRightsButton.setOnClickListener { showKnowYourRightsDialog() }
        rollCallButton.setOnClickListener { confirmAndStartRollCall() }
        detainedButton.setOnClickListener { showDetainedDialog() }
        detainedLogButton.setOnClickListener { showDetainedLogDialog() }
        setPinButton.setOnClickListener { showSetDispersalPointDialog() }
        evidenceVaultButton.setOnClickListener {
            startActivity(Intent(this, EvidenceVaultActivity::class.java))
        }
        firstAidButton.setOnClickListener { showFirstAidDialog() }
        clearPinButton.setOnClickListener { clearPinnedDispersal() }

        val filter = IntentFilter().apply {
            addAction(BleMeshService.ACTION_MESSAGE_RECEIVED)
            addAction(BleMeshService.ACTION_PEER_COUNT_CHANGED)
            addAction(BleMeshService.ACTION_ROLLCALL_PROMPT)
            addAction(BleMeshService.ACTION_ROLLCALL_UPDATE)
            addAction(BleMeshService.ACTION_PIN_UPDATED)
            addAction(BleMeshService.ACTION_DELIVERY_ACK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }

        updatePinnedBanner()
        updateGroupCountLabel()
        ensureBluetoothOnThenRequestPermissions()
    }

    // ---------------- Settings: app PIN + duress PIN ----------------

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val appPinInput = EditText(this).apply {
            hint = "App unlock PIN (blank = no lock)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText(prefs.getString("app_pin", ""))
        }
        val duressPinInput = EditText(this).apply {
            hint = "Duress PIN (shows empty decoy chat)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText(prefs.getString("duress_pin", ""))
        }
        layout.addView(appPinInput)
        layout.addView(duressPinInput)

        val aiStatus = TextView(this).apply {
            setPadding(0, 30, 0, 10)
            text = if (OfflineAiManager.isModelPresent(this@MainActivity))
                "Offline AI: model loaded hai ✅"
            else
                "Offline AI: koi model set nahi hai ❌"
        }
        val importModelButton = Button(this).apply {
            text = "Import AI model file (.task)"
            setOnClickListener { modelPickerLauncher.launch(arrayOf("*/*")) }
        }
        layout.addView(aiStatus)
        layout.addView(importModelButton)

        val autoWipeInput = EditText(this).apply {
            hint = "Auto-delete chat history after N hours (blank = never)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            val current = prefs.getLong("auto_wipe_hours", 0L)
            if (current > 0) setText(current.toString())
        }
        val silentModeCheckbox = CheckBox(this).apply {
            text = "Silent mode (naye message pe vibration/sound band)"
            isChecked = prefs.getBoolean("silent_mode", false)
        }
        val panicShortcutInfo = TextView(this).apply {
            setPadding(0, 20, 0, 0)
            textSize = 12f
            text = "Quick panic wipe: app khuli ho to Volume-Down 3 baar jaldi-jaldi dabao — turant wipe confirmation aa jaayega."
        }
        val rotateIdCheckbox = CheckBox(this).apply {
            text = "Rotate device ID har session mein"
            isChecked = prefs.getBoolean("rotate_device_id", false)
        }
        val rotateIdInfo = TextView(this).apply {
            setPadding(0, 0, 0, 10)
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            text = "ON karne par har app-restart pe ek naya random device ID milega (cross-protest tracking/correlation thoda harder) — lekin key-verification ('✓ verified') aur roll-call ka 'known members' list bhi har session reset ho jaayenge. Change apply hone ke liye app restart karo."
        }
        layout.addView(autoWipeInput)
        layout.addView(silentModeCheckbox)
        layout.addView(rotateIdCheckbox)
        layout.addView(rotateIdInfo)

        val deadManCheckbox = CheckBox(this).apply {
            text = "Dead-man's switch (opt-in)"
            isChecked = prefs.getBoolean("deadman_enabled", false)
        }
        val deadManHoursInput = EditText(this).apply {
            hint = "Check-in na kiya to alert bhejo (N hours)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            val current = prefs.getLong("deadman_hours", 0L)
            if (current > 0) setText(current.toString())
        }
        val deadManInfo = TextView(this).apply {
            setPadding(0, 0, 0, 10)
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            text = "ON karke ek N-hour window set karo. Agar itne time tak tumne app unlock nahi kiya ya 'I'm safe' nahi dabaya, group ko ek URGENT alert apne aap chala jaayega ('check-in nahi hua'). Har check-in (unlock/I'm safe) par timer reset ho jaata hai. Honest limitation: ye sirf tab kaam karta hai jab mesh service background mein chalti rahe (phone on, Bluetooth on) - agar phone hi band/dead ho jaaye to koi alert nahi ja sakta."
        }
        layout.addView(deadManCheckbox)
        layout.addView(deadManHoursInput)
        layout.addView(deadManInfo)
        layout.addView(panicShortcutInfo)

        val scrollWrapper = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("Lock screen settings")
            .setMessage("Agar App PIN set karoge, to app khulte waqt PIN maangega. Duress PIN dabane par ek khaali 'decoy' chat khulegi jo real data ko bilkul touch nahi karti.\n\nOffline AI: internet ke bina general sawaal-jawab ke liye ek chhota on-device model — pehle se internet pe download karke .task file yahan 'import' karni hogi. Ye real info-retrieval nahi hai, sirf ek fixed model ke andar jo pehle se baked hai — kabhi-kabhi galat/purana jawab de sakta hai, isliye important cheezein (legal rights, medical, safety) humans se hi confirm karo.")
            .setView(scrollWrapper)
            .setPositiveButton("Save") { _, _ ->
                val hours = autoWipeInput.text.toString().trim().toLongOrNull() ?: 0L
                val deadManHours = deadManHoursInput.text.toString().trim().toLongOrNull() ?: 0L
                val deadManWasEnabled = prefs.getBoolean("deadman_enabled", false)
                prefs.edit()
                    .putString("app_pin", appPinInput.text.toString().trim())
                    .putString("duress_pin", duressPinInput.text.toString().trim())
                    .putLong("auto_wipe_hours", hours)
                    .putBoolean("silent_mode", silentModeCheckbox.isChecked)
                    .putBoolean("rotate_device_id", rotateIdCheckbox.isChecked)
                    .putBoolean("deadman_enabled", deadManCheckbox.isChecked)
                    .putLong("deadman_hours", deadManHours)
                    .apply()
                // Turning it on (or re-saving while on) should start the timer fresh,
                // not immediately fire because "last checkin" was never set before.
                if (deadManCheckbox.isChecked && (!deadManWasEnabled || deadManHours > 0)) {
                    recordCheckIn()
                }
                applyAutoWipeIfConfigured()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Offline AI: ask a question, optionally share answer to mesh ----------------

    private fun showAskAiDialog() {
        if (!OfflineAiManager.isModelPresent(this)) {
            AlertDialog.Builder(this)
                .setTitle("Offline AI set nahi hai")
                .setMessage("Pehle Settings (⚙) mein jaakar ek .task model file import karo (ye ek baar internet hone par download karna padega). Uske baad ye pura offline kaam karega.")
                .setPositiveButton("Settings kholo") { _, _ -> showSettingsDialog() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val questionInput = EditText(this).apply {
            hint = "Kuch bhi pucho..."
        }
        AlertDialog.Builder(this)
            .setTitle("🤖 Ask AI (offline, unverified)")
            .setMessage("Ye ek chhota on-device model hai — internet/search nahi hai, sirf jo pehle se model ke andar 'baked' hai wahi jaanta hai, aur galat bhi bol sakta hai. Important faislon ke liye ispe akela bharosa mat karo.")
            .setView(questionInput)
            .setPositiveButton("Poocho") { _, _ ->
                val question = questionInput.text.toString().trim()
                if (question.isEmpty()) return@setPositiveButton
                val progress = AlertDialog.Builder(this)
                    .setTitle("Soch raha hai...")
                    .setMessage("Offline model se jawab aa raha hai, thoda time lag sakta hai.")
                    .setCancelable(false)
                    .show()
                aiManager.ask(question) { answer, error ->
                    runOnUiThread {
                        progress.dismiss()
                        if (answer != null) {
                            showAiAnswerDialog(question, answer)
                        } else {
                            Toast.makeText(this, error ?: "Kuch galat ho gaya", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAiAnswerDialog(question: String, answer: String) {
        AlertDialog.Builder(this)
            .setTitle("🤖 AI jawab (unverified)")
            .setMessage(answer)
            .setPositiveButton("Mesh mein share karo") { _, _ ->
                // Always tagged so everyone in the group can see this came from an
                // offline model, not a verified human source.
                val shareText = "[AI · unverified] Q: $question\nA: $answer"
                meshService?.sendMessage(shareText, Priority.NORMAL)
                adapter.addMessage(
                    DisplayMessage("You", shareText, System.currentTimeMillis(), true, priority = Priority.NORMAL, trust = TrustBadge.MINE)
                )
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- QR code: show my passphrase / scan someone else's ----------------

    private fun showGroupQrDialog() {
        val passphrase = getGroupPassphrase()
        val bitmap = QrUtils.generateQrBitmap(passphrase)
        val imageView = ImageView(this).apply { setImageBitmap(bitmap) }
        AlertDialog.Builder(this)
            .setTitle("Group QR code")
            .setMessage("Doosre device se ye scan karwao taaki wo bhi isi group mein aa jaaye")
            .setView(imageView)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
        options.setPrompt("Group ka QR code scan karo")
        options.setBeepEnabled(true)
        options.setOrientationLocked(true)
        qrScanLauncher.launch(options)
    }

    /**
     * Passphrase source, namespaced per profile so the duress/decoy session
     * never shares a passphrase (and therefore never shares any chat) with
     * the real one, even if both are open on the same phone.
     */
    private fun getGroupPassphrase(): String {
        if (isDecoySession) return "decoy-${myUserId.take(8)}"
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        return prefs.getString("group_passphrase", "changeme") ?: "changeme"
    }

    private fun setGroupPassphrase(passphrase: String) {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        prefs.edit().putString("group_passphrase", passphrase).apply()
    }

    // ---------------- Image sending ----------------

    private fun sendPickedImage(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(input)
            input.close()

            // Wi-Fi Direct (which every image effectively goes over now - BLE's
            // payload limit is too small for any real photo) has no practical
            // size ceiling, so there's no need for the old BLE-era 480px/quality-50
            // defaults - that was leaving images looking noticeably worse than
            // necessary. 1280px keeps a phone photo sharp on screen while still
            // being reasonable to hold in memory/relay across the mesh.
            val scaled = downscaleBitmap(original, 1280)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val priority = if (urgentCheckbox.isChecked) Priority.URGENT else Priority.NORMAL
            val sentId = meshService?.sendImage(base64, priority) ?: ""
            adapter.addMessage(DisplayMessage("You", "", System.currentTimeMillis(), true, MessageType.IMAGE, base64, priority, TrustBadge.MINE, id = sentId))
            recyclerView.scrollToPosition(adapter.itemCount - 1)

            val sizeKb = base64.length / 1024
            if (sizeKb > 800) {
                Toast.makeText(this, "Image badi hai (~${sizeKb}KB) - Wi-Fi Direct peer chahiye deliver hone ke liye, BLE-only peers tak nahi pahunchegi", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Image bhejne mein error aayi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val ratio = minOf(
            maxDimension.toFloat() / bitmap.width,
            maxDimension.toFloat() / bitmap.height,
            1f // never upscale
        )
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    // ---------------- Voice notes ----------------

    private fun toggleVoiceRecording(button: Button) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission chahiye voice note ke liye", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isRecording) {
            startRecording()
            button.text = "⏹ Stop"
        } else {
            stopRecordingAndSend()
            button.text = "🎤 Voice"
        }
    }

    private fun startRecording() {
        try {
            recordingFile = File.createTempFile("mesh_rec", ".m4a", cacheDir)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(24000) // low bitrate - keeps mesh payload small
                setAudioSamplingRate(16000)
                setOutputFile(recordingFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            Toast.makeText(this, "Recording... phir se dabao rokne ke liye", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Recording start nahi ho payi", Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    private fun stopRecordingAndSend() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            val file = recordingFile ?: return
            val bytes = file.readBytes()
            file.delete()
            val sizeKb = bytes.size / 1024
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val priority = if (urgentCheckbox.isChecked) Priority.URGENT else Priority.NORMAL
            val sentId = meshService?.sendAudio(base64, priority) ?: ""
            adapter.addMessage(DisplayMessage("You", "", System.currentTimeMillis(), true, MessageType.AUDIO, base64, priority, TrustBadge.MINE, id = sentId))
            recyclerView.scrollToPosition(adapter.itemCount - 1)
            if (sizeKb > 400) {
                Toast.makeText(this, "Voice note ~${sizeKb}KB hai - Wi-Fi Direct peer chahiye deliver hone ke liye", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Voice note bhejne mein error aayi", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Location sharing ----------------

    private fun sendCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission chahiye pin share karne ke liye", Toast.LENGTH_SHORT).show()
            return
        }
        val priority = if (urgentCheckbox.isChecked) Priority.URGENT else Priority.NORMAL
        Toast.makeText(this, "Location le rahe hain...", Toast.LENGTH_SHORT).show()
        // Was: getLastKnownLocation() only, which can be stale/null for a long
        // time. Now actively asks for a fresh fix first (with a fallback to the
        // old cached behavior), see LocationHelper for why.
        LocationHelper.getBestEffortLocation(this) { loc ->
            if (loc == null) {
                Toast.makeText(this, "Location abhi available nahi - GPS/network on karke thoda wait karo", Toast.LENGTH_LONG).show()
                return@getBestEffortLocation
            }
            val lat = loc.latitude
            val lon = loc.longitude
            val sentId = meshService?.sendLocation(lat, lon, priority) ?: ""
            val msg = DisplayMessage("You", "$lat,$lon", System.currentTimeMillis(), true, MessageType.LOCATION, null, priority, TrustBadge.MINE, id = sentId)
            adapter.addMessage(msg)
            recyclerView.scrollToPosition(adapter.itemCount - 1)

            // Best-effort: resolve to a real, human-readable address for local
            // display. Needs network, so this silently no-ops when offline -
            // the pin above has already been sent to the mesh either way.
            LocationHelper.reverseGeocode(this, loc) { address ->
                if (address != null) adapter.setLocationLabel(msg, address)
            }
        }
    }

    // ---------------- SOS / quick check-in ----------------

    private fun confirmAndSendSos() {
        AlertDialog.Builder(this)
            .setTitle("🆘 SOS bhejo?")
            .setMessage("Ye poore group ko sabse high priority emergency alert bhejega, saath mein tumhari last-known location (agar available ho). Sirf real emergency mein use karo.")
            .setPositiveButton("Haan, SOS bhejo") { _, _ -> sendSos() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendSos() {
        // SOS is urgent, so we don't wait long for a fix: a short timeout for a
        // fresh location, falling back to cached instantly if that's not viable.
        // This still beats the old behavior (cached-only, sometimes null/stale)
        // without meaningfully delaying the alert itself.
        LocationHelper.getBestEffortLocation(this, timeoutMs = 2500L) { loc ->
            val locationSuffix = if (loc != null) " — location: ${loc.latitude},${loc.longitude}" else ""
            val text = "🆘 SOS — help chahiye!$locationSuffix"
            meshService?.sendMessage(text, Priority.URGENT)
            adapter.addMessage(DisplayMessage("You", text, System.currentTimeMillis(), true, priority = Priority.URGENT, trust = TrustBadge.MINE))
            recyclerView.scrollToPosition(adapter.itemCount - 1)
            Toast.makeText(this, "SOS bhej diya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendImSafe() {
        val text = "✅ I'm safe"
        val sentId = meshService?.sendMessage(text, Priority.NORMAL) ?: ""
        adapter.addMessage(DisplayMessage("You", text, System.currentTimeMillis(), true, priority = Priority.NORMAL, trust = TrustBadge.MINE, id = sentId))
        recyclerView.scrollToPosition(adapter.itemCount - 1)
        recordCheckIn()
    }

    // ---------------- Dead-man's switch (opt-in, Settings) ----------------

    /** Call whenever the person does something that counts as "actively using the
     *  phone and OK" - unlocking with the real PIN, or tapping "I'm safe". Resets
     *  the dead-man's-switch timer in BleMeshService and re-arms it (clears the
     *  "already triggered" flag) so a future silence period can trigger it again. */
    private fun recordCheckIn() {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        prefs.edit()
            .putLong("deadman_last_checkin_ts", System.currentTimeMillis())
            .putBoolean("deadman_triggered", false)
            .apply()
    }

    // ---------------- Roll call / safety check ----------------

    /** Updates the "Mesh activity" line to also show how many distinct devices
     *  this phone has seen chatting so far this session (RollCallManager's
     *  roster) - this is the practical "kitne logo se baat ho sakti hai" number:
     *  not a hard cap, just who this phone currently knows is in the group.
     *  Also refreshes the version-mismatch warning below it (see VersionTracker). */
    private fun updateGroupCountLabel() {
        if (!::meshHealthLabel.isInitialized) return
        val density = MessageStore.recentUniqueSenderCountForDisplay()
        val known = RollCallManager.knownParticipantCount()
        meshHealthLabel.text = "Mesh activity (15 min): $density device(s) seen  |  Group: $known known (aap ko chhodke)"
        updateVersionWarning()
    }

    private fun updateVersionWarning() {
        if (!::versionWarningLabel.isInitialized) return
        val mismatched = VersionTracker.mismatched()
        if (mismatched.isEmpty()) {
            versionWarningLabel.visibility = View.GONE
            return
        }
        val olderCount = mismatched.count { it.value.second < AppVersion.CODE }
        val newerCount = mismatched.count { it.value.second > AppVersion.CODE }
        val parts = mutableListOf<String>()
        if (olderCount > 0) parts.add("$olderCount purane version (update karwao)")
        if (newerCount > 0) parts.add("$newerCount naye version (tum update karo)")
        versionWarningLabel.text = "⚠️ Tum ${AppVersion.NAME} pe ho — ${parts.joinToString(", ")} pe hain. Mixed versions mein naye safety features (roll call, detained alert, pin) sabke liye kaam nahi karenge."
        versionWarningLabel.visibility = View.VISIBLE
    }

    private fun confirmAndStartRollCall() {
        AlertDialog.Builder(this)
            .setTitle("📋 Roll call shuru karein?")
            .setMessage("Poore group ko URGENT 'confirm you're safe' broadcast jaayega. Jo log respond nahi karte unki list dikhegi — mass detention/dispersal ke waqt turant pata chalega kaun missing hai.\n\nHonest limitation: 'known members' sirf wahi log hain jinse tumhara phone is session mein already mila hai — koi fixed roster nahi hai.")
            .setPositiveButton("Haan, roll call bhejo") { _, _ ->
                val roundId = meshService?.startRollCall() ?: return@setPositiveButton
                adapter.addMessage(DisplayMessage("You", "📋 Roll call bhej diya — sab safe ho?", System.currentTimeMillis(), true, priority = Priority.URGENT, trust = TrustBadge.MINE))
                recyclerView.scrollToPosition(adapter.itemCount - 1)
                showRollCallStatusDialog(roundId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRollCallStatusDialog(roundId: String) {
        val statusView = TextView(this).apply { setPadding(40, 20, 40, 0); textSize = 14f }
        openRollCallDialogRoundId = roundId
        openRollCallStatusView = statusView
        updateRollCallStatusText(roundId, statusView)
        val dialog = AlertDialog.Builder(this)
            .setTitle("📋 Roll call status")
            .setView(statusView)
            .setPositiveButton("Close", null)
            .setOnDismissListener {
                if (openRollCallDialogRoundId == roundId) {
                    openRollCallDialogRoundId = null
                    openRollCallStatusView = null
                    openRollCallDialog = null
                }
            }
            .show()
        openRollCallDialog = dialog
    }

    private fun updateRollCallStatusText(roundId: String, view: TextView) {
        val responded = RollCallManager.respondedNamesFor(roundId)
        val missing = RollCallManager.missingFor(roundId)
        val sb = StringBuilder()
        sb.append("Responded: ${responded.size} / ${RollCallManager.expectedCountFor(roundId)}\n\n")
        if (responded.isNotEmpty()) {
            sb.append("✅ Safe:\n")
            responded.values.forEach { sb.append("  • $it\n") }
            sb.append("\n")
        }
        if (missing.isNotEmpty()) {
            sb.append("⚠ Abhi tak respond nahi kiya:\n")
            missing.values.forEach { sb.append("  • $it\n") }
        } else if (RollCallManager.expectedCountFor(roundId) > 0) {
            sb.append("Sab respond kar chuke hain ✅")
        }
        view.text = sb.toString()
    }

    private fun refreshRollCallStatusDialogIfShowing(roundId: String) {
        val view = openRollCallStatusView ?: return
        if (openRollCallDialogRoundId != roundId) return
        updateRollCallStatusText(roundId, view)
    }

    private fun showRollCallPromptDialog(roundId: String) {
        AlertDialog.Builder(this)
            .setTitle("📋 Roll call")
            .setMessage("Group organizer ne poocha hai — sab safe ho?")
            .setCancelable(false)
            .setPositiveButton("✅ Main safe hoon") { _, _ ->
                meshService?.sendRollCallResponse(roundId)
            }
            .setNegativeButton("Baad mein", null)
            .show()
    }

    // ---------------- Detained-person quick-log ----------------

    private fun showDetainedDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val nameInput = EditText(this).apply { hint = "Kaun detain hua (naam/pehchaan)" }
        val locationInput = EditText(this).apply { hint = "Last seen location (ya jitna pata ho)" }
        layout.addView(nameInput)
        layout.addView(locationInput)

        // Best-effort auto-fill from GPS - editable, not required. Fresh fix
        // preferred (short timeout so the dialog doesn't feel stuck), falls
        // back to cached; if a real address resolves, replaces the coordinates
        // with something the legal/family contact can actually recognize.
        LocationHelper.getBestEffortLocation(this, timeoutMs = 2500L) { loc ->
            if (loc == null) return@getBestEffortLocation
            locationInput.setText("${loc.latitude},${loc.longitude}")
            LocationHelper.reverseGeocode(this, loc) { address ->
                if (address != null && locationInput.text.toString() == "${loc.latitude},${loc.longitude}") {
                    locationInput.setText(address)
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("🚔 Detained-person alert")
            .setMessage("Ye poore group ko URGENT broadcast bhejega taaki legal team/family tak turant pahunche. Sirf real detention/arrest ki info ke liye use karo.")
            .setView(layout)
            .setPositiveButton("Bhejo") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "Unknown" }
                val location = locationInput.text.toString().trim().ifEmpty { "location unknown" }
                val timeStr = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val details = "$name — last seen at $location, $timeStr"
                val sentId = meshService?.sendDetainedAlert(details) ?: ""
                adapter.addMessage(DisplayMessage("You", "🚔 DETAINED ALERT (aapne bheja) — $details", System.currentTimeMillis(), true, priority = Priority.URGENT, trust = TrustBadge.MINE, id = sentId))
                recyclerView.scrollToPosition(adapter.itemCount - 1)
                Toast.makeText(this, "Detained alert bhej diya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetainedLogDialog() {
        val alerts = MessageStore.displayedMessages
            .filter { it.type == MessageType.DETAINED_ALERT }
            .sortedByDescending { it.ts }
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        val body = if (alerts.isEmpty()) {
            "Abhi tak koi detained-person alert nahi aaya."
        } else {
            alerts.joinToString("\n\n") { msg ->
                val plain = CryptoUtils.decryptForEpoch(msg.payload, getGroupPassphrase(), msg.epoch) ?: "(decrypt nahi ho paya)"
                "🚔 ${fmt.format(Date(msg.ts))} — ${msg.senderName} ne bheja:\n$plain"
            }
        }
        val scroll = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply { text = body; setPadding(40, 20, 40, 20); textSize = 14f })
        }
        AlertDialog.Builder(this)
            .setTitle("📋 Detained Log (is phone pe dikhne wale sab alerts)")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------------- Pinned dispersal point ----------------

    private fun showSetDispersalPointDialog() {
        val input = EditText(this).apply {
            hint = "Dispersal point / meeting point"
            val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
            setText(prefs.getString(pinnedDispersalPrefKey(), ""))
        }
        AlertDialog.Builder(this)
            .setTitle("📌 Dispersal point set/re-broadcast karo")
            .setMessage("Ye poore group ko URGENT pin ke roop mein bhejega aur sab ke chat ke upar ek fixed banner mein dikhega (normal messages ki tarah scroll mein kho nahi jaayega). Naya pin purane ko replace kar deta hai.")
            .setView(input)
            .setPositiveButton("Bhejo/Re-broadcast") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                meshService?.sendPinnedDispersal(text)
                savePinnedDispersal(text, "You")
                updatePinnedBanner()
                Toast.makeText(this, "Dispersal point bhej diya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinnedDispersalPrefKey() = if (isDecoySession) "pinned_dispersal_duress" else "pinned_dispersal_main"

    private fun savePinnedDispersal(text: String, sender: String) {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        prefs.edit()
            .putString(pinnedDispersalPrefKey(), text)
            .putString("${pinnedDispersalPrefKey()}_sender", sender)
            .apply()
    }

    private fun clearPinnedDispersal() {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        prefs.edit().remove(pinnedDispersalPrefKey()).remove("${pinnedDispersalPrefKey()}_sender").apply()
        updatePinnedBanner()
    }

    private fun updatePinnedBanner() {
        if (!::pinnedBannerContainer.isInitialized) return
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        val text = prefs.getString(pinnedDispersalPrefKey(), null)
        if (text.isNullOrEmpty()) {
            pinnedBannerContainer.visibility = View.GONE
            return
        }
        val sender = prefs.getString("${pinnedDispersalPrefKey()}_sender", "") ?: ""
        pinnedBannerText.text = "📌 Dispersal point ($sender): $text"
        pinnedBannerContainer.visibility = View.VISIBLE
    }

    // ---------------- Tear gas / injury first-aid quick reference (offline) ----------------

    private fun showFirstAidDialog() {
        val scroll = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.first_aid_reference)
                setPadding(40, 20, 40, 20)
                textSize = 14f
            })
        }
        AlertDialog.Builder(this)
            .setTitle("🩹 Tear gas / injury — first aid (offline)")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------------- Know Your Rights (local, editable, offline reference) ----------------

    private fun showKnowYourRightsDialog() {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        val defaultText = getString(R.string.know_your_rights_default)
        val savedText = prefs.getString("rights_notes", null) ?: defaultText

        val textInput = EditText(this).apply {
            setText(savedText)
            minLines = 10
            gravity = android.view.Gravity.TOP
        }
        val scroll = ScrollView(this).apply { addView(textInput) }

        AlertDialog.Builder(this)
            .setTitle("📖 Know Your Rights (edit karke apna banao)")
            .setMessage("Important: ye sirf ek editable local note hai, koi verified legal advice nahi. Ise apne local lawyer/legal aid/human rights group se verify karke, apne state/city ke sahi helpline numbers aur current kanoon ke hisaab se khud update karo — protest se PEHLE. Baad mein ye poora offline available rahega.")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("rights_notes", textInput.text.toString()).apply()
                Toast.makeText(this, "Saved — ab ye offline bhi available rahega", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }



    private fun confirmAndPanicWipe() {
        val scopeNote = if (isDecoySession) "Ye sirf decoy chat clear karega." else "Ye is phone ke saare mesh messages turant delete kar dega."
        AlertDialog.Builder(this)
            .setTitle("Sab data delete karein?")
            .setMessage("$scopeNote Ye undo nahi ho sakta. Doosre phones ka data isse affect nahi hota.")
            .setPositiveButton("Haan, delete karo") { _, _ ->
                meshService?.panicWipe()
                adapter.clear()
                updateGroupCountLabel()
                clearPinnedDispersal()
                Toast.makeText(this, "Sab data delete ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Setup / lifecycle ----------------

    private fun applyAutoWipeIfConfigured() {
        val prefs = getSharedPreferences("mesh_prefs", MODE_PRIVATE)
        val hours = prefs.getLong("auto_wipe_hours", 0L)
        if (hours <= 0) return
        MessageStore.purgeDisplayedOlderThan(hours * 60 * 60 * 1000L)
    }

    private fun loadPersistedHistory() {
        applyAutoWipeIfConfigured()
        val passphrase = getGroupPassphrase()
        for (msg in MessageStore.displayedMessages) {
            val plain = CryptoUtils.decryptForEpoch(msg.payload, passphrase, msg.epoch) ?: continue
            val isMine = msg.senderId == myUserId
            val trust = if (isMine) TrustBadge.MINE else TrustBadge.UNSIGNED // re-verified live on future receives
            adapter.addMessage(toDisplayMessage(msg, plain, isMine, trust))
        }
        if (adapter.itemCount > 0) recyclerView.scrollToPosition(adapter.itemCount - 1)
    }

    private fun ensureBluetoothOnThenRequestPermissions() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Ye device Bluetooth support nahi karta", Toast.LENGTH_LONG).show()
            return
        }
        permissionLauncher.launch(requiredPermissions)
    }

    private fun startMeshService() {
        val intent = Intent(this, BleMeshService::class.java)
        intent.putExtra(BleMeshService.EXTRA_PROFILE, activeProfile)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ---------------- Quick panic-wipe shortcut (Volume-Down x3) ----------------
    // Best-effort convenience only — works while the app is open/foreground.
    // It can't intercept the hardware volume key while the screen is off/locked
    // (that needs invasive device-admin/accessibility permissions this app
    // deliberately doesn't request) - the on-screen "Wipe All" button is the
    // reliable path if the screen is already locked.
    private var lastVolumeDownPressTimes = mutableListOf<Long>()

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            lastVolumeDownPressTimes.add(now)
            lastVolumeDownPressTimes = lastVolumeDownPressTimes.filter { now - it < 1500 }.toMutableList()
            if (lastVolumeDownPressTimes.size >= 3) {
                lastVolumeDownPressTimes.clear()
                confirmAndPanicWipe()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(serviceConnection)
        if (::recyclerView.isInitialized) {
            try { unregisterReceiver(messageReceiver) } catch (e: Exception) { }
        }
        aiManager.release()
    }
}
