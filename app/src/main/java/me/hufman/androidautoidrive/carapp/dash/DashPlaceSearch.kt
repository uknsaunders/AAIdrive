package me.hufman.androidautoidrive.carapp.dash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import me.hufman.androidautoidrive.maps.MapPlaceSearch
import me.hufman.androidautoidrive.maps.MapResult

/**
 * Destination search for the iDrive "Search" entry.
 * Saved places come first, then charging points ("charger"), what3words addresses, and OpenStreetMap place names.
 */
class DashPlaceSearch(private val locationProvider: CarLocationProvider,
                      private val context: Context): MapPlaceSearch {
	companion object {
		const val TAG = "i3DashSearch"
		private val CHARGER_WORDS = Regex("charg|ev point|ev station|plug", RegexOption.IGNORE_CASE)
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	override fun searchLocationsAsync(query: String): Deferred<List<MapResult>> = scope.async {
		try {
			val here = locationProvider.currentLocation
			val near = here?.let { Pair(it.latitude, it.longitude) } ?: DashStore.homeArea(context)
			DashDestinations.googleKey = DashStore.googleKey(context)
			val text = query.trim()

			val callOnly = text.startsWith("call ", ignoreCase = true)
			val calls = contacts(if (callOnly) text.substring(5).trim() else text)
			if (callOnly) return@async calls

			val found = mutableListOf<Resolved>()
			found.addAll(DashStore.places(context).filter { text.isNotEmpty() && it.name.contains(text, ignoreCase = true) })
			if (found.isEmpty()) {
				if (CHARGER_WORDS.containsMatchIn(text) && near != null) {
					found.addAll(DashDestinations.chargers(near.first, near.second).take(8))
				} else {
					found.addAll(DashDestinations.resolve(text, near).items)
				}
			}
			calls + found.map { place ->
				val location = LatLong(place.lat, place.lon)
				val distance = here?.let { LatLong(it.latitude, it.longitude).distanceFrom(location).toFloat() }
				MapResult("${place.lat},${place.lon}", place.name, place.detail, location, distance)
			}
		} catch (e: Exception) {
			Log.w(TAG, "Search failed for $query", e)
			emptyList()
		}
	}

	/** Phone contacts whose name contains the text, shown as "Call name" rows that the car dials when chosen */
	private fun contacts(text: String): List<MapResult> {
		if (text.length < 2) return emptyList()
		if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
		val results = mutableListOf<MapResult>()
		try {
			val phone = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
			context.contentResolver.query(phone,
				arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
					android.provider.ContactsContract.CommonDataKinds.Phone.TYPE, android.provider.ContactsContract.CommonDataKinds.Phone.LABEL),
				"${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$text%"),
				"${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cursor ->
				val seen = HashSet<String>()
				while (cursor.moveToNext() && results.size < 6) {
					val name = cursor.getString(0) ?: continue
					val number = cursor.getString(1) ?: continue
					if (seen.add(number.filter { it.isDigit() || it == '+' })) {
						// say which number this is (Mobile, Work...), for people with more than one
						val kind = try {
							android.provider.ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, cursor.getInt(2), cursor.getString(3))?.toString() ?: ""
						} catch (e: Exception) { "" }
						results.add(MapResult("tel:$number", "Call $name", if (kind.isBlank()) number else "$kind · $number"))
					}
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't look up contacts", e)
		}
		val wanted = "Call $text"
		return results.sortedBy { if (it.name.equals(wanted, ignoreCase = true)) 0 else 1 }
	}

	/** Saved places, then starred contacts as "Call name", for the one-press list in the car menu */
	override fun quickEntries(): List<String> {
		val entries = mutableListOf<String>()
		try {
			entries.addAll(DashStore.places(context).map { it.name }.take(6))
			if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
				val phone = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
				context.contentResolver.query(phone,
					arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
					"${android.provider.ContactsContract.CommonDataKinds.Phone.STARRED} = 1", null,
					"${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cursor ->
					val seen = LinkedHashSet<String>()
					while (cursor.moveToNext() && seen.size < 8) {
						cursor.getString(0)?.let { seen.add(it) }
					}
					entries.addAll(seen.map { "Call $it" })
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't build the quick list", e)
		}
		return entries
	}

	override fun resultInformationAsync(resultId: String): Deferred<MapResult?> = scope.async {
		val parts = resultId.split(",")
		val lat = parts.getOrNull(0)?.toDoubleOrNull()
		val lon = parts.getOrNull(1)?.toDoubleOrNull()
		if (lat != null && lon != null) {
			MapResult(resultId, "", null, LatLong(lat, lon))
		} else null
	}
}
