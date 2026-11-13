package me.hufman.androidautoidrive.carapp.dash

import android.hardware.display.VirtualDisplay
import android.util.Log
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.android.CarAppAssetResources
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.CarInformation
import me.hufman.androidautoidrive.MutableAppSettingsReceiver
import me.hufman.androidautoidrive.carapp.CarAppService
import me.hufman.androidautoidrive.carapp.maps.MapApp
import me.hufman.androidautoidrive.carapp.maps.MapAppMode
import me.hufman.androidautoidrive.carapp.maps.MapsInteractionControllerListener
import me.hufman.androidautoidrive.carapp.maps.VirtualDisplayScreenCapture
import me.hufman.androidautoidrive.carapp.music.MusicAppMode
import me.hufman.androidautoidrive.cds.CDSDataProvider
import me.hufman.androidautoidrive.maps.CdsLocationProvider

/**
 * i3 Dash: shows the i3 Dash cockpit (car, battery, journey stats and our own sat nav)
 * as one full-screen app in the car's Connected Apps menu.
 *
 * It reuses AAIdrive's full-screen image plumbing (the same one the custom Map uses):
 * the dashboard is drawn into a WebView on a hidden virtual display on the phone,
 * and each frame is sent to the iDrive screen as a picture.
 */
class DashAppService: CarAppService() {
	companion object {
		const val TAG = "i3Dash"
	}

	var mapAppMode: MapAppMode? = null
	var mapApp: MapApp? = null
	var screenCapture: VirtualDisplayScreenCapture? = null
	var virtualDisplay: VirtualDisplay? = null
	var dashController: DashController? = null
	var interactionListener: MapsInteractionControllerListener? = null

	override fun shouldStartApp(): Boolean = true

	override fun onCarStart() {
		Log.i(TAG, "Starting i3 Dash")
		val handler = this.handler!!

		val cdsData = CDSDataProvider()
		cdsData.setConnection(CarInformation.cdsData.asConnection(cdsData))
		val carLocationProvider = CdsLocationProvider(cdsData, false)

		val appSettings = MutableAppSettingsReceiver(this, handler)
		// the dashboard is designed for the full width of the i3's wide screen
		appSettings[AppSettings.KEYS.MAP_WIDESCREEN] = "true"

		val mapAppMode = MapAppMode.build(RHMIDimensions.create(carInformation.capabilities), appSettings, cdsData,
				MusicAppMode.TRANSPORT_PORTS.fromPort(iDriveConnectionStatus.port) ?: MusicAppMode.TRANSPORT_PORTS.BT)
		this.mapAppMode = mapAppMode

		val screenCapture = VirtualDisplayScreenCapture.build(mapAppMode)
		this.screenCapture = screenCapture
		// 160 dpi makes one web pixel equal one car screen pixel
		val virtualDisplay = VirtualDisplayScreenCapture.createVirtualDisplay(applicationContext, screenCapture.imageCapture, 160, "i3DashDisplay")
		this.virtualDisplay = virtualDisplay

		val dashController = DashController(applicationContext, virtualDisplay, carLocationProvider, mapAppMode)
		this.dashController = dashController
		val interactionListener = MapsInteractionControllerListener(applicationContext, dashController)
		interactionListener.onCreate()
		this.interactionListener = interactionListener

		val mapApp = MapApp(iDriveConnectionStatus, securityAccess,
				CarAppAssetResources(applicationContext, "smartthings"),
				mapAppMode, carLocationProvider,
				dashController,     // knob turns go straight to the page, not through an Android broadcast
				DashPlaceSearch(carLocationProvider, applicationContext),
				screenCapture,
				" ", "i3D",       // blank caption: the car draws it as a title line, and the menu entry keeps the name
				resources.openRawResource(me.hufman.androidautoidrive.R.raw.i3dash_entry).use { it.readBytes() })
		this.mapApp = mapApp
		dashController.carUi = object: DashCarUi {
			override fun openSearch() = mapApp.openSearch()
			override fun openDash() = mapApp.openFullScreen()
		}
		mapApp.onCreate(handler)
	}

	override fun onCarStop() {
		// each step on its own, so one failure can't stop the rest or take the app down
		fun step(what: String, action: () -> Unit) {
			try { action() } catch (e: Exception) { Log.w(TAG, "Problem while shutting down i3D ($what)", e) }
		}
		val display = virtualDisplay
		step("navigation") { mapAppMode?.currentNavDestination = null }
		step("screen capture") { screenCapture?.onDestroy() }
		step("listener") { interactionListener?.onDestroy() }
		step("car app") { mapApp?.onDestroy() }
		step("car connection") { mapApp?.disconnect() }
		// the page is taken off the hidden display first, and only then is the display let go
		val controller = dashController
		if (controller != null) {
			step("page") { controller.onDestroy { step("display") { display?.release() } } }
		} else {
			step("display") { display?.release() }
		}
		screenCapture = null
		interactionListener = null
		dashController = null
		virtualDisplay = null
		mapApp = null
	}
}
