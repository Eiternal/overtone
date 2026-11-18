package com.overtone.player

import java.nio.ByteBuffer

fun main() {
    for (encoding in PcmEncoding.entries) {
        val samples = when (encoding) {
            PcmEncoding.U8 -> listOf(-1.0, -1.0 / 128, 0.0, 1.0 / 128, 127.0 / 128)
            PcmEncoding.S16, PcmEncoding.S16_BE -> integerLevels(32768)
            PcmEncoding.S24, PcmEncoding.S24_BE -> integerLevels(8388608)
            PcmEncoding.S32, PcmEncoding.S32_BE -> integerLevels(2147483648)
            PcmEncoding.FLOAT -> listOf(-1.0, -1.0 / 16777216, 0.0, 1.0 / 16777216, 1.0)
        }
        val buffer = ByteBuffer.allocate(samples.size * encoding.bytes).order(encoding.order)
        for (sample in samples) encoding.write(buffer, sample)
        buffer.flip()
        for (sample in samples) check(encoding.read(buffer) == sample) { "Lost $sample in $encoding" }
    }
    val packed = ByteBuffer.wrap(byteArrayOf(0x56, 0x34, 0x12))
    check(PcmEncoding.S24.read(packed) == 0x123456 / 8388608.0)
    val packedNegative = ByteBuffer.wrap(byteArrayOf(0x44, 0xee.toByte(), 0xff.toByte()))
    check(PcmEncoding.S24.read(packedNegative) == -4540 / 8388608.0)
    check((8388607 / 8388608.0).toFloat().toDouble() == 8388607 / 8388608.0)
    println("PASS: PCM packing, signed samples, sub-16-bit levels, endian variants and 24-bit/float precision.")
}

private fun integerLevels(scale: Long) =
    listOf(-1.0, -1.0 / scale, 0.0, 1.0 / scale, 341.0.coerceAtMost(scale - 1.0) / scale, (scale - 1) / scale.toDouble())
