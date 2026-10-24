package com.leimao.tv.core.model.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度的取舍规则（VOD-06）。
 *
 * 这三条边界决定了「继续观看」里有什么：记太松，一行里全是被误触点开的记录；
 * 记太紧，用户真的在追的剧反而消失。这种「凭感觉调」的阈值最需要测试钉住 ——
 * 否则某次改动把 95% 调成 80%，没人会发现，直到用户抱怨「我明明没看完」。
 */
class VodProgressRulesTest {
    @Test
    fun `刚点开就退出不记`() {
        // 遥控器误触很常见，十秒的记录进了首页只会把人挤走
        assertFalse(VodProgressRules.shouldKeep(positionMs = 0, durationMs = 45 * 60_000L))
        assertFalse(VodProgressRules.shouldKeep(positionMs = 14_999, durationMs = 45 * 60_000L))
    }

    @Test
    fun `看够十五秒就记`() {
        assertTrue(VodProgressRules.shouldKeep(positionMs = 15_000, durationMs = 45 * 60_000L))
    }

    @Test
    fun `看到九成五算看完`() {
        // 40 分钟一集，最后 2 分钟是片尾曲。把阈值放在 95% 而不是 100%，
        // 就是为了「片尾曲一响用户就走」这个最常见的离场方式不再留下一条记录
        val duration = 40 * 60_000L
        assertFalse(VodProgressRules.isFinished(duration * 94 / 100, duration))
        assertTrue(VodProgressRules.isFinished(duration * 95 / 100, duration))
        assertTrue(VodProgressRules.isFinished(duration, duration))
    }

    @Test
    fun `看完的不再记`() {
        // 留着的话，下次点「继续观看」是去看两分钟片尾字幕
        val duration = 40 * 60_000L
        assertFalse(VodProgressRules.shouldKeep(positionMs = duration, durationMs = duration))
    }

    @Test
    fun `时长未知时不算看完`() {
        // HLS 还没取到 manifest 时内核报 0。拿 0 当分母会把一切都算成 100%，
        // 于是每部片都在播到第十秒时被判定「已看完」并删掉
        assertFalse(VodProgressRules.isFinished(positionMs = 30 * 60_000L, durationMs = 0))
        assertTrue(VodProgressRules.shouldKeep(positionMs = 30 * 60_000L, durationMs = 0))
    }

    @Test
    fun `进度比例夹在 0 到 1 之间`() {
        val half = VodProgress(1, "a", 0, 0, positionMs = 100, durationMs = 200, updatedAt = 0)
        val over = VodProgress(1, "a", 0, 0, positionMs = 300, durationMs = 200, updatedAt = 0)
        val unknown = VodProgress(1, "a", 0, 0, positionMs = 300, durationMs = 0, updatedAt = 0)

        assertEquals(0.5f, half.fraction)
        assertEquals(1f, over.fraction)
        // 时长未知不画进度条，而不是画一根满格
        assertEquals(0f, unknown.fraction)
    }

    @Test
    fun `集号从 1 开始说`() {
        // 下标是 0 基的，但对用户说「第 0 集」会被当成 bug
        val progress = VodProgress(1, "a", 0, episodeIndex = 4, positionMs = 0, durationMs = 0, updatedAt = 0)

        assertEquals("第 5 集", progress.episodeLabel)
        assertEquals("第 5 集", VodContinueItem(progress, title = "片名", poster = null).episodeLabel)
    }
}
