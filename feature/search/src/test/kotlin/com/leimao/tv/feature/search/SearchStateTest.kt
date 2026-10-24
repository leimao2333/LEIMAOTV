package com.leimao.tv.feature.search

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.search.SearchHit
import com.leimao.tv.core.model.search.SearchKind
import com.leimao.tv.core.model.search.SearchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索页的状态翻译（SEARCH-03 / 05 / 08）。
 *
 * 这一层不碰 Compose，但决定了用户看到的每一句话 —— 尤其是「什么时候可以说没搜到」：
 * 结果是一帧帧回来的，抢在 `searching` 之前下结论，慢源回来时就会「凭空多出结果」。
 */
class SearchStateTest {
    @Test
    fun `按 kind 分成两区`() {
        val state =
            SearchUiState(
                query = "片",
                outcome = outcome(channels = listOf(channelHit()), vod = listOf(vodHit(), vodHit(key = "2"))),
            )

        assertEquals(1, state.channelHits.size)
        assertEquals(2, state.vodHits.size)
        assertEquals(SearchKind.CHANNEL, state.channelHits.single().kind)
    }

    @Test
    fun `上一轮查询的帧不会冒充新查询的结果`() {
        // 用户把「流浪」改成「三体」，而在路上的那一帧还是「流浪」的结果
        val state =
            SearchUiState(
                query = "三体",
                outcome = outcome(query = "流浪", vod = listOf(vodHit())),
            )

        assertNull(state.current)
        assertTrue("旧帧不该显示出来", state.vodHits.isEmpty())
        assertFalse(state.noResult)
    }

    @Test
    fun `还在搜的时候不下没搜到的结论`() {
        val state =
            SearchUiState(
                query = "片",
                outcome = outcome(pending = 2),
            )

        assertTrue(state.searching)
        assertFalse("还有源没回来，不能说没搜到", state.noResult)
    }

    @Test
    fun `搜完了且一条都没有才是空态`() {
        val state = SearchUiState(query = "片", outcome = outcome())

        assertTrue(state.noResult)
        assertFalse(state.searching)
    }

    @Test
    fun `空关键词时显示历史`() {
        val history = listOf("流浪地球", "三体")

        assertTrue(SearchUiState(query = "", history = history).showHistory)
        // 有词的时候历史让位给结果，否则一屏两个列表抢焦点
        assertFalse(SearchUiState(query = "三体", history = history).showHistory)
    }

    @Test
    fun `状态行说清楚在搜什么、缺了什么`() {
        assertEquals("正在搜索…", describeSearchStatus(outcome(pending = 1), hasQuery = true))
        assertEquals(
            "正在搜索…（慢源 还没回来）",
            describeSearchStatus(outcome(pending = 1, failed = listOf("慢源")), hasQuery = true),
        )
        assertEquals(
            "慢源 没查完，结果可能不全",
            describeSearchStatus(outcome(pending = 0, failed = listOf("慢源")), hasQuery = true),
        )
        // 全部顺利时不打扰：一条常在的状态行会被用户当成装饰
        assertNull(describeSearchStatus(outcome(pending = 0), hasQuery = true))
        assertNull(describeSearchStatus(null, hasQuery = true))
    }

    @Test
    fun `直播板块的空态既解释口径又给出路`() {
        val hint = emptyHintFor(SearchScope.IPTV)

        assertTrue(hint.detail.contains("短剧"))
        assertNotNull("必须有「去影视板块」这个出口", hint.action)
    }

    @Test
    fun `影视板块的空态不再提白名单`() {
        val hint = emptyHintFor(SearchScope.VOD)

        // 影视板块本来就不限类型，再提一遍白名单只会让用户以为这里也搜不到
        assertFalse(hint.detail.contains("只搜这六类"))
        assertNull(hint.action)
    }

    private fun outcome(
        query: String = "片",
        channels: List<SearchHit> = emptyList(),
        vod: List<SearchHit> = emptyList(),
        pending: Int = 0,
        failed: List<String> = emptyList(),
    ) = SearchOutcome(
        query = query,
        scope = SearchScope.IPTV,
        hits = channels + vod,
        pendingSources = pending,
        failedSources = failed,
    )

    private fun channelHit() =
        SearchHit(
            kind = SearchKind.CHANNEL,
            key = "ch:1",
            type = ContentType.LIVE_CHANNEL,
            title = "CCTV-1",
            sourceId = 3,
            channelId = 1,
        )

    private fun vodHit(key: String = "1") =
        SearchHit(
            kind = SearchKind.VOD,
            key = "vod:7:$key",
            type = ContentType.MOVIE,
            title = "片名",
            sourceId = 7,
            sourceKey = key,
        )
}
