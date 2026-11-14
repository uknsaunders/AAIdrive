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
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneColumns
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

/** The two car screens the dashboard can ask for. Both talk to the car, so never call them on the main thread */
interface DashCarUi {
	fun openSearch()
	fun openDash()
}

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
		/** how long after the car leaves the dashboard a call still counts as the reason it left */
		const val CALL_RETURN_WINDOW_MS = 8000L
		const val CALL_WATCH_INTERVAL_MS = 1500L
		const val CALL_WATCH_MAX_MS = 90000L
		/** how long a call placed from the dashboard is shown before the phone itself reports it */
		const val DIAL_GRACE_MS = 6000L

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
	/** set by the car app once it is running */
	@Volatile var carUi: DashCarUi? = null
	/** slow jobs (music browsing, car screen switches) run here, one at a time, off the main thread */
	private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
	@Volatile private var mediaController: MediaController? = null

	/** Runs a slow job in the background. Quietly does nothing once the car app is shutting down */
	private fun background(job: () -> Unit) {
		try {
			worker.execute {
				try { job() } catch (e: Exception) { Log.w(TAG, "Background job failed", e) }
			}
		} catch (e: java.util.concurrent.RejectedExecutionException) {
			Log.i(TAG, "Skipping a background job, the car app is closing")
		}
	}

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
			if (videoOn) {
				// YouTube is on screen: only watch for the car moving
				if (isMoving()) {
					speak("Video stopped, the car is moving")
					stopVideo()
				}
				handler.postDelayed(this, PUSH_INTERVAL_MS)
				return
			}
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
			handler.removeCallbacks(callWatch)
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
			if (videoOn) stopVideo()
			js("window.i3hidden && window.i3hidden()")
			pausedAt = SystemClock.uptimeMillis()
			handler.removeCallbacks(callWatch)
			handler.postDelayed(callWatch, 700)
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
		if (videoOn) {
			stopVideo()     // any knob turn leaves the video
			return
		}
		quietUntil = SystemClock.uptimeMillis() + KNOB_QUIET_MS
		handler.postAtFrontOfQueue {
			try {
				presentation?.webView?.evaluateJavascript("window.i3knob && window.i3knob($steps)", null)
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't turn the page", e)
			}
		}
	}

	/** The knob press opens the dashboard's own menu, so the car never leaves the dashboard */
	override fun handlesClick(): Boolean = pageLoaded || videoOn

	override fun click() {
		mapAppMode.startInteraction()
		if (videoOn) {
			stopVideo()
			return
		}
		quietUntil = SystemClock.uptimeMillis() + KNOB_QUIET_MS
		handler.postAtFrontOfQueue {
			try {
				presentation?.webView?.evaluateJavascript("window.i3press && window.i3press()", null)
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't open the menu", e)
			}
		}
	}

	/**
	 * The car shows its own phone screen when a call starts, which pushes the dashboard off.
	 * If a call is under way just after the dashboard was hidden, bring the dashboard back once,
	 * so the call can be handled from here. It never does this for a call that is still ringing.
	 */
	@Volatile private var pausedAt = 0L
	private var returnedForCall = false
	private var sawCallAfterPause = false
	private val callWatch = object: Runnable {
		override fun run() {
			if (pushing) return     // the dashboard is back on screen
			val sincePause = SystemClock.uptimeMillis() - pausedAt
			try { readCall() } catch (e: Exception) { Log.w(TAG, "Couldn't read the call", e) }
			if (callState == 0) {
				returnedForCall = false
				if (sawCallAfterPause || sincePause > CALL_RETURN_WINDOW_MS) { sawCallAfterPause = false; return }
			} else {
				if (sincePause <= CALL_RETURN_WINDOW_MS) sawCallAfterPause = true
				if (!sawCallAfterPause) return      // the call started long after the driver left the dashboard
				if (callState == 2 && !returnedForCall) {
					returnedForCall = true
					sawCallAfterPause = false
					Log.i(TAG, "A call took the screen, bringing the dashboard back")
					background { carUi?.openDash() }
					return
				}
			}
			if (sincePause < CALL_WATCH_MAX_MS) handler.postDelayed(this, CALL_WATCH_INTERVAL_MS)
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

	private var music: DashMusic? = null
	private fun music(): DashMusic = music ?: DashMusic(context).also { music = it }
	override fun musicReset() { try { music().reset() } catch (e: Exception) { Log.w(TAG, "Music reset failed", e) } }
	override fun musicRows(): List<String> = try { music().rows() } catch (e: Exception) { Log.w(TAG, "Music rows failed", e); emptyList() }
	override fun musicPick(row: Int): Boolean = try { music().pick(row) } catch (e: Exception) { Log.w(TAG, "Music pick failed", e); false }

	/* YouTube, only while parked */
	@Volatile private var videoOn = false

	/** True unless the car says it is standing still. No speed reading counts as moving */
	private fun isMoving(): Boolean {
		val kmh = (data["speedKmh"] as? Number)?.toDouble() ?: return true
		return kmh >= 3.0
	}

	/** Starts a YouTube video or playlist on the car screen. Returns sent, moving or nocar */
	fun playVideo(id: String, list: String): String {
		if (presentation == null || !pushing) return "nocar"
		if (isMoving()) return "moving"
		handler.post {
			videoOn = true
			pageLoaded = false
			presentation?.showVideo(id, list)
		}
		return "sent"
	}

	fun stopVideo() {
		handler.post {
			if (!videoOn) return@post
			videoOn = false
			presentation?.showDash()
		}
	}

	@Volatile private var callState = 0
	override fun callStatus(): Int = callState

	private fun hasPermission(permission: String) =
		context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

	@SuppressLint("MissingPermission")
	override fun answerCall() {
		try {
			if (android.os.Build.VERSION.SDK_INT >= 26 && hasPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)) {
				context.getSystemService(android.telecom.TelecomManager::class.java)?.acceptRingingCall()
			} else {
				speak("Allow calls in the i3D app on your phone first")
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't answer the call", e)
		}
	}

	@SuppressLint("MissingPermission")
	override fun hangUpCall() {
		try {
			if (android.os.Build.VERSION.SDK_INT >= 28 && hasPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)) {
				context.getSystemService(android.telecom.TelecomManager::class.java)?.endCall()
				dialedAt = 0L
			} else {
				speak("Allow calls in the i3D app on your phone first")
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't hang up", e)
		}
	}

	/**
	 * The call the driver started from the dashboard. The phone's call notification is the best source
	 * for who is on the line, but it needs notification access, so the dashboard remembers its own calls too.
	 */
	@Volatile private var dialedName = ""
	@Volatile private var dialedNumber = ""
	@Volatile private var dialedAt = 0L          // wall clock, 0 when there is no such call
	@Volatile private var dialedSeen = false     // the phone has reported this call as under way
	private var callSeenAt = 0L                  // when a call without a notification was first noticed

	override fun dial(number: String) = placeCall(number, "")

	@SuppressLint("MissingPermission")
	fun placeCall(number: String, name: String) {
		try {
			if (hasPermission(android.Manifest.permission.CALL_PHONE)) {
				context.getSystemService(android.telecom.TelecomManager::class.java)
					?.placeCall(android.net.Uri.fromParts("tel", number, null), Bundle())
				dialedName = name.ifBlank { nameFor(number) }
				dialedNumber = number
				dialedSeen = false
				dialedAt = System.currentTimeMillis()
			} else {
				speak("Allow calls in the i3D app on your phone first")
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't place the call", e)
		}
	}

	/** The contact's name for a number, or nothing if it isn't in the phone's contacts */
	private fun nameFor(number: String): String {
		if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) return ""
		return try {
			val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
			context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) cursor.getString(0) else null
			} ?: ""
		} catch (e: Exception) {
			""
		}
	}

	/**
	 * Whether the phone itself is in a call: 0 no, 1 ringing, 2 under way.
	 * Works without notification access: from the phone state if that is allowed, else from the audio mode.
	 */
	@SuppressLint("MissingPermission")
	private fun phoneCallState(): Int {
		if (hasPermission(android.Manifest.permission.READ_PHONE_STATE)) {
			try {
				val telephony = context.getSystemService(android.telephony.TelephonyManager::class.java)
				@Suppress("DEPRECATION")
				val state = if (android.os.Build.VERSION.SDK_INT >= 31) telephony?.callStateForSubscription else telephony?.callState
				when (state) {
					android.telephony.TelephonyManager.CALL_STATE_RINGING -> return 1
					android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> return 2
					android.telephony.TelephonyManager.CALL_STATE_IDLE -> return 0
				}
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't read the phone state", e)
			}
		}
		return try {
			when (context.getSystemService(android.media.AudioManager::class.java)?.mode) {
				android.media.AudioManager.MODE_RINGTONE -> 1
				android.media.AudioManager.MODE_IN_CALL -> 2
				else -> 0
			}
		} catch (e: Exception) {
			0
		}
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
			try {
				readNotes()
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't read the notifications", e)
			}
			// slower things: missed calls and the next appointment
			if (slowTick++ % 20 == 0) background {
				try { readMissed() } catch (e: Exception) { Log.w(TAG, "Couldn't count missed calls", e) }
				try { readEvent() } catch (e: Exception) { Log.w(TAG, "Couldn't read the calendar", e) }
			}
			handler.postDelayed(this, MEDIA_INTERVAL_MS)
		}
	}

	private var slowTick = 0

	/** Calls missed and not yet looked at on the phone */
	private fun readMissed() {
		if (!hasPermission(android.Manifest.permission.READ_CALL_LOG)) return
		val count = context.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, arrayOf(android.provider.CallLog.Calls.NUMBER),
			"${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.MISSED_TYPE} AND ${android.provider.CallLog.Calls.NEW} = 1 AND ${android.provider.CallLog.Calls.DATE} > ?",
			arrayOf((System.currentTimeMillis() - 24 * 3600000L).toString()), null)?.use { it.count } ?: 0
		data["missed"] = count
	}

	/** The next appointment today that has a place, so the dashboard can offer to drive there */
	private fun readEvent() {
		if (!hasPermission(android.Manifest.permission.READ_CALENDAR)) return
		val now = System.currentTimeMillis()
		val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
			.appendPath((now - 15 * 60000L).toString()).appendPath((now + 18 * 3600000L).toString()).build()
		var found: JSONObject? = null
		context.contentResolver.query(uri, arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
				android.provider.CalendarContract.Instances.EVENT_LOCATION, android.provider.CalendarContract.Instances.ALL_DAY),
			null, null, "${android.provider.CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
			while (found == null && cursor.moveToNext()) {
				val where = cursor.getString(2)?.trim() ?: ""
				if (where.isEmpty() || cursor.getInt(3) == 1) continue
				found = JSONObject().put("title", cursor.getString(0) ?: "Appointment").put("begin", cursor.getLong(1)).put("where", where.take(120))
			}
		}
		data["event"] = found ?: JSONObject.NULL
	}

	/** The notification with this key, if it is still showing on the phone */
	private fun noteByKey(key: String): android.service.notification.StatusBarNotification? {
		val service = NotificationListenerServiceImpl.runningInstance ?: return null
		return try { service.activeNotifications?.firstOrNull { it.key == key } } catch (e: Exception) { null }
	}

	/** The notification's own reply box (WhatsApp, Messages and so on), if it has one */
	private fun replyAction(sbn: android.service.notification.StatusBarNotification): Notification.Action? =
		sbn.notification.actions?.firstOrNull { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }

	/** Answers a message through its notification, the way the phone's own quick reply does */
	private fun sendReply(key: String, text: String): Boolean {
		return try {
			val sbn = noteByKey(key) ?: return false
			val action = replyAction(sbn) ?: return false
			val inputs = action.remoteInputs ?: return false
			val results = Bundle()
			inputs.forEach { results.putCharSequence(it.resultKey, text) }
			val fill = android.content.Intent()
			android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
			action.actionIntent.send(context, 0, fill)
			true
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't send the reply", e)
			false
		}
	}

	/** Takes a notification off the phone once it has been dealt with in the car */
	private fun clearNote(key: String) {
		try {
			NotificationListenerServiceImpl.runningInstance?.cancelNotification(key)
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't clear the notification", e)
		}
	}

	private var recognizer: android.speech.SpeechRecognizer? = null

	private fun heard(kind: String, text: String, problem: String?) {
		js("window.i3heard && window.i3heard(${JSONObject.quote(kind)}, ${JSONObject.quote(text)}, ${if (problem == null) "null" else JSONObject.quote(problem)})")
	}

	/** Listens once through the phone's microphone and hands the words to the page as window.i3heard(kind, text, problem) */
	private fun listen(kind: String) {
		handler.post {
			try {
				if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
					heard(kind, "", "Allow the microphone in the i3D app on your phone first")
					return@post
				}
				if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
					heard(kind, "", "Voice input isn't available on this phone")
					return@post
				}
				recognizer?.destroy()
				val listener = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
				recognizer = listener
				listener.setRecognitionListener(object: android.speech.RecognitionListener {
					override fun onReadyForSpeech(params: Bundle?) {}
					override fun onBeginningOfSpeech() {}
					override fun onRmsChanged(rmsdB: Float) {}
					override fun onBufferReceived(buffer: ByteArray?) {}
					override fun onEndOfSpeech() {}
					override fun onPartialResults(partialResults: Bundle?) {}
					override fun onEvent(eventType: Int, params: Bundle?) {}
					override fun onError(error: Int) {
						heard(kind, "", when (error) {
							android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
							android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone in the i3D app on your phone first"
							android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice input needs the internet"
							else -> "Voice input didn't work (code $error)"
						})
					}
					override fun onResults(results: Bundle?) {
						val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
						heard(kind, text, if (text.isBlank()) "Didn't catch that" else null)
					}
				})
				listener.startListening(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
					.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
					.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
					.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3))
			} catch (e: Exception) {
				Log.w(TAG, "Voice input failed", e)
				heard(kind, "", "Voice input didn't work")
			}
		}
	}

	/** Looks a spoken or typed place up and hands the matches to the page as window.i3found({items, error}) */
	private fun lookup(text: String) {
		background {
			val json = JSONObject()
			val items = org.json.JSONArray()
			try {
				val here = carLocationProvider.currentLocation
				val near = here?.let { Pair(it.latitude, it.longitude) } ?: DashStore.homeArea(context)
				DashDestinations.googleKey = DashStore.googleKey(context)
				val saved = DashStore.places(context).filter { it.name.equals(text.trim(), ignoreCase = true) }
				val result = if (saved.isNotEmpty()) ResolveResult(saved) else DashDestinations.resolve(text, near)
				result.items.take(6).forEach {
					items.put(JSONObject().put("name", it.name).put("detail", it.detail ?: "").put("lat", it.lat).put("lon", it.lon))
				}
				json.put("error", result.error ?: JSONObject.NULL)
			} catch (e: Exception) {
				json.put("error", "Couldn't look that up")
			}
			json.put("items", items)
			js("window.i3found && window.i3found($json)")
		}
	}

	private val appLabels = ConcurrentHashMap<String, String>()

	/** The phone's recent notifications, for the Alerts page: no calls, music players or ongoing items */
	private fun readNotes() {
		val service = NotificationListenerServiceImpl.runningInstance
		val all = try { service?.activeNotifications } catch (e: Exception) { null }
		if (all == null) {
			data["notes"] = JSONObject.NULL
			return
		}
		val skipCategories = setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
			Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS)
		val list = all.filter {
			val n = it.notification
			!it.isOngoing && it.packageName != context.packageName &&
				n.category !in skipCategories &&
				(n.flags and Notification.FLAG_GROUP_SUMMARY) == 0
		}.sortedByDescending { it.postTime }.take(8)
		val array = org.json.JSONArray()
		for (sbn in list) {
			val extras = sbn.notification.extras
			val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
			val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
			if (title.isBlank() && text.isBlank()) continue
			val app = appLabels.getOrPut(sbn.packageName) {
				try {
					val pm = context.packageManager
					pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
				} catch (e: Exception) { sbn.packageName }
			}
			array.put(JSONObject().put("app", app).put("title", title).put("text", text.take(160)).put("when", sbn.postTime)
				.put("key", sbn.key).put("reply", replyAction(sbn) != null))
		}
		data["notes"] = array
	}

	/**
	 * A phone call in progress or ringing. The phone app's call notification gives the name and the start time;
	 * without it the phone state and the call the dashboard placed itself still show that a call is on.
	 */
	private fun readCall() {
		val service = NotificationListenerServiceImpl.runningInstance
		val notifications = try { service?.activeNotifications } catch (e: Exception) { null }
		val call = notifications?.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }
		val phone = phoneCallState()
		val now = System.currentTimeMillis()
		if (call != null || phone != 0) dialedSeen = dialedAt != 0L
		// a call placed from here takes a moment before the phone reports it
		val dialling = dialedAt != 0L && !dialedSeen && now - dialedAt < DIAL_GRACE_MS
		if (call == null && phone == 0 && !dialling) {
			callState = 0
			returnedForCall = false
			dialedAt = 0L
			callSeenAt = 0L
			data["call"] = JSONObject.NULL
			return
		}
		val mine = dialedAt != 0L
		if (callSeenAt == 0L) callSeenAt = if (mine) dialedAt else now
		val info = JSONObject()
		var name = ""
		var text = ""
		var ongoing = mine || phone == 2
		var since = callSeenAt
		if (call != null) {
			val extras = call.notification.extras
			val callType = extras.getInt("android.callType", 0)    // 1 incoming, 2 ongoing (Android 12+)
			ongoing = ongoing || callType == 2 || (callType == 0 && extras.getBoolean("android.showChronometer", false))
			name = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
			text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
			if (ongoing && call.notification.`when` > 0) since = call.notification.`when`
		}
		if (mine && name.isBlank()) name = dialedName.ifBlank { dialedNumber }
		info.put("name", name)
		info.put("text", text)
		info.put("number", if (mine) dialedNumber else "")
		info.put("outgoing", mine)
		info.put("ongoing", ongoing)
		info.put("since", since)
		callState = if (ongoing) 2 else 1
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
		mediaController = controller
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

	/** Shuts everything down. afterwards runs on the main thread once the page has been taken off its display */
	fun onDestroy(afterwards: () -> Unit = {}) {
		if (instance === this) instance = null
		pageLoaded = false
		pushing = false
		videoOn = false
		carUi = null
		try { handler.removeCallbacksAndMessages(null) } catch (e: Exception) { Log.w(TAG, "Couldn't clear the queue", e) }
		try { worker.shutdownNow() } catch (e: Exception) { Log.w(TAG, "Couldn't stop the background jobs", e) }
		try { music?.close() } catch (e: Exception) { Log.w(TAG, "Music close failed", e) }
		music = null
		try { scope.cancel() } catch (e: Exception) { Log.w(TAG, "Couldn't cancel the page jobs", e) }
		handler.post {
			try { presentation?.dismiss() } catch (e: Exception) { Log.w(TAG, "Couldn't close the page", e) }
			presentation = null
			try { tts?.shutdown() } catch (e: Exception) { Log.w(TAG, "Couldn't stop the voice", e) }
			tts = null
			try { recognizer?.destroy() } catch (e: Exception) { Log.w(TAG, "Couldn't stop listening", e) }
			recognizer = null
			try { afterwards() } catch (e: Exception) { Log.w(TAG, "Problem after closing the page", e) }
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

		/* ---- the dashboard's own menu ---- */

		/** Saved places, recent destinations, starred contacts and remembered videos, as JSON */
		@JavascriptInterface
		fun menu(): String {
			val json = JSONObject()
			try {
				fun places(list: List<Resolved>) = org.json.JSONArray().also { array ->
					list.forEach { array.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
				}
				json.put("places", places(DashStore.places(context).take(8)))
				json.put("recents", places(DashStore.recents(context).take(5)))
				json.put("contacts", contacts(true, 12))
				json.put("everyone", contacts(false, 250))
				json.put("recentCalls", recentCalls())
				json.put("canSeeContacts", hasPermission(android.Manifest.permission.READ_CONTACTS))
				json.put("canCall", hasPermission(android.Manifest.permission.CALL_PHONE))
				json.put("videos", DashStore.videos(context))
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't build the menu", e)
			}
			return json.toString()
		}

		@JavascriptInterface
		fun navStarted(lat: Double, lon: Double, name: String) {
			mapAppMode.currentNavDestination = LatLong(lat, lon)
			if (name.isNotBlank()) DashStore.addRecent(context, name, lat, lon)
		}

		/** The car's letter wheel, the only way to type. Choosing a result comes straight back to the dashboard */
		@JavascriptInterface
		fun search() {
			background { carUi?.openSearch() }
		}

		@JavascriptInterface
		fun answer() = answerCall()

		@JavascriptInterface
		fun hangup() = hangUpCall()

		@JavascriptInterface
		fun dial(number: String, name: String) = placeCall(number, name)

		/** toggle, next or prev on whatever is playing */
		@JavascriptInterface
		fun media(command: String) {
			try {
				val controls = mediaController?.transportControls ?: return
				when (command) {
					"toggle" -> if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
					"next" -> controls.skipToNext()
					"prev" -> controls.skipToPrevious()
					// podcasts and audiobooks: jump on half a minute, or back a quarter
					"fwd", "back" -> {
						val at = mediaController?.playbackState?.position ?: return
						controls.seekTo(maxOf(0L, at + if (command == "fwd") 30000L else -15000L))
					}
				}
			} catch (e: Exception) {
				Log.w(TAG, "Couldn't send $command to the music app", e)
			}
		}

		/** Listens for a place, a name or a reply. The words arrive later as window.i3heard(kind, text, problem) */
		@JavascriptInterface
		fun listen(kind: String) = this@DashController.listen(kind)

		/** Finds places for some words. The matches arrive later as window.i3found({items, error}) */
		@JavascriptInterface
		fun lookup(text: String) = this@DashController.lookup(text)

		/** Answers a message through its notification. False if that message can't be answered this way */
		@JavascriptInterface
		fun reply(key: String, text: String): Boolean = sendReply(key, text)

		/** Clears a notification from the phone */
		@JavascriptInterface
		fun clearNote(key: String) = this@DashController.clearNote(key)

		/** Lists the music apps. The answer arrives later as window.i3music({rows, top, played}) */
		@JavascriptInterface
		fun musicOpen() {
			background {
				try { music().reset() } catch (e: Exception) { Log.w(TAG, "Music reset failed", e) }
				sendMusic(false)
			}
		}

		/** Opens an app or folder, or plays a playlist or track. The answer arrives as window.i3music(...) */
		@JavascriptInterface
		fun musicPick(row: Int) {
			background {
				val played = try { music().pick(row) } catch (e: Exception) { Log.w(TAG, "Music pick failed", e); false }
				sendMusic(played)
			}
		}

		private fun sendMusic(played: Boolean) {
			val json = JSONObject()
			try {
				json.put("rows", org.json.JSONArray(music().rows()))
				json.put("top", music().atTop())
			} catch (e: Exception) {
				json.put("rows", org.json.JSONArray())
				json.put("top", true)
			}
			json.put("played", played)
			js("window.i3music && window.i3music($json)")
		}

		/** Plays a remembered YouTube video. Returns sent, moving or nocar */
		@JavascriptInterface
		fun video(id: String, list: String): String = playVideo(id, list)
	}

	/** The last people spoken to, newest first, from the phone's call list */
	private fun recentCalls(): org.json.JSONArray {
		val array = org.json.JSONArray()
		if (!hasPermission(android.Manifest.permission.READ_CALL_LOG)) return array
		try {
			val calls = android.provider.CallLog.Calls.CONTENT_URI
			context.contentResolver.query(calls,
				arrayOf(android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.NUMBER,
					android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.NEW),
				null, null, "${android.provider.CallLog.Calls.DATE} DESC")?.use { cursor ->
				val seen = HashSet<String>()
				var looked = 0
				while (cursor.moveToNext() && array.length() < 8 && looked++ < 60) {
					val number = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
					val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: number
					val missed = cursor.getInt(2) == android.provider.CallLog.Calls.MISSED_TYPE && cursor.getInt(3) == 1
					if (seen.add(number.filter { it.isDigit() }.takeLast(9))) array.put(JSONObject().put("name", name).put("number", number).put("missed", missed))
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't read the recent calls", e)
		}
		return array
	}

	/**
	 * Phone contacts that have a number, by name, each with all of its numbers and what they are
	 * (Mobile, Work...), so the driver can pick one. starred picks only the favourites
	 */
	private fun contacts(starred: Boolean, limit: Int): org.json.JSONArray {
		val array = org.json.JSONArray()
		if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) return array
		try {
			val phone = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
			context.contentResolver.query(phone,
				arrayOf(PhoneColumns.DISPLAY_NAME, PhoneColumns.NUMBER, PhoneColumns.TYPE, PhoneColumns.LABEL),
				if (starred) "${PhoneColumns.STARRED} = 1" else null, null,
				"${PhoneColumns.DISPLAY_NAME} COLLATE NOCASE ASC, ${PhoneColumns.IS_SUPER_PRIMARY} DESC")?.use { cursor ->
				val people = LinkedHashMap<String, JSONObject>()
				val seen = HashSet<String>()
				while (cursor.moveToNext()) {
					val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
					val number = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
					val key = name.lowercase()
					val person = people[key] ?: run {
						if (people.size >= limit) return@run null
						JSONObject().put("name", name).put("number", number).put("numbers", org.json.JSONArray()).also { people[key] = it }
					} ?: continue
					if (!seen.add(key + "|" + number.filter { it.isDigit() }.takeLast(9))) continue      // the same number saved twice
					val label = try {
						PhoneColumns.getTypeLabel(context.resources, cursor.getInt(2), cursor.getString(3))?.toString() ?: ""
					} catch (e: Exception) { "" }
					person.getJSONArray("numbers").put(JSONObject().put("number", number).put("label", label))
				}
				people.values.forEach { array.put(it) }
			}
		} catch (e: Exception) {
			Log.w(TAG, "Couldn't read the contacts", e)
		}
		return array
	}
}

/**
 * Shows the i3 Dash web page on the hidden display that is captured and sent to the car
 */
class DashPresentation(context: Context, display: Display, private val bridge: Any,
                       private val onLoaded: () -> Unit = {}): Presentation(context, display) {
	var webView: WebView? = null
		private set
	@Volatile private var inVideo = false

	/** Swaps the dashboard for a bare YouTube player. The page has no access to the phone bridge */
	fun showVideo(id: String, list: String) {
		val web = webView ?: return
		inVideo = true
		web.removeJavascriptInterface("I3Native")
		val query = StringBuilder("autoplay=1&playsinline=1&rel=0&controls=0&modestbranding=1")
		if (list.isNotEmpty()) query.append("&list=").append(Uri.encode(list))
		val src = "https://www.youtube.com/embed/" + Uri.encode(id) + "?" + query
		val html = "<html><body style='margin:0;background:#000'><iframe src='$src' style='border:0;width:100vw;height:100vh' " +
			"allow='autoplay; encrypted-media' referrerpolicy='strict-origin-when-cross-origin'></iframe></body></html>"
		web.loadDataWithBaseURL("https://i3dash.app/", html, "text/html", "utf-8", null)
	}

	/** Back to the dashboard */
	fun showDash() {
		val web = webView ?: return
		inVideo = false
		web.addJavascriptInterface(bridge, "I3Native")
		web.loadUrl("file:///android_asset/i3dash/index.html")
	}

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
				if (!inVideo) onLoaded()
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
