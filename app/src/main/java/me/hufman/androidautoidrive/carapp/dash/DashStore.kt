package me.hufman.androidautoidrive.carapp.dash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Things i3 Dash remembers on the phone: saved places, recent destinations, tariff, and a destination waiting for the car */
object DashStore {
	private const val PREFS = "i3dash_store"
	private const val MAX_PLACES = 30
	private const val MAX_RECENTS = 8
	private const val MAX_VIDEOS = 6
	private const val PENDING_MAX_AGE_MS = 45 * 60 * 1000L
	const val DEFAULT_TARIFF_PENCE = 28.0

	private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	private fun readList(context: Context, key: String): MutableList<Resolved> {
		val result = mutableListOf<Resolved>()
		try {
			val array = JSONArray(prefs(context).getString(key, "[]"))
			for (i in 0 until array.length()) {
				val item = array.getJSONObject(i)
				result.add(Resolved(item.getString("name"), null, item.getDouble("lat"), item.getDouble("lon")))
			}
		} catch (e: Exception) {
			// start fresh if the stored list is unreadable
		}
		return result
	}

	private fun writeList(context: Context, key: String, list: List<Resolved>) {
		val array = JSONArray()
		list.forEach {
			array.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon))
		}
		prefs(context).edit().putString(key, array.toString()).apply()
	}

	fun places(context: Context): List<Resolved> = readList(context, "places")

	fun savePlace(context: Context, name: String, lat: Double, lon: Double) {
		val clean = name.trim().ifEmpty { return }
		val list = readList(context, "places")
		list.removeAll { it.name.equals(clean, ignoreCase = true) }
		list.add(0, Resolved(clean, null, lat, lon))
		writeList(context, "places", list.take(MAX_PLACES))
	}

	fun deletePlace(context: Context, name: String) {
		val list = readList(context, "places")
		list.removeAll { it.name.equals(name, ignoreCase = true) }
		writeList(context, "places", list)
	}

	fun recents(context: Context): List<Resolved> = readList(context, "recents")

	fun addRecent(context: Context, name: String, lat: Double, lon: Double) {
		val list = readList(context, "recents")
		list.removeAll { it.name.equals(name, ignoreCase = true) }
		list.add(0, Resolved(name, null, lat, lon))
		writeList(context, "recents", list.take(MAX_RECENTS))
	}

	/** The driver's own Google Places key, for finding businesses by name. Kept on the phone only */
	fun googleKey(context: Context): String = prefs(context).getString("googleKey", "") ?: ""

	fun setGoogleKey(context: Context, key: String) {
		prefs(context).edit().putString("googleKey", key.trim()).apply()
	}

	/** Where to look for places when the car isn't connected: the saved Home, or the first saved place */
	fun homeArea(context: Context): Pair<Double, Double>? {
		val saved = places(context)
		val home = saved.firstOrNull { it.name.equals("Home", ignoreCase = true) } ?: saved.firstOrNull()
		return home?.let { Pair(it.lat, it.lon) }
	}

	/** Electricity price, pence per kWh */
	fun tariff(context: Context): Double = prefs(context).getFloat("tariffPence", DEFAULT_TARIFF_PENCE.toFloat()).toDouble()

	fun setTariff(context: Context, pence: Double) {
		if (pence in 1.0..200.0) prefs(context).edit().putFloat("tariffPence", pence.toFloat()).apply()
	}

	/** A destination sent from the phone, waiting until the dashboard is on the car's screen */
	fun setPending(context: Context, name: String, lat: Double, lon: Double) {
		val json = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("ts", System.currentTimeMillis())
		prefs(context).edit().putString("pending", json.toString()).apply()
	}

	fun takePending(context: Context): Resolved? {
		val raw = prefs(context).getString("pending", null) ?: return null
		prefs(context).edit().remove("pending").apply()
		return try {
			val json = JSONObject(raw)
			if (System.currentTimeMillis() - json.getLong("ts") > PENDING_MAX_AGE_MS) null
			else Resolved(json.getString("name"), null, json.getDouble("lat"), json.getDouble("lon"))
		} catch (e: Exception) {
			null
		}
	}

	fun hasPending(context: Context): Boolean = prefs(context).contains("pending")

	/** YouTube videos sent from the phone, newest first, so they can be started again from the car */
	fun videos(context: Context): JSONArray = try {
		JSONArray(prefs(context).getString("videos", "[]"))
	} catch (e: Exception) {
		JSONArray()
	}

	fun addVideo(context: Context, id: String, list: String, title: String) {
		val old = videos(context)
		val next = JSONArray()
		next.put(JSONObject().put("id", id).put("list", list).put("title", title.trim().take(60).ifEmpty { "YouTube video" }))
		for (i in 0 until old.length()) {
			val item = old.optJSONObject(i) ?: continue
			if (item.optString("id") == id && item.optString("list") == list) continue
			if (next.length() < MAX_VIDEOS) next.put(item)
		}
		prefs(context).edit().putString("videos", next.toString()).apply()
	}
}
