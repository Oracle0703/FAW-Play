package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.util.Locale

/** Debug-only preview; native artwork here is synthetic and never passed off as phone output. */
class ClusterNavigationPreviewActivity : Activity() {
    private lateinit var card: ClusterTurnCardView
    private lateinit var simulation: ClusterNavigationSimulationView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var scene = 0
    private var ticks = 0
    private var automatic = true
    private val simulationTick = object : Runnable {
        override fun run() {
            if (automatic) {
                ticks++
                if (ticks % 8 == 0) scene = (scene + 1) % 3
                render()
            }
            main.postDelayed(this, 1_000L)
        }
    }

    override fun attachBaseContext(base: Context) {
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(17, 25, 34)) }
        simulation = ClusterNavigationSimulationView(this)
        root.addView(simulation, FrameLayout.LayoutParams(-1, -1))
        card = ClusterTurnCardView(this, previewHongqi = true)
        root.addView(card, FrameLayout.LayoutParams(-1, -1))
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.rgb(17, 25, 34))
            addView(TextView(this@ClusterNavigationPreviewActivity).apply {
                text = "Autunn Play · 仪表通道测试（模拟数据）"
                textSize = 22f; setTextColor(Color.WHITE)
            })
        }
        val choices = LinearLayout(this)
        for ((label, content) in listOf(
            "地图＋可调提示框" to CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD,
            "CarPlay 原生组合" to CarPlayClusterDisplay.Content.INSTRUMENTS,
            "CarPlay 原生卡片" to CarPlayClusterDisplay.Content.TURN_CARD,
        )) choices.addView(Button(this).apply {
            text = label; textSize = 18f
            setOnClickListener {
                ClusterContentControl.select(this@ClusterNavigationPreviewActivity, content) {
                    runOnUiThread { render() }
                }
            }
        })
        choices.addView(Button(this).apply {
            text = "下一模拟场景"; textSize = 18f
            setOnClickListener { scene = (scene + 1) % 3; ticks = 0; render() }
        })
        choices.addView(Button(this).apply {
            text = "暂停演示"; textSize = 18f
            setOnClickListener {
                automatic = !automatic
                text = if (automatic) "暂停演示" else "继续演示"
            }
        })
        toolbar.addView(choices)
        status = TextView(this).apply { textSize = 16f; setTextColor(Color.rgb(159, 185, 209)) }
        toolbar.addView(status)
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        // Only the separate debug package receives example calibration; real-car data is untouched.
        if (ClusterLayoutTuning.preferences(this).all.isEmpty()) {
            AirPlayPersistence.saveClusterContent(this, CarPlayClusterDisplay.Content.INSTRUMENTS)
            ClusterLayoutTuning.save(this, ClusterLayoutTuning.Target.TBT,
                ClusterLayoutTuning.Placement(1396, 230, 69, 457, 164))
            ClusterLayoutTuning.save(this, ClusterLayoutTuning.Target.ETE,
                ClusterLayoutTuning.Placement(90, 622, 80, 450, 45))
        }
        // Shell-driven scenarios test the same selection path without an attached phone.
        intent.getStringExtra("preview_content")?.let { name ->
            CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == name }?.let { content ->
                ClusterContentControl.select(this, content) {}
            }
        }
        scene = intent.getIntExtra("preview_scene", 0).coerceIn(0, 2)
        render()
    }

    override fun onResume() {
        super.onResume()
        main.postDelayed(simulationTick, 1_000L)
    }

    override fun onPause() {
        main.removeCallbacks(simulationTick)
        super.onPause()
    }

    private fun render() {
        val content = AirPlayPersistence.loadClusterContent(this)
        val local = CarPlayClusterDisplay.usesCustomTurnCard(content)
        val distance = (320 - (ticks % 8) * 20).coerceAtLeast(80)
        simulation.show(content, scene, distance)
        card.setGuidance(if (local) ClusterTurnGuidance(3, 0, distance, "示例道路",
            arrivalEpochSeconds = 1_700_000_000, remainingSeconds = 2820, remainingMeters = 25000) else null)
        card.setNightMode(true)
        status.text = "模拟数据 · 内容：${content.name} · 场景 ${scene + 1}/3 · ${distance} 米 · 真实高德输出待实机确认"
        status.contentDescription = content.name
    }
}
