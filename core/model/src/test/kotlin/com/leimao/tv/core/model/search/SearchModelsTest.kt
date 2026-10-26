package com.leimao.tv.core.model.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 查询文本工具（SEARCH-06）。
 *
 * 重点是 [trailingPinyinSegment]：中文输入法是**分段**的 —— 选了「我」再打 `ai`，
 * 要出候选的是 `ai` 这一段，不是整串 `我ai`。这一条错了，就是「打第二个字没提示」。
 */
class SearchModelsTest {
    @Test
    fun `末尾拼音段`() {
        // 整串都是拼音
        assertEquals("wo", trailingPinyinSegment("wo"))
        // 汉字后面还在打拼音：只取后面这一段
        assertEquals("ai", trailingPinyinSegment("我ai"))
        assertEquals("nihao", trailingPinyinSegment("我nihao"))
        // 纯汉字：没有正在打的拼音
        assertNull(trailingPinyinSegment("我"))
        assertNull(trailingPinyinSegment("湖南卫视"))
        // 英文数字整串算一段（选词时整串替换）
        assertEquals("cctv5", trailingPinyinSegment("CCTV5"))
        // 空格结尾：末尾没有字母
        assertNull(trailingPinyinSegment("wo "))
        assertNull(trailingPinyinSegment(""))
    }
}
