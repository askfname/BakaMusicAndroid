package com.bakamusic.android.service

import com.bakamusic.android.data.*
import com.bakamusic.android.util.*
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
}

private class HeaderInjectingSource(private val inner: HttpDataSource) : HttpDataSource by inner {
    override fun open(dataSpec: DataSpec): Long {
        BakaMediaHeaders.get(dataSpec.uri.toString()).forEach { (key, value) ->
            runCatching { inner.setRequestProperty(key, value) }
        }
        return inner.open(dataSpec)
    }
}

/** Media3 播放服务：播放插件解析出的媒体地址。 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        // 允许跨协议重定向并设置浏览器用户代理，插件携带的请求头由 HeaderInjectingSource 按地址注入
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val injectingFactory = androidx.media3.datasource.DataSource.Factory { HeaderInjectingSource(httpFactory.createDataSource()) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(injectingFactory))
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
