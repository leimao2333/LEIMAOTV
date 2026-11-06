package com.leimao.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「无响应的线路不用再费时间」（2026-11-06）。
 *
 * 用户的原话：**「每次加载半天无响应的还是无响应」**。修法有两半：
 * 一半是缩短首字节预算（见 `Media3PlayerController.FAST_FIRST_BYTE_BUDGET_MS`），
 * 另一半就是这里的「记住哪些地址根本不回包，下次从后面的开始试」。
 */
class DeadLinesTest {
    private val slow = "http://a.example/1.m3u8"
    private val dead = "http://b.example/2.m3u8"
    private val good = "http://c.example/3.m3u8"

    @Before
    fun setUp() {
        DeadLines.clear()
    }

    @Test
    fun `记过的地址判死 没记过的不算`() {
        DeadLines.mark(dead)

        assertTrue(DeadLines.isDead(dead))
        assertFalse(DeadLines.isDead(slow))
    }

    @Test
    fun `跳过判死的线路 直接落到能试的那条`() {
        val lines = listOf(dead, dead + "?x", good)
        DeadLines.mark(dead)
        DeadLines.mark(dead + "?x")

        assertEquals(2, firstLiveLineIndex(lines, from = 0, isDead = DeadLines::isDead))
    }

    @Test
    fun `全都判死过就退回原样 不把台彻底堵死`() {
        val lines = listOf(dead, slow)
        val allDead: (String) -> Boolean = { true }

        // 全死了也要返回 from 本身：宁可多等一次，也不能一个候选都没有
        assertEquals(1, firstLiveLineIndex(lines, from = 1, isDead = allDead))
        assertEquals(0, firstLiveLineIndex(lines, from = 0, isDead = allDead))
    }

    @Test
    fun `从中间往后找 不回头`() {
        val lines = listOf(good, dead, good)
        DeadLines.mark(dead)

        // 用户按「换线路」是往后换，不能因为前面有一条活的又跳回去
        assertEquals(2, firstLiveLineIndex(lines, from = 1, isDead = DeadLines::isDead))
    }

    @Test
    fun `越界的起点会被夹回范围内`() {
        val lines = listOf(good, dead)

        assertEquals(1, firstLiveLineIndex(lines, from = 99, isDead = DeadLines::isDead))
        assertEquals(0, firstLiveLineIndex(lines, from = -5, isDead = DeadLines::isDead))
    }

    @Test
    fun `空列表返回零 调用方不会越界`() {
        assertEquals(0, firstLiveLineIndex(emptyList(), from = 3, isDead = DeadLines::isDead))
    }
}
