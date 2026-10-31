package com.example.personalovertimerecord

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalovertimerecord.databinding.ActivityBreathGuideBinding
import com.example.personalovertimerecord.utils.RelaxCampaignStore
import com.example.personalovertimerecord.view.BreathPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 解压小游戏：4-7-8 呼吸引导
 *
 * 纯本地能力，不读写业务数据：吸气 4 秒 → 屏息 7 秒 → 呼气 8 秒循环，
 * 阶段切换时给出触感提示（可开关），退出页面自动暂停。
 */
class BreathGuideActivity : AppCompatActivity() {

    private companion object {
        const val PREFS_HAPTIC = "relax_prefs"
        const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        const val KEY_ELAPSED = "elapsed_ms"
        const val KEY_RUNNING = "running"

        /** 吸气 4 秒 · 屏息 7 秒 · 呼气 8 秒 */
        const val INHALE_MS = 4_000L
        const val HOLD_MS = 7_000L
        const val EXHALE_MS = 8_000L
        const val FRAME_INTERVAL_MS = 16L
    }

    private data class PhaseStep(val phase: BreathPhase, val durationMs: Long, val labelRes: Int)

    private val phases = listOf(
        PhaseStep(BreathPhase.INHALE, INHALE_MS, R.string.relax_phase_inhale),
        PhaseStep(BreathPhase.HOLD, HOLD_MS, R.string.relax_phase_hold),
        PhaseStep(BreathPhase.EXHALE, EXHALE_MS, R.string.relax_phase_exhale)
    )
    private val cycleDurationMs = phases.sumOf { it.durationMs }

    private lateinit var binding: ActivityBreathGuideBinding

    private var elapsedMs = 0L
    private var running = false
    private var tickerJob: Job? = null

    private var lastRenderedPhase: BreathPhase? = null
    private var lastRenderedSecond = -1
    private var lastRenderedCycle = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBreathGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 节日活动配色（由解压入口页下发；无活动时使用默认配色）
        intent.getIntArrayExtra(RelaxCampaignStore.EXTRA_THEME_COLORS)
            ?.let { binding.breathView.setPhaseColors(it) }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupControls(savedInstanceState)
    }

    private fun setupControls(savedInstanceState: Bundle?) {
        binding.btnToggle.setOnClickListener {
            if (running) pause() else start()
        }
        binding.btnRestart.setOnClickListener { reset() }

        val prefs = getSharedPreferences(PREFS_HAPTIC, Context.MODE_PRIVATE)
        binding.switchHaptic.isChecked = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        binding.switchHaptic.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, checked).apply()
        }

        if (savedInstanceState != null) {
            elapsedMs = savedInstanceState.getLong(KEY_ELAPSED, 0L)
        }
        if (elapsedMs > 0L) {
            renderSession()
            if (savedInstanceState?.getBoolean(KEY_RUNNING, false) == true) {
                running = true
                applyRunningState()
            }
        } else {
            renderIdle()
        }
    }

    override fun onStart() {
        super.onStart()
        startTicker()
    }

    override fun onStop() {
        // 离开页面时自动暂停，回来后点击"继续"即可接着练
        pause()
        tickerJob?.cancel()
        tickerJob = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_ELAPSED, elapsedMs)
        outState.putBoolean(KEY_RUNNING, running)
    }

    private fun start() {
        running = true
        applyRunningState()
    }

    private fun pause() {
        if (!running) return
        running = false
        applyRunningState()
    }

    private fun reset() {
        elapsedMs = 0L
        running = false
        lastRenderedPhase = null
        lastRenderedSecond = -1
        lastRenderedCycle = 0
        renderIdle()
        applyRunningState()
    }

    private fun applyRunningState() {
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.btnToggle.text = getString(R.string.relax_pause)
            binding.tvCycleInfo.visibility = View.VISIBLE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val hasProgress = elapsedMs > 0L
            binding.btnToggle.text = getString(
                if (hasProgress) R.string.relax_resume else R.string.relax_start
            )
        }
    }

    private fun renderIdle() {
        binding.breathView.render(BreathPhase.INHALE, 0f)
        binding.tvPhaseTitle.text = getString(R.string.relax_ready)
        binding.tvCountdown.visibility = View.GONE
        binding.tvCycleInfo.visibility = View.GONE
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            var lastFrame = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(FRAME_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                if (running) {
                    elapsedMs += now - lastFrame
                    renderSession()
                }
                lastFrame = now
            }
        }
    }

    private fun renderSession() {
        val cycleIndex = (elapsedMs / cycleDurationMs).toInt() + 1
        val offset = elapsedMs % cycleDurationMs

        var phaseStart = 0L
        var step = phases.last()
        for (candidate in phases) {
            if (offset < phaseStart + candidate.durationMs) {
                step = candidate
                break
            }
            phaseStart += candidate.durationMs
        }
        val phaseElapsed = offset - phaseStart

        binding.breathView.render(
            step.phase,
            phaseElapsed.toFloat() / step.durationMs
        )

        val remainingSeconds =
            ceil((step.durationMs - phaseElapsed).toFloat() / 1000f).toInt().coerceAtLeast(1)
        if (remainingSeconds != lastRenderedSecond) {
            lastRenderedSecond = remainingSeconds
            binding.tvCountdown.text = remainingSeconds.toString()
            binding.tvCountdown.visibility = View.VISIBLE
            binding.tvPhaseTitle.text = getString(step.labelRes)
        }

        if (cycleIndex != lastRenderedCycle) {
            lastRenderedCycle = cycleIndex
            binding.tvCycleInfo.text = getString(R.string.relax_cycle, cycleIndex)
        }

        // 阶段切换时给一次触感提示
        if (step.phase != lastRenderedPhase) {
            lastRenderedPhase = step.phase
            if (binding.switchHaptic.isChecked) {
                binding.breathView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }
}
