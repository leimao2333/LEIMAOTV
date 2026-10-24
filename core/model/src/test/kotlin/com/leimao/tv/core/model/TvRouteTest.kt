package com.leimao.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 详情页路由的参数编码（VOD-04）。
 *
 * 这一条规则值得单测：`sourceKey` 大多是一串数字，随手拼字符串在开发机上永远不会出错，
 * 等到某个源的 id 里带了空格或 `/`，用户点进去看到的是空详情页 —— 而那个源的维护者
 * 不会为此改 id。编码错了只是一次静默的 404，排查成本远高于这二十行测试。
 */
class TvRouteTest {
    @Test
    fun `普通 id 原样进路由`() {
        assertEquals("vod_detail?sourceId=7&sourceKey=12345", TvRoute.vodDetail(7, "12345"))
    }

    @Test
    fun `空格编成 %20 而不是加号`() {
        // URLEncoder 会给 `+`，而 URI 里的 `+` 是字面量 —— 用错就是「片名带空格的条目查不到」
        assertEquals("vod_detail?sourceId=1&sourceKey=a%20b", TvRoute.vodDetail(1, "a b"))
        assertFalse(TvRoute.vodDetail(1, "a b").contains("+"))
    }

    @Test
    fun `中文按 UTF-8 逐字节编码`() {
        assertEquals("%E7%89%87", TvRoute.percentEncode("片"))
    }

    @Test
    fun `斜杠与查询分隔符都要编码`() {
        // 不编码的话 `/` 会切断路径、`&` 会多出来一个参数，两种都会让导航匹配失败
        assertEquals("a%2Fb%26c%3Dd", TvRoute.percentEncode("a/b&c=d"))
    }

    @Test
    fun `保留 unreserved 字符`() {
        assertEquals("A-z_0.9~", TvRoute.percentEncode("A-z_0.9~"))
    }

    @Test
    fun `百分号自身也要编码`() {
        assertEquals("100%25", TvRoute.percentEncode("100%"))
    }

    @Test
    fun `播放路由带上线路与集号`() {
        // 从详情页点第 12 集，进来就该播第 12 集，而不是「先播第一集再让用户自己跳」
        assertEquals(
            "vod_play?sourceId=3&sourceKey=88&lineIndex=1&episodeIndex=11&startMs=0",
            TvRoute.vodPlay(3, "88", lineIndex = 1, episodeIndex = 11),
        )
    }

    @Test
    fun `续播点进路由`() {
        // VOD-06：从「继续观看」进来要带上看到的位置，否则用户又得自己拖
        assertEquals(
            "vod_play?sourceId=3&sourceKey=88&lineIndex=0&episodeIndex=4&startMs=754000",
            TvRoute.vodPlay(3, "88", lineIndex = 0, episodeIndex = 4, startPositionMs = 754_000L),
        )
    }

    @Test
    fun `负的续播点归一成 0`() {
        // 内核偶尔上报 -1（位置还没算出来）。带着负数一路走到播放器，
        // Media3 会抛 IllegalSeekPositionException —— 那是一次崩溃，不是一次降级
        assertEquals(
            "vod_play?sourceId=1&sourceKey=k&lineIndex=0&episodeIndex=0&startMs=0",
            TvRoute.vodPlay(1, "k", lineIndex = 0, episodeIndex = 0, startPositionMs = -1L),
        )
    }

    @Test
    fun `频道 id 的魔法数归一成 null`() {
        assertEquals(null, TvRoute.channelIdFrom(-1L))
        assertEquals(9L, TvRoute.channelIdFrom(9L))
    }
}
