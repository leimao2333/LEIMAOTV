package com.leimao.tv.core.database.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceJsonTest {
    private fun sampleSource() =
        Source(
            id = 1,
            type = SourceType.LIVE,
            name = "测试台",
            url = "https://example.com/playlist.m3u8?token=abc",
            enabled = true,
            userAgent = "Mozilla/5.0",
            referer = "https://example.com/",
            basicUser = "user",
            basicPass = "p@ss\"word",
            sortOrder = 0,
        )

    @Test
    fun `往返后字段一致`() {
        val sources = listOf(sampleSource(), sampleSource().copy(id = 2, name = "第二个", enabled = false))
        val json = SourceJson.encode(sources)
        val decoded = SourceJson.decode(json)

        assertEquals(2, decoded.size)
        assertEquals("测试台", decoded[0].name)
        assertEquals("https://example.com/playlist.m3u8?token=abc", decoded[0].url)
        assertEquals(SourceType.LIVE, decoded[0].type)
        assertEquals("Mozilla/5.0", decoded[0].userAgent)
        assertEquals("https://example.com/", decoded[0].referer)
        assertEquals("user", decoded[0].basicUser)
        assertEquals("p@ss\"word", decoded[0].basicPass)
        assertTrue(decoded[0].enabled)
        assertEquals(false, decoded[1].enabled)
    }

    @Test
    fun `空字段不写出来，解析后为 null`() {
        val source =
            sampleSource().copy(
                userAgent = null,
                referer = null,
                basicUser = null,
                basicPass = null,
            )
        val json = SourceJson.encode(listOf(source))
        val decoded = SourceJson.decode(json)

        assertEquals(null, decoded[0].userAgent)
        assertEquals(null, decoded[0].referer)
        assertEquals(null, decoded[0].basicUser)
        assertEquals(null, decoded[0].basicPass)
    }

    @Test
    fun `特殊字符被正确转义`() {
        val source = sampleSource().copy(name = "带\"引号\"和\\反斜杠\n换行")
        val json = SourceJson.encode(listOf(source))
        val decoded = SourceJson.decode(json)

        assertEquals("带\"引号\"和\\反斜杠\n换行", decoded[0].name)
    }

    @Test
    fun `非 JSON 内容抛异常`() {
        try {
            SourceJson.decode("这不是 JSON")
            throw AssertionError("应当抛异常")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `空 sources 列表抛异常`() {
        try {
            SourceJson.decode("{\"version\":1,\"sources\":[]}")
            throw AssertionError("应当抛异常")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `聚合源 sources json 格式可解析`() {
        // leimaotv-sources 仓库 dist/sources.json 的真实形态：
        // 每个源只有 type/name/url/enabled 四个字段，不含 userAgent 等可选字段，
        // 且允许出现 enabled=false 的 VOD 源。这条钉住「聚合源清单能直接导入」。
        val json =
            """
            {
              "version": 1,
              "sources": [
                { "type": "LIVE", "name": "IPTV4 · 综合直播", "url": "https://live.example.com/tv/iptv4.txt", "enabled": true },
                { "type": "LIVE", "name": "IPTV6 · 某源", "url": "https://example.com/iptv6.txt", "enabled": true },
                { "type": "VOD", "name": "某影视配置", "url": "https://example.com/vod.txt", "enabled": false }
              ]
            }
            """.trimIndent()

        val decoded = SourceJson.decode(json)

        assertEquals(3, decoded.size)
        assertEquals("IPTV4 · 综合直播", decoded[0].name)
        assertEquals(SourceType.LIVE, decoded[0].type)
        assertTrue(decoded[0].enabled)
        assertEquals(null, decoded[0].userAgent)
        assertEquals(SourceType.VOD, decoded[2].type)
        assertEquals(false, decoded[2].enabled)
        assertEquals("某影视配置", decoded[2].name)
    }
}
