package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import org.junit.Assert.*
import org.junit.Test

class Iap2LaneDiagnosticsTest {
    @Test fun subscriptionTraceShowsActualComponentWithoutChangingWireEncoding() {
        val subscription = Iap2ControlMessages.subscriptions().single { it.messageId == 0x5200 }
        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.TX, "wireless-tunnel", subscription)
        assertTrue(formatted.contains("RouteGuidanceDisplayComponentIdentifier: u16=42"))
        assertArrayEquals(byteArrayOf(0, 6, 0, 0, 0, 42, 0, 4, 0, 1, 0, 4, 0, 2), subscription.payload)
    }

    @Test fun laneSelectionScalarsAreVisibleWhileRoadNamesAndUnknownFieldsRemainSuppressed() {
        val frame = Iap2Messages.buildRaw(0x5201) {
            u16(0, 42); u8(1, 1); string(3, "private-road-name")
            u16(16, 7); u16(17, 3); u8(18, 1); string(99, "private-future-field")
        }
        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.RX, "wireless-tunnel", frame)
        assertTrue(formatted.contains("CurrentLaneGuidanceIndex: u16=7"))
        assertTrue(formatted.contains("PresentLaneGuidance: u8=1"))
        assertFalse(formatted.contains("private-road-name"))
        assertFalse(formatted.contains("private-future-field"))
        assertTrue(formatted.contains("raw-body=<suppressed"))
    }

    @Test fun laneDetailsExposeOnlyLaneScalarsAndKeepDescriptionPrivate() {
        val frame = Iap2Messages.buildRaw(0x5204) {
            u16(0, 42); u16(1, 7)
            group(2) { u16(0, 0); u8(1, 2); i16(2, -90); i16(3, 0) }
            string(3, "private-lane-description")
        }
        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.RX, "wireless-tunnel", frame)
        assertTrue(formatted.contains("LaneGuidanceIndex: u16=7"))
        assertTrue(formatted.contains("LaneStatus: u8=2"))
        assertTrue(formatted.contains("LaneAngle: i16=-90"))
        assertFalse(formatted.contains("private-lane-description"))
    }

    @Test fun malformedKnownScalarDoesNotPrintItsBytesOrBreakFormatting() {
        val frame = Iap2Messages.buildRaw(0x5201) { bytes(16, "sensitive".encodeToByteArray()) }
        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.RX, "wireless-tunnel", frame)
        assertTrue(formatted.contains("CurrentLaneGuidanceIndex: bytes=9 <suppressed>"))
        assertFalse(formatted.contains("sensitive"))
    }
}
