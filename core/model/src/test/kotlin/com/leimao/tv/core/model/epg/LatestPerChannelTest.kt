package com.leimao.tv.core.model.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频道列表的「此刻在播」聚合（LIVE-21）。
 *
 * 这里测的是**批量查询回来的扁平结果怎么归堆**：一份聚合节目单里同一个台常有好几个 id，
 * 各自写了一份时间对不上的节目表，归堆规则错了，列表里就会长期挂着已经播完的节目名。
 */
class LatestPerChannelTest {
    private val h = 3_600_000L

    private fun row(name: String, startHour: Int, title: String, hours: Int = 1) =
        NamedProgramme(
            normalizedName = name,
            startAt = startHour * h,
            endAt = (startHour + hours) * h,
            title = title,
        )

    @Test
    fun `同一个台只有一条时原样保留`() {
        val map = latestPerChannel(listOf(row("cctv1", 20, "新闻联播")))

        assertEquals("新闻联播", map["cctv1"]?.title)
        assertEquals(20 * h, map["cctv1"]?.startAt)
        assertEquals(21 * h, map["cctv1"]?.endAt)
    }

    @Test
    fun `同名多条取开始最晚的那条`() {
        // 真实场景：CCTV1 与 cctv-1 各写了一份节目表，一个说 20:00 开播、一个说 20:05。
        // 取最早的那条会让列表里挂着已经播了 5 分钟的旧节目名
        val map =
            latestPerChannel(
                listOf(
                    row("cctv1", 20, "旧节目"),
                    row("cctv1", 20, "新节目").copy(startAt = 20 * h + 5 * 60_000L),
                ),
            )

        assertEquals("新节目", map["cctv1"]?.title)
    }

    @Test
    fun `不同频道各归各的`() {
        val map =
            latestPerChannel(
                listOf(
                    row("cctv1", 20, "新闻联播"),
                    row("cctv5", 20, "体育新闻"),
                ),
            )

        assertEquals(2, map.size)
        assertEquals("新闻联播", map["cctv1"]?.title)
        assertEquals("体育新闻", map["cctv5"]?.title)
    }

    @Test
    fun `空白标题丢弃`() {
        // 画一行「· 」出来比不画更让人困惑
        val map = latestPerChannel(listOf(row("cctv1", 20, "   ")))

        assertTrue(map.isEmpty())
    }

    @Test
    fun `标题两侧空白被剪掉`() {
        val map = latestPerChannel(listOf(row("cctv1", 20, "  新闻联播  ")))

        assertEquals("新闻联播", map["cctv1"]?.title)
    }

    @Test
    fun `结束不晚于开始的坏数据丢弃`() {
        // EPG 文件里偶有 endAt == startAt 的残缺条目，它永远不可能「在播」
        val map = latestPerChannel(listOf(row("cctv1", 20, "零时长", hours = 0)))

        assertTrue(map.isEmpty())
    }

    @Test
    fun `结果里没有的台就是没有节目单`() {
        // 调用方据此不画节目名 —— 而不是画「无节目信息」
        val map = latestPerChannel(listOf(row("cctv1", 20, "新闻联播")))

        assertNull(map["cctv5"])
    }
}
