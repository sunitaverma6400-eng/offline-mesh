package com.offline.mesh

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Encrypted evidence gallery - deliberately its OWN screen/activity, separate
 * from the mesh chat: own Access PIN, own optional Duress PIN (shows a genuinely
 * empty decoy vault, never touches real evidence), own "Wipe Vault" that only
 * clears the vault, never the chat. See EvidenceVaultManager for the storage/
 * crypto side of this.
 *
 * Capture flow writes photos/videos straight from the camera into app-private
 * cache, encrypts them into the vault, then deletes the plaintext temp file -
 * so evidence never sits unencrypted in the phone's shared gallery/MediaStore.
 * "Import" is provided too for evidence that already exists elsewhere, but
 * that plaintext original obviously stays wherever it already was - capture is
 * the stronger option when it's available.
 */
class EvidenceVaultActivity : AppCompatActivity() {

    private lateinit var pinScreen: View
    private lateinit var vaultScreen: View
    private lateinit var pinInput: EditText
    private lateinit var namespaceLabel: TextView
    private lateinit var grid: GridView

    private var currentNamespace: String? = null
    private var currentPin: String? = null
    private var items: MutableList<EvidenceItem> = mutableListOf()
    private lateinit var adapter: EvidenceGridAdapter

    private var pendingCaptureFile: File? = null
    private var pendingCaptureType: EvidenceType? = null

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) onCaptureFinished() else pendingCaptureFile?.delete() }

    private val captureVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success -> if (success) onCaptureFinished() else pendingCaptureFile?.delete() }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) importFromUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same rationale as MainActivity: evidence must never leak into a
        // screenshot, recent-apps thumbnail, or screen recording.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_evidence_vault)

        pinScreen = findViewById(R.id.pinScreen)
        vaultScreen = findViewById(R.id.vaultScreen)
        pinInput = findViewById(R.id.editVaultPin)
        namespaceLabel = findViewById(R.id.textNamespaceLabel)
        grid = findViewById(R.id.gridEvidence)

        adapter = EvidenceGridAdapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> viewItem(items[position]) }
        grid.setOnItemLongClickListener { _, _, position, _ -> confirmDeleteItem(items[position]); true }

        findViewById<Button>(R.id.buttonUnlockVault).setOnClickListener { attemptUnlock() }
        findViewById<Button>(R.id.buttonCapturePhoto).setOnClickListener { startCapture(EvidenceType.PHOTO) }
        findViewById<Button>(R.id.buttonCaptureVideo).setOnClickListener { startCapture(EvidenceType.VIDEO) }
        findViewById<Button>(R.id.buttonImportEvidence).setOnClickListener { importLauncher.launch("*/*") }
        findViewById<Button>(R.id.buttonVaultSettings).setOnClickListener { showDuressPinDialog() }
        findViewById<Button>(R.id.buttonLockVault).setOnClickListener { lockVault() }
        findViewById<Button>(R.id.buttonWipeVault).setOnClickListener { confirmWipeVault() }

        if (!EvidenceVaultManager.isAccessPinSet(this)) {
            showFirstTimeSetupDialog()
        }
    }

    // ---------------- PIN setup / unlock ----------------

    private fun showFirstTimeSetupDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val p1 = EditText(this).apply { hint = "Naya vault Access PIN"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val p2 = EditText(this).apply { hint = "PIN dobara likho"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        layout.addView(p1); layout.addView(p2)
        AlertDialog.Builder(this)
            .setTitle("🔒 Evidence Vault set up karo")
            .setMessage("Ye PIN chat ke App PIN se ALAG hai. Ye evidence gallery ka apna access PIN hai — bhoolna mat, ye kahin store/recover nahi hota.")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val a = p1.text.toString().trim()
                val b = p2.text.toString().trim()
                if (a.isEmpty() || a != b) {
                    Toast.makeText(this, "PIN match nahi hua ya khaali hai — phir try karo", Toast.LENGTH_LONG).show()
                    showFirstTimeSetupDialog()
                } else {
                    EvidenceVaultManager.setAccessPin(this, a)
                    Toast.makeText(this, "Vault PIN set ho gaya", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun attemptUnlock() {
        val pin = pinInput.text.toString().trim()
        val ns = EvidenceVaultManager.checkPin(this, pin)
        if (ns == null) {
            pinInput.error = "Galat PIN"
            return
        }
        currentNamespace = ns
        currentPin = pin
        pinInput.text.clear()
        pinScreen.visibility = View.GONE
        vaultScreen.visibility = View.VISIBLE
        namespaceLabel.text = if (ns == EvidenceVaultManager.NAMESPACE_DECOY)
            "Vault (decoy — khaali)" else "Vault"
        refreshItems()
    }

    private fun lockVault() {
        currentNamespace = null
        currentPin = null
        items = mutableListOf()
        adapter.notifyDataSetChanged()
        vaultScreen.visibility = View.GONE
        pinScreen.visibility = View.VISIBLE
    }

    private fun refreshItems() {
        val ns = currentNamespace ?: return
        val pin = currentPin ?: return
        items = EvidenceVaultManager.loadManifest(this, pin, ns).sortedByDescending { it.ts }.toMutableList()
        adapter.notifyDataSetChanged()
    }

    // ---------------- Capture directly into the vault ----------------

    private fun startCapture(type: EvidenceType) {
        val ns = currentNamespace ?: return
        try {
            val captureDir = File(cacheDir, "vault_capture").apply { mkdirs() }
            val ext = if (type == EvidenceType.PHOTO) ".jpg" else ".mp4"
            val file = File(captureDir, "capture_${System.currentTimeMillis()}$ext")
            pendingCaptureFile = file
            pendingCaptureType = type
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            if (type == EvidenceType.PHOTO) takePictureLauncher.launch(uri)
            else captureVideoLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera open nahi ho paya: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onCaptureFinished() {
        val file = pendingCaptureFile ?: return
        val type = pendingCaptureType ?: return
        val ns = currentNamespace ?: return
        val pin = currentPin ?: return
        try {
            val bytes = file.readBytes()
            file.delete() // plaintext copy gone the moment it's encrypted into the vault
            items = EvidenceVaultManager.addItem(this, pin, ns, type, "", bytes)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Vault mein encrypted save ho gaya", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save karne mein error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Import from gallery (plaintext original stays wherever it was) ----------------

    private fun importFromUri(uri: Uri) {
        val ns = currentNamespace ?: return
        val pin = currentPin ?: return
        try {
            val mime = contentResolver.getType(uri) ?: ""
            val type = if (mime.startsWith("video")) EvidenceType.VIDEO else EvidenceType.PHOTO
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            items = EvidenceVaultManager.addItem(this, pin, ns, type, "", bytes)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Vault mein import ho gaya (original file jahan tha wahin bhi rahega)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import karne mein error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- View / delete ----------------

    private fun viewItem(item: EvidenceItem) {
        val ns = currentNamespace ?: return
        val pin = currentPin ?: return
        val bytes = EvidenceVaultManager.readItemBytes(this, pin, ns, item) ?: run {
            Toast.makeText(this, "Ye item decrypt nahi ho paya", Toast.LENGTH_SHORT).show()
            return
        }
        when (item.type) {
            EvidenceType.PHOTO -> {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val imageView = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true }
                AlertDialog.Builder(this)
                    .setView(imageView)
                    .setPositiveButton("Close", null)
                    .show()
            }
            EvidenceType.VIDEO -> {
                try {
                    val tempDir = File(cacheDir, "vault_playback").apply { mkdirs() }
                    val tempFile = File(tempDir, "${item.id}.mp4")
                    tempFile.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                    // Best-effort cleanup of the plaintext playback copy - can't get a
                    // reliable completion callback from an external player, so this is
                    // a delayed cleanup rather than "on close".
                    Handler(Looper.getMainLooper()).postDelayed({ tempFile.delete() }, 10 * 60 * 1000L)
                } catch (e: Exception) {
                    Toast.makeText(this, "Video open nahi ho paya: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDeleteItem(item: EvidenceItem) {
        val ns = currentNamespace ?: return
        val pin = currentPin ?: return
        AlertDialog.Builder(this)
            .setTitle("Ye evidence delete karein?")
            .setMessage("Ye sirf is phone se delete hoga, undo nahi ho sakta.")
            .setPositiveButton("Delete") { _, _ ->
                EvidenceVaultManager.deleteItem(this, pin, ns, item)
                refreshItems()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Duress PIN settings ----------------

    private fun showDuressPinDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val duressInput = EditText(this).apply {
            hint = "Vault Duress PIN (blank = disable)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        layout.addView(duressInput)
        AlertDialog.Builder(this)
            .setTitle("Vault Duress PIN")
            .setMessage("Ye PIN daalne par ek bilkul KHAALI decoy vault khulega — real evidence ko bilkul touch nahi karega. Ye chat ke Duress PIN se alag/independent hai, chahe same number ho ya na ho.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val pin = duressInput.text.toString().trim()
                if (pin.isEmpty()) {
                    EvidenceVaultManager.clearDuressPin(this)
                    Toast.makeText(this, "Duress PIN hata diya", Toast.LENGTH_SHORT).show()
                } else if (pin == currentPin) {
                    Toast.makeText(this, "Duress PIN, Access PIN jaisa nahi ho sakta", Toast.LENGTH_LONG).show()
                } else {
                    EvidenceVaultManager.setDuressPin(this, pin)
                    Toast.makeText(this, "Duress PIN set ho gaya", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Export encrypted backup") { _, _ -> exportVaultBackup() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Zips this namespace's already-encrypted vault files into this app's own
     *  external-files folder (no storage permission needed) - see
     *  EvidenceVaultManager.exportEncryptedBackup for exactly what that means. */
    private fun exportVaultBackup() {
        val ns = currentNamespace
        val pin = currentPin
        if (ns == null || pin == null) return
        try {
            val outFile = EvidenceVaultManager.exportEncryptedBackup(this, pin, ns)
            if (outFile != null) {
                AlertDialog.Builder(this)
                    .setTitle("Backup ban gaya")
                    .setMessage("Encrypted backup yahan save hua hai (abhi bhi PIN-protected, plaintext nahi hai):\n\n${outFile.absolutePath}\n\nIse USB se PC pe copy karo ya kisi external SD/OTG drive pe move karo — phone confiscate ho to bhi evidence safe rahega.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                Toast.makeText(this, "Is vault mein export karne ke liye kuch nahi hai", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Export fail ho gaya", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Wipe (scoped to current namespace only) ----------------

    private fun confirmWipeVault() {
        val ns = currentNamespace ?: return
        val scopeNote = if (ns == EvidenceVaultManager.NAMESPACE_DECOY)
            "Ye sirf decoy vault clear karega." else "Ye real evidence vault clear karega."
        AlertDialog.Builder(this)
            .setTitle("Vault wipe karein?")
            .setMessage("$scopeNote Mesh chat is se bilkul affect nahi hoti. Ye undo nahi ho sakta.")
            .setPositiveButton("Haan, wipe karo") { _, _ ->
                EvidenceVaultManager.wipeNamespace(this, ns)
                refreshItems()
                Toast.makeText(this, "Vault wipe ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Grid adapter ----------------

    private inner class EvidenceGridAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@EvidenceVaultActivity)
                .inflate(R.layout.item_evidence, parent, false)
            val item = items[position]
            val thumb = view.findViewById<ImageView>(R.id.imageThumb)
            val videoBadge = view.findViewById<TextView>(R.id.textVideoBadge)
            val timeLabel = view.findViewById<TextView>(R.id.textEvidenceTime)

            val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
            timeLabel.text = fmt.format(Date(item.ts))
            videoBadge.visibility = if (item.type == EvidenceType.VIDEO) View.VISIBLE else View.GONE

            if (item.type == EvidenceType.PHOTO) {
                val ns = currentNamespace
                val pin = currentPin
                if (ns != null && pin != null) {
                    val bytes = EvidenceVaultManager.readItemBytes(this@EvidenceVaultActivity, pin, ns, item)
                    if (bytes != null) {
                        // Thumbnail-scale on the fly - full bytes stay encrypted on disk,
                        // this decoded bitmap only ever lives in memory.
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        thumb.setImageBitmap(bmp)
                    } else {
                        thumb.setImageBitmap(null)
                    }
                }
            } else {
                thumb.setImageDrawable(null)
                thumb.setBackgroundColor(0xFF333333.toInt())
            }
            return view
        }
    }
}
