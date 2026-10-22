package com.leimao.tv.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自动刷新周期（LIVE-07）的纯逻辑测试。 */
class RefreshIntervalTest {
    @Test
    fun `默认档是 24 小时`() {
        assertEquals(RefreshInterval.HOURS_24, RefreshInterval.DEFAULT)
    }

    @Test
    fun `关闭档 hours 为 null`() {
        assertNull(RefreshInterval.OFF.hours)
    }

    @Test
    fun `非关闭档都有小时数`() {
        RefreshInterval.entries
            .filter { it != RefreshInterval.OFF }
            .forEach { interval -> assert(interval.hours != null) }
    }

    @Test
    fun `反解析_不认识的回落默认`() {
        assertEquals(RefreshInterval.DEFAULT, RefreshInterval.fromName(null))
        assertEquals(RefreshInterval.DEFAULT, RefreshInterval.fromName(""))
        assertEquals(RefreshInterval.DEFAULT, RefreshInterval.fromName("NOPE"))
    }

    @Test
    fun `反解析_认识的名称原样取回`() {
        RefreshInterval.entries.forEach { interval ->
            assertEquals(interval, RefreshInterval.fromName(interval.name))
        }
    }
}
