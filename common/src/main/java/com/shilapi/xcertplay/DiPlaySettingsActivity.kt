// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.licensing.ProductLicense
import com.shilapi.xcertplay.licensing.DiagnosticMetadata
import com.shilapi.xcertplay.licensing.LicenseApiException
import com.shilapi.xcertplay.hud.AutunnNavigationOutputs
import com.shilapi.xcertplay.hud.AutunnOutputSettings
import com.shilapi.xcertplay.hud.AutunnVehicleField
import com.shilapi.xcertplay.hud.AutunnVehicleFieldStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns explicit software/setup pages; the installed launcher routes through [DiPlayActivity]. */
class DiPlaySettingsActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var appearanceWatch: AutoCloseable? = null
    private var page = PAGE_HOME
    private var setupError: String? = null
    private var pendingWireless = false
    private var rejectAutomaticResume = false
    private var stoppedSinceResume = false
    private var initialLaunch = true
    private var exportInProgress = false
    private var activityResumed = false
    private var windowFocused = false
    private var pendingAutomaticConnection = false
    private val entryDispatcher = AutomaticEntryDispatcher(this, this, ::automaticEntryForeground)
    private val automaticConnectLaunch = Runnable {
        if (activityResumed) autoConnectOnOpen()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connectToPhone(pendingAutomaticConnection)
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
        if (!CarPlayBackgroundSession.acceptBackgroundEntry(intent)) {
            finish()
            return
        }
        languagePreferenceAtCreate = AppLocale.preference(this)
        AutunnNavigationOutputs.onAppOpened(applicationContext)
        window.statusBarColor = BACKGROUND_COLOR
        window.navigationBarColor = BACKGROUND_COLOR
        LandscapeSettingsLayout.fullscreen(this)
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            Log.e(SETUP_LOG_TAG, "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        page = if (isLauncherEntryIntent(intent) && CarPlayBackgroundSession.connectedSessionAvailable()) PAGE_HOME
            else savedInstanceState?.getString(PAGE_KEY) ?: intent.getStringExtra(PAGE_KEY) ?: PAGE_HOME
        if (!isLauncherEntryIntent(intent)) {
            initialLaunch = false
            if (!isInternalConnectionRecovery()) cancelAutomaticProjectionEntry("explicit-page")
        }
        if (resumeConnectedProjection()) return
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelAutomaticProjectionEntry("home-back")
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
        if (!CarPlayBackgroundSession.acceptBackgroundEntry(intent)) {
            // Android may resume an existing singleTask/singleTop even after rejecting its
            // stale intent. Restore its prior background position at the resume boundary.
            rejectAutomaticResume = stoppedSinceResume
            return
        }
        rejectAutomaticResume = false
        setIntent(intent)
        page = intent.getStringExtra(PAGE_KEY) ?: PAGE_HOME
        prepareAutoConnectLaunch(intent)
        if (resumeConnectedProjection()) return
        render()
        handleWirelessRecovery()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PAGE_KEY, page)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        if (resumeConnectedProjection()) return
        CenterMapOverlay.onDiPlayScreenShown()
        appearanceWatch = AutunnUiTheme.watch(this) {
            if (!isFinishing && !isDestroyed) render()
        }
    }

    override fun onStop() {
        stoppedSinceResume = true
        handler.removeCallbacks(automaticConnectLaunch)
        entryDispatcher.cancel()
        appearanceWatch?.close()
        appearanceWatch = null
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || rejectAutomaticResume) {
            rejectAutomaticResume = false
            if (!isFinishing) moveTaskToBack(true)
            return
        }
        // Android can restore a task with onResume alone, without a new launcher intent.
        // Decide before resume-time page refresh can draw home.
        if (resumeConnectedProjection()) return
        stoppedSinceResume = false
        activityResumed = true
        CarPlayBackgroundSession.watchAutomaticEntry(this, ::scheduleAutomaticProjectionEntry)
        ProductLicense.refreshAsync(this)
        AppUpdates.checkOnOpen(this)
        LandscapeSettingsLayout.fullscreen(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        // Refresh pairing and permission changes made in the system settings.
        if (!initialLaunch && (page == PAGE_HOME || page == PAGE_SETTINGS || page == PAGE_CONNECTION)) render()
        if (initialLaunch) {
            initialLaunch = false
            PermissionAccess.enableWifiOnLaunch(this)
            handler.removeCallbacks(automaticConnectLaunch)
            handler.post(automaticConnectLaunch)
        }
        CarPlayBackgroundSession.recordUiDiagnostic("home lifecycle=resume page=$page focused=$windowFocused")
        scheduleAutomaticProjectionEntry()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (windowFocused != hasFocus) {
            windowFocused = hasFocus
            CarPlayBackgroundSession.recordUiDiagnostic("home focus=$hasFocus page=$page resumed=$activityResumed")
        }
        if (hasFocus) {
            LandscapeSettingsLayout.fullscreen(this)
        }
        scheduleAutomaticProjectionEntry()
        val disconnectedAtNs = intent.getLongExtra("disconnected_at_ns", 0L)
        if (hasFocus && AutunnSettingsPage.isConnectionPage(this) && disconnectedAtNs > 0L) {
            val now = System.nanoTime()
            Log.i(SETUP_LOG_TAG, "CONNECTION_DIAGNOSTIC settings focused atNs=$now " +
                "afterDisconnectMs=${((now - disconnectedAtNs) / 1_000_000L).coerceAtLeast(0)}")
            intent.removeExtra("disconnected_at_ns")
        }
    }

    private fun prepareAutoConnectLaunch(intent: Intent) {
        // A singleTop launcher can reuse this activity instead of calling onCreate again.
        // Explicit settings/recovery intents and Back must keep the connection screen usable.
        // The factory launcher may use an explicit component intent without MAIN/LAUNCHER.
        initialLaunch = isLauncherEntryIntent(intent)
        if (!initialLaunch && !isInternalConnectionRecovery()) cancelAutomaticProjectionEntry("explicit-page")
    }

    private fun autoConnectOnOpen() {
        if (isFinishing || isDestroyed || setupError != null ||
            wirelessReset != null || page != PAGE_HOME || !isLauncherEntryIntent(intent) ||
            !DiPlayPreferences.autoConnect(this)) return
        val reopened = CarPlayBackgroundSession.reopenFromLauncher(this)
        if (!CarPlayBackgroundSession.automaticConnectAllowed()) {
            CarPlayBackgroundSession.recordUiDiagnostic("launcher automatic connection blocked reason=${CarPlayBackgroundSession.startBlockReason()}")
            // Parking blocks connection, not the monitor needed to observe the next use.
            // The request still preserves manual stop, termination and auto-connect policy.
            CarPlayBackgroundSession.requestAutomaticConnection(this)
            return
        }
        if (!reopened && CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.requestAutomaticEntry()
            scheduleAutomaticProjectionEntry()
            return
        }
        ConnectionModePolicy.enforce(this)
        // Keep first-run pairing manual; the saved selection is enough for existing installs.
        if (DiPlayPreferences.phoneAddress(this).isNullOrBlank()) return
        CarPlayBackgroundSession.requestAutomaticEntry()
        scheduleAutomaticProjectionEntry()
        connectToPhone(automatic = true)
    }

    override fun onPause() {
        activityResumed = false
        CarPlayBackgroundSession.stopAutomaticEntryWatch(this)
        windowFocused = false
        handler.removeCallbacks(automaticConnectLaunch)
        entryDispatcher.cancel()
        CarPlayBackgroundSession.recordUiDiagnostic("home lifecycle=pause page=$page")
        WheelKeyService.cancelLearning()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        windowFocused = false
        cancelAutomaticProjectionEntry("user-left-home")
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        CarPlayBackgroundSession.stopAutomaticEntryWatch(this)
        handler.removeCallbacks(automaticConnectLaunch)
        entryDispatcher.cancel()
        appearanceWatch?.close()
        appearanceWatch = null
        wirelessReset?.cancel()
        wirelessReset = null
        WheelKeyService.cancelLearning()
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        render()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        if (activityResumed && windowFocused && CarPlayBackgroundSession.connectedSessionAvailable()) {
            // A submitted automatic handoff already owns the transition and its retry policy.
            if (CarPlayBackgroundSession.backgroundEntryFollowUp() != null || resumeConnectedProjection()) return
        }
        if (page != PAGE_HOME && !isInternalConnectionRecovery()) cancelAutomaticProjectionEntry("page-$page")
        WheelKeyService.cancelLearning()
        AutunnSystemParameters.enforce(this)
        when (page) {
            PAGE_SETTINGS, PAGE_CONNECTION, PAGE_ABOUT, PAGE_AUTHORIZATION -> AutunnSettingsPage.show(this, page)
            else -> {
                AutunnSettingsPage.showHome(this)
                if (page == PAGE_HOME) PermissionAccess.ensure(this)
            }
        }
    }

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(true)
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
        if (CarPlayBackgroundSession.hasSession()) connect(true)
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    // Keep the renderer's reflected Boolean signature; this HS5 edition uses Wi-Fi Direct.
    private fun connect(@Suppress("UNUSED_PARAMETER") requestedWireless: Boolean) {
        cancelAutomaticProjectionEntry("manual-connect")
        connectToPhone()
    }

    private fun connectToPhone() = connectToPhone(automatic = false)

    private fun connectToPhone(automatic: Boolean) {
        if (automatic && !CarPlayBackgroundSession.automaticEntryPending()) return
        pendingAutomaticConnection = automatic
        wirelessReset?.cancel()
        wirelessReset = null
        setupError?.let {
            toast(it)
            return
        }
        ConnectionModePolicy.enforce(this)
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
        val open = open@{
            if (automatic && !CarPlayBackgroundSession.automaticEntryPending()) return@open
            AirPlayPersistence.saveWirelessEnabled(this, true)
            if (automatic) {
                if (!CarPlayBackgroundSession.requestAutomaticConnection(this)) return@open
                CarPlayBackgroundSession.resumeService(this)
                try {
                    startForegroundService(Intent(this, DiPlaySessionService::class.java))
                } catch (error: RuntimeException) {
                    CarPlayBackgroundSession.recordUiDiagnostic("automatic connection service unavailable=${error.javaClass.simpleName}; manual entry available")
                }
                scheduleAutomaticProjectionEntry()
                return@open
            } else {
                CarPlayBackgroundSession.requestConnection(this)
            }
            launchProjection()
        }
        if (!automatic && CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        } else open()
    }

    private fun openProjection() {
        cancelAutomaticProjectionEntry("manual-open")
        CarPlayBackgroundSession.requestConnection(this)
        launchProjection()
    }

    private fun launchProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java)
            .putExtra(INTERNAL_HOST_ENTRY, true)
            .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_NO_ANIMATION,
        ))
    }

    /** A live connection replaces launcher/recovery home, including stale disconnected intents. */
    private fun resumeConnectedProjection(): Boolean {
        val connectionHome = page == PAGE_HOME && isLauncherEntryIntent(intent) || isInternalConnectionRecovery()
        if (isFinishing || isDestroyed || !connectionHome || setupError != null ||
            wirelessReset != null || !CarPlayBackgroundSession.connectedSessionAvailable()) return false
        return try {
            launchProjection()
            cancelAutomaticProjectionEntry("launcher-connected-resume")
            CarPlayBackgroundSession.recordUiDiagnostic("launcher resumed current CarPlay before home render")
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            true
        } catch (error: RuntimeException) {
            CarPlayBackgroundSession.recordUiDiagnostic("launcher resume failed=${error.javaClass.simpleName}")
            false
        }
    }

    private fun cancelAutomaticProjectionEntry(reason: String) {
        handler.removeCallbacks(automaticConnectLaunch)
        entryDispatcher.cancel()
        CarPlayBackgroundSession.cancelAutomaticEntry(reason)
    }

    private fun isInternalConnectionRecovery(): Boolean =
        intent.getBooleanExtra("internal_connection_recovery", false) &&
            intent.getStringExtra(PAGE_KEY) == PAGE_CONNECTION &&
            (page == PAGE_CONNECTION || page == PAGE_SETTINGS && AutunnSettingsPage.isConnectionPage(this))

    /** A desktop launch may carry page=home; only the app's own marker means explicit software UI. */
    private fun isLauncherEntryIntent(value: Intent): Boolean =
        value.getStringExtra(PAGE_KEY) == null ||
            value.getStringExtra(PAGE_KEY) == PAGE_HOME &&
            !value.getBooleanExtra(EXPLICIT_SOFTWARE_PAGE, false)

    private fun automaticEntryForeground(): Boolean = activityResumed && windowFocused &&
        !isFinishing && !isDestroyed && (page == PAGE_HOME &&
        window.decorView.findViewWithTag<android.view.View>("autunn_home_root")?.isShown == true ||
        isInternalConnectionRecovery() &&
        window.decorView.findViewWithTag<android.view.View>("autunn_settings_root")?.isShown == true)

    private fun scheduleAutomaticProjectionEntry() {
        if (automaticEntryForeground() && CarPlayBackgroundSession.backgroundEntryFollowUp() == null &&
            resumeConnectedProjection()) return
        entryDispatcher.schedule()
    }

    private fun checkAutomaticProjectionEntry() = entryDispatcher.dispatch()

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
        val devices = runCatching {
            com.shilapi.xcertplay.transport.IphoneBluetoothDevices.bonded(adapter).sortedBy { it.name ?: "" }
        }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            LandscapeSettingsLayout.dialogBuilder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).create().also { dialog ->
                    dialog.show()
                    dialog.window?.decorView?.let { view ->
                        IphoneChoicesMonitor.watch(view, Runnable {
                            if (dialog.isShowing) { dialog.dismiss(); choosePhone() }
                        })
                    }
                }
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
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.create().also { dialog ->
                dialog.show()
                dialog.window?.decorView?.let { view ->
                    IphoneChoicesMonitor.watch(view, Runnable {
                        if (dialog.isShowing) { dialog.dismiss(); choosePhone() }
                    })
                }
            }
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

    private fun reportFileName() = "Autunn-Play-${SimpleDateFormat(REPORT_TIMESTAMP_PATTERN, Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
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
                if (result.isFailure) {
                    showDiagnosticExportFailure()
                    return@runOnUiThread
                }
                val (savedReport, report) = result.getOrThrow()
                showDiagnosticExportSuccess(savedReport, report, fileName, uri)
            }
        }, "diplay-export").start()
    }

    private fun confirmDiagnosticUpload() {
        LandscapeSettingsLayout.dialogBuilder(this).setTitle("上传诊断报告")
            .setMessage("上传 App 版本、车机型号／固件及连接、画面、音频和已读取的车辆状态，供管理员排查问题。报告会脱敏；不会上传卡密、密钥或手机名称。只在你点击上传时发送。")
            .setPositiveButton("上传") { _, _ -> uploadDiagnostics() }.setNegativeButton("取消", null).show()
    }

    private fun uploadDiagnostics() {
        if (!diagnosticUploadInProgress.compareAndSet(false, true)) {
            Toast.makeText(this, "诊断报告正在上传，请稍候", Toast.LENGTH_SHORT).show(); return
        }
        val app = applicationContext
        Toast.makeText(this, "正在后台整理并上传诊断报告…", Toast.LENGTH_LONG).show()
        Thread({
            val result = runCatching {
                val report = RemoteDiagnosticReport.prepare(buildDiagnosticReport(app, newestFirst = true))
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                val metadata = DiagnosticMetadata(RemoteDiagnosticReport.metadataText(info.versionName ?: "unknown", 80),
                    info.longVersionCode, RemoteDiagnosticReport.metadataText(Build.MANUFACTURER, 80),
                    RemoteDiagnosticReport.metadataText(Build.MODEL, 100), RemoteDiagnosticReport.metadataText(Build.DISPLAY, 200),
                    RemoteDiagnosticReport.metadataText(Build.VERSION.RELEASE, 40), Build.VERSION.SDK_INT, report.truncated)
                ProductLicense.uploadDiagnostic(app, metadata, report.text)
            }
            diagnosticUploadInProgress.set(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val receipt = result.getOrNull()
                if (receipt != null) {
                    val message = "报告编号：${receipt.reportId}\n设备编号：${receipt.deviceFingerprint}\n请把报告编号发给管理员。" +
                        if (receipt.truncated) "\n本次保留了最新日志，较早历史可通过本地保存获取。" else ""
                    LandscapeSettingsLayout.dialogBuilder(this).setTitle("诊断报告已上传").setMessage(message)
                        .setPositiveButton("复制编号") { _, _ ->
                            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("诊断报告编号", receipt.reportId))
                            Toast.makeText(this, "已复制报告编号", Toast.LENGTH_SHORT).show()
                        }.setNegativeButton("完成", null).show()
                } else {
                    val error = result.exceptionOrNull()
                    val message = if (error is LicenseApiException) when {
                        error.status == 404 -> "服务器尚未开通诊断上传，请联系管理员更新服务器。"
                        error.status == 413 -> "报告超过上传限制，请保存到本地后联系管理员。"
                        error.status == 429 -> "上传次数已达限制，请稍后再试。"
                        error.code == "NOT_CONFIGURED" -> error.message
                        else -> "服务器未完成上传（${error.status}），请稍后重试。"
                    } else "无法完成上传，请检查网络后重试；也可以保存诊断报告。"
                    LandscapeSettingsLayout.dialogBuilder(this).setTitle("诊断报告未上传")
                        .setMessage(message).setPositiveButton("重试") { _, _ -> uploadDiagnostics() }
                        .setNegativeButton("取消", null).setNeutralButton("保存到本地") { _, _ -> exportDiagnostics() }.show()
                }
            }
        }, "diplay-diagnostic-upload").start()
    }

    private fun buildDiagnosticReport(appContext: Context, newestFirst: Boolean = false): String = buildString {
        appendLine("Diagnostic flush complete=${AsyncDiagnosticLog.flush(2_000)}")
        appendLine(AsyncDiagnosticLog.summary())
        appendLine("Autunn Play ${version()} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        appendLine("Session channel facts (not OEM pixel confirmation): ${CarPlayBackgroundSession.channelReadiness()}")
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine()
        appendLine("--- Application update installer diagnostics ---")
        appendLine(AutunnVendorInstaller.diagnosticReport(appContext))
        appendLine()
        appendLine("--- Current cluster display diagnostics (even when disabled) ---")
        appendLine(ClusterMapPresentation.diagnosticReport(appContext))
        appendLine()
        appendLine("--- Hongqi read-only vehicle data (cached application UID readings) ---")
        appendLine(com.shilapi.xcertplay.hud.HongqiVehicleData.diagnosticReport())
        appendLine()
        appendLine("--- ADB cluster activity routing ---")
        appendLine("adbClusterActivityEnabled=${AirPlayPersistence.loadAdbClusterEnabled(appContext)}")
        appendLine("clusterActivityMainTask=${ClusterActivityOutput.mainTaskId} surfaceValid=${ClusterActivityOutput.surface?.isValid}")
        AdbClusterRouter.report(appContext).lineSequence().forEach { line ->
            DiagnosticRedactor.redact(line)?.let { appendLine(it) }
        }
        appendLine()
        appendLine("--- Standalone HUD compatibility ---")
        appendLine(AutunnOutputSettings.standaloneHudDiagnosticReport(appContext))
        appendLine()
        appendLine("--- BYD vehicle-data probe ---")
        appendLine(
            "mode=${if (AutunnOutputSettings.legacyVehicleProbe(appContext)) "legacy-probe" else "default"} " +
                "switches location=${AirPlayPersistence.loadLocationReportingEnabled(appContext)} " +
                "battery=${AutunnOutputSettings.batteryToIphone(appContext)} " +
                "wheelSpeed=${AutunnOutputSettings.wheelSpeedToIphone(appContext)} " +
                "parkedVideo=${AutunnOutputSettings.videoWhileParkedActive(appContext)}",
        )
        val autunnCapabilities = AutunnVehicleFieldStore.load(appContext)
        if (autunnCapabilities == null) {
            appendLine("no saved successful probe")
        } else {
            appendLine(
                "catalog=${autunnCapabilities.catalogAvailable} detectedAt=${autunnCapabilities.detectedAtMillis} " +
                    "savedFirmware=${autunnCapabilities.firmwareKey} " +
                    "currentFirmware=${AutunnVehicleFieldStore.firmwareKey()}",
            )
            for (field in AutunnVehicleField.entries) {
                val probe = autunnCapabilities.result(field)
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
        for (name in if (newestFirst) SessionLogFile.REPORT_NAMES.reversed() else SessionLogFile.REPORT_NAMES) {
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
                requestedUri == null -> "Downloads/Autunn Play/$fileName"
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

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun label(value: String, size: Int, color: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create(REGULAR_FONT, Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val diagnosticUploadInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val PAGE_HOME = "home"
        private const val PAGE_SETTINGS = "settings"
        private const val PAGE_CONNECTION = "connection"
        private const val PAGE_ABOUT = "about"
        private const val PAGE_AUTHORIZATION = "authorization"
        private const val PAGE_WIRELESS_RECOVERY = "wireless-recovery"
        private const val PAGE_KEY = "page"
        private const val PREFERENCES_NAME = "diplay"
        private const val NOTIFICATION_ASKED_KEY = "notification_asked"
        private const val SETUP_LOG_TAG = "DiPlaySetup"
        private const val REPORT_MIME_TYPE = "text/plain"
        private const val REPORT_TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss-SSS"
        private const val FALLBACK_VERSION = "0.1.0-beta.1"
        private const val REGULAR_FONT = "sans-serif"
        private val BACKGROUND_COLOR = Color.rgb(12, 17, 27)
        private val TEXT_COLOR = Color.rgb(241, 245, 252)
        private val MUTED_COLOR = Color.rgb(168, 182, 202)
    }
}
