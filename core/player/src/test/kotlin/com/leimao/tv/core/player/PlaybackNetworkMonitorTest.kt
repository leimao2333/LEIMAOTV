package com.leimao.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条线路回过话没有」的判定语义（PLAY-08 快速认输的判据）。
 *
 * 这套语义错一次，用户就要么在转圈里坐一分钟（全判成活），要么正常线路被当成死线
 * 反复切走（全判成死）。所以每条边界都单独钉住。
 */
class PlaybackNetworkMonitorTest {
    private var clock = 1_000L
    private val monitor = PlaybackNetworkMonitor { clock }

    @Test
    fun `没收到响应时一律判为没回过话`() {
        assertFalse(monitor.respondedSince(0L))
        assertFalse(monitor.respondedSince(999L))
    }

    @Test
    fun `成功响应之后才算回过话`() {
        clock = 1_500L
        monitor.markResponse(successful = true)

        assertTrue(monitor.respondedSince(1_000L))
        assertTrue(monitor.respondedSince(1_500L))
    }

    @Test
    fun `响应早于线路起点时不算这一条线路的`() {
        // 上一条线路的迟到响应落在 1_200，这一条线路是 1_400 才开始计时的
        clock = 1_200L
        monitor.markResponse(successful = true)

        assertFalse(monitor.respondedSince(1_400L))
    }

    @Test
    fun `404 与 403 不算回过话`() {
        clock = 1_500L
        // 源失效时服务器回得很快，但「地址不存在」不等于「线路能用」
        monitor.markResponse(successful = false)

        assertFalse(monitor.respondedSince(1_000L))
    }

    @Test
    fun `reset 之后旧记录作废`() {
        clock = 1_500L
        monitor.markResponse(successful = true)
        assertTrue(monitor.respondedSince(1_000L))

        monitor.reset()
        assertFalse(monitor.respondedSince(0L))
    }

    @Test
    fun `成功之后再来一个失败响应不会抹掉记录`() {
        clock = 1_500L
        monitor.markResponse(successful = true)
        clock = 1_800L
        monitor.markResponse(successful = false)

        // 已经证明过线路活着，后面某个分片 404 不该让看门狗回头把它判死
        assertTrue(monitor.respondedSince(1_000L))
    }
}
