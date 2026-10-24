package com.shilapi.xcertplay.licensing

import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.SystemClock
import com.shilapi.xcertplay.hud.AutunnNavigationOutputs
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.HongqiNavigation
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Run with -PautunnLicenseDebug=true: real feature readers and Android 9 output branches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LicenseNavigationContinuityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var memory: LicenseDecisionPublication
    private val releaseOwner = CountDownLatch(1)
    private var heldOwner: Future<*>? = null
    private val instrument = RecordingBinder("ltd.qisi.adapterproxy.ICarInstrumentRelatedManager")
    private val hud = RecordingBinder("ltd.qisi.adapterproxy.ICarHUDManager")
    private val control = RecordingBinder("ltd.qisi.adapterproxy.ICarSystemControlManager")
    private val streamRequests = mutableListOf<Boolean>()
    private val streamControl: (Boolean) -> Unit = { streamRequests += it }

    @Before fun prepare() {
        assumeTrue(ProductLicense.isRequired())
        // Block only local verification, as slow disk/Keystore work would. All feature reads,
        // stream visibility decisions and OEM commands below run through production code.
        val executor = ReflectionHelpers.getStaticField<ScheduledExecutorService>(ProductLicense::class.java, "localExecutor")
        val entered = CountDownLatch(1)
        heldOwner = executor.submit { entered.countDown(); check(releaseOwner.await(15, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        ReflectionHelpers.getStaticField<AtomicLong>(ProductLicense::class.java, "nextAutomaticRefresh").set(Long.MAX_VALUE)
        memory = ReflectionHelpers.getStaticField(ProductLicense::class.java, "memory")
        memory.invalidate()
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().clear().commit()
        set("context", app)
        set("instrument", instrument); set("hud", hud); set("control", control)
        set("guidance", ClusterTurnGuidance(8, 0, 71, "test road"))
        for (name in listOf("routeActive", "mapReady", "mapInterfacesReady", "instrumentGuidanceSupported")) set(name, true)
        for (name in listOf("ownsMap", "ownsHud", "ownsInstrumentGuidance", "replayMap", "replayInstrumentGuidance", "mapRecoveryExpired")) set(name, false)
        set("mapLoadStep", 0); set("lastInstrumentGuidance", null)
        ReflectionHelpers.setStaticField(AutunnNavigationOutputs::class.java, "outputContext", app)
        AutunnNavigationOutputs.setClusterMapShown(true)
    }

    @After fun finish() {
        AutunnNavigationOutputs.clearClusterStreamControl(streamControl)
        releaseOwner.countDown()
        heldOwner?.get(5, TimeUnit.SECONDS)
    }

    @Test fun delayedLocalVerificationDoesNotExitMapHideStreamOrClearHud() {
        grant()
        apply()
        AutunnNavigationOutputs.setClusterStreamControl(streamControl)
        assertTrue(get("ownsMap") as Boolean)
        assertTrue(get("ownsHud") as Boolean)
        instrument.commands.clear(); hud.commands.clear(); control.commands.clear()
        repeat(16) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(250))
            apply()
            AutunnNavigationOutputs.refreshClusterMapVisibility()
            LicenseFeatures.ALL.forEach { assertTrue(ProductLicense.featureAllowed(app, it)) }
        }
        assertTrue(get("ownsMap") as Boolean)
        assertTrue(get("ownsHud") as Boolean)
        assertFalse(instrument.commands.any { it == 10 to 0 })
        assertTrue(control.commands.isEmpty())
        assertFalse(hud.commands.any { it == 5 to 0 })
        assertEquals(listOf(true), streamRequests)
    }

    @Test fun actualExpiryStillClosesOutputsWhenTheVerifierIsBlocked() {
        grant(lifetimeSeconds = 2)
        apply()
        AutunnNavigationOutputs.setClusterStreamControl(streamControl)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        apply()
        AutunnNavigationOutputs.refreshClusterMapVisibility()
        assertFalse(get("ownsMap") as Boolean)
        assertFalse(get("ownsHud") as Boolean)
        assertTrue(instrument.commands.contains(10 to 0))
        assertTrue(control.commands.contains(69 to 0))
        assertTrue(hud.commands.contains(5 to 0))
        assertEquals(listOf(true, false), streamRequests)
    }

    @Test fun revocationRejectsAnOldCommitAndImmediatelyClosesOutputs() {
        grant()
        apply()
        val inFlight = memory.begin()
        memory.invalidate()
        assertFalse(memory.publish(inFlight, inFlight.snapshot!!))
        apply()
        assertFalse(get("ownsMap") as Boolean)
        assertFalse(get("ownsHud") as Boolean)
        LicenseFeatures.ALL.forEach { assertFalse(ProductLicense.featureAllowed(app, it)) }
    }

    @Test fun replacementCanRemoveMapPermissionWithoutRemovingHudPermission() {
        grant()
        apply()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        grant(features = setOf(LicenseFeatures.HUD))
        apply()
        assertFalse(get("ownsMap") as Boolean)
        assertTrue(get("ownsHud") as Boolean)
        assertFalse(ProductLicense.featureAllowed(app, LicenseFeatures.STEERING_WHEEL))
    }

    private fun grant(lifetimeSeconds: Long = 60, features: Set<String> = LicenseFeatures.ALL) {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val snapshot = LicenseDecisionSnapshot(
            LicenseDecision(true, "test verified grant", "LICENSE_VALID", wall / 1000 + lifetimeSeconds, features),
            elapsed, LicenseClockAnchor(wall / 1000, wall, elapsed, -1))
        assertTrue(memory.publish(memory.begin(), snapshot))
    }

    private fun set(name: String, value: Any?) = ReflectionHelpers.setStaticField(HongqiNavigation::class.java, name, value)
    private fun get(name: String): Any? = ReflectionHelpers.getStaticField(HongqiNavigation::class.java, name)
    private fun apply() = HongqiNavigation::class.java.getDeclaredMethod("apply").apply { isAccessible = true }.invoke(null)

    private class RecordingBinder(private val descriptor: String) : Binder() {
        val commands = mutableListOf<Pair<Int, Int>>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptor)
            if (code == 10) {
                assertEquals(1, data.readInt())
                assertEquals(0, data.readInt())
            }
            commands += code to data.readInt()
            reply!!.writeNoException()
            reply.writeInt(0)
            return true
        }
    }
}
