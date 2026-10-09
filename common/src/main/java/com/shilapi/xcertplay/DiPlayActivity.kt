// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.FawNavigationOutputs
import com.shilapi.xcertplay.hud.FawOutputSettings
import com.shilapi.xcertplay.hud.FawVehicleCapabilities
import com.shilapi.xcertplay.hud.FawVehicleField
import com.shilapi.xcertplay.hud.FawVehicleFieldStore
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns the FAW Play connection flow and delegates page rendering to the landscape UI. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = PAGE_HOME
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var renderedPage: String? = null
    private var pendingScrollY: Int? = null
    private var fawVehicleAdvancedExpanded = false
    private var adbCheckInProgress = false
    private var adbStatus: TextView? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private val vehicleOperationLock = Any()
    private var automaticVehicleValidationPending = false
    private val automaticVehicleValidation = Runnable {
        automaticVehicleValidationPending = false
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(true)
    }

    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, STATUS_REFRESH_MILLIS)
        }
    }

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }

    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        FawNavigationOutputs.onAppOpened(applicationContext)
        window.statusBarColor = BACKGROUND_COLOR
        window.navigationBarColor = BACKGROUND_COLOR
        LandscapeSettingsLayout.fullscreen(this)
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            Log.e(SETUP_LOG_TAG, "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean(STATE_PENDING_CAR_HOTSPOT) ?: false
        fawVehicleAdvancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED_VEHICLE_DATA) ?: false
        page = savedInstanceState?.getString(PAGE_KEY) ?: intent.getStringExtra(PAGE_KEY) ?: PAGE_HOME
        render()
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != PAGE_HOME) {
                    page = PAGE_HOME
                    render()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prepareAutoConnectLaunch(intent)
        page = intent.getStringExtra(PAGE_KEY) ?: PAGE_HOME
        render()
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PAGE_KEY, page)
        outState.putBoolean(STATE_PENDING_CAR_HOTSPOT, pendingCarHotspotSetup)
        outState.putBoolean(STATE_ADVANCED_VEHICLE_DATA, fawVehicleAdvancedExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onStart() {
        super.onStart()
        CenterMapOverlay.onDiPlayScreenShown()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        LandscapeSettingsLayout.fullscreen(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == PAGE_HOME || page == PAGE_SETTINGS || page == PAGE_CONNECTION)) render()
        pausedForAdbSwitchChange = false
        if (initialLaunch) {
            initialLaunch = false
            enableWifiOnLaunch()
            handler.post { autoConnectOnOpen() }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) LandscapeSettingsLayout.fullscreen(this)
    }

    private fun prepareAutoConnectLaunch(intent: Intent) {
        // A singleTop launcher can reuse this activity instead of calling onCreate again.
        // Explicit settings/recovery intents and Back must keep the connection screen usable.
        // The factory launcher may use an explicit component intent without MAIN/LAUNCHER.
        initialLaunch = intent.getStringExtra(PAGE_KEY) == null
    }

    private fun autoConnectOnOpen() {
        if (isFinishing || isDestroyed || setupError != null || pendingCarHotspotSetup ||
            wirelessReset != null || page != PAGE_HOME || intent.getStringExtra(PAGE_KEY) != null || !DiPlayPreferences.autoConnect(this)) return
        if (CarPlayBackgroundSession.hasSession()) {
            openProjection()
            return
        }
        val wireless = AirPlayPersistence.loadWirelessEnabled(this)
        // Keep first-run pairing manual; the saved selection is enough for existing installs.
        if (wireless && DiPlayPreferences.phoneAddress(this).isNullOrBlank()) return
        connect(wireless)
    }

    override fun onPause() {
        WheelKeyService.cancelLearning()
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        wirelessReset?.cancel()
        wirelessReset = null
        WheelKeyService.cancelLearning()
        handler.removeCallbacks(automaticVehicleValidation)
        adbCheckInProgress = false
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        render()
    }

    private val isCompactLayout: Boolean
        get() = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) ||
            resources.configuration.screenWidthDp < COMPACT_WIDTH_DP ||
            resources.configuration.screenHeightDp < COMPACT_HEIGHT_DP

    private fun render() {
        // A pending assignment belongs to the widgets being replaced, never to another page.
        WheelKeyService.cancelLearning()
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { renderedPage == page }
        status = null
        connectButton = null
        disconnectButton = null
        lastRunning = null
        adbSwitches.clear()
        adbStatus = null
        val compact = isCompactLayout
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BACKGROUND_COLOR)
            isFillViewport = true
            clipToPadding = false
        }
        rootScroll = scroll
        val content = column().apply {
            if (compact) setPadding(dp(12), dp(10), dp(12), dp(12))
            else setPadding(dp(32), dp(24), dp(32), dp(32))
        }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_carplay)
                contentDescription = getString(R.string.carplay)
            },
            LinearLayout.LayoutParams(if (compact) dp(24) else dp(36), if (compact) dp(24) else dp(36)),
        )
        header.addView(
            label(getString(R.string.diplay), if (compact) 18 else 26, TEXT_COLOR, true).apply {
                setPadding(if (compact) dp(8) else dp(12), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, if (compact) dp(36) else dp(56), 1f),
        )
        if (page != PAGE_HOME || !compact) {
            header.addView(
                button(if (page == PAGE_HOME) getString(R.string.car_home) else getString(R.string.back), false) {
                    if (page == PAGE_HOME) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    else {
                        page = PAGE_HOME
                        render()
                    }
                },
                LinearLayout.LayoutParams(if (compact) dp(80) else dp(130), if (compact) dp(36) else dp(56)),
            )
        }
        content.addView(header)
        content.addView(space(if (compact) 8 else 24))
        if (page == PAGE_ABOUT) about(content)
        LandscapeSettingsLayout.apply(this, scroll, page)
        renderedPage = page
        refreshStatus()
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
    }

    // Older callers still reflect this entry; rendering belongs to LandscapeSettingsLayout.apply.
    private fun settings(@Suppress("UNUSED_PARAMETER") content: LinearLayout) = Unit

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT_COLOR, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED_COLOR).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT_COLOR))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED_COLOR))
        }
    }

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    private fun renderFawAdbControls(controls: LinearLayout, access: LocalAdb.Access) {
        controls.removeAllViews()
        adbSwitches.keys.retainAll(setOf(R.string.open_after_the_car_starts))
        adbStatus = null
        controls.visibility = if (CarHotspotSettings.visible(true, access)) View.VISIBLE else View.GONE
        if (controls.visibility == View.GONE) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            controls.visibility = View.GONE
            return
        }
        section(controls, getString(R.string.faw_adb_features), R.drawable.ic_dp_permissions) { card ->
            if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
                adbToggle(card, R.string.auto_car_hotspot_title, R.string.auto_car_hotspot_description,
                    read = { CarHotspotSettings.enabled(this) },
                    permissions = {
                        buildList {
                            add(CarHotspotSetup.Permission.HOTSPOT)
                            if (AirPlayPersistence.loadAutoStartOnBoot(this@DiPlayActivity)) {
                                add(CarHotspotSetup.Permission.BOOT_LAUNCH)
                            }
                        }
                    }) {
                    CarHotspotSettings.setEnabled(this, it)
                }
            }
            adbStatus = label(getString(if (access == LocalAdb.Access.READY)
                R.string.adb_access_ready else R.string.adb_not_approved), 14, MUTED_COLOR).also(card::addView)
        }
    }

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            updateAdbSwitches()
            return
        }
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationPending,
        )
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == PAGE_SETTINGS && fawVehicleAdvancedExpanded) render()
        Thread({
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
                runPendingAutomaticVehicleValidation()
                // Re-enable the vehicle controls disabled while this authorization was outstanding.
                if (page == PAGE_SETTINGS && fawVehicleAdvancedExpanded) render()
            }
        }, "diplay-adb-switch").start()
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()
        }
        updatingAdbSwitches = false
    }

    private fun vehicleAdbWorkInProgress(): Boolean = adbCheckInProgress

    private fun enableWifiOnLaunch() {
        PermissionAccess.enableWifiOnLaunch(this)
    }

    private fun carHotspotOffDialog() {
        LandscapeSettingsLayout.dialogBuilder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent(HOTSPOT_SETTINGS_ACTION)
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == OEM_HOTSPOT_SETTINGS_PACKAGE) {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        parent.addView(LandscapeSettingsLayout.wifiDirectStatus(this))
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun scheduleAutomaticVehicleValidation() {
        if (!FawOutputSettings.legacyVehicleProbe(this)) {
            automaticVehicleValidationPending = false
            handler.removeCallbacks(automaticVehicleValidation)
            return
        }
        automaticVehicleValidationPending = true
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    private fun cancelAutomaticVehicleValidationForUserOperation(
        resumeAfter: Boolean,
    ): FawVehicleCapabilities? = synchronized(vehicleOperationLock) {
        handler.removeCallbacks(automaticVehicleValidation)
        automaticVehicleValidationPending = resumeAfter
        FawVehicleFieldStore.load(applicationContext)
    }

    private fun runPendingAutomaticVehicleValidation() {
        if (!automaticVehicleValidationPending || adbSwitchChangePending ||
            vehicleAdbWorkInProgress()) return
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun reconnectForClusterMap() = reconnectIfRunning()

    private fun reconnectIfRunning() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) = numericSettingControl(
        parent, titleId, hintId, range, default, summaryId, load, reconnects,
        R.string.resolution_reset_defaults, R.string.resolution_number_error, save,
    )

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) = numericSettingControl(
        parent, titleId, hintId, range, default, summaryId, load, reconnects,
        R.string.ambient_light_reset_defaults, R.string.custom_number_error, save,
    )

    private fun numericSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        defaultValue: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean,
        resetLabelId: Int,
        errorMessageId: Int,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(
            R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = numericDialogFields()
            val input = numericInput(load())
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED_COLOR))
            val saveLabelId = if (reconnects && CarPlayBackgroundSession.hasSession()) {
                R.string.apply_and_reconnect
            } else {
                R.string.save
            }
            val dialog = LandscapeSettingsLayout.dialogBuilder(this)
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(saveLabelId), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(resetLabelId), null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener applyValue@{
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(errorMessageId, range.first, range.last)
                        return@applyValue
                    }
                    val changed = value != load()
                    save(value)
                    control.text = summary()
                    dialog.dismiss()
                    if (changed && reconnects) reconnectIfRunning()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(defaultValue.toString())
                    input.error = null
                }
            }
            showNumericSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun numericDialogFields() = column().apply {
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }

    private fun numericInput(value: Int) = EditText(this).apply {
        setSingleLine()
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = numericDialogFields()
            fields.addView(label(getString(R.string.ambient_light_threshold_value), 16, MUTED_COLOR))
            val input = numericInput(AirPlayPersistence.loadAmbientLightThreshold(this).lux)
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED_COLOR))
            val dialog = LandscapeSettingsLayout.dialogBuilder(this)
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNumericSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNumericSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        LandscapeSettingsLayout.showDialog(dialog)
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun connect(@Suppress("UNUSED_PARAMETER") requestedWireless: Boolean) {
        wirelessReset?.cancel()
        wirelessReset = null
        pendingCarHotspotSetup = false
        setupError?.let {
            toast(it)
            return
        }
        if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = PAGE_CONNECTION
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (carHotspotOff()) {
            carHotspotOffDialog()
            return
        }
        if (DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true
            choosePhone()
            return
        }
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean(NOTIFICATION_ASKED_KEY, false)) {
            preferences.edit().putBoolean(NOTIFICATION_ASKED_KEY, true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, true)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }

    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            LandscapeSettingsLayout.dialogBuilder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show()
            return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            LandscapeSettingsLayout.dialogBuilder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show()
            return
        }
        LandscapeSettingsLayout.dialogBuilder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val connectAfterSelection = pendingWireless
                pendingWireless = false
                render()
                if (connectAfterSelection) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != PAGE_WIRELESS_RECOVERY) return
        page = PAGE_HOME
        render()
        confirmWirelessReset()
    }

    private var wirelessReset: WirelessGroupReset? = null

    private fun confirmWirelessReset() {
        if (wirelessReset != null) return
        LandscapeSettingsLayout.dialogBuilder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ -> resetWirelessGroup() }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        if (wirelessReset != null || isFinishing || isDestroyed) return
        val phone = DiPlayPreferences.phoneAddress(this)
        val operation = WirelessGroupReset(this,
            allowed = { !isFinishing && !isDestroyed && phone == DiPlayPreferences.phoneAddress(this) &&
                !CarPlayBackgroundSession.hasSession() },
            complete = { success ->
                wirelessReset = null
                if (!isFinishing && !isDestroyed) {
                    if (success) connect(true)
                    else toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                }
            })
        wirelessReset = operation
        operation.start { done -> CarPlayBackgroundSession.stop(done) }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }

    private fun reportFileName() = "FAW-Play-${SimpleDateFormat(REPORT_TIMESTAMP_PATTERN, Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply {
            isEnabled = false
            text = getString(R.string.saving_report)
        }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildDiagnosticReport(appContext)
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply {
                    isEnabled = true
                    text = getString(R.string.save_diagnostic_report)
                }
                if (result.isFailure) {
                    showDiagnosticExportFailure()
                    return@runOnUiThread
                }
                val (savedReport, report) = result.getOrThrow()
                showDiagnosticExportSuccess(savedReport, report, fileName, uri)
            }
        }, "diplay-export").start()
    }

    private fun buildDiagnosticReport(appContext: Context): String = buildString {
        appendLine("Diagnostic flush complete=${AsyncDiagnosticLog.flush(2_000)}")
        appendLine(AsyncDiagnosticLog.summary())
        appendLine("FAW Play ${version()} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine()
        appendLine("--- Current cluster display diagnostics (even when disabled) ---")
        appendLine(ClusterMapPresentation.diagnosticReport(appContext))
        appendLine()
        appendLine("--- ADB cluster activity routing ---")
        appendLine("adbClusterActivityEnabled=${AirPlayPersistence.loadAdbClusterEnabled(appContext)}")
        appendLine("clusterActivityMainTask=${ClusterActivityOutput.mainTaskId} surfaceValid=${ClusterActivityOutput.surface?.isValid}")
        AdbClusterRouter.report(appContext).lineSequence().forEach { line ->
            DiagnosticRedactor.redact(line)?.let { appendLine(it) }
        }
        appendLine()
        appendLine("--- Standalone HUD compatibility ---")
        appendLine(FawOutputSettings.standaloneHudDiagnosticReport(appContext))
        appendLine()
        appendLine("--- BYD vehicle-data probe ---")
        appendLine(
            "mode=${if (FawOutputSettings.legacyVehicleProbe(appContext)) "legacy-probe" else "default"} " +
                "switches location=${AirPlayPersistence.loadLocationReportingEnabled(appContext)} " +
                "battery=${FawOutputSettings.batteryToIphone(appContext)} " +
                "wheelSpeed=${FawOutputSettings.wheelSpeedToIphone(appContext)} " +
                "parkedVideo=${FawOutputSettings.videoWhileParked(appContext)}",
        )
        val fawCapabilities = FawVehicleFieldStore.load(appContext)
        if (fawCapabilities == null) {
            appendLine("no saved successful probe")
        } else {
            appendLine(
                "catalog=${fawCapabilities.catalogAvailable} detectedAt=${fawCapabilities.detectedAtMillis} " +
                    "savedFirmware=${fawCapabilities.firmwareKey} " +
                    "currentFirmware=${FawVehicleFieldStore.firmwareKey()}",
            )
            for (field in FawVehicleField.entries) {
                val probe = fawCapabilities.result(field)
                appendLine("${field.name}: supported=${probe.supported} " +
                    (probe.address?.let { "tx=${it.transaction} dev=${it.device} fid=${it.fid} source=${it.source}" }
                        ?: "address=none"))
            }
        }
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
        appendLine()
        appendLine("--- Last received boot and app-launch result ---")
        appendLine(StartupDiagnosticSnapshot.report(appContext))
        appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
            "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
        appendLine()
        appendLine("--- Recent own-app process exits (Android 11+) ---")
        appendLine(ProcessExitDiagnostics.report(appContext))
        appendLine()
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(appContext.filesDir, "logs/$name")
            if (file.isFile) {
                appendLine("--- $name ---")
                file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
            }
        }
    }

    private fun showDiagnosticExportSuccess(
        savedReport: DiagnosticExportStore.SavedReport,
        report: String,
        fileName: String,
        requestedUri: Uri?,
    ) {
        LandscapeSettingsLayout.dialogBuilder(this).setTitle(getString(R.string.diagnostic_report_saved))
            .setMessage(when {
                savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                requestedUri == null -> "Downloads/FAW Play/$fileName"
                else -> getString(R.string.your_report_was_saved_to_the_selected_location)
            })
            .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
            .setNegativeButton(getString(R.string.done), null)
            .setNeutralButton(getString(R.string.share)) { _, _ ->
                shareDiagnosticReport(savedReport, report)
            }.show()
    }

    private fun shareDiagnosticReport(savedReport: DiagnosticExportStore.SavedReport, report: String) {
        runCatching {
            startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = REPORT_MIME_TYPE
                    putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.share_diagnostic_report)),
            )
        }.onFailure { showDiagnosticReport(report) }
    }

    private fun showDiagnosticExportFailure() {
        LandscapeSettingsLayout.dialogBuilder(this)
            .setTitle(getString(R.string.could_not_save_the_report))
            .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
            .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED_COLOR))
        body.addView(label(report, 13, TEXT_COLOR).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        LandscapeSettingsLayout.dialogBuilder(this).setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }

    private fun permissionHelp(title: String, body: String) {
        LandscapeSettingsLayout.dialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(getString(R.string.app_settings)) { _, _ ->
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(getString(R.string.later), null)
            .show()
    }

    private fun openSystem(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            toast(getString(R.string.open_this_setting_from_your_car_s_settings_app))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: FALLBACK_VERSION

    private fun section(
        parent: LinearLayout,
        title: String,
        icon: Int? = null,
        build: (LinearLayout) -> Unit,
    ) {
        val card = card()
        val heading = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        if (icon != null) {
            heading.addView(
                ImageView(this).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(ACCENT_COLOR)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) },
            )
        }
        heading.addView(label(title, 22, TEXT_COLOR, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(18) })
    }

    private fun toggle(
        parent: LinearLayout,
        title: String,
        description: String,
        value: Boolean,
        enabled: Boolean = true,
        save: (Boolean) -> Unit,
    ): Switch {
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val text = column().apply {
            addView(label(title, 18, TEXT_COLOR, true))
            addView(label(description, 14, MUTED_COLOR).apply { setPadding(0, dp(6), dp(16), 0) })
        }
        line.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val control = Switch(this).apply {
            contentDescription = title
            isChecked = value
            isEnabled = enabled
            minHeight = dp(56)
            buttonTintList = ColorStateList.valueOf(ACCENT_COLOR)
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }
        line.addView(control)
        parent.addView(line)
        return control
    }

    private fun card() = column().apply {
        background = rounded(SURFACE_COLOR, BORDER_COLOR)
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create(if (bold) MEDIUM_FONT else REGULAR_FONT, Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 18f
        setTextColor(if (primary) BACKGROUND_COLOR else TEXT_COLOR)
        typeface = Typeface.create(MEDIUM_FONT, Typeface.NORMAL)
        background = RippleDrawable(
            ColorStateList.valueOf(RIPPLE_COLOR),
            rounded(if (primary) ACCENT_COLOR else SURFACE_COLOR, if (primary) ACCENT_COLOR else BORDER_COLOR),
            null,
        )
        setPadding(dp(16), 0, dp(16), 0)
        minHeight = dp(56)
        stateListAnimator = null
        setOnClickListener { click() }
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(MATCH_PARENT, dp(height)).apply {
        topMargin = dp(top)
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_HOME = "home"
        private const val PAGE_SETTINGS = "settings"
        private const val PAGE_CONNECTION = "connection"
        private const val PAGE_ABOUT = "about"
        private const val PAGE_WIRELESS_RECOVERY = "wireless-recovery"
        private const val PAGE_KEY = "page"
        private const val STATE_PENDING_CAR_HOTSPOT = "pending_car_hotspot"
        private const val STATE_ADVANCED_VEHICLE_DATA = "byd_vehicle_advanced"
        private const val PREFERENCES_NAME = "diplay"
        private const val NOTIFICATION_ASKED_KEY = "notification_asked"
        private const val SETUP_LOG_TAG = "DiPlaySetup"
        private const val HOTSPOT_SETTINGS_ACTION = "com.android.settings.WIFI_TETHER_SETTINGS"
        private const val OEM_HOTSPOT_SETTINGS_PACKAGE = "com.byd.carsettings"
        private const val REPORT_MIME_TYPE = "text/plain"
        private const val REPORT_TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss-SSS"
        private const val FALLBACK_VERSION = "0.1.0-beta.1"
        private const val COMPACT_WIDTH_DP = 550
        private const val COMPACT_HEIGHT_DP = 450
        private const val STATUS_REFRESH_MILLIS = 1000L
        private const val RIPPLE_COLOR = 0x336F9FD9
        private const val MEDIUM_FONT = "sans-serif-medium"
        private const val REGULAR_FONT = "sans-serif"
        private val BACKGROUND_COLOR = Color.rgb(12, 17, 27)
        private val SURFACE_COLOR = Color.rgb(21, 30, 44)
        private val BORDER_COLOR = Color.rgb(42, 56, 75)
        private val ACCENT_COLOR = Color.rgb(166, 200, 255)
        private val TEXT_COLOR = Color.rgb(241, 245, 252)
        private val MUTED_COLOR = Color.rgb(168, 182, 202)
    }
}
