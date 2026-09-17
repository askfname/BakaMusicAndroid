package com.bakamusic.android.service

import com.bakamusic.android.data.*
import com.bakamusic.android.util.*
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** 按地址登记播放请求头，播放器使用的 DataSource 在打开连接时注入。 */
object BakaMediaHeaders {
    private val headers = ConcurrentHashMap<String, Map<String, String>>()

    fun put(url: String, value: Map<String, String>) {
        if (value.isEmpty()) headers.remove(url)
        else {
            headers[url] = value
            if (headers.size > 64) headers.keys.toList().take(headers.size - 64).forEach(headers::remove)
        }
    }

    fun get(url: String): Map<String, String> = headers[url] ?: emptyMap()

    /**
     * 播放器可能规范化 URI（如重定向/百分号编码差异）导致精确匹配失败，
     * 此时按去 query/fragment 的基地址回退查找。bilibili 等直链强依赖
     * Referer，查不到头会直接 403。
     */
    fun getForRequest(url: String): Map<String, String> {
        headers[url]?.takeIf { it.isNotEmpty() }?.let { return it }
        val base = url.substringBefore("?").substringBefore("#")
        if (base != url) {
            headers.entries.firstOrNull {
                it.key.substringBefore("?").substringBefore("#") == base
            }?.value?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyMap()
    }
}

/**
 * 自有播放数据源：按插件返回的请求头原样发送（UA/Referer/Cookie/Accept-Encoding）。
 * 背景：反编译确认 DefaultHttpDataSource.openConnection 会先应用自定义头，
 * 随后用工厂默认 UA 覆盖插件 UA、并按 allowGzip 覆盖 Accept-Encoding，
 * 导致 bilibili 等带签名的直链（与请求时的 UA 绑定）新鲜解析也 403，而桌面端
 * libmpv 原样发送插件头可以播放。此处同时手动跟随重定向并每次重发全部头。
 */
@UnstableApi
private class BakaPluginDataSource : BaseDataSource(true), HttpDataSource {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        /** 未知长度哨兵（C.LENGTH_UNSET 为 Int，此处用 Long 版避免 Kotlin 禁止 Long/Int 混用比较） */
        private const val LEN_UNSET = -1L
        /** 无插件 UA 时的回退，与之前工厂默认值一致，避免发送 ExoPlayerLib 被风控 */
        private const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /** headerFields 含 null 键（状态行）且可变，拷贝为不可变快照 */
        private fun headerSnapshot(conn: HttpURLConnection): Map<String, List<String>> {
            val out = LinkedHashMap<String, List<String>>()
            conn.headerFields?.forEach { (k, v) ->
                if (k != null && v != null) out[k] = v.toList()
            }
            return out
        }
    }

    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var currentSpec: DataSpec? = null
    private var finalUri: Uri = Uri.EMPTY
    private var bytesRemaining: Long = 0
    private var responseCode: Int = -1
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var transferBegun = false
    private val extraProps = mutableMapOf<String, String>()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentSpec = dataSpec
        val pluginHeaders = BakaMediaHeaders.getForRequest(dataSpec.uri.toString())
        fun hasHeader(name: String) =
            pluginHeaders.keys.any { it.equals(name, true) } || extraProps.keys.any { it.equals(name, true) }

        var url = try {
            URL(dataSpec.uri.toString())
        } catch (e: Exception) {
            throw HttpDataSource.HttpDataSourceException("无效播放地址", dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        var redirects = 0
        while (true) {
            val conn = try {
                (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    requestMethod = DataSpec.getStringForHttpMethod(dataSpec.httpMethod)
                    // 默认值先写，插件头覆盖：UA/Accept-Encoding 必须与解析请求时一致
                    if (!hasHeader("User-Agent")) setRequestProperty("User-Agent", FALLBACK_UA)
                    if (!hasHeader("Accept-Encoding")) setRequestProperty("Accept-Encoding", "identity")
                    extraProps.forEach { (k, v) -> setRequestProperty(k, v) }
                    pluginHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    val end = if (dataSpec.length != LEN_UNSET) dataSpec.position + dataSpec.length - 1 else null
                    if (dataSpec.position > 0 || end != null) {
                        setRequestProperty("Range", "bytes=${dataSpec.position}-${end ?: ""}")
                    }
                }
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            try {
                conn.connect()
            } catch (e: IOException) {
                conn.disconnect()
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            responseCode = code
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || ++redirects > MAX_REDIRECTS) {
                    throw HttpDataSource.InvalidResponseCodeException(
                        code, null, null, emptyMap(), dataSpec, ByteArray(0)
                    )
                }
                url = try {
                    URL(url, location)
                } catch (e: Exception) {
                    throw HttpDataSource.InvalidResponseCodeException(
                        code, null, null, emptyMap(), dataSpec, ByteArray(0)
                    )
                }
                continue
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                val fields = runCatching { headerSnapshot(conn) }.getOrDefault(emptyMap())
                conn.disconnect()
                throw HttpDataSource.InvalidResponseCodeException(
                    code, runCatching { conn.responseMessage }.getOrNull(), null, fields, dataSpec, ByteArray(0)
                )
            }
            if (code == HttpURLConnection.HTTP_OK && dataSpec.position != 0L) {
                conn.disconnect()
                throw HttpDataSource.HttpDataSourceException(
                    "服务器不支持断点续传", dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: LEN_UNSET
            bytesRemaining = if (dataSpec.length != LEN_UNSET) dataSpec.length else contentLength
            responseHeaders = runCatching { headerSnapshot(conn) }.getOrDefault(emptyMap())
            finalUri = Uri.parse(conn.url?.toString() ?: dataSpec.uri.toString())
            val rawInput = try {
                conn.inputStream
            } catch (e: IOException) {
                conn.disconnect()
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            // 仅当插件明确要求 gzip 才解压（我方默认 identity，不会走到这里）
            val encoding = conn.getHeaderField("Content-Encoding").orEmpty()
            input = if (encoding.contains("gzip", true)) java.util.zip.GZIPInputStream(rawInput) else rawInput
            connection = conn
            break
        }
        transferBegun = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val spec = currentSpec
            ?: throw HttpDataSource.HttpDataSourceException(
                DataSpec(Uri.EMPTY), HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        val capped = if (bytesRemaining == LEN_UNSET) length
        else minOf(bytesRemaining, length.toLong()).toInt()
        val count = try {
            input?.read(buffer, offset, capped) ?: C.RESULT_END_OF_INPUT
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e, spec, HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
        if (count == -1) {
            if (bytesRemaining != LEN_UNSET && bytesRemaining != 0L) {
                throw HttpDataSource.HttpDataSourceException(
                    "播放流提前结束", spec, HttpDataSource.HttpDataSourceException.TYPE_READ
                )
            }
            return C.RESULT_END_OF_INPUT
        }
        bytesTransferred(count)
        if (bytesRemaining != LEN_UNSET) bytesRemaining -= count
        return count
    }

    override fun getUri(): Uri = finalUri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun getResponseCode(): Int = responseCode

    override fun setRequestProperty(name: String, value: String) {
        extraProps[name] = value
    }

    override fun clearRequestProperty(name: String) {
        extraProps.keys.filter { it.equals(name, true) }.forEach(extraProps::remove)
    }

    override fun clearAllRequestProperties() {
        extraProps.clear()
    }

    override fun close() {
        if (transferBegun) {
            transferBegun = false
            runCatching { transferEnded() }
        }
        runCatching { input?.close() }
        runCatching { connection?.disconnect() }
        input = null
        connection = null
        currentSpec = null
        bytesRemaining = 0
        responseCode = -1
        responseHeaders = emptyMap()
        finalUri = Uri.EMPTY
    }
}

/** Media3 播放服务：播放插件解析出的媒体地址。 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // 自有数据源按插件请求头原样发送（含 UA/Referer/Cookie），缺省 UA 用浏览器 UA；
        // 跨协议重定向由 BakaPluginDataSource 手动跟随并重发全部头
        val pluginFactory = androidx.media3.datasource.DataSource.Factory { BakaPluginDataSource() }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(pluginFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), true
            ).setHandleAudioBecomingNoisy(true).build()
        this.player = player
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        // 先释放会话再释放播放器
        mediaSession?.release()
        mediaSession = null
        player?.runCatching {
            stop()
            clearMediaItems()
            release()
        }
        player = null
        super.onDestroy()
    }
}
