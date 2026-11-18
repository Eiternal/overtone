package com.overtone.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlin.math.exp

internal enum class ScopeMode(val title: String, val icon: String) {
    AUTO("随音乐", "spark"), RIBBONS("流光", "scope"), ORBIT("粒子星环", "orbit"), PRISM("棱镜", "prism"),
    WATERFALL("频谱瀑布", "waterfall"), BARS("柱状频谱", "bars"), WAVE("波形", "wave"), RING("环形频谱", "ring")
}

@Composable internal fun MonitorPlayback(
    motion: ScopeMotion, visible: Boolean, playing: Boolean, mode: ScopeMode, reduced: Boolean,
    haptics: MusicHaptics, vibrationEnabled: Boolean
) {
    var resumed by remember { mutableStateOf(false) }
    val listening = visible || vibrationEnabled
    LifecycleResumeEffect(listening) {
        resumed = true
        if (listening) {
            Signal.clear()
            Signal.listening = true
        }
        onPauseOrDispose {
            resumed = false
            Signal.listening = false
            Signal.clear()
            haptics.stop()
        }
    }
    LaunchedEffect(haptics, vibrationEnabled, playing, resumed, motion) {
        if (!vibrationEnabled || !playing || !resumed) haptics.stop()
        if (!vibrationEnabled || !playing || !resumed) motion.resetRhythm()
    }
    LaunchedEffect(motion, playing, resumed, reduced, mode, visible, vibrationEnabled) {
        if (!resumed || !listening) return@LaunchedEffect
        var iterations = 0
        var previousTime = android.os.SystemClock.elapsedRealtimeNanos()
        while (true) {
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            val dt = ((now - previousTime) / 1e9f).coerceIn(.008f, .15f)
            previousTime = now
            val strength = motion.step(if (playing) Signal.atPlaybackHead() else null, dt,
                visible && mode == ScopeMode.WATERFALL, reduced, vibrationEnabled)
            if (strength > 0f && vibrationEnabled) haptics.pulse(strength)
            if (!playing && ++iterations >= 20) break
            delay(if (vibrationEnabled) 16L else if (reduced) 115L else 22L)
        }
    }
}

@Composable internal fun ScopeCanvas(
    motion: ScopeMotion, mode: ScopeMode, reduced: Boolean, modifier: Modifier, monochromeCover: Boolean,
    sceneCenter: () -> Offset?
) {
    val target = if (mode == ScopeMode.AUTO) motion.profile else mode
    var outgoing by remember { mutableStateOf<ScopeMode?>(null) }
    var shown by remember { mutableStateOf(target) }
    val transition = remember { Animatable(1f) }
    LaunchedEffect(target, reduced) {
        if (shown != target) {
            outgoing = shown.takeUnless { reduced }
            shown = target
            transition.snapTo(if (reduced) 1f else 0f)
            if (!reduced) transition.animateTo(1f, tween(400, easing = Ease))
        }
        outgoing = null
    }

    val palette = LocalFrost.current
    val colors = if (monochromeCover) ScopeColors(Color.White, Color.White, 1f)
        else ScopeColors(palette.tint, palette.secondary, palette.amount)
    LaunchedEffect(colors) { motion.history.recolor(colors) }
    Canvas(modifier) {
        motion.revision
        val fade = transition.value
        val center = sceneCenter()
        outgoing?.let { drawScopeScene(it, motion, colors, 1f, center) }
        drawScopeScene(shown, motion, colors, fade, center)
    }
}

@Composable internal fun ScopeOptions(
    mode: ScopeMode, selectMode: (ScopeMode) -> Unit, vibrationEnabled: Boolean,
    toggleHaptics: () -> Unit, modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box {
            IconButton(mode.icon, "选择可视化效果：${mode.title}",
                tint = Paper, background = Ink.copy(alpha = .58f), size = 48) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Panel) {
                ScopeMode.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Label(candidate.title, color = if (mode == candidate) Accent else Paper) },
                        leadingIcon = { Glyph(candidate.icon, Modifier.size(19.dp), if (mode == candidate) Accent else Muted) },
                        trailingIcon = { if (mode == candidate) Glyph("check", Modifier.size(17.dp), Accent) },
                        onClick = { selectMode(candidate); menuOpen = false }
                    )
                }
            }
        }
        IconButton("vibration", if (vibrationEnabled) "关闭音乐触感" else "开启音乐触感",
            Modifier.semantics { toggleableState = ToggleableState(vibrationEnabled) },
            tint = if (vibrationEnabled) Ink else Paper,
            background = if (vibrationEnabled) Accent else Ink.copy(alpha = .58f), size = 48, action = toggleHaptics)
    }
}

internal class ScopeMotion {
    private val analyzer = Analyzer()
    val bands = FloatArray(48)
    val samples = FloatArray(Signal.SIZE)
    val history = SpectrumHistory(analyzer.bands.size)
    var phase = 0f
        private set
    var level = 0f
        private set
    var bass = 0f
        private set
    var pulse = 0f
        private set
    var profile by mutableStateOf(ScopeMode.RIBBONS)
        private set
    var revision by mutableIntStateOf(0)
        private set
    private var rhythm = RhythmPulse()

    fun resetRhythm() { rhythm = RhythmPulse() }

    private var frameAt = 1f
    private var source: Signal.Frame? = null
    private var low = 0f
    private var mid = 0f
    private var treble = 0f
    private var bassBaseline = 0f
    private var historyClock = 0f
    private var profileClock = 0f
    private var evidence = 0f
    private var candidate = ScopeMode.RIBBONS
    private var quiet = 0f
    private var activity = 0f
    private var averageLevel = 0f

    fun step(frame: Signal.Frame?, dt: Float, keepHistory: Boolean, reduced: Boolean, haptics: Boolean): Float {
        var strength = 0f
        frameAt += dt
        if (frame == null && source != null) {
            source = null
            resetRhythm()
        }
        if (frame != null && frame !== source) {
            // Re-analyze only when the decoder publishes a new PCM window.
            analyzer.update(frame)
            if (haptics) strength = rhythm.update(analyzer.bands, analyzer.bassShare, frame.timeUs)
            source = frame
            frameAt = 0f
            val rms = kotlin.math.sqrt(frame.samples.sumOf { (it * it).toDouble() } / frame.samples.size).toFloat()
            level = follow(level, (rms * 3.6f).coerceAtMost(1f), dt, .10f)
        }
        val active = frame != null && frameAt < .45f
        if (!active) level = follow(level, 0f, dt, .19f)
        for (i in bands.indices) {
            val target = if (active) analyzer.bands[i] else 0f
            bands[i] = follow(bands[i], target, dt, if (target > bands[i]) .060f else .24f)
        }
        if (active) frame.samples.copyInto(samples)
        else {
            val decay = exp(-dt / .085f)
            for (i in samples.indices) samples[i] *= decay
        }
        bass = average(0, 11)
        val onset = ((bass - bassBaseline - .028f) * 5.8f).coerceIn(0f, 1f)
        bassBaseline = follow(bassBaseline, bass, dt, .52f)
        pulse = maxOf(pulse * exp(-dt / .25f), onset)
        if (active && !reduced) phase += dt * (.25f + level * .76f + pulse * .9f)
        if (active && keepHistory) {
            historyClock += dt
            if (historyClock >= .046f) {
                historyClock = 0f
                history.push(bands)
            }
        }
        if (active) classify(dt)
        revision++
        return strength
    }

    private fun classify(dt: Float) {
        low = follow(low, bass, dt, 1.1f)
        mid = follow(mid, average(12, 32), dt, 1.1f)
        treble = follow(treble, average(33, 47), dt, 1.1f)
        activity = follow(activity, pulse, dt, 1.3f)
        averageLevel = follow(averageLevel, level, dt, 1.3f)
        quiet = if (averageLevel < .045f) quiet + dt else 0f
        profileClock += dt
        val next = when {
            quiet > .35f || averageLevel < .095f -> ScopeMode.RIBBONS
            low > .28f && low > mid * 1.19f && (activity > .14f || low > .60f) -> ScopeMode.ORBIT
            mid > .37f || treble > .23f || averageLevel > .42f -> ScopeMode.PRISM
            else -> ScopeMode.RIBBONS
        }
        if (next != candidate) {
            candidate = next
            evidence = 0f
        } else evidence += dt
        if (profile != candidate && evidence > 1.0f && profileClock > 3.3f) {
            profile = candidate
            profileClock = 0f
        }
    }

    private fun average(first: Int, last: Int): Float {
        var sum = 0f
        for (i in first..last) sum += bands[i]
        return sum / (last - first + 1)
    }

    private fun follow(current: Float, target: Float, dt: Float, time: Float): Float =
        current + (target - current) * (1f - exp(-dt / time))
}
