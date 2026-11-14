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
 * Turns whatever was typed or shared into places: names and postcodes (Google Places if a key is set, else OpenStreetMap),
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
		val key = w3wKey.ifBlank { BuildConfig.W3W_API_KEY }
		if (key.isBlank()) return ResolveResult(emptyList(), "what3words isn't set up yet: add a free API key in Send to car on the phone")
		val (code, body) = fetchWithError("https://api.what3words.com/v3/convert-to-coordinates?key=" +
				URLEncoder.encode(key, "UTF-8") + "&words=" + URLEncoder.encode(words, "UTF-8"))
		val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
		json.optJSONObject("error")?.let {
			return ResolveResult(emptyList(), it.optString("message", "what3words couldn't find ///$words"))
		}
		if (code !in 200..299) return ResolveResult(emptyList(), "what3words answered with error $code")
		val coords = json.getJSONObject("coordinates")
		val place = json.optString("nearestPlace").ifBlank { null }
		return ResolveResult(listOf(Resolved("///$words", place, coords.getDouble("lat"), coords.getDouble("lng"))))
	}

	/** Like fetch(), but reads the body of an error response too instead of throwing it away -
	 * what3words (and others) explain a rejected key in the JSON body of a 400/401, not just the HTTP status. */
	private fun fetchWithError(url: String): Pair<Int, String> {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.setRequestProperty("User-Agent", USER_AGENT)
		connection.connectTimeout = 12000
		connection.readTimeout = 20000
		return try {
			val code = connection.responseCode
			val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
				?.bufferedReader()?.use { it.readText() } ?: ""
			Pair(code, body)
		} finally {
			connection.disconnect()
		}
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

	/** The driver's own Google key for place search, set from the phone app. Blank means the free search only */
	@Volatile var googleKey: String = ""

	/** The driver's own what3words key, set from the phone app. Kept off the phone's build, so it's never inside a
	 * downloadable APK. Falls back to a build-time key (BuildConfig.W3W_API_KEY) for anyone who built their own copy
	 * with a repository secret instead. */
	@Volatile var w3wKey: String = ""

	/**
	 * Finds places by name. Three sources, best first:
	 * Google Places, if the driver has added a key: knows businesses and copes with misspellings.
	 * OpenStreetMap Nominatim: exact names, addresses and postcodes.
	 * OpenStreetMap Photon: the same places, but forgiving of spelling, for when the exact search finds nothing.
	 */
	fun search(query: String, near: Pair<Double, Double>?): List<Resolved> {
		val key = googleKey
		if (key.isNotBlank()) {
			try {
				val places = google(query, near, key)
				if (places.isNotEmpty()) return places
			} catch (e: Exception) {
				Log.w(TAG, "Google place search failed, using the free search", e)
			}
		}
		val exact = try {
			nominatim(query, near)
		} catch (e: Exception) {
			Log.w(TAG, "Exact place search failed", e)
			emptyList()
		}
		if (exact.isNotEmpty()) return exact
		return photon(query, near)
	}

	/** Checks a what3words key with one small lookup. Returns what went wrong, or null if it works.
	 * what3words answers a bad key with a 400/401 and a JSON body explaining why, not just an HTTP error -
	 * fetchWithError() reads that body instead of throwing it away. */
	fun w3wProblem(key: String): String? {
		return try {
			val (code, body) = fetchWithError("https://api.what3words.com/v3/convert-to-coordinates?key=" +
					URLEncoder.encode(key.trim(), "UTF-8") + "&words=filled.count.soap")
			if (code in 200..299) null
			else try {
				JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
					?: "what3words answered with error $code"
			} catch (e: Exception) {
				"what3words answered with error $code"
			}
		} catch (e: Exception) {
			e.message ?: "Couldn't reach what3words"
		}
	}

	/** Checks a Google key with one small search. Returns what went wrong, or null if it works */
	fun googleProblem(key: String): String? {
		return try {
			google("St Helier", null, key)
			null
		} catch (e: Exception) {
			e.message ?: "Couldn't reach Google"
		}
	}

	/** Checks an Open Charge Map key with one small lookup. Returns what went wrong, or null if it works.
	 * The car does the real lookups itself (see index.html), this is just to catch a bad key early. */
	fun ocmProblem(key: String): String? {
		val connection = try {
			(URL("https://api.openchargemap.io/v3/poi/?output=json&latitude=49.19&longitude=-2.11&distance=5&distanceunit=KM&maxresults=1&key=${URLEncoder.encode(key.trim(), "UTF-8")}&compact=true&verbose=false")
				.openConnection() as HttpURLConnection).also {
				it.connectTimeout = 10000; it.readTimeout = 10000; it.setRequestProperty("User-Agent", USER_AGENT)
			}
		} catch (e: Exception) {
			return e.message ?: "Couldn't reach Open Charge Map"
		}
		return try {
			if (connection.responseCode == 401 || connection.responseCode == 403) "Open Charge Map rejected that key"
			else if (connection.responseCode !in 200..299) "Open Charge Map answered with error ${connection.responseCode}"
			else null
		} catch (e: Exception) {
			e.message ?: "Couldn't reach Open Charge Map"
		} finally {
			connection.disconnect()
		}
	}

	/** Google Places text search: the same finder as Google Maps, so "cheeky monkies nursery" finds the nursery */
	private fun google(query: String, near: Pair<Double, Double>?, key: String): List<Resolved> {
		val body = JSONObject().put("textQuery", query).put("pageSize", 8)
		if (near != null) {
			body.put("locationBias", JSONObject().put("circle", JSONObject()
				.put("center", JSONObject().put("latitude", near.first).put("longitude", near.second))
				.put("radius", 40000.0)))
		}
		val connection = URL("https://places.googleapis.com/v1/places:searchText").openConnection() as HttpURLConnection
		connection.requestMethod = "POST"
		connection.doOutput = true
		connection.connectTimeout = 10000
		connection.readTimeout = 15000
		connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
		connection.setRequestProperty("X-Goog-Api-Key", key.trim())
		connection.setRequestProperty("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location")
		val reply = try {
			connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
			if (connection.responseCode !in 200..299) {
				val problem = try { connection.errorStream?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
				val message = try { JSONObject(problem ?: "{}").optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
				throw java.io.IOException(message?.takeIf { it.isNotBlank() } ?: "Google answered with error ${connection.responseCode}")
			}
			connection.inputStream.bufferedReader().use { it.readText() }
		} finally {
			connection.disconnect()
		}
		val places = JSONObject(reply).optJSONArray("places") ?: return emptyList()
		val items = mutableListOf<Resolved>()
		for (i in 0 until places.length()) {
			val place = places.getJSONObject(i)
			val location = place.optJSONObject("location") ?: continue
			if (!location.has("latitude") || !location.has("longitude")) continue
			val address = place.optString("formattedAddress")
			val name = place.optJSONObject("displayName")?.optString("text")?.takeIf { it.isNotBlank() } ?: address.substringBefore(",")
			if (name.isBlank()) continue
			items.add(Resolved(name, address.ifBlank { null }, location.getDouble("latitude"), location.getDouble("longitude")))
		}
		return items
	}

	private fun nominatim(query: String, near: Pair<Double, Double>?): List<Resolved> {
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

	/** Photon reads the same OpenStreetMap places but forgives spelling. Places near the car come first, and far ones are dropped if any are near */
	private fun photon(query: String, near: Pair<Double, Double>?): List<Resolved> {
		val bias = if (near != null) "&lat=${near.first}&lon=${near.second}" else ""
		val url = "https://photon.komoot.io/api/?limit=10&lang=en&q=" + URLEncoder.encode(query, "UTF-8") + bias
		val features = JSONObject(fetch(url)).optJSONArray("features") ?: return emptyList()
		val items = mutableListOf<Resolved>()
		for (i in 0 until features.length()) {
			val feature = features.getJSONObject(i)
			val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
			if (coordinates.length() < 2) continue
			val about = feature.optJSONObject("properties") ?: JSONObject()
			val road = listOf(about.optString("housenumber"), about.optString("street")).filter { it.isNotBlank() }.joinToString(" ")
			val name = about.optString("name").ifBlank { road }
			if (name.isBlank()) continue
			val detail = listOf(road.takeIf { it != name }, about.optString("city").ifBlank { about.optString("county") }, about.optString("postcode"), about.optString("country"))
				.filter { !it.isNullOrBlank() }.distinct().joinToString(", ")
			items.add(Resolved(name, detail.ifBlank { null }, coordinates.getDouble(1), coordinates.getDouble(0)))
		}
		if (near == null) return items.take(8)
		val close = items.filter { distanceMeters(near.first, near.second, it.lat, it.lon) < 100000 }
		return (if (close.isNotEmpty()) close else items).take(8)
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
