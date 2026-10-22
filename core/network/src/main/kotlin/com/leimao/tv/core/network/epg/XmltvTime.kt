package com.leimao.tv.core.network.epg

import java.util.Calendar
import java.util.TimeZone

/**
 * XMLTV 的时间格式：`20261022190000 +0800`。
 *
 * 两个坑：
 *
 * 1. **时区是必读的**。同一个字符串不带时区时按 UTC 解释会整体偏 8 小时，
 *    「现在播什么」直接错一档；节目单文件里 `+0800` 几乎总是存在，所以缺省按 UTC 处理；
 * 2. 不能用 `java.time` —— minSdk 是 21，那套 API 要开 desugaring。这里用
 *    `Calendar` + 显式时区，行为在所有 API 级别上一致，单测也能在 JVM 上跑。
 */
object XmltvTime {
    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()

        val digitsEnd = text.indexOfFirst { !it.isDigit() }.let { if (it < 0) text.length else it }
        val digits = text.substring(0, digitsEnd)
        if (digits.length < 14) return null

        val year = digits.substring(0, 4).toIntOrNull() ?: return null
        val month = digits.substring(4, 6).toIntOrNull() ?: return null
        val day = digits.substring(6, 8).toIntOrNull() ?: return null
        val hour = digits.substring(8, 10).toIntOrNull() ?: return null
        val minute = digits.substring(10, 12).toIntOrNull() ?: return null
        val second = digits.substring(12, 14).toIntOrNull() ?: return null

        val zone = timeZoneOf(text.substring(digitsEnd).trim())

        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
    }

    /** `+0800` / `+08:00` / `Z` / `-0500` → TimeZone；认不出来就当 UTC */
    private fun timeZoneOf(offset: String): TimeZone {
        if (offset.isEmpty()) return UTC
        if (offset.equals("Z", ignoreCase = true)) return UTC

        val sign =
            when (offset.first()) {
                '+' -> 1
                '-' -> -1
                else -> return UTC
            }
        val body = offset.drop(1).replace(":", "")
        if (body.length < 4) return UTC

        val hours = body.substring(0, 2).toIntOrNull() ?: return UTC
        val minutes = body.substring(2, 4).toIntOrNull() ?: return UTC
        val totalMinutes = sign * (hours * 60 + minutes)
        val id = String.format("GMT%+03d:%02d", totalMinutes / 60, kotlin.math.abs(totalMinutes % 60))
        return TimeZone.getTimeZone(id)
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("GMT")
}
