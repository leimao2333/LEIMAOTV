package com.leimao.tv.feature.iptv

import com.leimao.tv.core.model.catchup.CatchupEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 回看时间标签的单测（LIVE-24）。
 *
 * 时间基准用**默认时区**构造（与生产一致），所以断言在任何机器上都成立 ——
 * 这里要验的是「跨天有没有带日期」，不是某个固定时区下的字面量。
 */
class CatchupRangeLabelTest {
    private val now = at(2026, 10, 24, 22, 0)

    @Test
    fun `当天只写时刻`() {
        val entry = entry(at(2026, 10, 24, 20, 0), at(2026, 10, 24, 21, 30))

        assertEquals("20:00–21:30", catchupRangeLabel(entry, now))
    }

    @Test
    fun `昨天带昨天两个字`() {
        val entry = entry(at(2026, 10, 23, 20, 0), at(2026, 10, 23, 22, 0))

        assertEquals("昨天 20:00–22:00", catchupRangeLabel(entry, now))
    }

    @Test
    fun `更早的档期带月日`() {
        val entry = entry(at(2026, 10, 21, 9, 0), at(2026, 10, 21, 10, 0))

        assertEquals("10-21 09:00–10:00", catchupRangeLabel(entry, now))
    }

    private fun entry(
        startAt: Long,
        endAt: Long,
    ) = CatchupEntry(title = "测试节目", startAt = startAt, endAt = endAt)

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
