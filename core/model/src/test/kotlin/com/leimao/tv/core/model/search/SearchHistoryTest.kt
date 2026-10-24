package com.leimao.tv.core.model.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索历史（SEARCH-07）。
 *
 * 钉住四件事：新的在最前、同一句话只留一条、上限 10 条、空白词不记。
 * 这四条如果靠 DataStore 层手写，就得在设备上一条条试 —— 而它们本来就是纯字符串规则。
 */
class SearchHistoryTest {
    @Test
    fun `新搜的排在最前面`() {
        val history = pushSearchHistory(listOf("旧词"), "新词")

        assertEquals(listOf("新词", "旧词"), history)
    }

    @Test
    fun `同一句话只留一条，并且提到最前`() {
        val history = pushSearchHistory(listOf("流浪地球", "三体", "长津湖"), "三体")

        assertEquals(listOf("三体", "流浪地球", "长津湖"), history)
    }

    @Test
    fun `大小写与空格不同也算同一个词`() {
        // 「CCTV 1」与「cctv1」搜出来是同一批台，留两条只是占坑
        val history = pushSearchHistory(listOf("CCTV 1"), "cctv1")

        assertEquals(listOf("cctv1"), history)
    }

    @Test
    fun `最多留十条，最旧的被挤掉`() {
        val filled = (1..SEARCH_HISTORY_LIMIT).map { "词$it" }

        val history = pushSearchHistory(filled, "新词")

        assertEquals(SEARCH_HISTORY_LIMIT, history.size)
        assertEquals("新词", history.first())
        assertEquals("词1", history[1])
        // 满 10 条时再进一条，被挤掉的是最旧的「词10」
        assertEquals(false, "词$SEARCH_HISTORY_LIMIT" in history)
    }

    @Test
    fun `空白词不进历史`() {
        assertEquals(listOf("旧词"), pushSearchHistory(listOf("旧词"), "   "))
        assertEquals(emptyList<String>(), pushSearchHistory(emptyList(), "　"))
    }

    @Test
    fun `删掉一条后其余顺序不变`() {
        val history = removeSearchHistory(listOf("甲", "乙", "丙"), "乙")

        assertEquals(listOf("甲", "丙"), history)
    }

    @Test
    fun `编码解码是一个来回`() {
        val history = listOf("流浪地球", "cctv1", "三体")

        assertEquals(history, decodeSearchHistory(encodeSearchHistory(history)))
    }

    @Test
    fun `存档被改坏时只少几条，不抛异常`() {
        // 空行、首尾空白、超长都在这里出现：历史是「锦上添花」，读坏了一条不能影响启动
        assertEquals(listOf("甲", "乙"), decodeSearchHistory("甲\n\n乙\n"))
        assertEquals(emptyList<String>(), decodeSearchHistory(null))
        assertEquals(emptyList<String>(), decodeSearchHistory(""))
        assertEquals(SEARCH_HISTORY_LIMIT, decodeSearchHistory((1..30).joinToString("\n") { "词$it" }).size)
    }
}
