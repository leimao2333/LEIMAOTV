package com.leimao.tv.core.database.repository

import com.leimao.tv.core.model.appearance.FontScale
import com.leimao.tv.core.model.appearance.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                { "type": "VOD", "name": "某影视配置", "url": "https://example.com/vod.txt", "enabled": false, "format": "moontv" }
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
        assertEquals(SourceFormat.MOONTV, decoded[2].format)
    }

    @Test
    fun `format 缺省为 applecms 且 moontv-encrypted 归一为 moontv`() {
        // 直播源没有 format 字段 → 落回 applecms
        val liveJson =
            """
            {"version":1,"sources":[
              {"type":"LIVE","name":"台","url":"https://example.com/a.txt"}
            ]}
            """.trimIndent()
        val noFormat = SourceJson.decode(liveJson)
        assertEquals(SourceFormat.APPLECMS, noFormat[0].format)

        // 外部文件可能写 moontv-encrypted，也要认成 moontv
        val vodJson =
            """
            {"version":1,"sources":[
              {"type":"VOD","name":"影","url":"https://example.com/b.txt","format":"moontv-encrypted"}
            ]}
            """.trimIndent()
        val encrypted = SourceJson.decode(vodJson)
        assertEquals(SourceFormat.MOONTV, encrypted[0].format)
    }

    @Test
    fun `moontv 格式往返保留`() {
        val source = sampleSource().copy(type = SourceType.VOD, format = SourceFormat.MOONTV)
        val json = SourceJson.encode(listOf(source))
        val decoded = SourceJson.decode(json)
        assertEquals(SourceFormat.MOONTV, decoded[0].format)
    }

    // ---- 导入去重（SET-03）----

    @Test
    fun `导入时跳过已存在地址`() {
        val parsed =
            listOf(
                sampleSource().copy(url = "https://example.com/a.txt"),
                sampleSource().copy(url = "https://example.com/b.txt"),
            )

        val (fresh, skipped) = SourceJson.filterNew(parsed, setOf("https://example.com/a.txt"))

        assertEquals(1, fresh.size)
        assertEquals("https://example.com/b.txt", fresh[0].url)
        assertEquals(1, skipped)
    }

    @Test
    fun `文件内部重复的地址只留第一个`() {
        val parsed =
            listOf(
                sampleSource().copy(name = "甲", url = "https://example.com/same.txt"),
                sampleSource().copy(name = "乙", url = "https://example.com/same.txt"),
            )

        val (fresh, skipped) = SourceJson.filterNew(parsed, emptySet())

        // 同一个地址挂两个名字是上游清单的常见写法，但本地只该有一条源
        assertEquals(1, fresh.size)
        assertEquals("甲", fresh[0].name)
        assertEquals(1, skipped)
    }

    @Test
    fun `地址两端的空白不影响判重`() {
        val parsed = listOf(sampleSource().copy(url = "  https://example.com/a.txt  "))

        val (fresh, skipped) = SourceJson.filterNew(parsed, setOf("https://example.com/a.txt"))

        assertTrue(fresh.isEmpty())
        assertEquals(1, skipped)
    }

    @Test
    fun `没有重复时全部保留`() {
        val parsed = listOf(sampleSource().copy(url = "https://example.com/a.txt"))

        val (fresh, skipped) = SourceJson.filterNew(parsed, emptySet())

        assertEquals(1, fresh.size)
        assertEquals(0, skipped)
    }

    // ---- 远程偏好（LIVE-08）----

    @Test
    fun `备份里的 settings 段被解析出来`() {
        val json =
            """
            {"version":1,"settings":{"refreshHours":12,"theme":"dark","fontScale":"LARGE"},
             "sources":[{"type":"LIVE","name":"台","url":"https://example.com/a.m3u"}]}
            """.trimIndent()

        val settings = SourceJson.decodeSettings(json)!!

        assertEquals(12, settings.refreshHours)
        // 小写也认：远程配置是手写的，不该强求大小写
        assertEquals(ThemeMode.DARK, settings.theme)
        assertEquals(FontScale.LARGE, settings.fontScale)
        assertEquals("刷新 12h · 深色 · 大", settings.describe())
    }

    @Test
    fun `没有 settings 段返回 null`() {
        val json = """{"version":1,"sources":[{"type":"LIVE","name":"台","url":"https://example.com/a.m3u"}]}"""

        // 普通备份文件照样能用 —— 远程配置是附加能力，不是新格式
        assertNull(SourceJson.decodeSettings(json))
    }

    @Test
    fun `非法取值按不修改处理`() {
        val json =
            """
            {"version":1,"settings":{"refreshHours":7,"theme":"PURPLE","fontScale":"巨大"},"sources":[]}
            """.trimIndent()

        // 三项全非法 → 整段等于没写，本地设置一个都不动
        assertNull(SourceJson.decodeSettings(json))
    }

    @Test
    fun `只写一项时只改那一项`() {
        val json = """{"version":1,"settings":{"refreshHours":0},"sources":[]}"""

        val settings = SourceJson.decodeSettings(json)!!

        assertEquals(0, settings.refreshHours)
        assertNull(settings.theme)
        assertNull(settings.fontScale)
        assertEquals("关闭自动刷新", settings.describe())
    }
}
