package com.leimao.tv.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入法候选（SET-11）。
 *
 * 钉三件事：**打 wo 出「我」**（单字）、**打 nihao 出「你好」**（词组）、
 * **前缀能边打边看**（打 n 出 na/ne/nihao…）。这三条错了，键盘就等于只会打字母。
 */
class PinyinImeTest {
    private val ime =
        PinyinIme.parse(
            """
            ni	你,泥,拟
            nihao	你好
            wo	我,握,窝
            women	我们
            """.trimIndent(),
        )

    @Test
    fun `打 wo 第一个候选是我`() {
        // 这是整个功能的存在理由：单字必须能打出来
        assertEquals("我", ime.candidates("wo").first())
    }

    @Test
    fun `词组精确命中`() {
        assertEquals(listOf("你好"), ime.candidates("nihao"))
        assertEquals("我们", ime.candidates("women").first())
    }

    @Test
    fun `前缀能边打边看`() {
        // 打一个 n：先给精确命中的「你」，再按前缀补 nihao 的「你好」
        val partial = ime.candidates("n")
        assertTrue(partial.contains("你"))
        assertTrue(partial.contains("你好"))
    }

    @Test
    fun `候选去重`() {
        // 「你」既在 ni 的候选里、也在 nihao 的前缀里，不该出现两次
        val list = ime.candidates("ni")
        assertEquals(list.size, list.toSet().size)
    }

    @Test
    fun `大小写与非法输入`() {
        assertEquals("我", ime.candidates("WO").first())
        // 带数字/符号的查询不是拼音，直接不给候选（交给名字搜索）
        assertTrue(ime.candidates("wo1").isEmpty())
        assertTrue(ime.candidates("").isEmpty())
    }

    @Test
    fun `没有命中时返回空`() {
        assertTrue(ime.candidates("zzzz").isEmpty())
    }

    @Test
    fun `空表退化成没有候选`() {
        assertFalse(PinyinIme.EMPTY.candidates("wo").isNotEmpty())
        assertEquals(0, PinyinIme.EMPTY.size)
    }
}
