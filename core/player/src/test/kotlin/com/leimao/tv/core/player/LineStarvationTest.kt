package com.leimao.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条线路供不上」的判据（PLAY-13，2026-11-06「隔两秒卡一次」）。
 *
 * 用例里的数字全部来自实测：
 * - CCTV5 那条慢线：分片 5.45 MB / 4.2 秒 → 流要 10.4 Mbps，实测 0.82 Mbps；
 * - 同台的快线：5.6 MB / 一段，实测 27 Mbps。
 */
class LineStarvationTest {
    @Test
    fun `实测远低于码率 判定供不上`() {
        // 10.4 Mbps 的流，只给 0.82 Mbps（= 102_500 B/s）
        assertTrue(isLineStarved(measuredBytesPerSecond = 102_500, requiredBitsPerSecond = 10_400_000))
    }

    @Test
    fun `实测高于码率 不判定`() {
        // 27 Mbps 的余量喂 10.4 Mbps 的流
        assertTrue(!isLineStarved(measuredBytesPerSecond = 3_400_000, requiredBitsPerSecond = 10_400_000))
    }

    @Test
    fun `刚好等于码率也算供不上 因为余量为零`() {
        // 码率 8 Mbps → 需要 1_000_000 B/s；正好给这么多，任何一次抖动都会抽干缓冲
        assertTrue(isLineStarved(measuredBytesPerSecond = 1_000_000, requiredBitsPerSecond = 8_000_000))
    }

    @Test
    fun `留出余量之后才算够用`() {
        // 1.15 倍余量：8 Mbps 要 1_150_000 B/s 才不算饿
        assertTrue(isLineStarved(measuredBytesPerSecond = 1_100_000, requiredBitsPerSecond = 8_000_000))
        assertFalse(isLineStarved(measuredBytesPerSecond = 1_200_000, requiredBitsPerSecond = 8_000_000))
    }

    @Test
    fun `码率未知时不做判断`() {
        // 容器没声明码率（Livestream 里很常见）：没有依据就别乱换线
        assertFalse(isLineStarved(measuredBytesPerSecond = 1, requiredBitsPerSecond = 0))
        assertFalse(isLineStarved(measuredBytesPerSecond = 0, requiredBitsPerSecond = 0))
    }

    @Test
    fun `速率为零不做判断 交给反复缓冲那条判据`() {
        // 速率为 0 也可能是「暂停中」「缓冲已满内核不下载」，不能只凭它换线
        assertFalse(isLineStarved(measuredBytesPerSecond = 0, requiredBitsPerSecond = 10_400_000))
    }

    @Test
    fun `说明文字带两位实测数字 且用点号`() {
        assertEquals(
            "这条线路供不上：流要 10.4 Mbps，实测 0.8 Mbps",
            starvedDescription(requiredBitsPerSecond = 10_400_000, measuredBytesPerSecond = 102_500),
        )
    }

    // ---- 缓冲占比判据（码率未知时）----

    @Test
    fun `播两秒卡两秒 占比过半 判定供不上`() {
        // 10 秒窗口里 10 个周期在缓冲 = 50%，正是实测那条慢线的样子
        assertTrue(isRebufferHeavy(bufferingTicks = 10, totalTicks = 20))
    }

    @Test
    fun `偶尔抖一下 占比不到四成 不动手`() {
        // 20 个周期里 7 个在缓冲（35%）：能看，换线反而会黑屏一次
        assertFalse(isRebufferHeavy(bufferingTicks = 7, totalTicks = 20))
    }

    @Test
    fun `刚起播的那几秒不算数`() {
        // 起播本来就在缓冲；窗口没攒满时不下结论（否则每次换台都会误判）
        assertFalse(isRebufferHeavy(bufferingTicks = 10, totalTicks = 10))
    }

    @Test
    fun `一直没缓冲过 当然不算`() {
        assertFalse(isRebufferHeavy(bufferingTicks = 0, totalTicks = 20))
    }

    @Test
    fun `完全卡死 百分之百在缓冲`() {
        assertTrue(isRebufferHeavy(bufferingTicks = 20, totalTicks = 20))
    }
}
