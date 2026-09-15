package com.bakamusic.android

import android.util.Log
import android.webkit.JavascriptInterface
import com.quickjs.CommonJSModule
import com.quickjs.JSArray
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
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.Semaphore
import org.json.JSONTokener

private fun isPluginPrivateHost(host: String): Boolean {
    if (host == "localhost" || host == "0.0.0.0" || host.endsWith(".localhost")) return true
    return runCatching {
        InetAddress.getAllByName(host).any { address ->
            address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.hostAddress.startsWith("fc", true) || address.hostAddress.startsWith("fd", true)
        }
    }.getOrDefault(true)
}

private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "响应超过限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** 基于 QuickJS 的 MusicPlugin 实现：引擎绑定单线程，不使用 WebView。 */
class QuickJsPluginRuntime(private val manager: PluginManager) {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BakaQuickJs").apply { isDaemon = true }
    }
    private val active = ConcurrentHashMap<String, QuickPlugin>()
    private val pending = ConcurrentHashMap.newKeySet<Future<*>>()

    private fun <T> submit(block: () -> T): Future<T> {
        val future = executor.submit<T> { block() }
        pending.add(future)
        return future
    }

    suspend fun loadEnabled(): List<Pair<InstalledPlugin, MusicPlugin>> = withContext(Dispatchers.IO) {
        clear()
        manager.plugins().mapNotNull { plugin ->
            runCatching {
                val instanceFuture = submit { QuickPlugin(plugin, manager.pluginFile(plugin), executor) }
                val instance = try { instanceFuture.get() } finally { pending.remove(instanceFuture) }
                active[plugin.id] = instance
                val initFuture = submit { instance.initialize() }
                try { initFuture.get() } finally { pending.remove(initFuture) }
                plugin to instance
            }.onFailure {
                active.remove(plugin.id)?.let { failed -> runCatching { submit { failed.close() }.get() } }
                Log.e("BakaPlugin", "QuickJS 加载失败: ${plugin.name}", it)
            }.getOrNull()
        }
    }

    fun cancelPending() {
        pending.toList().forEach { it.cancel(true) }
        active.values.forEach { it.cancelPendingRequests() }
    }

    fun destroy() {
        cancelPending()
        // 关闭操作必须排队到 QuickJS 线程执行，调用方不等待
        val instances = active.values.toList()
        active.clear()
        executor.execute {
            instances.forEach { instance -> runCatching { instance.close() } }
            executor.shutdown()
        }
    }

    private fun clear() {
        active.values.forEach { runCatching { it.cancelPendingRequests() } }
        active.values.forEach { instance -> runCatching { submit { instance.close() }.get() } }
        active.clear()
    }

    private class QuickPlugin(
        private val metadata: InstalledPlugin,
        private val file: File,
        private val executor: ExecutorService
    ) : MusicPlugin {
        private val quick = QuickJS.createRuntime()
        private val context: PluginContext
        private val httpBridge = HttpBridge()
        private val requestSlots = Semaphore(3)
        private val pendingCalls = ConcurrentHashMap.newKeySet<Future<*>>()
        /** 是否已被重载/释放：已关闭的实例不再触碰 native 上下文，直接抛 StalePluginException。 */
        @Volatile private var closed = false
        private lateinit var pluginObject: JSObject
        private var platformName = metadata.name
        private var qualities = emptySet<String>()

        init {
            require(file.isFile) { "插件文件不存在" }
            require(file.length() <= 5L * 1024 * 1024) { "插件超过 5 MiB 限制" }
            context = PluginContext(quick, moduleScripts())
            installConsoleBridge()
            context.addJavascriptInterface(httpBridge, "BakaHttp")
            val source = file.readText(Charsets.UTF_8)
            // 补齐 CommonJS 的 exports 别名，避免插件直接访问 exports 时抛 ReferenceError。
            // 所用 QuickJS 绑定不支持异步任务，插件源码需剥离 async/await 为同步调用
            //（配套的同步 Promise/then 垫片见 runtimeBootstrap）。
            context.executeVoidScript(runtimeBootstrap(), "quickjs-runtime.js")
            val wrappedSource = "var exports = module.exports;\n${patchKuwoTopListDetail(patchNeteaseSearchBloat(source))}"
            val module = context.executeModuleScript(stripAsyncAwait(wrappedSource), "${metadata.name}.js")
            // 缺失的导出键返回 Undefined 而非 null，仅当 default 为真实对象时使用
            val exportsObj = module.getObject("exports")?.takeIf { !it.isUndefined }
                ?: throw IllegalStateException("插件没有导出对象")
            val defaultObj = runCatching {
                if (!exportsObj.contains("default")) null
                else exportsObj.getObject("default")?.takeIf { !it.isUndefined }
            }.getOrNull()
            pluginObject = defaultObj ?: exportsObj
        }

        fun initialize() {
            platformName = safeExportString("platform")?.ifBlank { metadata.name } ?: metadata.name
            qualities = safeExportArray("supportedQualities")?.let { values ->
                buildSet { for (i in 0 until values.length()) values.getString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let(::add) }
            } ?: emptySet()
        }

        private fun safeExportString(key: String): String? = runCatching {
            if (pluginObject.isUndefined || !pluginObject.contains(key)) null
            else pluginObject.getString(key)
        }.getOrNull()

        private fun safeExportArray(key: String): JSArray? = runCatching {
            if (pluginObject.isUndefined || !pluginObject.contains(key)) null
            else pluginObject.getArray(key)?.takeIf { !it.isUndefined }
        }.getOrNull()

        /**
         * 网易搜索补丁：短路逐首补音质的串行请求，仅保留批量详情以保证搜索速度。
         * 需在 stripAsyncAwait 之前执行。
         */
        private fun patchNeteaseSearchBloat(source: String): String {
            if (!source.contains("await getBatchMusicQualityInfo(idList)")) return source
            return source.replace(
                "await getBatchMusicQualityInfo(idList)",
                "({})"
            )
        }

        /**
         * 酷我榜单补丁：榜单首屏只取列表字段即时呈现，无封面歌曲由 UI 层按需回填；
         * 歌单导入仍走单次搜索富化。仅修改运行时加载的源码。
         */
        private fun patchKuwoTopListDetail(source: String): String {
            if (!source.contains("kbangserver.kuwo.cn/ksong.s")) return source
            var out = source
            // 榜单：列表直出，无封面歌曲由 UI 层回填
            out = out.replace(
                """res.data.musiclist.map((_) => {
        const rid = _.musicrid || _.id;

        return Promise.all([
          getPicByRid(rid),
          getQualityByMusicId(_.id, _.name, _.artist)
        ]).then(([artwork, qualities]) => {""",
                """res.data.musiclist.map((_) => {
        const rid = _.musicrid || _.id;

        // 首屏秒出：封面稍后由 UI 层 search 回填
        return [null, {}].then(([artwork, qualities]) => {"""
            )
            // 歌单导入：保留单次搜索富化
            out = out.replace(
                """return Promise.all([
          getPicByRid(rid),
          getQualityByMusicId(_.id, _.name, _.artist)
        ]).then(([artwork, qualities]) => {""",
                """return kuwoTopListEnrich(_).then(([artwork, qualities]) => {"""
            )
            // 注入搜索富化 helper
            if (!out.contains("function kuwoTopListEnrich")) {
                out = out.replace(
                    "function getTopListDetail(topListItem) {",
                    """function kuwoTopListEnrich(_) {
var params = { client: "kt", all: ((_.name || "") + " " + (_.artist || "")), pn: 0, rn: 1, uid: 2574109560, ver: "kwplayer_ar_8.5.4.2", vipver: 1, ft: "music", cluster: 0, strategy: 2012, encoding: "utf8", rformat: "json", vermerge: 1, mobi: 1 };
var res = null;
try { res = axios_1.default.get("http://search.kuwo.cn/r.s", { params: params, timeout: 5000 }); } catch (e) { res = null; }
var hit = res && res.data && res.data.abslist && res.data.abslist[0];
var same = hit && String(hit.MUSICRID || "") === "MUSIC_" + String(_.id || "");
var qualities = same && hit.N_MINFO ? parseKuWoQualityInfo(hit.N_MINFO) : {};
var artwork = (same && artworkShort2Long(hit.web_albumpic_short)) || null;
return [artwork, qualities];
}
function getTopListDetail(topListItem) {"""
                )
            }
            // 封面文本查询加固：去首尾空白后再判断 http(s)
            out = out.replace(
                ".then(res => /^http/.test(res.data) ? res.data : null)",
                ".then(res => { var t = String(res.data == null ? \"\" : res.data).trim(); return /^https?:\\/\\//i.test(t) ? t : null; })"
            )
            // 时长优先取 song_duration
            out = out.replace(
                "const rawDuration = item?.duration ?? item?.DURATION ?? item?.songtime ?? item?.song_duration;",
                "const rawDuration = item?.song_duration ?? item?.duration ?? item?.DURATION ?? item?.songtime;"
            )
            return out
        }

        /**
         * 剥离 async/await 为同步调用。跳过字符串/模板文本/注释与正则字面量，
         * `obj.await` 形式的属性访问会被保留。
         */
        private fun stripAsyncAwait(source: String): String {
            val out = StringBuilder(source.length)
            val stack = ArrayDeque<Int>()
            val exprDepths = ArrayDeque<Int>()
            var state = 0 // 0=code 1=lineComment 2=blockComment 3=sq 4=dq 5=template 6=expr
            var exprDepth = 0
            var lastCode = '\u0000'
            var lastWord = ""
            var i = 0
            val n = source.length
            // 判断 `/` 后可跟正则的场景：上一个有效字符为表达式起始符或特定关键字
            val regexKeywords = setOf(
                "return", "typeof", "instanceof", "in", "of", "new", "delete",
                "void", "throw", "case", "do", "else", "yield", "await"
            )
            fun isId(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
            fun push(s: Int) { stack.addLast(s) }
            fun pop(): Int { state = if (stack.isEmpty()) 0 else stack.removeLast(); return state }
            while (i < n) {
                val c = source[i]
                when (state) {
                    1 -> {
                        out.append(c); i++
                        if (c == '\n') pop()
                    }
                    2 -> {
                        if (c == '*' && i + 1 < n && source[i + 1] == '/') { out.append("*/"); i += 2; pop() }
                        else { out.append(c); i++ }
                    }
                    3, 4 -> {
                        val q = if (state == 3) '\'' else '"'
                        if (c == '\\' && i + 1 < n) { out.append(c).append(source[i + 1]); i += 2 }
                        else { out.append(c); i++; if (c == q) pop() }
                    }
                    5 -> {
                        if (c == '\\' && i + 1 < n) { out.append(c).append(source[i + 1]); i += 2 }
                        else if (c == '`') { out.append(c); i++; pop() }
                        else if (c == '$' && i + 1 < n && source[i + 1] == '{') {
                            out.append("\${"); i += 2; push(5); exprDepths.addLast(exprDepth); exprDepth = 1; state = 6
                        } else { out.append(c); i++ }
                    }
                    0, 6 -> {
                        if (c == '/' && i + 1 < n && source[i + 1] == '/') { out.append("//"); i += 2; push(state); state = 1; continue }
                        if (c == '/' && i + 1 < n && source[i + 1] == '*') { out.append("/*"); i += 2; push(state); state = 2; continue }
                        if (c == '\'') { out.append(c); i++; push(state); state = 3; continue }
                        if (c == '"') { out.append(c); i++; push(state); state = 4; continue }
                        if (c == '`') { out.append(c); i++; push(state); state = 5; continue }
                        if (c == '/' && (i + 1 >= n || (source[i + 1] != '/' && source[i + 1] != '*'))) {
                            // 区分正则字面量与除法，避免误进字符串状态
                            val regexAllowed = lastCode == '\u0000' ||
                                lastCode in "([{,;:!&|?+-*%=<>^~>" ||
                                lastWord in regexKeywords
                            if (regexAllowed) {
                                out.append(c); i++
                                var inClass = false
                                var terminated = false
                                while (i < n) {
                                    val r = source[i]
                                    out.append(r)
                                    if (r == '\\' && i + 1 < n) { out.append(source[i + 1]); i += 2; continue }
                                    if (r == '\n') break
                                    if (r == '[') inClass = true
                                    else if (r == ']') inClass = false
                                    else if (r == '/' && !inClass) { i++; terminated = true; break }
                                    i++
                                }
                                if (terminated) {
                                    while (i < n && source[i].isLetter()) { out.append(source[i]); i++ }
                                }
                                lastCode = 'x'; lastWord = ""
                                continue
                            }
                            out.append(c); lastCode = c; lastWord = ""; i++; continue
                        }
                        if (state == 6) {
                            if (c == '{') exprDepth++
                            if (c == '}') {
                                exprDepth--
                                if (exprDepth == 0) {
                                    out.append(c); i++; lastCode = c
                                    exprDepth = if (exprDepths.isEmpty()) 0 else exprDepths.removeLast()
                                    pop(); continue
                                }
                            }
                        }
                        if (isId(c)) {
                            var j = i
                            while (j < n && isId(source[j])) j++
                            val word = source.substring(i, j)
                            if (word == "async" && (i == 0 || !isId(source[i - 1])) && lastCode != '.') {
                                var k = j
                                while (k < n && source[k].isWhitespace()) k++
                                val nextIsParen = k < n && source[k] == '('
                                // async 字符串方法名（如 async "search"()）
                                val nextIsQuotedName = k < n && (source[k] == '"' || source[k] == '\'' || source[k] == '`')
                                var nextWord = ""
                                var m = k
                                if (!nextIsParen && !nextIsQuotedName) { while (m < n && isId(source[m])) m++; nextWord = source.substring(k, m) }
                                var p = m
                                while (p < n && source[p].isWhitespace()) p++
                                val identFollowedByParen = nextWord.isNotEmpty() && nextWord != "function" && p < n && source[p] == '('
                                if (nextIsParen || nextIsQuotedName || nextWord == "function" || identFollowedByParen) {
                                    i = k; continue
                                }
                            }
                            if (word == "await" && (i == 0 || !isId(source[i - 1])) && lastCode != '.') {
                                var k2 = j
                                while (k2 < n && source[k2].isWhitespace()) k2++
                                i = k2; continue
                            }
                            out.append(word)
                            for (t in i until j) if (!source[t].isWhitespace()) lastCode = source[t]
                            lastWord = word
                            i = j; continue
                        }
                        out.append(c); if (!c.isWhitespace()) lastCode = c; i++
                    }
                }
            }
            return out.toString()
        }

        /** 变参安全的 console 桥：直接接收原始参数并逐个安全格式化，避免 JNI 回调异常导致崩溃。 */
        private fun installConsoleBridge() {
            val consoleObj = JSObject(context)
            fun bind(name: String, priority: Int) {
                consoleObj.registerJavaMethod(JavaCallback { _, args ->
                    runCatching {
                        val msg = buildList {
                            val count = runCatching { args.length() }.getOrDefault(0)
                            for (i in 0 until count) add(consoleArgToString(args, i))
                        }.joinToString(" ")
                        Log.println(priority, "BakaPlugin", "[$platformName][js] $msg")
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

        private fun consoleArgToString(args: JSArray, index: Int): String = runCatching {
            when (args.getType(index)) {
                JSValue.TYPE.STRING -> args.getString(index) ?: "undefined"
                JSValue.TYPE.INTEGER -> args.getInteger(index).toString()
                JSValue.TYPE.DOUBLE -> args.getDouble(index).toString()
                JSValue.TYPE.BOOLEAN -> args.getBoolean(index).toString()
                JSValue.TYPE.NULL -> "null"
                JSValue.TYPE.UNDEFINED -> "undefined"
                JSValue.TYPE.JS_ARRAY ->
                    args.getArray(index)?.takeIf { !it.isUndefined }?.toJSONArray()?.toString() ?: "undefined"
                JSValue.TYPE.JS_FUNCTION -> "<function>"
                else ->
                    args.getObject(index)?.takeIf { !it.isUndefined }?.toJSONObject()?.toString() ?: "undefined"
            }
        }.getOrDefault("<unprintable)")

        override val platform: String get() = platformName
        override val version: String get() = metadata.version
        override val supportedQualities: Set<String> get() = qualities

        override suspend fun search(query: String, page: Int): SearchPage<MediaItem> {
            // 首次失败后延时重试一次，仍失败则抛给上层展示
            val first = runCatching { invoke("search", query, page, "music") }
            first.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val value = first.getOrNull() ?: run {
                Log.w("BakaPlugin", "$platformName.search 第1次失败，1.2s后重试: ${first.exceptionOrNull()?.message}")
                kotlinx.coroutines.delay(1200)
                invoke("search", query, page, "music")
            }
            return value.let {
                val data = it.optJSONArray("data") ?: JSONArray()
                val items = buildList { for (i in 0 until data.length()) data.optJSONObject(i)?.let { item -> add(toMedia(item)) } }
                SearchPage(items, it.optBoolean("isEnd", true))
            }
        }

        /** 榜单分组（对应 getTopLists）：顶层数组读取 data，失败时返回空列表。 */
        override suspend fun topLists(): List<TopListGroup> {
            val value = runCatching { invoke("getTopLists") }.getOrNull() ?: return emptyList()
            val groups = value.optJSONArray("data") ?: return emptyList()
            val out = buildList {
                for (i in 0 until groups.length()) {
                    val group = groups.optJSONObject(i) ?: continue
                    val groupTitle = group.optString("title")
                    val items = group.optJSONArray("data") ?: continue
                    add(
                        TopListGroup(
                            title = groupTitle,
                            items = buildList {
                                for (j in 0 until items.length()) {
                                    items.optJSONObject(j)?.let { toTopList(it, groupTitle) }?.let(::add)
                                }
                            }
                        )
                    )
                }
            }
            val boards = out.sumOf { it.items.size }
            if (boards == 0) Log.w("BakaPlugin", "$platformName.getTopLists 空榜单: groups=${out.size} rawKeys=${value.keys().asSequence().toList()}")
            else Log.d("BakaPlugin", "$platformName.getTopLists: groups=${out.size} boards=$boards")
            return out
        }

        /** 榜单详情（对应 getTopListDetail）：兼容扁平与嵌套两种回包形状，isEnd 缺省为 true。 */
        override suspend fun topListDetail(item: TopListItem, page: Int): SearchPage<MediaItem> {
            // 榜单详情可能涉及多次串行请求，超时放宽到 60s
            val value = invoke("getTopListDetail", topListJson(item), page, timeoutMs = 60_000)
            val musicArray = value.optJSONArray("musicList")
                ?: value.optJSONObject("topListItem")?.optJSONArray("musicList")
                ?: value.optJSONArray("data")
                ?: JSONArray()
            val songs = buildList {
                for (i in 0 until musicArray.length()) {
                    musicArray.optJSONObject(i)?.let { add(toMedia(it)) }
                }
            }
            val noCover = songs.count { it.artwork.isNullOrBlank() }
            Log.d("BakaPlugin", "$platformName.getTopListDetail: board=${item.title} songs=${songs.size} noCover=$noCover")
            return SearchPage(songs, value.optBoolean("isEnd", true))
        }

        private fun toTopList(obj: JSONObject, groupTitle: String = ""): TopListItem? {
            val id = when (val raw = obj.opt("id")) {
                null, JSONObject.NULL -> ""
                is Number -> raw.toString()
                is Boolean -> raw.toString()
                else -> obj.optString("id")
            }.ifBlank { obj.optString("songid") }.ifBlank { obj.optString("topId").takeIf { it != "0" }.orEmpty() }
            if (id.isBlank()) return null
            val cover = obj.optString("coverImg")
                .ifBlank { obj.optString("artwork") }
                .ifBlank { obj.optString("cover") }
                .ifBlank { obj.optString("pic") }
                .takeIf(String::isNotBlank)
            return TopListItem(
                id = id,
                platform = obj.optString("platform").ifBlank { platformName },
                title = obj.optString("title").ifBlank { obj.optString("name") },
                description = obj.optString("description").ifBlank { obj.optString("intro") },
                coverImg = cover,
                groupTitle = groupTitle,
                rawJson = obj.toString()
            )
        }

        /** 单次播放解析超时：10s，超时抛 MediaResolveTimeoutException */
        private val mediaSourceTimeoutMs = 10_000L
        /** 未完成的播放解析任务：新请求到来时撤掉旧任务，避免单线程排队阻塞 */
        private val mediaCalls = ConcurrentHashMap.newKeySet<Future<*>>()

        /** 撤掉本插件上一笔未完成的播放任务 */
        private fun preemptMediaCalls() {
            if (mediaCalls.isEmpty()) return
            var hadRunning = false
            mediaCalls.toList().forEach { task ->
                if (!task.isDone) {
                    hadRunning = true
                    runCatching { task.cancel(true) }
                }
                mediaCalls.remove(task)
            }
            if (hadRunning) httpBridge.cancelAll()
        }

        override suspend fun getMediaSource(item: MediaItem, quality: String): MediaItem? {
            preemptMediaCalls()
            val value = try {
                invoke("getMediaSource", itemJson(item), quality, timeoutMs = mediaSourceTimeoutMs, trackMedia = true)
            } catch (e: TimeoutCancellationException) {
                throw MediaResolveTimeoutException("播放请求超时(${mediaSourceTimeoutMs}ms): ${item.platform}:${item.id} $quality")
            }
            val url = value.optString("url").takeIf { it.startsWith("http", true) } ?: return null
            // 本端无法解密加密流（cek/ekey），返回 null 让路由继续降质重试
            if (value.has("cek") && value.optString("cek").isNotBlank()) {
                Log.d("BakaPlugin", "$platformName.getMediaSource 加密流跳过(cek): quality=$quality")
                return null
            }
            if (value.has("ekey") && value.optString("ekey").isNotBlank()) {
                Log.d("BakaPlugin", "$platformName.getMediaSource 加密流跳过(ekey): quality=$quality")
                return null
            }
            val headers = mutableMapOf<String, String>()
            value.optJSONObject("headers")?.let { obj ->
                obj.keys().forEach { key -> obj.optString(key).takeIf(String::isNotBlank)?.let { headers[key] = it } }
            }
            value.optString("userAgent").takeIf(String::isNotBlank)?.let { headers.putIfAbsent("User-Agent", it) }
            return item.copy(mediaUrl = url, mediaHeaders = headers)
        }

        override suspend fun getLyric(item: MediaItem): LyricSource? {
            val value = invoke("getLyric", itemJson(item))
            if (value.length() == 0 || value.has("__error")) return null
            // 歌词字段可能是 URL/纯文本/QRC/字符映射对象，需分别处理；
            // 只收字符串并按映射/数组解开，避免把对象直接转成 JSON 文本展示。
            var raw = normalizeLyric(lyricString(value, "rawLrc"))
            var lrcUrl: String? = null
            lyricString(value, "lrc")?.let { lrc ->
                if (lrc.trimStart().startsWith("http", true)) lrcUrl = lrc.trim()
                else if (raw.isNullOrBlank()) raw = normalizeLyric(lrc)
            }
            if (raw.isNullOrBlank() && lrcUrl != null) {
                val url = lrcUrl
                raw = normalizeLyric(runCatching { fetchText(url!!) }.getOrNull()?.takeIf(String::isNotBlank))
            }
            var translation = normalizeLyric(lyricString(value, "translation"))
            var romanization = normalizeLyric(lyricString(value, "romanization"))
            // raw 为空时用翻译/罗马音兜底，避免显示暂无歌词
            if (raw.isNullOrBlank()) raw = translation ?: romanization
            return if (!raw.isNullOrBlank() || !translation.isNullOrBlank() || !romanization.isNullOrBlank()) {
                LyricSource(raw, translation, romanization, value.optString("format", "lrc").ifBlank { "lrc" })
            } else null
        }

        /** 歌词文本归一化：去 \r，展开字面量 \n。 */
        private fun normalizeLyric(text: String?): String? {
            if (text == null) return null
            val normalized = text.replace("\r", "")
                .replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
            return normalized.takeIf(String::isNotBlank)
        }

        /** 取歌词字段：字符映射对象按数字键拼回，数组按行拼接，其余对象丢弃。 */
        private fun lyricString(value: JSONObject, key: String): String? {
            val raw = value.opt(key) ?: return null
            if (raw == JSONObject.NULL) return null
            if (raw is String) return raw.takeIf(String::isNotBlank)
            if (raw is JSONArray) {
                val lines = buildList {
                    for (i in 0 until raw.length()) {
                        val item = raw.opt(i) ?: continue
                        if (item == JSONObject.NULL) continue
                        val text = if (item is String) item else continue
                        if (text.isNotBlank()) add(text)
                    }
                }
                return lines.joinToString("\n").takeIf(String::isNotBlank)
            }
            if (raw is JSONObject) {
                val keys = raw.keys().asSequence().toList()
                // 字符映射：键全是数字
                if (keys.isNotEmpty() && keys.all { it.all(Char::isDigit) }) {
                    return keys.sortedBy { it.toLongOrNull() ?: Long.MAX_VALUE }
                        .map { raw.optString(it) }.joinToString("").takeIf(String::isNotBlank)
                }
                // 其余对象不是歌词，不转 JSON 文本，直接丢弃
                return null
            }
            return null
        }

        /** lrc 为 URL 时同步拉一次纯文本歌词（5s 超时，2MB 限制）。 */
        private fun fetchText(url: String): String? = runCatching {
            require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "bad url" }
            val host = URI(url).host?.lowercase() ?: throw IllegalArgumentException("bad url")
            require(!isPluginPrivateHost(host)) { "bad host" }
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 5_000; conn.readTimeout = 5_000; conn.instanceFollowRedirects = true
                val code = conn.responseCode
                val finalUrl = conn.url?.toString() ?: url
                val finalHost = URI(finalUrl).host?.lowercase() ?: throw IllegalArgumentException("bad redirect")
                require(finalUrl.startsWith("http://", true) || finalUrl.startsWith("https://", true)) { "bad redirect" }
                require(!isPluginPrivateHost(finalHost)) { "bad redirect host" }
                val input = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                input.use { readLimited(it, 2 * 1024 * 1024) }
                    .toString(Charsets.UTF_8)
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrNull()

        override suspend fun importMusicSheet(urlLike: String): PlaylistSnapshot? = null

        override fun cancelPendingRequests() {
            mediaCalls.toList().forEach { runCatching { it.cancel(true) } }
            mediaCalls.clear()
            pendingCalls.toList().forEach { it.cancel(true) }
            httpBridge.cancelAll()
        }

        private suspend fun invoke(
            method: String,
            vararg args: Any?,
            timeoutMs: Long = 30_000,
            trackMedia: Boolean = false
        ): JSONObject = withContext(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                val call = executorSubmit {
                    // 已关闭实例不再触碰 JNI，直接抛异常让上层重取最新适配器重试
                    if (closed) throw StalePluginException("$platformName 运行时已重载")
                    // 参数直接按类型 push，避免装箱后类型丢失导致进 JS 全为 null
                    val callArgs = JSArray(context)
                    args.forEach { value -> pushArg(callArgs, value) }
                    val result = pluginObject.executeFunction(method, callArgs)
                    when (result) {
                        // JSArray 是 JSObject 子类，必须先判数组，否则顶层数组会被误转为数字键对象
                        is JSArray -> JSONObject().put("data", result.toJSONArray())
                        is JSObject -> result.toJSONObject()
                        else -> {
                            Log.d("BakaPlugin", "$platformName.$method 返回非对象结果(${result?.javaClass?.simpleName}): 空结果")
                            JSONObject()
                        }
                    }
                }
                pendingCalls.add(call)
                if (trackMedia) mediaCalls.add(call)
                try {
                    awaitCancellable(call).also { value ->
                        val size = value.optJSONArray("data")?.length() ?: 0
                        Log.d("BakaPlugin", "$platformName.$method 完成: data=$size keys=${value.keys().asSequence().toList()}")
                    }
                } catch (e: CancellationException) {
                    // 取消时撤掉单线程上的旧任务，避免阻塞后续请求
                    runCatching { call.cancel(true) }
                    httpBridge.cancelAll()
                    throw e
                } catch (e: java.util.concurrent.ExecutionException) {
                    Log.e("BakaPlugin", "$platformName.$method 调用失败", e.cause ?: e)
                    throw e.cause ?: e
                } finally {
                    pendingCalls.remove(call)
                    if (trackMedia) mediaCalls.remove(call)
                }
            }
        }

        /** 可取消地等待单线程上的 JS 任务，避免超时与协程取消无法生效。 */
        private suspend fun <T> awaitCancellable(call: Future<T>): T {
            while (true) {
                try {
                    return call.get(50, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    currentCoroutineContext().ensureActive()
                } catch (e: InterruptedException) {
                    throw CancellationException("插件请求已取消")
                } catch (e: java.util.concurrent.CancellationException) {
                    throw CancellationException("插件请求已取消")
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e
                }
            }
        }

        private fun <T> executorSubmit(block: () -> T): Future<T> = executor.submit<T> { block() }

        fun close() { if (closed) return; closed = true; runCatching { context.close() }; runCatching { quick.close() } }

        /** 榜单条目回传插件：保留原始字段供插件定位榜单 */
        private fun topListJson(item: TopListItem) = runCatching { if (item.rawJson != null) JSONObject(item.rawJson) else JSONObject() }.getOrDefault(JSONObject()).apply {
            put("id", item.id); put("platform", item.platform.ifBlank { platformName }); put("title", item.title)
            if (!has("coverImg") && item.coverImg != null) put("coverImg", item.coverImg)
            if (!has("description") && item.description.isNotBlank()) put("description", item.description)
        }

        private fun itemJson(item: MediaItem) = runCatching { if (item.rawJson != null) JSONObject(item.rawJson) else JSONObject() }.getOrDefault(JSONObject()).apply {
            put("id", item.id); put("platform", item.platform); put("title", item.title); put("artist", item.artist); put("album", item.album)
            // 补齐秒级 duration，保证 rawJson 缺失时插件仍可取到时长
            if (!has("duration") && item.durationMs > 0) put("duration", item.durationMs / 1000)
            if (!has("durationMs") && item.durationMs > 0) put("durationMs", item.durationMs)
        }

        private fun pushArg(array: JSArray, value: Any?) {
            when (value) {
                is JSONObject -> array.push(JSObject(context, value))
                is JSONArray -> array.push(JSArray(context, value))
                is String -> array.push(value)
                is Int -> array.push(value)
                is Long -> array.push(value.toDouble())
                is Double -> array.push(value)
                is Boolean -> array.push(value)
                null -> array.push(JSValue.NULL())
                else -> array.push(value.toString())
            }
        }

        private fun toMedia(obj: JSONObject): MediaItem {
            val qualities = mutableMapOf<String, QualityInfo>()
            obj.optJSONObject("qualities")?.let { source ->
                source.keys().forEach { key ->
                    val info = source.optJSONObject(key)
                    if (info != null) {
                        qualities[key] = QualityInfo(
                            size = info.optLong("size", 0L).takeIf { it > 0 },
                            bitrate = info.optLong("bitrate", 0L).takeIf { it > 0 },
                            url = info.optString("url").takeIf { it.startsWith("http", true) }
                        )
                    }
                }
            }
            val duration = obj.optDouble("duration", 0.0)
            return MediaItem(
            id = obj.optString("id").ifBlank { obj.optString("songmid") }.ifBlank { obj.optString("songid") },
            platform = obj.optString("platform").ifBlank { platformName },
            title = obj.optString("title").ifBlank { obj.optString("songname") },
            artist = obj.optString("artist").ifBlank { obj.optString("singer") },
            album = obj.optString("album").ifBlank { obj.optString("albumname") },
            // 封面统一去首尾空白，避免空白导致图片加载失败
            artwork = obj.optString("artwork").trim().ifBlank { obj.optString("coverImg").trim() }.ifBlank { obj.optString("cover").trim() }.takeIf(String::isNotBlank),
            durationMs = if (duration > 10000) duration.toLong() else (duration * 1000).toLong(),
            mediaUrl = obj.optString("url").takeIf { it.startsWith("http", true) },
            qualities = qualities,
            rawJson = obj.toString()
            )
        }

        private inner class HttpBridge {
            private val open = ConcurrentHashMap.newKeySet<HttpURLConnection>()

            fun cancelAll() { open.toList().forEach { runCatching { it.disconnect() } }; open.clear() }

            @JavascriptInterface
            fun request(configText: String): String {
                if (!requestSlots.tryAcquire()) {
                    return JSONObject().put("__error", "插件请求并发数超限").toString()
                }
                return try {
                    runCatching {
                val config = JSONObject(configText)
                var url = encodeUrlInline(config.getString("url"))
                config.optJSONObject("params")?.let { params ->
                    val query = params.keys().asSequence().flatMap { key ->
                        val value = params.opt(key)
                        if (value is JSONArray) valueList(value).asSequence().map { key to it }
                        else sequenceOf(key to value)
                    }.filter { it.second != null && it.second != JSONObject.NULL }
                        .joinToString("&") { (key, value) ->
                            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value.toString(), "UTF-8")}"
                        }
                    if (query.isNotEmpty()) url += if (url.contains('?')) "&$query" else "?$query"
                }
                require(url.startsWith("https://", true) || url.startsWith("http://", true)) { "插件网络请求必须使用 HTTP(S)" }
                val host = URI(url).host?.lowercase() ?: throw IllegalArgumentException("插件请求地址无效")
                require(!isPluginPrivateHost(host)) { "插件不能访问本机或内网地址" }
                val method = config.optString("method", "GET").uppercase()
                val isBinary = config.optString("responseType").equals("arraybuffer", true) || config.optString("responseType").equals("blob", true)
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                open.add(conn)
                try {
                    conn.connectTimeout = 15_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = true
                    conn.requestMethod = method
                    val headers = config.optJSONObject("headers")
                    headers?.keys()?.forEach { key -> conn.setRequestProperty(key, headers.optString(key)) }
                    val data = config.opt("data")
                    if (data != null && data != JSONObject.NULL && conn.requestMethod !in setOf("GET", "HEAD")) {
                    conn.doOutput = true
                    // Header 名大小写不敏感，需遍历匹配 Content-Type
                    var contentType = ""
                    headers?.keys()?.forEach { key ->
                        if (key.equals("Content-Type", true)) contentType = headers.optString(key).orEmpty()
                    }
                    if (contentType.isBlank()) conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                    // 表单 Content-Type 配对象 data 时做 urlencoded 序列化；字符串 data 原样发送
                    val bodyBytes = if (data is JSONObject && contentType.contains("x-www-form-urlencoded", true)) {
                        formEncode(data).toByteArray()
                    } else {
                        data.toString().toByteArray()
                    }
                    conn.outputStream.use { it.write(bodyBytes) }
                }
                val code = conn.responseCode
                val finalUrl = conn.url?.toString() ?: url
                require(finalUrl.startsWith("http://", true) || finalUrl.startsWith("https://", true)) { "插件重定向地址无效" }
                val finalHost = URI(finalUrl).host?.lowercase() ?: throw IllegalArgumentException("插件重定向地址无效")
                require(!isPluginPrivateHost(finalHost)) { "插件不能重定向到本机或内网地址" }
                val input = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                require(conn.contentLengthLong <= 16L * 1024 * 1024 || conn.contentLengthLong < 0) { "响应超过 16 MiB 限制" }
                val encoding = (conn.contentEncoding ?: "").lowercase()
                val decoded = when {
                    encoding.contains("gzip") -> java.util.zip.GZIPInputStream(input)
                    encoding.contains("deflate") -> java.util.zip.InflaterInputStream(input)
                    else -> input
                }
                val bytes = decoded.use { stream ->
                    readLimited(stream, 16 * 1024 * 1024)
                }
                val body = String(bytes, Charsets.UTF_8)
                val dataValue = if (isBinary) {
                    JSONObject().put("__base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                } else {
                    // 仅类 JSON 体做解析，其余（如 JSONP/纯 LRC 文本）原样返回字符串
                    val trimmed = body.trimStart()
                    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        runCatching { JSONTokener(body).nextValue() }.getOrElse { body }
                    } else body
                }
                val responseHeaders = JSONObject()
                conn.headerFields.forEach { (key, values) ->
                    if (!key.isNullOrBlank() && !values.isNullOrEmpty()) responseHeaders.put(key, values.joinToString(","))
                }
                JSONObject().put("status", code).put("headers", responseHeaders).put("data", dataValue).toString()
                } finally {
                    open.remove(conn)
                    runCatching { conn.disconnect() }
                }
                    }.getOrElse { JSONObject().put("__error", it.message ?: "network error").toString() }
                } finally {
                    requestSlots.release()
                }
            }

            private fun valueList(value: JSONArray): List<Any?> = List(value.length()) { value.opt(it) }

            /** URL 仅编码非法字符，不动已有 %XX 编码。 */
            private fun encodeUrlInline(raw: String): String {
                val out = StringBuilder(raw.length)
                for (ch in raw.trim()) {
                    if (ch == ' ') out.append("%20")
                    else if (ch.code > 127 || ch in "<>\"{}|\\^`") out.append(URLEncoder.encode(ch.toString(), "UTF-8"))
                    else out.append(ch)
                }
                return out.toString()
            }

            private fun formEncode(obj: JSONObject): String =
                obj.keys().asSequence().map { key ->
                    val raw = obj.opt(key)
                    val text = when (raw) {
                        null, JSONObject.NULL -> ""
                        is JSONObject, is JSONArray -> raw.toString()
                        else -> raw.toString()
                    }
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(text, "UTF-8")}"
                }.joinToString("&")

            @JavascriptInterface fun md5(value: String) = digest("MD5", value)
            @JavascriptInterface fun sha1(value: String) = digest("SHA-1", value)
            @JavascriptInterface fun sha256(value: String) = digest("SHA-256", value)
            @JavascriptInterface fun hmacSha256(key: String, value: String): String = runCatching {
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
                mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            }.getOrDefault("")
            @JavascriptInterface fun modPowHex(baseHex: String, expHex: String, modHex: String): String = runCatching {
                java.math.BigInteger(baseHex.trim().removePrefix("0x").ifEmpty { "0" }, 16)
                    .modPow(
                        java.math.BigInteger(expHex.trim().removePrefix("0x").ifEmpty { "0" }, 16),
                        java.math.BigInteger(modHex.trim().removePrefix("0x").ifEmpty { "1" }, 16)
                    ).toString(16)
            }.getOrDefault("0")
            @JavascriptInterface fun textDecode(base64: String, charsetName: String): String = runCatching {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                String(bytes, java.nio.charset.Charset.forName(charsetName))
            }.getOrDefault("")
            /** zlib/raw-deflate 解压（供 pako.inflate 垫片）。 */
            @JavascriptInterface fun inflate(base64: String): String = runCatching {
                val input = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                require(input.size <= 8 * 1024 * 1024) { "inflate 输入超限" }
                val raw = if (input.size >= 2 && input[0] == 0x1f.toByte() && input[1] == 0x8b.toByte()) {
                    java.util.zip.GZIPInputStream(input.inputStream()).use { readLimited(it, 8 * 1024 * 1024) }
                } else {
                    inflateZlib(input, false) ?: inflateZlib(input, true)
                    ?: throw IllegalArgumentException("inflate 失败")
                }
                require(raw.size <= 8 * 1024 * 1024) { "inflate 输出超限" }
                android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
            }.getOrDefault("")

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
                        require(out.size() <= 8 * 1024 * 1024) { "inflate 输出超限" }
                    }
                    out.toByteArray()
                } finally {
                    inflater.end()
                }
            }.getOrNull()
            @JavascriptInterface fun aes(mode: String, key: String, iv: String, plain: String): String = runCatching {
                val cipher = javax.crypto.Cipher.getInstance("AES/${mode.uppercase()}/PKCS5Padding")
                // 非标准长度密钥按 16/24/32 补零对齐，与 CryptoJS 保持一致
                val secret = javax.crypto.spec.SecretKeySpec(normalizeAesKey(key.toByteArray()), "AES")
                if (mode.equals("ECB", true)) cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secret)
                else cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv.toByteArray().copyOf(16)))
                android.util.Base64.encodeToString(cipher.doFinal(plain.toByteArray()), android.util.Base64.NO_WRAP)
            }.getOrDefault("")

            private fun normalizeAesKey(bytes: ByteArray): ByteArray {
                if (bytes.size == 16 || bytes.size == 24 || bytes.size == 32) return bytes
                val target = intArrayOf(16, 24, 32).firstOrNull { it > bytes.size } ?: 32
                return bytes.copyOf(target)
            }

            private fun digest(algorithm: String, value: String) = java.security.MessageDigest.getInstance(algorithm)
                .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private class PluginContext(
            quick: QuickJS,
            private val modules: Map<String, String>
        ) : CommonJSModule(quick) {
            /** 未知可选模块返回空模块，避免 JNI 回调内抛异常导致崩溃。 */
            override fun getModuleScript(name: String): String =
                modules[name] ?: "module.exports={};"
        }

        /** 同步运行时垫片：提供同步 Promise、任意值的 then/catch、同步 setTimeout，与 stripAsyncAwait 配套。 */
        private fun runtimeBootstrap(): String = """
            var __root = this;
            function setTimeout(fn){ fn(); return 0; }
            function clearTimeout(id) {}
            __root.setTimeout = setTimeout;
            __root.clearTimeout = clearTimeout;
            function __BakaRejected(err){ this.__baka_error = err; }
            __BakaRejected.prototype.then = function(onF, onR){
              if (typeof onR === 'function') {
                try { var r = onR(this.__baka_error); return (r instanceof __BakaRejected) ? r : r; }
                catch (e2) { return new __BakaRejected(e2); }
              }
              return this;
            };
            __BakaRejected.prototype.catch = function(onR){ return this.then(undefined, onR); };
            Object.defineProperty(Object.prototype, 'then', {configurable: true, writable: true, enumerable: false, value: function(onF, onR){
              try {
                var r = (typeof onF === 'function') ? onF(this) : this;
                return (r instanceof __BakaRejected) ? r : r;
              } catch (e) {
                if (typeof onR === 'function') {
                  try { var r2 = onR(e); return (r2 instanceof __BakaRejected) ? r2 : r2; }
                  catch (e2) { return new __BakaRejected(e2); }
                }
                return new __BakaRejected(e);
              }
            }});
            Object.defineProperty(Object.prototype, 'catch', {configurable: true, writable: true, enumerable: false, value: function(onR){ return this; }});
            function Promise(executor){
              var self = this;
              self._v = undefined;
              function resolve(v){ self._v = (v instanceof Promise) ? v._v : v; }
              function reject(e){ self._v = new __BakaRejected(e); }
              try { executor(resolve, reject); } catch (e) { reject(e); }
            }
            Promise.resolve = function(v){ return (v instanceof Promise) ? v._v : v; };
            Promise.all = function(values){
              var out = []; var list = values || [];
              for (var i = 0; i < list.length; i++) {
                var v = list[i];
                if (v instanceof Promise) v = v._v;
                if (v instanceof __BakaRejected) throw v.__baka_error;
                out.push(v);
              }
              return out;
            };
            globalThis.Promise = Promise;
            function HashValue(value, enc){ this.value = String(value); this.enc = enc || null; }
            HashValue.prototype.toString = function(fmt){
              if (fmt === CryptoJs.enc.Hex) {
                if (this.enc === 'hex') return this.value;
                if (this.enc === 'base64') return __bakaBinToHex(atob(this.value));
                return __bakaBinToHex(__bakaUtf8Encode(this.value));
              }
              if (fmt === CryptoJs.enc.Base64) {
                if (this.enc === 'base64') return this.value;
                if (this.enc === 'hex') return btoa(__bakaHexToBin(this.value));
                return btoa(__bakaUtf8Encode(this.value));
              }
              if (fmt === CryptoJs.enc.Utf8) {
                if (this.enc === 'base64') return __bakaUtf8Decode(atob(this.value));
                if (this.enc === 'hex') return __bakaUtf8Decode(__bakaHexToBin(this.value));
                return this.value;
              }
              return this.value;
            };
            function hash(name, value){ return new HashValue(BakaHttp[name](String(value)), 'hex'); }
            var CryptoJs = {};
            CryptoJs.MD5 = function(value){ return hash('md5', value); };
            CryptoJs.SHA1 = function(value){ return hash('sha1', value); };
            CryptoJs.SHA256 = function(value){ return hash('sha256', value); };
            CryptoJs.HmacSHA256 = function(value, key){ return new HashValue(BakaHttp.hmacSha256(String(key), String(value)), 'hex'); };
            CryptoJs.enc = {
              Utf8: {parse: function(value){ return new HashValue(value, 'utf8'); }},
              Hex: {parse: function(value){ return new HashValue(value, 'hex'); }, stringify: function(value){ return String(value.value || value); }},
              Base64: {parse: function(value){ return new HashValue(__bakaUtf8Decode(atob(String(value))), 'utf8'); }, stringify: function(value){ return String(value.value || value); }}
            };
            CryptoJs.mode = {ECB: {name: 'ECB'}, CBC: {name: 'CBC'}};
            CryptoJs.pad = {Pkcs7: {}};
            CryptoJs.AES = {encrypt: function(value, key, config){
              var mode = config && config.mode && config.mode.name ? config.mode.name : 'CBC';
              var iv = config && config.iv ? String(config.iv.value || config.iv) : '';
              var cipher = new HashValue(BakaHttp.aes(mode, String(key.value || key), iv, String(value.value || value)), 'base64');
              return {ciphertext: cipher, toString: function(){ return cipher.toString(); }};
            }};
            function atob(value){
              var table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
              var input = String(value).replace(/=+$/, ''); var output = ''; var buffer = 0; var bits = 0;
              for (var i = 0; i < input.length; i++) { var n = table.indexOf(input.charAt(i)); if (n < 0) continue; buffer = (buffer << 6) | n; bits += 6; if (bits >= 8) { bits -= 8; output += String.fromCharCode((buffer >> bits) & 255); } }
              return output;
            }
            function btoa(value){
              var table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/='; var output = '';
              for (var i = 0; i < value.length; i += 3) { var a = value.charCodeAt(i), b = value.charCodeAt(i + 1), c = value.charCodeAt(i + 2); var n = (a << 16) | ((isNaN(b) ? 0 : b) << 8) | (isNaN(c) ? 0 : c); output += table[(n >> 18) & 63] + table[(n >> 12) & 63] + (isNaN(b) ? '=' : table[(n >> 6) & 63]) + (isNaN(c) ? '=' : table[n & 63]); }
              return output;
            }
            function __bakaUtf8Encode(s){ return unescape(encodeURIComponent(String(s))); }
            function __bakaUtf8Decode(bin){ try { return decodeURIComponent(escape(bin)); } catch (e) { return bin; } }
            function __bakaHexToBin(hex){ var h = String(hex).replace(/^0x/i, ''); if (h.length % 2) h = '0' + h; var out = ''; for (var i = 0; i < h.length; i += 2) out += String.fromCharCode(parseInt(h.substr(i, 2), 16)); return out; }
            function __bakaBinToHex(bin){ var out = ''; for (var i = 0; i < bin.length; i++) { var h = bin.charCodeAt(i).toString(16); out += (h.length < 2 ? '0' : '') + h; } return out; }
            function makeBuffer(value){
              value.toString = function(encoding){
                var bin = ''; for (var i = 0; i < value.length; i++) bin += String.fromCharCode(value[i]);
                if (encoding === 'base64') return btoa(bin);
                if (encoding === 'hex') return __bakaBinToHex(bin);
                return __bakaUtf8Decode(bin);
              };
              var _slice = value.slice, _sub = value.subarray;
              value.slice = function(s, e){ return makeBuffer(_slice.call(value, s, e)); };
              value.subarray = function(s, e){ return makeBuffer(_sub.call(value, s, e)); };
              return value;
            }
            var Buffer = {};
            Buffer.from = function(value, encoding){
              if (typeof value === 'string') {
                var enc = String(encoding || 'utf8').toLowerCase();
                var bin = enc === 'base64' ? atob(value) : enc === 'hex' ? __bakaHexToBin(value) : __bakaUtf8Encode(value);
                var bytes = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i) & 255; return makeBuffer(bytes);
              }
              if (value instanceof Uint8Array) { var c = new Uint8Array(value.length); c.set(value); return makeBuffer(c); }
              var arr = new Uint8Array((value && value.length) || 0); for (var j = 0; j < arr.length; j++) arr[j] = (value[j] || 0) & 255; return makeBuffer(arr);
            };
            Buffer.alloc = function(size, fill){ var a = new Uint8Array(size || 0); if (fill) a.fill(typeof fill === 'number' ? fill : 0); return makeBuffer(a); };
            Buffer.concat = function(list){ var size = 0; for (var i = 0; i < list.length; i++) size += list[i].length; var result = new Uint8Array(size); var offset = 0; for (var j = 0; j < list.length; j++) { result.set(list[j], offset); offset += list[j].length; } return makeBuffer(result); };
            Buffer.isBuffer = function(value){ return value instanceof Uint8Array; };
            Buffer.byteLength = function(s){ return __bakaUtf8Encode(s).length; };
            globalThis.Buffer = Buffer; globalThis.atob = atob; globalThis.btoa = btoa;
            function TextEncoder(){ this.encode = function(value){ var bin = __bakaUtf8Encode(value); var out = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return makeBuffer(out); }; }
            function TextDecoder(label){
              var enc = String(label || 'utf-8').toLowerCase();
              this.decode = function(value){
                if (!value) return '';
                if (typeof value === 'string') return value;
                var bin = ''; for (var i = 0; i < value.length; i++) bin += String.fromCharCode(value[i]);
                if (enc === 'utf-8' || enc === 'utf8') return __bakaUtf8Decode(bin);
                try { return BakaHttp.textDecode(btoa(bin), enc); } catch (e) { return __bakaUtf8Decode(bin); }
              };
            }
            globalThis.TextEncoder = TextEncoder; globalThis.TextDecoder = TextDecoder;
            function URL(value){
              var self = this;
              self.href = String(value);
              self.protocol = (self.href.match(/^https?:/i) || [''])[0];
              self.hostname = (self.href.match(/^https?:\/\/([^/:?#]+)/i) || ['', ''])[1];
              self.host = self.hostname;
              Object.defineProperty(self, 'pathname', {configurable: true, enumerable: true,
                get: function(){ return (self.href.match(/^https?:\/\/[^/]+([^?#]*)/i) || ['', '/'])[1]; },
                set: function(p){ self.href = self.href.replace(/^((?:https?:)?\/\/[^/?#]+)[^?#]*/, function(m, o){ return o + p; }); }
              });
              self.searchParams = {set: function(key, val){
                var part = encodeURIComponent(key) + '=' + encodeURIComponent(val);
                self.href += (self.href.indexOf('?') >= 0 ? '&' : '?') + part;
              }, get: function(){ return null; }};
              self.toString = function(){ return self.href; };
            }
            globalThis.URL = URL; globalThis.CryptoJS = CryptoJs; globalThis.CryptoJs = CryptoJs;
        """.trimIndent()

        private fun moduleScripts(): Map<String, String> = mapOf(
            "axios" to """
                var axios=function(c){var cfg=c||{};var r=JSON.parse(BakaHttp.request(JSON.stringify(cfg)));if(r.__error)throw new Error(r.__error);var ok=cfg.validateStatus?cfg.validateStatus(r.status):r.status>=200&&r.status<300;if(!ok){var err=new Error('Request failed with status code '+r.status);err.response={status:r.status,data:r.data,headers:r.headers||{}};throw err;}var d=r.data;var tr=cfg.transformResponse;if(tr){var fns=Array.isArray(tr)?tr:[tr];for(var i=0;i<fns.length;i++){if(typeof fns[i]==='function')d=fns[i](d);}}if(d&&d.__base64){var raw=atob(d.__base64),u=new Uint8Array(raw.length);for(var i=0;i<raw.length;i++)u[i]=raw.charCodeAt(i);d=u;}return {data:d,status:r.status,statusText:String(r.status),headers:r.headers||{},config:cfg};};
                axios.request=axios;axios.get=function(u,c){return axios(Object.assign({},c||{},{url:u,method:'GET'}));};axios.post=function(u,d,c){return axios(Object.assign({},c||{},{url:u,method:'POST',data:d}));};axios.head=function(u,c){return axios(Object.assign({},c||{},{url:u,method:'HEAD'}));};axios.getUri=function(c){c=c||{};var u=c.url||'';var p=c.params||{};var q=Object.keys(p).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(p[k]);}).join('&');return q?(u+(u.indexOf('?')>=0?'&':'?')+q):u;};axios.all=function(values){return Promise.all(values);};axios.spread=function(fn){return function(values){return fn.apply(null,values);};};axios.default=axios;axios.create=function(def){var inst=function(c){return axios(Object.assign({},def||{},c||{}));};inst.get=function(u,c){return inst(Object.assign({},c||{},{url:u,method:'GET'}));};inst.post=function(u,d,c){return inst(Object.assign({},c||{},{url:u,method:'POST',data:d}));};inst.head=function(u,c){return inst(Object.assign({},c||{},{url:u,method:'HEAD'}));};inst.request=inst;return inst;};module.exports=axios;
            """.trimIndent(),
            "buffer" to "module.exports={Buffer:globalThis.Buffer};",
            "crypto-js" to "module.exports=globalThis.CryptoJs;",
            "big-integer" to """
                function __Big(v){ this.h=v; }
                __Big.prototype.modPow=function(e,m){
                    var eh=(e instanceof __Big)?e.h:String(e), mh=(m instanceof __Big)?m.h:String(m);
                    return new __Big(BakaHttp.modPowHex(this.h, eh, mh));
                };
                __Big.prototype.toString=function(r){ var h=String(this.h); if(h.substr(0,2)==='0x'||h.substr(0,2)==='0X')h=h.substr(2); while(h.length>1&&h.charAt(0)==='0')h=h.substr(1); return h||'0'; };
                __Big.prototype.valueOf=function(){ return this.h; };
                module.exports=function(v,base){ if(v instanceof __Big)return v; if(typeof v==='number')return new __Big(Math.floor(v).toString(16)); var s=String(v); if(s.substr(0,2)==='0x'||s.substr(0,2)==='0X')s=s.substr(2); return new __Big(s); };
            """.trimIndent(),
            "qs" to "module.exports={stringify:function(o){return Object.keys(o||{}).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(o[k]);}).join('&');}};",
            "dayjs" to """
                function __dayjs(){ return {format:function(){return '';}}; };
                __dayjs.unix=function(t){ var d=new Date(Number(t)*1000); return {format:function(f){ try{ var s=String(f||''); if(s.indexOf('YYYY')>=0&&s.indexOf('MM')>=0&&s.indexOf('DD')>=0)return d.toISOString().slice(0,10); }catch(e){} return ''; }}; };
                module.exports=__dayjs;
            """.trimIndent(),
            "he" to "module.exports={decode:function(v){return String(v);}};",
            "pako" to """
                function __pakoBytes(v){
                    if (typeof v === 'string') { var bin = __bakaUtf8Encode(v); var u = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u; }
                    var u2 = new Uint8Array(v.length); for (var j = 0; j < v.length; j++) u2[j] = v[j] & 255; return u2;
                }
                function __pakoUnpack(u){ var bin = ''; for (var i = 0; i < u.length; i++) bin += String.fromCharCode(u[i]); return bin; }
                function __pakoB64ToBytes(b64){ return __pakoBytes(atob(String(b64)).split('').map(function(c){ return c.charCodeAt(0); })); }
                module.exports = {
                    inflate: function(v, opts){
                        var out = __pakoB64ToBytes(BakaHttp.inflate(btoa(__pakoUnpack(__pakoBytes(v)))));
                        if (opts && opts.to === 'string') return __bakaUtf8Decode(__pakoUnpack(out));
                        return makeBuffer(out);
                    },
                    ungzip: function(v){ return this.inflate(v); }
                };
            """.trimIndent(),
            // cheerio 子集：支持榜单页解析所需的 load/选择器/children/find/attr/text 等，属性与文本做实体解码
            "cheerio" to """
                function __cqDecode(s){
                return String(s==null?"":s).replace(/&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);/g,function(m,e){
                if(e.charAt(0)==="#"){
                var hex=e.charAt(1)==="x"||e.charAt(1)==="X";
                var code=parseInt(hex?e.slice(2):e.slice(1),hex?16:10);
                if(isFinite(code)){
                if(code>65535){code-=65536;return String.fromCharCode(55296+(code>>10),56320+(code&1023));}
                return String.fromCharCode(code);
                }
                return m;
                }
                if(e==="amp")return "&";
                if(e==="lt")return "<";
                if(e==="gt")return ">";
                if(e==="quot")return '"';
                if(e==="apos")return "'";
                if(e==="nbsp")return String.fromCharCode(160);
                return m;
                });
                }
                var __cqVoids={area:1,base:1,br:1,col:1,embed:1,hr:1,img:1,input:1,link:1,meta:1,param:1,source:1,track:1,wbr:1};
                function __cqAttrs(s){
                var out={};
                var re=/([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
                var m;
                while((m=re.exec(s))!==null){var v=m[2]!==undefined?m[2]:(m[3]!==undefined?m[3]:(m[4]!==undefined?m[4]:""));out[m[1].toLowerCase()]=__cqDecode(v);}
                return out;
                }
                function __cqParse(html){
                var root={type:"root",name:"",tagName:"",attribs:{},children:[],parent:null};
                var stack=[root];
                var src=String(html==null?"":html);
                var re=/<!--[\s\S]*?-->|<![^>]*>|<(\/?)([a-zA-Z][a-zA-Z0-9]*)((?:"[^"]*"|'[^']*'|[^>"'])*)(\/?)>|([^<]+)/g;
                var m;var raw=null;
                while((m=re.exec(src))!==null){
                if(m[5]!==undefined){var holder=raw||stack[stack.length-1];holder.children.push({type:"text",data:m[5],parent:holder});continue;}
                if(m[2]===undefined)continue;
                var name=m[2].toLowerCase();
                if(raw){if(m[1]==="/"&&name===raw.name){raw=null;}else{raw.children.push({type:"text",data:m[0],parent:raw});}continue;}
                if(m[1]==="/"){for(var i=stack.length-1;i>0;i--){if(stack[i].name===name){stack.length=i;break;}}continue;}
                var top=stack[stack.length-1];
                var el={type:"tag",name:name,tagName:name,attribs:__cqAttrs(m[3]||""),children:[],parent:top};
                top.children.push(el);
                if(m[4]!=="/"&&!__cqVoids[name]){stack.push(el);if(name==="script"||name==="style"){raw=el;}}
                }
                return root;
                }
                function __cqMatch(el,sel){
                if(!el||el.type!=="tag")return false;
                var s=String(sel||"").replace(/^\s+|\s+$/g,"");
                if(!s)return true;
                if(s.charAt(0)==="#")return (el.attribs["id"]||"")===s.slice(1);
                var dot=s.indexOf(".");
                var tag=dot<0?s:s.slice(0,dot);
                if(tag&&el.name!==tag.toLowerCase())return false;
                if(dot<0)return true;
                var need=s.slice(dot+1).split(".");
                var cls=" "+(el.attribs["class"]|| "")+" ";
                for(var i=0;i<need.length;i++){if(need[i]&&cls.indexOf(" "+need[i]+" ")<0)return false;}
                return true;
                }
                function __cqDesc(el,sel,out){
                var kids=el.children||[];
                for(var i=0;i<kids.length;i++){var k=kids[i];if(k.type==="tag"){if(__cqMatch(k,sel))out.push(k);__cqDesc(k,sel,out);}}
                return out;
                }
                function __cqText(el){
                if(!el)return "";
                if(el.type==="text")return el.data;
                var s="";var kids=el.children||[];
                for(var i=0;i<kids.length;i++)s+=__cqText(kids[i]);
                return s;
                }
                function __cqWrap(nodes){
                var arr=[];
                for(var i=0;i<nodes.length;i++)arr.push(nodes[i]);
                arr.children=function(sel){var out=[];for(var a=0;a<arr.length;a++){var kids=arr[a].children||[];for(var b=0;b<kids.length;b++){if(kids[b].type==="tag"&&(sel==null||__cqMatch(kids[b],sel)))out.push(kids[b]);}}return __cqWrap(out);};
                arr.find=function(sel){var out=[];for(var a=0;a<arr.length;a++)__cqDesc(arr[a],sel,out);return __cqWrap(out);};
                arr.attr=function(name){for(var a=0;a<arr.length;a++){var el=arr[a];if(el&&el.type==="tag"&&el.attribs){var v=el.attribs[String(name).toLowerCase()];if(v!==undefined)return v;}}return undefined;};
                arr.text=function(){var s="";for(var a=0;a<arr.length;a++)s+=__cqText(arr[a]);return __cqDecode(s);};
                arr.each=function(fn){for(var a=0;a<arr.length;a++)fn.call(arr[a],a,arr[a]);return arr;};
                arr.map=function(fn){var out=[];for(var a=0;a<arr.length;a++){var r=fn.call(arr[a],a,arr[a]);if(r!=null){if(Object.prototype.toString.call(r)==="[object Array]"){for(var b=0;b<r.length;b++)out.push(r[b]);}else out.push(r);}}return __cqWrap(out);};
                arr.toArray=function(){var c=[];for(var a=0;a<arr.length;a++)c.push(arr[a]);return c;};
                arr.get=function(i){return i==null?arr.toArray():arr[i];};
                arr.first=function(){return __cqWrap(arr.length?[arr[0]]:[]);};
                arr.eq=function(i){var n=i<0?arr.length+i:i;return __cqWrap((n>=0&&n<arr.length)?[arr[n]]:[]);};
                return arr;
                }
                function __cqLoad(html){
                var root=__cqParse(html);
                var fn=function(sel){
                if(typeof sel==="string"){var out=[];__cqDesc(root,sel,out);return __cqWrap(out);}
                if(sel&&sel.type)return __cqWrap([sel]);
                if(Object.prototype.toString.call(sel)==="[object Array]")return __cqWrap(sel);
                return __cqWrap([]);
                };
                return fn;
                }
                module.exports={load:__cqLoad};
            """.trimIndent(),
            "@react-native-cookies/cookies" to "module.exports={get:function(){return {};},set:function(){return true;}};"
        )
    }
}
