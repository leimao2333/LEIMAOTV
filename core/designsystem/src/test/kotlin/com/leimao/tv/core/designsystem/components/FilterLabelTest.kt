package com.leimao.tv.core.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 筛选维度文案（VOD-12 / LIVE-26）。
 *
 * 一个词就能说清「当前筛的是什么」：未选显示维度名、已选显示值。
 * 多维度叠加时每个触发器各自独立，互不干扰。
 */
class FilterLabelTest {
    @Test
    fun `未选时显示维度名`() {
        assertEquals("地区", filterLabel("地区", null))
        assertEquals("年份", filterLabel("年份", ""))
    }

    @Test
    fun `已选时显示所选值`() {
        assertEquals("美国", filterLabel("地区", "美国"))
        assertEquals("2024", filterLabel("年份", "2024"))
    }

    @Test
    fun `多个维度各自独立`() {
        // 三个维度同时存在：地区选了、年份没选、标签选了，各自的文案互不影响
        assertEquals("美国", filterLabel("地区", "美国"))
        assertEquals("年份", filterLabel("年份", null))
        assertEquals("动作", filterLabel("标签", "动作"))
    }
}
