package com.offline.mesh

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simple "who am I directly connected to right now" graph — deliberately NOT a
 * full mesh map. Honest limitation: no single phone can see the WHOLE mesh's
 * shape (that would need every device to share its connection list with every
 * other device, which is both a privacy problem and a lot of extra radio
 * traffic for a "nice to look at" feature). This view only draws:
 *
 *  - This phone, in the center
 *  - Every peer it currently has a live GATT/Wi-Fi Direct link to, around it
 *  - A rough "seen recently but not connected right now" ring further out,
 *    from ContactGraph's lastSeenMs, so an organizer can tell "used to see
 *    them, don't right now" apart from "connected right now"
 *
 * Good for: "is my phone actually relaying to anyone at all", "did that link
 * just drop", debugging why a message isn't spreading. Not good for: seeing
 * the shape of the whole protest/group's mesh - no phone has that information.
 */
class MeshTopologyView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Node(val id: String, val label: String, val connectedNow: Boolean)

    private var nodes: List<Node> = emptyList()

    private val selfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1976D2"); style = Paint.Style.FILL }
    private val connectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#388E3C"); style = Paint.Style.FILL }
    private val recentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#BDBDBD"); style = Paint.Style.FILL }
    private val linePaintActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#388E3C"); strokeWidth = 4f }
    private val linePaintDashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD"); strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f; textAlign = Paint.Align.CENTER }
    private val selfLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    /** Call from the host screen whenever peer list / contact graph might have changed
     *  (e.g. on PEER_COUNT_CHANGED broadcast) - see MeshToolsActivity. */
    fun setNodes(newNodes: List<Node>) {
        nodes = newNodes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.75f

        if (nodes.isEmpty()) {
            canvas.drawCircle(cx, cy, 40f, selfPaint)
            canvas.drawText("You", cx, cy + 8f, selfLabelPaint)
            canvas.drawText("Koi peer connected/seen nahi hai abhi", cx, cy + radius * 0.6f, labelPaint)
            return
        }

        val count = nodes.size
        nodes.forEachIndexed { i, node ->
            val angle = (2 * Math.PI * i / count) - Math.PI / 2
            val nx = cx + (radius * cos(angle)).toFloat()
            val ny = cy + (radius * sin(angle)).toFloat()

            canvas.drawLine(cx, cy, nx, ny, if (node.connectedNow) linePaintActive else linePaintDashed)

            val nodePaint = if (node.connectedNow) connectedPaint else recentPaint
            canvas.drawCircle(nx, ny, 28f, nodePaint)
            canvas.drawText(node.label.take(10), nx, ny + radius * 0.14f + 10f, labelPaint)
        }

        canvas.drawCircle(cx, cy, 40f, selfPaint)
        canvas.drawText("You", cx, cy + 8f, selfLabelPaint)
    }
}
