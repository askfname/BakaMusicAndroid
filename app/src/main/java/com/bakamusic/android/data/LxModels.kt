package com.bakamusic.android.data

import org.json.JSONArray
import org.json.JSONObject

/** LX 取流源 */
val LX_SOURCES = listOf("kw", "kg", "tx", "wy", "mg")

/** 平台名→LX 源 */
private val LX_PLATFORM_NAMES = mapOf(
    "kw" to listOf("酷我音乐", "酷我", "Kuwo"),
    "kg" to listOf("酷狗音乐", "酷狗", "Kugou"),
    "tx" to listOf("QQ音乐", "QQ Music", "Tencent"),
    "wy" to listOf("网易云音乐", "网易云", "NetEase Cloud Music", "Netease"),
    "mg" to listOf("咪咕音乐", "咪咕", "Migu")
)

fun lxSourceForPlatform(platform: String): String? {
    val n = platform.trim().lowercase()
    return LX_SOURCES.firstOrNull { s -> LX_PLATFORM_NAMES[s]?.any { it.lowercase() == n } == true }
}

data class LxSourceDescriptor(val qualities: List<String>)

data class LxPluginDescriptor(
    val hash: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
    val sourceUrl: String = "",
    val sources: Map<String, LxSourceDescriptor> = emptyMap(),
    val active: Boolean = false
)

data class LxScriptInfo(
    val name: String,
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = ""
)

/** 解析脚本头注释 @name/@version/@author 等 */
fun parseLxScriptInfo(code: String, fallbackName: String): LxScriptInfo {
    val header = Regex("^\\uFEFF?\\s*/\\*[\\s\\S]*?\\*/").find(code)?.value ?: ""
    val values = mutableMapOf<String, String>()
    header.split(Regex("\\r?\\n")).forEach { line ->
        Regex("^\\s*\\*?\\s*@([A-Za-z]+)\\s+(.+?)\\s*$").find(line)?.let {
            values[it.groupValues[1].lowercase()] = it.groupValues[2]
        }
    }
    fun v(k: String, max: Int) = (values[k] ?: "").trim().take(max)
    val name = v("name", 128).ifBlank { fallbackName.trim().take(128) }.ifBlank { "LX Source" }
    return LxScriptInfo(name, v("description", 512), v("version", 64), v("author", 128), v("homepage", 2048))
}

/** 解析 lx.send('inited') 上报的 sources */
fun parseLxSourcesDeclared(data: JSONObject): Map<String, LxSourceDescriptor> {
    val out = mutableMapOf<String, LxSourceDescriptor>()
    val rawSources = data.optJSONObject("sources") ?: return out
    for (s in LX_SOURCES) {
        val raw = rawSources.optJSONObject(s) ?: continue
        if (raw.optString("type") != "music") continue
        val actions = raw.optJSONArray("actions") ?: continue
        var musicUrl = false
        for (i in 0 until actions.length()) {
            if (actions.optString(i) == "musicUrl") { musicUrl = true; break }
        }
        if (!musicUrl) continue
        val rawQ = raw.optJSONArray("qualitys") ?: raw.optJSONArray("qualities") ?: continue
        val qs = linkedSetOf<String>()
        for (i in 0 until rawQ.length()) {
            val q = rawQ.optString(i)
            if (QUALITY_KEYS_ORDERED.contains(q)) qs.add(q)
        }
        if (qs.isEmpty()) continue
        out[s] = LxSourceDescriptor(qs.toList())
    }
    return out
}

/** 用户为某源选中的 LX 脚本（未配置则走原生插件） */
fun activeLxPluginForSource(plugins: List<LxPluginDescriptor>, activeHash: String?, source: String?): LxPluginDescriptor? {
    if (activeHash.isNullOrEmpty() || source.isNullOrEmpty()) return null
    return plugins.firstOrNull { it.hash == activeHash && it.sources.containsKey(source) }
}

/** LX 降级：本档起逐级向低 */
fun lxQualityFallbacks(quality: String, supported: List<String>): List<String> {
    val idx = QUALITY_KEYS_ORDERED.indexOf(quality)
    if (idx < 0) return if (supported.contains(quality)) listOf(quality) else emptyList()
    return (listOf(quality) + QUALITY_KEYS_ORDERED.subList(0, idx).reversed())
        .filter { supported.contains(it) }
}

/** LX 可用音质键 */
fun lxMusicQualityKeys(item: MediaItem, supported: List<String>, useSupported: Boolean): List<String> {
    if (item.qualities.isNotEmpty()) {
        return if (useSupported) supported.toList() else supported.filter { item.qualities.containsKey(it) }
    }
    return if (useSupported) supported.toList() else emptyList()
}

/** 以 LX 音质集重写条目 qualities */
fun replaceLxMusicQualities(item: MediaItem, supported: List<String>, useSupported: Boolean): MediaItem {
    val keys = lxMusicQualityKeys(item, supported, useSupported)
    return item.copy(qualities = keys.associateWith { item.qualities[it] ?: QualityInfo() })
}

/** 组装 LX 脚本入参 musicInfo */
fun toLxMusicInfo(source: String, item: MediaItem): JSONObject {
    val raw = runCatching { JSONObject(item.rawJson ?: "{}") }.getOrDefault(JSONObject())
    val rawQualities = raw.optJSONObject("qualities")
    fun qHash(quality: String): String? {
        val declared = rawQualities?.optJSONObject(quality)?.optString("hash")?.trim()
        if (!declared.isNullOrEmpty()) return declared
        fun top(vararg keys: String): String? {
            for (k in keys) raw.optString(k).trim().takeIf { it.isNotEmpty() }?.let { return it }
            return null
        }
        return when (quality) {
            "320k" -> top("320hash", "hash", "id")
            "flac", "flac24bit" -> top("sqhash", "hash", "id")
            "hires", "master" -> top("ResFileHash", "sqhash", "hash", "id")
            else -> top("hash", "id")
        }?.takeIf { source == "kg" }
    }
    val types = JSONArray()
    val qualityMap = JSONObject()
    if (rawQualities != null) {
        val keys = rawQualities.keys()
        while (keys.hasNext()) {
            val q = keys.next()
            val info = rawQualities.optJSONObject(q) ?: continue
            val size = if (info.has("size") && !info.isNull("size")) info.opt("size")?.toString() else null
            val entry = JSONObject().apply {
                put("type", q)
                if (size != null) put("size", size) else put("size", JSONObject.NULL)
                qHash(q)?.let { put("hash", it) }
            }
            types.put(entry)
            qualityMap.put(q, JSONObject().apply {
                if (size != null) put("size", size) else put("size", JSONObject.NULL)
                qHash(q)?.let { put("hash", it) }
            })
        }
    }
    fun str(vararg keys: String): String {
        for (k in keys) raw.optString(k).trim().takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }
    val songId = when (source) {
        "tx" -> str("songmid", "mid", "id")
        "kg" -> str("songmid", "audio_id", "album_audio_id", "id")
        "mg" -> str("songmid", "songId", "id")
        else -> str("songmid", "id")
    }
    val albumName = str("album", "albumName")
    val albumId = str("albumId", "album_id", "albumid")
    val artwork = str("artwork", "img").ifBlank { item.artwork.orEmpty() }
    val meta = JSONObject().apply {
        put("songId", songId)
        put("albumName", albumName)
        put("albumId", albumId)
        put("picUrl", artwork)
        put("qualitys", types)
        put("_qualitys", qualityMap)
    }
    if (source == "kg") meta.put("hash", str("hash", "id"))
    if (source == "tx") {
        meta.put("strMediaMid", str("strMediaMid", "mediaMid", "songmid", "mid", "id"))
        meta.put("albumMid", str("albumMid", "albummid"))
        meta.put("id", str("songId", "id"))
    }
    if (source == "mg") {
        meta.put("copyrightId", str("copyrightId", "id"))
        meta.put("lrcUrl", str("lrcUrl", "lrc"))
        meta.put("mrcUrl", str("mrcUrl"))
        meta.put("trcUrl", str("trcUrl"))
    }
    val out = JSONObject(raw.toString()).apply {
        put("id", item.id)
        put("name", item.title)
        put("singer", item.artist)
        put("source", source)
        put("songmid", songId)
        put("interval", item.durationMs.takeIf { it > 0 }?.let {
            val totalSec = if (it > 100000) (it / 1000).toInt() else (it / 1000).toInt()
            // durationMs 为毫秒：>100s 即视为毫秒时间戳，先转秒；否则按秒处理
            "%02d:%02d".format(totalSec / 60, totalSec % 60)
        } ?: JSONObject.NULL)
        put("albumName", albumName)
        put("albumId", albumId)
        put("img", artwork)
        put("types", types)
        put("_types", qualityMap)
        put("typeUrl", JSONObject())
        put("meta", meta)
    }
    if (source == "kg") out.put("hash", str("hash", "id"))
    if (source == "tx") {
        out.put("strMediaMid", meta.optString("strMediaMid"))
        out.put("albumMid", meta.optString("albumMid"))
        out.put("songId", meta.optString("id"))
    }
    if (source == "mg") {
        out.put("copyrightId", meta.optString("copyrightId"))
        out.put("lrcUrl", meta.optString("lrcUrl"))
        out.put("mrcUrl", meta.optString("mrcUrl"))
        out.put("trcUrl", meta.optString("trcUrl"))
    }
    return out
}
