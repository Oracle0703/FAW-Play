package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HongqiInstrumentOutputTest {
    private lateinit var app: Context
    private lateinit var instrument: RecordingBinder
    private lateinit var hud: RecordingBinder
    private lateinit var control: RecordingBinder

    @Before fun prepareVehicleOutput() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        assertTrue(HongqiNavigation.supported())
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().clear().commit()
        instrument = RecordingBinder("ltd.qisi.adapterproxy.ICarInstrumentRelatedManager")
        hud = RecordingBinder("ltd.qisi.adapterproxy.ICarHUDManager")
        control = RecordingBinder("ltd.qisi.adapterproxy.ICarSystemControlManager")
        set("context", app)
        set("instrument", instrument)
        set("hud", hud)
        set("control", control)
        set("guidance", guidance())
        for (name in listOf("mapReady", "mapInterfacesReady", "instrumentGuidanceSupported")) set(name, true)
        for (name in listOf("ownsMap", "ownsHud", "replayMap", "cleanupPending",
            "ownsInstrumentGuidance", "replayInstrumentGuidance")) set(name, false)
        set("lastInstrumentGuidance", null)
    }

    @Test fun flyingMapSendsNativeGuidanceWithoutReplacingTheHudSequence() {
        apply()
        assertEquals(listOf(5, 10, 10, 6), instrument.events.map { it.first })
        assertEquals(listOf("71米", 1, "测试路", "12公里", "31分", 8, -1), instrument.guidance.single())
        assertEquals(listOf(69 to listOf(1, 0)), control.events)
        assertEquals(listOf(5 to listOf(8), 70 to listOf(1, 71), 6 to listOf(0)), hud.events)
    }

    @Test fun unchangedGuidanceDoesNotSpamTheInstrumentAndNewEstimatesUpdateInPlace() {
        apply()
        apply()
        assertEquals(1, instrument.guidance.size)
        set("guidance", guidance().copy(remainingSeconds = 1800, remainingMeters = 11000))
        apply()
        assertEquals(2, instrument.guidance.size)
        assertEquals(listOf("11公里", "30分"), instrument.guidance.last().subList(3, 5))
        assertEquals(1, control.events.size)
    }

    @Test fun instrumentGuidanceDoesNotWaitForTheMapStreamOrItsVisibility() {
        set("mapReady", false)
        apply()
        assertEquals(listOf(5, 6), instrument.events.map { it.first })
        assertTrue(control.events.isEmpty())
        set("mapReady", true)
        HongqiNavigation.setMapShown(app, false)
        set("guidance", guidance().copy(distanceMeters = 149))
        apply()
        assertEquals(listOf(5, 6, 6), instrument.events.map { it.first })
        assertEquals("149米", instrument.guidance.last()[0])
        assertTrue(control.events.isEmpty())
        assertFalse(hud.events.isEmpty())
    }

    @Test fun closingTheMapReplaysLiveNativeHintsAndKeepsHudNavigationAlive() {
        apply()
        instrument.events.clear()
        hud.events.clear()
        HongqiNavigation.setMapShown(app, false)
        apply()
        assertEquals(listOf(10, 5, 6), instrument.events.map { it.first })
        assertEquals(listOf("71米", 1, "测试路", "12公里", "31分", 8, -1), instrument.guidance.last())
        assertEquals(5 to listOf(1), instrument.events[1])
        assertEquals(69 to listOf(0, 0), control.events.last())
        assertEquals(5 to listOf(8), hud.events.first())
        assertFalse(get("ownsMap") as Boolean)
        assertTrue(get("ownsInstrumentGuidance") as Boolean)
        instrument.events.clear()
        HongqiNavigation.setMapShown(app, true)
        apply()
        assertEquals(listOf(10, 10), instrument.events.map { it.first })
        assertEquals("31分", instrument.guidance.last()[4])
        assertEquals(69 to listOf(1, 0), control.events.last())
    }

    @Test fun aRouteStartedWithTheMapClosedShowsHintsAndClearsThemOnceOnRouteEnd() {
        HongqiNavigation.setMapShown(app, false)
        apply()
        assertEquals(listOf(5, 6), instrument.events.map { it.first })
        assertTrue(control.events.isEmpty())
        assertFalse(get("ownsMap") as Boolean)
        assertTrue(get("ownsInstrumentGuidance") as Boolean)
        instrument.events.clear()
        set("guidance", null)
        apply()
        assertEquals(listOf(6, 5), instrument.events.map { it.first })
        assertEquals(listOf("", 0, "", "", "", 0, -1), instrument.guidance.last())
        assertEquals(5 to listOf(0), instrument.events.last())
        assertFalse(get("ownsInstrumentGuidance") as Boolean)
        assertFalse(get("cleanupPending") as Boolean)
        instrument.events.clear()
        apply()
        assertTrue(instrument.events.isEmpty())
    }

    @Test fun hintsRemainAvailableWhenHudAndMapAreBothOff() {
        HongqiNavigation.setMapShown(app, false)
        app.getSharedPreferences("diplay_byd_outputs", 0).edit()
            .putBoolean("navigation_enabled", false).commit()
        apply()
        assertEquals(listOf(5, 6), instrument.events.map { it.first })
        assertTrue(hud.events.isEmpty())
        assertTrue(control.events.isEmpty())
        assertTrue(get("cleanupPending") as Boolean)
    }

    @Test fun missingMapControlDoesNotBlockTheIndependentInstrumentInterface() {
        set("mapInterfacesReady", false)
        set("control", null)
        apply()
        assertEquals(listOf(5, 6), instrument.events.map { it.first })
        assertFalse(get("ownsMap") as Boolean)
        assertTrue(get("ownsInstrumentGuidance") as Boolean)
    }

    @Test fun rebindingWhileMapIsClosedReplaysStateAndDataWithoutEnablingTheMap() {
        HongqiNavigation.setMapShown(app, false)
        apply()
        val replacement = RecordingBinder("ltd.qisi.adapterproxy.ICarInstrumentRelatedManager")
        set("instrument", replacement)
        set("lastInstrumentGuidance", null)
        set("replayInstrumentGuidance", true)
        apply()
        assertEquals(listOf(5, 6), replacement.events.map { it.first })
        assertEquals("31分", replacement.guidance.single()[4])
        assertTrue(control.events.isEmpty())
    }

    @Test fun routeEndingDuringServiceLossRetainsOwnershipUntilReboundCleanup() {
        HongqiNavigation.setMapShown(app, false)
        app.getSharedPreferences("diplay_byd_outputs", 0).edit()
            .putBoolean("navigation_enabled", false).commit()
        apply()
        set("instrument", null)
        set("guidance", null)
        apply()
        assertTrue(get("ownsInstrumentGuidance") as Boolean)
        assertTrue(get("cleanupPending") as Boolean)
        val replacement = RecordingBinder("ltd.qisi.adapterproxy.ICarInstrumentRelatedManager")
        set("instrument", replacement)
        set("lastInstrumentGuidance", null)
        set("replayInstrumentGuidance", true)
        apply()
        assertEquals(listOf(6, 5), replacement.events.map { it.first })
        assertEquals(listOf("", 0, "", "", "", 0, -1), replacement.guidance.single())
        assertEquals(5 to listOf(0), replacement.events.last())
        assertFalse(get("ownsInstrumentGuidance") as Boolean)
        assertFalse(get("cleanupPending") as Boolean)
    }

    @Test fun endingOrDisconnectingTheRouteClearsBothOutputsOnlyOnce() {
        apply()
        set("guidance", null)
        apply()
        assertEquals(listOf("", 0, "", "", "", 0, -1), instrument.guidance.last())
        assertEquals(listOf(5 to listOf(0), 70 to listOf(0, 0), 6 to listOf(0)), hud.events.takeLast(3))
        val count = instrument.events.size
        apply()
        assertEquals(count, instrument.events.size)
    }

    @Test fun unsupportedOptionalGuidanceDoesNotTearDownTheMapOrHud() {
        instrument.rejectGuidance = true
        apply()
        assertTrue(get("ownsMap") as Boolean)
        assertTrue(get("ownsHud") as Boolean)
        assertFalse(get("instrumentGuidanceSupported") as Boolean)
        apply()
        assertEquals(1, instrument.events.count { it.first == 6 })
        assertEquals(1, control.events.size)
        HongqiNavigation.setMapShown(app, false)
        apply()
        assertFalse(get("ownsMap") as Boolean)
        assertEquals(5 to listOf(1), instrument.events.last())
    }

    @Test fun thePublicEntryPointCannotWriteVehicleDataOnOtherDevices() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Other")
        set("guidance", null)
        assertFalse(HongqiNavigation.supported())
        HongqiNavigation.onGuidance(guidance())
        assertEquals(null, get("guidance"))
        assertTrue(instrument.events.isEmpty())
    }

    private fun guidance() = ClusterTurnGuidance(
        icon = 8, roundaboutExit = 0, distanceMeters = 71, road = "测试路",
        remainingSeconds = 1906L, remainingMeters = 12000L,
    )

    private fun set(name: String, value: Any?) {
        HongqiNavigation::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
        if (name == "guidance") ReflectionHelpers.setStaticField(HongqiNavigation::class.java, "routeActive", value != null)
    }

    private fun get(name: String): Any? =
        HongqiNavigation::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null)

    private fun apply() {
        HongqiNavigation::class.java.getDeclaredMethod("apply").apply { isAccessible = true }.invoke(null)
    }

    private class RecordingBinder(private val descriptor: String) : Binder() {
        val events = mutableListOf<Pair<Int, List<Any?>>>()
        val guidance = mutableListOf<List<Any?>>()
        var rejectGuidance = false

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptor)
            val values: List<Any?> = when {
                descriptor.endsWith("ICarInstrumentRelatedManager") && code == 6 -> {
                    assertEquals(1, data.readInt())
                    readOemFields(data).also { guidance += it }
                }
                code == 10 -> List(3) { data.readInt() }
                code == 69 || code == 70 -> List(2) { data.readInt() }
                else -> listOf(data.readInt())
            }
            assertEquals("unexpected trailing data for $descriptor/$code", 0, data.dataAvail())
            events += code to values
            if (rejectGuidance && code == 6 && descriptor.endsWith("ICarInstrumentRelatedManager")) return false
            reply!!.writeNoException()
            reply.writeInt(0)
            return true
        }
    }
}
