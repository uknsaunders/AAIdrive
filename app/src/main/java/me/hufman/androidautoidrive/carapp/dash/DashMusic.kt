package me.hufman.androidautoidrive.carapp.dash

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.hufman.androidautoidrive.music.MusicAppDiscovery
import me.hufman.androidautoidrive.music.MusicAppInfo
import me.hufman.androidautoidrive.music.MusicController
import me.hufman.androidautoidrive.music.MusicMetadata

/**
 * The music picker for the car's menu wheel.
 * First list: the music and streaming apps on the phone. Press one and the next list is
 * its own browse tree (playlists, albums, library). Press a playlist or track to play it.
 * It reuses AAIdrive's music code, so any app that offers a browse list works.
 */
class DashMusic(val context: Context) {
	companion object {
		const val TAG = "i3DashMusic"
		const val BACK = "‹ Back"
		const val MAX_ROWS = 60
		const val CONNECT_WAIT_MS = 6000L
		const val BROWSE_WAIT_MS = 8000L
	}

	private val thread = HandlerThread("i3DashMusic").also { it.start() }
	private val handler = Handler(thread.looper)
	private val discovery = MusicAppDiscovery(context, handler)
	private val controller = MusicController(context, handler)

	private var apps: List<MusicAppInfo> = emptyList()
	private var current: MusicAppInfo? = null
	private val trail = ArrayList<MusicMetadata?>()      // folders we came through, null is the app's top level
	private var items: List<MusicMetadata> = emptyList()

	init {
		try {
			discovery.loadInstalledMusicApps()
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't list music apps", e)
		}
		discovery.discoverAppsAsync()
		reset()
	}

	/** Back to the list of apps */
	fun reset() {
		current = null
		trail.clear()
		items = emptyList()
		apps = try {
			discovery.allApps
				.filter { !it.hidden && (it.className != null || it.controllable) }
				.sortedWith(compareByDescending<MusicAppInfo> { it.playing }.thenBy { it.name.lowercase() })
		} catch (e: Exception) {
			emptyList()
		}
	}

	fun rows(): List<String> {
		if (current == null) {
			return if (apps.isEmpty()) listOf("No music apps found") else apps.map { if (it.playing) "${it.name} (playing)" else it.name }
		}
		val list = ArrayList<String>()
		list.add(BACK)
		items.take(MAX_ROWS).forEach { list.add(label(it)) }
		if (items.isEmpty()) list.add("Nothing here")
		return list
	}

	private fun label(item: MusicMetadata): String {
		val text = item.title ?: item.subtitle ?: item.artist ?: "?"
		return if (item.browseable && !item.playable) "$text ›" else text
	}

	/** Press a row. Returns true if something started playing */
	fun pick(row: Int): Boolean {
		try {
			val app = current
			if (app == null) {
				apps.getOrNull(row)?.let { open(it) }
				return false
			}
			if (row == 0) {
				back()
				return false
			}
			val item = items.getOrNull(row - 1) ?: return false
			if (item.browseable && !item.playable) {
				trail.add(item)
				items = browse(item)
				return false
			}
			Log.i(TAG, "Playing ${item.title} from ${app.name}")
			if (item.queueId != null) controller.playQueue(item) else controller.playSong(item)
			return true
		} catch (e: Exception) {
			Log.w(TAG, "Music pick failed", e)
			return false
		}
	}

	private fun open(app: MusicAppInfo) {
		Log.i(TAG, "Opening ${app.name}")
		controller.connectAppManually(app)
		val until = System.currentTimeMillis() + CONNECT_WAIT_MS
		while (!controller.isConnected() && System.currentTimeMillis() < until) {
			Thread.sleep(150)
		}
		current = app
		trail.clear()
		trail.add(null)
		items = browse(null)
	}

	private fun back() {
		if (trail.size > 1) {
			trail.removeAt(trail.size - 1)
			items = browse(trail.last())
		} else {
			reset()
		}
	}

	private fun browse(directory: MusicMetadata?): List<MusicMetadata> = runBlocking {
		withTimeoutOrNull(BROWSE_WAIT_MS) { controller.browseAsync(directory).await() } ?: emptyList()
	}

	fun close() {
		try {
			controller.disconnectApp(false)
			discovery.cancelDiscovery()
		} catch (e: Exception) {
			Log.w(TAG, "Problem closing music", e)
		}
		thread.quitSafely()
	}
}
