package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.network.vod.VodFailure
import com.leimao.tv.core.network.vod.VodFetchResult
import com.leimao.tv.core.network.vod.VodSourceAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影视首页的数据编排（VOD-03）。
 *
 * 断言的重点不是「发了几个请求」，而是**失败怎么归类**与**哪些类型该落库** ——
 * 用户看到的「这一栏是空的」到底是源没有这类内容、还是这次没抓到，全由这里决定。
 */
class VodCatalogRefresherTest {
    private val media = FakeMediaRepository()
    private var now = 5_000L

    private fun refresher(
        sources: List<Source>,
        adapter: VodSourceAdapter,
    ) = VodCatalogRefresher(
        sources = FakeSourceRepository(sources),
        media = media,
        http = FakeHttp,
        clock = { now },
        adapterFactory = { adapter },
    )

    @Test
    fun `没有影视源时什么也不做`() =
        runTest {
            val report =
                refresher(
                    sources =
                        listOf(
                            Source(id = 1, name = "直播源", url = "https://example.com/live.txt", type = SourceType.LIVE),
                        ),
                    adapter = FakeAdapter(VodFetchResult.Success(emptyList()), emptyMap()),
                ).refreshAll()

            assertTrue(report.outcomes.isEmpty())
            assertTrue(media.replaced.isEmpty())
        }

    @Test
    fun `直播源混在列表里也不会被当成影视源去抓`() =
        runTest {
            val report =
                refresher(
                    sources =
                        listOf(
                            Source(id = 1, name = "直播源", url = "https://example.com/live.txt", type = SourceType.LIVE),
                            Source(
                                id = 2,
                                name = "影视源",
                                url = "https://example.com/api.php/provide/vod",
                                type = SourceType.VOD,
                            ),
                        ),
                    adapter =
                        FakeAdapter(
                            VodFetchResult.Success(listOf(category("电影", "1", ContentType.MOVIE))),
                            mapOf("1" to VodFetchResult.Success(pageOf(3))),
                        ),
                ).refreshAll()

            assertEquals(1, report.outcomes.size)
            assertEquals(2L, report.outcomes.single().sourceId)
        }

    @Test
    fun `分类一个都映射不出时归为不是影视源`() =
        runTest {
            val report =
                refresher(
                    sources = listOf(vodSource()),
                    adapter =
                        FakeAdapter(
                            // 源自定义的分类，映射不到九类
                            VodFetchResult.Success(listOf(VodCategory("9", "福利", null))),
                            emptyMap(),
                        ),
                ).refreshAll()

            assertEquals(VodFailure.NOT_VOD_SOURCE, report.outcomes.single().failure)
            assertTrue(media.replaced.isEmpty())
        }

    @Test
    fun `分类接口自己失败时保留原始原因`() =
        runTest {
            val report =
                refresher(
                    sources = listOf(vodSource()),
                    adapter = FakeAdapter(VodFetchResult.Failed(VodFailure.TIMEOUT), emptyMap()),
                ).refreshAll()

            assertEquals(VodFailure.TIMEOUT, report.outcomes.single().failure)
        }

    @Test
    fun `顶层分类为空时回退到同类型的下一个分类`() =
        runTest {
            // 「电影」是空分类、「动作片」有内容 —— 不少源就是这么组织的
            val report =
                refresher(
                    sources = listOf(vodSource()),
                    adapter =
                        FakeAdapter(
                            VodFetchResult.Success(
                                listOf(
                                    category("电影", "1", ContentType.MOVIE),
                                    category("动作片", "2", ContentType.MOVIE),
                                ),
                            ),
                            mapOf(
                                "1" to VodFetchResult.Success(pageOf(0)),
                                "2" to VodFetchResult.Success(pageOf(4)),
                            ),
                        ),
                ).refreshAll()

            val outcome = report.outcomes.single()
            assertTrue(outcome.ok)
            assertEquals(setOf(ContentType.MOVIE), outcome.fetchedTypes)
            assertEquals(4, outcome.itemCount)
        }

    @Test
    fun `只把抓到内容的类型落库`() =
        runTest {
            refresher(
                sources = listOf(vodSource()),
                adapter =
                    FakeAdapter(
                        VodFetchResult.Success(
                            listOf(
                                category("电影", "1", ContentType.MOVIE),
                                category("电视剧", "2", ContentType.TV_SERIES),
                            ),
                        ),
                        mapOf(
                            "1" to VodFetchResult.Success(pageOf(2)),
                            // 电视剧这一栏这次抓挂了：它的旧快照必须留着，不能被空数据覆盖
                            "2" to VodFetchResult.Failed(VodFailure.HTTP),
                        ),
                    ),
            ).refreshAll()

            val pages = media.replaced.single().second
            assertEquals(setOf(ContentType.MOVIE), pages.keys)
            assertEquals(2, pages.getValue(ContentType.MOVIE).size)
        }

    @Test
    fun `所有类型都空时报 EMPTY 且不落库`() =
        runTest {
            val report =
                refresher(
                    sources = listOf(vodSource()),
                    adapter =
                        FakeAdapter(
                            VodFetchResult.Success(
                                listOf(
                                    category("电影", "1", ContentType.MOVIE),
                                    category("电视剧", "2", ContentType.TV_SERIES),
                                ),
                            ),
                            mapOf(
                                "1" to VodFetchResult.Success(pageOf(0)),
                                "2" to VodFetchResult.Success(pageOf(0)),
                            ),
                        ),
                ).refreshAll()

            assertEquals(VodFailure.EMPTY, report.outcomes.single().failure)
            assertTrue(media.replaced.isEmpty())
        }

    @Test
    fun `落库时间取注入的时钟`() =
        runTest {
            refresher(
                sources = listOf(vodSource()),
                adapter =
                    FakeAdapter(
                        VodFetchResult.Success(listOf(category("电影", "1", ContentType.MOVIE))),
                        mapOf("1" to VodFetchResult.Success(pageOf(1))),
                    ),
            ).refreshAll()

            assertEquals(now, media.replaced.single().third)
            assertEquals(1L, media.replaced.single().first)
        }

    // ------------------------------------------------------------------ //
    // 候选分类的挑选规则（纯函数，单独钉住）
    // ------------------------------------------------------------------ //

    @Test
    fun `同一类型最多留两个候选`() {
        val picked =
            pickVodCandidates(
                listOf(
                    category("电影", "1", ContentType.MOVIE),
                    category("动作片", "2", ContentType.MOVIE),
                    category("喜剧片", "3", ContentType.MOVIE),
                ),
            )

        assertEquals(listOf("1", "2"), picked.getValue(ContentType.MOVIE).map { it.typeId })
    }

    @Test
    fun `映射不出的分类不参与`() {
        val picked = pickVodCandidates(listOf(VodCategory("9", "福利", null)))

        assertTrue(picked.isEmpty())
    }

    @Test
    fun `候选保持源返回的顺序`() {
        val picked =
            pickVodCandidates(
                listOf(
                    category("动作片", "2", ContentType.MOVIE),
                    category("电影", "1", ContentType.MOVIE),
                ),
            )

        assertEquals(listOf("2", "1"), picked.getValue(ContentType.MOVIE).map { it.typeId })
    }

    private fun vodSource() =
        Source(id = 1, name = "影视源", url = "https://example.com/api.php/provide/vod", type = SourceType.VOD)

    private fun category(
        name: String,
        typeId: String,
        type: ContentType?,
    ) = VodCategory(typeId = typeId, name = name, type = type)

    private fun pageOf(count: Int) =
        VodPage(
            items =
                (1..count).map { index ->
                    VodSummary(
                        sourceId = 1,
                        sourceKey = "key-$index",
                        name = "作品 $index",
                        type = ContentType.MOVIE,
                    )
                },
        )
}

private class FakeAdapter(
    private val categories: VodFetchResult<List<VodCategory>>,
    private val pages: Map<String, VodFetchResult<VodPage>>,
) : VodSourceAdapter {
    override suspend fun categories(): VodFetchResult<List<VodCategory>> = categories

    override suspend fun list(
        typeId: String,
        page: Int,
    ): VodFetchResult<VodPage> = pages[typeId] ?: VodFetchResult.Success(VodPage(items = emptyList()))

    override suspend fun detail(sourceKey: String): VodFetchResult<VodDetail?> = VodFetchResult.Success(null)

    override suspend fun search(
        keyword: String,
        page: Int,
    ): VodFetchResult<VodPage> = VodFetchResult.Success(VodPage(items = emptyList()))
}
