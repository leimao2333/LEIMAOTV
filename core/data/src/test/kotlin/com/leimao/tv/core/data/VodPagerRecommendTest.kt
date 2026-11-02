package com.leimao.tv.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推荐栏目的翻页位置（VOD-14）。
 *
 * 这里算错的表现不是崩溃，而是「翻两页又从头来」——用户看到的是满屏重复内容，
 * 只能得出「这个推荐就只有这么点东西」的结论。所以它是必须钉住的一块。
 */
class VodPagerRecommendTest {
    @Test
    fun `前几页依次用不同分类，不会连着取同一个`() {
        val slots = (1..4).map { recommendSlot(page = it, categoryCount = 4) }

        assertEquals(listOf(0, 1, 2, 3), slots.map { it.first })
        // 第一圈每一页都是该分类的第 1 页
        assertTrue(slots.all { it.second == 1 })
    }

    @Test
    fun `轮完一圈之后从第一个分类借第二页`() {
        val slots = (5..8).map { recommendSlot(page = it, categoryCount = 4) }

        assertEquals(listOf(0, 1, 2, 3), slots.map { it.first })
        assertTrue(slots.all { it.second == 2 })
    }

    @Test
    fun `只有一个分类时逐页往后翻`() {
        assertEquals(0 to 1, recommendSlot(page = 1, categoryCount = 1))
        assertEquals(0 to 2, recommendSlot(page = 2, categoryCount = 1))
        assertEquals(0 to 7, recommendSlot(page = 7, categoryCount = 1))
    }

    @Test
    fun `异常参数不抛异常`() {
        assertEquals(0 to 1, recommendSlot(page = 0, categoryCount = 4))
        assertEquals(0 to 1, recommendSlot(page = 3, categoryCount = 0))
    }
}
