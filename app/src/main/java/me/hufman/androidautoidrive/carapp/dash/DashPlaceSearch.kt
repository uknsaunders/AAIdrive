package me.hufman.androidautoidrive.carapp.dash

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import me.hufman.androidautoidrive.BuildConfig
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import me.hufman.androidautoidrive.maps.MapPlaceSearch
import me.hufman.androidautoidrive.maps.MapResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Destination search for the iDrive "Search" entry, using OpenStreetMap's free Nominatim service.
 * Results near the car are preferred.
 */
class DashPlaceSearch(private val locationProvider: CarLocationProvider): MapPlaceSearch {
	companion object {
		const val TAG = "i3DashSearch"
		const val USER_AGENT = "i3Dash/1.0 (personal in-car dashboard)"
		/** ///filled.count.soap, filled.count.soap, or with spaces from the car's speller */
		val W3W_PATTERN = Regex("""^\s*(?:/{0,3})\s*([\p{L}]+)\s*[.\s]\s*([\p{L}]+)\s*[.\s]\s*([\p{L}]+)\s*$""")
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	override fun searchLocationsAsync(query: String): Deferred<List<MapResult>> = scope.async {
		val words = what3words(query)
		if (words != null) return@async words
		try {
			val here = locationProvider.currentLocation
			val near = if (here != null) {
				val lat = here.latitude
				val lon = here.longitude
				"&viewbox=${lon - 0.5},${lat + 0.35},${lon + 0.5},${lat - 0.35}"
			} else ""
			val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=" +
					URLEncoder.encode(query, "UTF-8") + near
			val results = JSONArray(fetch(url))
			(0 until results.length()).mapNotNull { i ->
				val item = results.getJSONObject(i)
				val lat = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
				val lon = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
				val display = item.optString("display_name")
				val name = item.optString("name").ifBlank { display.substringBefore(",") }
				val location = LatLong(lat, lon)
				val distance = here?.let { LatLong(it.latitude, it.longitude).distanceFrom(location).toFloat() }
				MapResult("$lat,$lon", name, display.substringAfter(", ", display), location, distance)
			}
		} catch (e: Exception) {
			Log.w(TAG, "Search failed for $query", e)
			emptyList()
		}
	}

	override fun resultInformationAsync(resultId: String): Deferred<MapResult?> = scope.async {
		val parts = resultId.split(",")
		val lat = parts.getOrNull(0)?.toDoubleOrNull()
		val lon = parts.getOrNull(1)?.toDoubleOrNull()
		if (lat != null && lon != null) {
			MapResult(resultId, "", null, LatLong(lat, lon))
		} else null
	}

	/** Looks up a what3words address, if the search looks like one and a key is set */
	private fun what3words(query: String): List<MapResult>? {
		val key = BuildConfig.W3W_API_KEY
		val match = W3W_PATTERN.matchEntire(query) ?: return null
		// plain three-word searches like "tesco extra st" are probably places, unless they used dots or slashes
		if (!query.contains('.') && !query.contains('/')) return null
		val words = match.groupValues.drop(1).joinToString(".") { it.lowercase() }
		if (key.isBlank()) {
			Log.w(TAG, "No what3words key set, can't look up ///$words")
			return listOf()
		}
		return try {
			val json = JSONObject(fetch("https://api.what3words.com/v3/convert-to-coordinates?key=" +
					URLEncoder.encode(key, "UTF-8") + "&words=" + URLEncoder.encode(words, "UTF-8")))
			val coords = json.optJSONObject("coordinates") ?: return listOf()
			val lat = coords.getDouble("lat")
			val lon = coords.getDouble("lng")
			val location = LatLong(lat, lon)
			val here = locationProvider.currentLocation
			val distance = here?.let { LatLong(it.latitude, it.longitude).distanceFrom(location).toFloat() }
			val place = json.optString("nearestPlace").ifBlank { null }
			listOf(MapResult("$lat,$lon", "///$words", place, location, distance))
		} catch (e: Exception) {
			Log.w(TAG, "what3words lookup failed for $words", e)
			listOf()
		}
	}

	private fun fetch(url: String): String {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.setRequestProperty("User-Agent", USER_AGENT)
		connection.connectTimeout = 10000
		connection.readTimeout = 10000
		return try {
			connection.inputStream.bufferedReader().use { it.readText() }
		} finally {
			connection.disconnect()
		}
	}
}
