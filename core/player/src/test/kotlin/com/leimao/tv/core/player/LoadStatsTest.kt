package com.leimao.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 加载浮层那行字的格式（PLAY-12）。
 *
 * 这不是「随便拼个字符串」：这一行是用户判断**该等还是该换**的唯一依据。
 * 一个字节都没收到时如果显示成「已加载 0 B · 0 B/s」，用户只会觉得卡住了；
 * 显示「还没有收到数据」才是可行动的结论。
 */
class LoadStatsTest {
    @Test
    fun `没收到任何字节时说清楚`() {
        assertEquals("还没有收到数据", LoadStats.None.summary())
        assertEquals("还没有收到数据", LoadStats(buffering = true, bytesPerSecond = 0).summary())
    }

    @Test
    fun `有字节时按 已加载 速度 缓冲 排序`() {
        val stats =
            LoadStats(
                buffering = true,
                bytesLoaded = 3L * 1024 * 1024,
                bytesPerSecond = 480L * 1024,
                bufferedMs = 3_200,
                width = 1920,
                height = 1080,
            )
        assertEquals("已加载 3 MB · 480 KB/s · 已缓冲 3.2 秒", stats.summary())
    }

    @Test
    fun `分辨率最多排到第三段`() {
        val stats =
            LoadStats(
                bytesLoaded = 1024,
                bytesPerSecond = 0,
                bufferedMs = 12_000,
                width = 1280,
                height = 720,
            )
        assertEquals("已加载 1 KB · 已缓冲 12 秒 · 1280×720", stats.summary())
    }

    @Test
    fun `没有分辨率时不显示空段`() {
        val stats = LoadStats(bytesLoaded = 2048, bytesPerSecond = 1024)
        assertEquals("已加载 2 KB · 1 KB/s", stats.summary())
        assertNull(stats.resolution)
    }

    @Test
    fun `字节单位换算`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1536L * 1024))
        assertEquals("480 KB/s", formatSpeed(480L * 1024))
    }

    @Test
    fun `缓冲时长十秒内保留一位小数`() {
        assertEquals("0.3 秒", formatSeconds(300))
        assertEquals("9.5 秒", formatSeconds(9_500))
        assertEquals("12 秒", formatSeconds(12_000))
    }
}
