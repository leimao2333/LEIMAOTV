package com.leimao.tv.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release 正文的「减法」（SET-07）。
 *
 * 这里钉的是**不做什么**：不去解析完整 markdown、不保留 `##`、不把几十行正文全铺到电视上。
 * 一旦有人把这里的截断去掉，更新页会变成一屏读不完的发布说明，把「下载」按钮挤到折叠线以下。
 */
class UpdateStateTest {
    @Test
    fun `空正文给空列表`() {
        assertTrue(summarizeReleaseNotes("").isEmpty())
        assertTrue(summarizeReleaseNotes("   \n\n  ").isEmpty())
    }

    @Test
    fun `标题记号去掉、列表符号换成点`() {
        val lines = summarizeReleaseNotes("## 新增\n- 支持检查更新\n- 下完直接装")
        assertEquals(listOf("新增", "· 支持检查更新", "· 下完直接装"), lines)
    }

    @Test
    fun `分隔线与空行被丢掉`() {
        val lines = summarizeReleaseNotes("第一段\n\n---\n\n第二段\n***\n")
        assertEquals(listOf("第一段", "第二段"), lines)
    }

    @Test
    fun `加粗记号原样保留给渲染层`() {
        // 这里只做减法，`**` 交给 renderBoldMarkdown；提前吃掉会让加粗失效
        val lines = summarizeReleaseNotes("**重要**：装完要重启")
        assertEquals(listOf("**重要**：装完要重启"), lines)
    }

    @Test
    fun `行数超限时截断并说明去哪里看`() {
        val notes = (1..40).joinToString("\n") { "第 $it 行" }
        val lines = summarizeReleaseNotes(notes, maxLines = 5)
        assertEquals(6, lines.size)
        assertEquals("第 5 行", lines[4])
        assertTrue(lines.last().startsWith("…"))
    }

    @Test
    fun `字数超限时也截断`() {
        val notes = (1..20).joinToString("\n") { "这一行有二十个字符的内容占位" }
        val lines = summarizeReleaseNotes(notes, maxChars = 30)
        assertTrue(lines.size < 20)
        assertTrue(lines.last().startsWith("…"))
    }

    @Test
    fun `纯标题行不留下空条目`() {
        assertEquals(listOf("正文"), summarizeReleaseNotes("###\n正文"))
    }

    @Test
    fun `下载进度百分比在总量未知时不显示`() {
        assertNull(DownloadProgress(bytesRead = 100L, totalBytes = 0L).percent)
        assertEquals(50, DownloadProgress(bytesRead = 50L, totalBytes = 100L).percent)
        // 对方给的总量偏小时不要显示 120%
        assertEquals(100, DownloadProgress(bytesRead = 120L, totalBytes = 100L).percent)
    }
}
