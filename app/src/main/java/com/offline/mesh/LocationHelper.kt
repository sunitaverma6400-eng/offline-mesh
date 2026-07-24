package com.offline.mesh

import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * BUG FIX (location sharing):
 *
 * Every "send location" path in this app used to call ONLY
 * LocationManager.getLastKnownLocation(). That method never asks the GPS/network
 * chip for a fix - it just hands back whatever fix happens to be cached, which
 * can be minutes, hours, or (if the chip hasn't been used since boot / this app
 * was freshly installed) simply null. That's why pins could be stale or "location
 * abhi available nahi" even when the phone clearly had a fix.
 *
 * On top of that, the app only ever sent raw "lat,lon" text - not a "real"
 * location a person can recognize at a glance.
 *
 * This helper fixes both:
 *  1. [getBestEffortLocation] actively requests a fresh fix with a timeout,
 *     falling back to the best cached fix (old behavior) only if that fails.
 *  2. [reverseGeocode] best-effort resolves coordinates to a human-readable
 *     address. This needs connectivity (Geocoder calls out over the network),
 *     so when the phone is genuinely offline (the normal case for this app)
 *     it silently returns null and callers fall back to showing coordinates -
 *     it never blocks or delays sending the pin itself.
 */
object LocationHelper {

    private val geocodeExecutor = Executors.newSingleThreadExecutor()

    private fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Tries to get a FRESH GPS/network fix within [timeoutMs]. Falls back to the
     * best cached getLastKnownLocation() across all providers if the fresh
     * request times out, errors, or there's no permission/provider available.
     * [callback] fires exactly once, on the main thread.
     */
    fun getBestEffortLocation(context: Context, timeoutMs: Long = 6000L, callback: (Location?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        var delivered = false

        fun deliver(loc: Location?) {
            if (delivered) return
            delivered = true
            callback(loc)
        }

        if (!hasFineLocationPermission(context)) {
            deliver(bestCachedLocation(context))
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            deliver(bestCachedLocation(context))
            return
        }

        lateinit var listener: LocationListener
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try { locationManager.removeUpdates(listener) } catch (e: SecurityException) { }
                deliver(location)
            }
            @Deprecated("Deprecated in Java", ReplaceWith(""))
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
            override fun onProviderEnabled(provider: String) { }
            override fun onProviderDisabled(provider: String) { }
        }

        try {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            deliver(bestCachedLocation(context))
            return
        }

        mainHandler.postDelayed({
            try { locationManager.removeUpdates(listener) } catch (e: SecurityException) { }
            deliver(bestCachedLocation(context))
        }, timeoutMs)
    }

    /** Old behavior, kept as the fallback: best cached fix across all providers, or null. */
    private fun bestCachedLocation(context: Context): Location? {
        if (!hasFineLocationPermission(context)) return null
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (provider in locationManager.getProviders(true)) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            }
            best
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Best-effort reverse geocode into a short human-readable address
     * (e.g. "MG Road, Bengaluru"). Needs network - if there's none, Geocoder
     * throws and we just call back with null so the caller keeps showing
     * plain coordinates. [callback] fires exactly once, on the main thread.
     */
    fun reverseGeocode(context: Context, location: Location, callback: (String?) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (!Geocoder.isPresent()) {
            mainHandler.post { callback(null) }
            return
        }
        val appContext = context.applicationContext
        geocodeExecutor.execute {
            val address: String? = try {
                val geocoder = Geocoder(appContext, Locale.getDefault())
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val a = results?.firstOrNull()
                a?.let {
                    listOfNotNull(
                        it.thoroughfare ?: it.subLocality,
                        it.locality ?: it.subAdminArea,
                        it.adminArea
                    ).joinToString(", ").ifBlank { null }
                }
            } catch (e: Exception) {
                null
            }
            mainHandler.post { callback(address) }
        }
    }
}
