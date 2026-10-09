package com.shilapi.xcertplay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.shared.BuildConfig
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import java.util.concurrent.atomic.AtomicBoolean

/** The manifest requires the shell's DUMP permission; ordinary installed apps cannot tune it. */
class ClusterLayoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (intent.getStringExtra("op") == "content") {
            selectContent(context, intent)
            return
        }
        val response = runCatching { execute(context, intent) }.getOrElse { failure ->
            JSONObject().put("ok", false).put("error", failure.message ?: "仪表布局操作失败")
        }
        resultCode = if (response.optBoolean("ok")) Activity.RESULT_OK else Activity.RESULT_CANCELED
        resultData = response.toString()
    }

    private fun selectContent(context: Context, intent: Intent) {
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        fun finish(response: JSONObject) {
            if (!finished.compareAndSet(false, true)) return
            pending.resultCode = if (response.optBoolean("ok")) Activity.RESULT_OK else Activity.RESULT_CANCELED
            pending.resultData = response.toString()
            pending.finish()
        }
        val timeout = Runnable {
            finish(JSONObject().put("ok", false).put("error", "仪表内容切换超时，结果尚未确认。请保持 CarPlay 连接并重新读取状态"))
        }
        main.postDelayed(timeout, 5_000L)
        main.post {
            try {
                check(BuildConfig.FAW_CLUSTER_TUNING_ENABLED && ClusterLayoutTuning.active()) {
                    "当前设备未启用红旗 HS5 仪表调试"
                }
                val content = CarPlayClusterDisplay.Content.entries.firstOrNull {
                    it.name == intent.getStringExtra("content")
                } ?: error("请选择有效的仪表内容")
                ClusterContentControl.select(context.applicationContext, content) { accepted ->
                    main.post {
                        main.removeCallbacks(timeout)
                        if (accepted) finish(snapshot(context))
                        else finish(JSONObject().put("ok", false)
                            .put("error", "尚未发送到 iPhone，已保留原显示。请保持 CarPlay 和仪表视频连接后重试"))
                    }
                }
            } catch (failure: Exception) {
                main.removeCallbacks(timeout)
                finish(JSONObject().put("ok", false).put("error", failure.message ?: "仪表内容切换失败"))
            }
        }
    }

    private fun execute(context: Context, intent: Intent): JSONObject {
        check(BuildConfig.FAW_CLUSTER_TUNING_ENABLED) { "当前 APK 未启用仪表布局调试" }
        val op = intent.getStringExtra("op") ?: "get"
        if (op != "get") {
            check(ClusterLayoutTuning.active()) { "当前设备不是已适配的红旗 HS5 仪表" }
            when (op) {
                "set" -> ClusterLayoutTuning.save(context, target(intent), ClusterLayoutTuning.Placement(
                    integer(intent, "x"), integer(intent, "y"), integer(intent, "scale"),
                    optionalInteger(intent, "width"), optionalInteger(intent, "height")))
                "reset" -> ClusterLayoutTuning.reset(context,
                    if (intent.getStringExtra("target") == "all") null else target(intent))
                "original" -> ClusterLayoutTuning.restoreNavi29(context)
                else -> error("未知布局操作")
            }
        }
        return snapshot(context)
    }

    private fun snapshot(context: Context): JSONObject =
        JSONObject().put("ok", true).put("protocol", 2)
            .put("supported", ClusterLayoutTuning.active())
            .put("width", ClusterLayoutTuning.WIDTH).put("height", ClusterLayoutTuning.HEIGHT)
            .put("tbt", placement(context, ClusterLayoutTuning.Target.TBT))
            .put("ete", placement(context, ClusterLayoutTuning.Target.ETE))
            .put("content", AirPlayPersistence.loadClusterContent(context).name)
            .put("contentSelection", ClusterLayoutTuning.active())
            .put("displayedUrl", ClusterContentControl.displayedUrl() ?: JSONObject.NULL)
            .put("sessionRunning", CarPlayBackgroundSession.hasSession())

    private fun target(intent: Intent): ClusterLayoutTuning.Target =
        ClusterLayoutTuning.Target.entries.firstOrNull { it.wire == intent.getStringExtra("target") }
            ?: error("请选择 tbt 或 ete")

    private fun integer(intent: Intent, key: String): Int =
        (intent.extras?.get(key) as? Int) ?: error("缺少整数参数 $key")

    private fun optionalInteger(intent: Intent, key: String): Int? =
        if (intent.hasExtra(key)) integer(intent, key) else null

    private fun placement(context: Context, target: ClusterLayoutTuning.Target): JSONObject {
        val value = ClusterLayoutTuning.load(context, target)
        val (width, height) = ClusterLayoutTuning.referenceSize(target, value)
        return JSONObject().put("x", value.x).put("y", value.y).put("scale", value.scale)
            .put("width", width).put("height", height)
            .put("custom", ClusterLayoutTuning.custom(context, target) != null)
    }

    companion object {
        const val ACTION = "com.shihab.diplay.action.CLUSTER_LAYOUT"
    }
}
