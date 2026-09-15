package com.bakamusic.android

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

/** 默认音质缺失时优先更低音质 */
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
    /** 插件返回的完整原始 JSON，回传 getMediaSource/getLyric 用 */
    val rawJson: String? = null
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

/** 单个榜单条目：原始字段保留在 rawJson 中，回传 getTopListDetail 时供插件定位榜单。 */
data class TopListItem(
    val id: String,
    val platform: String = "",
    val title: String = "",
    val description: String = "",
    val coverImg: String? = null,
    /** 所属分组名，仅展示用，不回传插件 */
    val groupTitle: String = "",
    val rawJson: String? = null
)

/** 播放解析请求超时（10s）：与协程取消区分，超时弹 3003 弹窗。 */
class MediaResolveTimeoutException(message: String) : Exception(message)

/** 插件运行时已被重载/释放：调用方应按平台名重取最新适配器重试一次。 */
class StalePluginException(message: String) : IllegalStateException(message)

/** 插件契约的数据侧接口。 */
interface MusicPlugin {
    val platform: String
    val version: String?
    val supportedQualities: Set<String>
    suspend fun search(query: String, page: Int): SearchPage<MediaItem>
    suspend fun getMediaSource(item: MediaItem, quality: String): MediaItem?
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
