package com.overtone.player

import kotlin.math.*

/** The audio sink publishes short, read-only snapshots. The drawing thread owns the FFT. */
object Signal {
    const val SIZE = 2048
    @Volatile var listening = false
    @Volatile var playbackUs = Long.MIN_VALUE
    private val frames = ArrayDeque<Frame>()
    private var current: Frame? = null

    class Frame(val samples: FloatArray, val rate: Int, val timeUs: Long)

    @Synchronized fun publish(frame: Frame) {
        if (!listening) return
        if (frames.size == 128) frames.removeFirst()
        frames.addLast(frame)
    }

    @Synchronized fun atPlaybackHead(): Frame? {
        val head = playbackUs
        while (frames.firstOrNull()?.let { it.timeUs <= head } == true) current = frames.removeFirst()
        return current
    }

    @Synchronized fun clear() {
        frames.clear()
        current = null
        playbackUs = Long.MIN_VALUE
    }
}

internal class PcmTap {
    private val samples = FloatArray(Signal.SIZE)
    private var count = 0

    fun record(sample: Float, sampleRate: Int, presentationUs: Long, sampleOffset: Int) {
        samples[count++] = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
        if (count == samples.size) {
            val centerUs = presentationUs + (sampleOffset.toLong() - samples.size / 2) * 1_000_000 / sampleRate
            Signal.publish(Signal.Frame(samples.copyOf(), sampleRate, centerUs))
            count = 0
        }
    }

    fun idle() { count = 0 }
    fun reset() {
        idle()
        Signal.clear()
    }
}

class Analyzer {
    private val n = Signal.SIZE
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val window = DoubleArray(n) { .5 - .5 * cos(2 * PI * it / (n - 1)) }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(n)) }
    private val cosines = DoubleArray(n / 2) { cos(-2 * PI * it / n) }
    private val sines = DoubleArray(n / 2) { sin(-2 * PI * it / n) }
    val bands = FloatArray(48)
    var bassShare = 0f
        private set

    fun update(frame: Signal.Frame) {
        var windowPower = 0.0
        for (i in 0 until n) {
            val sample = frame.samples[i] * window[i]
            re[rev[i]] = sample
            im[i] = 0.0
            windowPower += sample * sample
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            for (base in 0 until n step size) for (i in 0 until half) {
                val angle = i * n / size
                val a = base + i
                val b = a + half
                val r = re[b] * cosines[angle] - im[b] * sines[angle]
                val j = re[b] * sines[angle] + im[b] * cosines[angle]
                re[b] = re[a] - r; im[b] = im[a] - j
                re[a] += r; im[a] += j
            }
            size *= 2
        }
        val d = frame.rate.toDouble() / n
        // Gate haptics on uncompressed power. Visual dB levels also show quiet spectral leakage.
        val firstBass = ceil(38 / d).toInt().coerceIn(1, n / 2 - 1)
        val lastBass = floor(185 / d).toInt().coerceIn(firstBass, n / 2 - 1)
        var bassPower = 0.0
        for (k in firstBass..lastBass) bassPower += re[k] * re[k] + im[k] * im[k]
        bassShare = (2 * bassPower / (n * windowPower).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0).toFloat()
        fun level(i: Int): Double = hypot(re[i], im[i]) * 4 / n
        for (b in bands.indices) {
            val low = 40 * 400.0.pow(b.toDouble() / bands.size) / d
            val high = 40 * 400.0.pow((b + 1.0) / bands.size) / d
            val center = sqrt(low * high).coerceIn(1.0, n / 2.0 - 2)
            val l = center.toInt()
            val fraction = center - l
            var amp = level(l) * (1 - fraction) + level(l + 1) * fraction
            for (k in ceil(low).toInt().coerceAtLeast(1)..floor(high).toInt().coerceAtMost(n / 2 - 1)) amp = max(amp, level(k))
            bands[b] = ((20 * log10(max(1e-6, amp)) + 66) / 58).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
