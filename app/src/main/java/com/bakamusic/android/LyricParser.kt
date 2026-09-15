package com.bakamusic.android

data class LyricLine(val timeMs: Long, val text: String)

object LyricParser {
    // LRC 时间戳，一行内可有多个
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    // QRC 行：[startMs,durationMs]正文(逐字时间)
    private val qrcLine = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
    // QRC/YRC 逐字时间，显示时剥掉
    private val wordTime = Regex("\\(\\d+,\\d+(?:,\\d+)?\\)")
    // 尖括号逐字时间，显示时剥掉
    private val angleWordTime = Regex("<\\d[^>]*>")
    // 数字键字符映射 {"0":"我","1":"的"}，按数字键拼回文本
    private val charMapKey = Regex("\"(\\d+)\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
    // 元信息行 [ti:..]/[ar:..] 等，直接跳过
    private val metaTag = Regex("^\\[[a-zA-Z]+:.*$")
    // YRC JSON 行 {"t":123,"c":[{"tx":"字"}]}
    private val yrcTime = Regex("\"t\"\\s*:\\s*(\\d+)")
    private val yrcWord = Regex("\"tx\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
    /** 去掉行内全部时间戳/逐字戳，返回干净正文。 */
    private fun cleanText(raw: String): String {
        var text = timestamp.replace(raw, "")
        text = angleWordTime.replace(text, "")
        text = wordTime.replace(text, "")
        text = text.trim()
        // [ts]{"0":"我","1":"的"} 或裸 {"0":..}：按数字键排序拼值
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
        val raw = source?.rawLrc?.replace("\r", "")?.replace("\\n", "\n")?.replace("\\r", "\n").orEmpty()
        if (raw.isBlank()) return emptyList()
        val parsed = mutableListOf<LyricLine>()
        val plainFallback = mutableListOf<String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || metaTag.matches(trimmed)) return@forEach
            // 1) 标准 LRC（含一行多时间戳）
            val stamps = timestamp.findAll(trimmed).toList()
            if (stamps.isNotEmpty()) {
                val text = cleanText(trimmed)
                stamps.forEach { m ->
                    val minutes = m.groupValues[1].toLongOrNull() ?: return@forEach
                    val seconds = m.groupValues[2].toLongOrNull() ?: return@forEach
                    val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    parsed.add(LyricLine(minutes * 60_000 + seconds * 1_000 + fraction, text))
                }
                return@forEach
            }
            // 2) QRC 行 [ms,dur]
            qrcLine.matchEntire(trimmed)?.let { m ->
                val start = m.groupValues[1].toLongOrNull() ?: return@forEach
                val text = cleanText(m.groupValues[3])
                if (text.isNotEmpty()) parsed.add(LyricLine(start, text))
                return@forEach
            }
            // 3) YRC JSON 行：拼 tx 为正文
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
            // 4) 纯文本行：先收起来，仅当全篇无时间戳时兜底
            val text = cleanText(trimmed).takeIf { it.isNotEmpty() } ?: return@forEach
            if (!text.startsWith("[") || !text.endsWith("]")) plainFallback.add(text)
        }
        if (parsed.isNotEmpty()) return parsed.sortedBy { it.timeMs }
        // 全篇无时间戳：按行递增给时间，保证可展示
        return plainFallback.mapIndexed { index, text -> LyricLine(index * 3000L, text) }
    }
}
