package com.leimao.tv.core.model.vod

import com.leimao.tv.core.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏的数据形态（VOD-07）。
 *
 * 不测数据库：`vod_favorite` 的读写靠 Room，跑起来要模拟器；这里钉住的是
 * 「收藏快照转成海报卡摘要」这一步 —— 收藏页与首页共用同一个卡片组件，
 * 转换里丢掉任何字段都表现为「卡片上少一行字」，很难在真机上发现。
 */
class VodFavoriteTest {
    private val favorite =
        VodFavorite(
            sourceId = 7,
            sourceKey = "1024",
            title = "示例电影",
            poster = "https://img.example/1024.jpg",
            type = ContentType.TV_SERIES,
            year = 2024,
            area = "中国香港",
            remarks = "全 40 集",
            addedAt = 1_700_000_000_000,
        )

    @Test
    fun `key 由源与源内编号组成`() {
        // 片名会重复（翻拍、分流站），只有这两个字段合起来才唯一
        assertEquals("7:1024", favorite.key)
    }

    @Test
    fun `toSummary 保住跳转所需的两个 id`() {
        val summary = favorite.toSummary()
        assertEquals(7L, summary.sourceId)
        assertEquals("1024", summary.sourceKey)
    }

    @Test
    fun `toSummary 带上快照里的展示字段`() {
        val summary = favorite.toSummary()
        assertEquals("示例电影", summary.name)
        assertEquals("https://img.example/1024.jpg", summary.pic)
        assertEquals(ContentType.TV_SERIES, summary.type)
        assertEquals(2024, summary.year)
        assertEquals("中国香港", summary.area)
        assertEquals("全 40 集", summary.remarks)
    }

    @Test
    fun `没给海报与类型时也能成卡`() {
        // 源里常有没图的条目；转成 summary 时不能因为 pic 为 null 就丢掉整条
        val bare = VodFavorite(sourceId = 1, sourceKey = "1", title = "无图")
        val summary = bare.toSummary()
        assertEquals("无图", summary.name)
        assertNull(summary.pic)
        assertEquals(ContentType.MOVIE, summary.type)
    }

    @Test
    fun `进度默认是没看完`() {
        // 升级到 VOD-07 后，存量进度靠这一条默认值继续出现在「继续观看」里。
        // 默认值一改，所有人的继续观看都会在一夜之间清空
        val progress =
            VodProgress(
                sourceId = 1,
                sourceKey = "1",
                lineIndex = 0,
                episodeIndex = 3,
                positionMs = 60_000,
                durationMs = 45 * 60_000,
                updatedAt = 0,
            )
        assertTrue(!progress.finished)
    }
}
