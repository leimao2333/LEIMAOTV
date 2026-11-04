package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.network.vod.VodFetchResult
import com.leimao.tv.core.network.vod.VodSourceAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视列表「一直往下翻」（VOD-03 翻页侧）。
 *
 * 用户的原话是「随便翻两下就直接没新东西了」。这个症状有两种成因，必须分开钉：
 *
 * 1. **环只有 2 格** —— 候选分类太少，第 3 页起必然重复（已由 `VodCandidatesTest` 钉住）；
 * 2. **每页都重新抓分类** —— 请求量翻倍、慢源下直接把翻页拖到超时，用户看起来也是「没了」。
 *
 * 这里钉第 2 条与整体行为：连翻 8 页内容互不重复，且分类只抓一次。
 * 分类缓存 2026-11-04 从裸 `HashMap` 换成了带容量上限的 LRU（低配机防常驻增长），
 * 换实现最容易踩的坑就是「缓存再也命不中」—— 那不会报错，只是每页都慢一拍。
 */
class VodPagerPaginationTest {
    private val source =
        Source(
            id = 7,
            name = "测试影视源",
            url = "https://example.com/api.php/provide/vod",
            type = SourceType.VOD,
        )

    private fun pager(adapter: VodSourceAdapter) =
        VodPager(
            sources = FakeSourceRepository(listOf(source)),
            http = FakeHttp,
            adapterFactory = { adapter },
        )

    @Test
    fun `连翻八页内容互不重复`() =
        runTest {
            val adapter = CountingAdapter(categoryCount = 4, itemsPerPage = 3)
            val pager = pager(adapter)

            val seen = mutableListOf<String>()
            for (page in 1..8) {
                val result = pager.page(ContentType.MOVIE, page)
                assertTrue("第 $page 页不该是空的", result.items.isNotEmpty())
                seen += result.items.map { it.sourceKey }
            }

            assertEquals("八页共 24 条，不该有重复", seen.size, seen.distinct().size)
        }

    @Test
    fun `分类只抓一次，后面翻页全部命中缓存`() =
        runTest {
            val adapter = CountingAdapter(categoryCount = 4, itemsPerPage = 3)
            val pager = pager(adapter)

            repeat(8) { pager.page(ContentType.MOVIE, it + 1) }

            // 少了这条，缓存坏掉不会报错：只是每页多一次网络往返，
            // 在慢源上表现为「翻页越来越卡」，在流量上表现为「翻 10 页发了 20 个请求」
            assertEquals("分类请求应只发一次", 1, adapter.categoriesCalls)
        }

    @Test
    fun `分类环用满之后才回头借第二页`() =
        runTest {
            val adapter = CountingAdapter(categoryCount = 3, itemsPerPage = 2)
            val pager = pager(adapter)

            repeat(6) { pager.page(ContentType.MOVIE, it + 1) }

            // 前 3 页各取一个分类的第 1 页；后 3 页回头取第 2 页。
            // 断言的是「每一页都发起了 list 请求」而不是复用上一页结果 ——
            // 复用会导致翻页像在「上一步」，用户以为卡住了
            assertEquals(6, adapter.listCalls)
        }

    @Test
    fun `没有可用分类时返回空结果而不是抛异常`() =
        runTest {
            val adapter = CountingAdapter(categoryCount = 0, itemsPerPage = 3)

            val result = pager(adapter).page(ContentType.MOVIE, 1)

            assertTrue(result.items.isEmpty())
            // 分类环为空 = 这个源「回答上了，但这类内容它没有」，所以计入成功源数。
            // 判定成失败会让界面弹「没加载出来，按 OK 重试」，用户重试一百次也是空 —— 语义必须钉住
            assertEquals(1, result.okSources)
            assertTrue(!result.allFailed)
        }

    /** 每次都按 (分类, 页码) 造出新内容，这样「重复」只可能来自翻页逻辑本身 */
    private class CountingAdapter(
        private val categoryCount: Int,
        private val itemsPerPage: Int,
    ) : VodSourceAdapter {
        var categoriesCalls = 0
            private set
        var listCalls = 0
            private set

        override suspend fun categories(): VodFetchResult<List<VodCategory>> {
            categoriesCalls++
            val list =
                (1..categoryCount).map { index ->
                    VodCategory(typeId = "c$index", name = "分类$index", type = ContentType.MOVIE)
                }
            return VodFetchResult.Success(list)
        }

        override suspend fun list(
            typeId: String,
            page: Int,
        ): VodFetchResult<VodPage> {
            listCalls++
            val items =
                (1..itemsPerPage).map { index ->
                    VodSummary(
                        sourceId = 7,
                        sourceKey = "$typeId-p$page-$index",
                        name = "第${page}页第${index}条",
                        type = ContentType.MOVIE,
                    )
                }
            return VodFetchResult.Success(VodPage(items = items, page = page, pageCount = 999))
        }

        override suspend fun detail(sourceKey: String): VodFetchResult<VodDetail?> = VodFetchResult.Success(null)

        override suspend fun search(
            keyword: String,
            page: Int,
        ): VodFetchResult<VodPage> = VodFetchResult.Success(VodPage(items = emptyList()))
    }
}
