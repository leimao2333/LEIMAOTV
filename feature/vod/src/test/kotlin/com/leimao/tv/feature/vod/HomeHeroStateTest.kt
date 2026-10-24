package com.leimao.tv.feature.vod

import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodContinueItem
import com.leimao.tv.core.model.vod.VodProgress
import com.leimao.tv.core.model.vod.VodSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页 Hero 区（HOME-04）的取数与轮播规则。
 *
 * 这些规则全是纯函数，也就意味着它们**不需要电视**就能验：谁排在前面、同一部片会不会
 * 重复出现、点到最后一张会不会越界。这些错在真机上表现为「首页轮播卡住或整块消失」，
 * 而在单元测试里只是一行断言。
 */
class HomeHeroStateTest {
    // ------------------------------------------------------------------ 排序

    @Test
    fun `继续观看排最前，其次直播，最后推荐`() {
        val items =
            buildHeroItems(
                continueWatching = listOf(continueItem(sourceKey = "c1")),
                liveChannels = listOf(channel(id = 9)),
                recommendations = listOf(summary(sourceKey = "r1")),
            )

        assertEquals(listOf("media:1:c1", "live:9", "media:1:r1"), items.map { it.identity })
        assertEquals(
            listOf(HeroItem.KICKER_CONTINUE, HeroItem.KICKER_LIVE, HeroItem.KICKER_RECOMMEND),
            items.map { it.kicker },
        )
    }

    @Test
    fun `继续观看最多占三席`() {
        val items =
            buildHeroItems(
                continueWatching = (1..5).map { continueItem(sourceKey = "c$it") },
            )

        assertEquals(HeroItem.CONTINUE_LIMIT, items.size)
        assertTrue(items.all { it is HeroItem.Continue })
    }

    @Test
    fun `直播最多两席且按频道去重`() {
        val items =
            buildHeroItems(
                liveChannels = listOf(channel(id = 1), channel(id = 1), channel(id = 2), channel(id = 3)),
            )

        assertEquals(2, items.size)
        assertEquals(listOf("live:1", "live:2"), items.map { it.identity })
    }

    // ------------------------------------------------------------------ 去重

    @Test
    fun `同一部片既在继续观看又在推荐里时只出现一次`() {
        val items =
            buildHeroItems(
                continueWatching = listOf(continueItem(sourceKey = "same")),
                recommendations = listOf(summary(sourceKey = "same"), summary(sourceKey = "other")),
            )

        assertEquals(2, items.size)
        // 留下的必须是带进度的那条：推荐那条会丢掉「看到第几分钟」
        assertTrue(items.first() is HeroItem.Continue)
        assertEquals("media:1:same", items.first().identity)
    }

    @Test
    fun `同名不同源算两部片，不能按标题去重`() {
        val items =
            buildHeroItems(
                continueWatching = listOf(continueItem(sourceId = 1, sourceKey = "a")),
                recommendations = listOf(summary(sourceId = 2, sourceKey = "a")),
            )

        assertEquals(2, items.size)
        // 两部片同名是常态（翻拍、分流站），按标题去重会把用户正在追的那条误删
        assertEquals("同一部片", items[0].title)
        assertEquals(items[0].title, items[1].title)
    }

    // ------------------------------------------------------------------ 边界

    @Test
    fun `什么内容都没有时不产出 Hero`() {
        // 空列表时组件自己不渲染 —— 这一条保证「没有内容就绝不出现一块黑」
        assertEquals(emptyList<HeroItem>(), buildHeroItems())
        assertEquals(emptyList<HeroItem>(), buildHeroItems(limit = 0))
    }

    @Test
    fun `推荐只补到上限为止`() {
        val items =
            buildHeroItems(
                continueWatching = listOf(continueItem(sourceKey = "c1")),
                recommendations = (1..10).map { summary(sourceKey = "r$it") },
            )

        assertEquals(HeroItem.HERO_LIMIT, items.size)
        assertEquals("media:1:c1", items.first().identity)
    }

    @Test
    fun `直播的副信息优先显示节目名，没有节目单时退回分组`() {
        val channel = channel(id = 7, groupName = "央视")
        val withEpg = HeroItem.Live(channel, nowPlaying = "新闻联播")
        val withoutEpg = HeroItem.Live(channel, nowPlaying = null)
        val blankEpg = HeroItem.Live(channel, nowPlaying = "  ")

        assertEquals("正在播 新闻联播 · 央视", withEpg.meta)
        assertEquals("央视", withoutEpg.meta)
        // 空字符串来自节目单里「有这一档但没写名字」的条目，不能显示成「正在播  · 央视」
        assertEquals("央视", blankEpg.meta)
    }

    @Test
    fun `继续观看的副信息带上集号与已看到的时间`() {
        val item = HeroItem.Continue(continueItem(sourceKey = "c1", positionMs = 754_000))

        assertEquals("第 3 集 · 已看到 12:34", item.meta)
        assertEquals("接着看", item.actionLabel)
    }

    // ------------------------------------------------------------------ 轮播

    @Test
    fun `轮播到最后一张回到第一张`() {
        assertEquals(1, nextHeroIndex(current = 0, size = 3))
        assertEquals(2, nextHeroIndex(current = 1, size = 3))
        assertEquals(0, nextHeroIndex(current = 2, size = 3))
    }

    @Test
    fun `只有一张时不越界`() {
        assertEquals(0, nextHeroIndex(current = 0, size = 1))
    }

    @Test
    fun `没有内容时返回 0 而不是除零`() {
        // size 为 0 时做 `% size` 会崩 —— 崩在首页就是整块内容区消失
        assertEquals(0, nextHeroIndex(current = 3, size = 0))
        assertEquals(0, nextHeroIndex(current = 0, size = -1))
    }

    @Test
    fun `下标越界时归一后再推进`() {
        // 列表从 6 条缩到 2 条：旧下标 5 还在内存里，这一步不能抛异常；
        // 5 在两条里就是最后一条，再推进回到第一条
        assertEquals(0, nextHeroIndex(current = 5, size = 2))
        assertEquals(1, nextHeroIndex(current = -3, size = 2))
    }

    // ------------------------------------------------------------------ 替身

    private fun continueItem(
        sourceId: Long = 1,
        sourceKey: String = "key",
        positionMs: Long = 754_000,
    ) = VodContinueItem(
        progress =
            VodProgress(
                sourceId = sourceId,
                sourceKey = sourceKey,
                lineIndex = 0,
                episodeIndex = 2,
                positionMs = positionMs,
                durationMs = 2_400_000,
                updatedAt = 1_700_000_000_000,
            ),
        title = "同一部片",
        poster = "https://example.invalid/p.jpg",
    )

    private fun summary(
        sourceId: Long = 1,
        sourceKey: String = "key",
    ) = VodSummary(
        sourceId = sourceId,
        sourceKey = sourceKey,
        name = "同一部片",
        type = ContentType.MOVIE,
        pic = null,
        remarks = "HD国语",
        year = 2024,
        area = "大陆",
    )

    private fun channel(
        id: Long,
        groupName: String = "默认",
    ) = Channel(
        id = id,
        name = "测试台 $id",
        groupName = groupName,
        logoUrl = null,
        streamUrl = "https://example.invalid/$id.m3u8",
        number = id.toInt(),
    )
}
