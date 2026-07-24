package com.offline.mesh

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named pinned location (dispersal point, roll-call meeting spot, medic tent, etc.)
 * shown on the offline map. Distinct from the existing single "PINNED_DISPERSAL"
 * broadcast banner (MeshMessage.PINNED_DISPERSAL) - that's still the fast one-line
 * broadcast for "everyone go here NOW". This is a small local list of points that
 * can be plotted on the offline tile map at once (e.g. two dispersal options + the
 * roll-call spot), and can be shared into chat as a LOCATION message same as before.
 */
data class PinnedLocation(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val kind: String = "general", // "dispersal", "rollcall", "medic", "general"
    val createdAt: Long = System.currentTimeMillis()
)

object PinnedLocationStore {

    @Synchronized
    fun loadAll(): MutableList<PinnedLocation> {
        val arr = PersistentStore.loadPinnedLocationsJson()
        val list = mutableListOf<PinnedLocation>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                PinnedLocation(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    kind = o.optString("kind", "general"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    @Synchronized
    fun saveAll(locations: List<PinnedLocation>) {
        val arr = JSONArray()
        for (p in locations) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("label", p.label)
            o.put("lat", p.lat)
            o.put("lon", p.lon)
            o.put("kind", p.kind)
            o.put("createdAt", p.createdAt)
            arr.put(o)
        }
        PersistentStore.savePinnedLocationsJson(arr)
    }

    @Synchronized
    fun add(location: PinnedLocation) {
        val all = loadAll()
        all.add(location)
        saveAll(all)
    }

    @Synchronized
    fun remove(id: String) {
        val all = loadAll()
        all.removeAll { it.id == id }
        saveAll(all)
    }
}
