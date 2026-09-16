package com.bakamusic.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.appDataStore by preferencesDataStore("bakamusic")

/** 歌曲快照：收藏/歌单展示与回放所需信息，key 规则为 "${platform}:${id}"。 */
data class SongRecord(
    val key: String,
    val id: String,
    val platform: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artwork: String? = null,
    val durationMs: Long = 0,
    val qualityKey: String = "320k",
    val size: Long? = null,
    val qualitiesJson: String = "{}",
    val rawJson: String? = null
) {
    fun toMediaItem(): MediaItem {
        val qualities = runCatching {
            val o = org.json.JSONObject(qualitiesJson)
            val map = mutableMapOf<String, QualityInfo>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val q = o.optJSONObject(k)
                if (q != null) map[k] = QualityInfo(
                    size = q.optLong("size").takeIf { q.has("size") && it > 0 },
                    bitrate = q.optLong("bitrate").takeIf { q.has("bitrate") && it > 0 },
                    url = q.optString("url").takeIf { it.startsWith("http", true) }
                )
            }
            map
        }.getOrDefault(emptyMap())
        return MediaItem(id, platform, title, artist, album, artwork, durationMs, null, emptyMap(), null, qualities, rawJson)
    }

    companion object {
        fun recordKey(platform: String, id: String) = "$platform:$id"

        fun from(item: MediaItem, qualityKey: String? = null): SongRecord {
            val best = qualityKey ?: item.qualities.keys.maxByOrNull { qualityRankOf(it) } ?: "320k"
            val qualitiesJson = org.json.JSONObject().apply {
                item.qualities.forEach { (k, v) ->
                    put(k, org.json.JSONObject().apply {
                        v.size?.let { put("size", it) }
                        v.bitrate?.let { put("bitrate", it) }
                        v.url?.let { put("url", it) }
                    })
                }
            }.toString()
            return SongRecord(
                key = recordKey(item.platform, item.id),
                id = item.id, platform = item.platform, title = item.title, artist = item.artist,
                album = item.album, artwork = item.artwork, durationMs = item.durationMs,
                qualityKey = best, size = item.qualities[best]?.size,
                qualitiesJson = qualitiesJson, rawJson = item.rawJson
            )
        }

        fun fromJson(raw: String?): SongRecord? = runCatching {
            require(!raw.isNullOrBlank())
            val o = org.json.JSONObject(raw)
            SongRecord(
                key = o.getString("key"), id = o.optString("id"), platform = o.optString("platform"),
                title = o.optString("title", "未知歌曲"), artist = o.optString("artist", "未知歌手"),
                album = o.optString("album"), artwork = o.optString("artwork").takeIf { it.isNotBlank() },
                durationMs = o.optLong("durationMs"), qualityKey = o.optString("qualityKey", "320k").ifBlank { "320k" },
                size = o.optLong("size").takeIf { o.has("size") && it > 0 },
                qualitiesJson = o.optString("qualitiesJson", "{}"),
                rawJson = o.optString("rawJson").takeIf { it.isNotBlank() }
            )
        }.getOrNull()

        fun toJson(r: SongRecord): String = org.json.JSONObject().apply {
            put("key", r.key); put("id", r.id); put("platform", r.platform)
            put("title", r.title); put("artist", r.artist); put("album", r.album)
            put("artwork", r.artwork ?: ""); put("durationMs", r.durationMs)
            put("qualityKey", r.qualityKey)
            r.size?.let { put("size", it) }
            put("qualitiesJson", r.qualitiesJson); put("rawJson", r.rawJson ?: "")
        }.toString()
    }
}

/** 本地数据存储：搜索历史、收藏、歌单与默认音质。 */
class AppRepository(private val context: Context) {
    companion object {
        /** 收藏歌单名称，不可修改或删除 */
        const val FAVORITE_SHEET = "我喜欢的音乐"
        /** 无自建歌单时的后备选项 */
        const val DEFAULT_SHEET = "默认歌单"
    }

    private val historyKey = stringPreferencesKey("search_history")
    private val favoriteKey = stringPreferencesKey("favorite_song_ids")
    private val playQualityKey = stringPreferencesKey("default_play_quality")
    private val downloadQualityKey = stringPreferencesKey("default_download_quality")
    private val recordKey = stringPreferencesKey("song_records")
    private val playlistNamesKey = stringPreferencesKey("playlist_names")
    val searchHistory: Flow<List<String>> = context.appDataStore.data.map { decode(it[historyKey]) }
    val favorites: Flow<Set<String>> = context.appDataStore.data.map { decode(it[favoriteKey]).toSet() }
    /** 默认播放/下载音质 */
    val playQuality: Flow<String> = context.appDataStore.data.map { it[playQualityKey] ?: "flac" }
    val downloadQuality: Flow<String> = context.appDataStore.data.map { it[downloadQualityKey] ?: "flac" }

    private fun recordsOf(prefs: androidx.datastore.preferences.core.Preferences): MutableMap<String, String> =
        decode(prefs[recordKey]).associate { s ->
            val o = runCatching { org.json.JSONObject(s) }.getOrNull()
            val k = o?.optString("key").orEmpty()
            k to s
        }.filterKeys { it.isNotEmpty() }.toMutableMap()

    private fun saveRecords(editor: androidx.datastore.preferences.core.MutablePreferences, records: Map<String, String>) {
        editor[recordKey] = encode(records.values.toList())
    }

    /** 收藏歌曲快照列表，无快照的旧收藏 ID 会被跳过 */
    val favoriteSongs: Flow<List<SongRecord>> = context.appDataStore.data.map { prefs ->
        val keys = decode(prefs[favoriteKey]).toSet()
        val records = recordsOf(prefs)
        keys.mapNotNull { records[it]?.let(SongRecord::fromJson) }
    }

    /** 全部歌单名列表，始终包含“我喜欢的音乐” */
    val playlistNames: Flow<List<String>> = context.appDataStore.data.map { prefs ->
        (listOf(FAVORITE_SHEET) + decode(prefs[playlistNamesKey])).distinct()
    }

    /** 用户自建歌单名列表，不含“我喜欢的音乐” */
    val userPlaylistNames: Flow<List<String>> = context.appDataStore.data.map { prefs ->
        decode(prefs[playlistNamesKey]).distinct()
    }

    /** 指定歌单的歌曲快照列表 */
    fun playlistSongs(name: String): Flow<List<SongRecord>> =
        if (name == FAVORITE_SHEET) favoriteSongs
        else context.appDataStore.data.map { prefs ->
            val keys = decode(prefs[stringPreferencesKey("playlist_${name}")])
            val records = recordsOf(prefs)
            keys.mapNotNull { records[it]?.let(SongRecord::fromJson) }
        }

    suspend fun addSearch(query: String) { if (query.isBlank()) return; context.appDataStore.edit { it[historyKey] = encode((decode(it[historyKey]) - query).toMutableList().apply { add(0, query) }.take(20)) } }

    /**
     * 收藏切换：收藏时写入快照，取消时仅移除键（快照保留供歌单使用）。
     * 返回切换后是否为已收藏。
     */
    suspend fun toggleFavorite(item: MediaItem): Boolean {
        val record = SongRecord.from(item)
        var result = false
        context.appDataStore.edit { prefs ->
            val values = decode(prefs[favoriteKey]).toMutableSet()
            result = if (values.contains(record.key)) {
                values.remove(record.key); false
            } else {
                values.add(record.key); true
            }
            prefs[favoriteKey] = encode(values.toList())
            val records = recordsOf(prefs)
            records[record.key] = SongRecord.toJson(record)
            saveRecords(prefs, records)
        }
        return result
    }

    suspend fun isFavorite(platform: String, id: String): Boolean =
        favoritesSnapshot().contains(SongRecord.recordKey(platform, id))

    private suspend fun favoritesSnapshot(): Set<String> =
        decode(context.appDataStore.data.first()[favoriteKey]).toSet()

    suspend fun addToPlaylist(playlist: String, item: MediaItem) {
        val record = SongRecord.from(item)
        context.appDataStore.edit { prefs ->
            if (playlist == FAVORITE_SHEET) {
                val values = (decode(prefs[favoriteKey]) + record.key).distinct().toMutableSet()
                prefs[favoriteKey] = encode(values.toList())
            } else {
                val key = stringPreferencesKey("playlist_${playlist}")
                prefs[key] = encode((decode(prefs[key]) + record.key).distinct())
                prefs[playlistNamesKey] = encode((decode(prefs[playlistNamesKey]) + playlist).distinct())
            }
            val records = recordsOf(prefs)
            records[record.key] = SongRecord.toJson(record)
            saveRecords(prefs, records)
        }
    }

    suspend fun removeFromPlaylist(playlist: String, key: String) {
        context.appDataStore.edit { prefs ->
            if (playlist == FAVORITE_SHEET) {
                prefs[favoriteKey] = encode((decode(prefs[favoriteKey]) - key).toList())
            } else {
                val k = stringPreferencesKey("playlist_${playlist}")
                prefs[k] = encode((decode(prefs[k]) - key).toList())
            }
        }
    }

    suspend fun createPlaylist(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || clean == FAVORITE_SHEET) return false
        var created = false
        context.appDataStore.edit { prefs ->
            val names = decode(prefs[playlistNamesKey]).toMutableList()
            if (!names.contains(clean)) { names.add(clean); created = true }
            prefs[playlistNamesKey] = encode(names)
        }
        return created
    }

    suspend fun deletePlaylist(name: String): Boolean {
        if (name == FAVORITE_SHEET) return false
        context.appDataStore.edit { prefs ->
            prefs[playlistNamesKey] = encode(decode(prefs[playlistNamesKey]) - name)
            prefs.remove(stringPreferencesKey("playlist_${name}"))
        }
        return true
    }

    suspend fun setPlayQuality(key: String) { context.appDataStore.edit { it[playQualityKey] = key } }
    suspend fun setDownloadQuality(key: String) { context.appDataStore.edit { it[downloadQualityKey] = key } }

    private fun encode(values: List<String>) = JSONArray(values).toString()
    private fun decode(raw: String?): List<String> = if (raw.isNullOrBlank()) emptyList() else runCatching { JSONArray(raw).let { array -> List(array.length()) { index -> array.getString(index) } } }.getOrDefault(emptyList())
}

/** 播放会话快照：持久化当前歌曲与播放列表，Activity 重建后恢复展示 */
data class SavedPlayerSession(
    val queue: List<MediaItem> = emptyList(),
    val queueIndex: Int = 0,
    val current: MediaItem? = null,
    val qualityKey: String = "320k",
    val size: Long? = null,
    val positionMs: Long = 0
)

/**
 * 播放会话持久化：进程被回收或 Activity 重建导致内存状态丢失时，以此恢复播放信息与播放列表；
 * 播放/暂停/进度仍以播放服务的实时快照为准。通过 SharedPreferences 读写，以便启动时同步恢复。
 */
class PlayerSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("player_session", Context.MODE_PRIVATE)

    /** 队列或当前歌曲变化时全量保存，调用方应对进度写入进行节流 */
    fun save(queue: List<MediaItem>, queueIndex: Int, current: MediaItem?, qualityKey: String?, size: Long?, positionMs: Long) {
        runCatching {
            // 队列过长时仅保留当前曲目附近共 200 首，避免持久化数据过大
            val from = if (queue.size > MAX_QUEUE) {
                (queueIndex.coerceIn(queue.indices) - MAX_QUEUE / 2).coerceAtLeast(0)
            } else 0
            val trimmed = queue.drop(from).take(MAX_QUEUE)
            prefs.edit()
                .putString("queue", JSONArray(trimmed.map { SongRecord.toJson(SongRecord.from(it)) }).toString())
                .putInt("queueIndex", (queueIndex - from).coerceIn(0, (trimmed.size - 1).coerceAtLeast(0)))
                .putString("current", current?.let { SongRecord.toJson(SongRecord.from(it)) })
                .putString("qualityKey", qualityKey?.ifBlank { null } ?: "320k")
                .apply {
                    if (size != null && size > 0) putLong("size", size) else remove("size")
                }
                .putLong("positionMs", positionMs.coerceAtLeast(0))
                .apply()
        }
    }

    /** 进度单独轻量保存，调用方应节流调用（如每 5 秒一次） */
    fun savePosition(positionMs: Long) {
        runCatching { prefs.edit().putLong("positionMs", positionMs.coerceAtLeast(0)).apply() }
    }

    fun load(): SavedPlayerSession? = runCatching {
        if (!prefs.contains("queue") && !prefs.contains("current")) return null
        val queue = prefs.getString("queue", null)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                List(array.length()) { array.getString(it) }
                    .mapNotNull(SongRecord::fromJson)
                    .map { it.toMediaItem() }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val index = prefs.getInt("queueIndex", 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val current = prefs.getString("current", null)?.let(SongRecord::fromJson)?.toMediaItem()
        if (queue.isEmpty() && current == null) return null
        SavedPlayerSession(
            queue = queue,
            queueIndex = index,
            current = current,
            qualityKey = prefs.getString("qualityKey", "320k")?.ifBlank { "320k" } ?: "320k",
            size = prefs.getLong("size", -1).takeIf { prefs.contains("size") && it > 0 },
            positionMs = prefs.getLong("positionMs", 0).coerceAtLeast(0)
        )
    }.getOrNull()

    companion object {
        private const val MAX_QUEUE = 200
    }
}
