package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodEpisode
import com.leimao.tv.core.model.vod.VodLine
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
 * 详情加载（VOD-04）。
 *
 * 断言的重点是**四种结局有没有被分开**：源没了、片子没了、网络挂了、拿到了 ——
 * 这四件事在 UI 上是四个不同的页面（一个引导去源管理、一个建议返回、一个给重试、一个正常展示），
 * 压成「成功 / 失败」两态之后，用户就只能在「重试」和「更用力地重试」之间选。
 */
class VodDetailLoaderTest {
    private val vodSource =
        Source(
            id = 7,
            name = "影视源",
            url = "https://example.com/api.php/provide/vod",
            type = SourceType.VOD,
        )

    private fun loader(
        sources: List<Source> = listOf(vodSource),
        adapter: VodSourceAdapter,
    ) = VodDetailLoader(
        sources = FakeSourceRepository(sources),
        http = FakeHttp,
        adapterFactory = { adapter },
    )

    @Test
    fun `拿到详情时原样返回`() =
        runTest {
            val detail = detailOf("片名")

            val result = loader(adapter = DetailAdapter(VodFetchResult.Success(detail))).load(7, "42")

            assertTrue(result is VodDetailResult.Loaded)
            assertEquals(detail, (result as VodDetailResult.Loaded).detail)
        }

    @Test
    fun `源返回空表示下架，不是失败`() =
        runTest {
            // Success(null) 是 VOD-01 定的语义：「源说查无此片」。它跟抓取失败必须分开，
            // 因为前者重试一万次也没用
            val result = loader(adapter = DetailAdapter(VodFetchResult.Success(null))).load(7, "42")

            assertEquals(VodDetailResult.Gone, result)
        }

    @Test
    fun `源被删掉时给出 NoSource`() =
        runTest {
            val result =
                loader(sources = emptyList(), adapter = DetailAdapter(VodFetchResult.Success(null)))
                    .load(7, "42")

            assertEquals(VodDetailResult.NoSource, result)
        }

    @Test
    fun `源被停用时也给出 NoSource`() =
        runTest {
            val disabled = vodSource.copy(enabled = false)

            val result =
                loader(sources = listOf(disabled), adapter = DetailAdapter(VodFetchResult.Success(null)))
                    .load(7, "42")

            assertEquals(VodDetailResult.NoSource, result)
        }

    @Test
    fun `直播源不会被当成影视源去拉详情`() =
        runTest {
            // 按 id 找到的源类型不对时直接拒掉，而不是拿直播地址去拼 ac=detail
            val live = vodSource.copy(type = SourceType.LIVE)

            val result =
                loader(sources = listOf(live), adapter = DetailAdapter(VodFetchResult.Success(null)))
                    .load(7, "42")

            assertEquals(VodDetailResult.NoSource, result)
        }

    @Test
    fun `抓取失败带上原因`() =
        runTest {
            val result =
                loader(
                    adapter = DetailAdapter(VodFetchResult.Failed(VodFailure.TIMEOUT, "8s")),
                ).load(7, "42")

            assertTrue(result is VodDetailResult.Failed)
            result as VodDetailResult.Failed
            assertEquals(VodFailure.TIMEOUT, result.reason)
            assertEquals("8s", result.detail)
        }

    @Test
    fun `多线路多集原样带出来`() =
        runTest {
            val detail = detailOf("片名")

            val result = loader(adapter = DetailAdapter(VodFetchResult.Success(detail))).load(7, "42")

            val loaded = (result as VodDetailResult.Loaded).detail
            assertEquals(2, loaded.lines.size)
            assertEquals(3, loaded.lines[0].episodes.size)
            assertEquals("第03集", loaded.lines[0].episodes[2].name)
        }

    @Test
    fun `sourceKey 原样传给适配器`() =
        runTest {
            val adapter = DetailAdapter(VodFetchResult.Success(null))

            loader(adapter = adapter).load(7, "abc-def_123")

            assertEquals(listOf("abc-def_123"), adapter.requestedKeys)
        }

    private fun detailOf(name: String): VodDetail =
        VodDetail(
            summary =
                VodSummary(
                    sourceId = 7,
                    sourceKey = "42",
                    name = name,
                    type = ContentType.MOVIE,
                    year = 2024,
                ),
            content = "一段简介",
            lines =
                listOf(
                    VodLine(
                        name = "线路甲",
                        episodes = (1..3).map { VodEpisode("第0${it}集", "https://example.com/a$it.m3u8") },
                    ),
                    VodLine(
                        name = "线路乙",
                        episodes = listOf(VodEpisode("正片", "https://example.com/b.mp4")),
                    ),
                ),
        )
}

/** 只关心 detail 的适配器：其余能力一律返回空，避免测试意外依赖到别的路径 */
private class DetailAdapter(
    private val detail: VodFetchResult<VodDetail?>,
) : VodSourceAdapter {
    val requestedKeys = mutableListOf<String>()

    override suspend fun categories(): VodFetchResult<List<VodCategory>> =
        VodFetchResult.Success(emptyList())

    override suspend fun list(
        typeId: String,
        page: Int,
    ): VodFetchResult<VodPage> = VodFetchResult.Success(VodPage(items = emptyList()))

    override suspend fun detail(sourceKey: String): VodFetchResult<VodDetail?> {
        requestedKeys += sourceKey
        return detail
    }

    override suspend fun search(
        keyword: String,
        page: Int,
    ): VodFetchResult<VodPage> = VodFetchResult.Success(VodPage(items = emptyList()))
}
