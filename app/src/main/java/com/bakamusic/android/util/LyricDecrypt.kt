package com.bakamusic.android.util

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 歌词解密：QRC 加密十六进制串或 QRC XML
 */
object LyricDecrypt {

    // QQ 客户端三 DES 密钥（与桌面端 KEY1/2/3 一致，纯 ASCII）
    private val KEY1 = "!@#)(NHLiuy*$%^&".toByteArray(Charsets.UTF_8)
    private val KEY2 = "123ZXC!@#)(*$%^&".toByteArray(Charsets.UTF_8)
    private val KEY3 = "!@#)(*$%^&abcDEF".toByteArray(Charsets.UTF_8)

    private val S_BOX1 = intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
    )
    private val S_BOX2 = intArrayOf(
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
    )
    private val S_BOX3 = intArrayOf(
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
    )
    private val S_BOX4 = intArrayOf(
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
    )
    private val S_BOX5 = intArrayOf(
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
    )
    private val S_BOX6 = intArrayOf(
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
    )
    private val S_BOX7 = intArrayOf(
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
    )
    private val S_BOX8 = intArrayOf(
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
    )

    private const val DES_ENCRYPT = 0
    private const val DES_DECRYPT = 1

    private const val MAX_INPUT_LENGTH = 2 * 1024 * 1024
    private const val MAX_DECOMPRESSED_LENGTH = 5 * 1024 * 1024

    private fun bitNum(a: ByteArray, b: Int, c: Int): Int {
        val byteIndex = b / 32 * 4 + 3 - (b % 32) / 8
        val bitPosition = 7 - (b % 8)
        val extractedBit = ((a[byteIndex].toInt() and 0xFF) shr bitPosition) and 0x01
        return extractedBit shl c
    }

    private fun bitNumIntR(a: Int, b: Int, c: Int): Int {
        val extractedBit = (a ushr (31 - b)) and 0x00000001
        return extractedBit shl c
    }

    private fun bitNumIntL(a: Int, b: Int, c: Int): Int {
        val extractedBit = (a shl b) and Int.MIN_VALUE
        return extractedBit ushr c
    }

    private fun sBoxBit(a: Int): Int = (a and 0x20) or ((a and 0x1f) shr 1) or ((a and 0x01) shl 4)

    private fun ipPermutation(state: IntArray, inBytes: ByteArray) {
        state[0] = (
            bitNum(inBytes, 57, 31) or bitNum(inBytes, 49, 30) or bitNum(inBytes, 41, 29) or
                bitNum(inBytes, 33, 28) or bitNum(inBytes, 25, 27) or bitNum(inBytes, 17, 26) or
                bitNum(inBytes, 9, 25) or bitNum(inBytes, 1, 24) or bitNum(inBytes, 59, 23) or
                bitNum(inBytes, 51, 22) or bitNum(inBytes, 43, 21) or bitNum(inBytes, 35, 20) or
                bitNum(inBytes, 27, 19) or bitNum(inBytes, 19, 18) or bitNum(inBytes, 11, 17) or
                bitNum(inBytes, 3, 16) or bitNum(inBytes, 61, 15) or bitNum(inBytes, 53, 14) or
                bitNum(inBytes, 45, 13) or bitNum(inBytes, 37, 12) or bitNum(inBytes, 29, 11) or
                bitNum(inBytes, 21, 10) or bitNum(inBytes, 13, 9) or bitNum(inBytes, 5, 8) or
                bitNum(inBytes, 63, 7) or bitNum(inBytes, 55, 6) or bitNum(inBytes, 47, 5) or
                bitNum(inBytes, 39, 4) or bitNum(inBytes, 31, 3) or bitNum(inBytes, 23, 2) or
                bitNum(inBytes, 15, 1) or bitNum(inBytes, 7, 0)
            )
        state[1] = (
            bitNum(inBytes, 56, 31) or bitNum(inBytes, 48, 30) or bitNum(inBytes, 40, 29) or
                bitNum(inBytes, 32, 28) or bitNum(inBytes, 24, 27) or bitNum(inBytes, 16, 26) or
                bitNum(inBytes, 8, 25) or bitNum(inBytes, 0, 24) or bitNum(inBytes, 58, 23) or
                bitNum(inBytes, 50, 22) or bitNum(inBytes, 42, 21) or bitNum(inBytes, 34, 20) or
                bitNum(inBytes, 26, 19) or bitNum(inBytes, 18, 18) or bitNum(inBytes, 10, 17) or
                bitNum(inBytes, 2, 16) or bitNum(inBytes, 60, 15) or bitNum(inBytes, 52, 14) or
                bitNum(inBytes, 44, 13) or bitNum(inBytes, 36, 12) or bitNum(inBytes, 28, 11) or
                bitNum(inBytes, 20, 10) or bitNum(inBytes, 12, 9) or bitNum(inBytes, 4, 8) or
                bitNum(inBytes, 62, 7) or bitNum(inBytes, 54, 6) or bitNum(inBytes, 46, 5) or
                bitNum(inBytes, 38, 4) or bitNum(inBytes, 30, 3) or bitNum(inBytes, 22, 2) or
                bitNum(inBytes, 14, 1) or bitNum(inBytes, 6, 0)
            )
    }

    private fun invIp(state: IntArray, inBytes: ByteArray) {
        inBytes[3] = (bitNumIntR(state[1], 7, 7) or bitNumIntR(state[0], 7, 6) or
            bitNumIntR(state[1], 15, 5) or bitNumIntR(state[0], 15, 4) or
            bitNumIntR(state[1], 23, 3) or bitNumIntR(state[0], 23, 2) or
            bitNumIntR(state[1], 31, 1) or bitNumIntR(state[0], 31, 0)).toByte()
        inBytes[2] = (bitNumIntR(state[1], 6, 7) or bitNumIntR(state[0], 6, 6) or
            bitNumIntR(state[1], 14, 5) or bitNumIntR(state[0], 14, 4) or
            bitNumIntR(state[1], 22, 3) or bitNumIntR(state[0], 22, 2) or
            bitNumIntR(state[1], 30, 1) or bitNumIntR(state[0], 30, 0)).toByte()
        inBytes[1] = (bitNumIntR(state[1], 5, 7) or bitNumIntR(state[0], 5, 6) or
            bitNumIntR(state[1], 13, 5) or bitNumIntR(state[0], 13, 4) or
            bitNumIntR(state[1], 21, 3) or bitNumIntR(state[0], 21, 2) or
            bitNumIntR(state[1], 29, 1) or bitNumIntR(state[0], 29, 0)).toByte()
        inBytes[0] = (bitNumIntR(state[1], 4, 7) or bitNumIntR(state[0], 4, 6) or
            bitNumIntR(state[1], 12, 5) or bitNumIntR(state[0], 12, 4) or
            bitNumIntR(state[1], 20, 3) or bitNumIntR(state[0], 20, 2) or
            bitNumIntR(state[1], 28, 1) or bitNumIntR(state[0], 28, 0)).toByte()
        inBytes[7] = (bitNumIntR(state[1], 3, 7) or bitNumIntR(state[0], 3, 6) or
            bitNumIntR(state[1], 11, 5) or bitNumIntR(state[0], 11, 4) or
            bitNumIntR(state[1], 19, 3) or bitNumIntR(state[0], 19, 2) or
            bitNumIntR(state[1], 27, 1) or bitNumIntR(state[0], 27, 0)).toByte()
        inBytes[6] = (bitNumIntR(state[1], 2, 7) or bitNumIntR(state[0], 2, 6) or
            bitNumIntR(state[1], 10, 5) or bitNumIntR(state[0], 10, 4) or
            bitNumIntR(state[1], 18, 3) or bitNumIntR(state[0], 18, 2) or
            bitNumIntR(state[1], 26, 1) or bitNumIntR(state[0], 26, 0)).toByte()
        inBytes[5] = (bitNumIntR(state[1], 1, 7) or bitNumIntR(state[0], 1, 6) or
            bitNumIntR(state[1], 9, 5) or bitNumIntR(state[0], 9, 4) or
            bitNumIntR(state[1], 17, 3) or bitNumIntR(state[0], 17, 2) or
            bitNumIntR(state[1], 25, 1) or bitNumIntR(state[0], 25, 0)).toByte()
        inBytes[4] = (bitNumIntR(state[1], 0, 7) or bitNumIntR(state[0], 0, 6) or
            bitNumIntR(state[1], 8, 5) or bitNumIntR(state[0], 8, 4) or
            bitNumIntR(state[1], 16, 3) or bitNumIntR(state[0], 16, 2) or
            bitNumIntR(state[1], 24, 1) or bitNumIntR(state[0], 24, 0)).toByte()
    }

    private fun feistelF(state: Int, key: ByteArray): Int {
        val lrg = ByteArray(6)
        var s = state
        val t1 = (
            bitNumIntL(s, 31, 0) or ((s and -0x10000000) ushr 1) or bitNumIntL(s, 4, 5) or
                bitNumIntL(s, 3, 6) or ((s and 0x0f000000) ushr 3) or bitNumIntL(s, 8, 11) or
                bitNumIntL(s, 7, 12) or ((s and 0x00f00000) ushr 5) or bitNumIntL(s, 12, 17) or
                bitNumIntL(s, 11, 18) or ((s and 0x000f0000) ushr 7) or bitNumIntL(s, 16, 23)
            )
        val t2 = (
            bitNumIntL(s, 15, 0) or ((s and 0x0000f000) shl 15) or bitNumIntL(s, 20, 5) or
                bitNumIntL(s, 19, 6) or ((s and 0x00000f00) shl 13) or bitNumIntL(s, 24, 11) or
                bitNumIntL(s, 23, 12) or ((s and 0x000000f0) shl 11) or bitNumIntL(s, 28, 17) or
                bitNumIntL(s, 27, 18) or ((s and 0x0000000f) shl 9) or bitNumIntL(s, 0, 23)
            )
        lrg[0] = (t1 ushr 24).toByte()
        lrg[1] = (t1 ushr 16).toByte()
        lrg[2] = (t1 ushr 8).toByte()
        lrg[3] = (t2 ushr 24).toByte()
        lrg[4] = (t2 ushr 16).toByte()
        lrg[5] = (t2 ushr 8).toByte()
        for (i in 0 until 6) lrg[i] = (lrg[i]. toInt() xor key[i].toInt()).toByte()
        val b0 = lrg[0].toInt() and 0xFF
        val b1 = lrg[1].toInt() and 0xFF
        val b2 = lrg[2].toInt() and 0xFF
        val b3 = lrg[3].toInt() and 0xFF
        val b4 = lrg[4].toInt() and 0xFF
        val b5 = lrg[5].toInt() and 0xFF
        s = (
            (S_BOX1[sBoxBit(b0 shr 2)] shl 28) or
                (S_BOX2[sBoxBit(((b0 and 0x03) shl 4) or (b1 shr 4))] shl 24) or
                (S_BOX3[sBoxBit(((b1 and 0x0f) shl 2) or (b2 shr 6))] shl 20) or
                (S_BOX4[sBoxBit(b2 and 0x3f)] shl 16) or
                (S_BOX5[sBoxBit(b3 shr 2)] shl 12) or
                (S_BOX6[sBoxBit(((b3 and 0x03) shl 4) or (b4 shr 4))] shl 8) or
                (S_BOX7[sBoxBit(((b4 and 0x0f) shl 2) or (b5 shr 6))] shl 4) or
                S_BOX8[sBoxBit(b5 and 0x3f)]
            )
        s = (
            bitNumIntL(s, 15, 0) or bitNumIntL(s, 6, 1) or bitNumIntL(s, 19, 2) or
                bitNumIntL(s, 20, 3) or bitNumIntL(s, 28, 4) or bitNumIntL(s, 11, 5) or
                bitNumIntL(s, 27, 6) or bitNumIntL(s, 16, 7) or bitNumIntL(s, 0, 8) or
                bitNumIntL(s, 14, 9) or bitNumIntL(s, 22, 10) or bitNumIntL(s, 25, 11) or
                bitNumIntL(s, 4, 12) or bitNumIntL(s, 17, 13) or bitNumIntL(s, 30, 14) or
                bitNumIntL(s, 9, 15) or bitNumIntL(s, 1, 16) or bitNumIntL(s, 7, 17) or
                bitNumIntL(s, 23, 18) or bitNumIntL(s, 13, 19) or bitNumIntL(s, 31, 20) or
                bitNumIntL(s, 26, 21) or bitNumIntL(s, 2, 22) or bitNumIntL(s, 8, 23) or
                bitNumIntL(s, 18, 24) or bitNumIntL(s, 12, 25) or bitNumIntL(s, 29, 26) or
                bitNumIntL(s, 5, 27) or bitNumIntL(s, 21, 28) or bitNumIntL(s, 10, 29) or
                bitNumIntL(s, 3, 30) or bitNumIntL(s, 24, 31)
            )
        return s
    }

    private fun desKeySetup(key: ByteArray, schedule: Array<ByteArray>, mode: Int) {
        val keyRndShift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
        val keyPermC = intArrayOf(
            56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
            9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35
        )
        val keyPermD = intArrayOf(
            62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
            13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3
        )
        val keyCompression = intArrayOf(
            13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9,
            22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
            40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
            43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
        )
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or bitNum(key, keyPermC[i], 31 - i)
            d = d or bitNum(key, keyPermD[i], 31 - i)
        }
        for (i in 0 until 16) {
            c = (((c shl keyRndShift[i]) or (c ushr (28 - keyRndShift[i]))) and -16)
            d = (((d shl keyRndShift[i]) or (d ushr (28 - keyRndShift[i]))) and -16)
            val toGen = if (mode == DES_DECRYPT) 15 - i else i
            schedule[toGen] = ByteArray(6)
            for (j in 0 until 24) {
                val idx = j / 8
                schedule[toGen][idx] = (schedule[toGen][idx].toInt() or bitNumIntR(c, keyCompression[j], 7 - (j % 8))).toByte()
            }
            for (j in 24 until 48) {
                val idx = j / 8
                schedule[toGen][idx] = (schedule[toGen][idx].toInt() or bitNumIntR(d, keyCompression[j] - 27, 7 - (j % 8))).toByte()
            }
        }
    }

    private fun desCrypt(input: ByteArray, keySchedule: Array<ByteArray>): ByteArray {
        val state = IntArray(2)
        ipPermutation(state, input)
        for (idx in 0 until 15) {
            val t = state[1]
            state[1] = feistelF(state[1], keySchedule[idx]) xor state[0]
            state[0] = t
        }
        state[0] = feistelF(state[1], keySchedule[15]) xor state[0]
        invIp(state, input)
        return input
    }

    private fun funcDes(buff: ByteArray, key: ByteArray, length: Int): ByteArray {
        val schedule = Array(16) { ByteArray(6) }
        desKeySetup(key, schedule, DES_ENCRYPT)
        val output = ByteArray(length)
        var i = 0
        while (i < length) {
            val block = buff.copyOfRange(i, minOf(i + 8, buff.size))
            desCrypt(block, schedule)
            block.copyInto(output, i, 0, minOf(8, length - i))
            i += 8
        }
        return output
    }

    private fun funcDdes(buff: ByteArray, key: ByteArray, length: Int): ByteArray {
        val schedule = Array(16) { ByteArray(6) }
        desKeySetup(key, schedule, DES_DECRYPT)
        val output = ByteArray(length)
        var i = 0
        while (i < length) {
            val block = buff.copyOfRange(i, minOf(i + 8, buff.size))
            desCrypt(block, schedule)
            block.copyInto(output, i, 0, minOf(8, length - i))
            i += 8
        }
        return output
    }

    /** QRC 解码：KEY1 解密 → KEY2 加密 → KEY3 解密（与桌面端 lyricDecode 一致）。 */
    private fun lyricDecode(content: ByteArray): ByteArray {
        val length = content.size
        var result = funcDdes(content, KEY1, length)
        result = funcDes(result, KEY2, length)
        result = funcDdes(result, KEY3, length)
        return result
    }

    private fun safeInflate(data: ByteArray): ByteArray {
        if (data.size > MAX_INPUT_LENGTH) throw IllegalArgumentException("Input too large for decompression")
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || out.size() > MAX_DECOMPRESSED_LENGTH) break
                    // 无进展且未结束：避免死循环
                    if (inflater.needsDictionary()) break
                    break
                }
                out.write(buf, 0, n)
                if (out.size() > MAX_DECOMPRESSED_LENGTH) throw IllegalArgumentException("Decompressed output exceeds safety limit")
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private val lrcTimestampHint = Regex("\\[\\d{2}:\\d{2}[.:]\\d{2,3}\\]")

    fun isQRCEncrypted(lyrics: String): Boolean {
        if (lyrics.isEmpty()) return false
        val trimmed = lyrics.trim()
        if (trimmed.length < 32) return false
        if (trimmed.length % 16 != 0) return false
        if (!trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        if (lrcTimestampHint.containsMatchIn(trimmed)) return false
        return true
    }

    fun isQrcXml(text: String): Boolean {
        if (text.isEmpty()) return false
        return text.contains("<?xml") && text.contains("LyricContent")
    }

    private val qrcLineSplit = Regex("\\[(\\d+),(\\d+)\\]([\\s\\S]*?)(?=\\[\\d+,\\d+\\]|$)")
    private val qrcWordKeep = Regex("([^()]*?)\\((\\d+),(\\d+)\\)")
    private val kanaTag = Regex("\\[kana:.*?\\]")

    /**
     * QRC XML → 保留逐字时间轴的富 QRC，与桌面端 convertQrcXmlToRichQrc 一致：
     * [行首ms,行时值]文字(字首ms,字时值)…
     */
    fun convertQrcXmlToRichQrc(xml: String): String {
        val lines = mutableListOf<String>()
        qrcLineSplit.findAll(xml).forEach { lm ->
            val lineStart = lm.groupValues[1]
            val lineDur = lm.groupValues[2]
            val body = kanaTag.replace(lm.groupValues[3], "")
            val chunks = StringBuilder()
            qrcWordKeep.findAll(body).forEach { wm ->
                chunks.append(wm.groupValues[1]).append('(').append(wm.groupValues[2]).append(',').append(wm.groupValues[3]).append(')')
            }
            val lineText = chunks.toString()
            if (wordTimeStrip.replace(lineText, "").trim().isNotEmpty()) {
                lines.add("[$lineStart,$lineDur]$lineText")
            }
        }
        return lines.joinToString("\n")
    }

    private val wordTimeStrip = Regex("\\(\\d+,\\d+\\)")

    private fun decryptQRCLyric(encryptedHex: String): String {
        val trimmed = encryptedHex.trim()
        val bytes = ByteArray(trimmed.length / 2) { i ->
            trimmed.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val decrypted = lyricDecode(bytes)
        val decompressed = safeInflate(decrypted)
        return decompressed.toString(Charsets.UTF_8)
    }

    /**
     * 与桌面端 autoDecryptLyric 一致：加密串解密、QRC XML 转富 QRC，
     * 普通 LRC/纯文本原样返回；失败时返回原文（桌面端 catch 后返回原文）。
     */
    fun autoDecryptLyric(lyrics: String?): String? {
        if (lyrics.isNullOrEmpty()) return lyrics
        if (isQRCEncrypted(lyrics)) {
            return try {
                val decrypted = decryptQRCLyric(lyrics)
                if (isQrcXml(decrypted)) convertQrcXmlToRichQrc(decrypted) else decrypted
            } catch (_: Exception) {
                lyrics
            }
        }
        if (isQrcXml(lyrics)) return convertQrcXmlToRichQrc(lyrics)
        return lyrics
    }
}
