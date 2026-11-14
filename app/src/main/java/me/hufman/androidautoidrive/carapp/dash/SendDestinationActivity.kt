package me.hufman.androidautoidrive.carapp.dash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import me.hufman.androidautoidrive.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The phone side of i3 Dash: type or share a destination (place, postcode, what3words,
 * Google Maps link) and send it to the car, manage saved places and the electricity price.
 */
class SendDestinationActivity: Activity() {
	private var webView: WebView? = null
	private var sharedText = ""

	@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		sharedText = textFrom(intent)
		val web = WebView(this)
		web.settings.javaScriptEnabled = true
		web.settings.domStorageEnabled = true
		web.addJavascriptInterface(Phone(), "Phone")
		web.webViewClient = WebViewClient()
		setContentView(web)
		web.loadUrl("file:///android_asset/i3dash/phone.html")
		webView = web
	}

	override fun onNewIntent(intent: Intent?) {
		super.onNewIntent(intent)
		setIntent(intent)
		sharedText = textFrom(intent)
		webView?.evaluateJavascript("window.onShared && window.onShared()", null)
	}

	override fun onDestroy() {
		webView?.destroy()
		webView = null
		super.onDestroy()
	}

	private fun textFrom(intent: Intent?): String {
		intent ?: return ""
		return when (intent.action) {
			Intent.ACTION_SEND -> {
				// YouTube shares only the link as text, with the video's name in the subject
				val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
				val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
				if (subject.isNotBlank() && text.contains("youtu") && !text.contains(subject)) "$subject $text" else text
			}
			Intent.ACTION_VIEW -> intent.dataString ?: ""
			else -> ""
		}
	}

	private fun listJson(list: List<Resolved>): String {
		val array = JSONArray()
		list.forEach { array.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
		return array.toString()
	}

	inner class Phone {
		/** What was shared into the app, once */
		@JavascriptInterface
		fun shared(): String {
			val text = sharedText
			sharedText = ""
			return text
		}

		@JavascriptInterface
		fun resolve(id: Int, text: String) {
			thread {
				val here = DashController.instance?.carLocationProvider?.currentLocation
				val near = here?.let { Pair(it.latitude, it.longitude) } ?: DashStore.homeArea(applicationContext)
				DashDestinations.googleKey = DashStore.googleKey(applicationContext)
				DashDestinations.w3wKey = DashStore.w3wKey(applicationContext)
				val result = DashDestinations.resolve(text, near)
				val json = JSONObject()
				json.put("error", result.error ?: JSONObject.NULL)
				val items = JSONArray()
				result.items.forEach {
					items.put(JSONObject().put("name", it.name).put("detail", it.detail ?: JSONObject.NULL).put("lat", it.lat).put("lon", it.lon))
				}
				json.put("items", items)
				runOnUiThread { webView?.evaluateJavascript("window.onResolved($id, $json)", null) }
			}
		}

		/** Sends a destination to the car. Returns "sent" if the dashboard is on screen, otherwise "queued" */
		@JavascriptInterface
		fun go(lat: Double, lon: Double, name: String): String {
			DashStore.addRecent(applicationContext, name, lat, lon)
			DashStore.setPending(applicationContext, name, lat, lon)
			val delivered = DashController.instance?.deliverPending() ?: false
			return if (delivered) "sent" else "queued"
		}

		/** Plays a YouTube video on the car screen, only if the car is standing still. Returns sent, moving or nocar */
		@JavascriptInterface
		fun video(id: String, list: String, title: String): String {
			DashStore.addVideo(applicationContext, id, list, title)     // remembered, so the car's menu can start it later
			return DashController.instance?.playVideo(id, list) ?: "nocar"
		}

		@JavascriptInterface
		fun places(): String = listJson(DashStore.places(applicationContext))

		@JavascriptInterface
		fun recents(): String = listJson(DashStore.recents(applicationContext))

		@JavascriptInterface
		fun savePlace(name: String, lat: Double, lon: Double) = DashStore.savePlace(applicationContext, name, lat, lon)

		@JavascriptInterface
		fun deletePlace(name: String) = DashStore.deletePlace(applicationContext, name)

		@JavascriptInterface
		fun tariff(): Double = DashStore.tariff(applicationContext)

		@JavascriptInterface
		fun setTariff(pence: Double) = DashStore.setTariff(applicationContext, pence)

		/** Saves the driver's Google key after one test search. A blank key switches Google search off. Answers window.onGoogleKey(problem or null) */
		@JavascriptInterface
		fun setGoogleKey(key: String) {
			val clean = key.trim()
			thread {
				val problem = if (clean.isEmpty()) null else DashDestinations.googleProblem(clean)
				if (problem == null) DashStore.setGoogleKey(applicationContext, clean)
				val answer = if (problem == null) "null" else JSONObject.quote(problem)
				runOnUiThread { webView?.evaluateJavascript("window.onGoogleKey($answer)", null) }
			}
		}

		/** Saves the driver's Open Charge Map key after one test lookup. A blank key switches live charger availability off.
		 * Answers window.onOcmKey(problem or null) */
		@JavascriptInterface
		fun setOcmKey(key: String) {
			val clean = key.trim()
			thread {
				val problem = if (clean.isEmpty()) null else DashDestinations.ocmProblem(clean)
				if (problem == null) DashStore.setOcmKey(applicationContext, clean)
				val answer = if (problem == null) "null" else JSONObject.quote(problem)
				runOnUiThread { webView?.evaluateJavascript("window.onOcmKey($answer)", null) }
			}
		}

		/** Saves the driver's what3words key after one test lookup. A blank key falls back to a build-time key, if there is one.
		 * Kept on the phone only, never inside the downloadable APK. Answers window.onW3wKey(problem or null) */
		@JavascriptInterface
		fun setW3wKey(key: String) {
			val clean = key.trim()
			thread {
				val problem = if (clean.isEmpty()) null else DashDestinations.w3wProblem(clean)
				if (problem == null) DashStore.setW3wKey(applicationContext, clean)
				val answer = if (problem == null) "null" else JSONObject.quote(problem)
				runOnUiThread { webView?.evaluateJavascript("window.onW3wKey($answer)", null) }
			}
		}

		@JavascriptInterface
		fun status(): String {
			val json = JSONObject()
			json.put("car", DashController.instance != null)
			json.put("w3w", DashStore.w3wKey(applicationContext).isNotBlank() || BuildConfig.W3W_API_KEY.isNotBlank())
			json.put("google", DashStore.googleKey(applicationContext).isNotBlank())
			json.put("ocm", DashStore.ocmKey(applicationContext).isNotBlank())
			return json.toString()
		}
	}
}
