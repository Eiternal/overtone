package com.overtone.player

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable internal fun MiniDeck(deck: Deck, cover: ImageBitmap?, modifier: Modifier = Modifier,
    open: () -> Unit, toggle: () -> Unit, visualize: () -> Unit, queue: () -> Unit) {
    val track = deck.track ?: return
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).frosted(15, .92f).padding(start = 9.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(onClickLabel = "展开播放器", onClick = open)
            .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)))
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)) {
                Label(track.title, size = 14, weight = FontWeight.SemiBold)
                if (track.artist.isNotBlank()) Label(track.artist, color = Muted, size = 12)
            }
        }
        IconButton(if (deck.playing) "pause" else "play", if (deck.playing) "暂停" else "播放",
            tint = Accent, size = 48, action = toggle)
        IconButton("scope", "全屏可视化", tint = Accent, size = 44, action = visualize)
        IconButton("queue", "播放队列", size = 44, action = queue)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NowPlaying(
    track: Track, artwork: ArtworkSelection, deck: Deck, remote: MediaController?, close: () -> Unit,
    toggle: () -> Unit, openQueue: () -> Unit, openEq: () -> Unit, openAudioInfo: () -> Unit,
    favorite: () -> Unit, starred: Boolean, lyricsRevision: Int, grantLyricsFolder: () -> Unit,
    followCover: Boolean, startInScope: Boolean, spectrumMode: ScopeMode,
    selectScopeMode: (ScopeMode) -> Unit, haptics: MusicHaptics, vibrationEnabled: Boolean,
    toggleHaptics: () -> Unit
) {
    val pager = key(startInScope) { rememberPagerState(initialPage = if (startInScope) 2 else 0) { 3 } }
    val motion = remember(track.uri) { ScopeMotion() }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(track.duration) }
    var scrub by remember(track.uri) { mutableStateOf<Float?>(null) }
    var stageCenter by remember { mutableStateOf<Offset?>(null) }
    var screenOrigin by remember { mutableStateOf<Offset?>(null) }
    val context = LocalContext.current
    val reduced = remember(context) { reducedAnimations(context) }
    val scopeVisible = pager.currentPage == 2 || pager.targetPage == 2
    val drawAmount by remember(pager) {
        derivedStateOf { (pager.currentPage + pager.currentPageOffsetFraction - 1f).coerceIn(0f, 1f) }
    }
    MonitorPlayback(motion, scopeVisible, deck.playing, spectrumMode, reduced, haptics, vibrationEnabled)
    LaunchedEffect(remote, track.uri, deck.playing) {
        while (true) {
            position = remote?.currentPosition ?: 0
            duration = remote?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: track.duration
            delay(if (deck.playing) 90 else 210)
        }
    }

    // Keep empty parts of the full-screen player from forwarding touches to the library.
    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.positionInRoot() }.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent() }
    }) {
        FrostBackdrop(artwork.backdrop, .96f, Modifier.matchParentSize(), immersive = true, followCover = followCover)
        if (drawAmount > 0f || scopeVisible) {
            ScopeCanvas(motion, spectrumMode, reduced,
                Modifier.matchParentSize().graphicsLayer { alpha = drawAmount },
                monochromeCover = followCover && artwork.backdrop?.main == Color.White,
                sceneCenter = { screenOrigin?.let { origin -> stageCenter?.minus(origin) } })
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val short = maxHeight < 625.dp
            val tabletPortrait = maxWidth >= 600.dp && maxHeight >= maxWidth
            val contentWidth = (maxWidth * .74f).coerceIn(500.dp, 660.dp)
            val narrow = maxWidth < 246.dp
            val side = if (narrow) 9.dp else if (maxWidth < 360.dp) 16.dp else 24.dp
            val topButton = if (narrow) 36 else 44
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max = if (tabletPortrait) contentWidth else 560.dp).fillMaxWidth()
                    .heightIn(min = 46.dp).padding(horizontal = if (tabletPortrait) 0.dp else side - 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton("down", "收起播放页", tint = Paper, size = topButton, action = close)
                    Spacer(Modifier.weight(1f))
                    val equalizer by EqStore.settings.collectAsState()
                    IconButton("info", "音频信息", tint = Paper, size = topButton, action = openAudioInfo)
                    IconButton("eq", "均衡器", tint = if (equalizer.enabled) Accent else Muted,
                        background = if (equalizer.enabled) AccentWell else Color.Transparent, size = topButton, action = openEq)
                    IconButton("queue", "播放队列", tint = Paper, size = topButton, action = openQueue)
                }

                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val indicatorSpace = 24.dp
                    val pageHeight = (maxHeight - indicatorSpace).coerceAtLeast(0.dp)
                    val coverHeight = artworkHeight(artwork.cover, maxWidth, pageHeight)
                    HorizontalPager(state = pager,
                        modifier = Modifier.fillMaxWidth().height(pageHeight).onGloballyPositioned {
                            stageCenter = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                        },
                        beyondViewportPageCount = 0) { page ->
                        when (page) {
                            0 -> PlayerArtwork(artwork.cover)
                            1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Box(Modifier.widthIn(max = if (tabletPortrait) contentWidth else 560.dp)
                                    .fillMaxSize().padding(horizontal = if (tabletPortrait) 0.dp else side)) {
                                    LyricsPanel(track, position, lyricsRevision, grantLyricsFolder, tabletPortrait) { remote?.seekTo(it) }
                                }
                            }
                            2 -> Box(Modifier.fillMaxSize().padding(start = side, end = side, top = 4.dp),
                                contentAlignment = Alignment.TopCenter) {
                                ScopeOptions(spectrumMode, selectScopeMode, vibrationEnabled, toggleHaptics)
                            }
                        }
                    }
                    PlayerPageIndicator(pager, Modifier.align(Alignment.TopCenter)
                        .offset(y = (pageHeight + coverHeight) / 2 + 9.dp))
                }

                val dockInk = Ink
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(
                    0f to dockInk.copy(alpha = 0f), .24f to dockInk.copy(alpha = .74f), 1f to dockInk.copy(alpha = .97f))),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val dockGap = if (!short) 6.dp else if (tabletPortrait) 2.dp else 3.dp
                    Spacer(Modifier.height(dockGap))
                    Column(Modifier.widthIn(max = if (tabletPortrait) contentWidth else 510.dp)
                        .fillMaxWidth().padding(horizontal = if (tabletPortrait) 0.dp else side)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Crossfade(artwork.cover,
                                Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)),
                                animationSpec = tween(270, easing = Ease), label = "track cover") { cover ->
                                Cover(cover, Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(10.dp))
                            AnimatedContent(track, Modifier.weight(1f), transitionSpec = {
                                fadeIn(tween(190)) togetherWith fadeOut(tween(96))
                            }, label = "track") { item ->
                                Column {
                                    Label(item.title, size = if (short) 24 else 29, weight = FontWeight.SemiBold)
                                    val subtitle = listOf(item.artist, item.album).filter(String::isNotBlank).distinct().joinToString(" · ")
                                    if (subtitle.isNotEmpty()) Label(subtitle, color = Muted, size = 13)
                                }
                            }
                            IconButton(if (starred) "starFilled" else "star", if (starred) "取消收藏" else "收藏",
                                tint = if (starred) Accent else Muted, size = 44, action = favorite)
                        }
                        Slider(value = (scrub ?: if (duration > 0) position.toFloat() / duration else 0f).coerceIn(0f, 1f),
                            onValueChange = { scrub = it },
                            onValueChangeFinished = { scrub?.let { remote?.seekTo((it * duration).toLong()) }; scrub = null },
                            enabled = duration > 0,
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Raised),
                            modifier = Modifier.fillMaxWidth().height(31.dp).semantics { contentDescription = "播放进度" },
                            thumb = { Box(Modifier.size(10.dp).background(Accent, RoundedCornerShape(50))) },
                            track = { state ->
                                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .21f))) {
                                    Box(Modifier.fillMaxWidth(state.value.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                                }
                            })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Label(clock(scrub?.let { (it * duration).toLong() } ?: position), color = Muted, size = 11)
                            Label(clock(duration), color = Muted, size = 11)
                        }
                        PlayerTransport(deck, remote, toggle, short,
                            Modifier.align(Alignment.CenterHorizontally)
                                .widthIn(max = if (tabletPortrait) contentWidth * .85f else Dp.Infinity)
                                .fillMaxWidth().padding(top = if (short && !tabletPortrait) 5.dp else 4.dp,
                                    bottom = if (!short) 8.dp else if (tabletPortrait) 5.dp else 3.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun PlayerPageIndicator(pager: PagerState, modifier: Modifier) {
    val active = Accent
    val inactive = Muted.copy(alpha = .42f)
    Canvas(modifier.size(width = 29.dp, height = 5.dp)) {
        val page = pager.currentPage + pager.currentPageOffsetFraction
        repeat(pager.pageCount) { index ->
            val emphasis = (1f - abs(page - index)).coerceIn(0f, 1f)
            drawCircle(lerp(inactive, active, emphasis), 2.5.dp.toPx(),
                Offset((2.5f + 12f * index).dp.toPx(), size.height / 2))
        }
    }
}

private fun artworkHeight(cover: ImageBitmap?, width: Dp, height: Dp): Dp =
    if (cover == null) minOf(width, height) * .84f
    else minOf(height, width * (cover.height.toFloat() / cover.width))

@Composable private fun PlayerArtwork(cover: ImageBitmap?) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        Crossfade(cover, Modifier.fillMaxSize(), animationSpec = tween(350, easing = Ease), label = "artwork") { art ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val height = artworkHeight(art, availableWidth, availableHeight)
                if (art == null) {
                    Cover(null, Modifier.size(height).clip(RoundedCornerShape(12.dp)))
                } else {
                    val ratio = art.width.toFloat() / art.height
                    val width = minOf(availableWidth, height * ratio)
                    Image(art, "专辑封面", Modifier.size(width, height)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithCache {
                            val edgeFade = Brush.verticalGradient(
                                0f to Color.Transparent, .035f to Color.White, .965f to Color.White, 1f to Color.Transparent)
                            onDrawWithContent {
                                drawContent()
                                drawRect(edgeFade, blendMode = BlendMode.DstIn)
                            }
                        }, contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

@Composable private fun PlayerTransport(
    deck: Deck, remote: MediaController?, toggle: () -> Unit, short: Boolean, modifier: Modifier
) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 250.dp
        val primary = if (compact) 46 else if (short) 55 else 66
        val side = if (compact) ((maxWidth.value - primary) / 4).toInt().coerceIn(24, 44) else 46
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton("shuffle", if (deck.shuffle) "关闭随机播放" else "开启随机播放",
                tint = if (deck.shuffle) Accent else Dim, size = side) { remote?.shuffleModeEnabled = !deck.shuffle }
            IconButton("previous", "上一首", tint = Paper, size = side, glyphSize = if (compact) 18 else 26) { remote?.seekToPrevious() }
            IconButton(if (deck.playing) "pause" else "play", if (deck.playing) "暂停" else "播放",
                tint = Ink, background = Accent, size = primary, glyphSize = 29, action = toggle)
            IconButton("next", "下一首", tint = Paper, size = side, glyphSize = if (compact) 18 else 26) { remote?.seekToNextMediaItem() }
            IconButton(if (deck.repeat == Player.REPEAT_MODE_ONE) "repeat1" else "repeat", "切换循环模式",
                tint = if (deck.repeat != Player.REPEAT_MODE_OFF) Accent else Dim, size = side) {
                remote?.repeatMode = when (deck.repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
    }
}

private fun reducedAnimations(context: android.content.Context) =
    android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable private fun LyricsPanel(
    track: Track, position: Long, revision: Int, grantFolder: () -> Unit, tabletPortrait: Boolean,
    seek: (Long) -> Unit
) {
    val context = LocalContext.current
    val request = Triple(track.uri, track.lyrics, revision)
    var lyrics by remember(request) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(request) { mutableStateOf(true) }
    LaunchedEffect(request) {
        lyrics = withContext(Dispatchers.IO) { readLyrics(context, track) }
        loading = false
    }
    val parsed = lyrics
    if (parsed == null) {
        if (loading) return
        val canChooseFolder = track.uri.startsWith("content:") && (track.lyrics.isBlank() || track.folder.startsWith(AUTO_FOLDER))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph("lyrics", Modifier.size(24.dp), Accent)
            Spacer(Modifier.height(8.dp))
            Label("暂无歌词", size = 15, color = Muted)
            if (canChooseFolder) {
                Spacer(Modifier.height(8.dp))
                IconButton("folder", "授权歌词文件夹", tint = Accent,
                    background = Panel.copy(alpha = .72f), size = 48, action = grantFolder)
            }
        }
    } else {
        val index = if (parsed.synchronized) parsed.lines.indexOfLast { (it.at ?: Long.MAX_VALUE) <= position } else -1
        val state = key(track.uri, parsed.source) { rememberLazyListState() }
        LaunchedEffect(index) { if (index >= 0) {
            val target = if (tabletPortrait) index else (index - 1).coerceAtLeast(0)
            if (reducedAnimations(context)) state.scrollToItem(target) else state.animateScrollToItem(target)
        } }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val inset = if (tabletPortrait && parsed.synchronized) maxHeight * .36f else 18.dp
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = inset),
                verticalArrangement = if (tabletPortrait && !parsed.synchronized) Arrangement.Center else Arrangement.Top) {
                itemsIndexed(parsed.lines) { i, line ->
                    val stamp = line.at
                    val action = if (stamp != null) Modifier.clickable { seek(stamp) } else Modifier
                    Label(line.text, Modifier.fillMaxWidth().then(action)
                        .padding(horizontal = if (tabletPortrait) 0.dp else 3.dp, vertical = 9.dp),
                        color = if (i == index || !parsed.synchronized) Paper else Muted,
                        weight = FontWeight.SemiBold, size = 22, lines = Int.MAX_VALUE)
                }
            }
        }
    }
}

@Composable internal fun Cover(image: ImageBitmap?, modifier: Modifier) {
    if (image != null) Image(image, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Brush.linearGradient(listOf(Raised, Panel, Ink))),
        contentAlignment = Alignment.Center) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            Glyph("disc", Modifier.size((maxWidth * .32f).coerceAtMost(98.dp)), Muted.copy(alpha = .48f))
        }
    }
}
