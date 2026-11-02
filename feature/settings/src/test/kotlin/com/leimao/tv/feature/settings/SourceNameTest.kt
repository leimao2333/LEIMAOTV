package com.leimao.tv.feature.settings

import com.leimao.tv.core.database.repository.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源名称自动生成（SET-03）。
 *
 * 钉住的是「名字要能区分源」：同一台设备上往往有好几条源，全叫「订阅源」等于没有名字。
 * 另外中转地址要取路径里的真实主机 —— 走加速的源全叫 `gh-proxy.com` 同样没有信息量。
 */
class SourceNameTest {
    @Test
    fun `取主机名作名称`() {
        assertEquals(
            "raw.githubusercontent.com",
            sourceNameFromUrl("https://raw.githubusercontent.com/foo/bar/main/live.txt", SourceType.LIVE),
        )
    }

    @Test
    fun `去掉 www 前缀`() {
        assertEquals("example.com", sourceNameFromUrl("https://www.example.com/live.m3u", SourceType.LIVE))
    }

    @Test
    fun `没写协议头也能解析`() {
        assertEquals("example.com", sourceNameFromUrl("example.com/live.m3u", SourceType.LIVE))
    }

    @Test
    fun `中转地址取路径里的真实主机`() {
        assertEquals(
            "raw.githubusercontent.com",
            sourceNameFromUrl(
                "https://gh-proxy.com/raw.githubusercontent.com/leimao2333/leimaotv-sources/main/dist/live.txt",
                SourceType.LIVE,
            ),
        )
    }

    @Test
    fun `空地址回落到默认名`() {
        assertEquals("订阅源", sourceNameFromUrl("", SourceType.LIVE))
        assertEquals("影视源", sourceNameFromUrl("   ", SourceType.VOD))
    }

    @Test
    fun `解析不出主机时回落到默认名`() {
        // 只打了半截、没有点号的主机名：与其把「abc」当名字，不如用默认名
        assertEquals("订阅源", sourceNameFromUrl("abc", SourceType.LIVE))
    }

    @Test
    fun `带端口时名称不含端口`() {
        assertEquals("example.com", sourceNameFromUrl("http://example.com:8080/live.m3u", SourceType.LIVE))
    }

    // ---- 源备份地址识别（SET-10）----

    @Test
    fun `json 地址认成源备份`() {
        assertTrue(looksLikeSourceBackupUrl("https://example.com/dist/sources.json"))
        // 带查询串也算：CDN 常常加 ?v=1
        assertTrue(looksLikeSourceBackupUrl("https://example.com/sources.json?v=2"))
    }

    @Test
    fun `播放列表地址不当成备份`() {
        assertFalse(looksLikeSourceBackupUrl("https://example.com/live.m3u"))
        assertFalse(looksLikeSourceBackupUrl("https://example.com/live.txt"))
        assertFalse(looksLikeSourceBackupUrl("https://example.com/epg.xml"))
    }

    // ---- 按内容识别备份（SET-14 混合导入）----

    @Test
    fun `内容像备份就按备份导入`() {
        // 改名成 .txt、或者干脆没有扩展名的备份也能认出来
        assertTrue(looksLikeSourceBackupText("""{"version":1,"sources":[{"url":"https://a.com/x.m3u"}]}"""))
        // 前面带 BOM / 空白不影响：很多手机上的编辑器会加
        assertTrue(looksLikeSourceBackupText("\uFEFF  \n{\"sources\":[]}"))
    }

    @Test
    fun `播放列表内容不当成备份`() {
        // 播放列表永远不是 JSON 对象，这两条必须为假，否则 m3u 会被送去当备份解析
        assertFalse(looksLikeSourceBackupText("#EXTM3U\n#EXTINF:-1,CCTV1\nhttp://a.com/1.m3u8"))
        assertFalse(looksLikeSourceBackupText("CCTV1,http://a.com/1.m3u8"))
        // 有 sources 字样但不是 JSON 对象（比如一段说明文字）
        assertFalse(looksLikeSourceBackupText("这里的 sources 指的是频道来源"))
    }
}
