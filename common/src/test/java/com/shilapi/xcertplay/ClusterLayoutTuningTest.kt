package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.shared.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowCanvas
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterLayoutTuningTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val guidance = ClusterTurnGuidance(2, 0, 320, "Test road",
        remainingSeconds = 2820, remainingMeters = 25000, arrivalEpochSeconds = 1_700_000_000)

    @Before fun setup() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        ClusterLayoutTuning.preferences(app).edit().clear().commit()
    }

    @Test fun manifestLimitsAdbInterfaceToTheTuningBuildAndPrivilegedSenders() {
        val info = app.packageManager.getReceiverInfo(ComponentName(app, ClusterLayoutReceiver::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals(BuildConfig.FAW_CLUSTER_TUNING_ENABLED, info.enabled)
        assertEquals("android.permission.DUMP", info.permission)
        assertTrue(info.exported)
    }

    @Test @Config(sdk = [28]) fun informationBarMovesAsOneWithoutMovingTheTurnBanner() {
        withCard { card ->
            val before = frame(card)
            ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.ETE, ClusterLayoutTuning.Placement(210, 510, 125))
            val after = frame(card)
            assertEquals(before.getDrawnRoundRect(0).rect, after.getDrawnRoundRect(0).rect)
            val duration = app.getString(R.string.turn_card_info_duration_m, 47)
            val remaining = texts(after).single { duration in it.text }
            assertEquals(28f * 213f / 160f * 1.25f, remaining.paint.textSize, 0.01f)
            assertTrue(remaining.x in 210f..710f && remaining.y in 510f..610f)
            assertSameTextPosition(before, after, app.getString(R.string.turn_card_distance_m, 320))
            assertTrue(app.getString(R.string.turn_card_info_km, 25.0) in remaining.text)
            assertFalse(texts(after).any { it.text == duration })
        }
    }

    @Test @Config(sdk = [28]) fun tbtMovesAndScalesWithoutMovingEteOrTheOtherRouteFields() {
        withCard { card ->
            val before = frame(card)
            ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.TBT, ClusterLayoutTuning.Placement(100, 420, 75))
            val after = frame(card)
            val rect = after.getDrawnRoundRect(0).rect
            assertEquals(100f, rect.left, 0f)
            assertEquals(420f, rect.top, 0f)
            assertEquals(500f, rect.width(), 0f)
            val turn = texts(after).single { it.text == app.getString(R.string.turn_card_distance_m, 320) }
            assertEquals(40f * 213f / 160f * 0.75f, turn.paint.textSize, 0.01f)
            assertSameTextPosition(before, after, app.getString(R.string.turn_card_info_duration_m, 47))
            assertSameTextPosition(before, after, app.getString(R.string.turn_card_info_km, 25.0))
        }
    }

    @Test @Config(sdk = [28]) fun changesInvalidateTheAttachedViewAndSurviveRecreation() {
        withCard { card ->
            shadowOf(card).clearWasInvalidated()
            ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.ETE, ClusterLayoutTuning.Placement(100, 540, 90))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(card).wasInvalidated())
        }
        withCard { card ->
            val duration = texts(frame(card)).single { app.getString(R.string.turn_card_info_duration_m, 47) in it.text }
            assertEquals(28f * 213f / 160f * 0.90f, duration.paint.textSize, 0.01f)
        }
    }

    @Test @Config(sdk = [28]) fun restoreNavi29KeepsTheWholeInformationBarAndDoesNotClearPairing() {
        app.getSharedPreferences("diplay", 0).edit().putString("phone_name", "Test phone").commit()
        ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.TBT, ClusterLayoutTuning.Placement(100, 420, 75))
        ClusterLayoutTuning.restoreNavi29(app)
        assertNull(ClusterLayoutTuning.custom(app, ClusterLayoutTuning.Target.TBT))
        assertNull(ClusterLayoutTuning.custom(app, ClusterLayoutTuning.Target.ETE))
        assertEquals("Test phone", app.getSharedPreferences("diplay", 0).getString("phone_name", null))
        withCard { card ->
            val drawing = frame(card)
            assertEquals(1154f, drawing.getDrawnRoundRect(0).rect.left, 0f)
            assertTrue(texts(drawing).any { it.text.contains("47分") && it.text.contains("25.0公里") })
        }
        ClusterLayoutTuning.reset(app, null)
        assertEquals(ClusterLayoutTuning.defaults(ClusterLayoutTuning.Target.ETE),
            ClusterLayoutTuning.load(app, ClusterLayoutTuning.Target.ETE))
    }

    @Test @Config(sdk = [28]) fun invalidRequestsDoNotPartiallyOverwriteTheSavedLayout() {
        assumeTrue(BuildConfig.FAW_CLUSTER_TUNING_ENABLED)
        val saved = ClusterLayoutTuning.Placement(100, 420, 75)
        ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.TBT, saved)
        for (bad in listOf(
            request("set").putExtra("target", "tbt").putExtra("x", "200").putExtra("y", 420).putExtra("scale", 75),
            request("set").putExtra("target", "tbt").putExtra("x", -1).putExtra("y", 420).putExtra("scale", 75),
            request("set").putExtra("target", "tbt").putExtra("x", 100).putExtra("y", 720).putExtra("scale", 75),
            request("set").putExtra("target", "tbt").putExtra("x", 100).putExtra("y", 420).putExtra("scale", 151),
            request("set").putExtra("target", "tbt").putExtra("x", 100).putExtra("y", 420)
                .putExtra("scale", 100).putExtra("width", "600").putExtra("height", 80),
            request("set").putExtra("target", "tbt").putExtra("x", 100).putExtra("y", 420)
                .putExtra("scale", 100).putExtra("width", 1921).putExtra("height", 80),
            request("set").putExtra("target", "tbt").putExtra("x", 100).putExtra("y", 420)
                .putExtra("scale", 100).putExtra("width", 600).putExtra("height", 39),
            request("reset").putExtra("target", "unknown"), request("unknown"))) {
            assertThrows(InvocationTargetException::class.java) { execute(bad) }
            assertEquals(saved, ClusterLayoutTuning.load(app, ClusterLayoutTuning.Target.TBT))
        }
    }

    @Test @Config(sdk = [28]) fun validAdbPayloadUpdatesTheSameStoreThatTheViewReads() {
        assumeTrue(BuildConfig.FAW_CLUSTER_TUNING_ENABLED)
        val reply = execute(request("set").putExtra("target", "ete")
            .putExtra("x", 160).putExtra("y", 530).putExtra("scale", 110)
            .putExtra("width", 900).putExtra("height", 100)) as org.json.JSONObject
        assertTrue(reply.getBoolean("ok"))
        assertEquals(160, reply.getJSONObject("ete").getInt("x"))
        assertEquals(ClusterLayoutTuning.Placement(160, 530, 110, 900, 100),
            ClusterLayoutTuning.load(app, ClusterLayoutTuning.Target.ETE))
    }

    @Test @Config(sdk = [28]) fun widthAndHeightAreIndependentOfFontScaleAndTheOtherTarget() {
        for (target in ClusterLayoutTuning.Target.entries) {
            ClusterLayoutTuning.preferences(app).edit().clear().commit()
            withCard { card ->
                val base = ClusterLayoutTuning.Placement(120, 180, 100, 900, 100)
                ClusterLayoutTuning.save(app, target, base)
                val before = frame(card)
                ClusterLayoutTuning.save(app, target, base.copy(width = 1100))
                val wider = frame(card)
                val index = if (target == ClusterLayoutTuning.Target.TBT) 0 else 3
                val other = if (index == 0) 3 else 0
                assertEquals(1100f, wider.getDrawnRoundRect(index).rect.width(), 0f)
                assertEquals(100f, wider.getDrawnRoundRect(index).rect.height(), 0f)
                assertEquals(before.getDrawnRoundRect(other).rect, wider.getDrawnRoundRect(other).rect)
                ClusterLayoutTuning.save(app, target, base.copy(width = 1100, height = 180))
                val taller = frame(card)
                assertEquals(1100f, taller.getDrawnRoundRect(index).rect.width(), 0f)
                assertEquals(180f, taller.getDrawnRoundRect(index).rect.height(), 0f)
                val label = if (index == 0) app.getString(R.string.turn_card_distance_m, 320)
                    else app.getString(R.string.turn_card_info_duration_m, 47)
                val originalFont = texts(before).single { label in it.text }.paint.textSize
                assertEquals(originalFont, texts(wider).single { label in it.text }.paint.textSize, 0f)
                assertEquals(originalFont, texts(taller).single { label in it.text }.paint.textSize, 0f)
                assertEquals(before.getDrawnRoundRect(other).rect, taller.getDrawnRoundRect(other).rect)
            }
        }
    }

    @Test @Config(sdk = [28]) fun legacyTurnCalibrationSurvivesButTheOldSeparateTimeBoxIsIgnored() {
        ClusterLayoutTuning.preferences(app).edit()
            .putInt("tbt_x", 1360).putInt("tbt_y", 136).putInt("tbt_scale", 69)
            .putInt("ete_x", 150).putInt("ete_y", 560).putInt("ete_scale", 100)
            .putBoolean("ete_detached", true).commit()
        val turn = ClusterLayoutTuning.load(app, ClusterLayoutTuning.Target.TBT)
        assertEquals(ClusterLayoutTuning.Placement(1360, 136, 69), turn)
        assertEquals(460 to 110, ClusterLayoutTuning.referenceSize(ClusterLayoutTuning.Target.TBT, turn))
        assertNull(ClusterLayoutTuning.custom(app, ClusterLayoutTuning.Target.ETE))
        withCard { card ->
            val info = texts(frame(card)).single { app.getString(R.string.turn_card_info_duration_m, 47) in it.text }
            assertTrue(app.getString(R.string.turn_card_info_km, 25.0) in info.text)
        }
    }

    @Test @Config(sdk = [33]) fun unsupportedPlatformRemainsReadOnlyAndKeepsTheOriginalRendering() {
        assumeTrue(BuildConfig.FAW_CLUSTER_TUNING_ENABLED)
        val reply = execute(request("get")) as org.json.JSONObject
        assertFalse(reply.getBoolean("supported"))
        val before = ClusterLayoutTuning.preferences(app).all
        val failure = assertThrows(InvocationTargetException::class.java) {
            execute(request("set").putExtra("target", "ete")
                .putExtra("x", 160).putExtra("y", 530).putExtra("scale", 110))
        }
        assertTrue(failure.cause is IllegalStateException)
        assertEquals(before, ClusterLayoutTuning.preferences(app).all)
        withCard { card ->
            val duration = app.getString(R.string.turn_card_info_duration_m, 47)
            val info = texts(frame(card)).single { duration in it.text }
            assertTrue(app.getString(R.string.turn_card_info_km, 25.0) in info.text)
        }
    }

    @Test @Config(sdk = [28]) fun shippedLayoutUsesTheConfirmedProfileWithoutWritingLocalPreferences() {
        withCard { card ->
            val drawing = frame(card)
            val turnRect = drawing.getDrawnRoundRect(0).rect
            assertEquals(android.graphics.RectF(1396f, 81f, 1853f, 245f), turnRect)
            val infoRect = drawing.getDrawnRoundRect(3).rect
            assertEquals(android.graphics.RectF(90f, 622f, 540f, 667f), infoRect)
            val turn = texts(drawing).single { it.text == app.getString(R.string.turn_card_distance_m, 320) }
            val road = texts(drawing).single { it.text == "Test road" }
            val info = texts(drawing).single { app.getString(R.string.turn_card_info_duration_m, 47) in it.text }
            assertEquals(40f * 213f / 160f * 0.69f, turn.paint.textSize, 0.01f)
            assertEquals(30f * 213f / 160f * 0.69f, road.paint.textSize, 0.01f)
            assertEquals(28f * 213f / 160f * 0.80f, info.paint.textSize, 0.01f)
            assertNull(ClusterLayoutTuning.custom(app, ClusterLayoutTuning.Target.TBT))
            assertNull(ClusterLayoutTuning.custom(app, ClusterLayoutTuning.Target.ETE))
            assertTrue(ClusterLayoutTuning.preferences(app).all.isEmpty())
        }
    }

    @Test @Config(sdk = [28]) fun savedCalibrationAppliesIndependentlyOfTheAdbBuildFlag() {
        withCard { card ->
            ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.TBT, ClusterLayoutTuning.Placement(100, 420, 75))
            ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.ETE, ClusterLayoutTuning.Placement(160, 530, 110))
            val after = frame(card)
            assertEquals(100f, after.getDrawnRoundRect(0).rect.left, 0f)
            assertEquals(420f, after.getDrawnRoundRect(0).rect.top, 0f)
            assertEquals(160f, after.getDrawnRoundRect(3).rect.left, 0f)
            assertEquals(530f, after.getDrawnRoundRect(3).rect.top, 0f)
            val duration = app.getString(R.string.turn_card_info_duration_m, 47)
            val info = texts(after).single { duration in it.text }
            assertTrue(app.getString(R.string.turn_card_info_km, 25.0) in info.text)
            assertEquals(28f * 213f / 160f * 1.10f, info.paint.textSize, 0.01f)
        }
    }

    @Test @Config(sdk = [28]) fun resettingOneTargetLeavesTheOtherLegacyPlacementUntouched() {
        ClusterLayoutTuning.restoreNavi29(app)
        ClusterLayoutTuning.reset(app, ClusterLayoutTuning.Target.TBT)
        assertEquals(ClusterLayoutTuning.defaults(ClusterLayoutTuning.Target.TBT),
            ClusterLayoutTuning.forDrawing(app, ClusterLayoutTuning.Target.TBT))
        assertNull(ClusterLayoutTuning.forDrawing(app, ClusterLayoutTuning.Target.ETE))
        assertEquals(ClusterLayoutTuning.Placement(1154, 328, 100),
            ClusterLayoutTuning.load(app, ClusterLayoutTuning.Target.ETE))
        val saved = ClusterLayoutTuning.Placement(200, 500, 90, 800, 70)
        ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.ETE, saved)
        assertEquals(saved, ClusterLayoutTuning.forDrawing(app, ClusterLayoutTuning.Target.ETE))
        assertFalse(ClusterLayoutTuning.preferences(app).contains("route_info_original"))
    }

    @Test fun malformedLegacyMarkersCannotSuppressTheShippedDefaults() {
        ClusterLayoutTuning.preferences(app).edit().putString("tbt_original", "true")
            .putInt("route_info_original", 1).commit()
        for (target in ClusterLayoutTuning.Target.entries) {
            assertEquals(ClusterLayoutTuning.defaults(target), ClusterLayoutTuning.load(app, target))
            assertEquals(ClusterLayoutTuning.defaults(target), ClusterLayoutTuning.forDrawing(app, target))
        }
    }

    @Test fun badStoredTypesFallBackAndEdgePlacementsStayInsideTheCanvas() {
        ClusterLayoutTuning.preferences(app).edit().putString("tbt_x", "broken")
            .putInt("tbt_y", 100).putInt("tbt_scale", 100).commit()
        assertNull(ClusterLayoutTuning.custom(app, ClusterLayoutTuning.Target.TBT))
        val rect = ClusterLayoutTuning.rect(ClusterLayoutTuning.Placement(1919, 719, 150), 1920, 720, 666, 160)
        assertEquals(1920, rect.left + rect.width)
        assertEquals(720, rect.top + rect.height)
    }

    @Test fun noRouteMeansNoStaleTimeOrTurnHint() {
        withCard { card ->
            card.setGuidance(null)
            assertTrue(texts(frame(card)).isEmpty())
        }
    }

    private fun request(op: String) = Intent(ClusterLayoutReceiver.ACTION).putExtra("op", op)

    private fun execute(intent: Intent): Any = ClusterLayoutReceiver::class.java
        .getDeclaredMethod("execute", Context::class.java, Intent::class.java)
        .apply { isAccessible = true }.invoke(ClusterLayoutReceiver(), app, intent).let(::checkNotNull)

    private fun withCard(block: (ClusterTurnCardView) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val card = ClusterTurnCardView(controller.get())
        val density = card.resources.displayMetrics.density
        try {
            card.resources.displayMetrics.density = 213f / 160f
            controller.get().setContentView(card)
            card.setGuidance(guidance)
            block(card)
        } finally {
            controller.pause().stop().destroy()
            card.resources.displayMetrics.density = density
        }
    }

    private fun frame(card: ClusterTurnCardView): ShadowCanvas {
        card.layout(0, 0, 1920, 720)
        val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        return try { shadowOf(Canvas(bitmap).also(card::draw)) } finally { bitmap.recycle() }
    }

    private fun texts(frame: ShadowCanvas) = (0 until frame.textHistoryCount).map(frame::getDrawnTextEvent)

    private fun assertSameTextPosition(before: ShadowCanvas, after: ShadowCanvas, label: String) {
        val first = texts(before).single { label in it.text }
        val second = texts(after).single { label in it.text }
        assertEquals(first.x, second.x, 0f)
        assertEquals(first.y, second.y, 0f)
    }
}
