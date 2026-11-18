package com.overtone.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer

internal fun PcmEncoding.integerBits(): Int? = when (this) {
    PcmEncoding.U8 -> 8
    PcmEncoding.S16, PcmEncoding.S16_BE -> 16
    PcmEncoding.S24, PcmEncoding.S24_BE -> 24
    PcmEncoding.S32, PcmEncoding.S32_BE -> 32
    PcmEncoding.FLOAT -> null
}

internal fun flacBits(streamInfo: ByteArray): Int? {
    val offset = when {
        streamInfo.size >= 42 && streamInfo[0] == 0x66.toByte() &&
            streamInfo[1] == 0x4c.toByte() && streamInfo[2] == 0x61.toByte() && streamInfo[3] == 0x43.toByte() &&
            (streamInfo[4].toInt() and 0x7f) == 0 && streamInfo[7] == 34.toByte() -> 8
        streamInfo.size == 34 -> 0
        else -> return null
    }
    val bits = (((streamInfo[offset + 12].toInt() and 1) shl 4) or
        ((streamInfo[offset + 13].toInt() and 0xff) shr 4)) + 1
    return bits.takeIf { it in 4..32 }
}

internal fun alacBits(cookie: ByteArray): Int? =
    if (cookie.size == 24) (cookie[5].toInt() and 0xff).takeIf { it in setOf(16, 20, 24, 32) } else null

/** Returns the precision proved by the source header and available in decoded PCM. */
internal fun exactSourceBits(source: Format?, decoded: Format): Int? {
    val pcm = PcmEncoding.from(decoded.pcmEncoding) ?: return null
    if (source == null || source.sampleRate != decoded.sampleRate || source.channelCount != decoded.channelCount) return null
    val header = source.initializationData.firstOrNull()
    val bits = when (source.sampleMimeType) {
        MimeTypes.AUDIO_RAW -> PcmEncoding.from(source.pcmEncoding)?.integerBits()
        MimeTypes.AUDIO_FLAC -> header?.let(::flacBits)
        MimeTypes.AUDIO_ALAC -> header?.let(::alacBits)
        else -> null
    } ?: return null
    return bits.takeIf { it <= (pcm.integerBits() ?: 24) }
}

/** Integer PCM widening adds zero low bits. No rounding, clipping or gain is allowed. */
internal fun packExactPcm(source: ByteBuffer, input: PcmEncoding, output: PcmEncoding, destination: ByteBuffer, sourceBits: Int = output.integerBits() ?: 0): Boolean {
    val depth = output.integerBits() ?: return false
    if (sourceBits !in 1..depth || source.remaining() % input.bytes != 0) return false
    if (destination.capacity() < source.remaining() / input.bytes * output.bytes) return false
    val scale = 1L shl (sourceBits - 1)
    val reader = source.duplicate().order(input.order)
    destination.clear()
    destination.order(output.order)
    while (reader.hasRemaining()) {
        val value = input.read(reader)
        if (!value.isFinite() || value < -1.0 || value >= 1.0) return false
        val units = value * scale
        if (units != units.toLong().toDouble()) return false
        output.write(destination, value)
    }
    destination.flip()
    return true
}

internal data class UsbProfile(val id: Int, val rate: Int, val channels: Int, val bits: Int, val bitPerfect: Boolean)

internal fun chooseUsbProfile(profiles: List<UsbProfile>, rate: Int, channels: Int, sourceBits: Int): UsbProfile? =
    profiles.filter { it.bitPerfect && it.rate == rate && it.channels == channels && it.bits >= sourceBits }
        .minByOrNull { it.bits }
