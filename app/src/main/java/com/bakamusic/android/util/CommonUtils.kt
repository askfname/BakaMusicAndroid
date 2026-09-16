package com.bakamusic.android.util

import java.util.Locale

fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0) return "0:00"
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

fun formatSize(bytes: Long): String =
    if (bytes <= 0) "未知大小"
    else if (bytes < 1024 * 1024) "${bytes / 1024} KB"
    else "%.1f MB".format(bytes / 1024f / 1024f)
