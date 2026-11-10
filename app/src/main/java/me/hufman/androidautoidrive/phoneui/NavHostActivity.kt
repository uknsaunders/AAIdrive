package me.hufman.androidautoidrive.phoneui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.phoneui.viewmodels.ConnectionStatusModel
import me.hufman.androidautoidrive.phoneui.viewmodels.viewModels

class NavHostActivity: AppCompatActivity() {

	private val connectionViewModel by viewModels<ConnectionStatusModel> { ConnectionStatusModel.Factory(this.applicationContext) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		AppSettings.loadSettings(this)
		if (AppSettings[AppSettings.KEYS.ENABLED_ANALYTICS].toBoolean()) {
			Analytics.init(this)
		}
		CarInformation.loadCache(MutableAppSettingsReceiver(applicationContext))

		if (!AppSettings[AppSettings.KEYS.FIRST_START_DONE].toBoolean()) {
			val intent = Intent(this, WelcomeActivity::class.java)
			startActivity(intent)
			finish()
			return
		}

		setContentView(R.layout.activity_navhost)
		val navToolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.nav_toolbar)
		setSupportActionBar(navToolbar)

		// the home screen is the only top level page, everything else gets a back arrow
		val appBarConfig = AppBarConfiguration(setOf(R.id.nav_overview))
		val navController = findNavController(R.id.nav_host_fragment)
		setupActionBarWithNavController(navController, appBarConfig)
	}

	override fun onSupportNavigateUp(): Boolean {
		return findNavController(R.id.nav_host_fragment).navigateUp() || super.onSupportNavigateUp()
	}

	override fun onResume() {
		super.onResume()

		startService()
	}

	fun startService() {
		try {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
				// this is a clear signal of car connection, we can confidently startForeground
				this.startForegroundService(Intent(this, MainService::class.java).setAction(MainService.ACTION_START))
			} else {
				this.startService(Intent(this, MainService::class.java).setAction(MainService.ACTION_START))
			}
		} catch (e: IllegalStateException) {
			// Android Oreo strenuously objects to starting the service if the activity isn't visible
			// for example, when Android Studio tries to start the Activity with the screen off
		}
	}

	override fun onPause() {
		super.onPause()
		// stop animations in the nav header
		connectionViewModel.onPause()
	}
}