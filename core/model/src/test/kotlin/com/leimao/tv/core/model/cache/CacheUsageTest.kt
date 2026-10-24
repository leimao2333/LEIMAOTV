package com.leimao.tv.core.model.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheUsageTest {
    @Test
    fun `空缓存显示 0 B`() {
        assertEquals("0 B", formatCacheSize(0L))
    }

    @Test
    fun `不足 1KB 给字节数`() {
        assertEquals("1 B", formatCacheSize(1L))
        assertEquals("1023 B", formatCacheSize(1023L))
    }

    @Test
    fun `KB 起留一位小数`() {
        assertEquals("1.0 KB", formatCacheSize(1024L))
        assertEquals("1.5 KB", formatCacheSize(1536L))
        assertEquals("50.0 KB", formatCacheSize(51_200L))
    }

    @Test
    fun `MB 与 GB 逐级进`() {
        assertEquals("1.0 MB", formatCacheSize(1024L * 1024))
        assertEquals("35.6 MB", formatCacheSize((35.6 * 1024 * 1024).toLong()))
        assertEquals("1.5 GB", formatCacheSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `到 100 以上取整`() {
        assertEquals("128 MB", formatCacheSize(128L * 1024 * 1024))
        assertEquals("300 MB", formatCacheSize((300.4 * 1024 * 1024).toLong()))
    }

    @Test
    fun `取整后发现满 1000 就进位`() {
        // 999.6 KB：不许写成「1000 KB」让人自己换算
        assertEquals("1.0 MB", formatCacheSize(1_023_488L))
    }

    @Test
    fun `负数按 0 处理`() {
        // 目录统计在个别设备上会返回 -1（拿不到权限），不能把 "-1 B" 摆到界面上
        assertEquals("0 B", formatCacheSize(-1L))
    }

    @Test
    fun `合计与空判断`() {
        assertEquals(3_000L, CacheUsage(imageBytes = 2_000L, otherBytes = 1_000L).totalBytes)
        assertEquals(true, CacheUsage().isEmpty)
        assertEquals(false, CacheUsage(imageBytes = 1L).isEmpty)
    }
}
