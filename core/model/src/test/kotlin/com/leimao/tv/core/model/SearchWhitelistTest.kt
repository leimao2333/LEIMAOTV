package com.leimao.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RULES §3.2 第 3 条要求的「全类型单测」：输入含短剧 / 纪录片 / 资讯 / 预告片，
 * 断言 IPTV scope 的输出**恰好等于**白名单六类。
 *
 * 这条测试红，版本不能发（§3.2）。断言用 assertEquals（集合相等）而不是 assertTrue(contains)，
 * 是为了同时钉住「不多」和「不少」—— 只测 contains 的话，哪天有人往白名单里塞进纪录片也照样绿。
 */
class SearchWhitelistTest {
    private data class Item(val title: String, val type: ContentType)

    /** 刻意让九类影视类型各来一条，另加一条直播频道。 */
    private val allTypes: List<Item> = ContentType.entries.map { Item("样例-${it.name}", it) }

    @Test
    fun `IPTV 板块的结果类型恰好是白名单六类`() {
        val result = allTypes.filterForScope(SearchScope.IPTV) { it.type }

        assertEquals(
            setOf(
                ContentType.LIVE_CHANNEL,
                ContentType.MOVIE,
                ContentType.ANIME,
                ContentType.TV_SERIES,
                ContentType.SPORTS,
                ContentType.VARIETY,
            ),
            result.map { it.type }.toSet(),
        )
        assertEquals("六类各一条", 6, result.size)
    }

    @Test
    fun `白名单外的四类在 IPTV 板块一条都不许出现`() {
        val result = allTypes.filterForScope(SearchScope.IPTV) { it.type }

        listOf(
            ContentType.SHORT_PLAY,
            ContentType.DOCUMENTARY,
            ContentType.NEWS,
            ContentType.TRAILER,
        ).forEach { forbidden ->
            assertEquals(
                "$forbidden 不该出现在直播板块的搜索结果里",
                emptyList<Item>(),
                result.filter { it.type == forbidden },
            )
        }
    }

    @Test
    fun `VOD 板块不受白名单限制`() {
        val result = allTypes.filterForScope(SearchScope.VOD) { it.type }

        assertEquals(ContentType.entries.toSet(), result.map { it.type }.toSet())
    }

    @Test
    fun `VOD 类型集合是九类，不含直播频道`() {
        assertEquals(9, ContentType.VOD_TYPES.size)
        assertEquals(false, ContentType.LIVE_CHANNEL in ContentType.VOD_TYPES)
    }
}
