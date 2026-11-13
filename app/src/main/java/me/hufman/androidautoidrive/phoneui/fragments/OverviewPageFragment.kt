package me.hufman.androidautoidrive.phoneui.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.carapp.dash.SendDestinationActivity
import me.hufman.androidautoidrive.phoneui.viewmodels.ConnectionStatusModel
import me.hufman.androidautoidrive.phoneui.viewmodels.activityViewModels

/** The phone app's home screen: a clear connection card and a list of big buttons */
class OverviewPageFragment: Fragment() {
	private val viewModel by activityViewModels<ConnectionStatusModel> { ConnectionStatusModel.Factory(requireContext().applicationContext) }

	private class Tile(val icon: Int, val title: Int, val sub: Int, val mark: String = "›", val action: () -> Unit)

	override fun onResume() {
		super.onResume()
		// permissions may have changed while the phone's settings were open, so redraw the setup list
		view?.findViewById<LinearLayout>(R.id.tiles)?.let { buildTiles(it) }
	}

	@Deprecated("Deprecated in Java")
	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		view?.findViewById<LinearLayout>(R.id.tiles)?.let { buildTiles(it) }
	}

	private val callPermissions = arrayOf(
		android.Manifest.permission.READ_CONTACTS,
		android.Manifest.permission.CALL_PHONE,
		android.Manifest.permission.ANSWER_PHONE_CALLS,
		android.Manifest.permission.READ_CALL_LOG,
		android.Manifest.permission.READ_PHONE_STATE)

	private fun hasNotificationAccess(ctx: Context) =
		androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

	/** True while Android is blocking this app's notification access switch ("Restricted setting") */
	private fun settingsLocked(ctx: Context): Boolean {
		if (android.os.Build.VERSION.SDK_INT < 33) return false
		return try {
			val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
			ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), ctx.packageName) != android.app.AppOpsManager.MODE_ALLOWED
		} catch (e: Exception) {
			false       // this phone doesn't have the lock
		}
	}

	/** Opens Android's notification access switch for this app, or the list of apps on older phones */
	private fun openNotificationAccess(ctx: Context) {
		try {
			if (android.os.Build.VERSION.SDK_INT >= 30) {
				val listener = android.content.ComponentName(ctx, me.hufman.androidautoidrive.notifications.NotificationListenerServiceImpl::class.java)
				startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
					.putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()))
				return
			}
		} catch (e: Exception) {
			// fall through to the list
		}
		try {
			startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
		} catch (e: Exception) {
			findNavController().navigate(R.id.nav_notifications)
		}
	}

	private fun hasCallAccess(ctx: Context) = callPermissions.all {
		ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		return inflater.inflate(R.layout.fragment_home, container, false)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val ctx = requireContext()
		val statusTitle = view.findViewById<TextView>(R.id.statusTitle)
		val statusHint = view.findViewById<TextView>(R.id.statusHint)
		val statusDot = view.findViewById<View>(R.id.statusDot)
		val step1 = view.findViewById<TextView>(R.id.step1)
		val step2 = view.findViewById<TextView>(R.id.step2)
		val step3 = view.findViewById<TextView>(R.id.step3)
		val openConnection = View.OnClickListener { findNavController().navigate(R.id.nav_connection) }
		view.findViewById<View>(R.id.statusCard).setOnClickListener(openConnection)
		view.findViewById<Button>(R.id.btnHelp).setOnClickListener(openConnection)

		var linked = false
		var ready = false
		var connected = false
		fun mark(ok: Boolean, text: Int) = (if (ok) "✓  " else "○  ") + ctx.getString(text)
		fun refreshSteps() {
			step1.text = mark(linked, R.string.home_step_phone)
			step2.text = mark(ready, R.string.home_step_ready)
			step3.text = mark(connected, R.string.home_step_connected)
		}
		refreshSteps()

		viewModel.carConnectionText.observe(viewLifecycleOwner, Observer { text ->
			statusTitle.text = text(ctx)
		})
		viewModel.carConnectionHint.observe(viewLifecycleOwner, Observer { hint ->
			val s = hint(ctx)
			statusHint.text = s
			statusHint.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
		})
		viewModel.carConnectionColor.observe(viewLifecycleOwner, Observer { color ->
			val wrapped = DrawableCompat.wrap(statusDot.background.mutate())
			DrawableCompat.setTint(wrapped, color(ctx))
			statusDot.background = wrapped
		})
		viewModel.isUsbConnected.observe(viewLifecycleOwner, Observer { linked = it == true || viewModel.isBtConnected.value == true; refreshSteps() })
		viewModel.isBtConnected.observe(viewLifecycleOwner, Observer { linked = it == true || viewModel.isUsbConnected.value == true; refreshSteps() })
		viewModel.isBclReady.observe(viewLifecycleOwner, Observer { ready = it == true; refreshSteps() })
		viewModel.isBclConnected.observe(viewLifecycleOwner, Observer { connected = it == true; refreshSteps() })

	}

	private fun buildTiles(container: LinearLayout) {
		val ctx = requireContext()
		val nav = findNavController()
		val themeAttrs = ctx.obtainStyledAttributes(R.style.optionMapVisible, intArrayOf(android.R.attr.visibility))
		val mapVisible = themeAttrs.getInt(0, 0) == View.VISIBLE
		themeAttrs.recycle()

		container.removeAllViews()

		// if the app stopped unexpectedly last time, offer to send the details
		val crashFile = java.io.File(ctx.filesDir, me.hufman.androidautoidrive.ApplicationCallbacks.CRASH_FILE)
		if (crashFile.exists()) {
			container.addView(sectionLabel(ctx, R.string.home_section_crash))
			container.addView(tileView(ctx, Tile(R.drawable.ic_heart_feather, R.string.home_crash, R.string.home_crash_sub) {
				try {
					val report = crashFile.readText().take(12000)
					startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
						.putExtra(Intent.EXTRA_SUBJECT, "i3D crash report").putExtra(Intent.EXTRA_TEXT, report), getString(R.string.home_crash)))
				} catch (e: Exception) {
					// nothing to share with
				}
			}))
			container.addView(tileView(ctx, Tile(R.drawable.ic_settings_feather, R.string.home_crash_clear, R.string.home_crash_clear_sub, "×") {
				crashFile.delete()
				buildTiles(container)
			}))
		}

		// one list for everything the phone has to allow, shown until it is all done
		val notificationsOk = hasNotificationAccess(ctx)
		val callsOk = hasCallAccess(ctx)
		if (!notificationsOk || !callsOk) {
			container.addView(sectionLabel(ctx, R.string.home_section_setup))
			if (notificationsOk) {
				container.addView(tileView(ctx, Tile(R.drawable.ic_bell_feather, R.string.home_setup_notifications, R.string.home_setup_done, "✓") {}))
			} else {
				// Android locks this switch for apps installed outside the Play Store, and only offers the unlock
				// after the switch has been tried once. So: try it, unlock it, switch it on.
				val locked = settingsLocked(ctx)
				if (locked) container.addView(noteLabel(ctx, R.string.home_setup_locked_note))
				container.addView(tileView(ctx, Tile(R.drawable.ic_bell_feather,
					if (locked) R.string.home_setup_try else R.string.home_setup_notifications,
					if (locked) R.string.home_setup_try_sub else R.string.home_setup_notifications_sub) {
					openNotificationAccess(ctx)
				}))
				if (locked) {
					container.addView(tileView(ctx, Tile(R.drawable.ic_settings_feather, R.string.home_setup_unlock, R.string.home_setup_unlock_sub) {
						try {
							startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
								android.net.Uri.fromParts("package", ctx.packageName, null)))
						} catch (e: Exception) {
							// no app details screen on this phone
						}
					}))
					container.addView(tileView(ctx, Tile(R.drawable.ic_bell_feather, R.string.home_setup_switch, R.string.home_setup_switch_sub) {
						openNotificationAccess(ctx)
					}))
				}
			}
			container.addView(tileView(ctx, Tile(R.drawable.ic_cellphone_android, R.string.home_calls,
				if (callsOk) R.string.home_setup_done else R.string.home_calls_sub,
				if (callsOk) "✓" else "›") {
				if (!callsOk) requestPermissions(callPermissions, 42)
			}))
		}

		val main = listOf(
			Tile(R.drawable.ic_send_feather, R.string.home_send, R.string.home_send_sub) {
				startActivity(Intent(ctx, SendDestinationActivity::class.java))
			}
		)

		// the original AAIdrive apps still work, but each is a separate entry in the car's menus
		val extras = mutableListOf(
			Tile(R.drawable.ic_music_feather, R.string.menu_music, R.string.home_music_sub) { nav.navigate(R.id.nav_music) },
			Tile(R.drawable.ic_bell_feather, R.string.menu_notifications, R.string.home_notifications_sub) { nav.navigate(R.id.nav_notifications) },
			Tile(R.drawable.ic_navigation_feather, R.string.menu_navigation, R.string.home_navigation_sub) { nav.navigate(R.id.nav_navigation) }
		)
		if (mapVisible) {
			extras.add(Tile(R.drawable.ic_map_feather, R.string.menu_maps, R.string.home_maps_sub) { nav.navigate(R.id.nav_maps) })
		}
		extras.add(Tile(R.drawable.ic_calendar_feather, R.string.menu_calendar, R.string.home_calendar_sub) { nav.navigate(R.id.nav_calendar) })
		extras.add(Tile(R.drawable.ic_mic_feather, R.string.menu_assistants, R.string.home_assistants_sub) { nav.navigate(R.id.nav_assistants) })
		extras.add(Tile(R.drawable.ic_outline_extension_24, R.string.menu_addons, R.string.home_addons_sub) { nav.navigate(R.id.nav_addons) })

		val more = listOf(
			Tile(R.drawable.ic_baseline_directions_car_24, R.string.menu_carinfo, R.string.home_carinfo_sub) { nav.navigate(R.id.nav_carinfo) },
			Tile(R.drawable.ic_settings_feather, R.string.menu_settings, R.string.home_settings_sub) { nav.navigate(R.id.nav_settings) },
			Tile(R.drawable.ic_heart_feather, R.string.menu_support, R.string.home_support_sub) { nav.navigate(R.id.nav_support) }
		)

		container.addView(sectionLabel(ctx, R.string.home_section_main))
		main.forEach { container.addView(tileView(ctx, it)) }
		container.addView(noteLabel(ctx, R.string.home_car_note))
		container.addView(sectionLabel(ctx, R.string.home_section_more))
		more.forEach { container.addView(tileView(ctx, it)) }
		container.addView(sectionLabel(ctx, R.string.home_section_extras))
		container.addView(noteLabel(ctx, R.string.home_extras_note))
		extras.forEach { container.addView(tileView(ctx, it)) }
	}

	private fun noteLabel(ctx: Context, text: Int) = TextView(ctx).apply {
		setText(text)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		setTextColor(themeColors(ctx, android.R.attr.textColorSecondary))
		layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			topMargin = dp(8); marginStart = dp(4); marginEnd = dp(4)
		}
	}

	private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

	private fun themeColors(ctx: Context, attr: Int): ColorStateList? {
		val a = ctx.obtainStyledAttributes(intArrayOf(attr))
		val c = a.getColorStateList(0)
		a.recycle()
		return c
	}

	private fun sectionLabel(ctx: Context, text: Int) = TextView(ctx).apply {
		setText(text)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
		isAllCaps = true
		letterSpacing = 0.08f
		setTextColor(themeColors(ctx, android.R.attr.textColorSecondary))
		layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			topMargin = dp(24); bottomMargin = dp(4); marginStart = dp(4)
		}
	}

	private fun tileView(ctx: Context, tile: Tile): View {
		val row = LinearLayout(ctx).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = dp(76)
			setPadding(dp(16), dp(12), dp(16), dp(12))
			background = ContextCompat.getDrawable(ctx, R.drawable.bg_tile)
			isClickable = true
			isFocusable = true
			val tv = TypedValue()
			if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
				foreground = ContextCompat.getDrawable(ctx, tv.resourceId)
			}
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				topMargin = dp(8)
			}
			setOnClickListener { tile.action() }
		}
		val icon = ImageView(ctx).apply {
			setImageResource(tile.icon)
			ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.colorPrimary)))
			layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) }
		}
		val texts = LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		}
		texts.addView(TextView(ctx).apply {
			setText(tile.title)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
			setTextColor(themeColors(ctx, android.R.attr.textColorPrimary))
		})
		texts.addView(TextView(ctx).apply {
			setText(tile.sub)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setTextColor(themeColors(ctx, android.R.attr.textColorSecondary))
		})
		val chevron = TextView(ctx).apply {
			text = tile.mark
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
			setTextColor(themeColors(ctx, android.R.attr.textColorSecondary))
		}
		row.addView(icon); row.addView(texts); row.addView(chevron)
		return row
	}
}
