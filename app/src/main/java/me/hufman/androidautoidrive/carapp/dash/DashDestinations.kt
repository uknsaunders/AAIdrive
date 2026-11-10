package me.hufman.androidautoidrive.carapp.dash

import android.util.Log
import me.hufman.androidautoidrive.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Resolved(val name: String, val detail: String?, val lat: Double, val lon: Double)
data class ResolveResult(val items: List<Resolved>, val error: String? = null)

/**
 * Turns whatever was typed or shared into places: names and postcodes (OpenStreetMap Nominatim),
 * what3words addresses, Google Maps links, geo: links and plain coordinates.
 * Also finds charging points near a spot.
 */
object DashDestinations {
	const val TAG = "i3DashDest"
	const val USER_AGENT = "i3Dash/1.0 (personal in-car dashboard)"
	private val TLDS = setOf("com", "co", "uk", "org", "net", "je", "gg", "io", "gov", "edu", "app", "info")
	private val W3W_LINK = Regex("""(?:w3w\.co|what3words\.com)/(?:[a-z]{2}/)?(\p{L}+\.\p{L}+\.\p{L}+)""", RegexOption.IGNORE_CASE)
	private val URL_PATTERN = Regex("""https?://\S+""")
	private val COORDS = Regex("""^\s*(-?\d{1,3}\.\d+)\s*[,;\s]\s*(-?\d{1,3}\.\d+)\s*$""")

	/** "///filled.count.soap" or "filled.count.soap", but not website addresses */
	fun w3wWords(text: String): String? {
		val trimmed = text.trim()
		val slashed = trimmed.startsWith("///")
		val body = trimmed.trimStart('/').trim()
		val parts = if (slashed) body.split(Regex("[.\\s]+")) else body.split(".")
		if (parts.size != 3 || parts.any { it.isEmpty() || !it.all { c -> c.isLetter() } }) return null
		if (!slashed && (parts[0].equals("www", true) || parts[2].lowercase() in TLDS)) return null
		return parts.joinToString(".") { it.lowercase() }
	}

	fun resolve(input: String, near: Pair<Double, Double>? = null): ResolveResult {
		val text = input.trim()
		if (text.isEmpty()) return ResolveResult(emptyList())
		return try {
			val link = W3W_LINK.find(text)
			val words = w3wWords(text)
			val url = URL_PATTERN.find(text)?.value
			when {
				link != null -> what3words(link.groupValues[1])
				words != null -> what3words(words)
				url != null -> fromLink(url, text)
				text.startsWith("geo:", ignoreCase = true) -> fromGeo(text)
				COORDS.matches(text) -> {
					val m = COORDS.matchEntire(text)!!
					ResolveResult(listOf(Resolved("Dropped pin", "${m.groupValues[1]}, ${m.groupValues[2]}", m.groupValues[1].toDouble(), m.groupValues[2].toDouble())))
				}
				else -> ResolveResult(search(text, near))
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't look up $text", e)
			ResolveResult(emptyList(), "Couldn't look that up (${e.message ?: "no internet?"})")
		}
	}

	private fun what3words(words: String): ResolveResult {
		val key = BuildConfig.W3W_API_KEY
		if (key.isBlank()) return ResolveResult(emptyList(), "what3words isn't set up yet: it needs a free API key added to the app's build")
		val json = JSONObject(fetch("https://api.what3words.com/v3/convert-to-coordinates?key=" +
				URLEncoder.encode(key, "UTF-8") + "&words=" + URLEncoder.encode(words, "UTF-8")))
		json.optJSONObject("error")?.let {
			return ResolveResult(emptyList(), it.optString("message", "what3words couldn't find ///$words"))
		}
		val coords = json.getJSONObject("coordinates")
		val place = json.optString("nearestPlace").ifBlank { null }
		return ResolveResult(listOf(Resolved("///$words", place, coords.getDouble("lat"), coords.getDouble("lng"))))
	}

	private fun fromGeo(text: String): ResolveResult {
		val match = Regex("""geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
		val label = Regex("""\(([^)]+)\)""").find(text)?.groupValues?.get(1)
		val query = Regex("""[?&]q=([^&]+)""").find(text)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
		if (match != null) {
			val lat = match.groupValues[1].toDouble()
			val lon = match.groupValues[2].toDouble()
			if (lat != 0.0 || lon != 0.0) return ResolveResult(listOf(Resolved(label ?: "Shared place", null, lat, lon)))
		}
		val coordsInQuery = query?.let { COORDS.matchEntire(it) }
		if (coordsInQuery != null) {
			return ResolveResult(listOf(Resolved(label ?: "Shared place", null, coordsInQuery.groupValues[1].toDouble(), coordsInQuery.groupValues[2].toDouble())))
		}
		return if (query != null) ResolveResult(search(query, null)) else ResolveResult(emptyList(), "No place found in that link")
	}

	/** Follows Google's short links to the real map address */
	private fun expand(url: String): String {
		if (!Regex("""goo\.gl|maps\.app|g\.co/""").containsMatchIn(url)) return url
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.instanceFollowRedirects = true
		connection.connectTimeout = 10000
		connection.readTimeout = 10000
		connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) i3Dash")
		return try {
			connection.inputStream.close()
			var target = connection.url.toString()
			// in Europe, Google may bounce through a consent page that carries the real link
			if (target.contains("consent.")) {
				Regex("""[?&]continue=([^&]+)""").find(target)?.groupValues?.get(1)?.let {
					target = URLDecoder.decode(it, "UTF-8")
				}
			}
			target
		} finally {
			connection.disconnect()
		}
	}

	private fun fromLink(url: String, fullText: String): ResolveResult {
		val expanded = expand(url)
		W3W_LINK.find(expanded)?.let { return what3words(it.groupValues[1]) }
		val decoded = try { URLDecoder.decode(expanded, "UTF-8") } catch (e: Exception) { expanded }
		// a shared place often starts with its name on the first line
		val firstLine = fullText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
		val sharedName = firstLine?.takeIf { !it.startsWith("http") && it.length < 80 }
		val pathName = Regex("""/maps/place/([^/@?]+)""").find(decoded)?.groupValues?.get(1)?.replace('+', ' ')

		val pin = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""").find(decoded)
				?: Regex("""[?&](?:q|ll|destination|daddr|query)=(-?\d+\.\d+)[ ,+]+(-?\d+\.\d+)""").find(decoded)
				?: Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(decoded)
		if (pin != null) {
			return ResolveResult(listOf(Resolved(sharedName ?: pathName ?: "Shared place", null, pin.groupValues[1].toDouble(), pin.groupValues[2].toDouble())))
		}
		val queryText = pathName ?: Regex("""[?&](?:q|query|destination|daddr)=([^&]+)""").find(decoded)?.groupValues?.get(1)?.replace('+', ' ') ?: sharedName
		if (queryText != null) return ResolveResult(search(queryText, null))
		return ResolveResult(emptyList(), "Couldn't find a place in that link")
	}

	fun search(query: String, near: Pair<Double, Double>?): List<Resolved> {
		val bias = if (near != null) {
			"&viewbox=${near.second - 0.5},${near.first + 0.35},${near.second + 0.5},${near.first - 0.35}"
		} else ""
		val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=" + URLEncoder.encode(query, "UTF-8") + bias
		val results = JSONArray(fetch(url))
		val items = mutableListOf<Resolved>()
		for (i in 0 until results.length()) {
			val item = results.getJSONObject(i)
			val lat = item.optString("lat").toDoubleOrNull() ?: continue
			val lon = item.optString("lon").toDoubleOrNull() ?: continue
			val display = item.optString("display_name")
			val name = item.optString("name").ifBlank { display.substringBefore(",") }
			items.add(Resolved(name, display.substringAfter(", ", display), lat, lon))
		}
		return items
	}

	/** Charging points from OpenStreetMap near a spot, nearest first */
	fun chargers(lat: Double, lon: Double, radiusMeters: Int = 25000): List<Resolved> {
		val query = "[out:json][timeout:15];node[\"amenity\"=\"charging_station\"](around:$radiusMeters,$lat,$lon);out body 60;"
		val elements = JSONObject(fetch("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8"))).optJSONArray("elements") ?: return emptyList()
		val items = mutableListOf<Resolved>()
		for (i in 0 until elements.length()) {
			val node = elements.getJSONObject(i)
			val tags = node.optJSONObject("tags") ?: JSONObject()
			val name = listOf("name", "operator", "network").map { tags.optString(it) }.firstOrNull { it.isNotBlank() } ?: "Charging point"
			val points = tags.optString("capacity").ifBlank { null }?.let { "$it points" }
			val detail = listOfNotNull(tags.optString("operator").ifBlank { null }?.takeIf { it != name }, points).joinToString(" · ").ifBlank { null }
			items.add(Resolved(name, detail, node.getDouble("lat"), node.getDouble("lon")))
		}
		return items.sortedBy { distanceMeters(lat, lon, it.lat, it.lon) }
	}

	fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
		val r = Math.PI / 180
		val dLat = (lat2 - lat1) * r
		val dLon = (lon2 - lon1) * r
		val a = sin(dLat / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2).pow(2)
		return 12742000 * asin(sqrt(a))
	}

	private fun fetch(url: String): String {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.setRequestProperty("User-Agent", USER_AGENT)
		connection.connectTimeout = 12000
		connection.readTimeout = 20000
		return try {
			connection.inputStream.bufferedReader().use { it.readText() }
		} finally {
			connection.disconnect()
		}
	}
}
