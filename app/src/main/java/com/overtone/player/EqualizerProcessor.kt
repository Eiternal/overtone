package com.overtone.player

import kotlin.math.roundToInt

/** One pair of filter chains per stream, shared by all supported PCM representations. */
internal class EqualizerProcessor(private val settings: () -> EqParameters) {
    private var active: EqPath? = null
    private var target: EqPath? = null
    private var configuration: EqParameters? = null
    private var frames = 0
    private var fadeFrames = 1

    fun reset() {
        active = null
        target = null
        configuration = null
        frames = 0
    }

    fun prepare(rate: Int, channels: Int): Boolean {
        val config = settings()
        val previous = active
        if (previous == null) {
            active = EqPath(config.compile(rate), channels)
            configuration = config
            fadeFrames = (rate * .025).roundToInt().coerceAtLeast(1)
        } else if (configuration != config && target == null) {
            val updated = EqPath(config.compile(rate), channels)
            configuration = config
            if (previous.bypass && updated.bypass) active = updated
            else {
                target = updated
                frames = 0
            }
        }
        return active?.bypass == false || target != null
    }

    fun process(sample: Double, channel: Int): Double {
        val previous = checkNotNull(active).process(sample, channel)
        val incoming = target ?: return previous
        return previous + (incoming.process(sample, channel) - previous) * frames / fadeFrames
    }

    fun endFrame() {
        val incoming = target ?: return
        if (++frames >= fadeFrames) {
            active = incoming
            target = null
        }
    }
}
