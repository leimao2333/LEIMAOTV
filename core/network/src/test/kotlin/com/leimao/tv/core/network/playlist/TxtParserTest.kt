package com.leimao.tv.core.network.playlist

import com.leimao.tv.core.model.playlist.PlaylistFormat
import com.leimao.tv.core.model.playlist.UNGROUPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TXT 源解析的单测（LIVE-02）。地址一律 `example.com`，不留真实源（RULES §2）。 */
class TxtParserTest {
    @Test
    fun `genre 行切换分组`() {
        val text =
            """
            央视,#genre#
            CCTV-1,https://example.com/1
            CCTV-2,https://example.com/2
            卫视,#genre#
            湖南卫视,https://example.com/3
            """.trimIndent()

        val result = Playlists.parse(text)

        assertEquals(PlaylistFormat.TXT, result.format)
        assertEquals(3, result.channels.size)
        assertEquals(listOf("央视", "央视", "卫视"), result.channels.map { it.groupName })
    }

    @Test
    fun `没有 genre 行时归入未分组`() {
        val channel = Playlists.parse("Demo,https://example.com/a").channels.single()

        assertEquals(UNGROUPED, channel.groupName)
        assertEquals("Demo", channel.name)
        assertEquals("https://example.com/a", channel.url)
    }

    @Test
    fun `中文逗号与行尾空格都能处理`() {
        val text = "  Demo频道 ，  https://example.com/b  \n"

        val channel = Playlists.parse(text).channels.single()

        assertEquals("Demo频道", channel.name)
        assertEquals("https://example.com/b", channel.url)
    }

    @Test
    fun `注释行不算频道也不算跳过`() {
        val text =
            """
            # 这是注释
            Demo,https://example.com/c
            """.trimIndent()

        val result = Playlists.parse(text)

        assertEquals(1, result.channels.size)
        assertEquals(0, result.skippedLines)
    }

    @Test
    fun `畸形行被计数并跳过`() {
        val text =
            """
            没有分隔符的一行
            ,https://example.com/d
            Demo,
            Demo,https://example.com/e
            """.trimIndent()

        val result = Playlists.parse(text)

        assertEquals(1, result.channels.size)
        assertEquals(3, result.skippedLines)
    }

    @Test
    fun `BOM 与空行不影响解析`() {
        val text = "\uFEFF\n\nDemo,https://example.com/f\n"

        val result = Playlists.parse(text)

        assertEquals(1, result.channels.size)
        assertEquals("Demo", result.channels.single().name)
    }

    @Test
    fun `空 genre 名回落到未分组`() {
        val text = ",#genre#\nDemo,https://example.com/g"

        assertEquals(UNGROUPED, Playlists.parse(text).channels.single().groupName)
    }

    @Test
    fun `超长行不影响后续行`() {
        val text = "${"y".repeat(MAX_LINE_LENGTH + 1)},https://example.com/h\nDemo,https://example.com/i"

        val result = Playlists.parse(text)

        assertEquals(1, result.channels.size)
        assertEquals("Demo", result.channels.single().name)
        assertEquals(1, result.skippedLines)
    }

    @Test
    fun `空文本得到空结果`() {
        val result = Playlists.parse("   \n\n")

        assertTrue(result.channels.isEmpty())
        assertEquals(PlaylistFormat.TXT, result.format)
    }
}
