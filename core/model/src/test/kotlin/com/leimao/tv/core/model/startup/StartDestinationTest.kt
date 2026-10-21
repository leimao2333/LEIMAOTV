package com.leimao.tv.core.model.startup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RULES §9.2 第 1 条要求单独验的四条启动路径 —— 这里用单测钉住，
 * 免得每次发版都靠人在电视上点一遍才发现「怎么又跑主页去了」。
 */
class StartDestinationTest {
    private val agreed =
        StartupSnapshot(
            complianceAgreed = true,
            hasEnabledSource = true,
            channelsParsed = true,
        )

    @Test
    fun `首次启动未同意合规提示时先出提示页`() {
        val destination = resolveStartDestination(agreed.copy(complianceAgreed = false))

        assertEquals(StartDestination.ComplianceNotice, destination)
    }

    @Test
    fun `没有任何源时落到添加源引导页`() {
        val destination = resolveStartDestination(agreed.copy(hasEnabledSource = false))

        assertEquals(StartDestination.AddSource, destination)
    }

    @Test
    fun `有上次观看的频道时直达该频道`() {
        val destination =
            resolveStartDestination(
                agreed.copy(lastChannelId = 7L, favoriteChannelId = 8L, firstChannelId = 9L),
            )

        assertEquals(StartDestination.Player(channelId = 7L, channelsLoading = false), destination)
    }

    @Test
    fun `上次频道已不存在时降级到收藏第一个`() {
        // 数据层用 JOIN 查「上次频道」，频道被删就返回 null —— 这里用 null 模拟该情况
        val destination =
            resolveStartDestination(
                agreed.copy(lastChannelId = null, favoriteChannelId = 8L, firstChannelId = 9L),
            )

        assertEquals(StartDestination.Player(channelId = 8L, channelsLoading = false), destination)
    }

    @Test
    fun `收藏为空时降级到频道列表第一个`() {
        val destination =
            resolveStartDestination(
                agreed.copy(lastChannelId = null, favoriteChannelId = null, firstChannelId = 9L),
            )

        assertEquals(StartDestination.Player(channelId = 9L, channelsLoading = false), destination)
    }

    @Test
    fun `有源但频道尚未解析完成时停在播放页骨架而不是主页`() {
        val destination =
            resolveStartDestination(
                agreed.copy(channelsParsed = false, lastChannelId = 7L),
            )

        assertEquals(StartDestination.Player(channelId = null, channelsLoading = true), destination)
    }

    @Test
    fun `有源且解析完成但一个频道都没有时仍停在播放页`() {
        val destination = resolveStartDestination(agreed)

        assertEquals(StartDestination.Player(channelId = null, channelsLoading = false), destination)
    }
}
