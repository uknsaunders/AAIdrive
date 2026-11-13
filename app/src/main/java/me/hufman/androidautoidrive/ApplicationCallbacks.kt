package me.hufman.androidautoidrive

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

class ApplicationCallbacks: Application(), Application.ActivityLifecycleCallbacks {
	companion object {
		const val CRASH_FILE = "last_crash.txt"
		// track whether we are in the foreground
		val visibleWindows = AtomicInteger(0)

		fun onResume() {
			visibleWindows.incrementAndGet()
		}
		fun onPause() {
			visibleWindows.decrementAndGet()
		}
	}

	override fun onCreate() {
		super.onCreate()

		registerActivityLifecycleCallbacks(this)
		recordCrashes()
	}

	/** Keeps the details of the last crash in a file, so the home screen can offer to share it */
	private fun recordCrashes() {
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try {
				java.io.File(filesDir, CRASH_FILE).writeText(
					"i3D ${BuildConfig.VERSION_NAME} on Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\n" +
					"${java.util.Date()}\nthread ${thread.name}\n\n" + android.util.Log.getStackTraceString(error))
			} catch (e: Throwable) {
				// nothing more can be done here
			}
			previous?.uncaughtException(thread, error)
		}
	}

	override fun onActivityPaused(p0: Activity) {
		onPause()
	}

	override fun onActivityResumed(p0: Activity) {
		onResume()
	}

	override fun onActivityStarted(p0: Activity) {}

	override fun onActivityDestroyed(p0: Activity) {}

	override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}

	override fun onActivityStopped(p0: Activity) {}

	override fun onActivityCreated(p0: Activity, p1: Bundle?) {}
}