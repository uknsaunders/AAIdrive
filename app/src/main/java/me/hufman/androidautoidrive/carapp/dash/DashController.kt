package me.hufman.androidautoidrive.carapp.dash

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Base64
import android.graphics.Color
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Display
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonObject
import io.bimmergestalt.idriveconnectkit.CDS
import io.bimmergestalt.idriveconnectkit.CDSProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.hufman.androidautoidrive.CarInformation
import me.hufman.androidautoidrive.carapp.maps.MapAppMode
import me.hufman.androidautoidrive.carapp.maps.MapInteractionController
import me.hufman.androidautoidrive.cds.CDSVehicleUnits
import me.hufman.androidautoidrive.cds.flow
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import me.hufman.androidautoidrive.notifications.NotificationListenerServiceImpl
import java.io.ByteArrayOutputStream
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsDouble
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsInt
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsJsonObject
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsJsonPrimitive
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives the i3 Dash web page shown on the hidden virtual display:
 *  - pushes live car data (battery, range, speed, temperatures, GPS) into the page
 *  - turns iDrive knob turns into page navigation
 *  - passes destinations from the iDrive search into the page's own navigation
 */
class DashController(val context: Context,
                     val virtualDisplay: VirtualDisplay,
                     val carLocationProvider: CarLocationProvider,
                     val mapAppMode: MapAppMode): MapInteractionController {
	companion object {
		const val TAG = "i3DashController"
		const val PUSH_INTERVAL_MS = 500L
		const val SHUTDOWN_DELAY_MS = 120000L
		/** after a knob turn, hold back data updates so the new page reaches the car first */
		const val KNOB_QUIET_MS = 1200L
		const val MEDIA_INTERVAL_MS = 1000L

		/** The running dashboard, so the phone screen can hand it destinations */
		@Volatile var instance: DashController? = null
	}

	private val handler = Handler(context.mainLooper)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private val data = ConcurrentHashMap<String, Any>()
	private val carInfo = CarInformation()
	private var presentation: DashPresentation? = null
	@Volatile private var pushing = false
	@Volatile private var pageLoaded = false
	private var tts: TextToSpeech? = null
	private var ttsReady = false
	@Volatile private var quietUntil = 0L
	private var lastArtKey: String? = null

	init {
		instance = this
		carLocationProvider.callback = { location -> onLocation(location) }
		watchCarData()
	}

	/** Subscribe to the car's own data, in the car's base units (km, km/h, °C) */
	private fun watchCarData() {
		watch(CDS.SENSORS.SOCBATTERYHYBRID, "soc", true) {
			it.tryAsJsonPrimitive("SOCBatteryHybrid")?.tryAsDouble?.takeIf { v -> v < 255 }
		}
		watch(CDS.DRIVING.DISPLAYRANGEELECTRICVEHICLE, "rangeKm", true) {
			it.tryAsJsonPrimitive("displayRangeElectricVehicle")?.tryAsDouble?.takeIf { v -> v < 4093 }
		}
		watch(CDS.DRIVING.SPEEDACTUAL, "speedKmh", false) {
			it.tryAsJsonPrimitive("speedActual")?.tryAsDouble
		}
		watch(CDS.SENSORS.TEMPERATUREEXTERIOR, "ambientC", false) {
			it.tryAsJsonPrimitive("temperatureExterior")?.tryAsDouble
		}
		watch(CDS.SENSORS.TEMPERATUREINTERIOR, "cabinC", false) {
			it.tryAsJsonPrimitive("temperatureInterior")?.tryAsDouble
		}
		watch(CDS.SENSORS.BATTERYTEMP, "batteryC", false) {
			it.tryAsJsonPrimitive("batteryTemp")?.tryAsDouble?.takeIf { v -> v < 255 }
		}
		watch(CDS.SENSORS.BATTERY, "battery12v", true) {
			it.tryAsJsonPrimitive("battery")?.tryAsDouble?.takeIf { v -> v < 255 }
		}
		watch(CDS.DRIVING.ODOMETER, "odoKm", true) {
			it.tryAsJsonPrimitive("odometer")?.tryAsDouble
		}
		watch(CDS.DRIVING.ECORANGEWON, "ecoWonKm", true) {
			it.tryAsJsonPrimitive("ecoRangeWon")?.tryAsDouble
		}
		watch(CDS.DRIVING.AVERAGECONSUMPTION, "avgConsumption", true) {
			it.tryAsJsonObject("averageConsumption")?.tryAsJsonPrimitive("averageConsumption1")?.tryAsDouble?.takeIf { v -> v < 2093 }
		}
		watch(CDS.DRIVING.AVERAGECONSUMPTION, "avgConsumptionUnit", true) {
			CDSVehicleUnits.Consumption.fromValue(it.tryAsJsonObject("averageConsumption")?.tryAsJsonPrimitive("unit")?.tryAsInt).name
		}
		watch(CDS.ENGINE.TORQUE, "torqueNm", false) {
			it.tryAsJsonPrimitive("torque")?.tryAsDouble
		}
		watch(CDS.ENGINE.RPMSPEED, "rpm", false) {
			it.tryAsJsonPrimitive("RPMSpeed")?.tryAsInt
		}
		watchTyres()
		watch(CDS.VEHICLE.UNITS, "units", true) {
			val units = CDSVehicleUnits.fromCdsProperty(it)
			"${units.distanceUnits.name},${units.temperatureUnits.name}"
		}
	}

	/**
	 * Tyre pressures: the exact CDS name isn't known up front, so watch every car property
	 * whose name looks tyre-related and let the page work out which number is which tyre
	 */
	private fun watchTyres() {
		try {
			CDS::class.java.declaredClasses.filter { it.isEnum }.forEach { group ->
				group.enumConstants?.forEach { constant ->
					val name = (constant as? Enum<*>)?.name ?: return@forEach
					val property = constant as? CDSProperty ?: return@forEach
					if (Regex("TIRE|TYRE|RDC|PRESSURE").containsMatchIn(name) && !name.contains("BARO")) {
						Log.i(TAG, "Watching tyre property $name")
						watch(property, "tyre:$name", true) { JSONObject(it.toString()) }
					}
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't look for tyre properties", e)
		}
	}

	private fun watch(property: CDSProperty, key: String, cached: Boolean, parse: (JsonObject) -> Any?) {
		val source = if (cached) carInfo.cachedCdsData else carInfo.cdsData
		scope.launch {
			try {
				source.flow[property].collect { json ->
					try {
						parse(json)?.let { data[key] = it }
					} catch (e: Exception) {
						Log.w(TAG, "Couldn't read $key from the car", e)
					}
				}
			} catch (e: Exception) {
				Log.w(TAG, "Stopped watching $key", e)
			}
		}
	}

	private fun onLocation(location: Location) {
		data["lat"] = location.latitude
		data["lon"] = location.longitude
		if (location.hasBearing()) data["heading"] = location.bearing.toDouble()
		if (location.hasSpeed()) data["gpsSpeedMs"] = location.speed.toDouble()
	}

	/** Sends the latest data into the page, twice a second, while it is on screen */
	private val pushRunnable = object: Runnable {
		override fun run() {
			if (!pushing) return
			val wait = quietUntil - SystemClock.uptimeMillis()
			if (wait > 0) {
				handler.postDelayed(this, wait)
				return
			}
			val json = JSONObject()
			data.forEach { (k, v) -> json.put(k, v) }
			json.put("t", System.currentTimeMillis())
			json.put("tariffP", DashStore.tariff(context))
			js("window.i3feed && window.i3feed($json)")
			handler.postDelayed(this, PUSH_INTERVAL_MS)
		}
	}

	private fun js(script: String) {
		handler.post {
			try {
				presentation?.webView?.evaluateJavascript(script, null)
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't update the dashboard", e)
			}
		}
	}

	private val shutdownRunnable = Runnable {
		Log.i(TAG, "Closing the dashboard after ${SHUTDOWN_DELAY_MS}ms in the background")
		presentation?.dismiss()
		presentation = null
		pageLoaded = false
	}

	override fun showMap() {
		handler.post {
			handler.removeCallbacks(shutdownRunnable)
			if (presentation == null) {
				Log.i(TAG, "Creating the dashboard on ${virtualDisplay.display}")
				presentation = DashPresentation(context, virtualDisplay.display, Bridge()) {
					pageLoaded = true
					lastArtKey = null       // a fresh page has no cover art yet, so send it again
					deliverPending()
				}
			}
			if (presentation?.isShowing == false) {
				presentation?.show()
			}
			js("window.i3shown && window.i3shown()")
			carLocationProvider.start()
			handler.post { deliverPending() }
			if (!pushing) {
				pushing = true
				handler.post(pushRunnable)
				handler.post(mediaRunnable)
			}
		}
	}

	override fun pauseMap() {
		handler.post {
			pushing = false
			handler.removeCallbacks(pushRunnable)
			handler.removeCallbacks(mediaRunnable)
			carLocationProvider.stop()
			handler.postDelayed(shutdownRunnable, SHUTDOWN_DELAY_MS)
		}
	}

	/** The iDrive knob, turned one way */
	override fun zoomIn(steps: Int) {
		knob(-steps)
	}

	private fun knob(steps: Int) {
		mapAppMode.startInteraction()
		quietUntil = SystemClock.uptimeMillis() + KNOB_QUIET_MS
		handler.postAtFrontOfQueue {
			try {
				presentation?.webView?.evaluateJavascript("window.i3knob && window.i3knob($steps)", null)
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't turn the page", e)
			}
		}
	}

	/** The iDrive knob, turned the other way */
	override fun zoomOut(steps: Int) {
		knob(steps)
	}

	override fun navigateTo(dest: LatLong) {
		mapAppMode.currentNavDestination = dest
		js("window.i3navigate && window.i3navigate(${dest.latitude}, ${dest.longitude})")
	}

	override fun recalcNavigation() {
		js("window.i3recalc && window.i3recalc()")
	}

	override fun stopNavigation() {
		mapAppMode.currentNavDestination = null
		js("window.i3stopNav && window.i3stopNav()")
	}

	/** What's playing on the phone (Spotify, Amazon Music, Apple Music, BBC Sounds...), once a second */
	private val mediaRunnable = object: Runnable {
		override fun run() {
			if (!pushing) return
			try {
				readMedia()
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't read what's playing", e)
			}
			try {
				readCall()
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't read the call", e)
			}
			handler.postDelayed(this, MEDIA_INTERVAL_MS)
		}
	}

	/** A phone call in progress or ringing, from the phone app's call notification */
	private fun readCall() {
		val service = NotificationListenerServiceImpl.runningInstance
		val notifications = try { service?.activeNotifications } catch (e: Exception) { null }
		val call = notifications?.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }
		if (call == null) {
			data["call"] = JSONObject.NULL
			return
		}
		val extras = call.notification.extras
		val callType = extras.getInt("android.callType", 0)    // 1 incoming, 2 ongoing (Android 12+)
		val ongoing = callType == 2 || (callType == 0 && extras.getBoolean("android.showChronometer", false))
		val info = JSONObject()
		info.put("name", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
		info.put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
		info.put("ongoing", ongoing)
		info.put("since", call.notification.`when`)
		data["call"] = info
	}

	private fun readMedia() {
		val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
		val controllers: List<MediaController> = try {
			manager.getActiveSessions(ComponentName(context, NotificationListenerServiceImpl::class.java))
		} catch (e: SecurityException) {
			data["mediaPermission"] = false
			data["media"] = JSONObject.NULL
			return
		}
		data["mediaPermission"] = true
		val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
		val controller = playing ?: controllers.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
		val metadata = controller?.metadata
		val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
		if (controller == null || metadata == null || title == null) {
			data["media"] = JSONObject.NULL
			return
		}
		val state = controller.playbackState
		val isPlaying = state?.state == PlaybackState.STATE_PLAYING
		var position = state?.position ?: 0L
		if (isPlaying && state != null && state.lastPositionUpdateTime > 0) {
			position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
		}
		val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
		val appName = try {
			val pm = context.packageManager
			pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
		} catch (e: Exception) { controller.packageName }
		val media = JSONObject()
		media.put("title", title)
		media.put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
		media.put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
		media.put("app", appName)
		media.put("playing", isPlaying)
		media.put("pos", (position / 1000) * 1000)
		media.put("dur", if (duration > 0) duration else 0)
		data["media"] = media

		// cover art: sent when the track changes, and retried each second until the app supplies a picture
		val artKey = "${controller.packageName}|$title|${media.optString("artist")}"
		if (artKey != lastArtKey) {
			lastArtKey = artKey
			artTries = 0
			artDone = false
			js("window.i3art && window.i3art('')")
		}
		if (!artDone && artTries < 40) {
			artTries++
			val art = findArt(metadata, controller.packageName)
			if (art != null) {
				artDone = true
				js("window.i3art && window.i3art(${JSONObject.quote(artToDataUrl(art))})")
			} else if (artTries == 1 || artTries % 5 == 0) {
				fetchArtOverHttp(metadata, artKey)
			}
		}
	}

	@Volatile private var artTries = 0
	@Volatile private var artDone = false

	/** Looks for cover art in the places apps put it: bitmaps, content links, then the media notification's picture */
	private fun findArt(metadata: MediaMetadata, pkg: String): Bitmap? {
		for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
			val bitmap = try { metadata.getBitmap(key) } catch (e: Exception) { null }
			if (bitmap != null) return bitmap
		}
		for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)) {
			val uri = metadata.getString(key) ?: continue
			if (uri.startsWith("content:") || uri.startsWith("file:")) {
				try {
					context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
						android.graphics.BitmapFactory.decodeStream(it)?.let { bmp -> return bmp }
					}
				} catch (e: Exception) {
					Log.d(TAG, "Couldn't open cover art $uri: $e")
				}
			}
		}
		// the player's own notification usually carries the album picture as its large icon
		val notifications = try { NotificationListenerServiceImpl.runningInstance?.activeNotifications } catch (e: Exception) { null }
		val own = notifications?.filter { it.packageName == pkg }.orEmpty()
		for (n in own) {
			val icon = try { n.notification.getLargeIcon() } catch (e: Exception) { null } ?: continue
			val drawable = try { icon.loadDrawable(context) } catch (e: Exception) { null } ?: continue
			val w = drawable.intrinsicWidth.coerceAtLeast(1)
			val h = drawable.intrinsicHeight.coerceAtLeast(1)
			val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
			val canvas = android.graphics.Canvas(bmp)
			drawable.setBounds(0, 0, w, h)
			drawable.draw(canvas)
			return bmp
		}
		return null
	}

	/** Some apps only give a web link to the cover, which has to be downloaded off the main thread */
	private fun fetchArtOverHttp(metadata: MediaMetadata, artKey: String) {
		val url = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
			.mapNotNull { metadata.getString(it) }.firstOrNull { it.startsWith("http") } ?: return
		Thread {
			try {
				val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
				connection.connectTimeout = 5000
				connection.readTimeout = 5000
				val bmp = connection.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@Thread
				handler.post {
					if (lastArtKey == artKey && !artDone) {
						artDone = true
						js("window.i3art && window.i3art(${JSONObject.quote(artToDataUrl(bmp))})")
					}
				}
			} catch (e: Exception) {
				Log.d(TAG, "Couldn't download cover art $url: $e")
			}
		}.start()
	}

	private fun artToDataUrl(art: Bitmap): String {
		val scaled = Bitmap.createScaledBitmap(art, 160, 160, true)
		val out = ByteArrayOutputStream()
		scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
		return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
	}

	/**
	 * Hands a destination sent from the phone to the dashboard page.
	 * Returns false if the page isn't on the car's screen yet, in which case it waits until it is.
	 */
	fun deliverPending(): Boolean {
		if (!(pageLoaded && pushing)) return false
		handler.post {
			val dest = DashStore.takePending(context) ?: return@post
			mapAppMode.currentNavDestination = LatLong(dest.lat, dest.lon)
			js("window.i3navigate && window.i3navigate(${dest.lat}, ${dest.lon}, ${JSONObject.quote(dest.name)})")
		}
		return true
	}

	fun onDestroy() {
		if (instance === this) instance = null
		pageLoaded = false
		pushing = false
		handler.removeCallbacksAndMessages(null)
		scope.cancel()
		handler.post {
			presentation?.dismiss()
			presentation = null
			tts?.shutdown()
			tts = null
		}
	}

	private fun speak(text: String) {
		handler.post {
			val engine = tts ?: TextToSpeech(context) { status ->
				ttsReady = status == TextToSpeech.SUCCESS
				if (ttsReady) {
					tts?.language = Locale.UK
					tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "i3dash")
				}
			}.also { tts = it }
			if (ttsReady) engine.speak(text, TextToSpeech.QUEUE_ADD, null, "i3dash")
		}
	}

	/** Called from the page's JavaScript, as window.I3Native */
	inner class Bridge {
		@JavascriptInterface
		fun speak(text: String) {
			this@DashController.speak(text)
		}

		@JavascriptInterface
		fun navigationEnded() {
			mapAppMode.currentNavDestination = null
		}

		@JavascriptInterface
		fun log(message: String) {
			Log.i(TAG, "page: $message")
		}
	}
}

/**
 * Shows the i3 Dash web page on the hidden display that is captured and sent to the car
 */
class DashPresentation(context: Context, display: Display, private val bridge: Any,
                       private val onLoaded: () -> Unit = {}): Presentation(context, display) {
	var webView: WebView? = null
		private set

	@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val webView = WebView(context)
		webView.setBackgroundColor(Color.BLACK)
		webView.settings.javaScriptEnabled = true
		webView.settings.domStorageEnabled = true
		webView.settings.mediaPlaybackRequiresUserGesture = false
		@Suppress("DEPRECATION")
		webView.settings.allowFileAccessFromFileURLs = true
		@Suppress("DEPRECATION")
		webView.settings.allowUniversalAccessFromFileURLs = true
		webView.addJavascriptInterface(bridge, "I3Native")
		webView.webViewClient = object: WebViewClient() {
			override fun onPageFinished(view: WebView?, url: String?) {
				onLoaded()
			}
		}
		webView.webChromeClient = object: WebChromeClient() {
			override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
				Log.i(DashController.TAG, "console: ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
				return true
			}
		}
		setContentView(webView)
		webView.loadUrl("file:///android_asset/i3dash/index.html")
		this.webView = webView
	}

	override fun onStop() {
		webView?.destroy()
		webView = null
		super.onStop()
	}
}
