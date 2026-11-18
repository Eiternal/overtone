package com.overtone.player

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

internal enum class PcmEncoding(val bytes: Int, val label: String, val order: ByteOrder = ByteOrder.LITTLE_ENDIAN) {
    U8(1, "8-bit PCM"),
    S16(2, "16-bit PCM"),
    S16_BE(2, "16-bit PCM", ByteOrder.BIG_ENDIAN),
    S24(3, "24-bit PCM"),
    S24_BE(3, "24-bit PCM", ByteOrder.BIG_ENDIAN),
    S32(4, "32-bit PCM"),
    S32_BE(4, "32-bit PCM", ByteOrder.BIG_ENDIAN),
    FLOAT(4, "32-bit float");

    fun read(buffer: ByteBuffer): Double = when (this) {
        U8 -> ((buffer.get().toInt() and 255) - 128) / 128.0
        S16, S16_BE -> buffer.short / 32768.0
        S24, S24_BE -> {
            val a = buffer.get().toInt() and 255
            val b = buffer.get().toInt() and 255
            val c = buffer.get().toInt() and 255
            val packed = if (this == S24) a or (b shl 8) or (c shl 16) else c or (b shl 8) or (a shl 16)
            (packed shl 8) / 2147483648.0
        }
        S32, S32_BE -> buffer.int / 2147483648.0
        FLOAT -> buffer.float.toDouble()
    }

    fun write(buffer: ByteBuffer, sample: Double) {
        fun integer(scale: Long) = (sample.coerceIn(-1.0, 1.0) * scale).roundToLong().coerceIn(-scale, scale - 1)
        when (this) {
            U8 -> buffer.put((integer(128) + 128).toByte())
            S16, S16_BE -> buffer.putShort(integer(32768).toShort())
            S24, S24_BE -> {
                val value = integer(8388608).toInt()
                val low = value.toByte()
                val mid = (value shr 8).toByte()
                val high = (value shr 16).toByte()
                if (this == S24) buffer.put(low).put(mid).put(high)
                else buffer.put(high).put(mid).put(low)
            }
            S32, S32_BE -> buffer.putInt(integer(2147483648).toInt())
            FLOAT -> buffer.putFloat(sample.coerceIn(-1.0, 1.0).toFloat())
        }
    }

    companion object {
        fun from(value: Int): PcmEncoding? = when (value) {
            C.ENCODING_PCM_8BIT -> U8
            C.ENCODING_PCM_16BIT -> S16
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> S16_BE
            C.ENCODING_PCM_24BIT -> S24
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> S24_BE
            C.ENCODING_PCM_32BIT -> S32
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> S32_BE
            C.ENCODING_PCM_FLOAT -> FLOAT
            else -> null
        }
    }
}

