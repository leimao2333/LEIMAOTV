package com.leimao.tv.feature.vod

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodEpisode
import com.leimao.tv.core.model.vod.VodLine
import com.leimao.tv.core.model.vod.VodSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 详情页状态里那几个派生量（VOD-04）。
 *
 * 专门测「倒序怎么换算」：这是唯一一处**两套下标同时存在**的地方（展示顺序 vs 源顺序），
 * 也是唯一一种「看着对、但存下去的是错的」的 bug —— 用户切到倒序选了第 1 集，
 * 记成原始第 1 集，等 VOD-06 接着播时就会跳到另一头去。
 */
class VodDetailStateTest {
    private fun state(
        reversed: Boolean = false,
        episodeIndex: Int = 0,
        lineIndex: Int = 0,
    ) = VodDetailState(
        detail =
            VodDetail(
                summary =
                    VodSummary(
                        sourceId = 1,
                        sourceKey = "9",
                        name = "某剧",
                        type = ContentType.TV_SERIES,
                        year = 2024,
                        area = "国产",
                    ),
                score = "8.1",
                lang = "国语",
                content = "简介",
                lines =
                    listOf(
                        VodLine("甲", (1..5).map { VodEpisode("第0${it}集", "https://e.com/a$it") }),
                        VodLine("乙", listOf(VodEpisode("正片", "https://e.com/b"))),
                    ),
            ),
        status = VodDetailStatus.READY,
        reversed = reversed,
        episodeIndex = episodeIndex,
        lineIndex = lineIndex,
    )

    @Test
    fun `正序时展示顺序就是源顺序`() {
        val s = state(episodeIndex = 2)

        assertEquals("第03集", s.episodes[2].name)
        assertEquals(2, s.shownEpisodeIndex)
        assertEquals(2, s.originalIndexAt(2))
    }

    @Test
    fun `倒序时第 0 格是最后一集`() {
        val s = state(reversed = true)

        assertEquals("第05集", s.episodes.first().name)
        assertEquals("第01集", s.episodes.last().name)
    }

    @Test
    fun `倒序时点第 0 格存的是原始最后一集`() {
        val s = state(reversed = true)

        // 用户看到的第一格是「第05集」，存下来的下标必须是 4（源里的第 5 集）
        assertEquals(4, s.originalIndexAt(0))
        assertEquals(0, s.originalIndexAt(4))
    }

    @Test
    fun `切倒序后当前集仍高亮在同一部片上`() {
        val forward = state(episodeIndex = 1)
        val backward = state(episodeIndex = 1, reversed = true)

        // 正序停在第 2 格，倒序后应该停在倒数第 2 格 —— 指的是同一集
        assertEquals("第02集", forward.episodes[forward.shownEpisodeIndex].name)
        assertEquals("第02集", backward.episodes[backward.shownEpisodeIndex].name)
    }

    @Test
    fun `选集下标越界时夹到范围内`() {
        // 换片时集数会变少，旧下标可能越界：宁可显示最后一集，也不要抛异常把整页炸掉
        val s = state(episodeIndex = 99)

        assertEquals(4, s.shownEpisodeIndex)
    }

    @Test
    fun `切线路后选集跟着换`() {
        val s = state(lineIndex = 1)

        assertEquals("乙", s.currentLine?.name)
        assertEquals(1, s.episodes.size)
        assertEquals("正片", s.episodes.first().name)
    }

    @Test
    fun `副信息行按评分 · 年份 · 地区 · 语言拼`() {
        assertEquals("评分 8.1 · 2024 · 国产 · 国语", state().metaLine)
    }

    @Test
    fun `没有详情时副信息只剩余摘要里的项`() {
        val s =
            VodDetailState(
                summary =
                    VodSummary(
                        sourceId = 1,
                        sourceKey = "9",
                        name = "某剧",
                        type = ContentType.TV_SERIES,
                        year = 2023,
                    ),
                status = VodDetailStatus.LOADING,
            )

        // 回源还没回来时也要有东西可显示，否则头部会空一片
        assertEquals("某剧", s.title)
        assertEquals("2023", s.metaLine)
    }
}
