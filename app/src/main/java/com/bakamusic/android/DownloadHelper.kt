package com.bakamusic.android

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

object DownloadHelper {
    fun enqueue(context: Context, item: MediaItem, quality: MusicQuality): Long {
        return enqueueByKey(context, item, quality.key)
    }

    /** 按音质键下载 */
    fun enqueueByKey(context: Context, item: MediaItem, qualityKey: String): Long {
        val url = requireNotNull(item.mediaUrl) { "当前歌曲没有可用的下载地址" }
        require(url.startsWith("https://", true) || url.startsWith("http://", true)) { "下载地址必须为 HTTP(S)" }
        val label = MusicQuality.labelOf(qualityKey)
        val ext = if (qualityKey.lowercase() in setOf("flac", "flac24bit", "hires", "vinyl", "master", "mgg")) "flac" else "mp3"
        val safeTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "未知歌曲" }
        val safeArtist = item.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "未知歌手" }
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(item.title)
            setDescription("$safeArtist · $label")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "BakaMusic/$safeTitle - $safeArtist.$ext")
            item.mediaHeaders.forEach { (key, value) -> runCatching { addRequestHeader(key, value) } }
        }
        return requireNotNull(context.getSystemService(DownloadManager::class.java)).enqueue(request)
    }
}
