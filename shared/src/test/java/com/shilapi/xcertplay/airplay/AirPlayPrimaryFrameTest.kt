package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class AirPlayPrimaryFrameTest {
    @Test fun alternateFramesCannotCompletePrimaryStartupOrTheLegacyCallback() {
        val rendered = mutableListOf<Int>()
        var primary = 0
        val session = session(object : AirPlaySessionListener {
            override fun onVideoFrameRendered(session: AirPlaySession) { primary++ }
            override fun onVideoFrameRendered(session: AirPlaySession, screenType: Int) {
                rendered += screenType
                super.onVideoFrameRendered(session, screenType)
            }
        })
        try {
            mainToken(session, Any())
            session.videoFrameRendered(111)
            assertFalse(session.hasRenderedMainScreen())
            assertEquals(0, primary)
            session.videoFrameRendered(110)
            assertTrue(session.hasRenderedMainScreen())
            assertEquals(1, primary)
            assertEquals(listOf(111, 110), rendered)
        } finally { session.close() }
    }

    @Test fun aNewPrimarySetupInvalidatesTheOldFramesAndClosedSessionsCannotReportAgain() {
        var frames = 0
        val session = session(object : AirPlaySessionListener {
            override fun onVideoFrameRendered(session: AirPlaySession) { frames++ }
        })
        mainToken(session, Any())
        session.videoFrameRendered(110)
        assertTrue(session.hasRenderedMainScreen())
        mainToken(session, Any())
        assertFalse(session.hasRenderedMainScreen())
        session.videoFrameRendered(111)
        assertFalse(session.hasRenderedMainScreen())
        session.videoFrameRendered(110)
        assertTrue(session.hasRenderedMainScreen())
        session.close()
        session.videoFrameRendered(110)
        assertFalse(session.hasRenderedMainScreen())
        assertEquals(2, frames)
    }

    @Test fun startupEvidenceAloneDoesNotCountAsRecentMediaProgress() {
        val session = session(object : AirPlaySessionListener {})
        try {
            mainToken(session, Any())
            session.videoFrameRendered(110)
            assertFalse(session.hasRecentMediaProgress(10_000))
            session.mediaProgress()
            assertTrue(session.hasRecentMediaProgress(10_000))
            assertFalse(session.hasRecentMediaProgress(0))
        } finally { session.close() }
        assertFalse(session.hasRecentMediaProgress(10_000))
    }

    private fun mainToken(session: AirPlaySession, token: Any) {
        AirPlaySession::class.java.getDeclaredField("mainScreenToken").apply { isAccessible = true }.set(session, token)
    }

    private fun session(listener: AirPlaySessionListener) = AirPlaySession(
        Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null, listener, object : AirPlayMediaHandler {},
    )
}
