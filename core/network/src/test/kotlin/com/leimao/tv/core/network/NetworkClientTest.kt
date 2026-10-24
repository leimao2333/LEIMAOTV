package com.leimao.tv.core.network

import com.leimao.tv.core.model.network.NetworkSettings
import com.leimao.tv.core.model.network.ProxyConfig
import com.leimao.tv.core.model.network.TimeoutProfile
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class NetworkClientTest {
    private val base = OkHttpClient()

    @Test
    fun `超时按档位落到 client 上`() {
        TimeoutProfile.entries.forEach { profile ->
            val client = buildNetworkClient(base, NetworkSettings(timeout = profile))
            assertEquals(profile.name, profile.connectSeconds * 1000, client.connectTimeoutMillis)
            assertEquals(profile.name, profile.readSeconds * 1000, client.readTimeoutMillis)
        }
    }

    @Test
    fun `直连不落到系统代理上`() {
        // proxy(null) 会让 OkHttp 回落到 ProxySelector（读系统代理），
        // 所以「用户选直连」必须显式写成 NO_PROXY
        val client = buildNetworkClient(base, NetworkSettings(proxy = null))
        assertEquals(Proxy.NO_PROXY, client.proxy)
    }

    @Test
    fun `配了代理就按 HTTP 代理连`() {
        val client =
            buildNetworkClient(base, NetworkSettings(proxy = ProxyConfig(host = "10.0.0.2", port = 8080)))
        val proxy = client.proxy
        assertEquals(Proxy.Type.HTTP, proxy?.type())
        assertEquals(InetSocketAddress("10.0.0.2", 8080), proxy?.address())
    }

    @Test
    fun `派生不丢连接池`() {
        // 每次改设置（下次启动）都派生一个新 client；若连接池不共用，等于每次启动都从零建连，
        // 「全应用一个 OkHttp 实例」的收益就没了
        val derived = buildNetworkClient(base, NetworkSettings())
        assertSame(base.connectionPool, derived.connectionPool)
        assertSame(base.dispatcher, derived.dispatcher)
    }

    @Test
    fun `基线 client 自己没被改动`() {
        // 派生必须是纯函数：base 被就地改了，同一进程里其他持有 base 的地方会静默换行为
        buildNetworkClient(base, NetworkSettings(timeout = TimeoutProfile.FAST, proxy = ProxyConfig("1.1.1.1", 1080)))
        assertEquals(TimeoutProfile.STANDARD.connectSeconds * 1000, base.connectTimeoutMillis)
        assertNull(base.proxy)
    }
}
