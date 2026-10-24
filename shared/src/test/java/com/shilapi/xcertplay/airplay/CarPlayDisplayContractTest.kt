package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Protocol regression: WWDC 2023 / 10150, 02:46, main-display-only safe-area flag. */
class CarPlayDisplayContractTest {
    private fun serializedDisplays(): List<Map<*, *>> {
        val config = AirPlayConfig(
            deviceName = "Audit receiver",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(1280, 720, safeAreaDrawOutside = true),
            cluster = CarPlayClusterDisplay.config(
                widthPixels = 1920,
                heightPixels = 720,
                baseSafeArea = CarPlayClusterDisplay.HONGQI_SAFE_AREA_PERCENT,
            ),
        )
        val info = BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(config))) as Map<*, *>
        return (info["displays"] as List<*>).map { it as Map<*, *> }
    }

    private fun safeArea(display: Map<*, *>): Map<*, *> =
        ((display["viewAreas"] as List<*>).single() as Map<*, *>)["safeArea"] as Map<*, *>

    @Test fun mainDisplayMayEnableDrawingOutsideSafeArea() {
        val main = serializedDisplays().single { it["uuid"] == AirPlayInfoPlist.MAIN_UUID }
        assertEquals(true, safeArea(main)["drawUIOutsideSafeArea"])
    }

    @Test fun clusterMustNotEnableMainDisplayOnlySafeAreaFlag() {
        val cluster = serializedDisplays().single { it["uuid"] == AirPlayInfoPlist.ALT_UUID }
        assertEquals(1600L, (cluster["widthPixels"] as Number).toLong())
        assertEquals(600L, (cluster["heightPixels"] as Number).toLong())
        assertFalse("Secondary display enables the main-display-only drawUIOutsideSafeArea flag",
            safeArea(cluster).containsKey("drawUIOutsideSafeArea"))
    }
}
