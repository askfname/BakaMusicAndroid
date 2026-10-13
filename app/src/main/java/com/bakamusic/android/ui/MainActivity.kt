package com.bakamusic.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import com.bakamusic.android.plugin.*
import com.bakamusic.android.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(0, 0), navigationBarStyle = SystemBarStyle.auto(0, 0))
        setContent { BakaTheme { BakaApp() } }
    }
}

@Composable fun BakaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Color(0xffb5c7ff),
            secondary = Color(0xffffb1c4),
            surface = Color(0xff111318),
            background = Color(0xff111318)
        ) else lightColorScheme(
            primary = Color(0xff475d92),
            secondary = Color(0xff7b5261),
            surface = Color(0xfff9f9ff)
        ),
        content = content
    )
}

/**
 * 常驻式 Tab 页容器：四页始终留在 composition 中，仅切换显隐
 */
@Composable private fun KeepAliveTab(visible: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .alpha(if (visible) 1f else 0f)
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
    ) { content() }
}

private fun hideSystemBars(window: Window) {
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }
}

private fun showSystemBars(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BakaApp(viewModel: MainViewModel = viewModel()) {
    val scope = rememberCoroutineScope()

    val favorites by viewModel.repository.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    val userPlaylistNames by viewModel.repository.userPlaylistNames.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var settingsScrollTrigger by remember { mutableStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var importNotice by remember { mutableStateOf(false) }
    var playlistSheet by rememberSaveable { mutableStateOf<String?>(null) }

    var playerAddItem by remember { mutableStateOf<MediaItem?>(null) }
    var playerDlItem by remember { mutableStateOf<MediaItem?>(null) }
    var playerQItem by remember { mutableStateOf<MediaItem?>(null) }
    var lyricFullscreen by rememberSaveable { mutableStateOf(false) }

    val sheetSongs by (playlistSheet?.let { name -> remember(name) { viewModel.repository.playlistSongs(name) } }
        ?: remember { flowOf(emptyList<SongRecord>()) })
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 非首页按返回优先回到主页（播放器/全屏歌词展开时由其自身的 BackHandler 接管）
    BackHandler(enabled = !expanded && !lyricFullscreen && selectedTab != 0) { selectedTab = 0 }

    // 切页时收起键盘：隐藏页的搜索框可能仍持有焦点
    val tabFocusManager = LocalFocusManager.current
    LaunchedEffect(selectedTab) { tabFocusManager.clearFocus() }

    // 悬浮底座显隐：位移跟随手指，用 Animatable 手动驱动位移+透明度
    var dockGone by remember { mutableStateOf(false) }
    var dockHPx by remember { mutableFloatStateOf(0f) }
    val dockOffsetY = remember { Animatable(0f) }
    val dockAlpha = remember { Animatable(1f) }
    var dockJob by remember { mutableStateOf<Job?>(null) }
    var dockLastTarget by remember { mutableStateOf(true) }
    var dockRunningDur by remember { mutableIntStateOf(350) }
    fun dockHideY(): Float = if (dockHPx > 0f) dockHPx + 64f else 800f
    fun settleDock(show: Boolean, durMs: Int) {
        if (show == dockLastTarget && dockJob?.isActive == true && abs(durMs - dockRunningDur) <= 60) return
        dockLastTarget = show
        dockRunningDur = durMs
        dockJob?.cancel()
        if (show) dockGone = false
        dockJob = scope.launch {
            coroutineScope {
                launch { dockOffsetY.animateTo(if (show) 0f else dockHideY(), tween(durMs)) }
                launch { dockAlpha.animateTo(if (show) 1f else 0f, tween(durMs)) }
            }
            if (!show) dockGone = true
        }
    }
    fun durationForDist(dPx: Float): Int =
        (90_000f / dPx.coerceAtLeast(96f)).toInt().coerceIn(280, 650)
    val dockHideConnection = remember {
        object : NestedScrollConnection {
            var acc = 0f
            var lastT = 0L
            var wasted = 0f
            // 页面无法滚动时的安全网：滑动完全不被消费
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy != 0f) {
                    if (wasted != 0f && (wasted < 0f) != (dy < 0f)) wasted = 0f
                    wasted += dy
                    if (abs(wasted) > 120f) {
                        wasted = 0f
                        settleDock(true, 400)
                    }
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = consumed.y
                if (dy != 0f) {
                    wasted = 0f
                    val now = SystemClock.uptimeMillis()
                    // 新手势（与上次事件间隔过久）与变向都清零累计
                    if (now - lastT > 250L || (acc != 0f && (acc < 0f) != (dy < 0f))) acc = 0f
                    acc += dy
                    lastT = now
                    // 96px（约 36dp）内短距离滑动不触发；内容上滑（acc<0）隐藏，下滑显示
                    if (abs(acc) > 96f) {
                        val show = acc > 0f
                        settleDock(show, durationForDist(abs(acc)))
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val v = consumed.y
                // 轻 flick 或本手势累计位移不足不触发；
                if (abs(v) > 2000f && abs(acc) > 200f) {
                    val show = v > 0f
                    settleDock(show, durationForDist(maxOf(abs(acc), abs(v) * 0.3f)))
                }
                return Velocity.Zero
            }
        }
    }
    // 从任意歌曲列表选中并播放新歌曲时，确保底座可见
    LaunchedEffect(viewModel.nowPlaying?.key) { settleDock(true, 350) }
    // 切页即显示底座：新页可能不可滚动，避免隐藏态下无处可滑导致导航丢失
    LaunchedEffect(selectedTab) { settleDock(true, 350) }

    fun jumpToPluginSettings() {
        selectedTab = 3
        settingsScrollTrigger++
        playlistSheet = null
    }

    Scaffold { padding ->
        // 底座不再占用 bottomBar 槽位，而是以覆盖层形态悬浮于内容之上
        Box(Modifier.fillMaxSize().nestedScroll(dockHideConnection)) {
            Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Box(Modifier.fillMaxSize()) {
                KeepAliveTab(visible = selectedTab == 0) {
                    HomeTab(
                        sourceService = viewModel.sourceService,
                        repository = viewModel.repository,
                        favorites = favorites,
                        playlistNames = userPlaylistNames,
                        onPlayMedia = { item, list -> viewModel.playMediaItem(item, list) },
                        onInstallPlugin = { jumpToPluginSettings() }
                    )
                }
                KeepAliveTab(visible = selectedTab == 1) {
                    LibraryTab(
                        repository = viewModel.repository,
                        playlistNames = userPlaylistNames,
                        onOpenPlaylist = { playlistSheet = it },
                        onImportPlaylist = { importNotice = true }
                    )
                }
                KeepAliveTab(visible = selectedTab == 2) {
                    SearchTab(
                        sourceService = viewModel.sourceService,
                        repository = viewModel.repository,
                        favorites = favorites,
                        playlistNames = userPlaylistNames,
                        onPlayMedia = { item, list -> viewModel.playMediaItem(item, list) },
                        onInstallPlugin = { jumpToPluginSettings() },
                        onShowDock = { settleDock(true, 350) }
                    )
                }
                KeepAliveTab(visible = selectedTab == 3) {
                    SettingsScreen(embedded = true, scrollTrigger = settingsScrollTrigger)
                }
            }
            }
            // 歌单详情全屏页：盖在内容层之上、悬浮底座之下，底座始终全局可见不被覆盖
            playlistSheet?.let { name ->
                PlaylistDetailScreen(
                    name = name,
                    songs = sheetSongs,
                    onPlay = { record ->
                        val items = sheetSongs.map { it.toMediaItem() }
                        val idx = items.indexOfFirst { it.platform == record.platform && it.id == record.id }
                        if (idx >= 0) viewModel.playItems(items, idx) else viewModel.playMediaItem(record.toMediaItem(), emptyList())
                    },
                    onRemove = { record -> scope.launch(Dispatchers.IO) { viewModel.repository.removeFromPlaylist(name, record.key) } },
                    onDeletePlaylist = if (name == AppRepository.FAVORITE_SHEET) null else ({
                        scope.launch(Dispatchers.IO) { viewModel.repository.deletePlaylist(name) }
                        playlistSheet = null
                        settleDock(true, 350)
                    }),
                    onClose = { playlistSheet = null; settleDock(true, 350) }
                )
            }
            // 底座常驻 composition（显隐只做位移+透明度，不移除）
            if (!dockGone) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .onSizeChanged { dockHPx = it.height.toFloat() }
                        .offset { IntOffset(0, dockOffsetY.value.roundToInt()) }
                        .alpha(dockAlpha.value)
                ) {
                BottomPlayerDock(
                    now = viewModel.nowPlaying,
                    playing = viewModel.playing,
                    buffering = viewModel.buffering,
                    positionMs = viewModel.positionMs,
                    durationMs = viewModel.durationMs,
                    onPrev = { viewModel.stepPrev() },
                    onToggle = { viewModel.togglePlay() },
                    onNext = { viewModel.stepNext() },
                    onOpenPlayer = { showQueue = false; expanded = true },
                    onSeek = { viewModel.seekTo(it) },
                    selectedTab = selectedTab,
                    // 切页即关闭歌单详情，避免其盖住其他页面
                    onSelectTab = { selectedTab = it; playlistSheet = null }
                )
                }
            }
        }
    }

    viewModel.playError?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.playError = null },
            title = { Text("播放提示") },
            text = { Text(msg) },
            confirmButton = { TextButton({ viewModel.playError = null }) { Text("确定") } }
        )
    }

    viewModel.playerError?.let { msg ->
        val cur = viewModel.nowPlaying
        val canRetry = cur != null && viewModel.playerErrorRetry
        var nextCountdown by remember { mutableStateOf(5) }
        LaunchedEffect(msg) {
            nextCountdown = 5
            repeat(5) {
                delay(1000)
                nextCountdown--
            }
            viewModel.playerError = null
            viewModel.stepNext()
        }
        AlertDialog(
            onDismissRequest = { viewModel.playerError = null },
            title = { Text("播放失败") },
            text = { Text(msg) },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = { viewModel.playerError = null; viewModel.stepNext() },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("下一曲 (${nextCountdown}s)", maxLines = 1) }
                    TextButton(
                        onClick = { viewModel.playerError = null },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("关闭", maxLines = 1) }
                    if (canRetry) {
                        TextButton(
                            onClick = { viewModel.retryLowerQuality(cur!!) },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("换更低音质重试", maxLines = 1) }
                    }
                }
            },
            dismissButton = null
        )
    }

    if (importNotice) {
        AlertDialog(
            onDismissRequest = { importNotice = false },
            title = { Text("导入歌单") },
            text = { Text("歌单导入功能暂未开放，敬请期待") },
            confirmButton = { TextButton({ importNotice = false }) { Text("确定") } }
        )
    }

    if (expanded) PlayerSheet(
        now = viewModel.nowPlaying,
        playing = viewModel.playing,
        queue = viewModel.playQueue.toList(),
        queueIndex = viewModel.queueIndex,
        positionMs = viewModel.positionMs,
        durationMs = viewModel.durationMs,
        lyricLines = viewModel.lyricLines,
        lyricLoading = viewModel.lyricLoading,
        onToggle = { viewModel.togglePlay() },
        onPrev = { viewModel.stepPrev() },
        onNext = { viewModel.stepNext() },
        onSeek = { pos -> viewModel.seekTo(pos) },
        onPlayAt = { viewModel.playAt(it) },
        isFavorite = viewModel.nowPlaying?.let { favorites.contains(it.key) } == true,
        onToggleFavorite = { viewModel.nowPlaying?.let { scope.launch(Dispatchers.IO) { viewModel.repository.toggleFavorite(it.item) } } },
        onOpenAddPlaylist = { playerAddItem = viewModel.nowPlaying?.item },
        onOpenDownload = { playerDlItem = viewModel.nowPlaying?.item },
        onOpenSwitchQuality = { playerQItem = viewModel.nowPlaying?.item },
        buffering = viewModel.buffering,
        onClose = { expanded = false; showQueue = false },
        showQueue = showQueue,
        onToggleQueue = { showQueue = !showQueue },
        playMode = viewModel.playMode,
        onModeChange = { viewModel.playMode = it },
        onOpenLyricFullscreen = { lyricFullscreen = true }
    )

    if (lyricFullscreen) {
        val lyricTransition = remember { MutableTransitionState(false).apply { targetState = true } }
        fun dismissLyricAnimated() {
            if (lyricTransition.targetState) lyricTransition.targetState = false
        }
        // 退出动画播完才真正关闭
        LaunchedEffect(lyricTransition.currentState, lyricTransition.isIdle) {
            if (!lyricTransition.targetState && !lyricTransition.currentState && lyricTransition.isIdle) {
                lyricFullscreen = false
            }
        }
        // 全屏覆盖层（Activity 窗口内）：独立 Dialog 建窗时系统栏先显后隐会闪烁
        BackHandler { dismissLyricAnimated() }
        // 与音乐播放器界面一样的封面取色对角线渐变背景
        val (lyricGradientStart, lyricGradientEnd) = rememberCoverGradientColors(viewModel.nowPlaying?.item?.artwork)
        val lyricLight = (lyricGradientStart.luminance() + lyricGradientEnd.luminance()) / 2f > 0.6f
        val lyricOverlayView = LocalView.current
        val lyricDarkTheme = isSystemInDarkTheme()
        DisposableEffect(lyricOverlayView, lyricLight, lyricDarkTheme) {
            var lyricCtx: android.content.Context? = lyricOverlayView.context
            var lyricActivity: Activity? = null
            while (lyricCtx is android.content.ContextWrapper) {
                if (lyricCtx is Activity) { lyricActivity = lyricCtx; break }
                lyricCtx = lyricCtx.baseContext
            }
            val lyricWindow = lyricActivity?.window
            lyricWindow?.let { hideSystemBars(it); applyGradientBarIcons(it, lyricLight) }
            // 全屏歌词常亮
            lyricOverlayView.keepScreenOn = true
            onDispose {
                lyricOverlayView.keepScreenOn = false
                lyricWindow?.let { showSystemBars(it); applyGradientBarIcons(it, lyricLight) }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            // 打开首帧快照一次导航栏高度并永久冻结
            val lyricDensity = LocalDensity.current
            var frozenLyricNavBottom by remember { mutableStateOf<Dp?>(null) }
            if (frozenLyricNavBottom == null) {
                frozenLyricNavBottom = with(lyricDensity) {
                    androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(lyricDensity).toDp()
                }
            }
            AnimatedVisibility(
                visibleState = lyricTransition,
                enter = fadeIn(animationSpec = tween(300)) + slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(250)) + scaleOut(targetScale = 0.96f, animationSpec = tween(250))
            ) {
            val lyricGradientBrush = remember(lyricGradientStart, lyricGradientEnd) {
                Brush.linearGradient(
                    colors = listOf(lyricGradientStart, lyricGradientEnd),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            }
            val lyricContentTarget =
                if ((lyricGradientStart.luminance() + lyricGradientEnd.luminance()) / 2f > 0.6f) Color.Black else Color.White
            val lyricSubTarget = lyricContentTarget.copy(alpha = 0.7f)
            val lyricContent by animateColorAsState(targetValue = lyricContentTarget, animationSpec = tween(600), label = "lyricContent")
            val lyricSubContent by animateColorAsState(targetValue = lyricSubTarget, animationSpec = tween(600), label = "lyricSubContent")
            // 全屏未播放歌词更浅，与副标题 0.7 区分
            val lyricInactiveTarget = lyricContentTarget.copy(alpha = 0.45f)
            val lyricInactive by animateColorAsState(targetValue = lyricInactiveTarget, animationSpec = tween(600), label = "lyricInactive")
            Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                Box(Modifier.fillMaxSize().background(lyricGradientBrush)) {
                Column(Modifier.fillMaxSize().padding(bottom = frozenLyricNavBottom ?: 0.dp).padding(start = 24.dp, end = 24.dp, top = 32.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("歌词", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = lyricContent)
                            Text(
                                viewModel.nowPlaying?.let { "${it.item.title} · ${it.item.artist}" } ?: "",
                                fontSize = 12.sp, color = lyricSubContent,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { dismissLyricAnimated() }) { Icon(Icons.Default.FullscreenExit, "退出全屏", tint = lyricContent) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LyricsView(
                            lines = viewModel.lyricLines,
                            positionMs = viewModel.positionMs,
                            loading = viewModel.lyricLoading && viewModel.nowPlaying != null,
                            playing = viewModel.playing,
                            markerFraction = 0.24f,
                            fontScale = 1.35f,
                            centered = true,
                            activeColor = lyricContent,
                            inactiveColor = lyricInactive,
                            onSeek = { viewModel.seekTo(it) },
                            lineGap = 10.dp
                        )
                    }
                }
                }
            }
            }
        }
    }

    playerAddItem?.let { item ->
        val options = userPlaylistNames.ifEmpty { listOf(AppRepository.DEFAULT_SHEET) }
        AlertDialog(
            onDismissRequest = { playerAddItem = null },
            title = { Text("添加到我的歌单") },
            text = {
                Column {
                    options.forEach { playlist ->
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) { viewModel.repository.addToPlaylist(playlist, item) }
                                playerAddItem = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(playlist) }
                    }
                }
            },
            confirmButton = {}
        )
    }

    playerDlItem?.let { item ->
        AlertDialog(
            onDismissRequest = { playerDlItem = null },
            title = { Text("选择下载音质") },
            text = {
                Column {
                    item.qualities.keys.sortedByDescending { qualityRankOf(it) }.ifEmpty { listOf("320k") }.forEach { quality ->
                        TextButton(
                            onClick = {
                                playerDlItem = null
                                viewModel.downloadItem(item, quality)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$quality${item.qualities[quality]?.size?.let { " · ${formatSize(it)}" } ?: ""}")
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    playerQItem?.let { item ->
        AlertDialog(
            onDismissRequest = { playerQItem = null },
            title = { Text("切换播放音质") },
            text = {
                Column {
                    val cur = viewModel.nowPlaying?.takeIf { it.key == "${item.platform}:${item.id}" }?.qualityKey
                    item.qualities.keys.sortedByDescending { qualityRankOf(it) }.ifEmpty { listOf("320k") }.forEach { quality ->
                        val selected = quality == cur
                        TextButton(
                            onClick = {
                                viewModel.switchQuality(item, quality)
                                playerQItem = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${if (selected) "● " else ""}$quality${item.qualities[quality]?.size?.let { " · ${formatSize(it)}" } ?: ""}",
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

}
