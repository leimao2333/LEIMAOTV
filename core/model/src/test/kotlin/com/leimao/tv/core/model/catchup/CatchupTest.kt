package com.leimao.tv.core.model.catchup

import com.leimao.tv.core.model.epg.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 回看地址拼装与档期生成的单测（LIVE-24）。
 *
 * 时间基准刻意用 UTC 构造：模板里的 `${start}` 按约定就是 UTC，
 * 断言里写死「2026-10-24 20:00 UTC」才能在任何时区的机器上跑出同一个结果。
 */
class CatchupTest {
    private val start = utc(2026, 10, 24, 20, 0)
    private val end = utc(2026, 10, 24, 21, 30)

    @Test
    fun `模板占位符按 UTC 的紧凑格式替换`() {
        val catchup =
            Catchup(
                mode = CatchupMode.TEMPLATE,
                template = "https://example.com/timeshift/1.m3u8?start=\${start}&end=\${end}",
            )

        val url = buildCatchupUrl("https://example.com/live/1.m3u8", catchup, start, end)

        assertEquals("https://example.com/timeshift/1.m3u8?start=20261024200000&end=20261024213000", url)
    }

    @Test
    fun `utc 占位符是 epoch 秒而不是毫秒`() {
        val catchup =
            Catchup(
                mode = CatchupMode.TEMPLATE,
                template = "https://example.com/c?utc=\${utc}&lutc=\${lutc}",
            )

        val url = buildCatchupUrl("https://example.com/live", catchup, start, end)

        val seconds = (start / 1000).toString()
        assertEquals("https://example.com/c?utc=$seconds&lutc=$seconds", url)
    }

    @Test
    fun `duration 占位符是分钟数`() {
        val catchup =
            Catchup(mode = CatchupMode.TEMPLATE, template = "https://example.com/c?d=\${duration}")

        val url = buildCatchupUrl("https://example.com/live", catchup, start, end)

        assertEquals("https://example.com/c?d=90", url)
    }

    @Test
    fun `模板里有不认识的占位符就不拼`() {
        val catchup =
            Catchup(
                mode = CatchupMode.TEMPLATE,
                template = "https://example.com/c?start=\${start}&offset=\${offset}",
            )

        // 留着 `${offset}` 的地址交给内核只会得到一块「播放失败」面板
        assertNull(buildCatchupUrl("https://example.com/live", catchup, start, end))
    }

    @Test
    fun `模板里一个占位符都没有就不拼`() {
        val catchup =
            Catchup(mode = CatchupMode.TEMPLATE, template = "https://example.com/fixed.m3u8")

        assertNull(buildCatchupUrl("https://example.com/live", catchup, start, end))
    }

    @Test
    fun `模板模式缺模板时不拼`() {
        assertNull(buildCatchupUrl("https://example.com/live", Catchup(CatchupMode.TEMPLATE), start, end))
    }

    @Test
    fun `flussonic 用 epoch 秒并且接住已有查询串`() {
        val catchup = Catchup(mode = CatchupMode.FLUSSONIC)

        val url = buildCatchupUrl("https://example.com/live/1?token=abc", catchup, start, end)

        assertEquals(
            "https://example.com/live/1?token=abc&start=${start / 1000}&end=${end / 1000}",
            url,
        )
    }

    @Test
    fun `flussonic 在没有查询串时用问号`() {
        val catchup = Catchup(mode = CatchupMode.FLUSSONIC)

        val url = buildCatchupUrl("https://example.com/live/1", catchup, start, end)

        assertTrue(url!!.startsWith("https://example.com/live/1?start="))
    }

    @Test
    fun `xtream 用可读的 UTC 时间`() {
        val catchup = Catchup(mode = CatchupMode.XTREAM)

        val url = buildCatchupUrl("https://example.com/live/1", catchup, start, end)

        assertEquals(
            "https://example.com/live/1?start=2026-10-24:20-00&end=2026-10-24:21-30",
            url,
        )
    }

    @Test
    fun `时间区间非法时不拼`() {
        val catchup = Catchup(mode = CatchupMode.FLUSSONIC)

        assertNull(buildCatchupUrl("https://example.com/live", catchup, end, start))
        assertNull(buildCatchupUrl("https://example.com/live", catchup, start, start))
    }

    @Test
    fun `基地址为空时不拼`() {
        assertNull(buildCatchupUrl("", Catchup(CatchupMode.FLUSSONIC), start, end))
    }

    @Test
    fun `天数解析夹在一到七之间`() {
        assertEquals(3, Catchup.daysOr(null))
        assertEquals(3, Catchup.daysOr("abc"))
        assertEquals(7, Catchup.daysOr("30"))
        assertEquals(1, Catchup.daysOr("0"))
        assertEquals(5, Catchup.daysOr("5"))
    }

    // ---- 档期生成 ----

    @Test
    fun `档期只留已播完的节目并按时间倒序`() {
        val now = utc(2026, 10, 24, 22, 0)
        val programmes =
            listOf(
                programme("正在播", utc(2026, 10, 24, 21, 30), utc(2026, 10, 24, 23, 0)),
                programme("两小时前", utc(2026, 10, 24, 19, 30), utc(2026, 10, 24, 21, 0)),
                programme("昨晚", utc(2026, 10, 23, 20, 0), utc(2026, 10, 23, 22, 0)),
                programme("预告", utc(2026, 10, 25, 0, 0), utc(2026, 10, 25, 2, 0)),
            )

        val entries = catchupEntriesFrom(programmes, now, days = 3)

        assertEquals(listOf("两小时前", "昨晚"), entries.map { it.title })
    }

    @Test
    fun `超出回看天数的档期被丢掉`() {
        val now = utc(2026, 10, 24, 22, 0)
        val programmes =
            listOf(
                programme("五天前", utc(2026, 10, 19, 20, 0), utc(2026, 10, 19, 21, 0)),
                programme("昨天", utc(2026, 10, 23, 20, 0), utc(2026, 10, 23, 21, 0)),
            )

        val entries = catchupEntriesFrom(programmes, now, days = 3)

        assertEquals(listOf("昨天"), entries.map { it.title })
    }

    @Test
    fun `档期条数有上限`() {
        val now = utc(2026, 10, 24, 22, 0)
        val programmes =
            (0 until 60).map { index ->
                val from = now - (index + 2) * 3_600_000L
                programme("节目$index", from, from + 3_000_000L)
            }

        assertEquals(CATCHUP_ENTRY_LIMIT, catchupEntriesFrom(programmes, now, days = 7).size)
    }

    @Test
    fun `没有节目单时按整点切档且不含正在走的这一小时`() {
        val now = utc(2026, 10, 24, 22, 37)

        val slots = catchupSlots(now, days = 1)

        // 最近一档是 21:00–22:00：22:00–23:00 还在走，回看它等于看直播
        assertEquals(utc(2026, 10, 24, 21, 0), slots.first().startAt)
        assertEquals(utc(2026, 10, 24, 22, 0), slots.first().endAt)
        assertEquals(24, slots.size)
        assertNull(slots.first().title)
    }

    private fun programme(
        title: String,
        startAt: Long,
        endAt: Long,
    ) = EpgProgramme(channelId = "cctv1", startAt = startAt, endAt = endAt, title = title)

    private fun utc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
