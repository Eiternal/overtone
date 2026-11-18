package com.overtone.player

import kotlin.math.*

fun main() {
    val rate = 48000
    fun plan(band: EqBand) = EqParameters(true, listOf(band), 0.0, false).compile(rate)
    fun gain(band: EqBand, hz: Double) = plan(band).response(hz)
    fun near(actual: Double, expected: Double, tolerance: Double = .04) {
        check(abs(actual - expected) < tolerance) { "Expected $expected, measured $actual" }
    }
    near(gain(EqBand(1000.0, 9.0, 2.0), 1000.0), 9.0)
    near(gain(EqBand(1000.0, -9.0, 2.0), 1000.0), -9.0)
    near(gain(EqBand(1000.0, 8.0, 1.0, FilterType.LOW_SHELF), 20.0), 8.0)
    near(gain(EqBand(1000.0, -8.0, 1.0, FilterType.HIGH_SHELF), 18000.0), -8.0)
    near(gain(EqBand(1000.0, q = sqrt(.5), type = FilterType.LOW_PASS), 1000.0), -3.0103)
    near(gain(EqBand(1000.0, q = sqrt(.5), type = FilterType.HIGH_PASS), 1000.0), -3.0103)
    check(gain(EqBand(1000.0, q = 3.0, type = FilterType.NOTCH), 1000.0) < -100.0)
    check(plan(EqBand(1000.0, 5.0, enabled = false)).bypass)
    check(EqSettings().parameters().compile(rate).bypass)
    check(EqParameters(true, listOf(EqBand(16000.0, 7.0)), 0.0, true).compile(22050).bypass)
    val boost = EqParameters(true, listOf(EqBand(1000.0, 9.0), EqBand(1000.0, 6.0)), 2.0, true).compile(rate)
    near(boost.trim, 17.5)
    near(boost.response(1000.0), -.5)
    val bands = listOf(
        EqBand(2000.0, 6.0, .8, FilterType.BELL),
        EqBand(300.0, -6.0, 1.0, FilterType.LOW_SHELF),
        EqBand(4000.0, 5.0, .7, FilterType.HIGH_SHELF),
        EqBand(180.0, q = .7, type = FilterType.HIGH_PASS),
        EqBand(3500.0, q = .7, type = FilterType.LOW_PASS),
        EqBand(1000.0, q = 8.0, type = FilterType.NOTCH),
    )
    for (band in bands) for (frequency in listOf(100.0, 950.0, 2000.0, 6500.0)) {
        val plan = plan(band)
        val path = EqPath(plan, 2)
        var totalIn = 0.0
        var totalOut = 0.0
        for (n in 0 until rate) {
            val input = .06 * sin(2 * PI * frequency * n / rate)
            val output = path.process(input, 0)
            check(path.process(0.0, 1) == 0.0)
            if (n >= rate / 2) {
                totalIn += input * input
                totalOut += output * output
            }
        }
        near(10 * log10(totalOut / totalIn), plan.response(frequency))
    }
    println("PASS: six filters, gain and cutoff, channel isolation, bypass, Nyquist, overlapping boost and PCM measurement.")
}
