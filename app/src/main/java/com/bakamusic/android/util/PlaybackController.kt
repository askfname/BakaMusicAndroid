package com.bakamusic.android.util

import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** 播放状态快照：UI 轮询同步显示，不阻塞 */
data class PlaybackSnapshot(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    /** 是否播放完毕，用于自动下一首 */
    val ended: Boolean = false,
    /** 是否正在缓冲，用于加载动画显示 */
    val buffering: Boolean = false
)

/**
 * UI 控制 MediaSessionService 的轻量边界。
 * 线程模型：所有播放控制调用最终都在应用主线程执行，非主线程调用会被自动转发；
 * awaitConnected/snapshot 可在任意线程调用。
 */
class PlaybackController(context: Context) {
    @Volatile private var released = false
    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))
    ).buildAsync()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    private val errorListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    private fun isAppThread() = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    /** 非主线程调用时转发到主线程执行 */
    private fun runOnAppThread(action: () -> Unit) {
        if (released) return
        if (isAppThread()) runCatching { if (!released) action() }
        else mainHandler.post { runCatching { if (!released) action() } }
    }

    private val playerListener = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val msg = "播放器错误 ${error.errorCode}: ${error.message ?: "未知错误"}"
            android.util.Log.e("BakaPlayer", "$msg (${error.errorCodeName})", error)
            errorListeners.forEach { runCatching { it(msg) } }
        }

        /** 进入播放状态时回调，用于结束加载状态显示 */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) audibleListeners.forEach { runCatching { it() } }
        }
    }

    private val audibleListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 订阅进入播放状态事件，返回取消订阅函数 */
    fun addAudibleListener(listener: () -> Unit): () -> Unit {
        audibleListeners.add(listener)
        return { audibleListeners.remove(listener) }
    }

    init {
        // 连接建立后在主线程挂载错误监听
        controllerFuture.addListener(
            { mainHandler.post { runCatching { controllerFuture.get() }.getOrNull()?.addListener(playerListener) } },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /** 订阅播放器异步错误，返回取消订阅函数 */
    fun addErrorListener(listener: (String) -> Unit): () -> Unit {
        errorListeners.add(listener)
        return { errorListeners.remove(listener) }
    }

    private fun controllerOrNull(): MediaController? {
        if (!controllerFuture.isDone) return null
        return runCatching { controllerFuture.get() }.getOrNull()
    }

    private fun toExo(item: MediaItem, uri: String): ExoMediaItem {
        // 播放请求头登记后由播放服务的 DataSource 注入
        BakaMediaHeaders.put(uri, item.mediaHeaders)
        return ExoMediaItem.Builder().setUri(uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).setAlbumTitle(item.album).build()
        ).build()
    }

    fun play(item: MediaItem) {
        val uri = requireNotNull(item.mediaUrl) { "当前歌曲没有可用的媒体地址" }
        if (isAppThread()) {
            doPlayNow(uri, item)
            return
        }
        runOnAppThread { doPlayNow(uri, item) }
    }

    private fun doPlayNow(uri: String, item: MediaItem) {
        val controller = controllerOrNull()
        if (controller != null) {
            runCatching { doPlay(controller, item) }
                .onFailure { notifyError("播放失败：${it.message}") }
            return
        }
        // 未连接时等待连接后播放
        ioScope.launch {
            if (awaitConnected(8000)) {
                runCatching { controllerOrNull()?.let { doPlay(it, item) } }
                    .onFailure { notifyError("播放失败：${it.message}") }
            } else {
                notifyError("播放器连接失败，请重试")
            }
        }
    }

    private fun notifyError(msg: String) {
        errorListeners.forEach { l -> runCatching { l(msg) } }
    }

    private fun doPlay(controller: MediaController, item: MediaItem) {
        val uri = requireNotNull(item.mediaUrl) { "当前歌曲没有可用的媒体地址" }
        controller.setMediaItem(toExo(item, uri))
        controller.prepare()
        controller.play()
    }

    fun playQueue(items: List<MediaItem>, startIndex: Int = 0) {
        items.forEach { requireNotNull(it.mediaUrl) { "队列歌曲没有可用的媒体地址" } }
        runOnAppThread {
            val controller = controllerOrNull()
            if (controller != null) {
                runCatching { doPlayQueue(controller, items, startIndex) }
                return@runOnAppThread
            }
            ioScope.launch {
                if (awaitConnected(8000)) {
                    runCatching { controllerOrNull()?.let { doPlayQueue(it, items, startIndex) } }
                }
            }
        }
    }

    private fun doPlayQueue(controller: MediaController, items: List<MediaItem>, startIndex: Int) {
        controller.setMediaItems(items.map { item ->
            toExo(item, requireNotNull(item.mediaUrl) { "队列歌曲没有可用的媒体地址" })
        }, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun stop() = runOnAppThread { runCatching { controllerOrNull()?.stop() } }

    /** 切换曲目时调用：停止播放并清空队列，避免残留失效地址。 */
    fun clear() = runOnAppThread {
        runCatching {
            controllerOrNull()?.let {
                it.stop()
                it.clearMediaItems()
            }
        }
    }
    fun toggle() = runOnAppThread { controllerOrNull()?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() = runOnAppThread { controllerOrNull()?.seekToNextMediaItem() }
    fun previous() = runOnAppThread { controllerOrNull()?.seekToPreviousMediaItem() }
    fun seekTo(positionMs: Long) = runOnAppThread { runCatching { controllerOrNull()?.seekTo(positionMs.coerceAtLeast(0)) } }

    /** 等待播放控制器连接建立 */
    suspend fun awaitConnected(timeoutMs: Long = 6000): Boolean {
        if (controllerOrNull() != null) return true
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                controllerFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                true
            }.getOrDefault(false)
        }
    }

    /** 供轮询调用：未连接时返回默认快照，不阻塞 */
    fun snapshot(): PlaybackSnapshot {
        val controller = controllerOrNull() ?: return PlaybackSnapshot()
        return runCatching {
            PlaybackSnapshot(
                connected = true,
                isPlaying = controller.isPlaying,
                positionMs = controller.currentPosition.coerceAtLeast(0),
                durationMs = controller.duration.takeIf { it > 0 } ?: 0,
                itemIndex = controller.currentMediaItemIndex.coerceAtLeast(0),
                itemCount = controller.mediaItemCount,
                ended = controller.playbackState == androidx.media3.common.Player.STATE_ENDED,
                buffering = controller.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            )
        }.getOrDefault(PlaybackSnapshot(connected = true))
    }

    fun release() {
        if (released) return
        released = true
        runCatching { controllerOrNull()?.removeListener(playerListener) }
        runCatching { ioScope.cancel() }
        MediaController.releaseFuture(controllerFuture)
    }
}
