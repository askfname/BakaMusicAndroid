package com.bakamusic.android.ui

import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import com.bakamusic.android.plugin.*
import com.bakamusic.android.util.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import com.bakamusic.android.*
import kotlinx.coroutines.*

@Composable fun MiniPlayer(
    now: NowPlaying?, playing: Boolean, buffering: Boolean,
    positionMs: Long, durationMs: Long,
    onPrev: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val enabled = now != null
    val showLoading = now?.loading == true || buffering
    val defaultBg = MaterialTheme.colorScheme.secondaryContainer
    val art = now?.item?.artwork
    var artworkBg by remember(art) { mutableStateOf<Color?>(null) }
    val miniPlayerContext = LocalContext.current
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
            Row(modifier = Modifier.offset(x = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = enabled) { Icon(imageVector = Icons.Default.SkipPrevious, contentDescription = "上一首", tint = buttonTint) }
                IconButton(onClick = onToggle, enabled = enabled) { Icon(imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "播放暂停", tint = buttonTint) }
                IconButton(onClick = onNext, enabled = enabled) { Icon(imageVector = Icons.Default.SkipNext, contentDescription = "下一首", tint = buttonTint) }
            }
        }
    }
}

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

private fun gradientColorsFromCoverBitmap(src: android.graphics.Bitmap): Pair<Color, Color>? {
    return runCatching {
        val safe = if (src.config == android.graphics.Bitmap.Config.HARDWARE) {
            src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return null
        } else src
        val n = 24
        val bmp = android.graphics.Bitmap.createScaledBitmap(safe, n, n, true)
        var r1 = 0L; var g1 = 0L; var b1 = 0L; var c1 = 0L
        var r2 = 0L; var g2 = 0L; var b2 = 0L; var c2 = 0L
        for (x in 0 until n) {
            for (y in 0 until n) {
                val px = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(px) < 128) continue
                if (x + y < n) {
                    r1 += android.graphics.Color.red(px)
                    g1 += android.graphics.Color.green(px)
                    b1 += android.graphics.Color.blue(px)
                    c1++
                } else {
                    r2 += android.graphics.Color.red(px)
                    g2 += android.graphics.Color.green(px)
                    b2 += android.graphics.Color.blue(px)
                    c2++
                }
            }
        }
        if (!bmp.isRecycled) runCatching { bmp.recycle() }
        if (safe !== src && !safe.isRecycled) runCatching { safe.recycle() }
        if (c1 == 0L || c2 == 0L) return null
        var start = Color(android.graphics.Color.rgb((r1 / c1).toInt(), (g1 / c1).toInt(), (b1 / c1).toInt()))
        var end = Color(android.graphics.Color.rgb((r2 / c2).toInt(), (g2 / c2).toInt(), (b2 / c2).toInt()))
        val dr = (start.red - end.red) * 255f
        val dg = (start.green - end.green) * 255f
        val db = (start.blue - end.blue) * 255f
        if (dr * dr + dg * dg + db * db < 40f * 40f) {
            end = Color(
                (end.red * 0.72f).coerceIn(0f, 1f),
                (end.green * 0.72f).coerceIn(0f, 1f),
                (end.blue * 0.72f).coerceIn(0f, 1f),
                1f
            )
        }
        start to end
    }.getOrNull()
}

@Composable fun rememberCoverGradientColors(artwork: String?): Pair<Color, Color> {
    val defaultStart = MaterialTheme.colorScheme.secondaryContainer
    val defaultEnd = MaterialTheme.colorScheme.surfaceContainerLow
    var raw by remember(artwork) { mutableStateOf<Pair<Color, Color>?>(null) }
    val context = LocalContext.current
    LaunchedEffect(artwork) {
        if (artwork.isNullOrBlank()) {
            raw = null
            return@LaunchedEffect
        }
        // 注意：之前用未挂载到 Image() 的 rememberAsyncImagePainter 取 drawable，
        // Coil 需布局尺寸才能解析请求而不执行，导致一直回退到主题色。
        // 改为 ImageLoader.execute() 直接在 IO 线程加载解析。
        raw = withContext(Dispatchers.IO) {
            runCatching {
                val request = coil.request.ImageRequest.Builder(context)
                    .data(artwork)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                val drawable = (result as? coil.request.SuccessResult)?.drawable ?: return@runCatching null
                drawableToMiniPlayerBitmap(drawable)?.let { gradientColorsFromCoverBitmap(it) }
            }.getOrNull()
        }
    }
    val targetStart = raw?.first ?: defaultStart
    val targetEnd = raw?.second ?: defaultEnd
    val animatedStart by animateColorAsState(targetValue = targetStart, animationSpec = tween(600), label = "coverGradientStart")
    val animatedEnd by animateColorAsState(targetValue = targetEnd, animationSpec = tween(600), label = "coverGradientEnd")
    return animatedStart to animatedEnd
}

@Composable fun rememberCoverGradientBrush(artwork: String?): Brush {
    val (start, end) = rememberCoverGradientColors(artwork)
    return remember(start, end) {
        Brush.linearGradient(colors = listOf(start, end), start = Offset.Zero, end = Offset.Infinite)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlayerSheet(
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
    val transition = remember { MutableTransitionState(false).apply { targetState = true } }
    fun dismissAnimated() {
        if (!transition.targetState) return
        transition.targetState = false
    }
    // 退出动画播完才真正关闭，避免固定延时与动画错位导致的闪烁
    LaunchedEffect(transition.currentState, transition.isIdle) {
        if (!transition.targetState && !transition.currentState && transition.isIdle) {
            onClose()
        }
    }
    Dialog(
        onDismissRequest = { dismissAnimated() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
    ) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        AnimatedVisibility(
            visibleState = transition,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(550)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 550, easing = CubicBezierEasing(0.8f, 0f, 0.6f, 1f))
            )
        ) {
            val (gradientStart, gradientEnd) = rememberCoverGradientColors(now?.item?.artwork)
            val gradientBrush = remember(gradientStart, gradientEnd) {
                Brush.linearGradient(colors = listOf(gradientStart, gradientEnd), start = Offset.Zero, end = Offset.Infinite)
            }
            val gradientContentTarget =
                if ((gradientStart.luminance() + gradientEnd.luminance()) / 2f > 0.5f) Color.Black else Color.White
            val gradientSubTarget = gradientContentTarget.copy(alpha = 0.7f)
            val gradientContent by animateColorAsState(targetValue = gradientContentTarget, animationSpec = tween(600), label = "coverGradientContent")
            val gradientSubContent by animateColorAsState(targetValue = gradientSubTarget, animationSpec = tween(600), label = "coverGradientSubContent")
            Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                Box(Modifier.fillMaxSize().background(gradientBrush)) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { dismissAnimated() }) { Icon(Icons.Default.KeyboardArrowDown, "关闭", tint = gradientContent) }
                        Text(if (showQueue) "播放列表" else "正在播放", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = gradientContent)
                        IconButton(onClick = onOpenDownload, enabled = now != null) { Icon(Icons.Default.Download, "下载", tint = gradientContent) }
                        IconButton(onClick = onToggleQueue) { Icon(Icons.Default.QueueMusic, if (showQueue) "返回播放页" else "播放列表", tint = gradientContent) }
                    }
            if (showQueue) {
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
                    Box(modifier = Modifier.size(320.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(28.dp)).background(Color(0xffdbe1ff)), contentAlignment = Alignment.Center) {
                        if (item?.artwork.isNullOrBlank()) Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(112.dp), tint = Color.White)
                        else AsyncImage(model = item?.artwork, contentDescription = item?.title, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(item?.title ?: "暂无播放", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = gradientContent)
                            Text(item?.artist ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, color = gradientContent)
                            Text(
                                if (now != null) "${formatDuration(item?.durationMs ?: 0)} · ${MusicQuality.labelOf(now.qualityKey)}${now.size?.let { " · ${formatSize(it)}" } ?: ""} · ${item?.platform}"
                                else "从搜索或歌单中选择歌曲播放",
                                fontSize = 12.sp, color = gradientSubContent
                            )
                        }
                        IconButton(onClick = onOpenSwitchQuality, enabled = now != null, modifier = Modifier.offset(y = 12.dp)) { Icon(Icons.Default.HighQuality, "音质", tint = gradientContent) }
                    }
                    var seeking by remember(now?.key) { mutableStateOf(false) }
                    var seekFrac by remember(now?.key) { mutableStateOf(0f) }
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
                        track = { sliderState ->
                        SliderDefaults.Track(sliderState = sliderState)
                    },
                        thumb = { Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (durationMs > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))) }
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(shownPosMs), fontSize = 12.sp, color = gradientSubContent)
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(durationMs), fontSize = 12.sp, color = gradientSubContent)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleFavorite, enabled = now != null) { Icon(imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "收藏", tint = gradientContent) }
                        IconButton(onClick = onPrev, enabled = now != null) { Icon(imageVector = Icons.Default.SkipPrevious, contentDescription = "上一首", tint = gradientContent) }
                        if ((now?.loading == true || buffering) && now != null) {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(30.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                            }
                        } else {
                            FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp), enabled = now != null) { Icon(imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "播放") }
                        }
                        IconButton(onClick = onNext, enabled = now != null) { Icon(imageVector = Icons.Default.SkipNext, contentDescription = "下一首", tint = gradientContent) }
                        IconButton(onClick = onOpenAddPlaylist, enabled = now != null) { Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = "加歌单", tint = gradientContent) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text("歌词", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = gradientContent)
                        IconButton(onClick = onOpenLyricFullscreen, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Fullscreen, "全屏歌词", modifier = Modifier.size(20.dp), tint = gradientContent) }
                    }
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        LyricsView(lines = lyricLines, positionMs = positionMs, loading = lyricLoading && now != null, centered = true, activeColor = gradientContent, inactiveColor = gradientSubContent)
                    }
                }
            }
                }
            }
        }
    }
}
}

@Composable fun LyricsView(
    lines: List<LyricLine>,
    positionMs: Long,
    loading: Boolean,
    markerFraction: Float = 0.24f,
    fontScale: Float = 1f,
    centered: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    if (loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = activeColor); Spacer(Modifier.width(8.dp)); Text("歌词加载中", color = inactiveColor) } }
        return
    }
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("暂无歌词", color = inactiveColor) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val listState = rememberLazyListState()
        val lyricScope = rememberCoroutineScope()
        var follow by remember(lines) { mutableStateOf(true) }
        var resumeFollowJob by remember { mutableStateOf<Job?>(null) }
        val currentIndex = remember(lines, positionMs) { lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0) }
        suspend fun scrollCurrentToAnchor(index: Int, animated: Boolean) {
            val i = index.coerceIn(lines.indices)
            val anchorPx = with(density) { (maxHeight * markerFraction).toPx() }
            runCatching {
                val visible = listState.layoutInfo.visibleItemsInfo
                val target = visible.firstOrNull { it.index == i }
                if (target != null) {
                    val delta = (target.offset + target.size / 2) - anchorPx
                    if (kotlin.math.abs(delta) > 1f) {
                        if (animated) listState.animateScrollBy(delta, tween(200)) else listState.scrollBy(delta)
                    }
                    return
                }
                val avgSize = visible.map { it.size }.average().takeIf { it.isFinite() && it > 0 }?.toInt() ?: with(density) { 32.dp.toPx().toInt() }
                val targetOffset = (avgSize / 2 - anchorPx).toInt()
                if (animated) {
                    listState.animateScrollToItem(i, targetOffset)
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return
                    val fix = (info.offset + info.size / 2) - anchorPx
                    if (kotlin.math.abs(fix) > 2f && kotlin.math.abs(fix) < info.size) listState.animateScrollBy(fix, tween(200))
                    else if (kotlin.math.abs(fix) >= info.size) listState.scrollBy(fix)
                } else {
                    listState.scrollToItem(i, targetOffset)
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return
                    val fix = (info.offset + info.size / 2) - anchorPx
                    if (kotlin.math.abs(fix) > 1f) listState.scrollBy(fix)
                }
            }
        }
        LaunchedEffect(lines) { resumeFollowJob?.cancel(); resumeFollowJob = null; follow = true; scrollCurrentToAnchor(currentIndex, false) }
        LaunchedEffect(currentIndex) { if (follow) scrollCurrentToAnchor(currentIndex, true) }
        LaunchedEffect(follow) { if (follow) scrollCurrentToAnchor(currentIndex, true) }
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is androidx.compose.foundation.interaction.DragInteraction.Start -> { resumeFollowJob?.cancel(); resumeFollowJob = null; follow = false }
                    is androidx.compose.foundation.interaction.DragInteraction.Stop, is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {
                        resumeFollowJob?.cancel(); resumeFollowJob = lyricScope.launch { delay(3000); follow = true }
                    }
                }
            }
        }
        val padTop = 16.dp
        val padBottom = maxHeight * 0.5f
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padTop, bottom = padBottom)) {
            itemsIndexed(lines) { index, line ->
                val active = index == currentIndex
                Text(line.text.ifBlank { " " }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), fontSize = (if (active) 17 else 14).sp * fontScale, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) activeColor else inactiveColor, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlaylistSheet(
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
                if (onDeletePlaylist != null) IconButton(onClick = onDeletePlaylist) { Icon(Icons.Default.Delete, "删除歌单") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭") }
            }
            Spacer(Modifier.height(8.dp))
            if (songs.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) { Text("歌单为空，可从搜索结果收藏或添加歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(songs, key = { it.key }) { record ->
                        ListItem(
                            headlineContent = { Text(record.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${record.artist} · ${formatDuration(record.durationMs)} · ${MusicQuality.labelOf(record.qualityKey)}${record.size?.let { " · ${formatSize(it)}" } ?: ""} · ${record.platform}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xffdbe1ff)), Alignment.Center) { if (record.artwork.isNullOrBlank()) Icon(Icons.Default.MusicNote, null, tint = Color.White) else AsyncImage(model = record.artwork, contentDescription = record.title, modifier = Modifier.fillMaxSize()) } },
                            trailingContent = { IconButton(onClick = { onRemove(record) }) { Icon(Icons.Default.DeleteOutline, "移出歌单") } },
                            modifier = Modifier.clickable(onClick = { onPlay(record) })
                        )
                    }
                }
            }
        }
    }
}
