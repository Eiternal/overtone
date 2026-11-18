package com.overtone.player

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main() {
    val profiles = listOf(
        UsbProfile(1, 44100, 2, 24, true),
        UsbProfile(2, 96000, 2, 16, true),
        UsbProfile(3, 96000, 6, 24, true),
        UsbProfile(4, 96000, 2, 24, false),
        UsbProfile(5, 96000, 2, 32, true),
        UsbProfile(6, 96000, 2, 24, true)
    )
    check(chooseUsbProfile(profiles, 96000, 2, 24)?.id == 6)
    check(chooseUsbProfile(profiles.dropLast(1), 96000, 2, 24)?.id == 5)
    check(chooseUsbProfile(profiles, 44100, 2, 24)?.id == 1)
    check(chooseUsbProfile(profiles, 48000, 2, 24) == null)
    check(chooseUsbProfile(profiles, 96000, 1, 24) == null)
    check(chooseUsbProfile(profiles.take(4), 96000, 2, 24) == null)

    val flac = byteArrayOf(0x66, 0x4c, 0x61, 0x43, 0x80.toByte(), 0, 0, 34) + ByteArray(34)
    flac[20] = 1
    flac[21] = 0x70
    check(flacBits(flac) == 24)
    check(flacBits(flac.copyOfRange(8, 42)) == 24)
    check(flacBits(flac.copyOf(22)) == null)
    val alac = ByteArray(24).apply { this[5] = 24 }
    check(alacBits(alac) == 24 && alacBits(alac.copyOf(15)) == null)

    val values = listOf(-8388608, -126005, -1, 0, 1, 126005, 8388607)
    val floats = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { floats.putFloat((it / 8388608.0).toFloat()) }
    floats.flip()
    val output = ByteBuffer.allocate(values.size * 4)
    check(packExactPcm(floats, PcmEncoding.FLOAT, PcmEncoding.S32, output, 24))
    values.forEach { check(output.int == it shl 8) }
    check(floats.position() == 0)
    check(packExactPcm(floats, PcmEncoding.FLOAT, PcmEncoding.S24, output, 24))
    values.forEach { check(PcmEncoding.S24.read(output) == it / 8388608.0) }
    check(!packExactPcm(floats, PcmEncoding.FLOAT, PcmEncoding.S32, output, 16))
    val raw32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    raw32.putInt(1).putInt(-1).flip()
    check(packExactPcm(raw32, PcmEncoding.S32, PcmEncoding.S32, output, 32))
    check(output.int == 1 && output.int == -1)
    val invalid = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN)
    invalid.flip()
    check(!packExactPcm(invalid, PcmEncoding.FLOAT, PcmEncoding.S24, output, 24))

    println("PASS: exact sample rates and channel masks, optional PCM widening, FLAC/ALAC precision, lossless sample packing and fallback.")
}
