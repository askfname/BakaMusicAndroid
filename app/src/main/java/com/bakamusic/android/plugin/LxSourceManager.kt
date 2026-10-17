package com.bakamusic.android.plugin

import com.bakamusic.android.data.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** LX 取流脚本仓库：文件落盘 + 激活态 */
class LxSourceManager(context: Context) {
    private val appContext = context.applicationContext
    private val root get() = File(appContext.filesDir, "lx-plugins").apply { mkdirs() }
    private val prefs get() = appContext.getSharedPreferences("lx_registry", Context.MODE_PRIVATE)
    val host = LxPluginHost()

    @Volatile private var descriptors: List<LxPluginDescriptor> = emptyList()
    @Volatile private var activeHash: String? = null

    /** 安装列表 */
    fun installed(): List<LxPluginDescriptor> =
        descriptors.map { it.copy(active = it.hash == activeHash) }

    fun active(): LxPluginDescriptor? = descriptors.firstOrNull { it.hash == activeHash }

    /** 某源当前选中的脚本 */
    fun activeFor(source: String): LxPluginDescriptor? =
        activeLxPluginForSource(descriptors, activeHash, source)

    /** 注册表指纹：LX 增删改/切换后推进，供播放地址缓存失效判断 */
    fun dataVersion(): Long = prefs.getLong("lx_generation", 0)

    private fun touchGeneration() {
        val next = maxOf(prefs.getLong("lx_generation", 0) + 1, System.currentTimeMillis())
        prefs.edit().putLong("lx_generation", next).apply()
    }

    /** 某平台的 LX 音质集；null 表示走原生插件 */
    fun getQualityOverride(platform: String): List<String>? {
        val source = lxSourceForPlatform(platform) ?: return null
        val plugin = activeLxPluginForSource(descriptors, activeHash, source) ?: return null
        return plugin.sources[source]?.qualities?.toList()
    }

    /** 启动/刷新时重载全部有效脚本 */
    suspend fun loadEnabled() = withContext(Dispatchers.IO) {
        host.clear()
        val state = readState()
        val next = mutableListOf<LxPluginDescriptor>()
        root.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "js" }
            ?.sortedBy { it.name }
            ?.take(100)
            .orEmpty()
            .forEach { file ->
                runCatching {
                    val code = file.readText(Charsets.UTF_8)
                    verifyIntegrity(file, code)
                    val info = parseLxScriptInfo(code, file.nameWithoutExtension)
                    val hash = sha256(code)
                    val sources = host.loadPlugin(hash, code, info)
                    next.add(
                        LxPluginDescriptor(
                            hash = hash, name = info.name, description = info.description,
                            version = info.version, author = info.author, homepage = info.homepage,
                            sourceUrl = integritySourceUrl(file).orEmpty(), sources = sources
                        )
                    )
                }.onFailure {
                    android.util.Log.e("BakaPlugin", "LX 脚本加载失败: ${file.name}", it)
                }
            }
        descriptors = next
        activeHash = if (state.configured) state.hash?.takeIf { h -> next.any { it.hash == h } } else next.firstOrNull()?.hash
        persistState()
        touchGeneration()
    }

    fun setActive(hash: String?) {
        if (hash != null) require(descriptors.any { it.hash == hash }) { "LX 脚本未安装" }
        activeHash = hash
        persistState()
        touchGeneration()
    }

    suspend fun installLocal(file: File): Result<LxPluginDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "脚本文件不存在" }
            require(file.extension.lowercase() == "js") { "LX 脚本仅支持 .js 文件" }
            installCode(file.readBytes(), file.nameWithoutExtension.ifBlank { file.name }, null)
        }
    }

    suspend fun installNetwork(url: String): Result<LxPluginDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = url.trim()
            require(isLxUrl(clean)) { "脚本地址必须是 HTTPS 的 .js 地址" }
            installCode(fetch(clean), clean.substringAfterLast('/').substringBefore('?').substringBeforeLast('.').ifBlank { "lx-script" }, clean)
        }
    }

    suspend fun uninstall(hash: String) = withContext(Dispatchers.IO) {
        host.unload(hash)
        File(root, "$hash.js").delete()
        File(root, "$hash.js.integrity.json").delete()
        descriptors = descriptors.filterNot { it.hash == hash }
        // 卸载启用中脚本后回落原生，不自动启用其他脚本
        if (activeHash == hash) {
            activeHash = null
            persistState()
        }
        touchGeneration()
    }

    fun close() = host.destroy()

    private fun installCode(bytes: ByteArray, fallbackName: String, sourceUrl: String?): LxPluginDescriptor {
        require(bytes.size <= MAX_LX_BYTES) { "脚本超过 5 MiB 限制" }
        val code = bytes.toString(Charsets.UTF_8)
        val hash = sha256(code)
        descriptors.firstOrNull { it.hash == hash }?.let {
            if (activeHash != hash) {
                activeHash = hash
                persistState()
                touchGeneration()
            }
            return it.copy(active = true)
        }
        val info = parseLxScriptInfo(code, fallbackName)
        val sources = runCatching { host.loadPlugin(hash, code, info) }.getOrThrow()
        File(root, "$hash.js").writeBytes(bytes)
        File(root, "$hash.js.integrity.json").writeText(
            JSONObject().apply {
                put("sha256", hash)
                if (!sourceUrl.isNullOrBlank()) put("sourceUrl", sourceUrl)
                put("installedAt", System.currentTimeMillis())
            }.toString()
        )
        val installed = LxPluginDescriptor(
            hash = hash, name = info.name, description = info.description,
            version = info.version, author = info.author, homepage = info.homepage,
            sourceUrl = sourceUrl.orEmpty(), sources = sources
        )
        // 同名旧版本视为更新，直接替换文件与宿主
        val oldSameName = descriptors.firstOrNull { it.hash != hash && it.name == installed.name }
        if (oldSameName != null) {
            host.unload(oldSameName.hash)
            File(root, "${oldSameName.hash}.js").delete()
            File(root, "${oldSameName.hash}.js.integrity.json").delete()
            // 兼容历史落盘：旧版曾以原文件名保存（如 latest.js），一并清理同名残留
            descriptors = descriptors.filterNot { it.hash == oldSameName.hash }
        }
        descriptors = (descriptors + installed).takeLast(100)
        activeHash = hash
        persistState()
        touchGeneration()
        return installed.copy(active = true)
    }

    private data class ActiveState(val configured: Boolean, val hash: String?)
    private fun stateFile() = File(root, "state.json")

    private fun readState(): ActiveState = runCatching {
        val o = JSONObject(stateFile().readText(Charsets.UTF_8))
        if (!o.has("activeHash")) return ActiveState(false, null)
        if (o.isNull("activeHash")) return ActiveState(true, null)
        val h = o.optString("activeHash")
        ActiveState(true, h.takeIf { it.matches(Regex("^[a-f0-9]{64}$")) })
    }.getOrDefault(ActiveState(false, null))

    private fun persistState() {
        runCatching {
            stateFile().writeText(
                JSONObject().apply {
                    val h = activeHash
                    if (h == null) put("activeHash", JSONObject.NULL) else put("activeHash", h)
                }.toString()
            )
        }
    }

    private fun verifyIntegrity(file: File, code: String) {
        val sidecar = File(root, "${file.name}.integrity.json")
        if (!sidecar.isFile) return
        val o = runCatching { JSONObject(sidecar.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        val expected = o.optString("sha256")
        if (expected.isNotBlank()) {
            require(sha256(code).equals(expected, true)) { "LX 脚本完整性校验失败" }
        }
    }

    private fun integritySourceUrl(file: File): String? = runCatching {
        JSONObject(File(root, "${file.name}.integrity.json").readText(Charsets.UTF_8)).optString("sourceUrl").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun isLxUrl(url: String): Boolean {
        // 仅支持 .js 格式（本地与网络一致）：路径须以 .js 结尾（允许 ?query），且全程 HTTPS
        val clean = url.trim()
        if (clean.length > 8192 || clean.isEmpty()) return false
        val uri = runCatching { URI(clean) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true)) return false
        val path = (uri.path ?: "").lowercase()
        return path.endsWith(".js")
    }

    private fun fetch(url: String): ByteArray {
        // 手动跟随重定向且全程保持 HTTPS（防 http 明文降级），超时 20s，上限 5MiB
        var current = "$url${if (URI(url).rawQuery.isNullOrEmpty()) "?" else "&"}_bakamusic_cache=${System.currentTimeMillis()}"
        require(current.length <= 8192) { "脚本地址过长" }
        var redirects = 0
        while (true) {
            val uri = runCatching { URI(current) }.getOrNull() ?: throw IllegalArgumentException("脚本地址无效")
            require(uri.scheme.equals("https", true)) { "脚本重定向必须保持 HTTPS" }
            val conn = uri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) BakaMusic/1.0")
            try {
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location")?.trim()
                        ?: throw IllegalStateException("脚本请求失败：$code")
                    current = runCatching { uri.resolve(loc).toString() }.getOrDefault(loc)
                    redirects++
                    continue
                }
                require(code in 200..299) { "脚本请求失败：$code" }
                val stream = try { conn.inputStream } catch (e: Exception) { conn.errorStream } ?: throw IllegalStateException("脚本请求失败：$code")
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_LX_BYTES) { "脚本超过 5 MiB 限制" }
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun sha256(code: String): String =
        sha256Bytes(code.toByteArray(Charsets.UTF_8))

    private fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val MAX_LX_BYTES = 5 * 1024 * 1024
    }
}
