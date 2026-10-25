package com.leimao.tv.core.network.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MoonTV 加密订阅配置解析。
 *
 * 测试向量里的站点域名一律用 `example.com`、名字是「测试站」这类占位，
 * 不含任何真实源信息（RULES §2.1）。Base58 串是构造的假 JSON 编码，结构对齐
 * 真实 moontv 订阅（`{cache_time, api_site:{key:{name,api,detail}}}`）。
 *
 * 编码用的 [base58Encode]（BigInteger 实现）与生产的 [com.leimao.tv.core.network.Base58]
 * 是两套独立实现，不存在「编解互证」的自圆其说。
 */
class MoontvConfigTest {
    // 两个正常站 + 一个缺 api 的站（应被丢弃）
    private val validJson =
        """
        {
          "cache_time": 7200,
          "api_site": {
            "site-a.example.com": { "name": "测试站A", "api": "https://site-a.example.com/api.php/provide/vod" },
            "site-b.example.com": { "name": "测试站B", "api": "https://site-b.example.com/api.php/provide/vod" },
            "broken.example.com": { "name": "缺api的站" }
          }
        }
        """.trimIndent()

    private val valid: String = encodeJson(validJson)

    @Test
    fun `解码出全部正常站点 缺 api 的丢弃`() {
        val sites = MoontvConfig.parse(valid)
        assertEquals(2, sites.size)
        assertEquals("site-a.example.com", sites[0].key)
        assertEquals("测试站A", sites[0].name)
        assertEquals("https://site-a.example.com/api.php/provide/vod", sites[0].api)
        assertEquals("site-b.example.com", sites[1].key)
        assertEquals("测试站B", sites[1].name)
    }

    @Test
    fun `缺名字的站点用 key 兜底`() {
        val noName = encodeJson("""{"api_site":{"k.example.com":{"api":"https://k.example.com/vod"}}}""")
        val sites = MoontvConfig.parse(noName)
        assertEquals(1, sites.size)
        assertEquals("k.example.com", sites[0].name)
    }

    @Test
    fun `不是 Base58 要报错`() {
        assertThrows(IllegalArgumentException::class.java) { MoontvConfig.parse("0OIl 非法字符!") }
    }

    @Test
    fun `缺 api_site 要报错`() {
        val noSite = encodeJson("""{"cache_time":7200,"foo":"bar"}""")
        assertTrue(errorMessage { MoontvConfig.parse(noSite) }.contains("api_site"))
    }

    @Test
    fun `全是缺 api 的站要报错`() {
        val allBroken = encodeJson("""{"api_site":{"a":{"name":"只有名字"}}}""")
        assertTrue(errorMessage { MoontvConfig.parse(allBroken) }.contains("没有任何可用站点"))
    }

    private fun encodeJson(json: String): String = base58Encode(json.toByteArray(Charsets.UTF_8))

    private fun errorMessage(block: () -> Unit): String =
        try {
            block()
            ""
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
}

/** 测试用 Base58 编码（与生产 [com.leimao.tv.core.network.Base58] 解码互为逆向）。 */
private fun base58Encode(bytes: ByteArray): String {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var n = java.math.BigInteger(1, bytes)
    val base = java.math.BigInteger.valueOf(58)
    val sb = StringBuilder()
    while (n > java.math.BigInteger.ZERO) {
        val rem = n.mod(base).toInt()
        sb.insert(0, alphabet[rem])
        n = n.divide(base)
    }
    var pad = 0
    for (b in bytes) {
        if (b.toInt() == 0) pad += 1 else break
    }
    return "1".repeat(pad) + sb
}
