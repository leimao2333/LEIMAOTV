package com.leimao.tv.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼音表（SEARCH-06）：全拼 + 简写两种口径。
 *
 * 用一张十来个字的小表，不依赖真词典文件 —— 单测要离线、稳定、快（RULES §9.1）。
 * 真词典（2.6 万字）由 `scripts/gen-pinyin.py` 生成，正确性靠产物 + 模拟器手工验收覆盖。
 */
class PinyinTableTest {
    private val pinyin =
        PinyinTable(
            """
            湖hu
            南nan
            卫wei
            视shi
            综zong
            合he
            电dian
            影ying
            台tai
            中zhong
            央yang
            """.trimIndent(),
        )

    @Test
    fun `全拼拼接正确`() {
        assertEquals("hunanweishi", pinyin.fullPinyinOf("湖南卫视"))
        // 英文与数字原样保留：CCTV5 的 5 是用户最可能记得的部分
        assertEquals("cctv5zonghe", pinyin.fullPinyinOf("CCTV5 综合"))
        // 标点与空格丢掉
        assertEquals("cctv1zhongyang", pinyin.fullPinyinOf("CCTV-1 中央"))
    }

    @Test
    fun `简写取全拼首字母`() {
        assertEquals("hnws", pinyin.initialsOf("湖南卫视"))
        assertEquals("cctv5zh", pinyin.initialsOf("CCTV5 综合"))
    }

    @Test
    fun `全拼与简写都能命中`() {
        assertTrue(pinyin.matches("湖南卫视", "hunan"))
        assertTrue(pinyin.matches("湖南卫视", "nweishi"))
        assertTrue(pinyin.matches("湖南卫视", "hnws"))
        assertTrue(pinyin.matches("CCTV5 综合", "zonghe"))
        assertFalse(pinyin.matches("湖南卫视", "hunan1"))
    }

    @Test
    fun `单字拼音可单独取`() {
        assertEquals("hu", pinyin.syllableOf('湖'))
        assertNull(pinyin.syllableOf('龘'))
    }

    @Test
    fun `表外的字被跳过而不是变成乱码`() {
        // 「龘」不在小表里：跳过它，其余照常
        assertEquals("dianying", pinyin.fullPinyinOf("龘电影"))
    }

    @Test
    fun `空查询不命中`() {
        // 空串若命中，整个频道表都会被当成结果
        assertFalse(pinyin.matches("湖南卫视", ""))
    }

    @Test
    fun `中文查询不参与拼音比对`() {
        // 中文查询走 SQL LIKE，不该在拼音里再比一次
        assertFalse(pinyin.matches("湖南卫视", "湖南"))
    }

    @Test
    fun `空表退化成永不命中`() {
        val empty = PinyinTable.EMPTY
        assertEquals(0, empty.size)
        assertFalse(empty.matches("湖南卫视", "hnws"))
        assertNull(empty.syllableOf('湖'))
    }

    @Test
    fun `重复汉字不会破坏解析`() {
        val dup = PinyinTable("电dian\n电dian\n影ying")
        assertEquals("dianying", dup.fullPinyinOf("电影"))
    }
}
