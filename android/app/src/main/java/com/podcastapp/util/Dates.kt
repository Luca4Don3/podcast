package com.podcastapp.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 日期时间兼容工具：不依赖 java.time（minSdk < 26 也能用），
 * 统一按 UTC 解析服务端 ISO-8601 时间，再按本地时区展示。
 */
object Dates {

    private val isoFormats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    /** 当前时间的 ISO-8601 UTC 字符串，用于 progress.playedAt。 */
    fun nowIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** 解析服务端时间；失败返回 null。 */
    fun parseIso(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        for (pattern in isoFormats) {
            val fmt = SimpleDateFormat(pattern, Locale.US)
            fmt.isLenient = false
            fmt.timeZone = if (pattern.endsWith("'Z'") || !pattern.contains("XXX")) {
                TimeZone.getTimeZone("UTC")
            } else {
                TimeZone.getDefault()
            }
            try {
                return fmt.parse(raw)
            } catch (_: Exception) {
                // 尝试下一个格式
            }
        }
        return null
    }

    /** 只取日期部分（本地时区）。 */
    fun formatDate(raw: String?): String {
        val date = parseIso(raw) ?: return raw?.take(10).orEmpty()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(date)
    }

    /** 相对时间：今天 / 昨天 / 3 天前 / 2024-10-14。 */
    fun formatRelative(raw: String?): String {
        val date = parseIso(raw) ?: return raw?.take(10).orEmpty()
        val diff = System.currentTimeMillis() - date.time
        val day = 24L * 60 * 60 * 1000
        return when {
            diff < 0 -> formatDate(raw)
            diff < day -> "今天"
            diff < 2 * day -> "昨天"
            diff < 7 * day -> (diff / day).toString() + " 天前"
            else -> formatDate(raw)
        }
    }

    /** 毫秒 -> 1:02:03 / 12:34（本地化数字，部分语言会改写数字字形）。 */
    fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return "--:--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val locale = Locale.getDefault()
        return if (h > 0) {
            String.format(locale, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(locale, "%d:%02d", m, s)
        }
    }

    fun formatDurationMs(ms: Long): String = formatDuration((ms / 1000).toInt())

    /** 字节数 -> 12.3 MB。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }
}
