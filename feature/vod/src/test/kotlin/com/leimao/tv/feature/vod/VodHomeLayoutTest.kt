package com.leimao.tv.feature.vod

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 影视首页网格的纯逻辑测试（VOD-03）。
 *
 * 这两件事都曾经靠「在模拟器上看一眼」来判断，而它们各自有一个不容易被肉眼发现的边界：
 * 列数算错会在导航栏收起/展开时露半张卡；位移算错会在「换一换」按十几次之后抛越界。
 */
class VodHomeLayoutTest {
    @Test
    fun `常见内容区宽度下算出 4 列`() {
        // 1080p 电视（960dp）减去导航栏 232 + 间距 28 + 安全区 96 ≈ 604dp
        assertEquals(4, columnsFor(604.dp))
    }

    @Test
    fun `导航栏收起后多放一列`() {
        // 收起后内容区约 780dp：多出的宽度应该变成第 5 列，而不是让海报各自变宽
        assertEquals(5, columnsFor(780.dp))
    }

    @Test
    fun `极窄时也不退化成两列`() {
        // 3 是 columnsFor 的下限：再窄也别退化成两张，一屏两张逛起来像翻相册
        assertEquals(3, columnsFor(200.dp))
    }

    @Test
    fun `位移 0 原样返回`() {
        val items = listOf(1, 2, 3)
        assertSame(items, rotateBy(items, 0))
    }

    @Test
    fun `位移一位把队首挪到队尾`() {
        assertEquals(listOf(2, 3, 1), rotateBy(listOf(1, 2, 3), 1))
    }

    @Test
    fun `位移超过长度也不会越界`() {
        assertEquals(listOf(3, 1, 2), rotateBy(listOf(1, 2, 3), 5))
        assertEquals(listOf(2, 3, 1), rotateBy(listOf(1, 2, 3), 7))
    }

    @Test
    fun `负位移按反方向转`() {
        assertEquals(listOf(3, 1, 2), rotateBy(listOf(1, 2, 3), -1))
    }

    @Test
    fun `空列表与单元素不变`() {
        assertEquals(emptyList<Int>(), rotateBy(emptyList<Int>(), 3))
        assertEquals(listOf(1), rotateBy(listOf(1), 3))
    }
}
