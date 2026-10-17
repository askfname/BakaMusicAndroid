package com.bakamusic.android.plugin

import android.util.Log
import com.bakamusic.android.data.MediaItem
import com.bakamusic.android.data.lxSourceForPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * 仅当 JS 的 getMediaSource 抛错或返回空地址时触发，不改变正常插件流程
 */
fun isQqPlatform(platform: String): Boolean {
    if (platform.trim().equals("QQ音乐", ignoreCase = true)) return true
    return lxSourceForPlatform(platform) == "tx"
}

// “念心”音质档位
private val QQ_NIANXIN_LEVEL = mapOf(
    "96k" to "standard",
    "128k" to "standard",
    "192k" to "standard",
    "320k" to "exhigh",
    "flac" to "lossless",
    "flac24bit" to "lossless",
    "hires" to "hires",
    "atmos" to "lossless",
    "atmos_plus" to "lossless",
    "master" to "jymaster",
    "vinyl" to "lossless",
    "dolby" to "lossless"
)

/** s01s 按音质的字段优先级（单字段为空时自动找同接口其它可用档） */
private val QQ_S01S_FIELDS = mapOf(
    "96k" to listOf("song_play_url_fq", "song_play_url_standard", "song_play_url", "song_play_url_hq", "song_play_url_sq"),
    "128k" to listOf("song_play_url_standard", "song_play_url", "song_play_url_hq", "song_play_url_fq", "song_play_url_sq"),
    "192k" to listOf("song_play_url_standard", "song_play_url", "song_play_url_hq", "song_play_url_fq", "song_play_url_sq"),
    "320k" to listOf("song_play_url_hq", "song_play_url", "song_play_url_sq", "song_play_url_standard", "song_play_url_fq"),
    "flac" to listOf("song_play_url_sq", "song_play_url", "song_play_url_hq", "song_play_url_standard", "song_play_url_fq"),
    "flac24bit" to listOf("song_play_url_sq", "song_play_url", "song_play_url_hq", "song_play_url_standard", "song_play_url_fq"),
    "hires" to listOf("song_play_url_sq", "song_play_url_hq", "song_play_url", "song_play_url_standard", "song_play_url_fq"),
    "atmos" to listOf("song_play_url_sq", "song_play_url_hq", "song_play_url", "song_play_url_standard", "song_play_url_fq"),
    "atmos_plus" to listOf("song_play_url_sq", "song_play_url_hq", "song_play_url", "song_play_url_standard", "song_play_url_fq"),
    "master" to listOf("song_play_url_sq", "song_play_url_hq", "song_play_url", "song_play_url_standard", "song_play_url_fq")
)

private val QQ_PAGE_URL = Regex("""music\.163\.com/song/media/outer|\.(?:html?|php)(?:[?#]|$)|/songDetail/""", RegexOption.IGNORE_CASE)
private const val QQ_API_TIMEOUT_MS = 8_000
private const val QQ_PROBE_TIMEOUT_MS = 5_000
private const val QQ_UA = "Mozilla/5.0"

/** 从回传载荷中取 QQ songmid（优先字母型 mid，纯数字 id 仅作最后尝试） */
private fun qqSongMid(payload: JSONObject, item: MediaItem): String? {
    val mid = payload.optString("songmid").trim().ifBlank { payload.optString("mid").trim() }
    if (mid.isNotEmpty()) return mid
    val id = item.id.trim()
    if (id.isNotEmpty()) return id
    return null
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun httpGetText(url: String, timeoutMs: Int): String {
    require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "bad url" }
    val host = URI(url).host?.lowercase() ?: throw IllegalArgumentException("bad url")
    require(!isPluginPrivateHost(host)) { "bad host" }
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", QQ_UA)
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        val code = conn.responseCode
        require(code in 200..299) { "HTTP $code" }
        val finalHost = conn.url?.host?.lowercase() ?: host
        require(!isPluginPrivateHost(finalHost)) { "bad redirect host" }
        val stream = try { conn.inputStream } catch (e: Exception) { conn.errorStream } ?: throw IllegalStateException("HTTP $code")
        return stream.use { readLimited(it, 512 * 1024) }.toString(Charsets.UTF_8)
    } finally {
        runCatching { conn.disconnect() }
    }
}

/** 音频探针：Range 首 1KB，命中 ID3/fLaC/OggS/MP3 帧同步/ftyp 才算可用 */
private fun probeQqAudio(url: String): Boolean {
    require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "bad url" }
    val host = URI(url).host?.lowercase() ?: throw IllegalArgumentException("bad url")
    require(!isPluginPrivateHost(host)) { "bad host" }
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = QQ_PROBE_TIMEOUT_MS
        conn.readTimeout = QQ_PROBE_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", QQ_UA)
        conn.setRequestProperty("Range", "bytes=0-1023")
        val code = conn.responseCode
        if (code != 200 && code != 206) return false
        val finalHost = conn.url?.host?.lowercase() ?: host
        if (isPluginPrivateHost(finalHost)) return false
        val stream = try { conn.inputStream } catch (e: Exception) { conn.errorStream } ?: return false
        val bytes = stream.use { readLimited(it, 64 * 1024) }
        if (bytes.size < 4) return false
        val sig4 = String(bytes.copyOfRange(0, 4), Charsets.ISO_8859_1)
        if (sig4 == "fLaC" || sig4.startsWith("ID3") || sig4 == "OggS") return true
        if (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return true
        if (bytes.size >= 8 && String(bytes.copyOfRange(4, 8), Charsets.ISO_8859_1) == "ftyp") return true
        return false
    } catch (e: Exception) {
        return false
    } finally {
        runCatching { conn.disconnect() }
    }
}

private fun validQqUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val url = raw.trim()
    if (!url.startsWith("http", true)) return null
    if (QQ_PAGE_URL.containsMatchIn(url)) return null
    return url
}

/**
 * QQ 原生直连取流：念心 tx.php 主通道 + s01s 备用通道，均经音频探针确认
 * 失败返回 null（由路由继续降级下一档），不抛错
 */
suspend fun qqDirectMediaUrl(item: MediaItem, payload: JSONObject, quality: String): String? =
    withContext(Dispatchers.IO) {
        val songmid = qqSongMid(payload, item)
        if (songmid.isNullOrBlank()) {
            Log.w("BakaPlugin", "QQ 原生兜底缺少 songmid: ${item.platform}:${item.id}")
            return@withContext null
        }
        // 主通道：念心 tx.php
        runCatching {
            val level = QQ_NIANXIN_LEVEL[quality] ?: "standard"
            val api = "https://mcp.nianxinxz.com/share/ceshi/tx.php?id=${enc(songmid)}&level=${enc(level)}"
            val body = JSONObject(httpGetText(api, QQ_API_TIMEOUT_MS))
            val curl = validQqUrl(body.optString("url").ifBlank { body.optJSONObject("data")?.optString("url") })
            if (curl != null && probeQqAudio(curl)) return@withContext curl
            throw IllegalStateException("念心未返回可用音频")
        }.onFailure {
            Log.w("BakaPlugin", "QQ 原生兜底念心失败($quality): ${it.message}")
        }.getOrNull()?.let { return@withContext it }
        // 备用通道：s01s（遍历该音质全部字段，空字段跳过）
        runCatching {
            val fields = QQ_S01S_FIELDS[quality]
                ?: listOf("song_play_url_standard", "song_play_url", "song_play_url_hq", "song_play_url_fq", "song_play_url_sq")
            val api = "https://tang.api.s01s.cn/music_open_api.php?mid=${enc(songmid)}"
            val body = JSONObject(httpGetText(api, QQ_API_TIMEOUT_MS))
            for (field in fields) {
                val curl = validQqUrl(body.optString(field)) ?: continue
                if (probeQqAudio(curl)) return@withContext curl
            }
            throw IllegalStateException("s01s 未返回请求音质的播放链接")
        }.onFailure {
            Log.w("BakaPlugin", "QQ 原生兜底 s01s 失败($quality): ${it.message}")
        }.getOrNull()?.let { return@withContext it }
        null
    }
