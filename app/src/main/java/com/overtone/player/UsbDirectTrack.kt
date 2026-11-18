package com.overtone.player

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.SystemClock
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

@RequiresApi(34)
internal class UsbDirectTrack private constructor(
    private val manager: AudioManager,
    private val attributes: AudioAttributes,
    private val device: AudioDeviceInfo,
    private val mixer: AudioMixerAttributes,
    private val track: AudioTrack,
    private val source: PcmEncoding,
    private val target: PcmEncoding,
    private val bits: Int,
) {
    data class Result(val track: UsbDirectTrack?, val state: UsbState)
    enum class Write { DONE, AGAIN, FAILED }

    val name: String = device.productName.toString()
    private val path = PcmPath(target, track.sampleRate, track.channelCount)
    private val frameBytes = target.bytes * track.channelCount
    private var queuedBytes = 0L
    private var firstTime = AudioSink.CURRENT_POSITION_NOT_SET
    private var lastHead = 0L
    private var wraps = 0L
    private var playing = false
    private var routeStarted = 0L
    private var nextRouteCheck = 0L
    private var confirmed = false
    private var announced = false
    private var input: ByteBuffer? = null
    private var output: ByteBuffer? = null
    private var staging = ByteBuffer.allocateDirect(0)

    fun play() {
        if (!playing) {
            track.play()
            playing = true
            confirmed = false
            routeStarted = SystemClock.elapsedRealtime()
            nextRouteCheck = 0
        }
    }

    fun pause() {
        if (playing) track.pause()
        playing = false
        confirmed = false
        announced = false
        AudioPath.usb(UsbStatus(UsbState.WAITING, name))
    }

    fun write(buffer: ByteBuffer, pts: Long): Write {
        if (!playing) return Write.AGAIN
        val now = SystemClock.elapsedRealtime()
        if (now >= nextRouteCheck) {
            val onUsb = if (Build.VERSION.SDK_INT >= 36) track.routedDevices.singleOrNull()?.id == device.id
                else track.routedDevice?.id == device.id
            confirmed = onUsb && manager.getPreferredMixerAttributes(attributes, device) == mixer
            if (!confirmed) {
                nextRouteCheck = now + 35
                AudioPath.usb(UsbStatus(UsbState.ROUTING, name))
                return if (now - routeStarted >= 800 || announced) Write.FAILED else Write.AGAIN
            }
            nextRouteCheck = now + 200
        }
        if (!confirmed) return Write.AGAIN
        if (input !== buffer) {
            if (buffer.remaining() % (source.bytes * track.channelCount) != 0) return Write.FAILED
            val prepared = if (source == target && source.integerBits() == bits) buffer else {
                val bytes = buffer.remaining() / source.bytes * target.bytes
                if (staging.capacity() < bytes) staging = ByteBuffer.allocateDirect(bytes)
                if (!packExactPcm(buffer, source, target, staging, bits)) return Write.FAILED
                staging
            }
            input = buffer
            output = prepared
        }
        val data = checkNotNull(output)
        val count = track.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        if (count < 0) return Write.FAILED
        if (count > 0) {
            if (firstTime == AudioSink.CURRENT_POSITION_NOT_SET) firstTime = pts
            queuedBytes += count
            if (!announced) {
                AudioPath.output(target, track.sampleRate, track.channelCount)
                AudioPath.usb(UsbStatus(UsbState.ACTIVE, name, path))
                announced = true
            }
        }
        if (data.hasRemaining()) return Write.AGAIN
        buffer.position(buffer.limit())
        input = null
        output = null
        return Write.DONE
    }

    private fun playedFrames(): Long {
        val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (head < lastHead && lastHead - head > 0x8000_0000L) wraps++
        lastHead = head
        return ((wraps shl 32) + head).coerceAtMost(queuedBytes / frameBytes)
    }

    fun positionUs(): Long = if (firstTime == AudioSink.CURRENT_POSITION_NOT_SET) firstTime
        else firstTime + playedFrames() * 1_000_000 / path.rate

    fun hasPendingData() = playedFrames() < queuedBytes / frameBytes

    fun release() {
        runCatching { if (playing) track.pause() }
        runCatching { track.flush() }
        try {
            track.release()
        } finally {
            runCatching { manager.clearPreferredMixerAttributes(attributes, device) }
        }
    }

    companion object {
        fun open(manager: AudioManager, devices: Array<AudioDeviceInfo>, format: Format, bits: Int, sessionId: Int): Result {
            val source = PcmEncoding.from(format.pcmEncoding) ?: return Result(null, UsbState.SOURCE)
            val mask = usbChannelMask(format.channelCount)
            if (mask == 0) return Result(null, UsbState.NO_MATCH)
            data class Candidate(val device: AudioDeviceInfo, val mixer: AudioMixerAttributes, val pcm: PcmEncoding)
            val candidates = devices.flatMap { device ->
                manager.getSupportedMixerAttributes(device).mapNotNull { mixer ->
                    val pcm = when (mixer.format.encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.S16
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmEncoding.S24
                        AudioFormat.ENCODING_PCM_32BIT -> PcmEncoding.S32
                        else -> return@mapNotNull null
                    }
                    Candidate(device, mixer, pcm)
                }
            }
            val profiles = candidates.mapIndexed { index, candidate ->
                val spec = candidate.mixer.format
                UsbProfile(index, spec.sampleRate, if (spec.channelMask == mask) spec.channelCount else 0,
                    candidate.pcm.integerBits() ?: 0,
                    candidate.mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
            }
            val profile = chooseUsbProfile(profiles, format.sampleRate, format.channelCount, bits)
                ?: return Result(null, UsbState.NO_MATCH)
            val (device, mixer, target) = candidates[profile.id]
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val platformMin = AudioTrack.getMinBufferSize(format.sampleRate, mask, mixer.format.encoding)
            val frameBytes = target.bytes * format.channelCount
            val bufferBytes = maxOf(platformMin, format.sampleRate / 8 * frameBytes)
            val alignedBytes = (bufferBytes + frameBytes - 1) / frameBytes * frameBytes
            if (!manager.setPreferredMixerAttributes(attributes, device, mixer)) return Result(null, UsbState.UNAVAILABLE)
            var track: AudioTrack? = null
            var success = false
            try {
                val builder = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(mixer.format)
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(alignedBytes)
                if (sessionId > 0) builder.setSessionId(sessionId)
                val candidate = builder.build()
                track = candidate
                if (candidate.state != AudioTrack.STATE_INITIALIZED || !candidate.setPreferredDevice(device) ||
                    candidate.format.encoding != mixer.format.encoding || candidate.sampleRate != format.sampleRate ||
                    candidate.channelCount != format.channelCount || candidate.format.channelMask != mask ||
                    manager.getPreferredMixerAttributes(attributes, device) != mixer) {
                    return Result(null, UsbState.UNAVAILABLE)
                }
                success = true
                return Result(UsbDirectTrack(manager, attributes, device, mixer, candidate, source, target, bits), UsbState.ROUTING)
            } finally {
                if (!success) {
                    try {
                        track?.release()
                    } finally {
                        manager.clearPreferredMixerAttributes(attributes, device)
                    }
                }
            }
        }
    }
}
