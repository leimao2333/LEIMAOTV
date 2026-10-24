package com.leimao.tv.core.model.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSettingsTest {
    // ---- 代理地址解析 ----

    @Test
    fun `标准的 主机冒号端口`() {
        val parsed = parseProxyAddress("192.168.1.2:7890") as ProxyParse.Ok
        assertEquals("192.168.1.2", parsed.config.host)
        assertEquals(7890, parsed.config.port)
        assertEquals("192.168.1.2:7890", parsed.config.address)
    }

    @Test
    fun `允许粘贴浏览器地址栏的写法`() {
        // 从浏览器复制过来的地址常带协议头与末尾斜杠，纠这些只会浪费用户时间
        val parsed = parseProxyAddress("  http://192.168.1.2:7890/  ") as ProxyParse.Ok
        assertEquals("192.168.1.2", parsed.config.host)
        assertEquals(7890, parsed.config.port)
    }

    @Test
    fun `空输入表示直连`() {
        assertTrue(parseProxyAddress("") is ProxyParse.Direct)
        assertTrue(parseProxyAddress("   ") is ProxyParse.Direct)
    }

    @Test
    fun `缺端口要说清缺什么`() {
        val parsed = parseProxyAddress("192.168.1.2") as ProxyParse.Invalid
        assertTrue(parsed.reason.contains("端口"))
    }

    @Test
    fun `端口不是数字`() {
        val parsed = parseProxyAddress("192.168.1.2:abc") as ProxyParse.Invalid
        assertTrue(parsed.reason.contains("数字"))
    }

    @Test
    fun `端口越界`() {
        assertTrue(parseProxyAddress("192.168.1.2:0") is ProxyParse.Invalid)
        assertTrue(parseProxyAddress("192.168.1.2:65536") is ProxyParse.Invalid)
    }

    @Test
    fun `拒绝 SOCKS`() {
        // HTTP 代理与 SOCKS 是两种 java.net.Proxy.Type，混用只会在连接阶段报一个看不懂的错
        val parsed = parseProxyAddress("socks5://127.0.0.1:1080") as ProxyParse.Invalid
        assertTrue(parsed.reason.contains("SOCKS"))
    }

    @Test
    fun `拒绝带账号密码的代理`() {
        val parsed = parseProxyAddress("user:pass@10.0.0.1:8080") as ProxyParse.Invalid
        assertTrue(parsed.reason.contains("账号密码"))
    }

    @Test
    fun `IPv6 要加方括号`() {
        val ok = parseProxyAddress("[::1]:8080") as ProxyParse.Ok
        assertEquals("::1", ok.config.host)
        assertEquals(8080, ok.config.port)
        assertTrue(parseProxyAddress("::1:8080") is ProxyParse.Invalid)
    }

    @Test
    fun `主机里不能有空格`() {
        assertTrue(parseProxyAddress("192.168.1.2 :7890") is ProxyParse.Invalid)
    }

    @Test
    fun `存储里读到的坏地址按直连处理`() {
        // 宁可直连，也不要拿一个坏地址把所有请求堵死 —— 那时用户连设置页都打不开源列表
        assertNull(proxyFromStorage("这不是地址"))
        assertNull(proxyFromStorage(null))
        assertNotNull(proxyFromStorage("10.0.0.1:8080"))
    }

    // ---- 档位 ----

    @Test
    fun `认不出的档位回落到默认`() {
        assertEquals(TimeoutProfile.STANDARD, TimeoutProfile.fromStorage("GONE"))
        assertEquals(TimeoutProfile.STANDARD, TimeoutProfile.fromStorage(null))
        assertEquals(ConcurrencyProfile.STANDARD, ConcurrencyProfile.fromStorage("GONE"))
        assertEquals(TimeoutProfile.RELAXED, TimeoutProfile.fromStorage("RELAXED"))
    }

    @Test
    fun `读超时都不小于连接超时`() {
        // 两个数分开给是因为成因不同：连不上是到不了对方，读不完是对方在慢慢吐。
        // 但读超时比连接还短没有意义 —— 那等于连接刚成功就判失败
        TimeoutProfile.entries.forEach { profile ->
            assertTrue(
                "${profile.name} 的读超时短于连接超时",
                profile.readSeconds >= profile.connectSeconds,
            )
        }
    }

    @Test
    fun `搜索预算不超过读超时`() {
        // 源都没来得及把第一条结果吐回来就判「这条源没查完」，是把超时档位架空了一半
        TimeoutProfile.entries.forEach { profile ->
            assertTrue(
                "${profile.name} 的搜索预算比读超时还长",
                profile.searchTimeoutMs <= profile.readSeconds * 1000L,
            )
        }
        // 标准档必须还是 8 秒，否则升级一次就悄悄改了所有老用户的搜索节奏
        assertEquals(8_000L, TimeoutProfile.STANDARD.searchTimeoutMs)
    }

    @Test
    fun `并发档位单调且刷新比搜索宽`() {
        val permits = ConcurrencyProfile.entries.map { it.permits }
        assertEquals(permits.sorted(), permits)
        ConcurrencyProfile.entries.forEach { profile ->
            assertTrue(profile.refreshPermits > profile.permits)
        }
        // 标准档必须与 SET-04 之前写死的 4 / 6 对上，否则升级一次就悄悄改了所有老用户的网络行为
        assertEquals(4, ConcurrencyProfile.STANDARD.permits)
        assertEquals(6, ConcurrencyProfile.STANDARD.refreshPermits)
    }
}
