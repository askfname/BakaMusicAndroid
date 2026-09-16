package com.bakamusic.android.ui

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import com.bakamusic.android.plugin.*
import com.bakamusic.android.util.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HomeScreen(
    sourceService: MusicSourceService,
    repository: AppRepository,
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
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    var searchNonce by rememberSaveable { mutableStateOf(0) }
    var sources by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var sourceNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var sourcesLoaded by remember { mutableStateOf(false) }
    var selectedSourcePlatform by rememberSaveable { mutableStateOf<String?>(null) }
    var pluginSongs by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchPage by remember { mutableStateOf(1) }
    var searchIsEnd by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadMoreError by remember { mutableStateOf<String?>(null) }
    var qualityDialog by remember { mutableStateOf<MediaItem?>(null) }
    var playlistDialog by remember { mutableStateOf<MediaItem?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var createSheetDialog by rememberSaveable { mutableStateOf(false) }
    var createSheetName by rememberSaveable { mutableStateOf("") }
    var dailyPlatform by rememberSaveable { mutableStateOf<String?>(null) }
    var dailyGroups by remember { mutableStateOf<List<TopListGroup>>(emptyList()) }
    var dailyBoard by remember { mutableStateOf<TopListItem?>(null) }
    var dailySongs by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var dailyLoading by remember { mutableStateOf(false) }
    var dailyError by remember { mutableStateOf<String?>(null) }
    var coverFillJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val hasNoPlugin = sourcesLoaded && sources.isEmpty()

    suspend fun reloadSources() {
        runCatching { sourceService.refresh() }
        val listed = runCatching { sourceService.installedPlugins() }.getOrDefault(sources)
        sources = listed
        sourceNames = listed.map { it.name }
        if (selectedSourcePlatform == null || listed.none { it.name == selectedSourcePlatform }) {
            selectedSourcePlatform = listed.firstOrNull()?.name ?: selectedSourcePlatform
        }
        val savedDailyPlatform = context.getSharedPreferences(DAILY_PREFS, Context.MODE_PRIVATE)
            .takeIf { it.getString(DAILY_KEY_DATE, null) == todayString() }
            ?.getString(DAILY_KEY_PLATFORM, null)
        if (dailyPlatform == null || listed.none { it.name == dailyPlatform }) {
            dailyPlatform = savedDailyPlatform?.takeIf { sp -> listed.any { it.name == sp } }
                ?: listed.firstOrNull()?.name ?: dailyPlatform
        }
        sourcesLoaded = true
    }

    LaunchedEffect(Unit) { reloadSources() }
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
        if (submittedQuery.isBlank()) {
            pluginSongs = emptyList(); searchError = null; loadMoreError = null; searchPage = 1; searchIsEnd = true; return@LaunchedEffect
        }
        if (!sourcesLoaded) return@LaunchedEffect
        if (sources.isEmpty()) { reloadSources() }
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
        val querySnapshot = submittedQuery
        val platformSnapshot = selectedSourcePlatform
        try {
            val page = sourceService.searchByPlatform(targetPlatform, submittedQuery)
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
            if (dailyBoard == null) dailyLoading = false
        }
    }

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
            coverFillJob = if (page.data.any { it.artwork.isNullOrBlank() }) {
                val snapshot = page.data
                scope.launch(Dispatchers.IO) {
                    for (song in snapshot) {
                        ensureActive()
                        if (submittedQuery.isNotBlank() || platform != dailyPlatform || board != dailyBoard) return@launch
                        if (!song.artwork.isNullOrBlank()) continue
                        val q = "${song.title} ${song.artist}".trim()
                        if (q.isBlank()) continue
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

    fun loadMore() {
        if (loadingMore || searching || searchIsEnd || submittedQuery.isBlank() || !sourcesLoaded || hasNoPlugin) return
        if (sources.isEmpty()) return
        if (searchPage >= 50) { searchIsEnd = true; return }
        val target = selectedSourcePlatform ?: sources.firstOrNull()?.name ?: return
        if (sources.none { it.name == target }) return
        loadingMore = true
        loadMoreError = null
        val q = submittedQuery
        val p = selectedSourcePlatform
        val next = searchPage + 1
        scope.launch {
            try {
                val page = sourceService.searchByPlatform(target, q, next)
                if (q != submittedQuery || p != selectedSourcePlatform) return@launch
                val known = pluginSongs.map { "${it.platform}:${it.id}" }.toSet()
                pluginSongs = pluginSongs + page.data.filterNot { known.contains("${it.platform}:${it.id}") }
                searchPage = next
                searchIsEnd = page.isEnd || page.data.isEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (q != submittedQuery || p != selectedSourcePlatform) return@launch
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

    var greetingMinute by remember { mutableStateOf(System.currentTimeMillis() / 60000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            greetingMinute = System.currentTimeMillis() / 60000
        }
    }
    val greeting = remember(greetingMinute) { homeGreeting() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索歌曲") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; submittedQuery = "" }) { Icon(Icons.Default.Clear, "清除") } },
                shape = RoundedCornerShape(30.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    submittedQuery = query.trim()
                    searchNonce++
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    scope.launch { repository.addSearch(query.trim()) }
                })
            )
            Spacer(Modifier.width(10.dp))
            FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Settings, "设置") }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(greeting, Modifier.padding(horizontal = 22.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("发现属于你的音乐", Modifier.padding(horizontal = 22.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeTile("我喜欢的音乐", Icons.Default.Favorite, Color(0xffffd9e2), Modifier.weight(1f), { onOpenPlaylist(AppRepository.FAVORITE_SHEET) })
                    HomeTile("导入歌单", Icons.Default.Add, Color(0xffd8f0e3), Modifier.weight(1f), onImportPlaylist)
                }
            }
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
            if (submittedQuery.isNotBlank() && !hasNoPlugin) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sources.isEmpty()) items(sourceNames) { name -> FilterChip(selected = false, enabled = false, onClick = {}, label = { Text(name) }) }
                    else items(sources) { source -> FilterChip(selected = source.name == selectedSourcePlatform, onClick = { selectedSourcePlatform = source.name }, label = { Text(source.name) }) }
                }
            }
            if (submittedQuery.isBlank() && !hasNoPlugin) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sources.isEmpty()) items(sourceNames) { name -> FilterChip(selected = false, enabled = false, onClick = {}, label = { Text(name) }) }
                    else items(sources) { source -> FilterChip(selected = source.name == dailyPlatform, onClick = { if (dailyPlatform != source.name) { dailyPlatform = source.name; dailyGroups = emptyList(); dailyBoard = null; dailySongs = emptyList(); dailyError = null } }, label = { Text(source.name) }) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (submittedQuery.isBlank()) "每日推荐" else "搜索结果", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (submittedQuery.isBlank() && !hasNoPlugin) DailyBoardDropdown(boards = dailyGroups.flatMap { it.items }, selected = dailyBoard, enabled = !dailyLoading, onSelect = { board -> dailyPlatform?.let { saveDailyBoard(context, it, board) }; dailyBoard = board })
                }
            }
            if (!hasNoPlugin && searchError != null) item { Text(searchError ?: "", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            if (!hasNoPlugin && submittedQuery.isBlank() && dailyError != null) item { Text(dailyError ?: "", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            
            if (hasNoPlugin) item { NoPluginPrompt(onInstallClick = onInstallPlugin) }
            else if ((submittedQuery.isNotBlank() && searching) || (submittedQuery.isBlank() && dailyLoading)) items(8) { SongRowSkeleton() }
            else if (submittedQuery.isBlank()) items(dailySongs) { item -> PluginSongRow(item, favorites.contains("${item.platform}:${item.id}"), { onPlayMedia(item, dailySongs) }, { scope.launch(Dispatchers.IO) { repository.toggleFavorite(item) } }, { qualityDialog = item }, { playlistDialog = item }) }
            else items(pluginSongs) { item -> PluginSongRow(item, favorites.contains("${item.platform}:${item.id}"), { onPlayMedia(item, pluginSongs) }, { scope.launch(Dispatchers.IO) { repository.toggleFavorite(item) } }, { qualityDialog = item }, { playlistDialog = item }) }
            
            if (!hasNoPlugin && submittedQuery.isNotBlank() && pluginSongs.isNotEmpty() && (!searchIsEnd || loadMoreError != null)) {
                item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                        if (loadingMore) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在加载下一页", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else if (loadMoreError != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("下一页加载失败，$loadMoreError", fontSize = 12.sp, color = MaterialTheme.colorScheme.error); TextButton(onClick = { loadMore() }) { Text("重试") }
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
                    val keys = item.qualities.keys.sortedByDescending { qualityRankOf(it) }.ifEmpty { listOf("320k") }
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
            Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("音乐世界，从这里开始", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("安装音源插件，获取音乐信息", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onInstallClick) { Text("安装音源插件") }
    }
}

private const val DAILY_PREFS = "daily_recommend"
private const val DAILY_KEY_DATE = "daily_date"
private const val DAILY_KEY_PLATFORM = "daily_platform"
private const val DAILY_KEY_BOARD = "daily_board_id"

private fun todayString(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

private fun homeGreeting(): String =
    when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        in 0..4 -> "夜深了"
        in 5..8 -> "早上好"
        in 9..11 -> "上午好"
        in 12..13 -> "中午好"
        in 14..18 -> "下午好"
        else -> "晚上好"
    }

private fun resolveDailyBoard(context: android.content.Context, platform: String, boards: List<TopListItem>): TopListItem {
    val prefs = context.getSharedPreferences(DAILY_PREFS, Context.MODE_PRIVATE)
    if (prefs.getString(DAILY_KEY_DATE, null) == todayString() && prefs.getString(DAILY_KEY_PLATFORM, null) == platform) {
        val savedId = prefs.getString(DAILY_KEY_BOARD, null)
        boards.firstOrNull { it.id == savedId }?.let { return it }
    }
    val hot = boards.filter { it.title.contains("热门") || it.title.contains("热歌") }
    val pick = (hot.ifEmpty { boards }).random()
    saveDailyBoard(context, platform, pick)
    return pick
}

private fun saveDailyBoard(context: android.content.Context, platform: String, board: TopListItem) {
    context.getSharedPreferences(DAILY_PREFS, Context.MODE_PRIVATE).edit()
        .putString(DAILY_KEY_DATE, todayString())
        .putString(DAILY_KEY_PLATFORM, platform)
        .putString(DAILY_KEY_BOARD, board.id)
        .apply()
}

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
            Text(selected?.let(::labelOf) ?: "选择榜单", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, "选择榜单")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(220.dp).height(300.dp), shape = RoundedCornerShape(16.dp)) {
            boards.forEach { board ->
                val isSelected = board.id == selected?.id && board.groupTitle == selected.groupTitle
                DropdownMenuItem(
                    text = { Text(labelOf(board), color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                    trailingIcon = { if (isSelected) Icon(Icons.Default.Check, "已选", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.offset(x = (-4).dp)) },
                    contentPadding = PaddingValues(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    onClick = { expanded = false; if (!isSelected) onSelect(board) }
                )
            }
        }
    }
}

private fun isNoPluginError(error: Throwable): Boolean = error.message?.contains("无可用插件") == true
