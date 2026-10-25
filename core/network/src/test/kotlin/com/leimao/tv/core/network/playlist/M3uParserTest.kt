package com.leimao.tv.core.network.playlist

import com.leimao.tv.core.model.catchup.CatchupMode
import com.leimao.tv.core.model.playlist.PlaylistFormat
import com.leimao.tv.core.model.playlist.UNGROUPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3U 解析的单测（RULES §9.1：解析器必须有测试，含畸形输入）。
 *
 * 用的全是示例域名 `example.com` —— 测试数据也不许夹带真实源地址（RULES §2）。
 */
class M3uParserTest {
    @Test
    fun `正常文件解析出全部字段`() {
        val text =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV1" tvg-logo="https://example.com/1.png" group-title="央视",CCTV-1
            https://example.com/a.m3u8
            """.trimIndent()

        val result = Playlists.parse(text)

        assertEquals(PlaylistFormat.M3U, result.format)
        assertEquals(1, result.channels.size)
        val channel = result.channels.single()
        assertEquals("CCTV-1", channel.name)
        assertEquals("cctv1", channel.tvgId)
        assertEquals("央视", channel.groupName)
        assertEquals("https://example.com/1.png", channel.logoUrl)
        assertEquals("https://example.com/a.m3u8", channel.url)
        assertEquals(0, result.skippedLines)
    }

    @Test
    fun `缺字段时回退到 tvg-name 和未分组`() {
        val text =
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="只有一个名字"
            https://example.com/b
            """.trimIndent()

        val channel = Playlists.parse(text).channels.single()

        assertEquals("只有一个名字", channel.name)
        assertEquals(UNGROUPED, channel.groupName)
        assertEquals(null, channel.tvgId)
    }

    @Test
    fun `标题和属性里的逗号都不会切错`() {
        val text =
            """
            #EXTINF:-1 group-title="新闻,财经" tvg-name="忽略",CCTV-2, 财经
            https://example.com/c
            """.trimIndent()

        val channel = Playlists.parse(text).channels.single()

        // 属性值里的逗号不参与切分；标题里的逗号属于标题
        assertEquals("新闻,财经", channel.groupName)
        assertEquals("CCTV-2, 财经", channel.name)
    }

    @Test
    fun `BOM 与 CRLF 不影响首行识别`() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:-1,Demo\r\nhttps://example.com/d\r\n"

        val result = Playlists.parse(text)

        assertEquals(PlaylistFormat.M3U, result.format)
        assertEquals("Demo", result.channels.single().name)
    }

    @Test
    fun `空行 注释与 VLC 选项都被忽略`() {
        val text =
            """
            #EXTM3U

            # 这是注释
            #EXTINF:-1,Demo
            #EXTVLCOPT:network-caching=1000
            #KODIPROP:inputstream=foo
            https://example.com/e
            """.trimIndent()

        val result = Playlists.parse(text)

        assertEquals(1, result.channels.size)
        assertEquals("https://example.com/e", result.channels.single().url)
        assertEquals(0, result.skippedLines)
    }

    @Test
    fun `超长行按跳过处理而不是崩溃`() {
        val longAttribute = "x".repeat(MAX_LINE_LENGTH + 100)
        val text = "#EXTM3U\n#EXTINF:-1 tvg-name=\"$longAttribute\"\nhttps://example.com/f"

        val result = Playlists.parse(text)

        assertTrue(result.channels.isEmpty())
        assertTrue(result.skippedLines >= 1)
    }

    @Test
    fun `连续的 EXTINF 只保留最后一条并计数`() {
        val text =
            """
            #EXTM3U
            #EXTINF:-1,第一条
            #EXTINF:-1,第二条
            https://example.com/g
            """.trimIndent()

        val result = Playlists.parse(text)

        assertEquals(1, result.channels.size)
        assertEquals("第二条", result.channels.single().name)
        assertEquals(1, result.skippedLines)
    }

    @Test
    fun `文件末尾缺地址的 EXTINF 计数且不产生频道`() {
        val text = "#EXTM3U\n#EXTINF:-1,悬空"

        val result = Playlists.parse(text)

        assertTrue(result.channels.isEmpty())
        assertEquals(1, result.skippedLines)
    }

    @Test
    fun `EXTGRP 覆盖分组`() {
        val text =
            """
            #EXTINF:-1 group-title="旧分组",Demo
            #EXTGRP:新分组
            https://example.com/h
            """.trimIndent()

        assertEquals("新分组", Playlists.parse(text).channels.single().groupName)
    }

    @Test
    fun `裸地址没有 EXTINF 时丢弃`() {
        val text = "#EXTM3U\nhttps://example.com/i"

        val result = Playlists.parse(text)

        assertTrue(result.channels.isEmpty())
        assertEquals(1, result.skippedLines)
    }

    @Test
    fun `tvg-chno 作为频道号带出`() {
        val text = "#EXTINF:-1 tvg-chno=\"12\",Demo\nhttps://example.com/j"

        assertEquals(12, Playlists.parse(text).channels.single().numberInPlaylist)
    }

    // ---- 回看能力（LIVE-24）----

    @Test
    fun `catchup-source 的模板带出为模板模式`() {
        val text =
            """
            #EXTINF:-1 catchup="default" catchup-source="https://example.com/ts?start=${'$'}{start}&end=${'$'}{end}" catchup-days="5",Demo
            https://example.com/live/1.m3u8
            """.trimIndent()

        val catchup = Playlists.parse(text).channels.single().catchup

        assertEquals(CatchupMode.TEMPLATE, catchup?.mode)
        assertEquals(5, catchup?.days)
    }

    @Test
    fun `声明了能力却没给模板时不产出能力`() {
        val text = "#EXTINF:-1 catchup=\"default\",Demo\nhttps://example.com/live/1.m3u8"

        // 不会拼就不长入口：功能清单要求「不支持时不显示，而不是点了报错」
        assertNull(Playlists.parse(text).channels.single().catchup)
    }

    @Test
    fun `flussonic 与 xtream 按服务端类型识别`() {
        val text =
            """
            #EXTINF:-1 catchup="flussonic",A
            https://example.com/a
            #EXTINF:-1 catchup="xc" timeshift="2",B
            https://example.com/b
            """.trimIndent()

        val channels = Playlists.parse(text).channels

        assertEquals(CatchupMode.FLUSSONIC, channels[0].catchup?.mode)
        assertEquals(CatchupMode.XTREAM, channels[1].catchup?.mode)
        // `timeshift` 与 `catchup-days` 同义：能回看几天
        assertEquals(2, channels[1].catchup?.days)
    }

    @Test
    fun `catchup 关闭时没有能力`() {
        val text = "#EXTINF:-1 catchup=\"0\",Demo\nhttps://example.com/live/1.m3u8"

        assertNull(Playlists.parse(text).channels.single().catchup)
    }

    @Test
    fun `没有 catchup 属性的频道没有能力`() {
        val text = "#EXTINF:-1 group-title=\"央视\",Demo\nhttps://example.com/live/1.m3u8"

        assertNull(Playlists.parse(text).channels.single().catchup)
    }
}
