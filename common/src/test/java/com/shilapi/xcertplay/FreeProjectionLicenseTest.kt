package com.shilapi.xcertplay

import android.os.Build
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.HongqiNavigation
import com.shilapi.xcertplay.licensing.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.Socket

/** Run with -PfawLicenseDebug=true and no license configuration, so every premium check denies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FreeProjectionLicenseTest {
    @Test fun unactivatedProjectionAcceptsConnectionsWhileKeysAndInstrumentStayLocked() {
        assumeTrue(ProductLicense.isRequired())
        val app = RuntimeEnvironment.getApplication()
        assertFalse(ProductLicense.check(app).allowed)
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val controller = CarPlayController(app,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        val session = AirPlaySession(Socket(), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        try {
            val accepts = controller.javaClass.getDeclaredMethod("authorizeNewConnection").apply { isAccessible = true }
            assertTrue(accepts.invoke(controller) as Boolean)
            val listener = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")
            assertTrue(listener.authorizeSession(session))
            assertFalse(SteeringWheelControls.ready(ConnectedController(app)))
            assertTrue(SteeringWheelControls.connectionReady(ConnectedController(app)))
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
            ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
            assertTrue(HongqiNavigation.supported())
            assertFalse(HongqiNavigation.mapShown(app))
            LicenseFeatures.ALL.forEach { assertFalse(ProductLicense.featureAllowed(app, it)) }
            controller.close()
            assertFalse(accepts.invoke(controller) as Boolean)
            assertFalse(listener.authorizeSession(session))
        } finally { session.close(); controller.close(); controller.awaitClosed(2000) }
    }
    class ConnectedController(@JvmField val appContext: android.content.Context) {
        @JvmField val activeSession = Any()
        fun isClosed() = false
        fun activeAirPlaySessionToken(): Any = activeSession
    }
}
