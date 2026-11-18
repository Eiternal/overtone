package com.overtone.player

import android.content.Context
import android.os.Handler
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

internal class FidelityRenderersFactory(context: Context, private val usb: UsbOutputCoordinator) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .setAudioTrackProvider { config, attributes, session ->
                DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, session).also { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        AudioPath.output(track.format.encoding, track.sampleRate, track.channelCount)
                    }
                }
            }
            .build()
        return FidelityAudioSink(sink, usb)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // Pin lossless FLAC/ALAC to FFmpeg when supported, without changing codec priority for
        // lossy tracks (where the standard integer pipeline can retain gapless trim).
        val decoder = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
        val capabilities = object : RendererCapabilities by decoder.capabilities {
            override fun supportsFormat(format: Format): Int = when (format.sampleMimeType) {
                MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC -> decoder.capabilities.supportsFormat(format)
                else -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }
        }
        val start = out.size
        out += decoder
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out)
        if (audioSink is FidelityAudioSink) {
            for (index in start until out.size) {
                val renderer = out[index]
                out[index] = object : ForwardingRenderer(renderer) {
                    override fun getCapabilities() = if (renderer === decoder) capabilities else super.getCapabilities()
                    override fun enable(configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream,
                        positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean, startPositionUs: Long,
                        offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
                        audioSink.source(formats.singleOrNull())
                        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream,
                            startPositionUs, offsetUs, mediaPeriodId)
                    }

                    override fun replaceStream(formats: Array<Format>, stream: SampleStream, startPositionUs: Long,
                        offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
                        audioSink.source(formats.singleOrNull())
                        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
                    }
                }
            }
        }
    }
}
