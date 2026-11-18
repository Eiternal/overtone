package com.overtone.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var usb: UsbOutputCoordinator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var queueDirty = true
    private val tick = object : Runnable {
        override fun run() { if (player.isPlaying) save(); handler.postDelayed(this, 2200) }
    }
    override fun onCreate() {
        super.onCreate()
        EqStore.init(this)
        UsbSettings.init(this)
        usb = UsbOutputCoordinator(this, ::restartOutput)
        player = ExoPlayer.Builder(this, FidelityRenderersFactory(this, usb))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                AudioPath.codec(decoderName)
            }
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?) {
                AudioPath.source(format.sampleMimeType)
            }
        })
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        player.repeatMode = store.getInt("repeat", Player.REPEAT_MODE_OFF)
        player.shuffleModeEnabled = store.getBoolean("shuffle", false)
        runCatching {
            val raw = JSONArray(store.getString("queue", "[]"))
            val restored = (0 until raw.length()).map { Track.fromJson(raw.getJSONArray(it)).mediaItem() }
            if (restored.isNotEmpty()) player.setMediaItems(restored, store.getInt("index", 0).coerceIn(restored.indices), store.getLong("position", 0))
        }
        val callback = object : MediaSession.Callback {
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                if (controller.packageName != packageName) return Futures.immediateFuture(emptyList())
                val resolved = mediaItems.mapNotNull { item ->
                    val uri = android.net.Uri.parse(item.mediaId)
                    if (uri.scheme != "content" && !(uri.scheme == "asset" && uri.path?.startsWith("/demo/") == true)) null
                    else item.buildUpon().setUri(uri).build()
                }
                return Futures.immediateFuture(resolved)
            }
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setCallback(callback).setSessionActivity(intent).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueDirty = true
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) save()
            }
        })
        scope.launch {
            combine(UsbSettings.requested, EqStore.settings) { enabled, eq -> enabled to eq.enabled }
                .collect { (enabled, eq) -> usb.preferencesChanged(enabled, eq) }
        }
        handler.post(tick)
    }
    private fun restartOutput(unplugged: Boolean) {
        if (!::player.isInitialized || player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        val position = player.currentPosition
        val index = player.currentMediaItemIndex
        val resume = player.playWhenReady && !unplugged && player.playbackState != Player.STATE_ENDED
        player.pause()
        player.stop()
        player.seekTo(index, position)
        player.prepare()
        if (resume) player.play()
    }

    private fun save() {
        if (!::player.isInitialized) return
        val edit = store.edit().putInt("index", player.currentMediaItemIndex).putLong("position", player.currentPosition)
            .putInt("repeat", player.repeatMode).putBoolean("shuffle", player.shuffleModeEnabled)
        if (queueDirty) {
            val queue = JSONArray()
            for (i in 0 until player.mediaItemCount) {
                val m = player.getMediaItemAt(i)
                queue.put(Track.fromItem(m).json())
            }
            edit.putString("queue", queue.toString())
            queueDirty = false
        }
        edit.apply()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        usb.release()
        save()
        session?.release()
        player.release()
        AudioPath.clear()
        session = null
        super.onDestroy()
    }
}
