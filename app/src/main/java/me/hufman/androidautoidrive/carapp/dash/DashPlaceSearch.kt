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
			val near = here?.let { Pair(it.latitude, it.longitude) }
			val text = query.trim()

			val found = mutableListOf<Resolved>()
			found.addAll(DashStore.places(context).filter { text.isNotEmpty() && it.name.contains(text, ignoreCase = true) })
			if (found.isEmpty()) {
				if (CHARGER_WORDS.containsMatchIn(text) && near != null) {
					found.addAll(DashDestinations.chargers(near.first, near.second).take(8))
				} else {
					found.addAll(DashDestinations.resolve(text, near).items)
				}
			}
			found.map { place ->
				val location = LatLong(place.lat, place.lon)
				val distance = here?.let { LatLong(it.latitude, it.longitude).distanceFrom(location).toFloat() }
				MapResult("${place.lat},${place.lon}", place.name, place.detail, location, distance)
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
}
