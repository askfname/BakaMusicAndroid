package com.bakamusic.android.plugin

import com.bakamusic.android.data.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

data class PluginManifest(val url: String, val name: String? = null, val version: String? = null, val sha256: String? = null)
data class InstalledPlugin(val id: String, val name: String, val version: String, val sourceUrl: String, val fileName: String, val enabled: Boolean, val order: Int)
data class PluginSubscription(val id: String, val name: String, val url: String, val lastUpdated: Long = 0)

/** 批量安装结果：成功数与因版本冲突跳过的清单名 */
data class BatchInstallResult(val installed: Int, val skipped: List<String> = emptyList()) {
    /** 成功提示后的跳过说明，无跳过时为空 */
    fun skippedNotice(): String =
        if (skipped.isEmpty()) "" else "；已安装更新的 ${skipped.joinToString("、")}，请卸载后重试"
}

/** 同名已安装更新版本时的冲突，批量安装中可跳过 */
class PluginVersionConflictException(
    message: String,
    val pluginName: String = "",
    val installedVersion: String = ""
) : IllegalStateException(message) {
    /** 名单标签 */
    fun label(): String =
        if (pluginName.isNotBlank() && installedVersion.isNotBlank()) "$pluginName（$installedVersion）"
        else pluginName.ifBlank { super.message ?: "版本冲突" }
}

/** 插件本地注册表，JS 执行由沙箱运行时提供。 */
class PluginManager(private val context: Context) {
    val applicationContext: Context get() = context.applicationContext
    private val root get() = File(context.filesDir, "plugins").apply { mkdirs() }
    private val prefs get() = context.getSharedPreferences("plugin_registry", Context.MODE_PRIVATE)

    fun plugins(): List<InstalledPlugin> = readPlugins().filter { it.enabled }.sortedBy { it.order }
    fun enabledPluginNames(): List<String> = plugins().map { it.name }
    fun allPlugins(): List<InstalledPlugin> = readPlugins().sortedBy { it.order }
    fun subscriptions(): List<PluginSubscription> = readSubscriptions()
    fun pluginFile(plugin: InstalledPlugin): File = File(root, plugin.fileName)

    /** 插件注册表指纹：插件增删改后推进，调用方据此自动重载运行时。 */
    fun dataVersion(): Long = prefs.getLong("plugins_generation", 0)

    private fun touchGeneration() {
        val next = maxOf(prefs.getLong("plugins_generation", 0) + 1, System.currentTimeMillis())
        prefs.edit().putLong("plugins_generation", next).apply()
    }

    suspend fun addSubscription(name: String, url: String): Result<PluginSubscription> = runCatching {
        require(name.trim().isNotEmpty()) { "请输入订阅名称" }
        require(isPluginUrl(url)) { "订阅地址必须是 HTTPS 的 .json 或 .js 地址" }
        val item = PluginSubscription(hash(url), name.trim(), url.trim())
        val values = readSubscriptions().filterNot { it.url == item.url } + item
        saveSubscriptions(values)
        item
    }

    suspend fun updateSubscription(subscription: PluginSubscription): Result<BatchInstallResult> = runCatching {
        val result = installFromUrl(subscription.url)
        saveSubscriptions(readSubscriptions().map { if (it.id == subscription.id) it.copy(lastUpdated = System.currentTimeMillis()) else it })
        result
    }

    suspend fun updateAllSubscriptions(): Result<BatchInstallResult> = runCatching {
        var total = 0
        val skipped = mutableListOf<String>()
        readSubscriptions().forEach { subscription ->
            val result = updateSubscription(subscription).getOrThrow()
            total += result.installed
            skipped.addAll(result.skipped)
        }
        BatchInstallResult(total, skipped.distinct())
    }

    suspend fun installNetwork(url: String): Result<BatchInstallResult> = runCatching { installFromUrl(url.trim()) }

    suspend fun installLocal(file: File): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "插件文件不存在" }
            val ext = file.extension.lowercase()
            require(ext == "js" || ext == "json") { "本地插件仅支持 .js / .json 文件" }
            if (ext == "json") {
                // 本地清单：名取清单 name
                val text = file.readText(Charsets.UTF_8)
                require(text.toByteArray().size <= MAX_PLUGIN_BYTES) { "插件超过 5 MiB 限制" }
                val sources = readManifestEntries(text, null)
                require(sources.isNotEmpty()) { "清单中没有可用插件" }
                var last: InstalledPlugin? = null
                var count = 0
                val skipped = mutableListOf<String>()
                // 倒序安装，版本冲突单条跳过
                for (src in sources.asReversed()) {
                    try {
                        val bytes = request(src.url)
                        verifyHash(bytes, src.sha256, src.url)
                        last = register(PluginManifest(src.url, src.name, src.version, src.sha256), bytes)
                        count++
                    } catch (e: PluginVersionConflictException) {
                        skipped.add(e.label())
                    }
                }
                if (count == 0 && skipped.isNotEmpty()) {
                    throw PluginVersionConflictException("已安装更新的 ${skipped.distinct().joinToString("、")}，请卸载后重试")
                }
                last ?: throw IllegalStateException("清单安装失败")
            } else {
                val bytes = file.readBytes()
                require(bytes.size <= MAX_PLUGIN_BYTES) { "插件超过 5 MiB 限制" }
                val text = bytes.toString(Charsets.UTF_8)
                // 单个 js：以文件名命名
                register(PluginManifest("local://${file.name}", file.nameWithoutExtension.ifBlank { file.name }, extractVersion(text) ?: "本地文件"), bytes, placeFirst = true)
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) { savePlugins(readPlugins().map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    fun removeSubscription(id: String) { saveSubscriptions(readSubscriptions().filterNot { it.id == id }) }
    fun remove(id: String) {
        val current = readPlugins().firstOrNull { it.id == id } ?: return
        File(root, current.fileName).delete()
        savePlugins(readPlugins().filterNot { it.id == id }.reorder())
    }
    fun move(id: String, delta: Int) {
        // 已安装列表整体按 order 相邻交换，启用/禁用统一处理；
        // 实际播放优先级取启用子集的相对顺序，与展示顺序一致
        val full = readPlugins().sortedBy { it.order }.toMutableList()
        val from = full.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, full.lastIndex)
        if (from == to) return
        val item = full.removeAt(from)
        full.add(to, item)
        persistPlugins(full.mapIndexed { index, p -> p.copy(order = index) })
    }

    private suspend fun installFromUrl(url: String): BatchInstallResult = withContext(Dispatchers.IO) {
        val clean = url.trim()
        require(isPluginUrl(clean)) { "插件地址必须是 HTTPS 的 .json 或 .js 地址" }
        val bytes = request(clean)
        val text = bytes.toString(Charsets.UTF_8).trimStart()
        if (text.startsWith("{")) {
            val base = runCatching { URI(clean).toString() }.getOrDefault(clean)
            val sources = readManifestEntries(text, base)
            // 倒序安装；名取清单 name（缺失拒绝），版本冲突单条跳过
            var count = 0
            val skipped = mutableListOf<String>()
            for (src in sources.asReversed()) {
                val entryName = src.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("不支持的链接：清单条目缺少插件名称")
                try {
                    val pluginBytes = request(src.url)
                    verifyHash(pluginBytes, src.sha256, src.url)
                    register(PluginManifest(src.url, entryName, src.version, src.sha256), pluginBytes)
                    count++
                } catch (e: PluginVersionConflictException) {
                    skipped.add(e.label())
                }
            }
            if (count == 0 && skipped.isNotEmpty()) {
                throw PluginVersionConflictException("已安装更新的 ${skipped.distinct().joinToString("、")}，请卸载后重试")
            }
            require(count > 0) { "清单中没有可用插件" }
            BatchInstallResult(count, skipped.distinct())
        } else {
            verifyHash(bytes, null, clean)
            val jsText = bytes.toString(Charsets.UTF_8)
            // 单个 js：以 URL 文件名命名
            val fileName = runCatching { URI(clean).path.substringAfterLast('/').substringBeforeLast('.') }.getOrDefault("").ifBlank { "网络插件" }
            // 单个安装默认排序置于首位
            register(PluginManifest(clean, fileName, extractVersion(jsText)), bytes, placeFirst = true)
            BatchInstallResult(1)
        }
    }

    private data class RemoteSource(val url: String, val sha256: String?, val name: String? = null, val version: String? = null)

    private fun readManifestEntries(raw: String, baseUrl: String?): List<RemoteSource> {
        val root = JSONObject(raw)
        val entries = root.optJSONArray("plugins") ?: JSONArray()
        val out = mutableListOf<RemoteSource>()
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            var pluginUrl = item.optString("url").trim().takeIf { it.isNotBlank() } ?: continue
            if (baseUrl != null) {
                pluginUrl = runCatching { URI(baseUrl).resolve(pluginUrl).toString() }.getOrDefault(pluginUrl)
            }
            // 清单条目同样要求使用 HTTPS
            if (!pluginUrl.startsWith("https://", true)) continue
            val sha = parseExpectedSha256(item.optString("sha256").takeIf { it.isNotBlank() }
                ?: item.optString("integrity").takeIf { it.isNotBlank() })
            val entryName = item.optString("name").trim().takeIf { it.isNotEmpty() }
            val entryVersion = item.optString("version").trim().takeIf { it.isNotEmpty() }
            out.add(RemoteSource(pluginUrl, sha, entryName, entryVersion))
            if (out.size >= 100) break
        }
        return out
    }

    private fun verifyHash(bytes: ByteArray, expected: String?, urlForMsg: String) {
        if (expected.isNullOrBlank()) return
        val actual = hashBytes(bytes).lowercase()
        require(actual.equals(expected.lowercase(), true)) { "插件校验失败：$urlForMsg" }
    }

    private fun register(manifest: PluginManifest, bytes: ByteArray, placeFirst: Boolean = false): InstalledPlugin {
        require(bytes.size <= MAX_PLUGIN_BYTES) { "插件超过 5 MiB 限制" }
        // 安装名即展示名，缺失直接拒绝
        val displayName = manifest.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("不支持的链接")
        val extractedVersion = extractVersion(runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault(""))
        val version = (manifest.version?.ifBlank { null } ?: extractedVersion ?: "未知版本")
        val id = hash(manifest.url)
        val current = readPlugins()
        val oldSameUrl = current.firstOrNull { it.id == id }
        // 同名去重：新版本替换旧版本
        val oldSamePlatform = current.firstOrNull { it.name == displayName && it.id != id }
        if (oldSamePlatform != null) {
            // 未知版本跳过新旧比较
            val comparable = oldSamePlatform.version != "未知版本" && version != "未知版本"
            if (comparable && compareVersion(oldSamePlatform.version, version) > 0) {
                throw PluginVersionConflictException("已安装更新的 ${displayName}（${oldSamePlatform.version}），请卸载后重试", displayName, oldSamePlatform.version)
            }
            File(root, oldSamePlatform.fileName).delete()
        }
        val plugin = InstalledPlugin(
            id, displayName, version, manifest.url, "$id.js",
            oldSameUrl?.enabled ?: oldSamePlatform?.enabled ?: true,
            // 存量更新保持原位；单个新装排序置于首位，批量新装追加
            oldSameUrl?.order ?: oldSamePlatform?.order
                ?: if (placeFirst) (current.minOfOrNull { it.order } ?: 0) - 1 else current.size
        )
        File(root, plugin.fileName).writeBytes(bytes)
        val next = (current.filterNot { it.id == id || (oldSamePlatform != null && it.id == oldSamePlatform.id) } + plugin).reorder()
        savePlugins(next)
        return plugin
    }

    private fun request(url: String): ByteArray {
        // 追加缓存规避参数，防止 CDN 或代理返回旧清单或旧插件
        val wireUrl = appendCacheBuster(url)
        val connection = URI(wireUrl).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) BakaMusic/1.0")
        connection.setRequestProperty("Accept", "*/*")
        return try {
            val code = connection.responseCode
            // 仅接受 HTTPS 重定向
            val finalUrl = connection.url?.toString() ?: wireUrl
            require(finalUrl.startsWith("https://", true)) { "插件重定向必须保持 HTTPS" }
            require(code in 200..299) { "插件请求失败：$code" }
            require(connection.contentLengthLong <= MAX_RESPONSE_BYTES || connection.contentLengthLong < 0) { "响应超过 16 MiB 限制" }
            val encoding = (connection.contentEncoding ?: "").lowercase()
            val stream = try { connection.inputStream } catch (e: Exception) { connection.errorStream }
                ?: throw IllegalStateException("插件请求失败：$code")
            val wrapped = when {
                encoding.contains("gzip") -> java.util.zip.GZIPInputStream(stream)
                encoding.contains("deflate") -> java.util.zip.InflaterInputStream(stream)
                else -> stream
            }
            wrapped.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0L
                while (true) { val size = input.read(buffer); if (size < 0) break; total += size; require(total <= MAX_RESPONSE_BYTES) { "响应超过 16 MiB 限制" }; output.write(buffer, 0, size) }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    /** 缓存规避参数仅作用于传输 URL，注册 id 仍使用原始 URL */
    private fun appendCacheBuster(url: String): String {
        if (!url.startsWith("http", true)) return url
        return runCatching {
            val sep = if (URI(url).rawQuery.isNullOrEmpty()) "?" else "&"
            "$url${sep}_bakamusic_cache=${System.currentTimeMillis()}"
        }.getOrDefault(url)
    }

    private fun readPlugins(): List<InstalledPlugin> = decode(prefs.getString("plugins", null), ::pluginFromJson)
    private fun savePlugins(values: List<InstalledPlugin>) { persistPlugins(values.reorder()) }
    private fun persistPlugins(values: List<InstalledPlugin>) {
        prefs.edit().putString("plugins", JSONArray(values.map(::pluginJson)).toString()).apply()
        touchGeneration()
    }
    private fun readSubscriptions(): List<PluginSubscription> = decode(prefs.getString("subscriptions", null), ::subscriptionFromJson)
    private fun saveSubscriptions(values: List<PluginSubscription>) { prefs.edit().putString("subscriptions", JSONArray(values.map(::subscriptionJson)).toString()).apply() }
    private fun <T> decode(raw: String?, parser: (JSONObject) -> T): List<T> = runCatching { val array = JSONArray(raw ?: "[]"); List(array.length()) { parser(array.getJSONObject(it)) } }.getOrDefault(emptyList())
    private fun pluginJson(item: InstalledPlugin) = JSONObject().apply { put("id", item.id); put("name", item.name); put("version", item.version); put("sourceUrl", item.sourceUrl); put("fileName", item.fileName); put("enabled", item.enabled); put("order", item.order) }
    private fun pluginFromJson(item: JSONObject) = InstalledPlugin(item.getString("id"), item.optString("name", "未命名插件").ifBlank { "未命名插件" }, item.optString("version", "未知版本"), item.optString("sourceUrl"), item.getString("fileName"), item.optBoolean("enabled", true), item.optInt("order"))
    private fun subscriptionJson(item: PluginSubscription) = JSONObject().apply { put("id", item.id); put("name", item.name); put("url", item.url); put("lastUpdated", item.lastUpdated) }
    private fun subscriptionFromJson(item: JSONObject) = PluginSubscription(item.getString("id"), item.getString("name"), item.getString("url"), item.optLong("lastUpdated"))
    private fun List<InstalledPlugin>.reorder() = sortedWith(compareBy<InstalledPlugin> { it.order }.thenBy { it.id }).mapIndexed { index, item -> item.copy(order = index) }
    private fun isPluginUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val path = (uri.path ?: "").lowercase()
        return uri.scheme.equals("https", true) && (path.endsWith(".json") || path.endsWith(".js"))
    }
    /** 解析期望的 SHA-256：64 位 hex 或 sha256-base64 */
    private fun parseExpectedSha256(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        if (Regex("^[a-fA-F0-9]{64}$").matches(v)) return v.lowercase()
        val m = Regex("^sha256-([A-Za-z0-9+/=]+)$").find(v) ?: throw IllegalArgumentException("插件 SHA-256 非法")
        val digest = android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT)
        require(digest.size == 32) { "插件 SHA-256 非法" }
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object {
        private const val MAX_PLUGIN_BYTES = 5 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
    }

    private fun extractVersion(js: String): String? {
        // 键可带引号，排除 version_code / appVersion 等复合键
        val values = Regex("""[^A-Za-z0-9_$]["']?version["']?\s*:\s*["']([^"']{1,32})["']""")
            .findAll(js).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        // 版本号以数字开头，导出定义在末尾故取最后一个
        values.lastOrNull {
            it.first().isDigit() || ((it.first() == 'v' || it.first() == 'V') && it.length > 1 && it[1].isDigit())
        }?.let { return it }
        return values.lastOrNull()
    }
    /** 简单版本比较：大于 0 表示旧版本更新，小于 0 表示新版本更新，等于 0 表示版本相同 */
    private fun compareVersion(oldV: String, newV: String): Int {
        fun parts(v: String) = v.trim().trimStart('v', 'V').split('.', '-', '_').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(oldV); val b = parts(newV)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
    private fun hashBytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(value: String) = hashBytes(value.toByteArray()).take(24)
}
