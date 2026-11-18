package com.overtone.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

data class Deck(val track: Track? = null, val playing: Boolean = false, val queue: List<Track> = emptyList(),
    val index: Int = -1, val shuffle: Boolean = false, val repeat: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Overtone(controller: MediaController?) {
    val context = LocalContext.current
    val repo = remember { MusicLibrary(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun notice(message: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) } }
    var library by remember { mutableStateOf(Library()) }
    var scanning by remember { mutableStateOf(false) }
    var scanSerial by remember { mutableIntStateOf(0) }
    var manualRevision by remember { mutableIntStateOf(0) }
    var completedManualRevision by remember { mutableIntStateOf(-1) }
    fun refreshFolders() { manualRevision++; scanSerial++ }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selectedFolder by rememberSaveable { mutableStateOf("") }
    var selectedAlbum by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var startInScope by rememberSaveable { mutableStateOf(false) }
    var audioInfoOpen by rememberSaveable { mutableStateOf(false) }
    var equalizerOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var reviewOpen by rememberSaveable { mutableStateOf(false) }
    val pendingImportCount = remember(library.review) { library.review.count { !it.excluded } }
    fun openReview() {
        importOpen = false
        sources = false
        reviewOpen = true
    }
    fun decideImport(track: Track, include: Boolean) {
        repo.setImportDecision(track, include)
        library = library.copy(review = library.review.mapNotNull { item ->
            if (item.track.favoriteKey != track.favoriteKey) item
            else if (include) null else item.copy(skipped = true)
        })
        scanSerial++
    }
    var autoEnabled by remember { mutableStateOf(repo.autoEnabled) }
    var audioGranted by remember { mutableStateOf(repo.deviceMusic.hasPermission) }
    var menu by remember { mutableStateOf<Track?>(null) }
    val settings = remember { context.getSharedPreferences("appearance", 0) }
    var followCover by remember { mutableStateOf(settings.getBoolean("follow_cover", true)) }
    var scopeMode by remember {
        val storedMode = settings.getString("scope_mode", null)
        mutableStateOf(ScopeMode.entries.firstOrNull { it.name == storedMode } ?: ScopeMode.AUTO)
    }
    fun selectScopeMode(mode: ScopeMode) {
        scopeMode = mode
        settings.edit().putString("scope_mode", mode.name).apply()
    }
    val haptics = remember(context) { MusicHaptics(context) }
    var vibrationEnabled by remember {
        mutableStateOf(haptics.supported && settings.getBoolean("music_haptics", false))
    }
    fun toggleHaptics() {
        if (!haptics.supported) {
            notice("当前设备不支持振动")
            return
        }
        vibrationEnabled = !vibrationEnabled
        settings.edit().putBoolean("music_haptics", vibrationEnabled).apply()
    }
    SideEffect {
        val activity = context as? MainActivity
        val orientation = if (playerOpen) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity != null && activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
    }
    val favStore = remember { context.getSharedPreferences("favorites", 0) }
    var favorites by remember { mutableStateOf(favStore.getStringSet("tracks", emptySet()).orEmpty().toSet()) }
    fun isFavorite(track: Track) = track.favoriteKey in favorites || track.uri in favorites
    fun favorite(track: Track) {
        val adding = !isFavorite(track)
        favorites = if (adding) favorites + track.favoriteKey else favorites - track.favoriteKey - track.uri
        favStore.edit().putStringSet("tracks", favorites).apply()
        notice(if (adding) "已收藏" else "已取消收藏")
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, autoEnabled) {
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = repo.deviceMusic.hasPermission
                val changed = audioGranted != granted
                audioGranted = granted
                if (autoEnabled && (!firstResume || changed)) scanSerial++
                firstResume = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(autoEnabled, audioGranted) {
        if (autoEnabled && audioGranted) repo.deviceMusic.changes().catch { }.collectLatest {
            delay(650)
            scanSerial++
        }
    }
    fun enableAuto() {
        repo.autoEnabled = true
        autoEnabled = true
        importOpen = false
        sources = false
        page = 0
        selectedFolder = ""
        query = ""
        scanSerial++
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioGranted = granted
        if (granted) enableAuto()
        else notice("未获音乐和音频权限，仍可选择文件夹。")
    }
    fun findMusic() {
        if (repo.deviceMusic.hasPermission) {
            audioGranted = true
            enableAuto()
        } else audioPermission.launch(repo.deviceMusic.permission)
    }

    LaunchedEffect(scanSerial) {
        scanning = true
        if (scanSerial == 0) library = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.load() }
        try {
            val revision = manualRevision
            library = repo.scan(previousManual = library.takeIf { completedManualRevision == revision }, favoriteKeys = favorites)
            completedManualRevision = revision
            val migrated = library.tracks.filter { it.storageKey.isNotBlank() && it.uri in favorites }
            if (migrated.isNotEmpty()) {
                favorites = favorites - migrated.map(Track::uri).toSet() + migrated.map(Track::favoriteKey)
                favStore.edit().putStringSet("tracks", favorites).apply()
            }
            if (selectedFolder.startsWith(AUTO_FOLDER) && library.folders.none { it.id == selectedFolder }) selectedFolder = ""
        }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { notice("读取失败，请重新扫描。") }
        finally { scanning = false }
        if (library.failed.isNotEmpty()) notice("无法读取 ${library.failed.joinToString("、")}，请检查权限。")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onSuccess { repo.add(uri); page = 0; selectedFolder = ""; query = ""; sources = false; refreshFolders() }
                .onFailure { notice("无法保留这个目录的读取权限，请选择本机的音乐文件夹。") }
        }
    }
    fun import() {
        importOpen = false
        picker.launch(null)
    }
    var deck by remember { mutableStateOf(Deck()) }
    DisposableEffect(controller) {
        fun sync(p: Player) {
            val queue = (0 until p.mediaItemCount).map { Track.fromItem(p.getMediaItemAt(it)) }
            deck = Deck(p.currentMediaItem?.let(Track::fromItem), p.isPlaying, queue,
                p.currentMediaItemIndex, p.shuffleModeEnabled, p.repeatMode)
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
            override fun onPlayerError(error: PlaybackException) { notice("这首歌无法播放。请检查文件权限、文件是否完整，或编码是否受支持。") }
        }
        controller?.let { it.addListener(listener); sync(it) }
        onDispose { controller?.removeListener(listener) }
    }
    fun play(track: Track, selection: List<Track>) {
        val remote = controller ?: return notice("播放器正在连接，请稍等。")
        val list = selection.ifEmpty { listOf(track) }
        remote.setMediaItems(list.map { it.mediaItem() }, list.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0), 0)
        remote.prepare()
        remote.play()
    }
    fun openPlayer(scope: Boolean = false) {
        startInScope = scope
        playerOpen = true
    }
    fun previewScope() {
        sources = false
        if (controller == null) {
            notice("播放器正在连接，请稍等。")
            return
        }
        play(demoTracks.first(), demoTracks)
        openPlayer(scope = true)
    }
    fun toggle() {
        controller?.let { p ->
            if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) p.pause()
            else if (p.mediaItemCount > 0) {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.play()
            }
        }
    }
    fun add(track: Track, next: Boolean) {
        val p = controller ?: return notice("播放器正在连接，请稍等。")
        if (next && p.mediaItemCount > 0) p.addMediaItem((p.currentMediaItemIndex + 1).coerceIn(0, p.mediaItemCount), track.mediaItem())
        else p.addMediaItem(track.mediaItem())
        notice(if (next) "已设为下一首" else "已加到队列末尾")
    }
    fun closeSearch() {
        query = ""
        searchOpen = false
    }
    fun browseBack() {
        if (searchOpen) closeSearch()
        else if (selectedAlbum.isNotEmpty()) selectedAlbum = ""
        else if (selectedFolder.isNotEmpty()) selectedFolder = library.folders.firstOrNull { it.id == selectedFolder }?.parent.orEmpty()
    }
    BackHandler(playerOpen || selectedFolder.isNotEmpty() || selectedAlbum.isNotEmpty() || searchOpen) {
        if (playerOpen) playerOpen = false else browseBack()
    }

    val albumGroups = remember(library.tracks) { library.tracks.groupBy { a -> a.album.ifBlank { "未标记专辑" } } }
    val albumNames = remember(albumGroups) { albumGroups.keys.sortedBy { it.lowercase(Locale.ROOT) } }
    val search = query.trim()
    val indexedTracks = remember(library.tracks) { library.tracks.associateBy(Track::favoriteKey) }
    val artworkTrack = deck.track?.let { indexedTracks[it.favoriteKey] ?: it }
    val selection = rememberArtwork(artworkTrack, scanSerial)
    val artwork = selection.backdrop
    val targetTint = artwork?.main ?: Color.White
    val tint by animateColorAsState(targetTint, tween(550, easing = Ease), label = "cover tint")
    val secondary by animateColorAsState(artwork?.secondary ?: targetTint, tween(550, easing = Ease), label = "cover shade")
    val amount by animateFloatAsState(if (followCover && artwork != null && targetTint != Color.White) 1f else 0f,
        tween(550, easing = Ease), label = "cover strength")
    val palette = FrostPalette(tint, secondary, amount)
    CompositionLocalProvider(LocalFrost provides palette) {
      MaterialTheme(colorScheme = darkColorScheme(
          primary = palette.accent, onPrimary = palette.ink, primaryContainer = palette.accentWell, onPrimaryContainer = Paper,
          secondary = palette.accent, onSecondary = palette.ink, secondaryContainer = palette.accentWell, onSecondaryContainer = Paper,
          tertiary = palette.accent, onTertiary = palette.ink, tertiaryContainer = palette.accentWell, onTertiaryContainer = Paper,
          surface = palette.panel, onSurface = Paper, surfaceTint = palette.accent,
          surfaceContainerLowest = palette.ink, surfaceContainerLow = palette.panel, surfaceContainer = palette.panel,
          surfaceContainerHigh = palette.raised, surfaceContainerHighest = palette.accentWell,
          surfaceVariant = palette.raised, onSurfaceVariant = palette.muted,
          outline = palette.dim, outlineVariant = palette.line,
          inverseSurface = palette.accent, inverseOnSurface = palette.ink, inversePrimary = palette.accentWell,
          background = palette.ink, onBackground = Paper
      )) {
      Box(Modifier.fillMaxSize()) {
        FrostBackdrop(artwork, .28f, Modifier.matchParentSize())
        val density = LocalDensity.current
        val dockVisible = deck.track != null && WindowInsets.ime.getBottom(density) == 0
        val librarySemantics = if (playerOpen && deck.track != null) {
            Modifier.clearAndSetSemantics { hideFromAccessibility() }
        } else Modifier
        BoxWithConstraints(Modifier.fillMaxSize().then(librarySemantics)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
          val short = maxHeight < 450.dp
          val gutter = if (maxWidth < 360.dp) 16.dp else 24.dp
          val pane = Modifier.widthIn(max = 760.dp).fillMaxWidth()
          Box(Modifier.fillMaxSize().imePadding()) {
          Column(pane.align(Alignment.TopCenter).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 6.dp,
                top = if (short) 2.dp else 14.dp, bottom = if (short) 3.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (searchOpen) {
                    SearchBox(query, Modifier.weight(1f).padding(end = 6.dp), ::closeSearch) { query = it }
                } else {
                    Glyph("mark", Modifier.padding(start = 2.dp).size(28.dp).semantics { contentDescription = "曲库" }, Accent)
                    Spacer(Modifier.weight(1f))
                    IconButton("search", "搜索曲库", tint = Muted, size = 48) { searchOpen = true }
                    IconButton("plus", "导入音乐", tint = Accent, size = 48, glyphSize = 23) { importOpen = true }
                    IconButton("more", "更多", tint = Muted, size = 48, glyphSize = 22) { sources = true }
                }
            }
            PlayerTabs(listOf(PlayerTab("文件夹", "folder"), PlayerTab("专辑", "disc"),
                PlayerTab("歌曲", "music"), PlayerTab("收藏", "star")), if (searchOpen) -1 else page,
                Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = if (short) 0.dp else 7.dp)) { index ->
                page = index
                selectedAlbum = ""
                selectedFolder = ""
                closeSearch()
            }
            AnimatedContent(targetState = Triple(page, selectedFolder, selectedAlbum), modifier = Modifier.weight(1f), transitionSpec = {
                (fadeIn(tween(145, delayMillis = 35)) + slideInHorizontally(tween(240, easing = Ease)) { it / 24 }) togetherWith fadeOut(tween(100))
            }, label = "library") { location ->
                val (page, folder, album) = location
                val activeFolder = library.folders.firstOrNull { it.id == folder }
                val directory = remember(library.folders, folder) { library.folders.filter { it.parent == folder } }
                val visible = remember(search, library.tracks, page, folder, album, favorites) {
                    when {
                        search.isNotEmpty() -> library.tracks.filter { t -> listOf(t.title, t.file, t.artist, t.album).any { it.contains(search, true) } }
                        page == 0 && folder.isNotEmpty() -> library.tracks.filter { it.folder == folder }
                        page == 1 && album.isNotEmpty() -> albumGroups[album].orEmpty()
                        page == 2 -> library.tracks
                        page == 3 -> (library.tracks + demoTracks).filter { isFavorite(it) }
                        else -> emptyList()
                    }
                }
                val sectionTitle = activeFolder?.title ?: album
                val dockClearance = if (!dockVisible) 16.dp else (86 + 24 * (density.fontScale - 1).coerceAtLeast(0f)).dp
                val showAlbumGrid = search.isEmpty() && page == 1 && album.isEmpty() && albumNames.isNotEmpty()
                if (showAlbumGrid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(125.dp), modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = gutter, end = gutter, top = 18.dp, bottom = dockClearance),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(albumNames, key = { it }) { name ->
                            val tracks = albumGroups.getValue(name)
                            AlbumTile(name, tracks, scanSerial) { selectedAlbum = name }
                        }
                    }
                } else LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = gutter, end = gutter, top = if (short) 8.dp else 12.dp, bottom = dockClearance)) {
                    if (!searchOpen && sectionTitle.isNotBlank()) item {
                        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton("left", "返回上一级", size = 44) { browseBack() }
                            Label(sectionTitle, Modifier.weight(1f), size = 20, weight = FontWeight.SemiBold)
                            if (activeFolder != null) SongCount(activeFolder.count)
                        }
                    }
                    if (scanning && library.folders.isEmpty() && library.tracks.isEmpty()) item {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (search.isEmpty() && page == 0) {
                        if (folder.isEmpty() && pendingImportCount > 0) item(key = "pending-imports") {
                            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(10.dp))
                                .background(Panel).clickable(onClick = ::openReview).padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Glyph("filter", Modifier.size(19.dp), Accent)
                                Label("待确认音频", Modifier.weight(1f), size = 14, weight = FontWeight.Medium)
                                Label(pendingImportCount.toString(), color = Muted, size = 12, mono = true)
                                Glyph("right", Modifier.size(14.dp), Muted)
                            }
                        }
                        items(directory, key = { it.id }) { item ->
                            FolderRow(item.title, item.count) { selectedFolder = item.id }
                        }
                    }
                    items(visible, key = { it.uri }) { track ->
                        TrackRow(track, scanSerial, deck.track?.favoriteKey == track.favoriteKey,
                            { play(track, visible) }, { menu = track })
                    }
                    val empty = visible.isEmpty() && (search.isNotEmpty() || when (page) {
                        0 -> directory.isEmpty()
                        1 -> albumGroups.isEmpty() || album.isNotEmpty()
                        else -> true
                    })
                    if (empty && search.isEmpty() && page == 0 && folder.isEmpty() && !scanning) item {
                        Welcome(
                            if (autoEnabled && audioGranted) null else ::findMusic,
                            ::previewScope
                        )
                    } else if (empty && !scanning) item {
                        val text = when {
                            search.isNotEmpty() -> "无搜索结果"
                            page == 3 -> "暂无收藏"
                            else -> "暂无歌曲"
                        }
                        Label(text, Modifier.padding(24.dp), color = Dim, size = 13)
                    }
                }
            }
          }
          if (dockVisible) {
              MiniDeck(deck, selection.cover, Modifier.align(Alignment.BottomCenter).padding(horizontal = gutter, vertical = 9.dp)
                  .widthIn(max = 712.dp),
                  open = { openPlayer() }, toggle = ::toggle, visualize = { openPlayer(scope = true) },
                  queue = { queueOpen = true })
          }
        }
        }
        AnimatedVisibility(playerOpen && deck.track != null,
            enter = slideInVertically(tween(350, easing = Ease)) { it } + fadeIn(tween(210)),
            exit = slideOutVertically(tween(280, easing = Ease)) { it } + fadeOut(tween(230)),
            modifier = Modifier.fillMaxSize()) {
            deck.track?.let { track ->
                NowPlaying(artworkTrack ?: track, selection, deck, controller, close = { playerOpen = false }, toggle = ::toggle,
                    openQueue = { queueOpen = true }, openEq = { equalizerOpen = true }, openAudioInfo = { audioInfoOpen = true }, favorite = { favorite(artworkTrack ?: track) }, starred = isFavorite(artworkTrack ?: track), lyricsRevision = scanSerial,
                    grantLyricsFolder = { picker.launch(lyricDirectoryHint(artworkTrack ?: track)) }, followCover = followCover,
                    startInScope = startInScope, spectrumMode = scopeMode, selectScopeMode = ::selectScopeMode, haptics = haptics,
                    vibrationEnabled = vibrationEnabled, toggleHaptics = ::toggleHaptics)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 77.dp, start = 14.dp, end = 14.dp))
      }
      if (importOpen) AppSheet("导入音乐", "", { importOpen = false }) {
          Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
              SheetAction(if (autoEnabled && audioGranted) "重新找歌" else "自动找歌", "search", ::findMusic)
              SheetAction("选择文件夹", "folder", ::import)
              if (library.review.isNotEmpty()) SheetAction("筛选结果", "filter", ::openReview)
          }
      }
      if (reviewOpen) AppSheet("导入筛选", "", { reviewOpen = false }) {
          ImportReview(library.review, scanSerial, pauseLibrary = { controller?.pause() }, onDecision = ::decideImport)
      }
      if (audioInfoOpen) AppSheet("音频信息", "", { audioInfoOpen = false }) { AudioInfoBody() }
      if (equalizerOpen) EqualizerSheet { equalizerOpen = false }
      if (queueOpen) AppSheet("播放队列", "${deck.queue.size} 首", { queueOpen = false }) {
          if (deck.queue.isEmpty()) Label("队列为空", Modifier.padding(22.dp), color = Muted)
          else LazyColumn(Modifier.heightIn(max = 400.dp)) {
              itemsIndexed(deck.queue) { index, item ->
                  Row(Modifier.heightIn(min = 68.dp).fillMaxWidth().padding(start = 20.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                      val selected = deck.index == index
                      Label((index + 1).toString().padStart(2, '0'), Modifier.width(32.dp), color = if (selected) Accent else Dim, size = 11, mono = true)
                      Column(Modifier.weight(1f).clickable {
                          controller?.let { it.seekToDefaultPosition(index); if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
                      }.padding(vertical = 10.dp)) {
                          Label(item.title, color = if (selected) Accent else Paper, size = 14)
                          Label(item.artist.ifBlank { item.extension }, color = Dim, size = 11)
                      }
                      if (index > 0) IconButton("up", "上移", size = 36) { controller?.moveMediaItem(index, index - 1) }
                      if (index < deck.queue.lastIndex) IconButton("down", "下移", size = 36) { controller?.moveMediaItem(index, index + 1) }
                      IconButton("close", "移出队列", size = 36) { controller?.removeMediaItem(index) }
                  }
                  Hairline(Modifier.padding(horizontal = 20.dp))
              }
          }
      }
      if (sources) AppSheet("更多", "", { sources = false }) {
          Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              repo.roots.forEach { root ->
                  val rootLabel = runCatching {
                      val uri = Uri.parse(root)
                      val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
                      val key = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, id).toString()
                      library.folders.firstOrNull { it.id == key }?.title ?: id.substringAfterLast('/').substringAfterLast(':')
                  }.getOrDefault("文件夹")
                  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Label(rootLabel, Modifier.weight(1f), size = 14)
                      IconButton("close", "取消收录文件夹") {
                          repo.remove(root); selectedFolder = ""; refreshFolders(); sources = false
                          notice("已移出曲库")
                      }
                  }
              }
              SheetAction(if (autoEnabled && audioGranted) "关闭自动找歌" else "自动找歌", "search") {
                  if (autoEnabled && audioGranted) {
                      repo.autoEnabled = false
                      autoEnabled = false
                      sources = false
                      if (selectedFolder.startsWith(AUTO_FOLDER)) selectedFolder = ""
                      scanSerial++
                  } else findMusic()
              }
              SheetAction("添加文件夹", "plus") { import() }
              SheetAction("重新扫描", "refresh") { refreshFolders(); sources = false }
              if (library.review.isNotEmpty()) SheetAction("筛选结果", "filter", ::openReview)
              SheetToggle("封面配色", "palette", followCover) { enabled ->
                  followCover = enabled
                  settings.edit().putBoolean("follow_cover", followCover).apply()
              }
              SheetAction("均衡器", "eq") { sources = false; equalizerOpen = true }
              SheetToggle("音乐触感", "vibration", vibrationEnabled) { toggleHaptics() }
              SheetAction("试听可视化", "scope", ::previewScope)
          }
      }
      menu?.let { track ->
          AppSheet(track.title, track.extension, { menu = null }) {
              Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp)) {
                  SheetAction("下一首播放", "next") { menu = null; add(track, true) }
                  SheetAction("加到队列末尾", "queue") { menu = null; add(track, false) }
                  SheetAction(if (isFavorite(track)) "取消收藏" else "收藏歌曲", "star") { menu = null; favorite(track) }
                  if (track.folder.startsWith(AUTO_FOLDER)) SheetAction("以后跳过此音频", "close") {
                      menu = null
                      decideImport(track, false)
                      notice("已跳过，可在筛选结果里恢复")
                  }
              }
          }
      }
      }
    }
}

@Composable private fun SearchBox(query: String, modifier: Modifier, close: () -> Unit, change: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Row(modifier.heightIn(min = 48.dp).frosted(radius = 11, opacity = .85f)
        .padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph("search", Modifier.size(18.dp), Dim)
        Spacer(Modifier.width(9.dp))
        BasicTextField(query, change, Modifier.weight(1f).padding(vertical = 12.dp).focusRequester(focus)
            .semantics { contentDescription = "搜索曲库" }, singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = Paper), cursorBrush = SolidColor(Accent))
        IconButton("close", "退出搜索", size = 48) { keyboard?.hide(); close() }
    }
}

@Composable private fun SongCount(count: Int) {
    Label(count.toString(), Modifier.semantics { contentDescription = "$count 首歌曲" }, color = Dim, size = 12, mono = true)
}

@Composable private fun FolderRow(name: String, count: Int, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = click)) {
        Row(Modifier.heightIn(min = 68.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Raised, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Glyph("folder", Modifier.size(20.dp), Muted)
            }
            Label(name, Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), size = 15, weight = FontWeight.Medium)
            SongCount(count)
            Spacer(Modifier.width(8.dp))
            Glyph("right", Modifier.size(15.dp), Dim)
        }
        Hairline(Modifier.padding(start = 52.dp))
    }
}

@Composable private fun AlbumTile(name: String, tracks: List<Track>, revision: Int, open: () -> Unit) {
    val cover = rememberThumbnail(tracks, revision)
    val artist = remember(tracks) { tracks.firstNotNullOfOrNull { it.artist.takeIf(String::isNotBlank) }.orEmpty() }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = open)) {
        Cover(cover, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)))
        Label(name, Modifier.padding(top = 8.dp, start = 1.dp), size = 15, weight = FontWeight.SemiBold)
        if (artist.isNotBlank()) Label(artist, Modifier.padding(start = 1.dp, bottom = 1.dp), color = Dim, size = 12)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TrackRow(track: Track, revision: Int, active: Boolean, play: () -> Unit, more: () -> Unit) {
    val thumbnail = rememberThumbnail(remember(track) { listOf(track) }, revision)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 68.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) AccentWell.copy(alpha = .44f) else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 68.dp).clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = play, onLongClick = more)
                .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Cover(thumbnail, Modifier.fillMaxSize())
                    if (active) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .65f)),
                        contentAlignment = Alignment.Center) { Glyph("mark", Modifier.size(20.dp), Paper) }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label(track.title, color = if (active) Accent else Paper, size = 15, weight = FontWeight.Medium)
                    Label(track.artist.ifBlank { track.extension }, color = Dim, size = 12)
                }
                if (track.duration > 0) Label(clock(track.duration), color = Dim, size = 12)
            }
            IconButton("more", "歌曲选项", size = 44, glyphSize = 16) { more() }
        }
        Hairline(Modifier.padding(start = 52.dp))
    }
}

@Composable private fun Welcome(findMusic: (() -> Unit)?, demo: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 42.dp, bottom = 24.dp), horizontalAlignment = Alignment.Start) {
        Label("暂无歌曲", size = 23, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (findMusic != null) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Accent)
                .clickable(onClick = findMusic).padding(horizontal = 15.dp, vertical = 12.dp)) {
                Label("自动找歌", color = Ink, size = 14, weight = FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = demo)
                .padding(horizontal = 14.dp, vertical = 12.dp)) {
                Label("试听可视化", color = Muted, size = 14)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppSheet(title: String, detail: String, dismiss: () -> Unit, body: @Composable () -> Unit) {
    val maxHeight = panelHeight()
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel.copy(alpha = .98f), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = null) {
        DarkSheetBars()
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = 22.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Label(title, Modifier.weight(1f), size = 21, weight = FontWeight.SemiBold)
                if (detail.isNotBlank()) Label(detail, color = Dim, size = 12, mono = true)
                IconButton("close", "关闭", size = 44) { dismiss() }
            }
            Hairline()
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) { body() }
            Spacer(Modifier.height(8.dp))
        }
    }
}
@Composable private fun AudioInfoBody() {
    val context = LocalContext.current
    val usbRequested by UsbSettings.requested.collectAsState()
    val usbSupported = android.os.Build.VERSION.SDK_INT >= 34
    val info by AudioPath.info.collectAsState()
    val eq by EqStore.settings.collectAsState()
    val format = when (info.inputMime) {
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/raw" -> "PCM"
        "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        else -> info.inputMime?.substringAfter('/')
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 495.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp)) {
        AudioInfoRow("编码", listOfNotNull(format, info.sourceBits?.let { "${it} 位" }).joinToString(" · ").ifEmpty { null })
        AudioInfoRow("解码器", info.decoder)
        AudioInfoRow("解码后", info.decoded?.text())
        AudioInfoRow("交给 Android", info.output?.text())
        if (usbRequested) {
            AudioInfoRow("USB 状态", info.usb.state.text)
            if (info.usb.output != null) {
                AudioInfoRow("USB DAC", info.usb.device)
                AudioInfoRow("实际 USB 输出", info.usb.output?.text())
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 51.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("USB 逐位", size = 14)
                Label(if (usbSupported) "逐位播放时在 DAC 上调音量" else "需要 Android 14 或以上",
                    color = Muted, size = 11)
            }
            Switch(usbRequested, { enabled ->
                if (enabled) {
                    EqStore.change { it.copy(enabled = false) }
                    EqStore.persist()
                }
                UsbSettings.set(context, enabled)
            }, Modifier.semantics { contentDescription = "USB 逐位" }, enabled = usbSupported,
                colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent,
                    uncheckedThumbColor = Muted, uncheckedTrackColor = Raised, uncheckedBorderColor = Line))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 51.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("原音", Modifier.weight(1f), size = 14)
            Switch(!eq.enabled, { original ->
                EqStore.change { settings -> settings.copy(enabled = !original) }
                EqStore.persist()
            }, Modifier.semantics { contentDescription = "原音" },
                colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent,
                    uncheckedThumbColor = Muted, uncheckedTrackColor = Raised, uncheckedBorderColor = Line))
        }
    }
}

@Composable private fun AudioInfoRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.width(98.dp), color = Muted, size = 13)
        Label(value ?: "—", Modifier.weight(1f), color = Paper, size = 13, lines = 2)
    }
}

@Composable private fun SheetAction(label: String, icon: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(47.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = click).padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph(icon, Modifier.size(18.dp))
        Label(label, size = 14)
    }
}

@Composable private fun SheetToggle(label: String, icon: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph(icon, Modifier.size(18.dp))
        Label(label, Modifier.weight(1f), size = 14)
        Switch(checked, change, Modifier.semantics { contentDescription = label })
    }
}
