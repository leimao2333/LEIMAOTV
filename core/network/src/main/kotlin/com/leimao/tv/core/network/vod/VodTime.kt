package com.leimao.tv.core.network.vod

import java.util.Calendar
import java.util.TimeZone

/**
 * 苹果CMS 的 `vod_time`：`2024-01-15 12:30:00`，偶尔只给日期。
 *
 * 两个必须钉死的点：
 *
 * 1. **不带时区**。这类源的服务器基本都在东八区，所以这里显式按 `GMT+8` 解析，
 *    不用设备默认时区 —— 否则用户把电视时区改到别处，「最近更新」的排序会整体偏移；
 * 2. **解析不出来就返回 null**，由调用方回落到入库时间。宁可让排序退化成入库顺序，
 *    也不要因为一条脏数据把作品挤到 1970 年或排到最前面。
 *
 * 同样不用 `java.time`：minSdk 21，那套 API 需要 desugaring（与 [com.leimao.tv.core.network.epg.XmltvTime] 同因）。
 */
object VodTime {
    private val ZONE: TimeZone = TimeZone.getTimeZone("GMT+8")

    private val PATTERN = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?""")

    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val match = PATTERN.find(raw.trim()) ?: return null
        val parts = match.groupValues

        val year = parts[1].toIntOrNull() ?: return null
        val month = parts[2].toIntOrNull() ?: return null
        val day = parts[3].toIntOrNull() ?: return null
        // 只有日期时，时分秒按 0 处理：这类数据在「按天」的粒度上仍然是准确的
        val hour = parts[4].toIntOrNull() ?: 0
        val minute = parts[5].toIntOrNull() ?: 0
        val second = parts[6].toIntOrNull() ?: 0

        if (year !in 1900..2100) return null
        if (month !in 1..12) return null
        if (day !in 1..31) return null
        if (hour !in 0..23) return null
        if (minute !in 0..59) return null
        if (second !in 0..59) return null

        return Calendar.getInstance(ZONE).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
    }
}
