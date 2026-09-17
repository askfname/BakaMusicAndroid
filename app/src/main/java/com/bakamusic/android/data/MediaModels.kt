package com.bakamusic.android.data

/** 点播解析整体超时：超时终止请求，并按失败原因显示对应的播放提示 */
const val PLAY_RESOLVE_TIMEOUT_MS = 20_000L

/** 当前播放项：已解析地址与实际播放音质，供播放栏/播放页显示 */
data class NowPlaying(
    val item: MediaItem,
    val mediaUrl: String,
    val qualityKey: String,
    val size: Long? = null,
    /** 播放请求头，随地址一起传递给播放器 */
    val headers: Map<String, String> = emptyMap(),
    /** 是否处于解析中：解析中先显示歌曲信息并叠加加载动画 */
    val loading: Boolean = false,
    /** 备用播放地址：主源 403/断连时自动切换，耗尽才弹失败框 */
    val backupUrls: List<String> = emptyList()
) {
    val key: String get() = "${item.platform}:${item.id}"
}

enum class MusicQuality(val key: String, val label: String) {
    Q96("96k", "流畅 · 96K"),
    LOW("128k", "标准 · 128K"),
    Q192("192k", "中等 · 192K"),
    HIGH("320k", "高品质 · 320K"),
    LOSSLESS("flac", "无损 · FLAC"),
    FLAC24("flac24bit", "无损 · FLAC 24Bit"),
    HI_RES("hires", "Hi-Res"),
    VINYL("vinyl", "黑胶 · Vinyl"),
    DOLBY("dolby", "杜比 · Dolby"),
    ATMOS("atmos", "空间 · Atmos"),
    ATMOS_PLUS("atmos_plus", "空间 · Atmos 2.0"),
    MASTER("master", "大师 · Master");

    companion object {
        fun fromKey(key: String?): MusicQuality? = values().firstOrNull { it.key == key }
        fun labelOf(key: String): String = fromKey(key)?.label ?: key.uppercase()
    }
}

/** 音质键：从低到高 */
val QUALITY_KEYS_ORDERED = listOf(
    "96k", "128k", "192k", "320k", "flac", "flac24bit",
    "hires", "vinyl", "dolby", "atmos", "atmos_plus", "master"
)

fun qualityRankOf(key: String): Int = QUALITY_KEYS_ORDERED.indexOf(key).let { if (it < 0) -1 else it }

/** 生成音质尝试顺序：默认音质优先，其次更低音质，最后更高音质 */
fun qualityOrderLowerFirst(defaultKey: String): List<String> {
    val idx = QUALITY_KEYS_ORDERED.indexOf(defaultKey)
    if (idx < 0) return listOf(defaultKey) + QUALITY_KEYS_ORDERED
    val left = QUALITY_KEYS_ORDERED.subList(0, idx).reversed()
    val right = QUALITY_KEYS_ORDERED.subList(idx + 1, QUALITY_KEYS_ORDERED.size)
    return listOf(defaultKey) + left + right
}

fun filterQualityOrderByDeclared(item: MediaItem, order: List<String>): List<String> {
    if (item.qualities.isEmpty()) return order
    val declared = item.qualities.keys
    val filtered = order.filter { declared.contains(it) }
    return filtered.ifEmpty { declared.sortedBy(::qualityRankOf) }
}

data class MediaItem(
    val id: String,
    val platform: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artwork: String? = null,
    val durationMs: Long = 0,
    val mediaUrl: String? = null,
    val mediaHeaders: Map<String, String> = emptyMap(),
    val lyric: LyricSource? = null,
    val qualities: Map<String, QualityInfo> = emptyMap(),
    /** 插件返回的完整原始 JSON，用于回传给 getMediaSource/getLyric */
    val rawJson: String? = null,
    /** 备用播放地址（如 bilibili 的 backupUrl），主源失败时自动切换 */
    val backupUrls: List<String> = emptyList()
)

data class QualityInfo(val size: Long? = null, val bitrate: Long? = null, val url: String? = null)

data class LyricSource(
    val rawLrc: String? = null,
    val translation: String? = null,
    val romanization: String? = null,
    val format: String = "lrc"
)

data class SearchPage<T>(val data: List<T>, val isEnd: Boolean = true)

/** 榜单分组：title 为分组名，items 为该分组下的榜单条目。 */
data class TopListGroup(
    val title: String = "",
    val items: List<TopListItem> = emptyList()
)

/** 单个榜单条目：原始字段保留在 rawJson 中，用于调用 getTopListDetail 时定位榜单。 */
data class TopListItem(
    val id: String,
    val platform: String = "",
    val title: String = "",
    val description: String = "",
    val coverImg: String? = null,
    /** 所属分组名，仅展示用，不传递给插件 */
    val groupTitle: String = "",
    val rawJson: String? = null
)

/** 播放解析请求超时（20s）：与协程取消区分，超时显示 3003 错误弹窗。 */
class MediaResolveTimeoutException(message: String) : Exception(message)

/** 插件运行时已被重载/释放：调用方应按平台名重新获取最新适配器重试一次。 */
class StalePluginException(message: String) : IllegalStateException(message)

/** 插件契约的数据侧接口。 */
interface MusicPlugin {
    val platform: String
    val version: String?
    val supportedQualities: Set<String>
    suspend fun search(query: String, page: Int): SearchPage<MediaItem>
    suspend fun getMediaSource(item: MediaItem, quality: String): MediaItem?
    /** 歌曲详情补全（如 bilibili 搜索项 qualities 为空时补音质/封面），不支持返回 null。 */
    suspend fun getMusicInfo(item: MediaItem): MediaItem? = null
    /**
     * 带实际命中音质的播放解析：部分插件会降级返回（如 bilibili 请求 flac 实际命中 320k），
     * 此时第二分量为插件上报的实际音质键；默认实现沿用请求音质。
     */
    suspend fun getMediaSourceDetailed(item: MediaItem, quality: String): Pair<MediaItem, String>? =
        getMediaSource(item, quality)?.let { it to quality }
    suspend fun getLyric(item: MediaItem): LyricSource?
    suspend fun importMusicSheet(urlLike: String): PlaylistSnapshot?
    /** 榜单分组，不支持的插件返回空列表。 */
    suspend fun topLists(): List<TopListGroup> = emptyList()
    /** 榜单详情，多数插件忽略 page，isEnd 缺省为 true。 */
    suspend fun topListDetail(item: TopListItem, page: Int = 1): SearchPage<MediaItem> = SearchPage(emptyList())
    /** 切歌时立即终止该插件未完成的网络请求 */
    fun cancelPendingRequests()
}

data class PlaylistSnapshot(
    val id: String,
    val platform: String,
    val title: String,
    val artist: String? = null,
    val artwork: String? = null,
    val musicList: List<MediaItem> = emptyList()
)
