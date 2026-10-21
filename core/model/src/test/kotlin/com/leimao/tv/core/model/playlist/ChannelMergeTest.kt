package com.leimao.tv.core.model.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同名频道合并与线路归并（LIVE-13，RULES §9.1 要求有单测）。
 *
 * 合并规则的价值在「判断错会怎样」：并得太多 → 用户换台被送到错的台；
 * 并得太少 → 列表里一排同名项。下面的用例把两边的边界都钉住。
 */
class ChannelMergeTest {
    @Test
    fun `同名不同清晰度合并成一条并保留两条线路`() {
        val merged =
            mergeChannels(
                listOf(
                    channel("CCTV-1 高清", "https://example.com/1-hd"),
                    channel("CCTV-1", "https://example.com/1-sd"),
                ),
            )

        assertEquals(1, merged.size)
        assertEquals(listOf("https://example.com/1-hd", "https://example.com/1-sd"), merged.single().lines)
    }

    @Test
    fun `相同 tvg-id 即使名字不同也合并`() {
        val merged =
            mergeChannels(
                listOf(
                    channel("CCTV1 综合", "https://example.com/a", tvgId = "cctv1"),
                    channel("央视一套", "https://example.com/b", tvgId = "cctv1"),
                ),
            )

        assertEquals(1, merged.size)
        assertEquals("cctv1", merged.single().tvgId)
        assertEquals(2, merged.single().lines.size)
    }

    @Test
    fun `无 tvg-id 的同名条目并入有标识的那一组`() {
        val merged =
            mergeChannels(
                listOf(
                    channel("CCTV-1", "https://example.com/no-id"),
                    channel(
                        "CCTV-1",
                        "https://example.com/with-id",
                        tvgId = "cctv1",
                        logo = "https://example.com/l.png",
                    ),
                ),
            )

        assertEquals(1, merged.size)
        // 展示信息取有标识的那条：台标与分组通常只有它填得全
        assertEquals("cctv1", merged.single().tvgId)
        assertEquals("https://example.com/l.png", merged.single().logoUrl)
        assertEquals(listOf("https://example.com/no-id", "https://example.com/with-id"), merged.single().lines)
    }

    @Test
    fun `不同频道不会被误合并`() {
        val merged =
            mergeChannels(
                listOf(
                    channel("CCTV-5", "https://example.com/5"),
                    channel("CCTV-5+", "https://example.com/5p"),
                ),
            )

        assertEquals(2, merged.size)
    }

    @Test
    fun `同名但 tvg-id 不同时保持两条`() {
        val merged =
            mergeChannels(
                listOf(
                    channel("新闻", "https://example.com/x", tvgId = "news-a"),
                    channel("新闻", "https://example.com/y", tvgId = "news-b"),
                ),
            )

        // 两边都有稳定标识且冲突：源作者自己标成这样，硬拧成一个反而会藏掉一个台
        assertEquals(2, merged.size)
    }

    @Test
    fun `重复地址只留一条`() {
        val merged =
            mergeChannels(
                listOf(
                    channel("Demo", "https://example.com/same"),
                    channel("Demo", "https://example.com/same"),
                ),
            )

        assertEquals(listOf("https://example.com/same"), merged.single().lines)
    }

    @Test
    fun `合并后保持首次出现顺序`() {
        val merged =
            mergeChannels(
                listOf(
                    channel("B", "https://example.com/b"),
                    channel("A", "https://example.com/a"),
                    channel("B 高清", "https://example.com/b2"),
                ),
            )

        assertEquals(listOf("B", "A"), merged.map { it.name })
    }

    @Test
    fun `未分组的条目遇到有分组的同名条目时取有分组的分组`() {
        val merged =
            mergeChannels(
                listOf(
                    channel("Demo", "https://example.com/a"),
                    channel("Demo", "https://example.com/b", group = "测试组"),
                ),
            )

        assertEquals("测试组", merged.single().groupName)
    }

    @Test
    fun `空输入返回空结果`() {
        assertTrue(mergeChannels(emptyList()).isEmpty())
    }

    @Test
    fun `名称归一去掉空白 大小写与清晰度后缀`() {
        assertEquals("cctv-1", normalizeChannelName("  CCTV-1 高清 "))
        assertEquals("cctv-1", normalizeChannelName("CCTV-1\u3000HD"))
        assertEquals("demo", normalizeChannelName("DEMO-"))
        // 空白一律去掉，所以 HDTV Channel 与 HDTVChannel 视作同名；
        // 但开头的 HD 不会被当成清晰度后缀剥掉 —— 只有结尾才剥
        assertEquals("hdtvchannel", normalizeChannelName("HDTV Channel"))
    }

    private fun channel(
        name: String,
        url: String,
        tvgId: String? = null,
        group: String = UNGROUPED,
        logo: String? = null,
    ) = ParsedChannel(
        name = name,
        groupName = group,
        url = url,
        tvgId = tvgId,
        logoUrl = logo,
    )
}
