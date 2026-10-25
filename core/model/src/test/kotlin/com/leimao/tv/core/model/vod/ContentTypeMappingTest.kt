package com.leimao.tv.core.model.vod

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.IPTV_ALLOWED_TYPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VOD-02 类型映射 + 与 SEARCH-02 白名单的同源关系。
 *
 * 重点不是「每个分类名都能映射」，而是**顺序敏感的那几个不能错**：
 * 「短剧」若被判成电视剧、「纪录片」若被判成电影，白名单就形同虚设了。
 */
class ContentTypeMappingTest {
    @Test
    fun `九类各自的典型分类名都能映射`() {
        assertEquals(ContentType.MOVIE, contentTypeOfSourceName("电影"))
        assertEquals(ContentType.TV_SERIES, contentTypeOfSourceName("电视剧"))
        assertEquals(ContentType.ANIME, contentTypeOfSourceName("动漫"))
        assertEquals(ContentType.VARIETY, contentTypeOfSourceName("综艺"))
        assertEquals(ContentType.SPORTS, contentTypeOfSourceName("体育"))
        assertEquals(ContentType.OPERA, contentTypeOfSourceName("戏曲"))
        assertEquals(ContentType.OPERA, contentTypeOfSourceName("京剧"))
        assertEquals(ContentType.OPERA, contentTypeOfSourceName("相声"))
        assertEquals(ContentType.SHORT_PLAY, contentTypeOfSourceName("短剧"))
        assertEquals(ContentType.DOCUMENTARY, contentTypeOfSourceName("纪录片"))
        assertEquals(ContentType.NEWS, contentTypeOfSourceName("资讯"))
        assertEquals(ContentType.TRAILER, contentTypeOfSourceName("预告片"))
    }

    @Test
    fun `顺序敏感的分类不会被泛匹配抢走`() {
        // 「短剧」含「剧」：若「剧 → 电视剧」先判，短剧会被错认成电视剧，白名单就被绕过
        assertEquals(ContentType.SHORT_PLAY, contentTypeOfSourceName("微短剧"))
        // 「纪录片」「预告片」「综艺片」「动画片」都含「片」：电影分支的「片」兜底必须排在后面
        assertEquals(ContentType.DOCUMENTARY, contentTypeOfSourceName("纪录片"))
        assertEquals(ContentType.TRAILER, contentTypeOfSourceName("预告片"))
        assertEquals(ContentType.VARIETY, contentTypeOfSourceName("综艺片"))
        assertEquals(ContentType.ANIME, contentTypeOfSourceName("动画片"))
    }

    @Test
    fun `动漫电影按动漫处理`() {
        // 这类源把它放在动漫栏，用户去动漫里找它更符合习惯
        assertEquals(ContentType.ANIME, contentTypeOfSourceName("动漫电影"))
        assertEquals(ContentType.ANIME, contentTypeOfSourceName("日本动漫"))
    }

    @Test
    fun `电影的子分类靠 片 兜底`() {
        listOf("动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片", "战争片", "伦理片")
            .forEach { assertEquals("分类 $it", ContentType.MOVIE, contentTypeOfSourceName(it)) }
    }

    @Test
    fun `电视剧的子分类靠 剧 兜底`() {
        listOf("国产剧", "港台剧", "日韩剧", "欧美剧", "连续剧", "海外剧")
            .forEach { assertEquals("分类 $it", ContentType.TV_SERIES, contentTypeOfSourceName(it)) }
    }

    @Test
    fun `全角字符与空白不影响命中`() {
        assertEquals(ContentType.TV_SERIES, contentTypeOfSourceName("　电视剧　"))
        assertEquals(ContentType.MOVIE, contentTypeOfSourceName(" 电 影 "))
        // 全角的「剧」是同一个字，但全角的括号等邻近字符要能归一化掉
        assertEquals(ContentType.SPORTS, contentTypeOfSourceName("（体育）"))
    }

    @Test
    fun `认不出的分类返回 null 而不是猜一个`() {
        assertNull(contentTypeOfSourceName("福利"))
        assertNull(contentTypeOfSourceName("全部"))
        assertNull(contentTypeOfSourceName("最新"))
        assertNull(contentTypeOfSourceName(""))
        assertNull(contentTypeOfSourceName("   "))
        assertNull(contentTypeOfSourceName(null))
    }

    @Test
    fun `白名单四类受限内容映射后不在 IPTV 允许集合里`() {
        // 这条钉住 SEARCH-02：短剧/纪录片/资讯/预告片 无论在源里叫什么，
        // 映射出来的类型都必须被 IPTV 白名单挡在外面
        listOf("短剧", "微短剧", "纪录片", "资讯", "新闻", "预告片", "花絮")
            .map { contentTypeOfSourceName(it) }
            .forEach { type ->
                assertTrue("映射结果不该为空", type != null)
                assertTrue("$type 不该出现在 IPTV 白名单里", type !in IPTV_ALLOWED_TYPES)
            }
    }

    @Test
    fun `白名单六类都能被映射到`() {
        // 反向保证：允许出现的六类必须有办法从源头产生，否则「能搜到」是空话
        val produced =
            listOf("电影", "动漫", "电视剧", "体育", "综艺")
                .mapNotNull { contentTypeOfSourceName(it) }
                .toSet()
        assertTrue(produced.containsAll(IPTV_ALLOWED_TYPES - ContentType.LIVE_CHANNEL))
    }
}
