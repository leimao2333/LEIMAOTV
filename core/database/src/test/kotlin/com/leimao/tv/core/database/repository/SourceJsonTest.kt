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
}
