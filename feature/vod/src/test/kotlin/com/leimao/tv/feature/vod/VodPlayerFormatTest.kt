package com.leimao.tv.feature.vod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放页的两处纯函数（VOD-05）。
 *
 * [formatTime] 值得单独测：电视上进度时间的字号最大，一旦格式错了（比如把 2 小时的电影
 * 显示成 `120:31`），用户第一眼看到的就是它。
 */
class VodPlayerFormatTest {
    @Test
    fun `不到一小时只显示分秒`() {
        assertEquals("00:00", formatTime(0))
        assertEquals("01:30", formatTime(90_000))
        assertEquals("59:59", formatTime(3_599_000))
    }

    @Test
    fun `超过一小时带上小时`() {
        // 两小时的电影显示成 120:31 是错的，这是这条测试存在的全部理由
        assertEquals("1:00:00", formatTime(3_600_000))
        assertEquals("2:00:31", formatTime(7_231_000))
    }

    @Test
    fun `负时长当 0 处理`() {
        // 直播源在某些内核上会返回 -1，不该显示成 -1:59
        assertEquals("00:00", formatTime(-1_000))
    }

    @Test
    fun `没有时长时不画进度`() {
        val live = VodPlayerUiState(durationMs = 0, positionMs = 5_000)

        assertEquals(false, live.seekable)
        assertEquals(0f, live.progress)
    }

    @Test
    fun `进度夹在 0 到 1 之间`() {
        // 位置偶尔会略超时长（内核上报时序），进度条不能因此溢出容器
        assertEquals(0.5f, VodPlayerUiState(positionMs = 50, durationMs = 100).progress)
        assertEquals(1f, VodPlayerUiState(positionMs = 150, durationMs = 100).progress)
    }

    @Test
    fun `首尾两集没有上一个下一个`() {
        val first = VodPlayerUiState(episodeIndex = 0, episodeCount = 3)
        val last = VodPlayerUiState(episodeIndex = 2, episodeCount = 3)

        assertEquals(false, first.hasPrevious)
        assertEquals(true, first.hasNext)
        assertEquals(true, last.hasPrevious)
        assertEquals(false, last.hasNext)
    }

    @Test
    fun `只有一集时没有上一集也没有下一集`() {
        // 电影就是这种情况：整部片子只有「正片」一条
        val movie = VodPlayerUiState(episodeIndex = 0, episodeCount = 1)

        assertEquals(false, movie.hasPrevious)
        assertEquals(false, movie.hasNext)
    }
}
