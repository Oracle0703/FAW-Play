package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.Surface
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** Uses the merged manifest so the installed launcher's task and theme are part of the contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlayLauncherRoutingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val closer = PausedExecutorService()
    private lateinit var previousCloser: ExecutorService
    private lateinit var stateIoScope: SessionStateIoTestScope
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private val entries = mutableListOf<ActivityController<DiPlayActivity>>()
    private var stops = 0

    @Before fun setUp() {
        stateIoScope = SessionStateIoTestScope()
        previousCloser = field("closer")
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "closer", closer)
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        closer.runAll()
        main.idleFor(Duration.ofMillis(250))
        CarPlayBackgroundSession.requestConnection(app)
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        doAnswer { invocation ->
            invocation.getArgument<(CarPlayController.CloseResult) -> Unit>(0)(CarPlayController.CloseResult(true))
            null
        }.`when`(controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        CarPlayBackgroundSession.store(controller, sink, 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) { completed ->
            stops++
            completed()
        }
        assertNull("The launcher must not inherit another fixture's resource barrier",
            CarPlayBackgroundSession.startBlockReason())
    }

    @After fun tearDown() {
        try {
            entries.forEach { it.destroy() }
            CarPlayBackgroundSession.clear()
            CarPlayBackgroundSession.terminate()
            closer.runAll()
            main.idleFor(Duration.ofMillis(250))
            assertTrue("Finish the real close acknowledgement before replacing the test looper",
                field<MutableList<*>>("retiring").isEmpty())
            assertFalse(field<Boolean>("stopping"))
            field<android.os.Handler>("main").removeCallbacksAndMessages(null)
        } finally {
            ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "closer", previousCloser)
            closer.shutdownNow()
            stateIoScope.close()
        }
    }

    @Test fun aReadyLauncherRoutesDirectlyToTheExistingPhoneEvenWhenAutomaticConnectionIsDisabled() {
        ready()
        DiPlayPreferences.saveAutoConnect(app, false)
        CarPlayBackgroundSession.requestAutomaticEntry()

        val entry = launch()

        val opened = assertTarget(entry, CarPlayHostActivity::class.java)
        assertHostSeesLauncherEntry(opened)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        assertSessionUntouched()
    }

    @Test fun theFactoryLaunchersExplicitComponentAlsoRoutesDirectlyToAReadyPhone() {
        ready()
        val entry = launch(Intent(app, DiPlayActivity::class.java))
        assertHostSeesLauncherEntry(assertTarget(entry, CarPlayHostActivity::class.java))
        assertSessionUntouched()
    }

    @Test fun aLauncherHomePageExtraStillMeansTheVehicleLauncherEntry() {
        ready()
        val entry = launch(launcherIntent().putExtra("page", "home"))
        val opened = assertTarget(entry, CarPlayHostActivity::class.java)
        assertHostSeesLauncherEntry(opened)
        assertEquals("home", opened.getStringExtra("page"))
        assertSessionUntouched()
    }

    @Test fun withoutALivePhoneTheLauncherOpensSoftware() {
        val entry = launch()
        assertTarget(entry, DiPlaySettingsActivity::class.java)
        assertSessionUntouched()
    }

    @Test fun aLivePhoneWithItsMainVideoWithdrawnCanBeRestoredManuallyWithoutAutomaticEntry() {
        `when`(controller.currentActiveAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))
        CarPlayBackgroundSession.cancelAutomaticEntry("phone-requested-car-ui")
        assertNull(controller.activeAirPlaySessionToken())
        assertFalse(CarPlayBackgroundSession.automaticEntryReady())
        assertTarget(launch(), CarPlayHostActivity::class.java)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertSessionUntouched()
    }

    @Test fun aPrimaryTokenAloneUsesTheSameConnectedBoundaryForLauncherAndSoftwareResume() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        assertNull(controller.currentActiveAirPlaySession())
        assertTrue(CarPlayBackgroundSession.connectedSessionAvailable())
        assertHostSeesLauncherEntry(assertTarget(launch(), CarPlayHostActivity::class.java))
        assertSessionUntouched()
    }

    @Test fun aFirstInstallationWithoutASessionKeepsConfigurationAccessible() {
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().remove("phone_address").remove("phone_name").commit()
        val entry = launch()
        assertTarget(entry, DiPlaySettingsActivity::class.java)
        assertSessionUntouched()
    }

    @Test fun explicitSoftwareAndWirelessRecoveryPagesRemainAccessibleWithAReadyPhone() {
        ready()
        for (page in listOf("home", "settings", "connection", "about", "authorization", "diagnostics", "wireless-recovery")) {
            val entryIntent = launcherIntent().putExtra("page", page).apply {
                if (page == "home") putExtra(EXPLICIT_SOFTWARE_PAGE, true)
            }
            val entry = launch(entryIntent)
            val opened = assertTarget(entry, DiPlaySettingsActivity::class.java)
            assertEquals(page, opened.getStringExtra("page"))
        }
        assertSessionUntouched()
    }

    @Test fun connectionRecoveryPreservesItsReasonAndAutomaticEntryRequest() {
        ready()
        CarPlayBackgroundSession.requestAutomaticEntry()
        val entry = launch(launcherIntent().putExtra("page", "connection")
            .putExtra("internal_connection_recovery", true).putExtra("disconnected_at_ns", 123L))

        val opened = assertTarget(entry, DiPlaySettingsActivity::class.java)
        assertTrue(opened.getBooleanExtra("internal_connection_recovery", false))
        assertEquals(123L, opened.getLongExtra("disconnected_at_ns", 0L))
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSessionUntouched()
    }

    @Test fun manualStopParkingAndTerminationRejectAStalePrimaryToken() {
        ready()
        for (name in listOf("manualDisconnect", "ignitionOff", "terminated", "stopping")) {
            val previous = field<Boolean>(name)
            ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, true)
            try {
                val entry = launch()
                assertTarget(entry, DiPlaySettingsActivity::class.java)
                assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
            } finally {
                ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, previous)
            }
        }
        assertSessionUntouched()
    }

    @Test fun anUnfinishedResourceCloseCannotRestoreAStalePrimaryToken() {
        ready()
        CarPlayBackgroundSession.retire(controller, sink, "launcher-test-pending-close")

        val entry = launch()

        assertTarget(entry, DiPlaySettingsActivity::class.java)
        assertSessionUntouched()
        // The test owns the same close as production; do not delete its admission barrier.
        closer.runAll()
        main.idle()
        assertTrue(field<MutableList<*>>("retiring").isEmpty())
    }

    @Test @Config(shadows = [LaunchFailure::class])
    fun aRejectedHostLaunchFallsBackToSoftwareAndPreservesTheConnection() {
        ready()
        CarPlayBackgroundSession.requestAutomaticEntry()
        val entry = newEntry()
        val launch = Shadow.extract<LaunchFailure>(entry.get())
        launch.rejectHost = true

        entry.create()

        assertEquals(1, launch.hostAttempts)
        assertTarget(entry.get(), DiPlaySettingsActivity::class.java)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSessionUntouched()
    }

    @Test @Config(shadows = [LaunchFailure::class])
    fun evenARejectedSoftwareFallbackFinishesTheInvisibleLauncherWithoutClosingThePhone() {
        ready()
        val entry = newEntry()
        val launch = Shadow.extract<LaunchFailure>(entry.get())
        launch.rejectHost = true
        launch.rejectSoftware = true

        entry.create()

        assertEquals(1, launch.hostAttempts)
        assertEquals(1, launch.softwareAttempts)
        assertTrue(entry.get().isFinishing)
        assertNull(shadowOf(entry.get()).nextStartedActivity)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSessionUntouched()
    }

    @Test fun aStaleQueuedAutomaticEntryCannotOpenEitherWindow() {
        ready()
        val entry = launch(launcherIntent().putExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, Long.MAX_VALUE))
        assertTrue(entry.isFinishing)
        assertNull(shadowOf(entry).nextStartedActivity)
        assertSessionUntouched()
    }

    @Test fun theInstalledLauncherIsAnInvisibleRouterAndCannotResumeTheSettingsTask() {
        val packageManager = app.packageManager
        val entry = packageManager.getActivityInfo(ComponentName(app, DiPlayActivity::class.java), 0)
        val settings = packageManager.getActivityInfo(ComponentName(app, DiPlaySettingsActivity::class.java), 0)
        val host = packageManager.getActivityInfo(ComponentName(app, CarPlayHostActivity::class.java), 0)
        assertTrue(entry.exported)
        assertFalse(settings.exported)
        assertNotEquals("The icon must not share the Host task, otherwise a launcher click is " +
            "resolved straight into the retained CarPlay task and this router never decides",
            host.taskAffinity, entry.taskAffinity)
        assertEquals("The installed icon must use the no-display router theme",
            app.resources.getIdentifier("Theme.AutunnEntry", "style", app.packageName), entry.theme)
        assertNotEquals("The settings task must never be selected as the launcher task",
            settings.taskAffinity, entry.taskAffinity)
    }

    /**
     * Exactly one launcher entry may exist, and it must be the router. A stale
     * `activity-alias` left over from the direct-to-Host generation would silently restore a
     * second, marker-less path into the Host and reintroduce the software-page flash.
     */
    @Test fun onlyTheInvisibleRouterCanBeStartedFromTheLauncher() {
        val resolvers = app.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        val names = resolvers.map { it.activityInfo.name }.toSet()
        assertEquals("The installed icon must resolve to exactly one component", 1, names.size)
        assertTrue("The launcher must resolve to the router, not the Host",
            DiPlayActivity::class.java.name in names)
        assertFalse("The Host must never be launchable from the launcher",
            CarPlayHostActivity::class.java.name in names)
        assertFalse("The software page must never be launchable from the launcher",
            DiPlaySettingsActivity::class.java.name in names)
    }

    private fun ready() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(controller.currentActiveAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))
    }

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun newEntry(intent: Intent = launcherIntent()): ActivityController<DiPlayActivity> =
        Robolectric.buildActivity(DiPlayActivity::class.java, intent).also(entries::add)

    private fun launch(intent: Intent = launcherIntent()): DiPlayActivity = newEntry(intent).create().get()

    private fun assertTarget(entry: DiPlayActivity, expected: Class<*>): Intent {
        val opened = shadowOf(entry).nextStartedActivity
        assertNotNull("A launcher request must have a usable destination", opened)
        assertEquals(expected.name, opened.component?.className)
        assertTrue("A destination must live outside the short-lived entry task", opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(entry.isFinishing)
        assertNull(entry.window.decorView.findViewWithTag<View>("autunn_home_root"))
        assertNull(entry.window.decorView.findViewWithTag<View>("autunn_settings_root"))
        assertNull("One launcher request must route only once", shadowOf(entry).nextStartedActivity)
        return opened
    }

    /**
     * A launcher handoff must still look like a launcher entry to the Host. A component-targeted
     * `Intent(context, CarPlayHostActivity::class)` loses `ACTION_MAIN`/`CATEGORY_LAUNCHER`, so
     * without the explicit marker the Host reveals its preparation cover and builds its own UI
     * instead of handing off to the retained CarPlay surface.
     */
    private fun assertHostSeesLauncherEntry(opened: Intent) {
        assertTrue("The Host intent must carry the launcher entry marker",
            opened.getBooleanExtra(LAUNCHER_ENTRY_MARKER, false))
        assertTrue("The Host must resolve this intent as a launcher entry",
            LauncherEntryRouting.isLauncherIntent(Intent(opened)))
        assertFalse("A launcher handoff must never arrive as an internal Host entry",
            opened.getBooleanExtra(INTERNAL_HOST_ENTRY, false))
    }

    private fun assertSessionUntouched() {
        assertEquals(0, stops)
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    private fun <T> field(name: String): T = ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, name)

    @Implements(Activity::class)
    class LaunchFailure : ShadowActivity() {
        var rejectHost = false
        var rejectSoftware = false
        var hostAttempts = 0
        var softwareAttempts = 0

        @Implementation protected fun startActivity(intent: Intent) {
            when (intent.component?.className) {
                CarPlayHostActivity::class.java.name -> {
                    hostAttempts++
                    if (rejectHost) throw SecurityException("Host launch rejected")
                }
                DiPlaySettingsActivity::class.java.name -> {
                    softwareAttempts++
                    if (rejectSoftware) throw SecurityException("Software launch rejected")
                }
            }
            Shadow.directlyOn<Void, Activity>(realActivity, Activity::class.java, "startActivity", from(Intent::class.java, intent))
        }
    }
}
