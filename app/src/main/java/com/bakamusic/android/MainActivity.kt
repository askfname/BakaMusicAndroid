package com.bakamusic.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException

/** 当前播放项：已解析地址与实际播放音质，供播放栏/播放页显示 */
private data class NowPlaying(
    val item: MediaItem,
    val mediaUrl: String,
    val qualityKey: String,
    val size: Long? = null,
    /** 播放请求头，随地址一起透传给播放器 */
    val headers: Map<String, String> = emptyMap(),
    /** 是否处于解析中：解析中先显示歌曲信息并叠加加载动画 */
    val loading: Boolean = false
) {
    val key: String get() = "${item.platform}:${item.id}"
}

/** 点播解析整体超时：超时终止请求并按失败原因弹对应的播放提示窗 */
private const val PLAY_RESOLVE_TIMEOUT_MS = 10_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(0, 0), navigationBarStyle = SystemBarStyle.auto(0, 0))
        setContent { BakaTheme { BakaApp() } }
    }
}

@Composable fun BakaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xffb5c7ff), secondary = Color(0xffffb1c4), surface = Color(0xff111318), background = Color(0xff111318)) else lightColorScheme(primary = Color(0xff475d92), secondary = Color(0xff7b5261), surface = Color(0xfff9f9ff)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BakaApp() {
    val context = LocalContext.current
    val repository = remember { AppRepository(context.applicationContext) }
    val playbackController = remember { PlaybackController(context.applicationContext) }
    val sourceService = remember { MusicSourceService(context.applicationContext) }
    DisposableEffect(sourceService) {
        onDispose { sourceService.close() }
    }
    val favorites by repository.favorites.collectAsStateWithLifecycle(initialValue = emptySet())
    val userPlaylistNames by repository.userPlaylistNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val defaultPlayQuality by repository.playQuality.collectAsStateWithLifecycle(initialValue = "320k")
    val scope = rememberCoroutineScope()
    // 统一播放状态：当前项 + 队列 + 真实播放器状态
    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    val playQueue = remember { mutableStateListOf<MediaItem>() }
    var queueIndex by remember { mutableStateOf(0) }
    val resolvedCache = remember { mutableStateMapOf<String, NowPlaying>() }
    // 已解析地址缓存对应的插件指纹：插件变更后旧地址失效，需清空缓存
    var resolvedCacheGen by remember { mutableStateOf(-1L) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var playerError by remember { mutableStateOf<String?>(null) }
    /** 播放失败弹窗是否提供“换更低音质重试” */
    var playerErrorRetry by remember { mutableStateOf(true) }
    var importNotice by remember { mutableStateOf(false) }
    var playlistSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var lastEndedKey by remember { mutableStateOf<String?>(null) }
    var lyricLines by remember { mutableStateOf(emptyList<LyricLine>()) }
    var lyricLoading by remember { mutableStateOf(false) }
    // 播放页动作弹窗：加歌单 / 下载选音质 / 切换音质
    var playerAddItem by remember { mutableStateOf<MediaItem?>(null) }
    var playerDlItem by remember { mutableStateOf<MediaItem?>(null) }
    var playerQItem by remember { mutableStateOf<MediaItem?>(null) }
    var lyricFullscreen by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(playbackController) {
        // 将 ExoPlayer 异步错误转发到 UI 展示
        val off = playbackController.addErrorListener { msg ->
            playerError = msg
            playerErrorRetry = true
            nowPlaying?.let { if (it.loading) nowPlaying = it.copy(loading = false) }
        }
        // 开始出声时停掉加载动画
        val offAudible = playbackController.addAudibleListener {
            nowPlaying?.let { if (it.loading) nowPlaying = it.copy(loading = false) }
        }
        onDispose { off(); offAudible(); playbackController.release() }
    }

    // 切歌令牌：过期解析结果直接丢弃，避免后到的旧结果覆盖当前歌曲
    var playToken by remember { mutableStateOf(0) }
    // 当前播放解析任务：新请求发起前先取消上一笔
    var playRequestJob: Job? by remember { mutableStateOf<Job?>(null) }

    suspend fun resolveAndPlay(item: MediaItem, index: Int, excludeQualities: Set<String> = emptySet(), token: Int) {
        // 解析在后台线程执行
        val np: NowPlaying? = kotlinx.coroutines.withContext(Dispatchers.IO) {
            // 遗留兼容：platform 为 BakaMusic 的内置演示曲自带直链，无需插件解析
            if (item.platform == "BakaMusic") {
                val url = item.mediaUrl?.takeIf { it.startsWith("http", true) }
                    ?: run { playError = "该歌曲暂无可用音源"; return@withContext null }
                val best = item.qualities.keys.maxByOrNull(::qualityRankOf) ?: "320k"
                return@withContext NowPlaying(item, url, best, null)
            }
            if (!playbackController.awaitConnected()) { playError = "播放器连接失败，请重试"; return@withContext null }
            val key = "${item.platform}:${item.id}"
            // 插件变更后旧解析地址失效，先按指纹清空缓存
            val genNow = sourceService.dataVersion()
            if (resolvedCacheGen != genNow) {
                resolvedCache.clear()
                resolvedCacheGen = genNow
            }
            // 强制重试（降质）时跳过缓存
            if (excludeQualities.isEmpty()) resolvedCache[key]?.let { return@withContext it }
            // 以默认播放音质起播，失败时向更低音质降级；整体限时，过期请求丢弃
            val (res, actualQuality) = try {
                withTimeout(PLAY_RESOLVE_TIMEOUT_MS) {
                    sourceService.resolveForPlaybackDetailed(item, defaultPlayQuality, excludeQualities)
                }
            } catch (e: MediaResolveTimeoutException) {
                // 单次播放请求超时：弹带重试的 3003 弹窗
                if (token == playToken) {
                    if (nowPlaying?.key == "${item.platform}:${item.id}") nowPlaying = nowPlaying?.copy(loading = false)
                    playerError = "播放器错误 3003：Source error"
                    playerErrorRetry = true
                }
                return@withContext null
            } catch (e: TimeoutCancellationException) {
                // 整轮解析超时：同样弹带重试的 3003 弹窗
                if (token == playToken) {
                    if (nowPlaying?.key == "${item.platform}:${item.id}") nowPlaying = nowPlaying?.copy(loading = false)
                    playerError = "播放器错误 3003：Source error"
                    playerErrorRetry = true
                }
                return@withContext null
            } catch (e: CancellationException) {
                // 请求被取消：过期请求静默丢弃，仅当前请求需要停掉加载动画并提示
                if (token == playToken && nowPlaying?.key == "${item.platform}:${item.id}") {
                    nowPlaying = nowPlaying?.copy(loading = false)
                    playError = "该歌曲暂无可用音源（${item.platform}）"
                }
                throw e
            } catch (e: Throwable) {
                null
            } ?: run { if (token == playToken) { if (nowPlaying?.key == "${item.platform}:${item.id}") nowPlaying = nowPlaying?.copy(loading = false); playerError = "该歌曲暂无可用音源"; playerErrorRetry = false }; return@withContext null }
            val url = res.mediaUrl?.takeIf { it.startsWith("http", true) }
                ?: run { if (token == playToken) { if (nowPlaying?.key == "${item.platform}:${item.id}") nowPlaying = nowPlaying?.copy(loading = false); playerError = "该歌曲暂无可用音源"; playerErrorRetry = false }; return@withContext null }
            NowPlaying(item, url, actualQuality, res.qualities[actualQuality]?.size, res.mediaHeaders).also { resolvedCache[key] = it }
        }
        if (token != playToken) return
        if (np == null) {
            // 解析失败：停掉加载动画，错误已由弹窗展示
            if (nowPlaying?.key == "${item.platform}:${item.id}") nowPlaying = nowPlaying?.copy(loading = false)
            return
        }
        queueIndex = index
        nowPlaying = np
        positionMs = 0
        durationMs = item.durationMs
        runCatching { playbackController.play(np.item.copy(mediaUrl = np.mediaUrl, mediaHeaders = np.headers)) }
            .onFailure { playError = "播放失败：${it.message ?: "无可用音源"}" }
    }

    fun startTrack(item: MediaItem, index: Int, excludeQualities: Set<String> = emptySet()) {
        playToken++
        val token = playToken
        // 先清空播放器队列并取消上一笔解析请求，再发起新请求
        playbackController.clear()
        playRequestJob?.cancel()
        sourceService.cancelPendingRequests()
        playing = false
        positionMs = 0
        durationMs = item.durationMs
        // 点按即更新队列位置，保证上一首/下一首基于最新位置导航
        queueIndex = index
        val best = item.qualities.keys.maxByOrNull(::qualityRankOf) ?: defaultPlayQuality
        nowPlaying = NowPlaying(item, "", best, item.qualities[best]?.size, loading = true)
        playRequestJob = scope.launch { resolveAndPlay(item, index, excludeQualities, token) }
    }

    /** 播放键统一入口：无可用地址时重载当前歌曲，加载中忽略，否则暂停/继续 */
    fun onTogglePlay() {
        val cur = nowPlaying
        if (cur != null && cur.mediaUrl.isBlank() && !cur.loading) {
            startTrack(cur.item, queueIndex)
            return
        }
        if (cur?.loading == true) return
        playbackController.toggle()
    }

    fun playItems(items: List<MediaItem>, index: Int) {
        if (items.isEmpty() || index !in items.indices) return
        playQueue.clear(); playQueue.addAll(items)
        startTrack(items[index], index)
    }

    fun playMediaItem(item: MediaItem, context: List<MediaItem>) {
        // 当前列表整体进入队列，支持上一首/下一首
        val idx = context.indexOfFirst { it.platform == item.platform && it.id == item.id }
        if (idx >= 0) playItems(context, idx) else playItems(listOf(item), 0)
    }

    fun playAt(index: Int) {
        if (index !in playQueue.indices) return
        if (index == queueIndex && nowPlaying != null && nowPlaying?.loading == false) return
        startTrack(playQueue[index], index)
    }

    fun advance(delta: Int, silent: Boolean = false) {
        if (playQueue.isEmpty()) return
        val next = (queueIndex + delta).coerceIn(playQueue.indices)
        if (next == queueIndex) {
            if (!silent) {
                val msg = if (delta > 0) "已是最后一首" else "已是第一首"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        startTrack(playQueue[next], next)
    }

    // 播放模式：0顺序播放 1单曲循环 2随机播放
    var playMode by rememberSaveable { mutableStateOf(0) }

    fun stepNext() {
        if (playMode == 2 && playQueue.size > 1) {
            val ni = playQueue.indices.filter { it != queueIndex }.random()
            startTrack(playQueue[ni], ni)
            return
        }
        advance(1)
    }

    fun stepPrev() {
        advance(-1)
    }

    /** 播放失败后排除当前及更高音质，按更低音质重试 */
    fun retryLowerQuality(cur: NowPlaying) {
        playerError = null
        val order = QUALITY_KEYS_ORDERED
        val curIdx = order.indexOf(cur.qualityKey)
        val exclude = (if (curIdx >= 0) order.subList(curIdx, order.size) else listOf(cur.qualityKey)).toSet()
        startTrack(cur.item, queueIndex, exclude)
    }

    /** 按选中音质解析后入队系统下载 */
    fun downloadItem(item: MediaItem, quality: String) {
        scope.launch {
            val resolved = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) { sourceService.resolveByKey(item, quality) }
            }.getOrNull()
            if (resolved?.mediaUrl?.startsWith("http", true) == true) {
                runCatching { DownloadHelper.enqueueByKey(context, resolved, quality) }
                    .onFailure { playError = "下载失败：${it.message}" }
            } else {
                playError = "该音质暂无可用下载地址"
            }
        }
    }

    /** 切换音质：重解析指定音质，从当前播放位置继续播放 */
    fun switchQuality(item: MediaItem, newKey: String) {
        playerQItem = null
        // 取消上一笔解析请求，避免旧结果覆盖新请求
        playRequestJob?.cancel()
        sourceService.cancelPendingRequests()
        playRequestJob = scope.launch {
            val keepPos = playbackController.snapshot().positionMs
            val itemKey = "${item.platform}:${item.id}"
            // 切换期间显示加载动画，出声后恢复
            nowPlaying?.takeIf { it.key == itemKey }?.let { nowPlaying = it.copy(loading = true) }
            fun fail3003NoRetry() {
                if (nowPlaying?.key == itemKey) nowPlaying = nowPlaying?.copy(loading = false)
                playerError = "该歌曲暂无可用音源"
                playerErrorRetry = false
            }
            fun fail(msg: String) {
                playError = msg
                if (nowPlaying?.key == itemKey) nowPlaying = nowPlaying?.copy(loading = false)
            }
            val pair = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    withTimeout(PLAY_RESOLVE_TIMEOUT_MS) {
                        sourceService.resolveForPlaybackDetailed(item, newKey)
                    }
                }
            } catch (e: MediaResolveTimeoutException) {
                if (nowPlaying?.key == itemKey) nowPlaying = nowPlaying?.copy(loading = false)
                playerError = "播放器错误 3003：Source error"
                playerErrorRetry = true
                return@launch
            } catch (e: TimeoutCancellationException) {
                if (nowPlaying?.key == itemKey) nowPlaying = nowPlaying?.copy(loading = false)
                playerError = "播放器错误 3003：Source error"
                playerErrorRetry = true
                return@launch
            } catch (e: CancellationException) {
                return@launch // 请求被新请求取代，静默终止
            } catch (e: Throwable) {
                null
            } ?: run { fail3003NoRetry(); return@launch }
            val (res, q) = pair
            val url = res.mediaUrl?.takeIf { it.startsWith("http", true) }
                ?: run { fail3003NoRetry(); return@launch }
            val np = NowPlaying(item, url, q, res.qualities[q]?.size, res.mediaHeaders)
            // 插件指纹已变化时不写入缓存，下次点播重新解析
            if (resolvedCacheGen == sourceService.dataVersion()) {
                resolvedCache[itemKey] = np
            }
            nowPlaying = np
            runCatching { playbackController.play(item.copy(mediaUrl = url, mediaHeaders = res.mediaHeaders)) }
                .onFailure { fail("播放失败：${it.message}"); return@launch }
            // 待新地址就绪（时长可读）后回到原位置继续播
            repeat(40) {
                delay(250)
                val s = runCatching { playbackController.snapshot() }.getOrNull()
                if (s != null && s.durationMs > 0) {
                    playbackController.seekTo(keepPos)
                    return@launch
                }
            }
            playbackController.seekTo(keepPos)
        }
    }

    // 状态轮询：同步播放/暂停/进度/时长/缓冲态，并处理自动下一首；
    // 加载动画结束由播放器出声事件驱动
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            val s = runCatching { playbackController.snapshot() }.getOrNull() ?: continue
            playing = s.isPlaying
            positionMs = s.positionMs
            buffering = s.buffering
            if (s.durationMs > 0) durationMs = s.durationMs
            if (s.ended) {
                val cur = nowPlaying
                val guard = "${cur?.key}@$queueIndex"
                if (cur != null && lastEndedKey != guard) {
                    lastEndedKey = guard
                    when (playMode) {
                        // 单曲循环：重播本首
                        1 -> if (queueIndex in playQueue.indices) {
                            lastEndedKey = null
                            startTrack(playQueue[queueIndex], queueIndex)
                        }
                        // 随机播放：随机下一首
                        2 -> if (playQueue.size > 1) {
                            val ni = playQueue.indices.filter { it != queueIndex }.random()
                            startTrack(playQueue[ni], ni)
                        }
                        // 顺序播放：播完停在末尾
                        else -> advance(1, silent = true)
                    }
                }
            }
        }
    }

    // 歌词跟随当前歌曲加载，整体限时避免单次请求挂起
    LaunchedEffect(nowPlaying?.key) {
        lyricLines = emptyList()
        val item = nowPlaying?.item ?: return@LaunchedEffect
        lyricLoading = true
        lyricLines = runCatching {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(20000) { sourceService.lyrics(item) }
            }
        }.getOrNull().let { LyricParser.parse(it) }
        lyricLoading = false
    }

    val sheetSongs by (playlistSheet?.let { name -> remember(name) { repository.playlistSongs(name) } }
        ?: remember { flowOf(emptyList<SongRecord>()) })
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f)) {
                HomeScreen(
                    sourceService = sourceService,
                    favorites = favorites,
                    playlistNames = userPlaylistNames,
                    onPlayMedia = ::playMediaItem,
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
                now = nowPlaying, playing = playing, buffering = buffering,
                positionMs = positionMs, durationMs = durationMs,
                onPrev = { stepPrev() },
                onToggle = { onTogglePlay() },
                onNext = { stepNext() },
                onOpenPlayer = { showQueue = false; expanded = true }
            )
        }
    }
    playError?.let { msg ->
        AlertDialog(onDismissRequest = { playError = null }, title = { Text("播放提示") }, text = { Text(msg) }, confirmButton = { TextButton({ playError = null }) { Text("确定") } })
    }
    playerError?.let { msg ->
        val cur = nowPlaying
        val canRetry = cur != null && playerErrorRetry
        AlertDialog(
            onDismissRequest = { playerError = null },
            title = { Text("播放失败") },
            text = { Text(msg) },
            confirmButton = {
                if (canRetry) {
                    TextButton(
                        onClick = { retryLowerQuality(cur!!) },
                    ) { Text("换更低音质重试") }
                } else {
                    TextButton(onClick = { playerError = null; stepNext() }) { Text("下一曲") }
                }
            },
            dismissButton = {
                Row {
                    if (canRetry) {
                        TextButton(onClick = { playerError = null; stepNext() }) { Text("下一曲") }
                    }
                    TextButton(onClick = { playerError = null }) { Text("关闭") }
                }
            }
        )
    }
    if (importNotice) {
        AlertDialog(onDismissRequest = { importNotice = false }, title = { Text("导入歌单") }, text = { Text("歌单导入功能暂未开放，敬请期待") }, confirmButton = { TextButton({ importNotice = false }) { Text("确定") } })
    }
    if (expanded) PlayerSheet(
        now = nowPlaying, playing = playing,
        queue = playQueue.toList(), queueIndex = queueIndex,
        positionMs = positionMs, durationMs = durationMs,
        lyricLines = lyricLines, lyricLoading = lyricLoading,
        onToggle = { onTogglePlay() },
        onPrev = { stepPrev() }, onNext = { stepNext() },
        onSeek = { pos -> playbackController.seekTo(pos) },
        onPlayAt = ::playAt,
        isFavorite = nowPlaying?.let { favorites.contains(it.key) } == true,
        onToggleFavorite = { nowPlaying?.let { scope.launch(Dispatchers.IO) { repository.toggleFavorite(it.item) } } },
        onOpenAddPlaylist = { playerAddItem = nowPlaying?.item },
        onOpenDownload = { playerDlItem = nowPlaying?.item },
        onOpenSwitchQuality = { playerQItem = nowPlaying?.item },
        buffering = buffering,
        onClose = { expanded = false; showQueue = false },
        showQueue = showQueue, onToggleQueue = { showQueue = !showQueue },
        playMode = playMode, onModeChange = { playMode = it },
        onOpenLyricFullscreen = { lyricFullscreen = true }
    )
    // 全屏歌词：与半屏共用同一 LyricsView
    if (lyricFullscreen) {
        Dialog(
            onDismissRequest = { lyricFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("歌词", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(
                                nowPlaying?.let { "${it.item.title} · ${it.item.artist}" } ?: "",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { lyricFullscreen = false }) { Icon(Icons.Default.FullscreenExit, "退出全屏") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LyricsView(
                            lines = lyricLines, positionMs = positionMs,
                            loading = lyricLoading && nowPlaying != null,
                            markerFraction = 0.24f,
                            fontScale = 1.35f, centered = true
                        )
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
            text = { Column { options.forEach { playlist -> TextButton(onClick = { scope.launch(Dispatchers.IO) { repository.addToPlaylist(playlist, item) }; playerAddItem = null }, modifier = Modifier.fillMaxWidth()) { Text(playlist) } } } },
            confirmButton = {})
    }
    playerDlItem?.let { item ->
        AlertDialog(
            onDismissRequest = { playerDlItem = null },
            title = { Text("选择下载音质") },
            text = {
                Column {
                    item.qualities.keys.sortedByDescending(::qualityRank).ifEmpty { listOf("320k") }.forEach { quality ->
                        TextButton(
                            onClick = { playerDlItem = null; downloadItem(item, quality) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("$quality${item.qualities[quality]?.size?.let { " · ${formatSize(it)}" } ?: ""}") }
                    }
                }
            },
            confirmButton = {})
    }
    playerQItem?.let { item ->
        AlertDialog(
            onDismissRequest = { playerQItem = null },
            title = { Text("切换播放音质（从当前位置继续）") },
            text = {
                Column {
                    val cur = nowPlaying?.takeIf { it.key == "${item.platform}:${item.id}" }?.qualityKey
                    item.qualities.keys.sortedByDescending(::qualityRank).ifEmpty { listOf("320k") }.forEach { quality ->
                        TextButton(
                            onClick = { switchQuality(item, quality) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("${if (quality == cur) "● " else ""}$quality${item.qualities[quality]?.size?.let { " · ${formatSize(it)}" } ?: ""}") }
                    }
                }
            },
            confirmButton = {})
    }
    playlistSheet?.let { name ->
        PlaylistSheet(
            name = name, songs = sheetSongs,
            onPlay = { record ->
                val items = sheetSongs.map { it.toMediaItem() }
                val idx = items.indexOfFirst { it.platform == record.platform && it.id == record.id }
                playlistSheet = null
                if (idx >= 0) playItems(items, idx) else playMediaItem(record.toMediaItem(), emptyList())
            },
            onRemove = { record -> scope.launch(Dispatchers.IO) { repository.removeFromPlaylist(name, record.key) } },
            onDeletePlaylist = if (name == AppRepository.FAVORITE_SHEET) null else ({
                scope.launch(Dispatchers.IO) { repository.deletePlaylist(name) }
                playlistSheet = null
            }),
            onClose = { playlistSheet = null }
        )
    }
}

@Composable private fun HomeScreen(
    sourceService: MusicSourceService,
    favorites: Set<String>,
    playlistNames: List<String>,
    onPlayMedia: (MediaItem, List<MediaItem>) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onImportPlaylist: () -> Unit,
    onSettings: () -> Unit,
    onInstallPlugin: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val repository = remember { AppRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    // 搜索提交计数：相同关键词重复提交也能重新触发搜索
    var searchNonce by rememberSaveable { mutableStateOf(0) }
    // 音源芯片仅展示注册表元信息；实际搜索按平台名取最新适配器
    var sources by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var sourceNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var sourcesLoaded by remember { mutableStateOf(false) }
    var selectedSourcePlatform by rememberSaveable { mutableStateOf<String?>(null) }
    var pluginSongs by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    // 搜索分页状态：滑到底部 page+1 追加；分页失败走独立的底部提示，不复用顶部 searchError
    var searchPage by remember { mutableStateOf(1) }
    var searchIsEnd by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadMoreError by remember { mutableStateOf<String?>(null) }
    var qualityDialog by remember { mutableStateOf<MediaItem?>(null) }
    var playlistDialog by remember { mutableStateOf<MediaItem?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var createSheetDialog by rememberSaveable { mutableStateOf(false) }
    var createSheetName by rememberSaveable { mutableStateOf("") }
    // 每日推荐：独立于搜索的音源/榜单选择
    var dailyPlatform by rememberSaveable { mutableStateOf<String?>(null) }
    var dailyGroups by remember { mutableStateOf<List<TopListGroup>>(emptyList()) }
    var dailyBoard by remember { mutableStateOf<TopListItem?>(null) }
    var dailySongs by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var dailyLoading by remember { mutableStateOf(false) }
    var dailyError by remember { mutableStateOf<String?>(null) }
    // 无封面歌曲的后台回填任务，随榜单切换取消
    var coverFillJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    // UI 可用插件（启用中）为空时统一显示安装引导；搜索首页/分页据此保持一致
    val hasNoPlugin = sourcesLoaded && sources.isEmpty()
    suspend fun reloadSources() {
        // 先重载运行时，再读最新注册表展示音源芯片
        runCatching { sourceService.refresh() }
        val listed = runCatching { sourceService.installedPlugins() }.getOrDefault(sources)
        sources = listed
        sourceNames = listed.map { it.name }
        // 默认用第一优先级音源
        if (selectedSourcePlatform == null || listed.none { it.name == selectedSourcePlatform }) {
            selectedSourcePlatform = listed.firstOrNull()?.name ?: selectedSourcePlatform
        }
        // 每日推荐默认音源：当天已存选择且仍可用则沿用，否则用第一优先级
        val savedDailyPlatform = context.getSharedPreferences(DAILY_PREFS, android.content.Context.MODE_PRIVATE)
            .takeIf { it.getString(DAILY_KEY_DATE, null) == todayString() }
            ?.getString(DAILY_KEY_PLATFORM, null)
        if (dailyPlatform == null || listed.none { it.name == dailyPlatform }) {
            dailyPlatform = savedDailyPlatform?.takeIf { sp -> listed.any { it.name == sp } }
                ?: listed.firstOrNull()?.name ?: dailyPlatform
        }
        sourcesLoaded = true
    }
    LaunchedEffect(Unit) { reloadSources() }
    // 从插件管理页返回时刷新音源
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch { reloadSources() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(submittedQuery, selectedSourcePlatform, sourcesLoaded, searchNonce, sources) {
        if (submittedQuery.isBlank()) { pluginSongs = emptyList(); searchError = null; loadMoreError = null; searchPage = 1; searchIsEnd = true; return@LaunchedEffect }
        if (!sourcesLoaded) return@LaunchedEffect
        if (sources.isEmpty()) {
            reloadSources()
        }
        // 新一轮首页搜索先复位分页与报错：插件安装返回后旧分页状态不再与新结果叠加
        searchPage = 1
        searchIsEnd = true
        loadMoreError = null
        searchError = null
        searching = true
        val targetPlatform = selectedSourcePlatform ?: sources.firstOrNull()?.name
        if (sources.isEmpty() || targetPlatform == null) {
            searching = false
            pluginSongs = emptyList()
            searchError = null
            return@LaunchedEffect
        }
        // 先加载运行时再使用：searchByPlatform 内部 ensureLoaded 串行等待重载完成
        val querySnapshot = submittedQuery
        val platformSnapshot = selectedSourcePlatform
        try {
            val page = sourceService.searchByPlatform(targetPlatform, submittedQuery)
            // 查询/音源已变则丢弃晚到结果
            if (querySnapshot != submittedQuery || platformSnapshot != selectedSourcePlatform) return@LaunchedEffect
            pluginSongs = page.data
            searchPage = 1
            searchIsEnd = page.isEnd || page.data.isEmpty()
            searchError = if (page.data.isEmpty()) "在 $targetPlatform 没有找到“$submittedQuery”，可切换上方音源重试" else null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (querySnapshot != submittedQuery || platformSnapshot != selectedSourcePlatform) return@LaunchedEffect
            pluginSongs = emptyList()
            // 无插件由安装引导统一承载，不再显示旧条幅
            searchError = if (isNoPluginError(error)) {
                if (sources.isEmpty()) null else "音源插件加载中，请稍后重试"
            } else {
                error.message ?: "音源搜索失败"
            }
            searchIsEnd = true
        } finally {
            searching = false
        }
    }
    // 每日推荐榜单分组：随音源切换重拉，就绪后按当日规则选榜
    LaunchedEffect(dailyPlatform, sourcesLoaded) {
        val platform = dailyPlatform
        if (platform == null || !sourcesLoaded) return@LaunchedEffect
        dailyLoading = true
        dailyError = null
        try {
            val groups = sourceService.topListsByPlatform(platform)
            if (platform != dailyPlatform) return@LaunchedEffect
            dailyGroups = groups
            val boards = groups.flatMap { it.items }
            if (boards.isEmpty()) {
                dailyBoard = null
                dailySongs = emptyList()
                dailyError = "$platform 暂无可用榜单"
            } else {
                val pick = resolveDailyBoard(context, platform, boards)
                // 同一榜单复选时详情不重载，此处直接结束加载态
                val changed = dailyBoard != pick
                dailyBoard = pick
                if (!changed) dailyLoading = false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (platform != dailyPlatform) return@LaunchedEffect
            dailyGroups = emptyList()
            dailyBoard = null
            dailySongs = emptyList()
            dailyError = "榜单加载失败：${error.message ?: "网络异常"}"
        } finally {
            // 榜单详情紧接着加载，加载态交由详情管理，避免闪烁
            if (dailyBoard == null) dailyLoading = false
        }
    }
    // 每日推荐榜单详情：随音源/榜单切换重拉
    LaunchedEffect(dailyPlatform, dailyBoard) {
        val platform = dailyPlatform
        val board = dailyBoard
        coverFillJob?.cancel()
        if (platform == null || board == null) return@LaunchedEffect
        dailyLoading = true
        dailyError = null
        try {
            val page = sourceService.topListDetailByPlatform(platform, board)
            if (platform != dailyPlatform || board != dailyBoard) return@LaunchedEffect
            dailySongs = page.data
            if (page.data.isEmpty()) dailyError = "“${board.title}”暂无歌曲，可切换上方榜单重试"
            // 封面回填：后台对无封面歌曲逐个搜索补封面，串行逐行呈现，随切换取消
            coverFillJob = if (page.data.any { it.artwork.isNullOrBlank() }) {
                val snapshot = page.data
                scope.launch(Dispatchers.IO) {
                    for (song in snapshot) {
                        ensureActive()
                        if (submittedQuery.isNotBlank() || platform != dailyPlatform || board != dailyBoard) return@launch
                        if (!song.artwork.isNullOrBlank()) continue
                        val q = "${song.title} ${song.artist}".trim()
                        if (q.isBlank()) continue
                        // 单首失败跳过本首，不中断整轮
                        val hit = runCatching { sourceService.searchByPlatform(platform, q) }
                            .getOrNull()?.data?.firstOrNull { it.id == song.id && !it.artwork.isNullOrBlank() }
                            ?: continue
                        if (submittedQuery.isNotBlank() || platform != dailyPlatform || board != dailyBoard) return@launch
                        withContext(Dispatchers.Main) {
                            if (platform == dailyPlatform && board == dailyBoard) {
                                dailySongs = dailySongs.map {
                                    if (it.platform == song.platform && it.id == song.id && it.artwork.isNullOrBlank())
                                        it.copy(artwork = hit.artwork, qualities = hit.qualities.ifEmpty { it.qualities })
                                    else it
                                }
                            }
                        }
                    }
                }
            } else null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (platform != dailyPlatform || board != dailyBoard) return@LaunchedEffect
            dailySongs = emptyList()
            dailyError = "榜单加载失败：${error.message ?: "网络异常"}"
        } finally {
            if (platform == dailyPlatform && board == dailyBoard) dailyLoading = false
        }
    }
    // 滑到底部自动加载下一页并追加：无插件时直接停掉，不再产生“加载下一页失败”条幅
    fun loadMore() {
        if (loadingMore || searching || searchIsEnd || submittedQuery.isBlank() || !sourcesLoaded || hasNoPlugin) return
        if (sources.isEmpty()) return
        if (searchPage >= 50) { searchIsEnd = true; return }
        val target = selectedSourcePlatform ?: sources.firstOrNull()?.name ?: return
        // 目标音源已不在可用列表时不再翻页，避免用过期适配器请求
        if (sources.none { it.name == target }) return
        loadingMore = true
        loadMoreError = null
        val q = submittedQuery
        val p = selectedSourcePlatform
        val next = searchPage + 1
        scope.launch {
            try {
                val page = sourceService.searchByPlatform(target, q, next)
                // 查询/音源已变则丢弃晚到结果
                if (q != submittedQuery || p != selectedSourcePlatform) return@launch
                val known = pluginSongs.map { "${it.platform}:${it.id}" }.toSet()
                pluginSongs = pluginSongs + page.data.filterNot { known.contains("${it.platform}:${it.id}") }
                searchPage = next
                searchIsEnd = page.isEnd || page.data.isEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (q != submittedQuery || p != selectedSourcePlatform) return@launch
                // 安装引导覆盖无插件情形：停掉自动翻页且不弹顶部条幅；其余失败只在底部展示并支持重试
                if (sources.isEmpty() || isNoPluginError(error)) {
                    searchIsEnd = true
                    loadMoreError = null
                } else {
                    loadMoreError = "请检查网络或切换音源后重试"
                }
            } finally {
                loadingMore = false
            }
        }
    }
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total > 0 && info.visibleItemsInfo.lastOrNull()?.index == total - 1
        }
    }
    LaunchedEffect(atEnd, pluginSongs.size, searchIsEnd, loadingMore, submittedQuery, searching, sources) {
        if (atEnd) loadMore()
    }
    Column(Modifier.fillMaxSize()) {
        // 顶部搜索栏与设置按钮浮动显示，不随列表滑动
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), placeholder = { Text("搜索歌曲") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; submittedQuery = "" }) { Icon(Icons.Default.Clear, "清除") } }, shape = RoundedCornerShape(30.dp), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submittedQuery = query.trim(); searchNonce++; focusManager.clearFocus(); keyboardController?.hide(); scope.launch { repository.addSearch(query.trim()) } })); Spacer(Modifier.width(10.dp)); FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Settings, "设置") } }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text("早上好", Modifier.padding(horizontal = 22.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("发现属于你的音乐", Modifier.padding(horizontal = 22.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { HomeTile("我喜欢的音乐", Icons.Default.Favorite, Color(0xffffd9e2), Modifier.weight(1f), { onOpenPlaylist(AppRepository.FAVORITE_SHEET) }); HomeTile("导入歌单", Icons.Default.Add, Color(0xffd8f0e3), Modifier.weight(1f), onImportPlaylist) } }
        item { Text("我的歌单", Modifier.padding(horizontal = 22.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            LazyRow(contentPadding = PaddingValues(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { HomeTile("新建歌单", Icons.Default.Add, Color(0xffd8f0e3), Modifier.width(150.dp), { createSheetName = ""; createSheetDialog = true }) }
                items(playlistNames) { name ->
                    HomeTile(
                        name, if (name == AppRepository.FAVORITE_SHEET) Icons.Default.Favorite else Icons.Default.LibraryMusic,
                        if (name == AppRepository.FAVORITE_SHEET) Color(0xffffd9e2) else Color(0xffe7dcff),
                        Modifier.width(150.dp), { onOpenPlaylist(name) }
                    )
                }
            }
        }
        // 无可用插件（未安装或全部禁用）时推荐/搜索列表统一显示安装引导
        if (submittedQuery.isNotBlank() && !hasNoPlugin) item { LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (sources.isEmpty()) items(sourceNames) { name -> FilterChip(selected = false, enabled = false, onClick = {}, label = { Text(name) }) } else items(sources) { source -> FilterChip(selected = source.name == selectedSourcePlatform, onClick = { selectedSourcePlatform = source.name }, label = { Text(source.name) }) } } }
        // 每日推荐音源切换芯片
        if (submittedQuery.isBlank() && !hasNoPlugin) item { LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (sources.isEmpty()) items(sourceNames) { name -> FilterChip(selected = false, enabled = false, onClick = {}, label = { Text(name) }) } else items(sources) { source -> FilterChip(selected = source.name == dailyPlatform, onClick = { if (dailyPlatform != source.name) { dailyPlatform = source.name; dailyGroups = emptyList(); dailyBoard = null; dailySongs = emptyList(); dailyError = null } }, label = { Text(source.name) }) } } }
        item { Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (submittedQuery.isBlank()) "每日推荐" else "搜索结果", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold); if (submittedQuery.isBlank() && !hasNoPlugin) DailyBoardDropdown(boards = dailyGroups.flatMap { it.items }, selected = dailyBoard, enabled = !dailyLoading, onSelect = { board -> dailyPlatform?.let { saveDailyBoard(context, it, board) }; dailyBoard = board }) } }
        if (!hasNoPlugin && searchError != null) item { Text(searchError ?: "", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        if (!hasNoPlugin && submittedQuery.isBlank() && dailyError != null) item { Text(dailyError ?: "", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        if (hasNoPlugin) item { NoPluginPrompt(onInstallClick = onInstallPlugin) }
        else if ((submittedQuery.isNotBlank() && searching) || (submittedQuery.isBlank() && dailyLoading)) items(8) { SongRowSkeleton() }
        else if (submittedQuery.isBlank()) items(dailySongs) { item -> PluginSongRow(item, favorites.contains("${item.platform}:${item.id}"), { onPlayMedia(item, dailySongs) }, { scope.launch(Dispatchers.IO) { repository.toggleFavorite(item) } }, { qualityDialog = item }, { playlistDialog = item }) }
        else items(pluginSongs) { item -> PluginSongRow(item, favorites.contains("${item.platform}:${item.id}"), { onPlayMedia(item, pluginSongs) }, { scope.launch(Dispatchers.IO) { repository.toggleFavorite(item) } }, { qualityDialog = item }, { playlistDialog = item }) }
        // 分页底部提示区：分页失败只在底部展示并支持重试，不再复用顶部条幅
        if (!hasNoPlugin && submittedQuery.isNotBlank() && pluginSongs.isNotEmpty() && (!searchIsEnd || loadMoreError != null)) {
            item {
                Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                    if (loadingMore) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在加载下一页", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (loadMoreError != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("下一页加载失败，$loadMoreError", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { loadMore() }) { Text("重试") }
                        }
                    } else {
                        Text("上滑加载更多", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }
    }
    qualityDialog?.let { item ->
        AlertDialog(
            onDismissRequest = { qualityDialog = null },
            title = { Text("选择下载音质") },
            text = {
                Column {
                    val keys = item.qualities.keys.sortedByDescending(::qualityRank).ifEmpty { listOf("320k") }
                    keys.forEach { quality ->
                        TextButton(
                            onClick = {
                                qualityDialog = null
                                scope.launch {
                                    val resolved = runCatching { sourceService.resolveByKey(item, quality) }.getOrNull()
                                    if (resolved?.mediaUrl?.startsWith("http", true) == true) {
                                        runCatching { DownloadHelper.enqueueByKey(context, resolved, quality) }
                                            .onFailure { downloadError = "下载失败：${it.message}" }
                                    } else {
                                        downloadError = "该音质暂无可用下载地址"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("$quality${item.qualities[quality]?.size?.let { " · ${formatSize(it)}" } ?: ""}") }
                    }
                }
            },
            confirmButton = {})
    }
    downloadError?.let { msg ->
        AlertDialog(onDismissRequest = { downloadError = null }, title = { Text("下载提示") }, text = { Text(msg) }, confirmButton = { TextButton({ downloadError = null }) { Text("确定") } })
    }
    playlistDialog?.let { item ->
        // 无自建歌单时提供“默认歌单”占位项，选中即创建并添加
        val options = playlistNames.ifEmpty { listOf(AppRepository.DEFAULT_SHEET) }
        AlertDialog(onDismissRequest = { playlistDialog = null }, title = { Text("添加到我的歌单") }, text = { Column { options.forEach { playlist -> TextButton(onClick = { scope.launch(Dispatchers.IO) { repository.addToPlaylist(playlist, item) }; playlistDialog = null }, modifier = Modifier.fillMaxWidth()) { Text(playlist) } } } }, confirmButton = {})
    }
    if (createSheetDialog) {
        AlertDialog(
            onDismissRequest = { createSheetDialog = false },
            title = { Text("新建歌单") },
            text = { OutlinedTextField(value = createSheetName, onValueChange = { createSheetName = it }, label = { Text("歌单名称") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch(Dispatchers.IO) { repository.createPlaylist(createSheetName) }; createSheetDialog = false },
                    enabled = createSheetName.isNotBlank()
                ) { Text("创建") }
            },
            dismissButton = { TextButton({ createSheetDialog = false }) { Text("取消") } }
        )
    }
}

@Composable private fun HomeTile(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) { Card(modifier.height(84.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) { Icon(imageVector = icon, contentDescription = null, tint = Color(0xff314568), modifier = Modifier.size(26.dp)); Text(title, fontWeight = FontWeight.Bold, color = Color(0xff25334c), maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
@Composable private fun PluginSongRow(item: MediaItem, favorite: Boolean, play: () -> Unit, toggleFavorite: () -> Unit, download: () -> Unit, addPlaylist: () -> Unit) {
    // 显示支持的最高音质 + 大小
    val quality = item.qualities.keys.maxByOrNull { qualityRankOf(it) }
    val info = item.qualities[quality]
    ListItem(
        headlineContent = { Text(item.title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text("${item.artist} · ${formatDuration(item.durationMs)} · ${quality ?: "未知音质"}${info?.size?.let { " · ${formatSize(it)}" } ?: ""} · ${item.platform}") },
        leadingContent = { Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xffdbe1ff)), Alignment.Center) { if (item.artwork.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = Color.White) else AsyncImage(model = item.artwork, contentDescription = item.title, modifier = Modifier.fillMaxSize()) } },
        trailingContent = { Row(modifier = Modifier.offset(x = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = toggleFavorite, modifier = Modifier.size(40.dp)) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏") }; IconButton(onClick = download, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Download, "下载") }; IconButton(onClick = addPlaylist, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.PlaylistAdd, "添加歌单") } } },
        modifier = Modifier.clickable(onClick = play).padding(horizontal = 8.dp)
    )
}

/** 歌曲列表动态骨架图：搜索/每日推荐加载时占位展示 */
@Composable private fun SongRowSkeleton() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    val placeholder = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    ListItem(
        headlineContent = { Box(Modifier.fillMaxWidth(0.55f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(placeholder)) },
        supportingContent = { Box(Modifier.fillMaxWidth(0.85f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(placeholder)) },
        leadingContent = { Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(placeholder)) },
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

/** 无可用音源插件时的统一安装引导：推荐列表与搜索结果共用 */
@Composable private fun NoPluginPrompt(onInstallClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("音乐世界，从这里开始", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "安装音源插件，获取音乐信息",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onInstallClick) { Text("安装音源插件") }
    }
}

/** 每日推荐当日选择落盘，跨天失效重选 */
private const val DAILY_PREFS = "daily_recommend"
private const val DAILY_KEY_DATE = "daily_date"
private const val DAILY_KEY_PLATFORM = "daily_platform"
private const val DAILY_KEY_BOARD = "daily_board_id"

private fun todayString(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

/**
 * 每日推荐默认榜单：含“热门”/“热歌”关键词的榜单优先池随机；
 * 多个含关键词则随机其一，全不含则全量随机；结果落盘，当天复用。
 */
private fun resolveDailyBoard(context: android.content.Context, platform: String, boards: List<TopListItem>): TopListItem {
    val prefs = context.getSharedPreferences(DAILY_PREFS, android.content.Context.MODE_PRIVATE)
    if (prefs.getString(DAILY_KEY_DATE, null) == todayString() &&
        prefs.getString(DAILY_KEY_PLATFORM, null) == platform
    ) {
        val savedId = prefs.getString(DAILY_KEY_BOARD, null)
        boards.firstOrNull { it.id == savedId }?.let { return it }
    }
    val hot = boards.filter { it.title.contains("热门") || it.title.contains("热歌") }
    val pick = (hot.ifEmpty { boards }).random()
    saveDailyBoard(context, platform, pick)
    return pick
}

/** 用户手动切榜后同步落盘当日选择 */
private fun saveDailyBoard(context: android.content.Context, platform: String, board: TopListItem) {
    context.getSharedPreferences(DAILY_PREFS, android.content.Context.MODE_PRIVATE).edit()
        .putString(DAILY_KEY_DATE, todayString())
        .putString(DAILY_KEY_PLATFORM, platform)
        .putString(DAILY_KEY_BOARD, board.id)
        .apply()
}

/** 每日推荐榜单下拉框：重名榜单带分组区分 */
@Composable private fun DailyBoardDropdown(
    boards: List<TopListItem>,
    selected: TopListItem?,
    enabled: Boolean,
    onSelect: (TopListItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val titleCounts = remember(boards) { boards.groupingBy { it.title }.eachCount() }
    fun labelOf(board: TopListItem): String =
        if ((titleCounts[board.title] ?: 0) > 1 && board.groupTitle.isNotBlank()) "${board.groupTitle} · ${board.title}"
        else board.title.ifBlank { "未知榜单" }
    Box(modifier = Modifier.offset(x = 8.dp)) {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled && boards.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(
                selected?.let(::labelOf) ?: "选择榜单",
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            Icon(Icons.Default.ArrowDropDown, "选择榜单")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(220.dp).height(300.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            boards.forEach { board ->
                val isSelected = board.id == selected?.id && board.groupTitle == selected.groupTitle
                DropdownMenuItem(
                    text = { Text(labelOf(board), color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                    trailingIcon = { if (isSelected) Icon(Icons.Default.Check, "已选", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.offset(x = (-4).dp)) },
                    contentPadding = PaddingValues(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    onClick = {
                        expanded = false
                        if (!isSelected) onSelect(board)
                    }
                )
            }
        }
    }
}

private fun qualityRank(key: String) = qualityRankOf(key)
/** 无插件类错误统一识别：命中时由安装引导承载，不再弹旧条幅 */
private fun isNoPluginError(error: Throwable): Boolean =
    error.message?.contains("无可用插件") == true
private fun formatDuration(milliseconds: Long): String { if (milliseconds <= 0) return "0:00"; val seconds = milliseconds / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
private fun formatSize(bytes: Long): String = if (bytes <= 0) "未知大小" else if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1024f / 1024f)
@Composable private fun MiniPlayer(
    now: NowPlaying?, playing: Boolean, buffering: Boolean,
    positionMs: Long, durationMs: Long,
    onPrev: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val enabled = now != null
    val showLoading = now?.loading == true || buffering
    val defaultBg = MaterialTheme.colorScheme.secondaryContainer
    val art = now?.item?.artwork
    // 封面取色：无封面时保持默认色，切换歌曲时先回落到默认色
    var artworkBg by remember(art) { mutableStateOf<Color?>(null) }
    val miniPlayerContext = LocalContext.current
    // 禁用硬件位图，否则 getPixel 会抛 HARDWARE 异常导致崩溃
    val painter = rememberAsyncImagePainter(
        model = coil.request.ImageRequest.Builder(miniPlayerContext).data(art).allowHardware(false).build()
    )
    val drawable = (painter.state as? AsyncImagePainter.State.Success)?.result?.drawable
    LaunchedEffect(drawable) {
        artworkBg = runCatching {
            drawable?.let { drawableToMiniPlayerBitmap(it) }?.let { dominantColorFromMiniPlayerBitmap(it) }
        }.getOrNull()
    }
    val containerColor = if (art.isNullOrBlank()) defaultBg else artworkBg ?: defaultBg
    val contentColor = if (containerColor.luminance() > 0.5f) Color.Black else Color.White
    val subContentColor = contentColor.copy(alpha = 0.7f)
    val buttonTint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp)).background(containerColor).clickable(onClick = onOpenPlayer)
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = contentColor,
            trackColor = contentColor.copy(alpha = 0.2f),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xffdbe1ff)), contentAlignment = Alignment.Center) {
                if (art.isNullOrBlank()) Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                else Image(painter = painter, contentDescription = now?.item?.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (showLoading) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(now?.item?.title ?: "暂无播放", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
                Text(
                    now?.let { np ->
                        "${np.item.artist} · ${formatDuration(np.item.durationMs)} · ${MusicQuality.labelOf(np.qualityKey)}${np.size?.let { " · ${formatSize(it)}" } ?: ""} · ${np.item.platform}"
                    } ?: "点击搜索结果开始播放",
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = subContentColor
                )
            }
            Row(modifier = Modifier.offset(x = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = enabled) { Icon(imageVector = Icons.Default.SkipPrevious, contentDescription = "上一首", tint = buttonTint) }
                IconButton(onClick = onToggle, enabled = enabled) { Icon(imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "播放暂停", tint = buttonTint) }
                IconButton(onClick = onNext, enabled = enabled) { Icon(imageVector = Icons.Default.SkipNext, contentDescription = "下一首", tint = buttonTint) }
            }
        }
    }
}

/** Drawable 转 Bitmap：BitmapDrawable 直接复用（硬件位图转一份软件位图），其余类型绘制到新 Bitmap */
private fun drawableToMiniPlayerBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap? {
    if (drawable is android.graphics.drawable.BitmapDrawable) {
        val bmp = drawable.bitmap ?: return null
        return if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) {
            runCatching { bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } else bmp
    }
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 64
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 64
    return runCatching {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bmp
    }.getOrNull()
}

/** 缩小到 16x16 取平均色作为封面代表色，失败返回 null 以便回落默认色，绝不抛异常 */
private fun dominantColorFromMiniPlayerBitmap(src: android.graphics.Bitmap): Color? {
    return runCatching {
        val safe = if (src.config == android.graphics.Bitmap.Config.HARDWARE) {
            src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return null
        } else src
        val bmp = android.graphics.Bitmap.createScaledBitmap(safe, 16, 16, true)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L
        for (x in 0 until 16) {
            for (y in 0 until 16) {
                val px = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(px) < 128) continue
                rSum += android.graphics.Color.red(px)
                gSum += android.graphics.Color.green(px)
                bSum += android.graphics.Color.blue(px)
                count++
            }
        }
        if (!bmp.isRecycled) runCatching { bmp.recycle() }
        if (safe !== src && !safe.isRecycled) runCatching { safe.recycle() }
        if (count == 0L) return null
        Color(android.graphics.Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt()))
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerSheet(
    now: NowPlaying?, playing: Boolean,
    queue: List<MediaItem>, queueIndex: Int,
    positionMs: Long, durationMs: Long,
    lyricLines: List<LyricLine>, lyricLoading: Boolean,
    onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit,
    onSeek: (Long) -> Unit, onPlayAt: (Int) -> Unit,
    isFavorite: Boolean, onToggleFavorite: () -> Unit,
    onOpenAddPlaylist: () -> Unit, onOpenDownload: () -> Unit, onOpenSwitchQuality: () -> Unit,
    buffering: Boolean,
    onClose: () -> Unit, showQueue: Boolean, onToggleQueue: () -> Unit,
    playMode: Int, onModeChange: (Int) -> Unit,
    onOpenLyricFullscreen: () -> Unit
) {
    // 默认半屏展开，上滑进入全屏：允许 PartiallyExpanded 锚点
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (showQueue) "播放列表" else "正在播放", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onOpenDownload, enabled = now != null) { Icon(Icons.Default.Download, "下载") }
                IconButton(onClick = onToggleQueue) { Icon(Icons.Default.QueueMusic, if (showQueue) "返回播放页" else "播放列表") }
            }
            if (showQueue) {
                // 播放模式切换
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = playMode == 0, onClick = { onModeChange(0) }, label = { Text("顺序") }, leadingIcon = { Icon(Icons.Default.Repeat, null, Modifier.size(16.dp)) })
                    FilterChip(selected = playMode == 1, onClick = { onModeChange(1) }, label = { Text("单曲循环") }, leadingIcon = { Icon(Icons.Default.RepeatOne, null, Modifier.size(16.dp)) })
                    FilterChip(selected = playMode == 2, onClick = { onModeChange(2) }, label = { Text("随机") }, leadingIcon = { Icon(Icons.Default.Shuffle, null, Modifier.size(16.dp)) })
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(queue) { index, item ->
                        val active = index == queueIndex
                        ListItem(
                            headlineContent = { Text(item.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { Text("${item.artist} · ${item.platform}") },
                            leadingContent = {
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (active) MaterialTheme.colorScheme.primaryContainer else Color(0xffdbe1ff)), Alignment.Center) {
                                    if (item.artwork.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color.White)
                                    else AsyncImage(model = item.artwork, contentDescription = item.title, modifier = Modifier.fillMaxSize())
                                }
                            },
                            trailingContent = { if (active && playing) Icon(Icons.Default.Equalizer, "播放中") },
                            modifier = Modifier.clickable(onClick = { onPlayAt(index) })
                        )
                    }
                }
            } else {
                val item = now?.item
                Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(28.dp)).background(Color(0xffdbe1ff)), contentAlignment = Alignment.Center) {
                    if (item?.artwork.isNullOrBlank()) Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(88.dp), tint = Color.White)
                    else AsyncImage(model = item?.artwork, contentDescription = item?.title, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(item?.title ?: "暂无播放", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item?.artist ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (now != null) "${formatDuration(item?.durationMs ?: 0)} · ${MusicQuality.labelOf(now.qualityKey)}${now.size?.let { " · ${formatSize(it)}" } ?: ""} · ${item?.platform}"
                            else "从搜索或歌单中选择歌曲播放",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSwitchQuality, enabled = now != null, modifier = Modifier.offset(y = 12.dp)) { Icon(Icons.Default.HighQuality, "音质") }
                }
                val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                var seeking by remember(now?.key) { mutableStateOf(false) }
                var seekFrac by remember(now?.key) { mutableStateOf(0f) }
                // 已提交但播放器尚未跟上的目标位置：显示期间不受轮询进度回写影响
                var seekTarget by remember(now?.key) { mutableStateOf<Long?>(null) }
                LaunchedEffect(positionMs) {
                    seekTarget?.let { if (kotlin.math.abs(positionMs - it) < 1500) seekTarget = null }
                }
                LaunchedEffect(seekTarget) {
                    if (seekTarget != null) { delay(5000); seekTarget = null }
                }
                val shownPosMs = when {
                    seeking && durationMs > 0 -> (seekFrac * durationMs).toLong()
                    seekTarget != null && durationMs > 0 -> seekTarget!!
                    else -> positionMs
                }
                val shownFrac = if (durationMs > 0) (shownPosMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Slider(
                    value = if (seeking) seekFrac else shownFrac,
                    onValueChange = { seeking = true; seekFrac = it },
                    onValueChangeFinished = {
                        seeking = false
                        if (durationMs > 0) {
                            val target = (seekFrac * durationMs).toLong()
                            seekTarget = target
                            onSeek(target)
                        }
                    },
                    enabled = durationMs > 0,
                    thumb = {
                        Box(
                            Modifier.size(16.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    if (durationMs > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                        )
                    }
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(shownPosMs), fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(durationMs), fontSize = 12.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleFavorite, enabled = now != null) { Icon(imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "收藏") }
                    IconButton(onClick = onPrev, enabled = now != null) { Icon(imageVector = Icons.Default.SkipPrevious, contentDescription = "上一首") }
                    // 解析/缓冲/seek 期间显示加载动画
                    if ((now?.loading == true || buffering) && now != null) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(30.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                        }
                    } else {
                        FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp), enabled = now != null) { Icon(imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "播放") }
                    }
                    IconButton(onClick = onNext, enabled = now != null) { Icon(imageVector = Icons.Default.SkipNext, contentDescription = "下一首") }
                    IconButton(onClick = onOpenAddPlaylist, enabled = now != null) { Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = "加歌单") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("歌词", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenLyricFullscreen, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Fullscreen, "全屏歌词", modifier = Modifier.size(20.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(220.dp)) {
                    LyricsView(lines = lyricLines, positionMs = positionMs, loading = lyricLoading && now != null, centered = true)
                }
                }
            }
        }
    }
}

/**
 * 歌词视图：跟随当前行自动滚动，当前行中心对齐到歌词区顶部 markerFraction 处（默认 24%）；
 * 手指拖动时暂停跟随，松手 3 秒无操作后恢复跟随；全屏与半屏共用同一套逻辑。
 */
@Composable private fun LyricsView(
    lines: List<LyricLine>,
    positionMs: Long,
    loading: Boolean,
    markerFraction: Float = 0.24f,
    fontScale: Float = 1f,
    centered: Boolean = false
) {
    if (loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("歌词加载中") } }
        return
    }
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val listState = rememberLazyListState()
        val lyricScope = rememberCoroutineScope()
        var follow by remember(lines) { mutableStateOf(true) }
        var resumeFollowJob by remember { mutableStateOf<Job?>(null) }
        val currentIndex = remember(lines, positionMs) {
            lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
        }
        // 把指定行中心平滑滚动到跟随锚点（顶部 markerFraction 处）：
        // 目标已可见时只做一次 animateScrollBy，避免先跳到顶部再折返的上跳闪动。
        suspend fun scrollCurrentToAnchor(index: Int, animated: Boolean) {
            val i = index.coerceIn(lines.indices)
            val anchorPx = with(density) { (maxHeight * markerFraction).toPx() }
            runCatching {
                val visible = listState.layoutInfo.visibleItemsInfo
                val target = visible.firstOrNull { it.index == i }
                if (target != null) {
                    // 正向滚动会使内容上移，故使用当前中心到锚点的偏移量，一次到位。
                    val delta = (target.offset + target.size / 2) - anchorPx
                    if (kotlin.math.abs(delta) > 1f) {
                        if (animated) listState.animateScrollBy(delta, tween(200)) else listState.scrollBy(delta)
                    }
                    return
                }
                // 目标不可见（如切歌/快进）：一次 scrollToItem 直接落到锚点附近。
                val avgSize = visible.map { it.size }.average()
                    .takeIf { it.isFinite() && it > 0 }?.toInt()
                    ?: with(density) { 32.dp.toPx().toInt() }
                val targetOffset = (avgSize / 2 - anchorPx).toInt()
                if (animated) {
                    listState.animateScrollToItem(i, targetOffset)
                    // 估计高度只有微小误差，只做小幅修正，避免大幅折返。
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return
                    val fix = (info.offset + info.size / 2) - anchorPx
                    if (kotlin.math.abs(fix) > 2f && kotlin.math.abs(fix) < info.size) {
                        listState.animateScrollBy(fix, tween(200))
                    } else if (kotlin.math.abs(fix) >= info.size) {
                        listState.scrollBy(fix)
                    }
                } else {
                    listState.scrollToItem(i, targetOffset)
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return
                    val fix = (info.offset + info.size / 2) - anchorPx
                    if (kotlin.math.abs(fix) > 1f) listState.scrollBy(fix)
                }
            }
        }
        // 切歌/初次加载用瞬间定位，当前行切换/恢复跟随用平滑滚动
        LaunchedEffect(lines) {
            resumeFollowJob?.cancel()
            resumeFollowJob = null
            follow = true
            scrollCurrentToAnchor(currentIndex, false)
        }
        LaunchedEffect(currentIndex) { if (follow) scrollCurrentToAnchor(currentIndex, true) }
        LaunchedEffect(follow) { if (follow) scrollCurrentToAnchor(currentIndex, true) }
        // 新的拖拽会取消旧的恢复任务，避免等待期间再次拖动仍被自动回位。
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                        resumeFollowJob?.cancel()
                        resumeFollowJob = null
                        follow = false
                    }
                    is androidx.compose.foundation.interaction.DragInteraction.Stop,
                    is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {
                        resumeFollowJob?.cancel()
                        resumeFollowJob = lyricScope.launch {
                            delay(3000)
                            follow = true
                        }
                    }
                }
            }
        }
        // 顶部留白独立于当前行锚点，避免整段歌词随锚点整体下移。
        val padTop = 16.dp
        // 末尾预留歌词区域一半高度，使最后几行仍能向上滚动查看。
        val padBottom = maxHeight * 0.5f
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padTop, bottom = padBottom)
        ) {
            itemsIndexed(lines) { index, line ->
                val active = index == currentIndex
                Text(
                    line.text.ifBlank { " " },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    fontSize = (if (active) 17 else 14).sp * fontScale,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlaylistSheet(
    name: String,
    songs: List<SongRecord>,
    onPlay: (SongRecord) -> Unit,
    onRemove: (SongRecord) -> Unit,
    onDeletePlaylist: (() -> Unit)?,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("共 ${songs.size} 首", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDeletePlaylist != null) {
                    IconButton(onClick = onDeletePlaylist) { Icon(Icons.Default.Delete, "删除歌单") }
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭") }
            }
            Spacer(Modifier.height(8.dp))
            if (songs.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                    Text("歌单为空，可从搜索结果收藏或添加歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(songs, key = { it.key }) { record ->
                        ListItem(
                            headlineContent = { Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${record.artist} · ${formatDuration(record.durationMs)} · ${MusicQuality.labelOf(record.qualityKey)}${record.size?.let { " · ${formatSize(it)}" } ?: ""} · ${record.platform}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xffdbe1ff)), Alignment.Center) {
                                    if (record.artwork.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = Color.White)
                                    else AsyncImage(model = record.artwork, contentDescription = record.title, modifier = Modifier.fillMaxSize())
                                }
                            },
                            trailingContent = { IconButton(onClick = { onRemove(record) }) { Icon(Icons.Default.DeleteOutline, "移出歌单") } },
                            modifier = Modifier.clickable(onClick = { onPlay(record) })
                        )
                    }
                }
            }
        }
    }
}
