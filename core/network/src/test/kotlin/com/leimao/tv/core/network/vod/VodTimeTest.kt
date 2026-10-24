package com.leimao.tv.core.network.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `vod_time` 解析（VOD-03 的「最近更新」排序依赖它）。
 *
 * 断言用的是**固定毫秒值**而不是「再算一遍」：这个函数最容易出的错就是把时区当成了
 * 设备默认时区，那样在 CI（UTC）和开发机（+08）上结果不同，而「再算一遍」的断言
 * 会跟着一起错，等于没测。
 */
class VodTimeTest {
    @Test
    fun `完整时间按东八区解析`() {
        // 2024-01-15 12:30:00 +0800 == 2024-01-15T04:30:00Z
        assertEquals(1_705_293_000_000L, VodTime.parse("2024-01-15 12:30:00"))
    }

    @Test
    fun `只有日期时按当天零点`() {
        // 2024-01-15 00:00:00 +0800 == 2024-01-14T16:00:00Z
        assertEquals(1_705_248_000_000L, VodTime.parse("2024-01-15"))
    }

    @Test
    fun `T 分隔与前后缀都能认`() {
        assertEquals(1_705_293_000_000L, VodTime.parse("2024-01-15T12:30:00"))
        assertEquals(1_705_293_000_000L, VodTime.parse("更新时间：2024-01-15 12:30:00 "))
    }

    @Test
    fun `空值与乱码返回 null`() {
        assertNull(VodTime.parse(null))
        assertNull(VodTime.parse(""))
        assertNull(VodTime.parse("   "))
        assertNull(VodTime.parse("刚刚更新"))
        assertNull(VodTime.parse("0000-00-00 00:00:00"))
    }

    @Test
    fun `越界字段一律丢弃`() {
        // 宁可让排序退化成入库顺序，也不要把脏数据排到最前面或挤到 1970 年
        assertNull(VodTime.parse("2024-13-15 12:30:00"))
        assertNull(VodTime.parse("2024-01-32 12:30:00"))
        assertNull(VodTime.parse("2024-01-15 25:00:00"))
        assertNull(VodTime.parse("1899-01-15 12:30:00"))
    }
}
