package com.leimao.tv.feature.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 播放页顶部那一行节目文案（LIVE-20）。
 *
 * 用固定时区的格式化器，断言才稳定：跟着机器默认时区走的话，同一份代码在 CI 上
 * 跑出来差 8 小时，测试会变成「谁在本机跑过就过」。
 */
class ProgrammeLineTest {
    private val formatter =
        SimpleDateFormat("HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }

    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 22, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `当前档与下一档都拼进去`() {
        val line =
            ProgrammeLine(
                current = "新闻联播",
                currentEndsAt = at(21),
                next = "焦点访谈",
                nextStartsAt = at(21),
            )
        assertEquals("正在播 新闻联播 21:00 结束　·　下一档 焦点访谈 21:00", line.toCaption(formatter))
    }

    @Test
    fun `只有当前档时不硬凑下一档`() {
        val line = ProgrammeLine(current = "新闻联播", currentEndsAt = at(21, 5))
        assertEquals("正在播 新闻联播 21:05 结束", line.toCaption(formatter))
    }

    @Test
    fun `只有下一档时也照实显示`() {
        // 节目单从半小时后才开始覆盖这个台：这时候「正在播」是空的，
        // 不能拿下一档去冒充当前档
        val line = ProgrammeLine(next = "焦点访谈", nextStartsAt = at(21, 30))
        assertEquals("下一档 焦点访谈 21:30", line.toCaption(formatter))
    }

    @Test
    fun `缺结束时间就不写结束时间`() {
        val line = ProgrammeLine(current = "新闻联播")
        assertEquals("正在播 新闻联播", line.toCaption(formatter))
    }

    @Test
    fun `节目单没覆盖这个台时整行不显示`() {
        assertNull(ProgrammeLine().toCaption(formatter))
        val missing: ProgrammeLine? = null
        assertNull(missing.toCaption(formatter))
    }
}
