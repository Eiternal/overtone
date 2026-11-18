package com.overtone.player

import kotlin.math.*

internal enum class EqMode { SIMPLE, PROFESSIONAL }
internal enum class FilterType(val label: String, val hasGain: Boolean = false) {
    BELL("钟形", true),
    LOW_SHELF("低架", true),
    HIGH_SHELF("高架", true),
    HIGH_PASS("高通"),
    LOW_PASS("低通"),
    NOTCH("陷波");

    val isShelf get() = this == LOW_SHELF || this == HIGH_SHELF
}

internal data class EqBand(
    val frequency: Double,
    val gain: Double = 0.0,
    val q: Double = 1.0,
    val type: FilterType = FilterType.BELL,
    val enabled: Boolean = true,
)

internal fun flatBands() = listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0).map(::EqBand)

internal data class EqSettings(
    val enabled: Boolean = false,
    val mode: EqMode = EqMode.SIMPLE,
    val low: Double = 0.0,
    val mid: Double = 0.0,
    val high: Double = 0.0,
    val bands: List<EqBand> = flatBands(),
    val preamp: Double = 0.0,
    val autoHeadroom: Boolean = true,
) {
    fun parameters(): EqParameters {
        val selected = when (mode) {
            EqMode.SIMPLE -> listOf(
                EqBand(105.0, low, 1.0, FilterType.LOW_SHELF),
                EqBand(1050.0, mid, .70),
                EqBand(4300.0, high, 1.0, FilterType.HIGH_SHELF),
            )
            EqMode.PROFESSIONAL -> bands
        }
        return EqParameters(enabled, selected, if (mode == EqMode.PROFESSIONAL) preamp else 0.0, autoHeadroom)
    }

    fun resetMode(): EqSettings = when (mode) {
        EqMode.SIMPLE -> copy(low = 0.0, mid = 0.0, high = 0.0)
        EqMode.PROFESSIONAL -> copy(bands = flatBands(), preamp = 0.0)
    }
}

internal data class EqParameters(val enabled: Boolean, val bands: List<EqBand>, val preamp: Double, val autoHeadroom: Boolean)

internal data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun power(cos1: Double, sin1: Double, cos2: Double, sin2: Double): Double {
        val topReal = b0 + b1 * cos1 + b2 * cos2
        val topImag = b1 * sin1 + b2 * sin2
        val bottomReal = 1 + a1 * cos1 + a2 * cos2
        val bottomImag = a1 * sin1 + a2 * sin2
        return (topReal * topReal + topImag * topImag) / (bottomReal * bottomReal + bottomImag * bottomImag)
    }
}

// RBJ biquad coefficients. Shelves use slope S; the other filters use Q.
internal fun EqBand.coefficients(rate: Int): Biquad? {
    if (!enabled || frequency >= rate * .495 || (type.hasGain && gain == 0.0)) return null
    val angle = 2 * PI * frequency / rate
    val sine = sin(angle)
    val cosine = cos(angle)
    val amplitude = 10.0.pow(gain / 40)
    val alpha = if (type.isShelf) {
        sine / 2 * sqrt((amplitude + 1 / amplitude) * (1 / q - 1) + 2)
    } else sine / (2 * q)
    fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    val a0 = 1 + alpha
    val a1 = -2 * cosine
    val a2 = 1 - alpha
    return when (type) {
        FilterType.BELL -> normalized(1 + alpha * amplitude, a1, 1 - alpha * amplitude,
            1 + alpha / amplitude, a1, 1 - alpha / amplitude)
        FilterType.HIGH_PASS -> normalized((1 + cosine) / 2, -(1 + cosine), (1 + cosine) / 2, a0, a1, a2)
        FilterType.LOW_PASS -> normalized((1 - cosine) / 2, 1 - cosine, (1 - cosine) / 2, a0, a1, a2)
        FilterType.NOTCH -> normalized(1.0, a1, 1.0, a0, a1, a2)
        FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> {
            val sum = amplitude + 1
            val difference = amplitude - 1
            val slope = 2 * sqrt(amplitude) * alpha
            if (type == FilterType.LOW_SHELF) normalized(
                amplitude * (sum - difference * cosine + slope),
                2 * amplitude * (difference - sum * cosine),
                amplitude * (sum - difference * cosine - slope),
                sum + difference * cosine + slope, -2 * (difference + sum * cosine),
                sum + difference * cosine - slope,
            ) else normalized(
                amplitude * (sum + difference * cosine + slope),
                -2 * amplitude * (difference + sum * cosine),
                amplitude * (sum + difference * cosine - slope),
                sum - difference * cosine + slope, 2 * (difference - sum * cosine),
                sum - difference * cosine - slope,
            )
        }
    }
}

internal class EqPlan(val filters: List<Biquad>, val preamp: Double, val trim: Double, private val rate: Int) {
    val gain = 10.0.pow((preamp - trim) / 20)
    val bypass = filters.isEmpty() && gain == 1.0

    fun response(frequency: Double): Double {
        if (filters.isEmpty()) return preamp - trim
        val omega = 2 * PI * frequency / rate
        val cos1 = cos(omega)
        val sin1 = sin(omega)
        val cos2 = cos1 * cos1 - sin1 * sin1
        val sin2 = 2 * sin1 * cos1
        var power = 1.0
        for (filter in filters) power *= filter.power(cos1, sin1, cos2, sin2)
        return 10 * log10(power.coerceAtLeast(1e-18)) + preamp - trim
    }
}

internal fun EqParameters.compile(rate: Int): EqPlan {
    if (!enabled) return EqPlan(emptyList(), 0.0, 0.0, rate)
    val filters = bands.mapNotNull { it.coefficients(rate) }
    val plan = EqPlan(filters, preamp, 0.0, rate)
    if (!autoHeadroom || plan.bypass) return plan
    val nyquist = rate / 2.0
    var peak = max(plan.response(1.0), plan.response(nyquist - 1))
    for (i in 0..480) {
        val frequency = 10 * (nyquist / 10).pow(i / 480.0)
        peak = max(peak, plan.response(frequency))
    }
    for (band in bands) {
        if (band.enabled && band.frequency < nyquist) peak = max(peak, plan.response(band.frequency))
    }
    return EqPlan(filters, preamp, if (peak > .005) peak + .5 else 0.0, rate)
}

internal class EqPath(private val plan: EqPlan, channels: Int) {
    private val delay1 = DoubleArray(channels * plan.filters.size)
    private val delay2 = DoubleArray(delay1.size)
    val bypass get() = plan.bypass
    fun process(input: Double, channel: Int): Double {
        var sample = input * plan.gain
        for (index in plan.filters.indices) {
            val filter = plan.filters[index]
            val state = channel * plan.filters.size + index
            val output = sample * filter.b0 + delay1[state]
            delay1[state] = sample * filter.b1 - output * filter.a1 + delay2[state]
            delay2[state] = sample * filter.b2 - output * filter.a2
            sample = output
        }
        return sample
    }
}
