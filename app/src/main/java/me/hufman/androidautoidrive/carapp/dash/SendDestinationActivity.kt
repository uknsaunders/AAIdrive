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
			Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
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
				val near = here?.let { Pair(it.latitude, it.longitude) }
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

		@JavascriptInterface
		fun status(): String {
			val json = JSONObject()
			json.put("car", DashController.instance != null)
			json.put("w3w", BuildConfig.W3W_API_KEY.isNotBlank())
			return json.toString()
		}
	}
}
