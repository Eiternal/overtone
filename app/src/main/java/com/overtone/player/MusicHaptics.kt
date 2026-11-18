package com.overtone.player

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.exp
import kotlin.math.roundToInt

internal class MusicHaptics(context: Context) {
    @Suppress("DEPRECATION")
    private val motor = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else context.getSystemService(Vibrator::class.java)

    val supported = motor?.hasVibrator() == true
    private val amplitudeControl = supported && motor?.hasAmplitudeControl() == true
    private val tickPrimitive = if (Build.VERSION.SDK_INT >= 31 && motor != null && supported) {
        val candidates = intArrayOf(VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_TICK)
        val durations = motor.getPrimitiveDurations(*candidates)
        candidates.indices.firstOrNull { durations[it] in 1..35 }?.let { candidates[it] }
    } else null
    private val audioUsage = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private var sent = false

    @Suppress("DEPRECATION")
    fun pulse(strength: Float) {
        val vibrator = motor ?: return
        if (!supported) return
        val amount = strength.coerceIn(0f, 1f)
        val effect = when {
            Build.VERSION.SDK_INT >= 31 && tickPrimitive != null -> VibrationEffect.startComposition()
                .addPrimitive(tickPrimitive, .26f + amount * .30f)
                .compose()
            amplitudeControl -> {
                val peak = (47 + amount * 76).roundToInt()
                VibrationEffect.createWaveform(
                    longArrayOf(3, 8, 5, 3), intArrayOf(peak / 3, peak, peak / 3, 0), -1
                )
            }
            else -> VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else vibrator.vibrate(effect, audioUsage)
        sent = true
    }

    fun stop() {
        if (sent) motor?.cancel()
        sent = false
    }
}

/** Tracks new bass attacks without retriggering over the tail of the same note. */
internal class RhythmPulse {
    private var lowFloor = 0f
    private var previousLow = 0f
    private var sinceHit = 0f
    private var lastTimeUs: Long? = null

    fun update(bands: FloatArray, bassShare: Float, timeUs: Long): Float {
        val last = lastTimeUs
        lastTimeUs = timeUs
        val dt = if (last == null) 0f else (timeUs - last) / 1e6f
        // The first dozen logarithmic bands cover about 40 to 180 Hz.
        val low = mean(bands, 0, 12)
        if (dt <= 0f || dt > .35f) {
            lowFloor = low
            previousLow = low
            sinceHit = .20f
            return 0f
        }
        sinceHit += dt
        val rise = low - previousLow
        val excess = low - lowFloor
        previousLow = low
        lowFloor += excess * (1f - exp(-dt / .32f))
        if (sinceHit < .20f || bassShare < .16f || low < .19f || rise < .065f || excess < .135f) return 0f
        sinceHit = 0f
        return ((excess - .09f) * 3.4f).coerceAtMost(1f)
    }

    private fun mean(bands: FloatArray, start: Int, end: Int): Float {
        var sum = 0f
        for (i in start until end) sum += bands[i]
        return sum / (end - start)
    }
}
