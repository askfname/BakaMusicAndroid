package com.bakamusic.android

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

    suspend fun updateSubscription(subscription: PluginSubscription): Result<Int> = runCatching {
        val count = installFromUrl(subscription.url)
        saveSubscriptions(readSubscriptions().map { if (it.id == subscription.id) it.copy(lastUpdated = System.currentTimeMillis()) else it })
        count
    }

    suspend fun updateAllSubscriptions(): Result<Int> = runCatching {
        var total = 0
        readSubscriptions().forEach { subscription ->
            total += updateSubscription(subscription).getOrThrow()
        }
        total
    }

    suspend fun installNetwork(url: String): Result<Int> = runCatching { installFromUrl(url.trim()) }

    suspend fun installLocal(file: File): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "插件文件不存在" }
            val ext = file.extension.lowercase()
            require(ext == "js" || ext == "json") { "本地插件仅支持 .js / .json 文件" }
            if (ext == "json") {
                // 本地清单：逐条远程安装
                val text = file.readText(Charsets.UTF_8)
                require(text.toByteArray().size <= MAX_PLUGIN_BYTES) { "插件超过 5 MiB 限制" }
                val sources = readManifestEntries(text, null)
                require(sources.isNotEmpty()) { "清单中没有可用插件" }
                var last: InstalledPlugin? = null
                for (src in sources) {
                    val bytes = request(src.url)
                    verifyHash(bytes, src.sha256, src.url)
                    last = register(PluginManifest(src.url, null, null, src.sha256), bytes)
                }
                last ?: throw IllegalStateException("清单安装失败")
            } else {
                val bytes = file.readBytes()
                require(bytes.size <= MAX_PLUGIN_BYTES) { "插件超过 5 MiB 限制" }
                val text = bytes.toString(Charsets.UTF_8)
                val platform = extractPlatform(text) ?: file.nameWithoutExtension
                register(PluginManifest("local://${file.name}", platform, extractVersion(text) ?: "本地文件"), bytes)
            }
        }
    }

    /**
     * 以 JS 运行时真实 platform 校正注册表名称。
     * 返回更新后的条目，无需修正或存在同名冲突时返回 null，需由调用方主动调用。
     */
    fun healPluginName(id: String, runtimePlatform: String): InstalledPlugin? {
        val want = normalizeName(runtimePlatform.trim()).takeIf { it.isNotEmpty() } ?: return null
        val current = readPlugins()
        val target = current.firstOrNull { it.id == id } ?: return null
        if (target.name == want) return null
        if (current.any { it.id != id && it.name == want }) return null
        val updated = target.copy(name = want)
        savePlugins(current.map { if (it.id == id) updated else it })
        return updated
    }

    fun setEnabled(id: String, enabled: Boolean) { savePlugins(readPlugins().map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    fun removeSubscription(id: String) { saveSubscriptions(readSubscriptions().filterNot { it.id == id }) }
    fun remove(id: String) {
        val current = readPlugins().firstOrNull { it.id == id } ?: return
        File(root, current.fileName).delete()
        savePlugins(readPlugins().filterNot { it.id == id }.reorder())
    }
    fun move(id: String, delta: Int) {
        val all = readPlugins().sortedBy { it.order }
        val active = all.filter { it.enabled }
        val fromActive = active.indexOfFirst { it.id == id }
        if (fromActive >= 0) {
            // 音源优先级仅针对启用插件
            val toActive = (fromActive + delta).coerceIn(0, active.lastIndex)
            if (fromActive == toActive) return
            val newActive = active.toMutableList()
            val item = newActive.removeAt(fromActive)
            newActive.add(toActive, item)
            val disabled = all.filterNot { it.enabled }
            val newAll = (newActive + disabled).mapIndexed { index, p -> p.copy(order = index) }
            persistPlugins(newAll)
            return
        }
        // 禁用插件在全量中直接移动
        val full = all.toMutableList()
        val from = full.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, full.lastIndex)
        if (from == to) return
        val item = full.removeAt(from)
        full.add(to, item)
        val newAll = full.mapIndexed { index, p -> p.copy(order = index) }
        persistPlugins(newAll)
    }

    private suspend fun installFromUrl(url: String): Int = withContext(Dispatchers.IO) {
        val clean = url.trim()
        require(isPluginUrl(clean)) { "插件地址必须是 HTTPS 的 .json 或 .js 地址" }
        val bytes = request(clean)
        val text = bytes.toString(Charsets.UTF_8).trimStart()
        if (text.startsWith("{")) {
            val base = runCatching { URI(clean).toString() }.getOrDefault(clean)
            val sources = readManifestEntries(text, base)
            var count = 0
            for (src in sources) {
                val pluginBytes = request(src.url)
                verifyHash(pluginBytes, src.sha256, src.url)
                register(PluginManifest(src.url, null, null, src.sha256), pluginBytes)
                count++
            }
            require(count > 0) { "清单中没有可用插件" }
            count
        } else {
            verifyHash(bytes, null, clean)
            val jsText = bytes.toString(Charsets.UTF_8)
            val fileName = runCatching { URI(clean).path.substringAfterLast('/').substringBeforeLast('.') }.getOrDefault("").ifBlank { "网络插件" }
            val platform = extractPlatform(jsText) ?: fileName
            register(PluginManifest(clean, platform, extractVersion(jsText)), bytes)
            1
        }
    }

    private data class RemoteSource(val url: String, val sha256: String?)

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
            // 清单条目也必须是 HTTPS
            if (!pluginUrl.startsWith("https://", true)) continue
            val sha = parseExpectedSha256(item.optString("sha256").takeIf { it.isNotBlank() }
                ?: item.optString("integrity").takeIf { it.isNotBlank() })
            out.add(RemoteSource(pluginUrl, sha))
            if (out.size >= 100) break
        }
        return out
    }

    private fun verifyHash(bytes: ByteArray, expected: String?, urlForMsg: String) {
        if (expected.isNullOrBlank()) return
        val actual = hashBytes(bytes).lowercase()
        require(actual.equals(expected.lowercase(), true)) { "插件校验失败：$urlForMsg" }
    }

    private fun register(manifest: PluginManifest, bytes: ByteArray): InstalledPlugin {
        require(bytes.size <= MAX_PLUGIN_BYTES) { "插件超过 5 MiB 限制" }
        val jsText = runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        val extractedPlatform = extractPlatform(jsText)
        val extractedVersion = extractVersion(jsText)
        val rawName = (manifest.name?.ifBlank { null } ?: extractedPlatform ?: "未命名插件")
        val displayName = normalizeName(rawName)
        val version = (manifest.version?.ifBlank { null } ?: extractedVersion ?: "未知版本")
        val id = hash(manifest.url)
        val current = readPlugins()
        val oldSameUrl = current.firstOrNull { it.id == id }
        // 同平台去重：新版本替换旧版本
        val oldSamePlatform = current.firstOrNull { it.name == displayName && it.id != id }
        if (oldSamePlatform != null) {
            // 拒绝旧版本覆盖已安装的新版本
            if (compareVersion(oldSamePlatform.version, version) > 0) {
                throw IllegalStateException("已安装更新的 ${displayName}（${oldSamePlatform.version}），拒绝旧版本覆盖")
            }
            File(root, oldSamePlatform.fileName).delete()
        }
        val plugin = InstalledPlugin(
            id, displayName, version, manifest.url, "$id.js",
            oldSameUrl?.enabled ?: oldSamePlatform?.enabled ?: true,
            oldSameUrl?.order ?: oldSamePlatform?.order ?: current.size
        )
        File(root, plugin.fileName).writeBytes(bytes)
        val next = (current.filterNot { it.id == id || (oldSamePlatform != null && it.id == oldSamePlatform.id) } + plugin).reorder()
        savePlugins(next)
        return plugin
    }

    private fun request(url: String): ByteArray {
        // 追加缓存破坏参数，防止 CDN/代理返回旧清单/旧插件
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

    /** 缓存破坏参数仅作用于传输 URL，注册 id 仍用原始 URL */
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
    private fun pluginFromJson(item: JSONObject) = InstalledPlugin(item.getString("id"), normalizeName(item.optString("name", "未命名插件")), item.optString("version", "未知版本"), item.optString("sourceUrl"), item.getString("fileName"), item.optBoolean("enabled", true), item.optInt("order"))
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
        /** 已知音源名，用于从干扰项中识别真正的导出 platform */
        private val KNOWN_PLATFORM_NAMES = setOf(
            "qq音乐", "网易云音乐", "酷我音乐", "酷狗音乐", "咪咕音乐", "汽水音乐",
            "bilibili", "qq", "wy", "kw", "kg", "mg", "qishui"
        )
        /** 非插件名的 platform 取值（请求参数等） */
        private val NON_PLATFORM_VALUES = setOf(
            "web", "pc", "h5", "android", "ios", "windows", "mac", "webfilter", "yqq.json"
        )
    }

    private fun extractPlatform(js: String): String? {
        // key 可带引号，同时排除 device_platform 等复合 key
        val strValues = Regex("""[^A-Za-z0-9_$]["']?platform["']?\s*:\s*(["'])([^"']{1,64})\1""")
            .findAll(js).map { it.groupValues[2].trim() }
            .filter { it.isNotEmpty() && it.length <= 128 }.toList()
        // 优先取已知音源名，导出块位于文件末尾故取最后一个，并跳过请求参数干扰项
        strValues.lastOrNull { KNOWN_PLATFORM_NAMES.contains(it.lowercase()) || it.contains("音乐") }?.let { return it }
        strValues.lastOrNull { !NON_PLATFORM_VALUES.contains(it.lowercase()) }?.let { return it }
        // 兼容 platform: SOME_CONST 写法：追踪常量的字符串赋值
        Regex("""[^A-Za-z0-9_$]["']?platform["']?\s*:\s*([A-Za-z_$][\w$]*)""")
            .findAll(js).map { it.groupValues[1] }.toList().asReversed().forEach { name ->
                Regex("""(?:const|let|var)\s+${Regex.escape(name)}\s*=\s*(["'])([^"']{1,64})\1""")
                    .find(js)?.let { return it.groupValues[2].trim().takeIf { v -> v.isNotEmpty() } }
            }
        return strValues.lastOrNull()
    }
    private fun extractVersion(js: String): String? {
        // key 可带引号，排除 version_code / appVersion 等复合 key
        val values = Regex("""[^A-Za-z0-9_$]["']?version["']?\s*:\s*["']([^"']{1,32})["']""")
            .findAll(js).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        // 版本号数字开头，导出块在末尾故取最后一个
        values.lastOrNull {
            it.first().isDigit() || ((it.first() == 'v' || it.first() == 'V') && it.length > 1 && it[1].isDigit())
        }?.let { return it }
        return values.lastOrNull()
    }
    /** 简单版本比较：>0 表示 old 更新 */
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
    private fun normalizeName(name: String): String = mapOf("qq" to "QQ音乐", "wy" to "网易云音乐", "kw" to "酷我音乐", "kg" to "酷狗音乐", "mg" to "咪咕音乐", "qishui" to "汽水音乐", "bilibili" to "bilibili")[name.lowercase()] ?: name
}
