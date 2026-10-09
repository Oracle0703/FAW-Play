package com.shilapi.xcertplay.hud

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HongqiInstrumentGuidanceTest {
    @Test fun matchesTheCapturedOemSevenFieldParcelAndDuration() {
        val decoded = decode(HongqiInstrumentGuidance.from(guidance()))
        assertEquals(listOf("71米", 1, "测试路", "12公里", "31分", 8, -1), decoded)
    }

    @Test fun missingRouteEstimatesStayEmptyRatherThanBecomingAnArrivalClockOrZero() {
        val next = guidance().copy(
            arrivalEpochSeconds = 1_800_000_000L,
            remainingSeconds = null,
            remainingMeters = null,
        )
        assertEquals(listOf("71米", 1, "测试路", "", "", 8, -1), decode(HongqiInstrumentGuidance.from(next)))
    }

    @Test fun distanceUnitsAndImmediateInstructionFollowTheOemBoundaries() {
        val cases = listOf(
            Triple(14, "现在", 0), Triple(15, "15米", 1), Triple(999, "999米", 1),
            Triple(1000, "1公里", 2), Triple(1500, "1.5公里", 2), Triple(10000, "10公里", 3),
        )
        for ((meters, label, range) in cases) {
            val decoded = decode(HongqiInstrumentGuidance.from(guidance().copy(distanceMeters = meters)))
            assertEquals("distance at $meters m", label, decoded[0])
            assertEquals("range at $meters m", range, decoded[1])
        }
    }

    @Test fun durationSupportsHoursAndZeroWithoutUsingTheArrivalTimestamp() {
        val expected = mapOf(0L to "0分", 59L to "1分", 1906L to "31分", 3600L to "1小时", 7260L to "2小时1分")
        for ((seconds, label) in expected) {
            val next = guidance().copy(remainingSeconds = seconds)
            assertEquals(label, decode(HongqiInstrumentGuidance.from(next))[4])
        }
    }

    @Test fun invalidEstimatesDoNotProduceNegativeOrOverflowedLabels() {
        for (invalid in listOf(-1L, Long.MAX_VALUE)) {
            val next = guidance().copy(remainingSeconds = invalid, remainingMeters = invalid)
            assertEquals(listOf("", ""), decode(HongqiInstrumentGuidance.from(next)).subList(3, 5))
        }
    }

    @Test fun roundaboutExitsUseTheOemZeroBasedIndexAndUnknownManeuversStayUnknown() {
        for ((exit, expected) in mapOf(0 to -1, 1 to 0, 16 to 15, 17 to -1)) {
            val next = guidance().copy(icon = 11, roundaboutExit = exit)
            assertEquals(expected, decode(HongqiInstrumentGuidance.from(next))[6])
        }
        assertEquals(0, decode(HongqiInstrumentGuidance.from(guidance().copy(icon = 999)))[5])
    }

    @Test fun clearParcelErasesEveryTextFieldAndTurnIndicator() {
        assertEquals(listOf("", 0, "", "", "", 0, -1), decode(HongqiInstrumentGuidance.from(null)))
    }

    @Test fun identicalDisplayValuesCanBeDeduplicatedButChangedEstimatesCannot() {
        val first = HongqiInstrumentGuidance.from(guidance())
        val same = HongqiInstrumentGuidance.from(guidance().copy(remainingSeconds = 1907))
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        val changed = HongqiInstrumentGuidance.from(guidance().copy(remainingSeconds = 1800))
        org.junit.Assert.assertNotEquals(first, changed)
    }

    private fun guidance() = ClusterTurnGuidance(
        icon = 8, roundaboutExit = 0, distanceMeters = 71, road = "测试路",
        remainingSeconds = 1906L, remainingMeters = 12000L,
    )

    private fun decode(info: HongqiInstrumentGuidance): List<Any?> {
        val parcel = Parcel.obtain()
        try {
            info.writeTo(parcel)
            parcel.setDataPosition(0)
            return readOemFields(parcel).also { assertEquals("extra fields in OEM parcel", 0, parcel.dataAvail()) }
        } finally {
            parcel.recycle()
        }
    }
}

/** QisiNaviInfo(Parcel) field order verified against the archived vehicle SDK. */
internal fun readOemFields(parcel: Parcel): List<Any?> = listOf(
    parcel.readString(), parcel.readInt(), parcel.readString(), parcel.readString(),
    parcel.readString(), parcel.readInt(), parcel.readInt(),
)
