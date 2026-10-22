package com.leimao.tv.core.model.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 时间轴布局（LIVE-22）的单测。
 *
 * 时区固定成 GMT+8 再取 epoch：跟着机器默认时区走的话，
 * 这套断言在 CI 上会整体偏 8 小时，看起来还「全绿」，等于没测。
 */
class EpgTimelineTest {
    private val zone = TimeZone.getTimeZone("GMT+08:00")

    private fun at(hour: Int, minute: Int): Long =
        Calendar
            .getInstance(zone)
            .apply {
                clear()
                set(2026, Calendar.OCTOBER, 22, hour, minute, 0)
            }.timeInMillis

    private fun programme(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        title: String,
    ) = EpgProgramme(
        channelId = "cctv1",
        startAt = at(from.first, from.second),
        endAt = at(to.first, to.second),
        title = title,
    )

    @Test
    fun `窗口起点对齐到半点`() {
        // 11:43 打开，窗口该从 11:00 起（对齐到 11:30 再往回留半格），而不是 11:13
        val timeline = buildEpgTimeline(emptyList(), nowMillis = at(11, 43))

        assertEquals(at(11, 0), timeline.windowStart)
        assertEquals(at(17, 0), timeline.windowEnd)
        assertEquals(360, timeline.windowMinutes)
    }

    @Test
    fun `跨左边界的节目被截断而不是整体右推`() {
        val timeline =
            buildEpgTimeline(
                programmes = listOf(programme(10 to 0, 12 to 0, "上一档")),
                nowMillis = at(11, 43),
            )

        val block = timeline.blocks.single()
        assertEquals(0, block.startMinute)
        assertEquals(60, block.durationMinutes)
    }

    @Test
    fun `跨右边界的节目截到窗口末尾`() {
        val timeline =
            buildEpgTimeline(
                programmes = listOf(programme(16 to 30, 18 to 0, "晚间档")),
                nowMillis = at(11, 43),
            )

        val block = timeline.blocks.single()
        assertEquals(330, block.startMinute)
        assertEquals(30, block.durationMinutes)
    }

    @Test
    fun `完全在窗外的节目被丢弃`() {
        val timeline =
            buildEpgTimeline(
                programmes =
                    listOf(
                        programme(8 to 0, 9 to 0, "早上"),
                        programme(12 to 0, 12 to 30, "正午"),
                        programme(20 to 0, 21 to 0, "晚上"),
                    ),
                nowMillis = at(11, 43),
            )

        assertEquals(listOf("正午"), timeline.blocks.map { it.programme.title })
    }

    @Test
    fun `相邻节目首尾相接不留缝`() {
        val timeline =
            buildEpgTimeline(
                programmes =
                    listOf(
                        programme(12 to 0, 12 to 30, "一"),
                        programme(12 to 30, 14 to 0, "二"),
                    ),
                nowMillis = at(11, 43),
            )

        val (first, second) = timeline.blocks
        assertEquals(first.startMinute + first.durationMinutes, second.startMinute)
    }

    @Test
    fun `刻度从 0 到窗口末尾每半格一个`() {
        val timeline = buildEpgTimeline(emptyList(), nowMillis = at(11, 43))

        assertEquals(13, timeline.tickMinutes.size)
        assertEquals(0, timeline.tickMinutes.first())
        assertEquals(360, timeline.tickMinutes.last())
        assertTrue(timeline.isEmpty)
    }

    @Test
    fun `现在的位置按分钟算`() {
        val timeline = buildEpgTimeline(emptyList(), nowMillis = at(11, 43))
        assertEquals(43, timeline.nowOffsetMinutes(at(11, 43)))
        assertEquals(0, timeline.nowOffsetMinutes(at(11, 0)))
    }

    @Test
    fun `窗口外不画竖线而不是夹到边上`() {
        val timeline = buildEpgTimeline(emptyList(), nowMillis = at(11, 43))

        assertNull("早于窗口起点", timeline.nowOffsetMinutes(at(9, 0)))
        assertNull("晚于窗口末尾", timeline.nowOffsetMinutes(at(19, 0)))
    }

    @Test
    fun `能找到此刻正在播的那块`() {
        val timeline =
            buildEpgTimeline(
                programmes =
                    listOf(
                        programme(11 to 30, 12 to 10, "新闻"),
                        programme(12 to 10, 13 to 0, "纪录片"),
                    ),
                nowMillis = at(11, 43),
            )

        assertEquals("新闻", timeline.blockAt(at(11, 43))?.programme?.title)
        assertEquals("纪录片", timeline.blockAt(at(12, 30))?.programme?.title)
        assertNull("节目单没覆盖到的时段", timeline.blockAt(at(16, 0)))
    }

    @Test
    fun `数据本身时长为零的节目不进网格`() {
        val broken = programme(12 to 0, 12 to 0, "脏数据")
        val timeline = buildEpgTimeline(listOf(broken), nowMillis = at(11, 43))

        assertTrue(timeline.isEmpty)
        assertNotNull(timeline.tickMinutes)
    }
}
