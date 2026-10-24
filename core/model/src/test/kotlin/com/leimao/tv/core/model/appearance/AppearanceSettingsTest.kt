package com.leimao.tv.core.model.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun `从存储读档位`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorage("LIGHT"))
        assertEquals(FontScale.LARGE, FontScale.fromStorage("LARGE"))
    }

    @Test
    fun `认不出来的值回落默认`() {
        // 枚举改过名之后旧值还在 DataStore 里，不能为一个显示设置把设置页搞崩
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStorage("MIDNIGHT"))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromStorage(""))
        assertEquals(FontScale.DEFAULT, FontScale.fromStorage("GIGANTIC"))
        assertEquals(FontScale.DEFAULT, FontScale.fromStorage(null))
    }

    @Test
    fun `三档主题与系统两种状态的全部组合`() {
        assertTrue(shouldUseDarkTheme(ThemeMode.DARK, systemInDark = true))
        assertTrue(shouldUseDarkTheme(ThemeMode.DARK, systemInDark = false))
        assertFalse(shouldUseDarkTheme(ThemeMode.LIGHT, systemInDark = true))
        assertFalse(shouldUseDarkTheme(ThemeMode.LIGHT, systemInDark = false))
        assertTrue(shouldUseDarkTheme(ThemeMode.SYSTEM, systemInDark = true))
        assertFalse(shouldUseDarkTheme(ThemeMode.SYSTEM, systemInDark = false))
    }

    @Test
    fun `字号档位单调递增且标准档是 1`() {
        val scales = FontScale.entries.map { it.scale }
        assertEquals(scales.sorted(), scales)
        assertEquals(1.0f, FontScale.STANDARD.scale)
        assertTrue(FontScale.HUGE.scale <= 1.3f)
    }

    @Test
    fun `最小档仍守住文字底线`() {
        // RULES §6.3：正文 16sp、提示 14sp；缩到最小档也不能低于 14sp / 12sp
        val smallest = FontScale.entries.minOf { it.scale }
        assertTrue(16f * smallest >= 14f)
        assertTrue(14f * smallest >= 12f)
    }
}
