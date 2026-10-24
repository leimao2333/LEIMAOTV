package com.leimao.tv.core.player

import com.leimao.tv.core.player.Media3PlayerController.Companion.composeTrackLabel
import com.leimao.tv.core.player.Media3PlayerController.Companion.languageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 轨道菜单上那一行字（PLAY-07）。
 *
 * 这段逻辑被抽成纯字符串函数就是为了能这么测：真实轨道要一个带多音轨的媒体文件
 * 加上模拟器才能造出来，而「`en` 该说英语」这种判断没必要跑一遍解码器。
 */
class TrackLabelTest {
    @Test
    fun `容器自带的名字优先`() {
        // 「国语」「Director's Commentary」这类信息 language 里根本没有
        assertEquals(
            "国语 · AAC",
            composeTrackLabel(
                kind = TrackKind.AUDIO,
                ordinal = 1,
                label = "国语",
                language = "zh",
                mime = "audio/mp4a-latm",
            ),
        )
    }

    @Test
    fun `没有名字时用语言代码`() {
        assertEquals(
            "英语",
            composeTrackLabel(
                kind = TrackKind.AUDIO,
                ordinal = 2,
                label = null,
                language = "en",
                mime = null,
            ),
        )
    }

    @Test
    fun `什么都没有时编号兜底`() {
        // 顺序是「名字 · 编码」里的序号；序号由调用方按同类轨道数算好
        assertEquals("音轨 3", composeTrackLabel(TrackKind.AUDIO, 3, null, null, null))
        assertEquals("字幕 1", composeTrackLabel(TrackKind.TEXT, 1, null, null, null))
    }

    @Test
    fun `空字符串不算名字`() {
        // 源里 label="" 很常见，不能因此显示成空行
        assertEquals(
            "中文",
            composeTrackLabel(TrackKind.TEXT, 1, "  ", "zh", null),
        )
    }

    @Test
    fun `认得的编码附在后面`() {
        assertEquals(
            "日语 · EAC3",
            composeTrackLabel(TrackKind.AUDIO, 1, null, "ja", "audio/eac3"),
        )
        assertEquals(
            "字幕 1 · WebVTT",
            composeTrackLabel(TrackKind.TEXT, 1, null, null, "text/vtt"),
        )
    }

    @Test
    fun `不认得的编码不显示`() {
        // 显示 audio/x-unknown 只会让菜单变脏，用户也读不懂
        assertEquals(
            "法语",
            composeTrackLabel(TrackKind.AUDIO, 1, null, "fr", "audio/x-unknown"),
        )
    }

    @Test
    fun `ISO 639-1 与 639-2 都认`() {
        // 同一种语言在不同封装里可能写 zh / chi / zho，区域码还会带后缀
        assertEquals("中文", languageName("zh"))
        assertEquals("中文", languageName("zh-CN"))
        assertEquals("中文", languageName("CHI"))
        assertEquals("粤语", languageName("yue"))
    }

    @Test
    fun `不认得的语言返回 null`() {
        assertNull(languageName("xx"))
        assertNull(languageName(""))
    }
}
