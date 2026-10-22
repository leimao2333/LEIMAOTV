package com.leimao.tv.core.network.epg

import com.leimao.tv.core.model.epg.EpgChannel
import com.leimao.tv.core.model.epg.EpgProgramme
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * XMLTV 节目单解析（LIVE-20）。
 *
 * 为什么是 SAX 而不是 DOM：真实的聚合节目单动辄几万条节目、好几 MB
 * （`e.xml` 是 6.6MB / 14573 条），DOM 会把整棵树建进内存，低端盒子上直接 OOM。
 * SAX 逐元素回调，常驻内存只有「正在解析的这一条」。
 *
 * 只认三类元素：`channel` / `display-name` / `programme`（含 `title`、`desc`）。
 * 其余一律忽略 —— 节目单里还有 `icon`、`rating`、`episode-num` 等一堆可选元素，
 * 我们一屏只显示「当前 / 下一档」，多解出来的字段没有落点，反而增加出错面。
 */
object XmltvParser {
    /** 解析结果。[skipped] 是「认出来了但缺关键字段」的条数，用来在导入结果里诚实汇报 */
    data class Result(
        val channels: List<EpgChannel>,
        val programmes: List<EpgProgramme>,
        val skipped: Int,
    )

    fun parse(input: InputStream): Result {
        val handler = Handler()
        val factory =
            SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                // 校验会让解析器去读 DTD：节目单里的 DTD 指向网络，导入就卡住了
                isValidating = false

                // 逐条 **静默** 设置，不能直接 setFeature。
                //
                // 这些特性名来自 Xerces，Android 自带的 SAX 实现只认其中一部分：
                // 只要有一条不认识就抛 SAXNotRecognizedException，而异常消息就是特性 URL
                // 本身（实测报错：「节目单中途损坏，只读到半份（http://apache.org/xml/
                // features/disallow-doctype-decl）」）。一句话，一条不认识，整个导入报废。
                enableFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
                enableFeature("http://xml.org/sax/features/external-general-entities", false)
                enableFeature("http://xml.org/sax/features/external-parameter-entities", false)
                // 这条才是「别去下载 DOCTYPE 里那个 DTD」的真正开关。
                // 真实节目单的文件头几乎都写着 <!DOCTYPE tv SYSTEM "xmltv.dtd">，
                // 不关掉它，导入会先去解析作者的服务器，断网就整份失败
                enableFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }

        // 直接在 XMLReader 上挂实体解析器，而不是用 SAXParser.parse(input, handler)。
        //
        // 后者内部会自己配一遍 reader，实测（JVM/Xerces）entityResolver 会被丢掉，
        // 于是又去联网取 DTD，报 UnknownHostException。这里自己接管，行为可预期。
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        // 双保险：外部实体一律解析成空 —— 既不联网，也不会因为拉不到 DTD 而失败。
        // 内置实体（&amp; 之类）不走这里，正文不会被吞掉
        reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.parse(InputSource(input))

        return Result(
            channels = handler.channels,
            programmes = handler.programmes,
            skipped = handler.skipped,
        )
    }

    /** 设不上就算了：这条特性在当前平台上不存在，不该让整次导入失败 */
    private fun SAXParserFactory.enableFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private class Handler : DefaultHandler() {
        val channels = mutableListOf<EpgChannel>()
        val programmes = mutableListOf<EpgProgramme>()
        var skipped = 0
            private set

        private var channelId: String? = null
        private var channelName: String? = null

        private var programmeChannel: String? = null
        private var programmeStart: Long = 0
        private var programmeStop: Long = 0
        private var programmeTitle: String? = null
        private var programmeDesc: String? = null

        /** 当前正在收文本的元素（`display-name` / `title` / `desc`），null 表示没收 */
        private var capturing: String? = null
        private val text = StringBuilder()

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes?,
        ) {
            when (qName) {
                "channel" -> {
                    channelId = attributes?.getValue("id")
                    channelName = null
                }

                "display-name" -> {
                    if (channelId != null) beginCapture("display-name")
                }

                "programme" -> {
                    programmeChannel = attributes?.getValue("channel")
                    programmeStart = XmltvTime.parse(attributes?.getValue("start")) ?: 0L
                    programmeStop = XmltvTime.parse(attributes?.getValue("stop")) ?: 0L
                    programmeTitle = null
                    programmeDesc = null
                }

                "title" -> {
                    if (programmeChannel != null) beginCapture("title")
                }

                "desc" -> {
                    if (programmeChannel != null) beginCapture("desc")
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing != null) text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (qName) {
                "display-name" -> {
                    if (capturing == "display-name") {
                        channelName = text.toString().trim().ifEmpty { null }
                        capturing = null
                    }
                }

                "title" -> {
                    if (capturing == "title") {
                        programmeTitle = text.toString().trim().ifEmpty { null }
                        capturing = null
                    }
                }

                "desc" -> {
                    if (capturing == "desc") {
                        programmeDesc = text.toString().trim().ifEmpty { null }
                        capturing = null
                    }
                }

                "channel" -> {
                    val id = channelId
                    val name = channelName
                    if (id != null && name != null) channels += EpgChannel(id = id, name = name) else skipped++
                    channelId = null
                    channelName = null
                }

                "programme" -> {
                    val channel = programmeChannel
                    val title = programmeTitle
                    if (channel != null && title != null && programmeStop > programmeStart) {
                        programmes +=
                            EpgProgramme(
                                channelId = channel,
                                startAt = programmeStart,
                                endAt = programmeStop,
                                title = title,
                                description = programmeDesc,
                            )
                    } else {
                        skipped++
                    }
                    programmeChannel = null
                }
            }
        }

        private fun beginCapture(element: String) {
            capturing = element
            text.setLength(0)
        }
    }
}
