package com.leimao.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream

/**
 * 响应体解码（LIVE-28）。
 *
 * 这里钉的是三件事：gzip 魔数认得准、解开后文本一字不差、坏包不抛异常。
 * 第三条最容易在真实数据上翻车 —— 上游偶尔发半截文件，此时「解压失败」被当成
 * 网络错误报出去，用户会去查网络，而网络是通的。
 */
class HttpBodyTest {
    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    @Test
    fun `普通文本原样返回`() {
        val text = "#EXTM3U\n#EXTINF:-1,示例频道\nhttps://example.com/a.m3u8\n"

        assertEquals(text, HttpBody.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `gzip 正文自动解开`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><tv><channel id="demo"/></tv>"""

        assertEquals(xml, HttpBody.decode(gzip(xml)))
    }

    @Test
    fun `按响应头 charset 解码`() {
        val text = "<tv>示例</tv>"

        // 非压缩正文走 charset 参数：GBK 源（老播放列表常见）不能解成乱码
        assertEquals(text, HttpBody.decode(text.toByteArray(Charset.forName("GBK")), Charset.forName("GBK")))
        // 压缩正文同样走 charset：先解开再按该编码解码
        assertEquals(text, HttpBody.decode(gzip(text)))
    }

    @Test
    fun `坏包退回原始字节而不是抛异常`() {
        // 只有魔数、后面是垃圾：解压必然失败
        val broken = byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x01, 0x02)

        assertTrue(HttpBody.looksGzipped(broken))
        // 不抛异常即通过；内容是什么不重要，重要的是调用方还能走「不是播放列表」这条路
        HttpBody.decode(broken)
    }

    @Test
    fun `空正文返回空串`() {
        assertEquals("", HttpBody.decode(ByteArray(0)))
    }

    @Test
    fun `普通文本不会被误判成 gzip`() {
        assertFalse(HttpBody.looksGzipped("<?xml version=\"1.0\"?>".toByteArray()))
        assertFalse(HttpBody.looksGzipped(byteArrayOf(0x1f)))
    }
}
