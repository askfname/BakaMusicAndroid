package com.bakamusic.android.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bakamusic.android.BakaApplication
import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import com.bakamusic.android.plugin.*
import com.bakamusic.android.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val bakaApp = application as BakaApplication
    val repository = bakaApp.repository
    val playbackController = bakaApp.playbackController
    val sourceService = bakaApp.sourceService
    val sessionStore = bakaApp.sessionStore

    private val context = application.applicationContext

    // 播放状态
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
        private set
    val playQueue = mutableStateListOf<MediaItem>()
    var queueIndex by mutableStateOf(0)
        private set

    private val resolvedCache = mutableStateMapOf<String, NowPlaying>()
    private var resolvedCacheGen = -1L
    
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var buffering by mutableStateOf(false)
        private set
    var serviceItemCount by mutableStateOf(0)
        private set
    
    var playError by mutableStateOf<String?>(null)
    var playerError by mutableStateOf<String?>(null)
    var playerErrorRetry by mutableStateOf(true)
    
    var lastEndedKey by mutableStateOf<String?>(null)
    var lyricLines by mutableStateOf(emptyList<LyricLine>())
        private set
    var lyricLoading by mutableStateOf(false)
        private set
    
    /** 播放模式：0 顺序播放，1 单曲循环，2 随机播放 */
    var playMode by mutableStateOf(0)

    private var playToken = 0
    private var playRequestJob: Job? = null

    init {
        // 恢复播放会话
        sessionStore.load()?.let { saved ->
            nowPlaying = saved.current?.let {
                NowPlaying(it, "", saved.qualityKey, saved.size, emptyMap(), loading = false)
            }
            playQueue.addAll(saved.queue)
            queueIndex = saved.queueIndex
            positionMs = saved.positionMs
            durationMs = saved.current?.durationMs ?: 0L
        }

        // 注册播放控制器监听
        playbackController.addErrorListener { msg ->
            val cur = nowPlaying
            // 源错误（403/断连等多为单 CDN 边缘故障）：有备用地址时静默切换，不弹框；
            // 耗尽才走失败弹窗。解码类错误不适用备用源，直接弹框。
            val isSourceError = msg.contains("Source error") || msg.contains("2001") || msg.contains("2004")
            val next = if (cur != null && isSourceError) {
                cur.backupUrls.firstOrNull { it.startsWith("http", true) && it != cur.mediaUrl }
            } else null
            if (cur != null && next != null) {
                android.util.Log.w("BakaPlayer", "主源失败自动切换备用地址: $msg")
                val remaining = cur.backupUrls.filter { it != next }
                val switched = cur.copy(mediaUrl = next, backupUrls = remaining, loading = true)
                nowPlaying = switched
                resolvedCache[cur.key]?.let { resolvedCache[cur.key] = switched.copy(loading = false) }
                runCatching {
                    playbackController.play(cur.item.copy(mediaUrl = next, mediaHeaders = cur.headers))
                }.onFailure {
                    if (nowPlaying?.key == cur.key) nowPlaying = nowPlaying?.copy(loading = false)
                    playerError = "播放失败：${it.message ?: "无可用音源"}"
                    playerErrorRetry = false
                }
                return@addErrorListener
            }
            playerError = msg
            playerErrorRetry = true
            nowPlaying?.let { if (it.loading) nowPlaying = it.copy(loading = false) }
        }
        playbackController.addAudibleListener {
            nowPlaying?.let { if (it.loading) nowPlaying = it.copy(loading = false) }
        }

        // 轮询播放状态
        viewModelScope.launch {
            var pollTick = 0
            while (true) {
                delay(200)
                val s = runCatching { playbackController.snapshot() }.getOrNull() ?: continue
                playing = s.isPlaying
                if (s.itemCount > 0) positionMs = s.positionMs
                buffering = s.buffering
                serviceItemCount = s.itemCount
                if (s.durationMs > 0) durationMs = s.durationMs
                
                if (nowPlaying != null && ++pollTick % 25 == 0) {
                    sessionStore.savePosition(positionMs)
                }

                if (s.ended) {
                    handleTrackEnded()
                }
            }
        }

        // 按当前曲目加载歌词
        viewModelScope.launch {
            while (true) {
                val currentKey = nowPlaying?.key
                if (currentKey != null) {
                    val item = nowPlaying!!.item
                    lyricLoading = true
                    lyricLines = runCatching {
                        withContext(Dispatchers.IO) {
                            withTimeout(20000) { sourceService.lyrics(item) }
                        }
                    }.getOrNull().let { LyricParser.parse(it) }
                    lyricLoading = false
                }
                // 等待曲目切换
                while (nowPlaying?.key == currentKey) {
                    delay(500)
                }
            }
        }
        
        // 定期保存播放会话
        viewModelScope.launch {
            while(true) {
                delay(2000)
                sessionStore.save(playQueue.toList(), queueIndex, nowPlaying?.item, nowPlaying?.qualityKey, nowPlaying?.size, positionMs)
            }
        }
    }

    private fun handleTrackEnded() {
        val cur = nowPlaying
        val guard = "${cur?.key}@$queueIndex"
        if (cur != null && lastEndedKey != guard) {
            lastEndedKey = guard
            when (playMode) {
                1 -> if (queueIndex in playQueue.indices) {
                    lastEndedKey = null
                    startTrack(playQueue[queueIndex], queueIndex)
                }
                2 -> if (playQueue.size > 1) {
                    val ni = playQueue.indices.filter { it != queueIndex }.random()
                    startTrack(playQueue[ni], ni)
                }
                else -> advance(1, silent = true)
            }
        }
    }

    fun startTrack(item: MediaItem, index: Int, excludeQualities: Set<String> = emptySet()) {
        playToken++
        val token = playToken
        playbackController.clear()
        playRequestJob?.cancel()
        sourceService.cancelPendingRequests()
        playing = false
        positionMs = 0
        durationMs = item.durationMs
        queueIndex = index
        val best = item.qualities.keys.maxByOrNull(::qualityRankOf) ?: "320k"
        nowPlaying = NowPlaying(item, "", best, item.qualities[best]?.size, loading = true)
        
        playRequestJob = viewModelScope.launch {
            val defaultPlayQuality = repository.playQuality.first()
            var resolveEmpty = false
            val np: NowPlaying? = withContext(Dispatchers.IO) {
                if (item.platform == "BakaMusic") {
                    val url = item.mediaUrl?.takeIf { it.startsWith("http", true) }
                    return@withContext if (url != null) {
                        val q = item.qualities.keys.maxByOrNull(::qualityRankOf) ?: "320k"
                        NowPlaying(item, url, q, null)
                    } else {
                        playError = "该歌曲暂无可用音源"; null
                    }
                }
                if (!playbackController.awaitConnected()) {
                    playError = "播放器连接失败，请重试"; return@withContext null
                }
                val key = "${item.platform}:${item.id}"
                val genNow = sourceService.dataVersion()
                if (resolvedCacheGen != genNow) {
                    resolvedCache.clear()
                    resolvedCacheGen = genNow
                }
                if (excludeQualities.isEmpty()) resolvedCache[key]?.let { return@withContext it }
                
                val resPair = try {
                    val r = withTimeout(PLAY_RESOLVE_TIMEOUT_MS) {
                        sourceService.resolveForPlaybackDetailed(item, defaultPlayQuality, excludeQualities)
                    }
                    if (r == null) resolveEmpty = true
                    r
                } catch (e: Exception) {
                    if (token == playToken) {
                        if (nowPlaying?.key == key) nowPlaying = nowPlaying?.copy(loading = false)
                        if (e is MediaResolveTimeoutException || e is TimeoutCancellationException) {
                            playerError = "播放器错误 3003：Source error"
                            playerErrorRetry = true
                        } else if (e is CancellationException) {
                            // 协程取消，无需处理
                        } else {
                            playerError = "该歌曲暂无可用音源"; playerErrorRetry = false
                        }
                    }
                    null
                } ?: return@withContext null
                
                val (res, actualQuality) = resPair
                val url = res.mediaUrl?.takeIf { it.startsWith("http", true) }
                    ?: run {
                        if (token == playToken) {
                            if (nowPlaying?.key == key) nowPlaying = nowPlaying?.copy(loading = false)
                            playerError = "该歌曲暂无可用音源"; playerErrorRetry = false
                        }
                        return@withContext null
                    }
                // res 由详情补全后的条目拷贝而来（如 bilibili 的完整音质表/rawJson），展示与缓存均用它
                val displayItem = res.copy(mediaUrl = null, mediaHeaders = emptyMap())
                val backups = res.backupUrls.filter { it != url }
                NowPlaying(displayItem, url, actualQuality, res.qualities[actualQuality]?.size, res.mediaHeaders, loading = false, backupUrls = backups).also {
                    resolvedCache[key] = it
                }
            }

            if (token != playToken) return@launch
            if (np == null) {
                if (nowPlaying?.key == "${item.platform}:${item.id}") nowPlaying = nowPlaying?.copy(loading = false)
                // 解析返回空（非取消/超时异常）时此前无任何提示，只会停在暂停态；此处补齐失败弹窗
                if (resolveEmpty) { playerError = "该歌曲暂无可用音源"; playerErrorRetry = false }
                return@launch
            }
            queueIndex = index
            nowPlaying = np
            positionMs = 0
            durationMs = item.durationMs
            runCatching {
                playbackController.play(np.item.copy(mediaUrl = np.mediaUrl, mediaHeaders = np.headers))
            }.onFailure {
                playError = "播放失败：${it.message ?: "无可用音源"}"
            }
        }
    }

    fun togglePlay() {
        val cur = nowPlaying
        if (cur != null && cur.mediaUrl.isBlank() && !cur.loading && serviceItemCount <= 0) {
            startTrack(cur.item, queueIndex)
            return
        }
        if (cur?.loading == true) return
        playbackController.toggle()
    }

    fun playItems(items: List<MediaItem>, index: Int) {
        if (items.isEmpty() || index !in items.indices) return
        playQueue.clear()
        playQueue.addAll(items)
        startTrack(items[index], index)
    }

    fun playMediaItem(item: MediaItem, context: List<MediaItem>) {
        val idx = context.indexOfFirst { it.platform == item.platform && it.id == item.id }
        if (idx >= 0) playItems(context, idx) else playItems(listOf(item), 0)
    }

    fun playAt(index: Int) {
        if (index !in playQueue.indices) return
        if (index == queueIndex && nowPlaying?.loading == false) {
            if (nowPlaying != null && (serviceItemCount > 0 || nowPlaying?.mediaUrl?.isNotBlank() == true)) return
        }
        startTrack(playQueue[index], index)
    }

    fun advance(delta: Int, silent: Boolean = false) {
        if (playQueue.isEmpty()) return
        val next = (queueIndex + delta).coerceIn(playQueue.indices)
        if (next == queueIndex) {
            if (!silent) {
                val msg = if (delta > 0) "已是最后一首" else "已是第一首"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            return
        }
        startTrack(playQueue[next], next)
    }

    fun stepNext() {
        if (playMode == 2 && playQueue.size > 1) {
            val ni = playQueue.indices.filter { it != queueIndex }.random()
            startTrack(playQueue[ni], ni)
            return
        }
        advance(1)
    }

    fun stepPrev() = advance(-1)

    fun retryLowerQuality(cur: NowPlaying) {
        playerError = null
        val order = QUALITY_KEYS_ORDERED
        val curIdx = order.indexOf(cur.qualityKey)
        val exclude = (if (curIdx >= 0) order.subList(curIdx, order.size) else listOf(cur.qualityKey)).toSet()
        startTrack(cur.item, queueIndex, exclude)
    }

    fun downloadItem(item: MediaItem, quality: String) {
        viewModelScope.launch {
            val resolved = runCatching {
                withContext(Dispatchers.IO) { sourceService.resolveByKey(item, quality) }
            }.getOrNull()
            if (resolved?.mediaUrl?.startsWith("http", true) == true) {
                runCatching { DownloadHelper.enqueueByKey(context, resolved, quality) }
                    .onFailure { playError = "下载失败：${it.message}" }
            } else {
                playError = "该音质暂无可用下载地址"
            }
        }
    }

    fun switchQuality(item: MediaItem, newKey: String) {
        playRequestJob?.cancel()
        sourceService.cancelPendingRequests()
        playRequestJob = viewModelScope.launch {
            val keepPos = playbackController.snapshot().positionMs
            val itemKey = "${item.platform}:${item.id}"
            nowPlaying?.takeIf { it.key == itemKey }?.let { nowPlaying = it.copy(loading = true) }
            
            val pair = try {
                withContext(Dispatchers.IO) {
                    withTimeout(PLAY_RESOLVE_TIMEOUT_MS) {
                        sourceService.resolveForPlaybackDetailed(item, newKey)
                    }
                }
            } catch (e: Exception) {
                if (nowPlaying?.key == itemKey) nowPlaying = nowPlaying?.copy(loading = false)
                if (e is MediaResolveTimeoutException || e is TimeoutCancellationException) {
                    playerError = "播放器错误 3003：Source error"; playerErrorRetry = true
                } else if (e !is CancellationException) {
                    playerError = "该歌曲暂无可用音源"; playerErrorRetry = false
                }
                return@launch
            } ?: run {
                if (nowPlaying?.key == itemKey) nowPlaying = nowPlaying?.copy(loading = false)
                playerError = "该歌曲暂无可用音源"; playerErrorRetry = false
                return@launch
            }
            
            val (res, q) = pair
            val url = res.mediaUrl?.takeIf { it.startsWith("http", true) }
                ?: run {
                    if (nowPlaying?.key == itemKey) nowPlaying = nowPlaying?.copy(loading = false)
                    playerError = "该歌曲暂无可用音源"; playerErrorRetry = false
                    return@launch
                }
            
            val np = NowPlaying(res.copy(mediaUrl = null, mediaHeaders = emptyMap()), url, q, res.qualities[q]?.size, res.mediaHeaders, loading = false, backupUrls = res.backupUrls.filter { it != url })
            if (resolvedCacheGen == sourceService.dataVersion()) {
                resolvedCache[itemKey] = np
            }
            nowPlaying = np
            runCatching {
                playbackController.play(np.item.copy(mediaUrl = url, mediaHeaders = res.mediaHeaders))
            }.onFailure {
                playError = "播放失败：${it.message}"
                if (nowPlaying?.key == itemKey) nowPlaying = nowPlaying?.copy(loading = false)
                return@launch
            }

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
    
    fun seekTo(pos: Long) {
        playbackController.seekTo(pos)
    }
}
