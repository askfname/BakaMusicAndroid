package com.bakamusic.android.service

import com.bakamusic.android.data.*
import com.bakamusic.android.plugin.*
import android.content.Context
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.WeakHashMap

/**
 * 搜索、歌单导入、播放地址与歌词的统一入口。
 * UI 与下载层应经由本服务调用，不直接选择插件；PluginRouter 按启用优先级路由。
 */
class MusicSourceService(context: Context, private val router: PluginRouter = PluginRouter(PluginManager(context.applicationContext))) {
    private val appContext = context.applicationContext
    private val manager = PluginManager(appContext)
    private val runtime = QuickJsPluginRuntime(manager)
    private var loaded = false
    /** 已加载的注册表指纹：插件变更后下次调用自动重载 */
    private var loadedVersion: Long = -1
    /** 串行化加载：避免并发替换 QuickJS 上下文 */
    private val loadMutex = kotlinx.coroutines.sync.Mutex()

    init {
        live.add(this)
    }

    fun close() {
        runtime.destroy()
        live.remove(this)
    }

    private suspend fun ensureLoaded() {
        if (loaded && manager.dataVersion() == loadedVersion) return
        loadMutex.withLock {
            if (loaded && manager.dataVersion() == loadedVersion) return
            router.replaceAll(runtime.loadEnabled())
            loaded = true
            // 取加载完成后的最新指纹
            loadedVersion = manager.dataVersion()
        }
    }

    /** 插件变更后调用，重新加载 QuickJS 音源。 */
    suspend fun refresh() {
        // 注册表无变化时跳过重建，避免中断正在提供服务的旧实例
        if (loaded && manager.dataVersion() == loadedVersion) return
        loadMutex.withLock {
            if (loaded && manager.dataVersion() == loadedVersion) return
            router.replaceAll(runtime.loadEnabled())
            loaded = true
            loadedVersion = manager.dataVersion()
        }
    }

    companion object {
        private val live: MutableSet<MusicSourceService> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<MusicSourceService, Boolean>()))
        /** 插件变更后调用：所有现存运行时立即重载新插件。 */
        suspend fun refreshAll() {
            val snapshot: List<MusicSourceService> = synchronized(live) { live.toList() }
            snapshot.forEach { service ->
                runCatching { service.refresh() }
            }
        }
    }

    suspend fun sources(): List<MusicPlugin> { ensureLoaded(); return router.activeSources() }
    fun enabledSourceNames(): List<String> = manager.enabledPluginNames()
    /** 注册表元信息，供音源选择组件展示，不调用 JS 运行时 */
    fun installedPlugins(): List<InstalledPlugin> = manager.plugins()
    /** 插件注册表指纹，供播放地址缓存失效判断 */
    fun dataVersion(): Long = manager.dataVersion()
    /** 切歌时立即终止未完成的插件网络请求，不阻塞调用方 */
    fun cancelPendingRequests() {
        runCatching { runtime.cancelPending() }
        runCatching { router.cancelPendingRequests() }
    }
    suspend fun searchSource(source: MusicPlugin, query: String, page: Int = 1): SearchPage<MediaItem> { ensureLoaded(); return router.search(source, query, page) }

    /** 按平台名搜索：每次取最新适配器，找不到则以第一优先级作为后备。 */
    suspend fun searchByPlatform(platform: String?, query: String, page: Int = 1): SearchPage<MediaItem> {
        ensureLoaded()
        val adapter = platform?.let { router.findAdapterByPlatform(it) }
            ?: router.activeSources().firstOrNull()
            ?: throw IllegalStateException("无可用插件")
        return router.search(adapter, query, page)
    }

    suspend fun search(query: String, page: Int = 1): SearchPage<MediaItem> { ensureLoaded(); return router.search(query, page) }
    /** 指定平台榜单分组，无榜单能力时返回空列表 */
    suspend fun topListsByPlatform(platform: String): List<TopListGroup> {
        ensureLoaded()
        return try {
            router.topLists(platform)
        } catch (e: StalePluginException) {
            // 若遇到插件重建，按平台名重新获取最新适配器并仅重试一次
            ensureLoaded()
            router.topLists(platform)
        }
    }
    /** 指定平台榜单详情，无榜单能力时返回空结果 */
    suspend fun topListDetailByPlatform(platform: String, item: TopListItem, page: Int = 1): SearchPage<MediaItem> {
        ensureLoaded()
        return try {
            router.topListDetail(platform, item, page)
        } catch (e: StalePluginException) {
            ensureLoaded()
            router.topListDetail(platform, item, page)
        }
    }
    suspend fun importPlaylist(url: String) = ensureLoaded().let { router.importSheet(url) }
    suspend fun resolve(item: MediaItem, quality: MusicQuality) = ensureLoaded().let { router.resolveMedia(item, quality) }
    suspend fun resolveByKey(item: MediaItem, qualityKey: String) = ensureLoaded().let { router.resolveMediaByKey(item, qualityKey) }
    suspend fun resolveForPlayback(item: MediaItem, defaultQualityKey: String) = ensureLoaded().let { router.resolveForPlayback(item, defaultQualityKey) }
    suspend fun resolveForPlaybackDetailed(item: MediaItem, defaultQualityKey: String, excludeQualities: Set<String> = emptySet()) = ensureLoaded().let { router.resolveForPlaybackDetailed(item, defaultQualityKey, excludeQualities) }
    suspend fun lyrics(item: MediaItem) = ensureLoaded().let { router.lyric(item) }
}
