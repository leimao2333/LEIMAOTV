package com.leimao.tv.core.database.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SET-14「混合导入」的判定逻辑。
 *
 * 这些用例锁的不是实现细节，而是**四条会直接害到用户的错误判定**：
 * 1. 一份直播清单被认成影视 → 用户看到「返回的不是影视接口」；
 * 2. 一份影视接口被认成直播 → 用户看到「内容不是 M3U / TXT 播放列表」；
 * 3. 备份里写着 LIVE、地址却是采集接口，照着声明导入 → 一条永远刷不出频道的死源；
 * 4. 认不出来时抛异常或给空类型 → 直接入库一条类型非法的源，列表里显示空白。
 */
class SourceTypeGuesserTest {
    // ── 地址特征 ────────────────────────────────────────────────────────────────

    @Test
    fun `苹果CMS 采集接口认成影视`() {
        assertEquals(
            SourceType.VOD,
            SourceTypeGuesser.guessFromUrl("https://example.com/api.php/provide/vod/?ac=list"),
        )
        assertEquals(
            SourceType.VOD,
            SourceTypeGuesser.guessFromUrl("https://example.com/api.php?ac=videolist&wd=测试"),
        )
        assertEquals(
            SourceType.VOD,
            SourceTypeGuesser.guessFromUrl("https://example.com/api.php?at=json&wd=流浪地球"),
        )
    }

    @Test
    fun `播放列表地址认成直播`() {
        assertEquals(SourceType.LIVE, SourceTypeGuesser.guessFromUrl("https://example.com/live.m3u"))
        assertEquals(SourceType.LIVE, SourceTypeGuesser.guessFromUrl("https://example.com/tv/list.m3u8"))
        assertEquals(SourceType.LIVE, SourceTypeGuesser.guessFromUrl("https://example.com/iptv.txt"))
        assertEquals(SourceType.LIVE, SourceTypeGuesser.guessFromUrl("https://example.com/playlist?type=m3u"))
    }

    @Test
    fun `查询串里的 m3u 不影响后缀判断`() {
        // `.json?v=2` 这种带查询串的地址：path 才是判据，query 只是噪声
        assertNull(SourceTypeGuesser.guessFromUrl("https://example.com/sources.json?v=2"))
    }

    @Test
    fun `看不出来的地址返回 null 而不是乱猜`() {
        assertNull(SourceTypeGuesser.guessFromUrl("https://example.com/api.php"))
        assertNull(SourceTypeGuesser.guessFromUrl(""))
    }

    // ── 正文特征 ────────────────────────────────────────────────────────────────

    @Test
    fun `M3U 头认成直播`() {
        val m3u = "#EXTM3U\n#EXTINF:-1 tvg-name=\"CCTV1\",CCTV1\nhttp://example.com/1.m3u8\n"
        assertEquals(SourceType.LIVE, SourceTypeGuesser.guessFromContent(m3u))
    }

    @Test
    fun `纯 EXTINF 清单也认成直播`() {
        val txt = "#EXTINF:-1,频道一\nhttp://example.com/1.m3u8\n"
        assertEquals(SourceType.LIVE, SourceTypeGuesser.guessFromContent(txt))
    }

    @Test
    fun `台名逗号地址的 txt 认成直播`() {
        val txt = "CCTV1,http://example.com/1.m3u8\nCCTV2,http://example.com/2.m3u8\n"
        assertEquals(SourceType.LIVE, SourceTypeGuesser.guessFromContent(txt))
    }

    @Test
    fun `采集接口的 JSON 响应认成影视`() {
        val json =
            """{"code":1,"msg":"数据列表","page":1,"pagecount":5,"limit":"20","total":100,""" +
                """"class":[{"type_id":1,"type_name":"电影"}],"list":[{"vod_id":1,"vod_name":"某片"}]}"""
        assertEquals(SourceType.VOD, SourceTypeGuesser.guessFromContent(json))
    }

    @Test
    fun `只有 list 没有 class 的 JSON 不认成影视`() {
        // 单看 list 会把大量无关 JSON（普通接口、配置）认成影视，代价是一条假的影视源
        val json = """{"code":0,"list":[1,2,3]}"""
        assertNull(SourceTypeGuesser.guessFromContent(json))
    }

    @Test
    fun `一行频道不算列表`() {
        // 一行「名字,地址」也可能只是一句说明文字；两行以上才像播放列表
        assertNull(SourceTypeGuesser.guessFromContent("说明,http://example.com/x"))
    }

    // ── 综合判定 ────────────────────────────────────────────────────────────────

    @Test
    fun `正文比声明可靠`() {
        // 备份里写着 LIVE，但抓回来的正文是采集接口的 JSON
        val json = """{"class":[{"type_id":1}],"list":[{"vod_id":1}]}"""
        assertEquals(
            SourceType.VOD,
            SourceTypeGuesser.resolve(SourceType.LIVE, "https://example.com/api.php", json),
        )
    }

    @Test
    fun `地址与声明冲突时以地址为准`() {
        assertEquals(
            SourceType.VOD,
            SourceTypeGuesser.resolve(SourceType.LIVE, "https://example.com/api.php/provide/vod"),
        )
        assertEquals(
            SourceType.LIVE,
            SourceTypeGuesser.resolve(SourceType.VOD, "https://example.com/live.m3u"),
        )
    }

    @Test
    fun `txt 后缀是软证据 不能推翻声明`() {
        // 聚合源清单里 `format=moontv` 的影视源就挂在 `.txt` 上（SourceJsonTest 有原始形态）。
        // 拿后缀去推翻用户明确选的类型，得到的是一个永远刷不出频道的死源
        assertEquals(
            SourceType.VOD,
            SourceTypeGuesser.resolve(SourceType.VOD, "https://example.com/vod.txt"),
        )
        // 反过来也一样：声明是直播、地址是 `.txt`，没有理由改成影视
        assertEquals(
            SourceType.LIVE,
            SourceTypeGuesser.resolve(SourceType.LIVE, "https://example.com/tv.txt"),
        )
    }

    @Test
    fun `不冲突时尊重声明`() {
        // `api.php` 既可能是苹果CMS 也可能是别的代理，地址看不出时声明说了算
        assertEquals(
            SourceType.VOD,
            SourceTypeGuesser.resolve(SourceType.VOD, "https://example.com/api.php"),
        )
        assertEquals(
            SourceType.LIVE,
            SourceTypeGuesser.resolve("直播", "https://example.com/api.php"),
        )
    }

    @Test
    fun `认不出来兜底直播`() {
        assertEquals(SourceType.LIVE, SourceTypeGuesser.resolve(null, "https://example.com/api.php"))
        assertEquals(SourceType.LIVE, SourceTypeGuesser.resolve("", ""))
        // AUTO 只是表单层的占位，不是能落库的类型
        assertEquals(SourceType.LIVE, SourceTypeGuesser.resolve(SourceType.AUTO, "https://example.com/x"))
    }

    @Test
    fun `声明写法归一`() {
        assertEquals(SourceType.VOD, SourceTypeGuesser.normalize("VOD"))
        assertEquals(SourceType.VOD, SourceTypeGuesser.normalize("影视"))
        assertEquals(SourceType.VOD, SourceTypeGuesser.normalize("vod"))
        assertEquals(SourceType.LIVE, SourceTypeGuesser.normalize("直播"))
        assertEquals(SourceType.LIVE, SourceTypeGuesser.normalize("iptv"))
        assertNull(SourceTypeGuesser.normalize("unknown"))
        assertNull(SourceTypeGuesser.normalize(null))
    }

    // ── 混合备份整体走一遍 ─────────────────────────────────────────────────────

    @Test
    fun `混合备份里直播与影视各归各的`() {
        val json =
            """
            {"version":1,"sources":[
              {"type":"LIVE","name":"直播A","url":"https://example.com/live.m3u"},
              {"type":"LIVE","name":"影视B","url":"https://example.com/api.php/provide/vod/?ac=list"},
              {"name":"影视C","url":"https://example.com/api.php?ac=videolist"},
              {"name":"直播D","url":"https://example.com/iptv/list.txt"}
            ]}
            """.trimIndent()
        val decoded = SourceJson.decode(json).associateBy { it.name }

        assertEquals(SourceType.LIVE, decoded.getValue("直播A").type)
        // 声明写着 LIVE，地址是采集接口 —— 认地址
        assertEquals(SourceType.VOD, decoded.getValue("影视B").type)
        // 没有 type 字段的老备份 / 手写备份照样能判
        assertEquals(SourceType.VOD, decoded.getValue("影视C").type)
        assertEquals(SourceType.LIVE, decoded.getValue("直播D").type)
    }
}
