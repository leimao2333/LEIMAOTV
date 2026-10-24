package com.leimao.tv.core.model.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关键词归一化与本地匹配（SEARCH-06）。
 *
 * 这两条规则被 SQL 与 Kotlin 两侧共用，所以必须钉死：SQL 侧只负责去空格，
 * 「全角转半角、转小写」在 `normalizeSearchQuery` 里做一次，两侧口径不一致的话，
 * 会出现「本地搜得到、SQL 搜不到」这种只能靠试才能发现的 bug。
 */
class SearchQueryTest {
    @Test
    fun `全角转半角并转小写`() {
        assertEquals("cctv-1", normalizeSearchQuery("ＣＣＴＶ－１"))
    }

    @Test
    fun `去掉首尾与内部所有空白`() {
        // 中英文空格混着来（源里的名字就是这样），匹配时一律不认
        assertEquals("cctv1", normalizeSearchQuery("  CCTV 1 "))
        assertEquals("cctv1", normalizeSearchQuery("CCTV　1"))
    }

    @Test
    fun `匹配忽略空格与大小写`() {
        assertTrue(matchesSearchQuery("CCTV 1 综合", "cctv1"))
        assertTrue(matchesSearchQuery("cctv-1", "CCTV-1"))
        assertTrue(matchesSearchQuery("湖南卫视高清", "卫视"))
    }

    @Test
    fun `不相关的词不匹配`() {
        assertFalse(matchesSearchQuery("CCTV-1", "cctv2"))
        assertFalse(matchesSearchQuery(null, "cctv"))
        assertFalse(matchesSearchQuery("CCTV-1", ""))
    }

    @Test
    fun `唯一键口径与收藏进度一致`() {
        // 收藏（VOD-07）与进度（VOD-06）都用 (sourceId, sourceKey) 定位，搜索必须同口径，
        // 否则点进去是「另一部片」这种最难查的问题
        assertEquals("vod:7:abc", vodSearchKey(7, "abc"))
        assertEquals("ch:42", channelSearchKey(42))
    }
}
