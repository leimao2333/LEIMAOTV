package com.leimao.tv.feature.settings.changelog

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoldMarkdownTest {
    @Test
    fun `成对的星号被渲染成加粗`() {
        val text = "看起来 **很粗** 的字"
        val result = renderBoldMarkdown(text)

        assertEquals("看起来 很粗 的字", result.text)

        val boldStart = result.text.indexOf("很粗")
        val spans = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, spans.size)
        assertEquals(boldStart, spans.first().start)
        assertEquals(boldStart + 2, spans.first().end)
    }

    @Test
    fun `未闭合的星号原样保留，不吞掉后文`() {
        val text = "只有开头 **没有结尾"

        val result = renderBoldMarkdown(text)

        assertEquals(text, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `多段加粗互不干扰`() {
        val result = renderBoldMarkdown("**一** 和 **二**")

        assertEquals("一 和 二", result.text)
        assertEquals(2, result.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `纯文本原样返回`() {
        val result = renderBoldMarkdown("没有任何标记")

        assertEquals("没有任何标记", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }
}
