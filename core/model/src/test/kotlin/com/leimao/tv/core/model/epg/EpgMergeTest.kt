package com.leimao.tv.core.model.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同类合并（LIVE-20）的核心用例。
 *
 * 每条用例都对应一种真实节目单里的脏数据：同一个台多个 id、同一档节目重复出现、
 * 节目顺序被打乱、节目指向不存在的频道。
 */
class EpgMergeTest {
    private val h = 3_600_000L

    private fun programme(channelId: String, startHour: Int, title: String) =
        EpgProgramme(
            channelId = channelId,
            startAt = startHour * h,
            endAt = (startHour + 1) * h,
            title = title,
        )

    @Test
    fun `同名频道归并成一个 保留全部 id`() {
        val guide =
            mergeEpgGuide(
                channels =
                    listOf(
                        EpgChannel("1", "CCTV1"),
                        EpgChannel("2", "cctv-1 高清"),
                        EpgChannel("3", "东方卫视"),
                    ),
                programmes = emptyList(),
            )

        assertEquals(2, guide.channels.size)
        val cctv1 = guide.channels.first { it.normalizedName == "cctv1" }
        assertEquals(listOf("1", "2"), cctv1.ids)
        // 展示名取第一条，不玩「挑最规范的」这种小聪明
        assertEquals("CCTV1", cctv1.name)
    }

    @Test
    fun `归并后节目按时间排序并去重`() {
        val guide =
            mergeEpgGuide(
                channels = listOf(EpgChannel("1", "CCTV1"), EpgChannel("2", "CCTV1 高清")),
                programmes =
                    listOf(
                        programme("2", 21, "焦点访谈"),
                        // 同一个 id 下重复的同档节目
                        programme("1", 19, "新闻联播"),
                        programme("2", 19, "新闻联播"),
                        programme("1", 21, "焦点访谈"),
                    ),
            )

        val list = guide.programmes.getValue("cctv1")
        assertEquals(listOf("新闻联播", "焦点访谈"), list.map { it.title })
        assertTrue(list[0].startAt < list[1].startAt)
    }

    @Test
    fun `指向不存在频道的节目被丢弃`() {
        val guide =
            mergeEpgGuide(
                channels = listOf(EpgChannel("1", "CCTV1")),
                programmes = listOf(programme("1", 19, "新闻联播"), programme("99", 19, "孤儿节目")),
            )

        assertEquals(1, guide.programmeCount)
        assertEquals("新闻联播", guide.programmes.getValue("cctv1").single().title)
    }

    @Test
    fun `当前节目与下一档按时间取对`() {
        val guide =
            mergeEpgGuide(
                channels = listOf(EpgChannel("1", "CCTV1")),
                programmes =
                    listOf(
                        programme("1", 19, "新闻联播"),
                        programme("1", 20, "天气预报"),
                        programme("1", 21, "焦点访谈"),
                    ),
            )

        val at1940 = 19 * h + 40 * 60_000L
        assertEquals("新闻联播", guide.currentProgramme("CCTV1", at1940)?.title)
        assertEquals("天气预报", guide.nextProgramme("CCTV1", at1940)?.title)

        val at2100 = 21 * h
        assertEquals("焦点访谈", guide.currentProgramme("cctv-1", at2100)?.title)
        // 最后一档：没有下一档，不要循环回到第一条
        assertNull(guide.nextProgramme("CCTV1", at2100))
    }

    @Test
    fun `节目之间的空档不返回当前节目`() {
        val guide =
            mergeEpgGuide(
                channels = listOf(EpgChannel("1", "CCTV1")),
                programmes = listOf(programme("1", 19, "新闻联播")),
            )

        // 20:30 落在节目结束后、下一档开始前
        assertNull(guide.currentProgramme("CCTV1", 20 * h + 30 * 60_000L))
    }

    @Test
    fun `匹配用的频道名与播放列表同一套归一化`() {
        val guide =
            mergeEpgGuide(
                channels = listOf(EpgChannel("1", "东方卫视")),
                programmes = listOf(programme("1", 20, "电视剧")),
            )

        // 源里写的常常带清晰度后缀，归一化之后要能对上
        assertEquals("电视剧", guide.currentProgramme("东方卫视高清", 20 * h + 60_000L)?.title)
    }
}
