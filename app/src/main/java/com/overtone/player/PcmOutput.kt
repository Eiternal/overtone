package com.overtone.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PcmPath(val encoding: PcmEncoding?, val rate: Int, val channels: Int) {
    fun text(): String = listOfNotNull(
        encoding?.label,
        rate.takeIf { it > 0 }?.let {
            val khz = it / 1000.0
            "${if (it % 1000 == 0) it / 1000 else khz} kHz"
        },
        channels.takeIf { it > 0 }?.let { "${it} 声道" }
    ).joinToString(" · ")
}

internal data class AudioPathInfo(
    val decoder: String? = null,
    val inputMime: String? = null,
    val decoded: PcmPath? = null,
    val sourceBits: Int? = null,
    val output: PcmPath? = null,
    val usb: UsbStatus = UsbStatus(UsbState.OFF),
)

internal object AudioPath {
    private val flow = MutableStateFlow(AudioPathInfo())
    val info = flow.asStateFlow()
    fun codec(name: String) { flow.update { it.copy(decoder = name) } }
    fun source(mime: String?) { flow.update { it.copy(inputMime = mime) } }
    fun decoded(format: Format, sourceBits: Int?) {
        val path = PcmPath(PcmEncoding.from(format.pcmEncoding), format.sampleRate, format.channelCount)
        flow.update { it.copy(decoded = path, sourceBits = sourceBits) }
    }
    fun output(encoding: Int, rate: Int, channels: Int) = output(PcmEncoding.from(encoding), rate, channels)
    fun output(encoding: PcmEncoding?, rate: Int, channels: Int) {
        flow.update { it.copy(output = PcmPath(encoding, rate, channels)) }
    }
    fun usb(status: UsbStatus) { flow.update { it.copy(usb = status) } }
    fun clearOutput() { flow.update { it.copy(output = null) } }
    fun clear() { flow.value = AudioPathInfo() }

}

/**
 * Runs our optional DSP before either Media3 PCM pipeline, including the high-resolution one.
 * The delegate can take a partial write, so transformed data is retained until it accepts
 * the whole input buffer. During bypass the original PCM buffer goes straight to the delegate.
 */
internal class FidelityAudioSink(delegate: AudioSink, private val usb: UsbOutputCoordinator) : ForwardingAudioSink(delegate) {
    private val eq = EqualizerProcessor { EqStore.parameters }
    private val tap = PcmTap()
    private var format: Format? = null
    private var pendingInput: ByteBuffer? = null
    private var pendingOutput: ByteBuffer? = null
    private var scratch = ByteBuffer.allocateDirect(0)
    private var usbTrack: UsbDirectTrack? = null
    private var usbAttempted = false
    private var currentSource: Format? = null
    private var attemptedSource: Format? = null
    private var playing = false
    private var volume = 1f
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var sessionId = 0
    private var ending = false
    private var channelMap: IntArray? = null

    fun source(format: Format?) { currentSource = format }

    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format) =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format) = AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        format = inputFormat
        channelMap = outputChannels
        usbAttempted = false
        ending = false
        AudioPath.decoded(inputFormat, exactSourceBits(currentSource, inputFormat))
        if (inputFormat.sampleRate > 0) EqStore.setSampleRate(inputFormat.sampleRate)
        clearBuffers()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (restarting) return false
        val config = format
        if (config != null && pendingInput == null && buffer.hasRemaining()) {
            val ready = prepareUsb(config)
            if (!ready) return false
            val direct = usbTrack
            if (direct != null) {
                if (Signal.listening && config.sampleRate > 0) inspectDirect(buffer, config, presentationTimeUs)
                else tap.idle()
                return try {
                    when (direct.write(buffer, presentationTimeUs)) {
                        UsbDirectTrack.Write.DONE -> true.also { inspected = null }
                        UsbDirectTrack.Write.AGAIN -> false
                        UsbDirectTrack.Write.FAILED -> false.also { failUsb() }
                    }
                } catch (_: RuntimeException) {
                    failUsb()
                    false
                }
            }
        }
        if (!buffer.hasRemaining() && pendingInput == null) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (pendingInput !== buffer) {
            pendingInput = buffer
            pendingOutput = transform(buffer, presentationTimeUs)
        }
        val processed = checkNotNull(pendingOutput)
        val finished = super.handleBuffer(processed, presentationTimeUs, encodedAccessUnitCount)
        if (finished) {
            buffer.position(buffer.limit())
            pendingInput = null
            pendingOutput = null
        }
        return finished
    }

    private fun prepareUsb(config: Format): Boolean {
        if (usbAttempted && attemptedSource == currentSource) return true
        val wasDirect = usbTrack
        if (wasDirect != null) {
            if (wasDirect.hasPendingData()) return false
            closeUsb()
        } else if (UsbSettings.requested.value && usb.initialState() == UsbState.WAITING && super.hasPendingData()) {
            super.playToEndOfStream()
            if (super.hasPendingData()) return false
        }
        usbAttempted = true
        attemptedSource = currentSource
        val identityChannels = channelMap?.withIndex()?.all { it.index == it.value } != false &&
            (channelMap == null || channelMap?.size == config.channelCount)
        if (!identityChannels || volume != 1f || playbackParameters != PlaybackParameters.DEFAULT || skipSilence) {
            AudioPath.usb(UsbStatus(if (UsbSettings.requested.value) UsbState.NO_MATCH else UsbState.OFF))
            return true
        }
        val source = currentSource
        if (usb.initialState() == UsbState.WAITING) super.flush()
        usbTrack = usb.open(source, config, sessionId)
        if (usbTrack != null) {
            AudioPath.clearOutput()
            if (playing) runCatching { usbTrack?.play() }.onFailure { failUsb() }
        }
        return usbTrack != null || !restarting
    }

    private var inspected: ByteBuffer? = null
    private var restarting = false
    private fun inspectDirect(buffer: ByteBuffer, config: Format, presentationTimeUs: Long) {
        if (inspected === buffer) return
        inspected = buffer
        val pcm = PcmEncoding.from(config.pcmEncoding) ?: return
        val reader = buffer.duplicate().order(pcm.order)
        val frameSize = config.channelCount * pcm.bytes
        var sampleOffset = 0
        while (reader.remaining() >= frameSize) {
            var sum = 0.0
            repeat(config.channelCount) { sum += pcm.read(reader) }
            tap.record((sum / config.channelCount).toFloat(), config.sampleRate, presentationTimeUs, sampleOffset++)
        }
    }

    private fun failUsb() {
        closeUsb()
        restarting = true
        usb.failed()
    }

    private fun closeUsb() {
        val previous = usbTrack ?: return
        usbTrack = null
        inspected = null
        runCatching { previous.release() }
        AudioPath.clearOutput()
    }

    override fun play() {
        playing = true
        super.play()
        runCatching { usbTrack?.play() }.onFailure { failUsb() }
    }
    override fun pause() {
        playing = false
        super.pause()
        runCatching { usbTrack?.pause() }.onFailure { failUsb() }
    }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = usbTrack?.positionUs() ?: super.getCurrentPositionUs(sourceEnded)
        if (Signal.listening) Signal.playbackUs = position
        return position
    }
    override fun hasPendingData() = usbTrack?.hasPendingData() ?: super.hasPendingData()
    override fun playToEndOfStream() {
        if (usbTrack == null) super.playToEndOfStream() else ending = true
    }
    override fun isEnded() = usbTrack?.let { ending && !it.hasPendingData() } ?: super.isEnded()
    override fun setVolume(volume: Float) {
        this.volume = volume
        if (volume != 1f && usbTrack != null) failUsb()
        super.setVolume(volume)
    }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        if (playbackParameters != PlaybackParameters.DEFAULT && usbTrack != null) failUsb()
        super.setPlaybackParameters(playbackParameters)
    }
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
        if (skipSilenceEnabled && usbTrack != null) failUsb()
        super.setSkipSilenceEnabled(skipSilenceEnabled)
    }
    override fun setAudioSessionId(audioSessionId: Int) {
        sessionId = audioSessionId
        super.setAudioSessionId(audioSessionId)
    }

    private fun transform(buffer: ByteBuffer, presentationTimeUs: Long): ByteBuffer {
        val format = format ?: return buffer
        val pcm = PcmEncoding.from(format.pcmEncoding) ?: return buffer
        val channels = format.channelCount
        if (channels <= 0 || format.sampleRate <= 0) return buffer
        val frameBytes = channels * pcm.bytes
        if (buffer.remaining() % frameBytes != 0) return buffer
        val effect = eq.prepare(format.sampleRate, channels)
        val inspect = Signal.listening
        if (!inspect) tap.idle()
        if (!effect && !inspect) return buffer
        val reader = buffer.duplicate().order(pcm.order)
        if (effect) {
            if (scratch.capacity() < buffer.remaining()) scratch = ByteBuffer.allocateDirect(buffer.remaining())
            scratch.clear()
            scratch.order(pcm.order)
        }
        var sampleOffset = 0
        while (reader.hasRemaining()) {
            var sum = 0.0
            repeat(channels) { channel ->
                val input = pcm.read(reader)
                val value = if (effect) eq.process(if (input.isFinite()) input else 0.0, channel) else input
                if (effect) pcm.write(scratch, value)
                if (inspect) sum += value
            }
            if (effect) eq.endFrame()
            if (inspect) tap.record((sum / channels).coerceIn(-1.0, 1.0).toFloat(), format.sampleRate, presentationTimeUs, sampleOffset++)
        }
        return if (effect) scratch.apply { flip(); order(ByteOrder.LITTLE_ENDIAN) } else buffer
    }

    private fun clearBuffers() {
        pendingInput = null
        pendingOutput = null
        eq.reset()
        tap.reset()
    }
    override fun flush() {
        closeUsb()
        clearBuffers()
        usbAttempted = false
        restarting = false
        ending = false
        usb.idle()
        super.flush()
    }
    override fun reset() { flush(); format = null; super.reset() }
    override fun release() { flush(); super.release() }
}
