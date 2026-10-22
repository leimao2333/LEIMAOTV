package com.leimao.tv.core.network.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 节目单解析里最容易错的是两件事：**时区**和**重复条目**。
 * 前者错了「现在播什么」整体偏 8 小时，后者错了「下一档」会连着蹦出两个同名节目。
 */
class XmltvParserTest {
    private val xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="1"><display-name lang="zh">CCTV1</display-name></channel>
          <channel id="2"><display-name lang="zh">cctv-1 高清</display-name></channel>
          <channel id="3"><display-name>东方卫视</display-name></channel>
          <channel id="4"></channel>
          <programme start="20261022190000 +0800" stop="20261022210000 +0800" channel="1">
            <title>新闻联播</title>
            <desc>每日新闻</desc>
          </programme>
          <programme start="20261022190000 +0800" stop="20261022210000 +0800" channel="2">
            <title>新闻联播</title>
          </programme>
          <programme start="20261022210000 +0800" stop="20261022220000 +0800" channel="1">
            <title>焦点访谈</title>
          </programme>
          <programme start="20261022210000 +0800" stop="20261022210000 +0800" channel="3">
            <title>缺 stop 的节目</title>
          </programme>
          <programme start="20261022220000 +0800" stop="20261022230000 +0800" channel="99">
            <title>对不上频道的节目</title>
          </programme>
        </tv>
        """.trimIndent()

    @Test
    fun `解析频道与节目`() {
        val result = XmltvParser.parse(xml.byteInputStream())

        assertEquals(3, result.channels.size)
        assertEquals(listOf("CCTV1", "cctv-1 高清", "东方卫视"), result.channels.map { it.name })
        // 缺 display-name 的频道与 stop==start 的节目都算跳过
        assertEquals(2, result.skipped)
        // 解析器不做匹配判断：对不上频道的 ch99 也原样产出，交给合并层丢弃
        assertEquals(4, result.programmes.size)
    }

    @Test
    fun `带时区的时间按偏移量换算`() {
        val start = XmltvTime.parse("20261022190000 +0800")

        val expected =
            Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00")).apply {
                clear()
                set(2026, Calendar.OCTOBER, 22, 19, 0, 0)
            }.timeInMillis
        assertEquals(expected, start)
    }

    @Test
    fun `不带时区的时间按 UTC 解释`() {
        val start = XmltvTime.parse("20261022190000")

        val expected =
            Calendar.getInstance(TimeZone.getTimeZone("GMT")).apply {
                clear()
                set(2026, Calendar.OCTOBER, 22, 19, 0, 0)
            }.timeInMillis
        assertEquals(expected, start)
    }

    @Test
    fun `认不出的时间返回 null 而不是零`() {
        assertNull(XmltvTime.parse(null))
        assertNull(XmltvTime.parse(""))
        assertNull(XmltvTime.parse("2026"))
    }

    @Test
    fun `带 DOCTYPE 的节目单不联网取 DTD 也能解析`() {
        // 真实节目单文件头几乎都带这一行。真去拉它会让导入卡在网络上，
        // 而「为了禁止联网而设的 SAX 特性」在 Android 上又不被支持（会抛异常），
        // 所以这条路必须单独钉一条用例
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "http://xmltv.example.com/xmltv.dtd">
            <tv>
              <channel id="1"><display-name>CCTV1</display-name></channel>
              <programme start="20261022190000 +0800" stop="20261022210000 +0800" channel="1">
                <title>新闻联播 &amp; 天气预报</title>
              </programme>
            </tv>
            """.trimIndent()

        val result = XmltvParser.parse(xml.byteInputStream())

        assertEquals(1, result.channels.size)
        // 内置实体不能被「掐掉外部实体」误伤
        assertEquals("新闻联播 & 天气预报", result.programmes.single().title)
    }
}
