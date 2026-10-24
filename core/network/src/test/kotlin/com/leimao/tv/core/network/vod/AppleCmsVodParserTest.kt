package com.leimao.tv.core.network.vod

import com.leimao.tv.core.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 苹果CMS 响应解析（VOD-01）。
 *
 * 所有 JSON 都是**构造的假数据**（域名一律 example.com，片名是「测试影片」这类占位），
 * 不含任何真实源信息（RULES §2.1）。
 */
class AppleCmsVodParserTest {
    @Test
    fun `分类解析 认不出的分类保留但类型为 null`() {
        val json =
            """
            {
              "code": 1,
              "msg": "数据列表",
              "class": [
                {"type_id": 1, "type_pid": 0, "type_name": "电影"},
                {"type_id": 2, "type_pid": 0, "type_name": "电视剧"},
                {"type_id": 3, "type_pid": 0, "type_name": "福利"},
                {"type_id": "", "type_pid": 0, "type_name": "缺 id 的脏数据"},
                {"type_id": 9, "type_pid": 0, "type_name": ""}
              ]
            }
            """.trimIndent()

        val categories = AppleCmsVodParser.parseCategories(json)

        // 缺 id / 缺名的两条被丢弃，其余三条保留
        assertEquals(3, categories.size)
        assertEquals("电影", categories[0].name)
        assertEquals(ContentType.MOVIE, categories[0].type)
        assertEquals(ContentType.TV_SERIES, categories[1].type)
        // 「福利」映射不出：保留名字但 type 为 null，由上层决定不展示
        assertEquals("福利", categories[2].name)
        assertNull(categories[2].type)
    }

    @Test
    fun `分类为空要明确报错而不是返回空列表`() {
        val error = errorMessage { AppleCmsVodParser.parseCategories("""{"code":1,"class":[]}""") }
        assertTrue(error.contains("没有返回分类"))
    }

    @Test
    fun `响应不是 JSON 对象要报错`() {
        // 报错文案要能让用户判断「地址填错了」，而不只是「解析失败了」
        assertTrue(errorMessage { AppleCmsVodParser.parseCategories("<html>404</html>") }.contains("不是 JSON"))
        assertTrue(errorMessage { AppleCmsVodParser.parseCategories("[]") }.contains("不是 JSON"))
    }

    @Test
    fun `列表解析 丢脏数据并计数`() {
        val json =
            """
            {
              "code": 1,
              "page": 2,
              "pagecount": 5,
              "total": 100,
              "list": [
                {"vod_id": 1001, "vod_name": "测试影片甲", "type_name": "动作片",
                 "vod_pic": "https://example.com/p/1.jpg", "vod_remarks": "HD国语",
                 "vod_year": "2023", "vod_area": "内地"},
                {"vod_id": "1002", "vod_name": "测试影片乙", "type_name": "纪录片"},
                {"vod_id": 1003, "vod_name": "  ", "type_name": "电影"},
                {"vod_id": 1004, "vod_name": "认不出类型", "type_name": "福利"},
                {"vod_id": 1005, "vod_name": "脏图片", "type_name": "电影",
                 "vod_pic": "/relative.jpg", "vod_year": "未知"}
              ]
            }
            """.trimIndent()

        val page = AppleCmsVodParser.parseList(json, sourceId = 7L)

        assertEquals(3, page.items.size)
        assertEquals(2, page.droppedCount) // 缺名字 + 类型认不出
        assertEquals(2, page.page)
        assertEquals(5, page.pageCount)
        assertEquals(100, page.total)
        assertTrue(page.hasMore)

        val first = page.items[0]
        assertEquals(7L, first.sourceId)
        assertEquals("1001", first.sourceKey) // 数字 id 统一成文本存
        assertEquals("测试影片甲", first.name)
        assertEquals(ContentType.MOVIE, first.type) // 动作片 → 电影
        assertEquals("https://example.com/p/1.jpg", first.pic)
        assertEquals("HD国语", first.remarks)
        assertEquals(2023, first.year)
        assertEquals("内地", first.area)

        // 字符串形式的 id 也要能取；空备注/空图给 null 而不是空串
        val second = page.items[1]
        assertEquals("1002", second.sourceKey)
        assertEquals(ContentType.DOCUMENTARY, second.type)
        assertNull(second.pic)
        assertNull(second.remarks)

        // 相对路径的图和非法年份不采信
        val third = page.items[2]
        assertNull(third.pic)
        assertNull(third.year)
    }

    @Test
    fun `最后一页 hasMore 为 false`() {
        val json = """{"page":5,"pagecount":5,"list":[{"vod_id":1,"vod_name":"甲","type_name":"电影"}]}"""
        assertTrue(!AppleCmsVodParser.parseList(json, 1L).hasMore)
    }

    @Test
    fun `详情解析 多线路多集与简介清洗`() {
        // JSON 原文里的 $ 是数据，不是 Kotlin 模板 —— 用 \$ 转义
        val playFrom = "线路甲\$\$\$线路乙"
        val playUrl =
            "第01集\$https://example.com/a/1.m3u8#第02集\$https://example.com/a/2.m3u8" +
                "\$\$\$正片\$https://example.com/b/full.mp4"
        val json =
            """
            {
              "code": 1,
              "list": [{
                "vod_id": 1001,
                "vod_name": "测试影片甲",
                "type_name": "动作片",
                "vod_pic": "https://example.com/p/1.jpg",
                "vod_content": "<p>这是简介<br/>第二行&nbsp;内容 &amp; 更多</p>",
                "vod_actor": "张三,李四,王五",
                "vod_director": "赵六",
                "vod_score": "7.8",
                "vod_lang": "国语",
                "vod_play_from": "$playFrom",
                "vod_play_url": "$playUrl"
              }]
            }
            """.trimIndent()

        val detail = AppleCmsVodParser.parseDetail(json, sourceId = 7L)!!

        assertEquals("测试影片甲", detail.summary.name)
        assertEquals("这是简介 第二行 内容 & 更多", detail.content)
        assertEquals("张三 / 李四 / 王五", detail.actor)
        assertEquals("赵六", detail.director)
        assertEquals("7.8", detail.score)
        assertEquals("国语", detail.lang)

        assertEquals(2, detail.lines.size)
        assertEquals("线路甲", detail.lines[0].name)
        assertEquals(2, detail.lines[0].episodes.size)
        assertEquals("第01集", detail.lines[0].episodes[0].name)
        assertEquals("https://example.com/a/1.m3u8", detail.lines[0].episodes[0].url)
        assertEquals("线路乙", detail.lines[1].name)
        assertEquals(1, detail.lines[1].episodes.size)
        assertEquals("正片", detail.lines[1].episodes[0].name)
    }

    @Test
    fun `详情缺集名与非法地址的处理`() {
        // 电影线路常见「只有地址没有集名」；非 http 的自家协议地址要丢掉
        val episodes =
            AppleCmsVodParser.parseEpisodes(
                "https://example.com/x.mp4#qqplay://abc#第2集\$https://example.com/y.mp4#",
            )
        assertEquals(2, episodes.size)
        assertEquals("", episodes[0].name)
        assertEquals("https://example.com/x.mp4", episodes[0].url)
        assertEquals("第2集", episodes[1].name)
    }

    @Test
    fun `播放地址为空时线路为空`() {
        assertTrue(AppleCmsVodParser.parseLines(null, null).isEmpty())
        assertTrue(AppleCmsVodParser.parseLines("线路甲", "   ").isEmpty())
        // 有线路名但没有任何可用地址：不产出空线路（UI 不该出现「线路甲 0 集」）
        assertTrue(AppleCmsVodParser.parseLines("线路甲\$\$\$线路乙", "qqplay://a\$\$\$qqplay://b").isEmpty())
    }

    @Test
    fun `详情里查不到这条返回 null`() {
        assertNull(AppleCmsVodParser.parseDetail("""{"code":1,"list":[]}""", 1L))
        assertNull(AppleCmsVodParser.parseDetail("""{"code":0,"msg":"数据不存在"}""", 1L))
    }

    @Test
    fun `简介清洗 空内容给 null`() {
        assertNull(AppleCmsVodParser.cleanHtml(null))
        assertNull(AppleCmsVodParser.cleanHtml("   "))
        assertNull(AppleCmsVodParser.cleanHtml("<p></p>"))
        assertEquals("只有正文", AppleCmsVodParser.cleanHtml("<div><span>只有正文</span></div>"))
    }

    @Test
    fun `演职员清洗`() {
        assertNull(AppleCmsVodParser.cleanPeople(null))
        assertNull(AppleCmsVodParser.cleanPeople(" , ，、 "))
        assertEquals("张三 / 李四", AppleCmsVodParser.cleanPeople("张三、李四"))
        assertEquals("张三 / 李四", AppleCmsVodParser.cleanPeople("张三 / 李四"))
    }

    private fun errorMessage(block: () -> Unit): String =
        try {
            block()
            throw AssertionError("期望抛 IllegalArgumentException，但没有")
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
}
