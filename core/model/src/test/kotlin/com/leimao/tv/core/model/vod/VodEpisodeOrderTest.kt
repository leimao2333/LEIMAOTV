package com.leimao.tv.core.model.vod

import com.leimao.tv.core.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动连播的「下一集在哪」（VOD-05）。
 *
 * 这段规则看着只有一句「播完下一集」，但边界不少：线路到头、线路数不对齐、
 * 最后一条线路的最后一集、以及「往回退不跨线路」。这些在真机上要被一集一集看完才碰得到，
 * 放在单测里几分钟就能全覆盖。
 */
class VodEpisodeOrderTest {
    private fun detail(vararg lines: Pair<String, Int>): VodDetail =
        VodDetail(
            summary =
                VodSummary(
                    sourceId = 1,
                    sourceKey = "1",
                    name = "某剧",
                    type = ContentType.TV_SERIES,
                ),
            lines =
                lines.map { (name, count) ->
                    VodLine(name, (1..count).map { VodEpisode("第0$it 集", "https://e.com/$name$it") })
                },
        )

    @Test
    fun `同线路还有下一集就顺着走`() {
        val d = detail("甲" to 5, "乙" to 5)

        assertEquals(VodEpisodePos(0, 2), d.nextEpisode(0, 1))
    }

    @Test
    fun `当前线路到头换下一条线路的第一集`() {
        // 不按集号对齐：甲线路 3 集，播完第 3 集后乙线路从第 1 集开始
        val d = detail("甲" to 3, "乙" to 10)

        assertEquals(VodEpisodePos(1, 0), d.nextEpisode(0, 2))
    }

    @Test
    fun `线路集数不对齐时不按集号硬跳`() {
        // 甲线路 40 集、乙线路 39 集：甲的第 40 集在乙里不存在，
        // 所以甲播完必须回乙的第一集，而不是「乙的第 40 集」（那是越界）
        val d = detail("甲" to 40, "乙" to 39)

        assertEquals(VodEpisodePos(1, 0), d.nextEpisode(0, 39))
    }

    @Test
    fun `跳过空线路`() {
        // 空线路（源里那条线一个地址都没有）不该吃掉一次「下一集」，
        // 否则用户会看到画面停住、没有任何提示
        val d = detail("甲" to 2, "空" to 0, "乙" to 2)

        assertEquals(VodEpisodePos(2, 0), d.nextEpisode(0, 1))
    }

    @Test
    fun `最后一集的下一集是 null`() {
        val d = detail("甲" to 3)

        assertNull(d.nextEpisode(0, 2))
    }

    @Test
    fun `线路越界时退回第一条线路的第一集`() {
        val d = detail("甲" to 3)

        assertEquals(VodEpisodePos(0, 0), d.nextEpisode(9, 0))
    }

    @Test
    fun `上一集同线路往回退`() {
        val d = detail("甲" to 5)

        assertEquals(VodEpisodePos(0, 1), d.previousEpisode(0, 2))
    }

    @Test
    fun `第一集的上一集是 null`() {
        val d = detail("甲" to 5, "乙" to 5)

        // 关键：不跨到上一条线路的最后一集 —— 那可能是完全不同的内容
        assertNull(d.previousEpisode(1, 0))
    }

    @Test
    fun `集名为空时给可读的兜底`() {
        val d =
            VodDetail(
                summary =
                    VodSummary(
                        sourceId = 1,
                        sourceKey = "1",
                        name = "某剧",
                        type = ContentType.MOVIE,
                    ),
                lines = listOf(VodLine("甲", listOf(VodEpisode("", "https://e.com/x")))),
            )

        assertEquals("第 1 集", d.episodeName(0, 0))
    }

    @Test
    fun `降级链只带同一集的其他线路地址`() {
        val d = detail("甲" to 3, "乙" to 3)

        val urls = d.episodeUrls(0, 1)

        // 第一条是用户选的那条线路；第二条是乙线路的同一集
        assertEquals(listOf("https://e.com/甲2", "https://e.com/乙2"), urls)
    }

    @Test
    fun `其他线路没有这一集时不进降级链`() {
        // 乙线路只有 1 集，甲线路第 2 集在乙里不存在 —— 硬塞会让换线后播到别的内容
        val d = detail("甲" to 3, "乙" to 1)

        assertEquals(listOf("https://e.com/甲2"), d.episodeUrls(0, 1))
    }

    @Test
    fun `进度指向还在的集就是有效的`() {
        val d = detail("甲" to 5)

        assertEquals(true, d.acceptsProgress(progress(line = 0, episode = 4)))
    }

    @Test
    fun `源里集数变少后旧进度作废`() {
        // 上周 40 集、这周只剩 30 集：拿指向第 40 集的进度去起播，
        // 用户只会得到一句「这一集没有播放地址」，还不知道为什么
        val shrunk = detail("甲" to 30)
        val stale = progress(line = 0, episode = 39)

        assertEquals(false, shrunk.acceptsProgress(stale))
    }

    @Test
    fun `线路没了同样作废`() {
        // 源少了一条线：旧进度记在第二条线上，现在只有一条
        val d = detail("甲" to 5)

        assertEquals(false, d.acceptsProgress(progress(line = 1, episode = 0)))
    }

    private fun progress(
        line: Int,
        episode: Int,
    ) = VodProgress(
        sourceId = 1,
        sourceKey = "1",
        lineIndex = line,
        episodeIndex = episode,
        positionMs = 60_000,
        durationMs = 45 * 60_000,
        updatedAt = 0,
    )
}
