package com.bakamusic.android.plugin

import com.bakamusic.android.data.*
import android.util.Log
import kotlinx.coroutines.CancellationException

/** 按音源优先级路由插件调用，已禁用与未注册的插件永不被调用。 */
class PluginRouter(private val manager: PluginManager) {
    private val adapters = linkedMapOf<String, MusicPlugin>()

    fun register(plugin: InstalledPlugin, adapter: MusicPlugin) {
        require(plugin.enabled) { "禁用的插件不能注册为音源" }
        adapters[plugin.id] = adapter
    }

    fun unregister(pluginId: String) { adapters.remove(pluginId) }

    fun clear() { adapters.clear() }

    /** 切歌时立即终止各插件未完成的网络请求 */
    fun cancelPendingRequests() {
        adapters.values.toList().forEach { runCatching { it.cancelPendingRequests() } }
    }

    /** 插件调用失败返回 null，取消与播放超时必须继续向上传播 */
    private inline fun <T> runAbortable(block: () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaResolveTimeoutException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 搜索项 qualities 为空时用 getMusicInfo 补全，
     * 先补全详情再解析；补全失败则回退用原条目直接解析，不阻塞播放。
     */
    private suspend fun maybeEnrich(plugin: MusicPlugin, item: MediaItem): MediaItem {
        if (item.qualities.isNotEmpty()) return item
        val enriched = runAbortable { plugin.getMusicInfo(item) } ?: return item
        return if (enriched.qualities.isNotEmpty()) enriched else item
    }

    /** 替换全量映射，调用方需保证串行调用。 */
    fun replaceAll(entries: List<Pair<InstalledPlugin, MusicPlugin>>) {
        adapters.clear()
        entries.forEach { (meta, adapter) ->
            if (meta.enabled) adapters[meta.id] = adapter
        }
    }

    /** 按平台名获取当前最新适配器，调用方不应长期持有返回的旧对象 */
    fun findAdapterByPlatform(platform: String): MusicPlugin? = resolveAdapter(platform)

    fun findAdapterById(id: String): MusicPlugin? =
        manager.plugins().firstOrNull { it.id == id }?.let { adapters[it.id] }

    /** 注册名优先、运行时名兜底解析适配器 */
    private fun resolveAdapter(platform: String): MusicPlugin? {
        manager.plugins().firstOrNull { it.name == platform }?.let { adapters[it.id] }?.let { return it }
        return activeAdapters().firstOrNull { it.platform == platform }
    }

    private fun activeAdapters(): List<MusicPlugin> = manager.plugins().mapNotNull { adapters[it.id] }

    fun activeSources(): List<MusicPlugin> = activeAdapters()

    suspend fun search(plugin: MusicPlugin, query: String, page: Int = 1): SearchPage<MediaItem> {
        return try {
            plugin.search(query, page)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("BakaPlugin", "${plugin.platform}.search failed: ${error.message}", error)
            throw error
        }
    }

    suspend fun search(query: String, page: Int = 1): SearchPage<MediaItem> {
        for (plugin in activeAdapters()) runAbortable { plugin.search(query, page) }?.let { if (it.data.isNotEmpty() || it.isEnd) return it }
        return SearchPage(emptyList())
    }

    /** 指定平台榜单分组：无适配器或插件未实现时返回空列表。 */
    suspend fun topLists(platform: String): List<TopListGroup> {
        val plugin = resolveAdapter(platform) ?: return emptyList()
        return try {
            plugin.topLists()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // 旧实例已释放时抛出至上层，由上层获取最新适配器重试一次
            if (error is StalePluginException) throw error
            Log.e("BakaPlugin", "${plugin.platform}.getTopLists failed: ${error.message}", error)
            emptyList()
        }
    }

    /** 指定平台榜单详情：仅请求当前平台插件，确保 id 体系一致 */
    suspend fun topListDetail(platform: String, item: TopListItem, page: Int = 1): SearchPage<MediaItem> {
        val plugin = resolveAdapter(platform) ?: return SearchPage(emptyList())
        return try {
            plugin.topListDetail(item, page)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("BakaPlugin", "${plugin.platform}.getTopListDetail failed: ${error.message}", error)
            throw error
        }
    }

    suspend fun resolveMedia(item: MediaItem, quality: MusicQuality): MediaItem? {
        return resolveMediaByKey(item, quality.key)
    }

    /** 按音质键解析：仅请求当前平台插件，不跨平台解析 */
    suspend fun resolveMediaByKey(item: MediaItem, qualityKey: String): MediaItem? {
        val plugin = activeAdapters().firstOrNull { it.platform == item.platform }
            ?: run {
                Log.d("BakaPlugin", "无当前平台适配器，拒绝解析: ${item.platform}:${item.id}")
                return null
            }
        val current = maybeEnrich(plugin, item)
        val fullOrder = qualityOrderLowerFirst(qualityKey)
        val tried = filterQualityOrderByDeclared(current, fullOrder).ifEmpty { fullOrder }
        for (q in tried) {
            val res = runAbortable { plugin.getMediaSource(current, q) }
            if (res?.mediaUrl?.startsWith("http", true) == true) return res
        }
        // 后备：使用 qualities 自带的 url
        current.qualities[qualityKey]?.url?.takeIf { it.startsWith("http", true) }?.let {
            return current.copy(mediaUrl = it)
        }
        return null
    }

    /** 供播放调用：默认音质优先向更低音质降级 */
    suspend fun resolveForPlayback(item: MediaItem, defaultQualityKey: String): MediaItem? {
        return resolveMediaByKey(item, defaultQualityKey)
    }

    /** 供播放调用（返回实际命中的音质键，供播放栏显示），仅请求当前平台 */
    suspend fun resolveForPlaybackDetailed(
        item: MediaItem,
        defaultQualityKey: String,
        excludeQualities: Set<String> = emptySet()
    ): Pair<MediaItem, String>? {
        val plugin = activeAdapters().firstOrNull { it.platform == item.platform }
            ?: run {
                Log.d("BakaPlugin", "无当前平台适配器，拒绝解析: ${item.platform}:${item.id}")
                return null
            }
        val current = maybeEnrich(plugin, item)
        val fullOrder = qualityOrderLowerFirst(defaultQualityKey)
        val tried = filterQualityOrderByDeclared(current, fullOrder).ifEmpty { fullOrder }
        for (q in tried) {
            if (excludeQualities.contains(q)) continue
            val res = runAbortable { plugin.getMediaSourceDetailed(current, q) }
            if (res?.first?.mediaUrl?.startsWith("http", true) == true) {
                val (media, actual) = res
                Log.d("BakaPlugin", "解析命中 ${current.platform}:${current.id} quality=$actual(requested=$q) via ${plugin.platform}")
                return media to actual
            }
        }
        if (!excludeQualities.contains(defaultQualityKey)) {
            current.qualities[defaultQualityKey]?.url?.takeIf { it.startsWith("http", true) }?.let {
                return current.copy(mediaUrl = it) to defaultQualityKey
            }
        }
        Log.d("BakaPlugin", "解析失败 ${current.platform}:${current.id} tried=$tried excluded=$excludeQualities")
        return null
    }
    suspend fun lyric(item: MediaItem): LyricSource? {
        // 仅请求当前平台插件，各平台 id 体系不同
        val plugin = activeAdapters().firstOrNull { it.platform == item.platform }
            ?: run {
                Log.d("BakaPlugin", "无当前平台适配器，跳过歌词: ${item.platform}:${item.id}")
                return null
            }
        return try {
            val res = plugin.getLyric(item)
            if (res == null) Log.d("BakaPlugin", "${plugin.platform}.lyric 空结果(id=${item.id})")
            res
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is MediaResolveTimeoutException) throw error
            Log.d("BakaPlugin", "${plugin.platform}.lyric 失败(id=${item.id}): ${error.message}")
            null
        }
    }
    suspend fun importSheet(urlLike: String): PlaylistSnapshot? {
        for (plugin in activeAdapters()) {
            val res = runAbortable { plugin.importMusicSheet(urlLike) }
            if (res != null) return res
        }
        return null
    }
}
