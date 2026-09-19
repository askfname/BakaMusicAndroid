package com.bakamusic.android.util

import com.bakamusic.android.data.*
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)
data class LyricLine(val timeMs: Long, val text: String, val endMs: Long = Long.MAX_VALUE, val words: List<LyricWord> = emptyList())

object LyricParser {
    // LRC 时间戳，一行内可有多个
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    // QRC 行：[startMs,durationMs]正文(逐字时间)
    private val qrcLine = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
    // QRC/YRC 逐字时间，显示时移除
    private val wordTime = Regex("\\(\\d+,\\d+(?:,\\d+)?\\)")
    // 尖括号逐字时间，显示时移除
    private val angleWordTime = Regex("<\\d[^>]*>")
    // 数字键字符映射 {"0":"我","1":"的"}，按数字键排序还原文本
    private val charMapKey = Regex("\"(\\d+)\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
    // 元信息行 [ti:..]/[ar:..] 等，直接跳过
    private val metaTag = Regex("^\\[[a-zA-Z]+:.*$")
    // YRC JSON 行 {"t":123,"c":[{"tx":"字"}]}
    private val yrcTime = Regex("\"t\"\\s*:\\s*(\\d+)")
    private val yrcWord = Regex("\"tx\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
    // QRC 逐字：文字(绝对startMs,时值[,预留])，文字在括号前
    private val qrcWord = Regex("([^()\\[\\]]+?)\\((\\d+),(\\d+)(?:,\\d+)?\\)")
    // ESLRC/A2 尖括号逐字：<mm:ss.xxx>文字
    private val angleStamp = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    private fun angleToMs(m: MatchResult): Long {
        val minutes = m.groupValues[1].toLongOrNull() ?: 0
        val seconds = m.groupValues[2].toLongOrNull() ?: 0
        val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
        return minutes * 60_000 + seconds * 1_000 + fraction
    }
    /** QRC 行内逐字切分，失败返回空（调用方回退为整行）。 */
    private fun parseQrcWords(body: String, lineEndMs: Long): List<LyricWord> {
        val matches = qrcWord.findAll(body).toList().filter { it.groupValues[1].isNotEmpty() }
        if (matches.isEmpty()) return emptyList()
        val words = matches.mapNotNull { m ->
            val text = m.groupValues[1]
            if (text.isEmpty()) return@mapNotNull null
            val start = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val dur = m.groupValues[3].toLongOrNull() ?: 0
            LyricWord(text, start.coerceAtLeast(0), (start + dur).coerceAtLeast(start))
        }
        if (words.isEmpty()) return emptyList()
        // 行尾对齐：末字时值缺失/为 0 时延长到行尾，避免卡拉 OK 提前停住
        return words.mapIndexed { i, w ->
            if (i == words.lastIndex && w.endMs <= w.startMs) w.copy(endMs = lineEndMs.coerceAtLeast(w.startMs + 320)) else w
        }
    }
    /** ESLRC/A2 行内尖括号逐字切分，失败返回空。 */
    private fun parseAngleWords(body: String, lineEndMs: Long): List<LyricWord> {
        val tags = angleStamp.findAll(body).toList()
        if (tags.isEmpty()) return emptyList()
        val words = mutableListOf<LyricWord>()
        tags.forEachIndexed { i, tag ->
            val start = angleToMs(tag)
            val textStart = tag.range.last + 1
            val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            val text = body.substring(textStart.coerceIn(0, body.length), textEnd.coerceIn(0, body.length))
            // 末尾悬空时间戳（如酷我行尾补的结束戳）无后随文字则作为上一字结尾
            if (text.isEmpty()) {
                if (words.isNotEmpty()) {
                    val last = words.removeAt(words.lastIndex)
                    words.add(last.copy(endMs = start))
                }
                return@forEachIndexed
            }
            val end = if (i + 1 < tags.size) angleToMs(tags[i + 1]) else lineEndMs
            words.add(LyricWord(text, start, end.coerceAtLeast(start + 120)))
        }
        return words
    }
    // 相对偏移逐字（咪咕 lxlyric 等）：<行内偏移ms,时值ms>文字，相对行首
    private val relativeAngleWord = Regex("<(\\d+),(\\d+)>")
    /** 相对偏移逐字切分，失败返回空。 */
    private fun parseRelativeAngleWords(body: String, lineStartMs: Long, lineEndMs: Long): List<LyricWord> {
        val tags = relativeAngleWord.findAll(body).toList()
        if (tags.isEmpty()) return emptyList()
        val words = mutableListOf<LyricWord>()
        tags.forEachIndexed { i, tag ->
            val start = lineStartMs + (tag.groupValues[1].toLongOrNull() ?: return@forEachIndexed)
            val dur = tag.groupValues[2].toLongOrNull() ?: 0
            val textStart = tag.range.last + 1
            val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
            val text = body.substring(textStart.coerceIn(0, body.length), textEnd.coerceIn(0, body.length))
            if (text.isEmpty()) return@forEachIndexed
            val end = (start + dur).coerceAtMost(lineEndMs)
            words.add(LyricWord(text, start.coerceAtLeast(lineStartMs), end.coerceAtLeast(start + 120)))
        }
        return words
    }
    /** 去掉行内全部时间戳/逐字戳，返回干净正文。 */
    private fun cleanText(raw: String): String {
        var text = timestamp.replace(raw, "")
        text = angleWordTime.replace(text, "")
        text = wordTime.replace(text, "")
        text = text.trim()
        // [ts]{"0":"我","1":"的"} 或裸 {"0":..}：按数字键排序拼接取值
        if (text.startsWith("{") && text.endsWith("}")) {
            val chars = charMapKey.findAll(text).toList()
            if (chars.isNotEmpty() && text.contains("\"0\"")) {
                return chars.sortedBy { it.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE }
                    .joinToString("") { it.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\") }
                    .trim()
            }
        }
        return text
    }

    fun parse(source: LyricSource?): List<LyricLine> {
        val escaped = source?.rawLrc?.replace("\r", "")?.replace("\\n", "\n")?.replace("\\r", "\n").orEmpty()
        if (escaped.isBlank()) return emptyList()
        // 与桌面端 autoDecryptLyric 一致：QQ 等来源的 QRC 加密串/QRC XML 先还原为富 QRC 再解析
        val raw = LyricDecrypt.autoDecryptLyric(escaped)?.replace("\r", "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val parsed = mutableListOf<LyricLine>()
        val plainFallback = mutableListOf<String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || metaTag.matches(trimmed)) return@forEach
            // 1) 标准 LRC（含一行多时间戳）：行内若带逐字戳则一并保留
            val stamps = timestamp.findAll(trimmed).toList()
            if (stamps.isNotEmpty()) {
                val body = trimmed.substring((stamps.last().range.last + 1).coerceIn(0, trimmed.length))
                val minutes0 = stamps.last().groupValues[1].toLongOrNull() ?: 0
                val seconds0 = stamps.last().groupValues[2].toLongOrNull() ?: 0
                val fraction0 = stamps.last().groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                val lineStart0 = minutes0 * 60_000 + seconds0 * 1_000 + fraction0
                val angleWords = if (body.contains('<') && angleStamp.containsMatchIn(body)) {
                    // 行首时间未知时先按 0 占位，排序后按行首/下一行修正
                    parseAngleWords(body, Long.MAX_VALUE)
                } else emptyList()
                val qrcWords = if (angleWords.isEmpty() && body.contains('(')) parseQrcWords(body, Long.MAX_VALUE) else emptyList()
                val relWords = if (angleWords.isEmpty() && qrcWords.isEmpty() && body.contains('<')) {
                    parseRelativeAngleWords(body, lineStart0, Long.MAX_VALUE)
                } else emptyList()
                val words = if (angleWords.isNotEmpty()) angleWords else if (qrcWords.isNotEmpty()) qrcWords else relWords
                val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim()
                    .takeIf { it.isNotEmpty() } ?: cleanText(trimmed) else cleanText(trimmed)
                stamps.forEach { m ->
                    val minutes = m.groupValues[1].toLongOrNull() ?: return@forEach
                    val seconds = m.groupValues[2].toLongOrNull() ?: return@forEach
                    val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    val start = minutes * 60_000 + seconds * 1_000 + fraction
                    // 尖括号时间为绝对时间可直接用；QRC 式 (ms,ms) 已是绝对时间，直接用
                    val shifted = if (words.isNotEmpty() && words.first().startMs < start - 5000) {
                        val delta = start - words.first().startMs
                        words.map { it.copy(startMs = it.startMs + delta, endMs = if (it.endMs == Long.MAX_VALUE) Long.MAX_VALUE else it.endMs + delta) }
                    } else words
                    parsed.add(LyricLine(start, text, words = shifted))
                }
                return@forEach
            }
            // 2) QRC 行 [startMs,durMs]文字(startMs,durMs)…
            qrcLine.matchEntire(trimmed)?.let { m ->
                val start = m.groupValues[1].toLongOrNull() ?: return@forEach
                val dur = m.groupValues[2].toLongOrNull() ?: 3000
                val body = m.groupValues[3]
                val words = parseQrcWords(body, start + dur)
                val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim()
                    .takeIf { it.isNotEmpty() } ?: cleanText(body) else cleanText(body)
                if (text.isNotEmpty()) parsed.add(LyricLine(start, text, start + dur, words))
                return@forEach
            }
            // 3) YRC JSON 行：拼接 tx 字段为正文
            if (trimmed.startsWith("{\"") && trimmed.endsWith("}")) {
                val t = yrcTime.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
                if (t != null) {
                    val text = cleanText(yrcWord.findAll(trimmed).joinToString("") {
                        it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
                    })
                    if (text.isNotEmpty()) parsed.add(LyricLine(t, text))
                    return@forEach
                }
                val mapped = cleanText(trimmed)
                if (mapped.isNotEmpty() && mapped != trimmed) {
                    plainFallback.add(mapped)
                    return@forEach
                }
                // 无法解读的裸 JSON 行直接丢弃
                return@forEach
            }
            // 4) 纯文本行：暂存，仅当全篇无时间戳时作为后备；
            // 解密失败残留的 QRC 十六进制串（超长无空格纯 hex）直接丢弃，避免整屏乱码
            val text = cleanText(trimmed).takeIf { it.isNotEmpty() } ?: return@forEach
            if (text.length > 500 && text.none { it.isWhitespace() } && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return@forEach
            if (!text.startsWith("[") || !text.endsWith("]")) plainFallback.add(text)
        }
        if (parsed.isNotEmpty()) {
            val sorted = parsed.sortedBy { it.timeMs }
            // 回填行尾/字尾：无声明时截断到下一行行首，避免高亮拖尾、滚动提前量错位
            return sorted.mapIndexed { i, l ->
                val nextStart = sorted.getOrNull(i + 1)?.timeMs
                val end = when {
                    l.endMs != Long.MAX_VALUE && (nextStart == null || l.endMs <= nextStart) -> l.endMs
                    nextStart != null -> nextStart
                    l.endMs != Long.MAX_VALUE -> l.endMs
                    else -> l.timeMs + 8000
                }.coerceAtLeast(l.timeMs + 320)
                val words = if (l.words.isEmpty()) emptyList() else l.words.mapIndexed { wi, w ->
                    val wEnd = if (wi == l.words.lastIndex) {
                        if (w.endMs == Long.MAX_VALUE || w.endMs > end) end else w.endMs
                    } else {
                        val nStart = l.words[wi + 1].startMs
                        // 后字提前（插件相对/绝对时间混用）时把前字截断到后字开头
                        if (w.endMs > nStart) nStart else w.endMs
                    }
                    w.copy(endMs = wEnd.coerceAtLeast(w.startMs + 120))
                }
                l.copy(endMs = end, words = words)
            }
        }
        // 全篇无时间戳：按行递增分配时间戳，确保可展示
        return plainFallback.mapIndexed { index, text ->
            val s = index * 3000L
            LyricLine(s, text, s + 3000, emptyList())
        }
    }
}
