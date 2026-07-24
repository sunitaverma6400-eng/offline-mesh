package com.offline.mesh

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.*

/**
 * Offline tile-cache map view — deliberately NOT using Google Maps/Mapbox SDKs,
 * because both need a live network connection (and usually an API key/account)
 * to fetch tiles, which defeats the entire point of this app. Instead:
 *
 *  - Tiles must be PRE-DOWNLOADED onto the phone (while you still have internet,
 *    before heading somewhere offline) into standard XYZ/slippy-map layout:
 *      <context.getExternalFilesDir(null)>/offline_tiles/{z}/{x}/{y}.png
 *    This is the same layout tools like `mb-util` / most tile servers export
 *    (see README section "Offline maps setup" for a concrete recipe using an
 *    open tool to cut tiles from OpenStreetMap data for one small area).
 *  - If a tile file for the current zoom/area isn't present, that tile square
 *    is just drawn as a flat grid square with lat/lon gridlines - never crashes,
 *    never shows a blank white screen, always shows SOMETHING plottable, and
 *    pinned points still render even over a missing tile.
 *  - No API key, no attribution requirement beyond whatever you owe the tile
 *    source you cut the tiles from (OSM requires attribution - keep the
 *    "© OpenStreetMap contributors" credit visible if you use OSM-derived tiles).
 *
 * This view supports simple pan (drag) and pinch-zoom, and plots [PinnedLocation]
 * markers converted from lat/lon to tile pixel space.
 */
class OfflineMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val TILE_SIZE = 256
        private const val MIN_ZOOM = 3
        private const val MAX_ZOOM = 18
        const val DEFAULT_ZOOM = 15
    }

    private var zoom = DEFAULT_ZOOM
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var pins: List<PinnedLocation> = emptyList()

    private val tileDir: File by lazy { File(context.getExternalFilesDir(null), "offline_tiles") }
    private val tileCache = LinkedHashMap<String, Bitmap?>(32, 0.75f, true)

    private val gridPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
    private val missingTileBg = Paint().apply { color = Color.parseColor("#EDEDED") }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D32F2F"); style = Paint.Style.FILL }
    private val pinRollcallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1976D2"); style = Paint.Style.FILL }
    private val pinMedicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#388E3C"); style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f; isFakeBoldText = true }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CCFFFFFF") }
    private val noTilesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 24f }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var scaleDetector: android.view.ScaleGestureDetector

    init {
        scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                if (detector.scaleFactor > 1.05f) zoom = min(MAX_ZOOM, zoom + 1)
                else if (detector.scaleFactor < 0.95f) zoom = max(MIN_ZOOM, zoom - 1)
                invalidate()
                return true
            }
        })
    }

    fun setCenter(lat: Double, lon: Double, zoomLevel: Int = zoom) {
        centerLat = lat
        centerLon = lon
        zoom = zoomLevel.coerceIn(MIN_ZOOM, MAX_ZOOM)
        tileCache.clear()
        invalidate()
    }

    fun setPins(newPins: List<PinnedLocation>) {
        pins = newPins
        invalidate()
    }

    /** True if at least one tile file exists anywhere in the offline tile cache dir. */
    fun hasAnyTiles(): Boolean = tileDir.exists() && (tileDir.listFiles()?.isNotEmpty() == true)

    // --- Web Mercator slippy-map math (standard OSM tile scheme) ---
    private fun lonToTileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)
    private fun latToTileY(lat: Double, z: Int): Double {
        val latRad = Math.toRadians(lat)
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl z)
    }

    private fun loadTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        if (tileCache.containsKey(key)) return tileCache[key]
        val file = File(tileDir, "$z/$x/$y.png")
        val bmp = if (file.exists()) {
            try { BitmapFactory.decodeFile(file.absolutePath) } catch (e: Exception) { null }
        } else null
        tileCache[key] = bmp
        if (tileCache.size > 64) {
            val oldest = tileCache.keys.firstOrNull()
            if (oldest != null) tileCache.remove(oldest)
        }
        return bmp
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val scale = 1 shl zoom
                    centerLon -= dx / TILE_SIZE / scale * 360.0
                    val latRad = Math.toRadians(centerLat)
                    val metersPerPixelLatFactor = cos(latRad).coerceAtLeast(0.1)
                    centerLat += dy / TILE_SIZE / scale * 360.0 * metersPerPixelLatFactor
                    lastTouchX = event.x; lastTouchY = event.y
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        val centerTileX = lonToTileX(centerLon, zoom)
        val centerTileY = latToTileY(centerLat, zoom)
        val centerPxX = width / 2f
        val centerPxY = height / 2f

        val tilesAcross = ceil(width.toDouble() / TILE_SIZE).toInt() + 2
        val tilesDown = ceil(height.toDouble() / TILE_SIZE).toInt() + 2
        var foundAnyTile = false

        for (tx in -tilesAcross / 2..tilesAcross / 2) {
            for (ty in -tilesDown / 2..tilesDown / 2) {
                val tileX = floor(centerTileX).toInt() + tx
                val tileY = floor(centerTileY).toInt() + ty
                if (tileX < 0 || tileY < 0 || tileX >= (1 shl zoom) || tileY >= (1 shl zoom)) continue

                val screenX = centerPxX + (tileX - centerTileX).toFloat() * TILE_SIZE
                val screenY = centerPxY + (tileY - centerTileY).toFloat() * TILE_SIZE

                val bmp = loadTile(zoom, tileX, tileY)
                if (bmp != null) {
                    foundAnyTile = true
                    canvas.drawBitmap(bmp, screenX, screenY, null)
                } else {
                    canvas.drawRect(screenX, screenY, screenX + TILE_SIZE, screenY + TILE_SIZE, missingTileBg)
                    canvas.drawRect(screenX, screenY, screenX + TILE_SIZE, screenY + TILE_SIZE, gridPaint)
                }
            }
        }

        if (!foundAnyTile) {
            canvas.drawText(
                "Offline map tiles nahi mile — kisi area ke tiles pehle se download karke",
                24f, 60f, noTilesPaint
            )
            canvas.drawText(
                "offline_tiles/ folder mein daalo (README dekho). Pins fir bhi neeche dikhenge.",
                24f, 90f, noTilesPaint
            )
        }

        // Plot pins
        for (pin in pins) {
            val tileX = lonToTileX(pin.lon, zoom)
            val tileY = latToTileY(pin.lat, zoom)
            val px = centerPxX + (tileX - centerTileX).toFloat() * TILE_SIZE
            val py = centerPxY + (tileY - centerTileY).toFloat() * TILE_SIZE
            val paint = when (pin.kind) {
                "rollcall" -> pinRollcallPaint
                "medic" -> pinMedicPaint
                else -> pinPaint
            }
            canvas.drawCircle(px, py, 16f, paint)
            canvas.drawCircle(px, py, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f })

            val textWidth = labelPaint.measureText(pin.label)
            canvas.drawRect(px + 18f, py - 34f, px + 18f + textWidth + 12f, py - 4f, labelBgPaint)
            canvas.drawText(pin.label, px + 24f, py - 12f, labelPaint)
        }
    }
}
