package com.offline.mesh

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/** Trust badge shown next to a sender's name - mirrors PeerTrustStore.TrustResult. */
enum class TrustBadge { VERIFIED, MISMATCH, UNSIGNED, MINE }

data class DisplayMessage(
    val senderName: String,
    val text: String,
    val ts: Long,
    val isMine: Boolean,
    val type: MessageType = MessageType.TEXT,
    val payloadBase64: String? = null, // image or audio bytes, base64
    val priority: Priority = Priority.NORMAL,
    val trust: TrustBadge = TrustBadge.UNSIGNED
)

class ChatAdapter(private val items: MutableList<DisplayMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val sender: TextView = view.findViewById(R.id.textSender)
        val body: TextView = view.findViewById(R.id.textBody)
        val time: TextView = view.findViewById(R.id.textTime)
        val image: ImageView = view.findViewById(R.id.imageBody)
        val trustBadge: TextView = view.findViewById(R.id.textTrustBadge)
        val urgentBadge: TextView = view.findViewById(R.id.textUrgentBadge)
        val playAudioButton: Button = view.findViewById(R.id.buttonPlayAudio)
        val openLocationButton: Button = view.findViewById(R.id.buttonOpenLocation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.sender.text = if (item.isMine) "You" else item.senderName
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.time.text = fmt.format(Date(item.ts))

        holder.urgentBadge.visibility = if (item.priority == Priority.URGENT) View.VISIBLE else View.GONE

        holder.trustBadge.text = when (item.trust) {
            TrustBadge.MINE -> ""
            TrustBadge.VERIFIED -> "✓ verified"
            TrustBadge.MISMATCH -> "⚠ key changed!"
            TrustBadge.UNSIGNED -> "unsigned"
        }
        holder.trustBadge.setTextColor(
            if (item.trust == TrustBadge.MISMATCH) 0xFFB00020.toInt() else 0xFF888888.toInt()
        )

        // Reset all optional views before deciding what this item needs
        holder.body.visibility = View.GONE
        holder.image.visibility = View.GONE
        holder.playAudioButton.visibility = View.GONE
        holder.openLocationButton.visibility = View.GONE

        when (item.type) {
            MessageType.IMAGE -> {
                holder.image.visibility = View.VISIBLE
                try {
                    val bytes = Base64.decode(item.payloadBase64, Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    holder.image.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    holder.image.setImageBitmap(null)
                }
            }
            MessageType.AUDIO -> {
                holder.playAudioButton.visibility = View.VISIBLE
                holder.playAudioButton.setOnClickListener {
                    playAudio(holder.itemView.context, item.payloadBase64)
                }
            }
            MessageType.LOCATION -> {
                holder.body.visibility = View.VISIBLE
                holder.body.text = "📍 ${item.text}"
                holder.openLocationButton.visibility = View.VISIBLE
                holder.openLocationButton.setOnClickListener {
                    openLocation(holder.itemView.context, item.text)
                }
            }
            else -> {
                holder.body.visibility = View.VISIBLE
                holder.body.text = item.text
            }
        }
    }

    private fun playAudio(context: android.content.Context, base64: String?) {
        if (base64 == null) return
        try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val tempFile = File.createTempFile("mesh_audio", ".m4a", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(bytes) }
            val player = MediaPlayer()
            player.setDataSource(tempFile.absolutePath)
            player.prepare()
            player.start()
            player.setOnCompletionListener { it.release(); tempFile.delete() }
        } catch (e: Exception) {
            Toast.makeText(context, "Audio play nahi ho paya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLocation(context: android.content.Context, latLon: String) {
        try {
            val parts = latLon.split(",")
            val lat = parts[0].trim()
            val lon = parts[1].trim()
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(context, "Location open nahi ho payi", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = items.size

    fun addMessage(msg: DisplayMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }
}
