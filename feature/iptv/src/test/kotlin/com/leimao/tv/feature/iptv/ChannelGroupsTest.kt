package com.leimao.tv.feature.iptv

import com.leimao.tv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 换台与分组是播放页里最容易「看起来对、用起来错」的两块：
 * 顺序错一位用户就会觉得「上下键乱跳」，到头不翻页用户会以为按键坏了。
 */
class ChannelGroupsTest {
    private fun channel(id: Long, name: String, group: String, number: Int) =
        Channel(
            id = id,
            name = name,
            groupName = group,
            logoUrl = null,
            streamUrl = "https://example.com/$id.m3u8",
            number = number,
        )

    private val channels =
        listOf(
            channel(1, "台一", "分组甲", 1),
            channel(2, "台二", "分组乙", 2),
            channel(3, "台三", "分组甲", 3),
        )

    @Test
    fun `分组保持传入顺序`() {
        val groups = groupChannels(channels)

        assertEquals(listOf("分组甲", "分组乙"), groups.map { it.name })
        assertEquals(listOf(1L, 3L), groups[0].channels.map { it.id })
        assertEquals(listOf(2L), groups[1].channels.map { it.id })
    }

    @Test
    fun `下键取后一个台`() {
        assertEquals(2L, neighborOf(channels, currentId = 1L, delta = 1)?.id)
    }

    @Test
    fun `上键取前一个台`() {
        assertEquals(2L, neighborOf(channels, currentId = 3L, delta = -1)?.id)
    }

    @Test
    fun `最后一个台往下翻回第一个`() {
        assertEquals(1L, neighborOf(channels, currentId = 3L, delta = 1)?.id)
    }

    @Test
    fun `第一个台往上翻到最后一个`() {
        assertEquals(3L, neighborOf(channels, currentId = 1L, delta = -1)?.id)
    }

    @Test
    fun `只有一个台时不换台，避免原地重播`() {
        assertNull(neighborOf(listOf(channel(1, "唯一", "分组甲", 1)), currentId = 1L, delta = 1))
    }

    @Test
    fun `当前台不在列表里时回到第一个`() {
        assertEquals(1L, neighborOf(channels, currentId = 99L, delta = 1)?.id)
    }

    @Test
    fun `空列表返回空`() {
        assertNull(neighborOf(emptyList(), currentId = 1L, delta = 1))
    }

    @Test
    fun `过滤空关键词原样返回`() {
        val groups = groupChannels(channels)
        assertEquals(groups, filterGroups(groups, "  "))
    }

    @Test
    fun `过滤按名称包含匹配且不区分大小写`() {
        val groups =
            listOf(
                ChannelGroup("分组甲", listOf(channel(1, "CCTV1", "分组甲", 1), channel(3, "cctv2", "分组甲", 3))),
                ChannelGroup("分组乙", listOf(channel(2, "凤凰卫视", "分组乙", 2))),
            )
        val result = filterGroups(groups, "cctv")

        assertEquals(1, result.size)
        assertEquals("分组甲", result[0].name)
        assertEquals(listOf(1L, 3L), result[0].channels.map { it.id })
    }

    @Test
    fun `过滤后无命中的分组被整组去掉`() {
        val groups =
            listOf(
                ChannelGroup("分组甲", listOf(channel(1, "台一", "分组甲", 1))),
                ChannelGroup("分组乙", listOf(channel(2, "台二", "分组乙", 2))),
            )
        val result = filterGroups(groups, "台一")

        assertEquals(1, result.size)
        assertEquals("分组甲", result[0].name)
    }

    @Test
    fun `过滤无结果时返回空列表`() {
        val groups = groupChannels(channels)
        assertEquals(emptyList<ChannelGroup>(), filterGroups(groups, "不存在"))
    }
}
