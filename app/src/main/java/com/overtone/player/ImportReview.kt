package com.overtone.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun ImportReview(
    candidates: List<ImportCandidate>,
    revision: Int,
    pauseLibrary: () -> Unit,
    onDecision: (Track, Boolean) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Track?>(null) }
    var previewFailed by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pending = candidates.count { !it.excluded }
    val rows = remember(candidates, tab) { candidates.filter { it.excluded == (tab == 1) } }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { preview = null }
    }
    LaunchedEffect(preview) {
        val track = preview ?: return@LaunchedEffect
        val renderers = DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val player = ExoPlayer.Builder(context, renderers).build()
        try {
            player.setTrackSelectionParameters(player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build())
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && preview == track) preview = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (preview == track) {
                        preview = null
                        previewFailed = track.favoriteKey
                    }
                }
            })
            player.setMediaItem(MediaItem.fromUri(track.uri))
            player.prepare()
            player.play()
            delay(12_000)
            if (preview == track) preview = null
        } finally {
            player.release()
        }
    }

    Column(Modifier.fillMaxSize()) {
        PlayerTabs(listOf(PlayerTab("待确认", "help", pending), PlayerTab("已跳过", "skip", candidates.size - pending)), tab) {
            tab = it
            preview = null
        }
        if (rows.isEmpty()) {
            Label(if (tab == 0) "无待确认音频" else "无跳过记录",
                Modifier.padding(22.dp), color = Muted)
        } else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            items(rows, key = { it.track.favoriteKey }) { candidate ->
                val track = candidate.track
                val coverSize = 44.dp
                val thumbnail = rememberThumbnail(remember(track) { listOf(track) }, revision, coverSize)
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                    val playing = preview == track
                    Box(Modifier.size(coverSize).clip(RoundedCornerShape(7.dp))
                        .clickable(role = Role.Button) {
                            previewFailed = null
                            if (playing) preview = null
                            else {
                                pauseLibrary()
                                preview = track
                            }
                        }.semantics { contentDescription = if (playing) "停止试听" else "试听 ${track.title}" }) {
                        Cover(thumbnail, Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.BottomEnd).padding(2.dp).size(17.dp)
                            .background(Color.Black.copy(alpha = .78f), CircleShape), contentAlignment = Alignment.Center) {
                            Glyph(if (playing) "pause" else "play", Modifier.size(11.dp), Paper)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Label(track.title, size = 14)
                        val parent = track.folder.removePrefix(AUTO_FOLDER).substringAfter(':')
                        val meta = listOf(candidate.label, parent,
                            if (track.duration > 0) clock(track.duration) else track.extension)
                            .filter(String::isNotBlank).joinToString(" · ")
                        Label(if (previewFailed == track.favoriteKey) "无法试听 · $meta" else meta,
                            color = Muted, size = 11, lines = 2)
                    }
                    if (!candidate.excluded) {
                        IconButton("skip", "跳过 ${track.title}", tint = Muted, size = 48) {
                            if (playing) preview = null
                            onDecision(track, false)
                        }
                    }
                    IconButton("check", "${if (candidate.excluded) "恢复" else "收录"} ${track.title}", tint = Accent, size = 48) {
                        if (playing) preview = null
                        onDecision(track, true)
                    }
                }
                Hairline(Modifier.padding(start = 56.dp))
            }
        }
    }
}
