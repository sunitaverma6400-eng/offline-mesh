package com.offline.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

/**
 * One screen for all the "mesh intelligence / advanced" tools, kept separate from
 * MainActivity so the main chat screen doesn't get more crowded than it already is:
 *
 *  - Mesh topology visualizer (who am I directly linked to right now)
 *  - Compression/payload-size stats (debug/health)
 *  - Contact graph top contacts (mesh intelligence)
 *  - Offline map with pinned locations (dispersal points / roll-call spot / medic)
 *  - Sub-channel picker (#general / #medic / #legal / custom) - sets the channel
 *    used by MainActivity's send button going forward
 *  - On-device translation test box
 *  - Voice-note transcript test
 *  - Battery relay status (also fires as a system notification on its own, see
 *    BatteryRelayMonitor - this is just a manual "check right now" view)
 */
class MeshToolsActivity : AppCompatActivity() {

    private lateinit var topologyView: MeshTopologyView
    private lateinit var mapView: OfflineMapView
    private lateinit var statsText: TextView
    private lateinit var contactsText: TextView
    private lateinit var channelSpinner: Spinner
    private lateinit var pinsListLayout: LinearLayout
    private lateinit var batteryStatusText: TextView

    private var meshService: BleMeshService? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshTopologyAndStats()
            refreshHandler.postDelayed(this, 5000)
        }
    }

    private val peerCountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshTopologyAndStats()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mesh_tools)
        title = "🧰 Mesh Tools"

        topologyView = findViewById(R.id.meshTopologyView)
        mapView = findViewById(R.id.offlineMapView)
        statsText = findViewById(R.id.textCompressionStats)
        contactsText = findViewById(R.id.textTopContacts)
        channelSpinner = findViewById(R.id.spinnerChannel)
        pinsListLayout = findViewById(R.id.layoutPinsList)
        batteryStatusText = findViewById(R.id.textBatteryRelayStatus)

        setupChannelPicker()
        setupPinnedLocations()
        setupTranslation()
        setupVoiceTranscript()
        refreshTopologyAndStats()

        // Same Android 14 mandatory-flag requirement noted elsewhere in this codebase
        // (BleMeshService.registerBatteryReceiver, MainActivity's messageReceiver) -
        // the 3-arg registerReceiver overload only exists on API 33+, so it must stay
        // behind this SDK check or the app crashes with NoSuchMethodError below API 33
        // (minSdk here is 26).
        val filter = IntentFilter(BleMeshService.ACTION_PEER_COUNT_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(peerCountReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(peerCountReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(peerCountReceiver) } catch (e: Exception) { }
    }

    // ---------------- Topology + compression + contact graph + battery ----------------

    private fun refreshTopologyAndStats() {
        // MainActivity's bound service isn't directly reachable from here without extra
        // plumbing, so this pulls straight from the static/singleton managers that
        // BleMeshService already writes to (ContactGraph, CompressionStats) - accurate
        // regardless of whether this screen has its own service binding.
        val nodes = ContactGraph.topContacts(20).map { c ->
            val now = System.currentTimeMillis()
            MeshTopologyView.Node(c.peerId, c.peerLabel, (now - c.lastSeenMs) < 3L * 60 * 1000)
        }
        topologyView.setNodes(nodes)

        statsText.text = CompressionStats.summaryText()

        val top = ContactGraph.topContacts(8)
        contactsText.text = if (top.isEmpty()) {
            "Abhi tak koi contact record nahi hua — jaise hi kisi se message decrypt hoga, yahan dikhega."
        } else {
            "Total known contacts: ${ContactGraph.totalKnownContacts()}\n" +
                top.joinToString("\n") { "• ${it.peerLabel} — ${it.encounterCount}x mile, last: ${fmtTime(it.lastSeenMs)}" }
        }

        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val peerCount = nodes.count { it.connectedNow }
        batteryStatusText.text = "Battery: $pct%  |  Direct-ish peers (last 3 min): $peerCount" +
            if (peerCount >= 3 && pct in 0..15) "\n⚠️ Critical relay point — charge dhoondo!" else ""
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    // ---------------- Channels ----------------

    private fun setupChannelPicker() {
        val known = ChannelsAndAnonymity.getKnownChannels(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, known)
        channelSpinner.adapter = adapter
        val active = ChannelsAndAnonymity.getActiveChannel(this)
        val idx = known.indexOf(active).takeIf { it >= 0 } ?: 0
        channelSpinner.setSelection(idx)
        channelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                ChannelsAndAnonymity.setActiveChannel(this@MeshToolsActivity, known[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.buttonAddChannel).setOnClickListener {
            val input = EditText(this)
            input.hint = "e.g. supplies"
            android.app.AlertDialog.Builder(this)
                .setTitle("Naya channel")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val name = input.text.toString().trim().lowercase()
                    if (name.isNotBlank()) {
                        ChannelsAndAnonymity.rememberChannel(this, name)
                        setupChannelPicker()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------------- Offline map + pinned locations ----------------

    private fun setupPinnedLocations() {
        refreshPins()
        findViewById<Button>(R.id.buttonAddPin).setOnClickListener {
            LocationHelper.getBestEffortLocation(this) { loc ->
                if (loc == null) {
                    Toast.makeText(this, "Location nahi mila abhi", Toast.LENGTH_SHORT).show()
                    return@getBestEffortLocation
                }
                val labelInput = EditText(this)
                labelInput.hint = "Label (e.g. Dispersal point A)"
                android.app.AlertDialog.Builder(this)
                    .setTitle("Pin add karo")
                    .setView(labelInput)
                    .setPositiveButton("Add") { _, _ ->
                        val label = labelInput.text.toString().trim().ifBlank { "Pinned spot" }
                        PinnedLocationStore.add(
                            PinnedLocation(
                                id = UUID.randomUUID().toString(),
                                label = label,
                                lat = loc.latitude,
                                lon = loc.longitude
                            )
                        )
                        mapView.setCenter(loc.latitude, loc.longitude)
                        refreshPins()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun refreshPins() {
        val pins = PinnedLocationStore.loadAll()
        mapView.setPins(pins)
        if (pins.isNotEmpty()) {
            mapView.setCenter(pins.last().lat, pins.last().lon)
        }
        pinsListLayout.removeAllViews()
        for (pin in pins) {
            val row = TextView(this)
            row.text = "📍 ${pin.label} (${String.format("%.5f", pin.lat)}, ${String.format("%.5f", pin.lon)})"
            row.setPadding(0, 8, 0, 8)
            row.setOnClickListener { mapView.setCenter(pin.lat, pin.lon) }
            row.setOnLongClickListener {
                PinnedLocationStore.remove(pin.id)
                refreshPins()
                Toast.makeText(this, "Pin hataya: ${pin.label}", Toast.LENGTH_SHORT).show()
                true
            }
            pinsListLayout.addView(row)
        }
        if (!mapView.hasAnyTiles()) {
            Toast.makeText(
                this,
                "Offline tiles nahi mile — pins fir bhi dikhenge, background grid ke saath.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------------- Translation ----------------

    private fun setupTranslation() {
        val input = findViewById<EditText>(R.id.editTranslateInput)
        val output = findViewById<TextView>(R.id.textTranslateOutput)
        val langInput = findViewById<EditText>(R.id.editTranslateLang)
        val button = findViewById<Button>(R.id.buttonTranslate)
        val manager = TranslationManager(this)

        button.setOnClickListener {
            val text = input.text.toString()
            val lang = langInput.text.toString().ifBlank { "Hindi" }
            if (text.isBlank()) return@setOnClickListener
            if (!manager.isAvailable()) {
                output.text = "Offline AI model set nahi hai — Settings > Offline AI se karo pehle."
                return@setOnClickListener
            }
            output.text = "Translate ho raha hai…"
            manager.translate(text, lang) { translated, error ->
                runOnUiThread {
                    output.text = translated ?: "Error: $error"
                }
            }
        }
    }

    // ---------------- Voice transcript ----------------

    private fun setupVoiceTranscript() {
        val output = findViewById<TextView>(R.id.textVoiceTranscript)
        val button = findViewById<Button>(R.id.buttonTranscribe)
        val transcriber = VoiceTranscriber(this)

        button.setOnClickListener {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 5501
                )
                return@setOnClickListener
            }
            if (!transcriber.isAvailable()) {
                output.text = "Speech recognizer is phone pe available nahi hai."
                return@setOnClickListener
            }
            output.text = "Sun raha hoon… bolo (silent situation ho to skip karo)"
            transcriber.startListening { transcript, error ->
                output.text = if (transcript != null)
                    "\"$transcript\"\n(AI transcript — audio khud sun ke verify karo agar possible ho)"
                else "Error: $error"
            }
        }
    }
}
