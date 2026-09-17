package com.bakamusic.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import com.bakamusic.android.plugin.*
import com.bakamusic.android.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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

private fun hideSystemBars(window: Window) {
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

@Composable private fun FullscreenImmersiveEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        var ctx: android.content.Context? = view.context
        var activity: Activity? = null
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) { activity = ctx; break }
            ctx = ctx.baseContext
        }
        val activityWindow = activity?.window
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val windows = listOfNotNull(activityWindow, dialogWindow)
        windows.forEach { hideSystemBars(it) }
        onDispose {
            windows.forEach { showSystemBars(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BakaApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val favorites by viewModel.repository.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    val userPlaylistNames by viewModel.repository.userPlaylistNames.collectAsStateWithLifecycle(initialValue = emptyList())
    
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

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f)) {
                HomeScreen(
                    sourceService = viewModel.sourceService,
                    repository = viewModel.repository,
                    favorites = favorites,
                    playlistNames = userPlaylistNames,
                    onPlayMedia = { item, list -> viewModel.playMediaItem(item, list) },
                    onOpenPlaylist = { playlistSheet = it },
                    onImportPlaylist = { importNotice = true },
                    onSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                    onInstallPlugin = {
                        val intent = Intent(context, SettingsActivity::class.java)
                        intent.putExtra(SettingsActivity.EXTRA_SCROLL_TO_PLUGIN, true)
                        context.startActivity(intent)
                    }
                )
            }
            MiniPlayer(
                now = viewModel.nowPlaying,
                playing = viewModel.playing,
                buffering = viewModel.buffering,
                positionMs = viewModel.positionMs,
                durationMs = viewModel.durationMs,
                onPrev = { viewModel.stepPrev() },
                onToggle = { viewModel.togglePlay() },
                onNext = { viewModel.stepNext() },
                onOpenPlayer = { showQueue = false; expanded = true }
            )
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
        Dialog(
            onDismissRequest = { lyricFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            FullscreenImmersiveEffect()
            // 与音乐播放器界面一样的封面取色对角线渐变背景（含切换时的平滑过渡）
            val (lyricGradientStart, lyricGradientEnd) = rememberCoverGradientColors(viewModel.nowPlaying?.item?.artwork)
            val lyricGradientBrush = remember(lyricGradientStart, lyricGradientEnd) {
                Brush.linearGradient(
                    colors = listOf(lyricGradientStart, lyricGradientEnd),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            }
            val lyricContentTarget =
                if ((lyricGradientStart.luminance() + lyricGradientEnd.luminance()) / 2f > 0.5f) Color.Black else Color.White
            val lyricSubTarget = lyricContentTarget.copy(alpha = 0.7f)
            val lyricContent by animateColorAsState(targetValue = lyricContentTarget, animationSpec = tween(600), label = "lyricContent")
            val lyricSubContent by animateColorAsState(targetValue = lyricSubTarget, animationSpec = tween(600), label = "lyricSubContent")
            Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                Box(Modifier.fillMaxSize().background(lyricGradientBrush)) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("歌词", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = lyricContent)
                            Text(
                                viewModel.nowPlaying?.let { "${it.item.title} · ${it.item.artist}" } ?: "",
                                fontSize = 12.sp, color = lyricSubContent,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { lyricFullscreen = false }) { Icon(Icons.Default.FullscreenExit, "退出全屏", tint = lyricContent) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LyricsView(
                            lines = viewModel.lyricLines,
                            positionMs = viewModel.positionMs,
                            loading = viewModel.lyricLoading && viewModel.nowPlaying != null,
                            markerFraction = 0.24f,
                            fontScale = 1.35f,
                            centered = true,
                            activeColor = lyricContent,
                            inactiveColor = lyricSubContent
                        )
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

    playlistSheet?.let { name ->
        PlaylistSheet(
            name = name,
            songs = sheetSongs,
            onPlay = { record ->
                val items = sheetSongs.map { it.toMediaItem() }
                val idx = items.indexOfFirst { it.platform == record.platform && it.id == record.id }
                playlistSheet = null
                if (idx >= 0) viewModel.playItems(items, idx) else viewModel.playMediaItem(record.toMediaItem(), emptyList())
            },
            onRemove = { record -> scope.launch(Dispatchers.IO) { viewModel.repository.removeFromPlaylist(name, record.key) } },
            onDeletePlaylist = if (name == AppRepository.FAVORITE_SHEET) null else ({
                scope.launch(Dispatchers.IO) { viewModel.repository.deletePlaylist(name) }
                playlistSheet = null
            }),
            onClose = { playlistSheet = null }
        )
    }
}
