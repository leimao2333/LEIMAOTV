package com.leimao.tv.core.network.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视源 URL 构造（VOD-01）。
 *
 * 只钉两件容易出错的事：**基础地址的形态**（带不带尾斜杠、自带没自带 `?`）
 * 和**关键词编码**（中文必须 percent-encode，空格不能用 `+`）。
 */
class VodApiTest {
    private val base = "https://example.com/api.php/provide/vod/"

    @Test
    fun `分类与列表与详情与搜索的 URL`() {
        assertEquals("$base?ac=list", VodApi.categoriesUrl(base))
        assertEquals("$base?ac=detail&t=1&pg=2", VodApi.listUrl(base, "1", 2))
        assertEquals("$base?ac=detail&ids=1001", VodApi.detailUrl(base, "1001"))
        assertEquals("$base?ac=detail&wd=abc&pg=1", VodApi.searchUrl(base, "abc", 1))
    }

    @Test
    fun `基础地址没有尾斜杠也能拼对`() {
        assertEquals(
            "https://example.com/api.php/provide/vod?ac=list",
            VodApi.categoriesUrl("https://example.com/api.php/provide/vod"),
        )
    }

    @Test
    fun `基础地址自带查询串时用 & 连接`() {
        assertEquals(
            "https://example.com/api.php/provide/vod/?token=abc&ac=list",
            VodApi.categoriesUrl("https://example.com/api.php/provide/vod/?token=abc"),
        )
    }

    @Test
    fun `基础地址末尾多余的连接符会被去掉`() {
        assertEquals("$base?ac=list", VodApi.categoriesUrl("$base&"))
        assertEquals("$base?ac=list", VodApi.categoriesUrl("$base?"))
        assertEquals("$base?ac=list", VodApi.categoriesUrl("  $base  "))
    }

    @Test
    fun `中文关键词要编码且空格不用加号`() {
        val url = VodApi.searchUrl(base, "测试 影片", 1)
        assertTrue("中文应被编码：$url", url.contains("wd=%E6%B5%8B%E8%AF%95%20%E5%BD%B1%E7%89%87"))
        assertTrue("空格不该变成 +：$url", !url.contains("+"))
    }

    @Test
    fun `空地址要明确报错`() {
        val error =
            try {
                VodApi.categoriesUrl("   ")
                throw AssertionError("期望抛 IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                e.message.orEmpty()
            }
        assertTrue(error.contains("地址为空"))
    }
}
