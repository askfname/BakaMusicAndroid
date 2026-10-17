package com.bakamusic.android.plugin

import com.bakamusic.android.data.*
import android.util.Log
import android.webkit.JavascriptInterface
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSFunction
import com.quickjs.JSObject
import com.quickjs.JSValue
import com.quickjs.JavaCallback
import com.quickjs.QuickJS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** LX 脚本异常：与普通失败区分，仅探针类错误值得同档重试 */
class LxProbeException(message: String) : IllegalStateException(message)

/** LX 脚本宿主：QuickJS 同步执行 */
class LxPluginHost {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "BakaLxJs").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap.newKeySet<Future<*>>()
    private val open = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val hosted = ConcurrentHashMap<String, HostedScript>()

    private data class HostedScript(
        val quick: QuickJS,
        val context: JSContext,
        val lxObj: JSObject,
        val handler: JSFunction,
        val info: LxScriptInfo,
        val sources: Map<String, LxSourceDescriptor>
    )

    fun clear() {
        val items = hosted.values.toList()
        hosted.clear()
        if (items.isEmpty()) return
        val f = executor.submit {
            items.forEach { runCatching { (it.context as? java.io.Closeable)?.close() } }
            items.forEach { runCatching { it.quick.close() } }
        }
        pending.add(f)
        try { f.get() } catch (e: Exception) { /* 关闭失败忽略 */ } finally { pending.remove(f) }
    }

    fun unload(hash: String) {
        val item = hosted.remove(hash) ?: return
        val f = executor.submit {
            runCatching { (item.context as? java.io.Closeable)?.close() }
            runCatching { item.quick.close() }
        }
        pending.add(f)
        try { f.get() } catch (e: Exception) { /* 关闭失败忽略 */ } finally { pending.remove(f) }
    }

    fun cancelPending() {
        pending.toList().forEach { runCatching { it.cancel(true) } }
        open.toList().forEach { runCatching { it.disconnect() } }
        open.clear()
    }

    fun destroy() {
        cancelPending()
        val items = hosted.values.toList()
        hosted.clear()
        executor.execute {
            items.forEach { runCatching { (it.context as? java.io.Closeable)?.close() } }
            items.forEach { runCatching { it.quick.close() } }
            executor.shutdown()
        }
    }

    /** 载入脚本并执行 lx.send('inited') 握手，返回声明的 sources */
    fun loadPlugin(hash: String, code: String, info: LxScriptInfo): Map<String, LxSourceDescriptor> {
        val f = executor.submit<Map<String, LxSourceDescriptor>> { doLoad(hash, code, info) }
        pending.add(f)
        try {
            try {
                return f.get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                runCatching { f.cancel(true) }
                runCatching { unload(hash) }
                throw IllegalStateException("LX 脚本载入超时")
            }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            pending.remove(f)
        }
    }

    /** 调用脚本 request handler 取流，返回播放地址（挂起函数，20s 超时） */
    suspend fun invoke(
        hash: String,
        source: String,
        quality: String,
        musicInfo: JSONObject
    ): String = withContext(Dispatchers.IO) {
        withTimeout(INVOKE_TIMEOUT_MS) {
            val call = executor.submit<String> { doInvoke(hash, source, quality, musicInfo) }
            pending.add(call)
            try {
                awaitCancellable(call)
            } catch (e: TimeoutCancellationException) {
                runCatching { call.cancel(true) }
                throw MediaResolveTimeoutException("LX 解析超时: $source $quality")
            } catch (e: CancellationException) {
                runCatching { call.cancel(true) }
                throw e
            } finally {
                pending.remove(call)
            }
        }
    }

    private suspend fun <T> awaitCancellable(call: Future<T>): T {
        while (true) {
            try {
                return call.get(50, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                currentCoroutineContext().ensureActive()
            } catch (e: InterruptedException) {
                throw CancellationException("LX 请求已取消")
            } catch (e: java.util.concurrent.CancellationException) {
                throw CancellationException("LX 请求已取消")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private fun doLoad(hash: String, code: String, info: LxScriptInfo): Map<String, LxSourceDescriptor> {
        unload(hash)
        val quick = QuickJS.createRuntime()
        try {
            // 裸 context：LX 沙箱本就没有 require/module，模块机制的 require 回调遇 null-this 会直接 abort 进程
            val context = quick.createContext()
            installLxConsole(context, info.name)
            val httpNative = LxHttpNative()
            context.addJavascriptInterface(httpNative, "BakaLxHttp")
            val hostNative = LxHostNative(info, code)
            context.addJavascriptInterface(hostNative, "BakaLxHost")
            context.set("BakaLxCrypto", LxCryptoNative().let { crypto ->
                JSObject(context).apply {
                    registerJavaMethod(JavaCallback { _, args -> crypto.md5(strArg(args, 0)) }, "md5")
                    registerJavaMethod(JavaCallback { _, args -> crypto.randomBytes(intArg(args, 0)) }, "randomBytes")
                    registerJavaMethod(
                        JavaCallback { _, args ->
                            crypto.aesEncrypt(strArg(args, 0), strArg(args, 1), strArg(args, 2), strArg(args, 3))
                        },
                        "aesEncrypt"
                    )
                    registerJavaMethod(
                        JavaCallback { _, args -> crypto.rsaEncrypt(strArg(args, 0), strArg(args, 1)) },
                        "rsaEncrypt"
                    )
                    registerJavaMethod(JavaCallback { _, args -> crypto.inflate(strArg(args, 0)) }, "inflate")
                    registerJavaMethod(JavaCallback { _, args -> crypto.deflate(strArg(args, 0)) }, "deflate")
                }
            })
            context.executeVoidScript(runtimeBootstrap(), "quickjs-runtime.js")
            context.executeVoidScript(lxBootstrap(), "lx-runtime.js")
            context.executeVoidScript(stripAsyncAwait(code), "${info.name}.lx.js")
            val handler = runCatching { context.getObject("__lxRequestHandler") }.getOrNull()
                ?.takeIf { !it.isUndefined } as? JSFunction
                ?: throw IllegalStateException("LX 脚本未注册 request handler")
            val sources = hostNative.declaredSources()
                .takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("LX 脚本未声明可用 source")
            val lxObj = runCatching { context.getObject("lx") }.getOrNull()
                ?: throw IllegalStateException("LX 运行时异常")
            hosted[hash] = HostedScript(quick, context, lxObj, handler, info, sources)
            Log.d("BakaPlugin", "[LX] 载入 ${info.name} sources=${sources.keys}")
            return sources
        } catch (e: Throwable) {
            runCatching { quick.close() }
            throw e
        }
    }

    private fun doInvoke(hash: String, source: String, quality: String, musicInfo: JSONObject): String {
        val hostedScript = hosted[hash] ?: throw IllegalStateException("LX 脚本未加载")
        if (hostedScript.sources[source]?.qualities?.contains(quality) != true) {
            throw IllegalStateException("LX 不支持该音质: $quality")
        }
        val context = hostedScript.context
        val req = JSONObject().apply {
            put("source", source)
            put("action", "musicUrl")
            put("info", JSONObject().apply {
                put("type", quality)
                put("musicInfo", musicInfo)
            })
        }
        val args = JSArray(context).apply { push(JSObject(context, req)) }
        val raw = try {
            hostedScript.handler.call(hostedScript.lxObj, args)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
        val url = unwrapUrl(raw)
            ?: throw IllegalStateException(
                "LX 脚本返回了无效的播放地址: ${raw.toString().take(80)}" +
                    (if (raw is JSObject && !raw.isUndefined) " keys=${runCatching { raw.keys.toList() }.getOrDefault(emptyList())}" else "")
            )
        require(url.length <= MAX_URL_LENGTH) { "LX 脚本返回了无效的播放地址" }
        probe(url)
        return url
    }

    /** 剥离后 handler 应直接返回字符串；兼容 new Promise 包裹与 {url} 对象形态*/
    private fun unwrapUrl(raw: Any?, depth: Int = 0): String? {
        if (depth > 3) return null
        if (raw is String) return raw.trim().takeIf { it.startsWith("http", true) }
        if (raw is JSObject && !raw.isUndefined) {
            if (runCatching { raw.getObject("__baka_error") }.getOrNull()?.takeIf { !it.isUndefined } != null) {
                val msg = runCatching { raw.getObject("__baka_error").toString() }.getOrDefault("LX 脚本执行失败")
                throw IllegalStateException(msg)
            }
            // 不经 getType 直接取值：部分属性上 getType 不可靠
            // 1) Promise 包裹：_v 为字符串
            runCatching { raw.getString("_v") }.getOrNull()?.trim()?.takeIf { it.startsWith("http", true) }?.let { return it }
            // 2) 原包形态：{url: "https://..."}（字符串直接返回或 Promise 包裹后 _v 为对象）
            runCatching { raw.getString("url") }.getOrNull()?.trim()?.takeIf { it.startsWith("http", true) }?.let { return it }
            runCatching { raw.getObject("_v") }.getOrNull()?.takeIf { !it.isUndefined }?.let {
                return unwrapUrl(it, depth + 1)
            }
        }
        return null
    }

    /** 取流探针：Range 首字节，200/206 才算可用 */
    private fun probe(url: String) {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "LX 播放地址无效" }
        var current = url
        var redirects = 0
        while (true) {
            val host = URI(current).host?.lowercase() ?: throw IllegalArgumentException("LX 播放地址无效")
            require(!isPluginPrivateHost(host)) { "LX 不能访问本机或内网地址" }
            val conn = URI(current).toURL().openConnection() as HttpURLConnection
            open.add(conn)
            try {
                conn.connectTimeout = PROBE_TIMEOUT_MS
                conn.readTimeout = PROBE_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", PROBE_UA)
                conn.setRequestProperty("Range", "bytes=0-1")
                val code = conn.responseCode
                if (code in 300..399 && redirects < MAX_REDIRECTS) {
                    val loc = conn.getHeaderField("Location")?.trim()
                    if (!loc.isNullOrEmpty()) {
                        current = runCatching { URI(current).resolve(loc).toString() }.getOrDefault(loc)
                        require(current.startsWith("http://", true) || current.startsWith("https://", true)) { "LX 重定向地址无效" }
                        redirects++
                        continue
                    }
                }
                if (code != 200 && code != 206) throw LxProbeException("LX media URL probe failed with HTTP $code")
                return
            } finally {
                open.remove(conn)
                runCatching { conn.disconnect() }
            }
        }
    }

    /** lx.send('inited'/'updateAlert') 接收入口 */
    private inner class LxHostNative(private val info: LxScriptInfo, private val code: String) {
        @Volatile private var sources: Map<String, LxSourceDescriptor> = emptyMap()

        fun declaredSources(): Map<String, LxSourceDescriptor> = sources

        @JavascriptInterface
        fun send(event: String, dataJson: String): String {
            val data = runCatching { JSONObject(if (dataJson == "null") "{}" else dataJson) }.getOrDefault(JSONObject())
            when (event) {
                "inited" -> sources = parseLxSourcesDeclared(data)
                "updateAlert" -> Log.i("BakaPlugin", "[LX] ${info.name} 更新提示: ${data.optString("log")}")
                "showConfigView" -> { /* ignore: no host UI */ }
                else -> throw IllegalArgumentException("LX event unsupported: $event")
            }
            return "ok"
        }

        @JavascriptInterface
        fun scriptInfo(): String = JSONObject().apply {
            put("name", info.name)
            put("description", info.description)
            put("version", info.version)
            put("author", info.author)
            put("homepage", info.homepage)
            put("rawScript", code)
        }.toString()
    }

    /** lx.request 传输层：同步 HTTP，String 进出 */
    private inner class LxHttpNative {
        @JavascriptInterface
        fun request(configText: String): String {
            return try {
                executeOnce(JSONObject(configText)).toString()
            } catch (e: Throwable) {
                if (e is CancellationException || e is InterruptedException) throw e
                val host = runCatching { URI(JSONObject(configText).optString("url")).host }.getOrDefault("?")
                Log.w("BakaPlugin", "[LX:http] $host 失败: ${e.message}")
                JSONObject().put("__error", e.message ?: "network error").toString()
            }
        }

        private fun executeOnce(config: JSONObject): JSONObject {
            val url = config.getString("url")
            require(url.length <= MAX_URL_LENGTH) { "LX request URL is invalid" }
            require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "LX request URL protocol is invalid" }
            val method = config.optString("method", "GET").uppercase().takeIf { it.matches(Regex("^[A-Z]{3,12}$")) } ?: "GET"
            val timeoutMs = config.optInt("timeout", 15_000).coerceIn(3_000, 60_000)
            val isBinary = config.optString("responseType").equals("arraybuffer", true)
            val headers = config.optJSONObject("headers")
            val contentType = config.optString("contentType").takeIf { it.isNotBlank() }
            val bodyBytes = config.optString("data", "").takeIf { config.has("data") && !config.isNull("data") }?.toByteArray(Charsets.UTF_8)
            var current = url
            var currentMethod = method
            var redirects = 0
            val start = System.currentTimeMillis()
            Log.d("BakaPlugin", "[LX:http] $method ${runCatching { URI(current).host }.getOrDefault("?")}")
            while (true) {
                val host = URI(current).host?.lowercase() ?: throw IllegalArgumentException("LX 请求地址无效")
                require(!isPluginPrivateHost(host)) { "LX 不能访问本机或内网地址" }
                val conn = URI(current).toURL().openConnection() as HttpURLConnection
                open.add(conn)
                try {
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = currentMethod
                    var hasUA = false
                    var hasCT = false
                    headers?.keys()?.forEach { key ->
                        if (key.equals("User-Agent", true)) hasUA = true
                        if (key.equals("Content-Type", true)) hasCT = true
                        conn.setRequestProperty(key, headers.optString(key))
                    }
                    if (!hasUA) conn.setRequestProperty("User-Agent", LX_UA)
                    if (!hasCT && contentType != null) conn.setRequestProperty("Content-Type", contentType)
                    if (bodyBytes != null && currentMethod !in setOf("GET", "HEAD")) {
                        conn.doOutput = true
                        if (!hasCT && contentType == null) conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                        conn.outputStream.use { it.write(bodyBytes) }
                    }
                    val code = conn.responseCode
                    if (code in 300..399 && redirects < MAX_REDIRECTS) {
                        val loc = conn.getHeaderField("Location")?.trim()
                        if (!loc.isNullOrEmpty()) {
                            current = runCatching { URI(current).resolve(loc).toString() }.getOrDefault(loc)
                            require(current.startsWith("http://", true) || current.startsWith("https://", true)) { "LX 重定向地址无效" }
                            if (code == 303 || ((code == 301 || code == 302) && currentMethod == "POST")) currentMethod = "GET"
                            redirects++
                            continue
                        }
                    }
                    val respHeaders = JSONObject()
                    conn.headerFields?.forEach { (k, v) ->
                        if (!k.isNullOrBlank() && !v.isNullOrEmpty()) respHeaders.put(k, v.joinToString(","))
                    }
                    val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                    val bytes = stream.use { readLimited(it, MAX_RESPONSE_BYTES) }
                    val out = JSONObject()
                        .put("status", code)
                        .put("statusText", conn.responseMessage ?: "")
                        .put("headers", respHeaders)
                    if (isBinary) {
                        out.put("__base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    } else {
                        out.put("data", bytes.toString(Charsets.UTF_8))
                    }
                    Log.d("BakaPlugin", "[LX:http] $currentMethod $host code=$code ${System.currentTimeMillis() - start}ms resp=${bytes.size}B")
                    return out
                } finally {
                    open.remove(conn)
                    runCatching { conn.disconnect() }
                }
            }
        }
    }

    /** lx.utils.crypto/zlib 原生件 */
    private class LxCryptoNative {
        @JavascriptInterface fun md5(value: String): String = digest("MD5", value, 1024 * 1024)
        @JavascriptInterface fun randomBytes(size: Int): String {
            require(size in 0..1024 * 1024) { "LX random byte size is invalid" }
            val out = ByteArray(size)
            java.security.SecureRandom().nextBytes(out)
            return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
        }

        @JavascriptInterface fun aesEncrypt(base64Data: String, mode: String, base64Key: String, base64Iv: String): String {
            val m = Regex("^aes-(128|192|256)-(cbc|ecb)$").find(mode.lowercase()) ?: throw IllegalArgumentException("LX AES mode is invalid")
            val transformation = "AES/${m.groupValues[2].uppercase()}/PKCS5Padding"
            val key = android.util.Base64.decode(base64Key, android.util.Base64.DEFAULT)
            require(key.size == m.groupValues[1].toInt() / 8) { "LX AES key length is invalid" }
            val cipher = javax.crypto.Cipher.getInstance(transformation)
            val secret = javax.crypto.spec.SecretKeySpec(key, "AES")
            if (m.groupValues[2] == "ecb") cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secret)
            else {
                val iv = android.util.Base64.decode(base64Iv, android.util.Base64.DEFAULT)
                require(iv.size == 16) { "LX AES iv length is invalid" }
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))
            }
            val data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            require(data.size <= 8 * 1024 * 1024) { "LX AES input is invalid" }
            return android.util.Base64.encodeToString(cipher.doFinal(data), android.util.Base64.NO_WRAP)
        }

        @JavascriptInterface fun rsaEncrypt(base64Data: String, pemKey: String): String {
            val clean = pemKey.replace(Regex("-----[^-]+-----"), "").replace(Regex("\\s"), "")
            val keyBytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            val key = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes)) as java.security.interfaces.RSAPublicKey
            val modulusBytes = (key.modulus.bitLength() + 7) / 8
            val input = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            require(input.size <= modulusBytes) { "LX RSA input is too large" }
            // 裸 RSA_NO_PADDING：左侧补零至模长
            val padded = ByteArray(modulusBytes)
            input.copyInto(padded, modulusBytes - input.size)
            val out = java.math.BigInteger(1, padded).modPow(key.publicExponent, key.modulus).toByteArray()
            val fixed = ByteArray(modulusBytes)
            out.copyInto(fixed, modulusBytes - minOf(out.size, modulusBytes), maxOf(0, out.size - modulusBytes))
            return android.util.Base64.encodeToString(fixed, android.util.Base64.NO_WRAP)
        }

        @JavascriptInterface fun inflate(base64Data: String): String {
            val input = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            require(input.size <= 8 * 1024 * 1024) { "LX inflate input is invalid" }
            val raw = if (input.size >= 2 && input[0] == 0x1f.toByte() && input[1] == 0x8b.toByte()) {
                java.util.zip.GZIPInputStream(input.inputStream()).use { readLimited(it, 8 * 1024 * 1024) }
            } else {
                inflateZlib(input, false) ?: inflateZlib(input, true) ?: throw IllegalArgumentException("LX inflate failed")
            }
            return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        }

        @JavascriptInterface fun deflate(base64Data: String): String {
            val input = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            require(input.size <= 8 * 1024 * 1024) { "LX deflate input is invalid" }
            val deflater = java.util.zip.Deflater()
            try {
                deflater.setInput(input)
                deflater.finish()
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buf)
                    out.write(buf, 0, n)
                    require(out.size() <= 8 * 1024 * 1024) { "LX deflate output is invalid" }
                }
                return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } finally {
                deflater.end()
            }
        }

        private fun inflateZlib(input: ByteArray, nowrap: Boolean): ByteArray? = runCatching {
            val inflater = java.util.zip.Inflater(nowrap)
            try {
                inflater.setInput(input)
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(65536)
                while (!inflater.finished()) {
                    if (inflater.needsInput()) throw IllegalArgumentException("数据不完整")
                    val count = inflater.inflate(buffer)
                    if (count == 0) throw IllegalArgumentException("无法推进解压")
                    out.write(buffer, 0, count)
                    require(out.size() <= 8 * 1024 * 1024) { "LX inflate output is invalid" }
                }
                out.toByteArray()
            } finally {
                inflater.end()
            }
        }.getOrNull()

        private fun digest(algorithm: String, value: String, max: Int): String {
            require(value.length <= max) { "LX hash input is invalid" }
            return java.security.MessageDigest.getInstance(algorithm)
                .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    private fun strArg(args: JSArray, index: Int): String =
        runCatching { args.getString(index) }.getOrNull() ?: ""

    private fun intArg(args: JSArray, index: Int): Int =
        runCatching { args.getInteger(index) }.getOrDefault(0)

    private fun installLxConsole(context: JSContext, tag: String) {
        val consoleObj = JSObject(context)
        fun bind(name: String, priority: Int) {
            consoleObj.registerJavaMethod(JavaCallback { _, args ->
                runCatching {
                    val msg = buildList {
                        val count = runCatching { args.length() }.getOrDefault(0)
                        for (i in 0 until count) add(lxArgToString(args, i))
                    }.joinToString(" ")
                    Log.println(priority, "BakaPlugin", "[LX:$tag] $msg")
                }
                null
            }, name)
        }
        bind("log", Log.DEBUG)
        bind("info", Log.INFO)
        bind("warn", Log.WARN)
        bind("error", Log.ERROR)
        bind("debug", Log.DEBUG)
        context.set("console", consoleObj)
    }

    private fun lxArgToString(args: JSArray, index: Int): String = runCatching {
        when (args.getType(index)) {
            JSValue.TYPE.STRING -> args.getString(index) ?: "undefined"
            JSValue.TYPE.INTEGER -> args.getInteger(index).toString()
            JSValue.TYPE.DOUBLE -> args.getDouble(index).toString()
            JSValue.TYPE.BOOLEAN -> args.getBoolean(index).toString()
            JSValue.TYPE.NULL -> "null"
            JSValue.TYPE.UNDEFINED -> "undefined"
            JSValue.TYPE.JS_ARRAY -> args.getArray(index)?.takeIf { !it.isUndefined }?.toJSONArray()?.toString() ?: "undefined"
            JSValue.TYPE.JS_FUNCTION -> "<function>"
            else -> args.getObject(index)?.takeIf { !it.isUndefined }?.toJSONObject()?.toString() ?: "undefined"
        }
    }.getOrDefault("<unprintable)")

    /** LX 运行时垫片：同步 request + 事件注册 + 工具集 */
    private fun lxBootstrap(): String = """
        globalThis.__lxRequestHandler = null;
        globalThis.window = globalThis; globalThis.self = globalThis; globalThis.global = globalThis;
        (function(){
        function b64ToBytes(b64){ var bin = atob(String(b64)); var u = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i) & 255; return u; }
        function bytesToB64(u){ var bin = ''; for (var i = 0; i < u.length; i++) bin += String.fromCharCode(u[i]); return btoa(bin); }
        function parseBody(text){
          if (typeof text !== 'string') return text;
          var t = text.trim();
          if (t.charAt(0) === '{' || t.charAt(0) === '[') { try { return JSON.parse(text); } catch (e) { return text; } }
          return text;
        }
        globalThis.lx = {
          EVENT_NAMES: {request: 'request', inited: 'inited', updateAlert: 'updateAlert', showConfigView: 'showConfigView'},
          request: function(url, options, callback){
            if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) throw new Error('LX request URL is invalid');
            if (typeof callback !== 'function') throw new Error('LX request callback is required');
            var opts = (options && typeof options === 'object') ? options : {};
            var method = typeof opts.method === 'string' ? opts.method : 'GET';
            var headers = (opts.headers && typeof opts.headers === 'object') ? opts.headers : {};
            var timeout = (typeof opts.timeout === 'number' && opts.timeout > 0) ? opts.timeout : 15000;
            var binary = opts.binary === true;
            var cfg = {url: url, method: method, headers: headers, timeout: timeout, responseType: binary ? 'arraybuffer' : 'text'};
            if (opts.form && typeof opts.form === 'object') {
              var parts = []; for (var k in opts.form) { if (Object.prototype.hasOwnProperty.call(opts.form, k)) parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(opts.form[k]))); }
              cfg.data = parts.join('&'); cfg.contentType = 'application/x-www-form-urlencoded';
            } else if (opts.formData && typeof opts.formData === 'object') {
              var p2 = []; for (var k2 in opts.formData) { if (Object.prototype.hasOwnProperty.call(opts.formData, k2)) p2.push(encodeURIComponent(k2) + '=' + encodeURIComponent(String(opts.formData[k2]))); }
              cfg.data = p2.join('&'); cfg.contentType = 'application/x-www-form-urlencoded';
            } else if (opts.body !== undefined && opts.body !== null) {
              if (typeof opts.body === 'string') cfg.data = opts.body;
              else { try { cfg.data = JSON.stringify(opts.body); } catch (e) { cfg.data = String(opts.body); } cfg.contentType = 'application/json;charset=UTF-8'; }
            }
            var text = BakaLxHttp.request(JSON.stringify(cfg));
            var r = JSON.parse(text);
            if (r.__error) { var err = new Error(r.__error); try { callback(err, null, null); } catch (e2) {} throw err; }
            var body = r.__base64 ? b64ToBytes(r.__base64) : parseBody(r.data);
            var bytes = r.__base64 ? body.length : 0;
            try { if (!r.__base64 && typeof r.data === 'string') bytes = unescape(encodeURIComponent(r.data)).length; } catch (e3) {}
            var resp = {statusCode: r.status, statusMessage: r.statusText || '', headers: r.headers || {}, bytes: bytes, raw: r.__base64 ? body : (typeof r.data === 'string' ? r.data : ''), body: body};
            try { callback(null, resp, resp.body); } catch (e4) {}
            return resp;
          },
          send: function(eventName, data){ return BakaLxHost.send(String(eventName), JSON.stringify(data === undefined ? null : data)); },
          on: function(eventName, handler){
            if (typeof handler !== 'function') throw new Error('LX event unsupported: ' + String(eventName));
            // 接受注册但不替换 URL handler；其余未知事件才抛错
            if (eventName === 'showConfigView') return true;
            if (eventName !== 'request') throw new Error('LX event unsupported: ' + String(eventName));
            globalThis.__lxRequestHandler = handler;
            return true;
          },
          utils: {
            crypto: {
              md5: function(v){ return BakaLxCrypto.md5(String(v)); },
              randomBytes: function(n){ return b64ToBytes(BakaLxCrypto.randomBytes(Number(n) || 0)); },
              aesEncrypt: function(buf, mode, key, iv){
                return b64ToBytes(BakaLxCrypto.aesEncrypt(bytesToB64(buf), String(mode), bytesToB64(key), bytesToB64(iv || new Uint8Array(0))));
              },
              rsaEncrypt: function(buf, key){ return b64ToBytes(BakaLxCrypto.rsaEncrypt(bytesToB64(buf), String(key))); }
            },
            buffer: {
              from: function(input, encoding){
                var enc = String(encoding || 'utf8').toLowerCase();
                if (typeof input === 'string') {
                  if (enc === 'base64') return b64ToBytes(input);
                  if (enc === 'hex') { var h = input.replace(/^0x/i, ''); if (h.length % 2) h = '0' + h; var u = new Uint8Array(h.length / 2); for (var i = 0; i < u.length; i++) u[i] = parseInt(h.substr(i * 2, 2), 16); return u; }
                  var bin = unescape(encodeURIComponent(input)); var u2 = new Uint8Array(bin.length); for (var j = 0; j < bin.length; j++) u2[j] = bin.charCodeAt(j); return u2;
                }
                var u3 = new Uint8Array(input.length || 0); for (var k = 0; k < u3.length; k++) u3[k] = (input[k] || 0) & 255; return u3;
              },
              bufToString: function(buf, encoding){
                var enc = String(encoding || 'utf8').toLowerCase();
                var bin = ''; for (var i = 0; i < buf.length; i++) bin += String.fromCharCode(buf[i]);
                if (enc === 'base64') return btoa(bin);
                if (enc === 'hex') { var out = ''; for (var j = 0; j < bin.length; j++) { var h = bin.charCodeAt(j).toString(16); out += (h.length < 2 ? '0' : '') + h; } return out; }
                try { return decodeURIComponent(escape(bin)); } catch (e) { return bin; }
              }
            },
            zlib: {
              inflate: function(buf){ return b64ToBytes(BakaLxCrypto.inflate(bytesToB64(buf))); },
              deflate: function(buf){ return b64ToBytes(BakaLxCrypto.deflate(bytesToB64(buf))); }
            }
          },
          currentScriptInfo: JSON.parse(BakaLxHost.scriptInfo()),
          version: '2.0.0',
          env: 'desktop'
        };
        })();
    """.trimIndent()

    companion object {
        private const val MAX_URL_LENGTH = 8192
        private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val INVOKE_TIMEOUT_MS = 20_000L
        private const val LOAD_TIMEOUT_MS = 15_000L
        private const val LX_UA = "Mozilla/5.0 BakaMusic LX Compatibility/2.0"
        // Range 首字节探针需带同一 UA，部分 CDN 按 UA 限流
        private const val PROBE_UA = "Mozilla/5.0 BakaMusic LX Compatibility/2.0"
    }
}
