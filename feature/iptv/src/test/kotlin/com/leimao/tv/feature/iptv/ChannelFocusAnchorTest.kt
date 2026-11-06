package com.leimao.tv.feature.iptv

import com.leimao.tv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抽屉打开时焦点落在哪一行（2026-11-06「按两下 OK 进频道列表，上下左右没反应」）。
 *
 * 这条 bug 的本质是**焦点请求打在一个没有被组合的节点上**：LazyColumn 不组合可视区外的行，
 * 而抽屉一打开就滚到了正在看的台，第一行早就不在组合里 —— 请求必然失败，
 * 焦点悬空，方向键自然一动不动。所以「落点必须跟着列表滚到的位置走」。
 */
class ChannelFocusAnchorTest {
    private fun channel(id: Long) =
        Channel(
            id = id,
            name = "台 $id",
            groupName = "其他频道",
            logoUrl = null,
            streamUrl = "https://example.com/$id.m3u8",
            number = id.toInt(),
        )

    private val channels = (1L..10L).map { channel(it) }

    @Test
    fun `正在看的台在第 7 行时 焦点落在第 7 行`() {
        // 列表会滚到它，那一行一定在组合里 —— 这正是 bug 的修法
        assertEquals(6, channelFocusAnchorIndex(channels, selectedChannelId = 7L))
    }

    @Test
    fun `没有在播的台时落第一行`() {
        assertEquals(0, channelFocusAnchorIndex(channels, selectedChannelId = null))
    }

    @Test
    fun `正在看的台不在当前分组时落第一行`() {
        // 切到别的分组之后，原来那个台不在这一组里，只能从头看
        assertEquals(0, channelFocusAnchorIndex(channels, selectedChannelId = 999L))
    }

    @Test
    fun `空列表返回负一 调用方据此跳过焦点请求`() {
        assertEquals(-1, channelFocusAnchorIndex(emptyList(), selectedChannelId = 1L))
    }
}
