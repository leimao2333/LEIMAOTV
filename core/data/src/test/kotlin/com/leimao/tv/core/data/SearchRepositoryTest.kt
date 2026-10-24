package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelRepository
import com.leimao.tv.core.database.repository.ChannelWithSource
import com.leimao.tv.core.database.repository.MediaRepository
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.search.SearchHit
import com.leimao.tv.core.model.search.SearchKind
import com.leimao.tv.core.model.search.SearchOutcome
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.network.vod.VodFailure
import com.leimao.tv.core.network.vod.VodSourceAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨库搜索（SEARCH-01 / 02 / 05 / 09）。
 *
 * 这里断言的六件事，每一件都对应一条会在线上造成真实观感的规则：
 * 1. 白名单在**仓库出口**生效 —— UI 拿到的结果里不该有短剧 / 纪录片 / 资讯 / 预告片；
 * 2. VOD 板块不受白名单限制（否则影视板块自己搜不到自己的内容）；
 * 3. 本地结果先出、远端逐源补入 —— 用户不该为最慢的那条源等 8 秒；
 * 4. 单源超时只拖住它自己 —— 慢源失败不该让整页失败；
 * 5. 本地快照与远端结果是同一部片时只出现一次；
 * 6. 空关键词不发任何请求 —— 遥控器上每敲一个键都会触发搜索。
 *
 * 所有远端延时都是虚拟时间：`delay(30_000)` 在这里是毫秒级，超时分支能真跑到。
 */
class SearchRepositoryTest {
    private val fastSource = vodSourceOf(id = 1, name = "快源")
    private val slowSource = vodSourceOf(id = 2, name = "慢源")

    // ---------- 白名单（SEARCH-02） ----------

    @Test
    fun `直播板块的影视结果恰好是白名单六类里的影视五类，外加频道`() =
        runTest {
            val channelRepo = FakeChannelRepository(hits = mapOf("片" to listOf(channelHit(11))))
            val allTypes = FakeVodSearchAdapter(page = VodPage(items = allVodTypes(1)))

            val result = search(channels = channelRepo, adapters = mapOf(1L to allTypes)).finalFrame()

            // 六类 = 直播频道 + 影视五类；短剧 / 纪录片 / 资讯 / 预告片一条都不许出现
            assertEquals(
                setOf(
                    ContentType.LIVE_CHANNEL,
                    ContentType.MOVIE,
                    ContentType.TV_SERIES,
                    ContentType.ANIME,
                    ContentType.VARIETY,
                    ContentType.SPORTS,
                ),
                result.hits.map { it.type }.toSet(),
            )
            assertEquals(6, result.hits.size)
            assertEquals(1, result.channels.size)
            assertEquals(5, result.vod.size)
        }

    @Test
    fun `白名单外的四类在直播板块一条都不出现`() =
        runTest {
            val allTypes = FakeVodSearchAdapter(page = VodPage(items = allVodTypes(1)))

            val result = search(adapters = mapOf(1L to allTypes)).finalFrame()

            listOf(
                ContentType.SHORT_PLAY,
                ContentType.DOCUMENTARY,
                ContentType.NEWS,
                ContentType.TRAILER,
            ).forEach { forbidden ->
                assertEquals(
                    "$forbidden 不该出现在直播板块的搜索结果里",
                    emptyList<SearchHit>(),
                    result.hits.filter { it.type == forbidden },
                )
            }
        }

    @Test
    fun `影视板块九类全返回，且不查频道`() =
        runTest {
            val channelRepo = FakeChannelRepository(hits = mapOf("片" to listOf(channelHit(11))))
            val allTypes = FakeVodSearchAdapter(page = VodPage(items = allVodTypes(1)))

            val result =
                search(channels = channelRepo, adapters = mapOf(1L to allTypes), scope = SearchScope.VOD)
                    .finalFrame()

            assertEquals(ContentType.VOD_TYPES, result.hits.map { it.type }.toSet())
            assertTrue("影视板块不该返回频道", result.channels.isEmpty())
            assertTrue("影视板块压根不该查频道表", channelRepo.searched.isEmpty())
        }

    // ---------- 渐进式（SEARCH-05） ----------

    @Test
    fun `本地结果先出，远端随后补入`() =
        runTest {
            val media = FakeMediaRepository().apply { searchable["流浪"] = listOf(summaryOf(1, "101", "流浪地球")) }
            val slow =
                FakeVodSearchAdapter(
                    delayMs = 500,
                    page = VodPage(items = listOf(summaryOf(1, "202", "流浪地球2"))),
                )

            val frames = search(media = media, adapters = mapOf(1L to slow), query = "流浪").toList()

            assertEquals("先本地一帧、再远端一帧", 2, frames.size)
            assertEquals("第一帧只有本地命中", listOf("流浪地球"), frames[0].vod.map { it.title })
            assertTrue("第一帧还没搜完", frames[0].searching)
            assertEquals(listOf("流浪地球", "流浪地球2"), frames[1].vod.map { it.title })
            assertTrue("最后一帧必须收尾", !frames[1].searching)
        }

    @Test
    fun `本地与远端是同一部片时只出现一次`() =
        runTest {
            val media = FakeMediaRepository().apply { searchable["流浪"] = listOf(summaryOf(1, "101", "流浪地球")) }
            val remote =
                FakeVodSearchAdapter(
                    page =
                        VodPage(
                            items = listOf(summaryOf(1, "101", "流浪地球"), summaryOf(1, "202", "流浪地球2")),
                        ),
                )

            val result = search(media = media, adapters = mapOf(1L to remote), query = "流浪").finalFrame()

            assertEquals(2, result.vod.size)
            assertEquals(2, result.vod.distinctBy { it.key }.size)
            assertEquals("唯一键口径是 (sourceId, sourceKey)", "vod:1:101", result.vod[0].key)
        }

    // ---------- 并发与超时（SEARCH-09） ----------

    @Test
    fun `单源超时只记它自己，不拖累其他源`() =
        runTest {
            val fast = FakeVodSearchAdapter(page = VodPage(items = listOf(summaryOf(1, "1", "快源的片"))))
            val slow =
                FakeVodSearchAdapter(
                    delayMs = 30_000,
                    page = VodPage(items = listOf(summaryOf(2, "2", "慢源的片"))),
                )

            val frames =
                search(
                    sources = listOf(fastSource, slowSource),
                    adapters = mapOf(1L to fast, 2L to slow),
                ).toList()

            val last = frames.last()
            assertEquals("慢源被 8s 超时掐掉，但另一条源的结果必须留下", listOf("快源的片"), last.vod.map { it.title })
            assertEquals(listOf("慢源"), last.failedSources)
            assertEquals(0, last.pendingSources)
            assertEquals("本地一帧 + 两条源各一帧", 3, frames.size)
        }

    @Test
    fun `源报错也只记它自己`() =
        runTest {
            val broken = FakeVodSearchAdapter(failure = VodFailure.HTTP)
            val fast = FakeVodSearchAdapter(page = VodPage(items = listOf(summaryOf(1, "1", "快源的片"))))

            val last =
                search(sources = listOf(fastSource, slowSource), adapters = mapOf(1L to fast, 2L to broken))
                    .finalFrame()

            assertEquals(listOf("慢源"), last.failedSources)
            assertEquals(1, last.vod.size)
        }

    // ---------- 入口与空态 ----------

    @Test
    fun `空关键词不发请求也不查库`() =
        runTest {
            val channelRepo = FakeChannelRepository(hits = mapOf("" to listOf(channelHit(1))))
            val adapter = FakeVodSearchAdapter(page = VodPage(items = allVodTypes(1)))

            val frames = search(channels = channelRepo, adapters = mapOf(1L to adapter), query = " 　 ").toList()

            assertEquals(1, frames.size)
            assertTrue(frames[0].isEmpty)
            assertEquals(0, adapter.searchCalls)
            assertTrue(channelRepo.searched.isEmpty())
        }

    @Test
    fun `没有影视源时只出本地那一帧就收尾`() =
        runTest {
            val channelRepo = FakeChannelRepository(hits = mapOf("片" to listOf(channelHit(11))))

            val frames = search(channels = channelRepo, sources = emptyList()).toList()

            assertEquals(1, frames.size)
            assertEquals(1, frames[0].channels.size)
            assertEquals(0, frames[0].pendingSources)
            assertEquals(SearchKind.CHANNEL, frames[0].hits.single().kind)
        }

    @Test
    fun `搜索结果带上源名与点击所需的信息`() =
        runTest {
            val channelRepo = FakeChannelRepository(hits = mapOf("片" to listOf(channelHit(11))))
            val adapter = FakeVodSearchAdapter(page = VodPage(items = listOf(summaryOf(1, "9", "片名"))))

            val result = search(channels = channelRepo, adapters = mapOf(1L to adapter)).finalFrame()

            val channel = result.channels.single()
            assertEquals("央视 · 直播源", channel.subtitle)
            assertTrue(channel.isActionable)
            assertEquals(11L, channel.channelId)

            val vod = result.vod.single()
            assertEquals("电影 · 2024 · 内地", vod.subtitle)
            assertEquals("快源", vod.sourceName)
            assertEquals("9", vod.sourceKey)
            assertTrue(vod.isActionable)
        }

    // ---------- 工具 ----------

    private fun search(
        channels: ChannelRepository = FakeChannelRepository(),
        media: MediaRepository = FakeMediaRepository(),
        sources: List<Source> = listOf(fastSource),
        adapters: Map<Long, VodSourceAdapter> = emptyMap(),
        query: String = "片",
        scope: SearchScope = SearchScope.IPTV,
    ) = SearchRepository(
        channels = channels,
        media = media,
        sources = FakeSourceRepository(sources),
        http = FakeHttp,
        adapterFactory = { ref -> adapters[ref.id] ?: FakeVodSearchAdapter() },
    ).search(query, scope)

    /** 测试里只关心最后一帧；`toList()` 本身不表达「只要结果」，取最后一帧的意图得写在名字里 */
    private suspend fun Flow<SearchOutcome>.finalFrame(): SearchOutcome = toList().last()

    private fun vodSourceOf(
        id: Long,
        name: String,
    ) = Source(
        id = id,
        name = name,
        url = "https://$name.example.com/api.php/provide/vod",
        type = SourceType.VOD,
    )

    private fun channelHit(
        id: Long,
        name: String = "CCTV-1 综合",
    ) = ChannelWithSource(
        channel =
            Channel(
                id = id,
                name = name,
                groupName = "央视",
                logoUrl = null,
                streamUrl = "https://example.com/live.m3u8",
                number = 1,
            ),
        sourceId = 100,
        sourceName = "直播源",
    )

    private fun summaryOf(
        sourceId: Long,
        key: String,
        title: String,
        type: ContentType = ContentType.MOVIE,
    ) = VodSummary(
        sourceId = sourceId,
        sourceKey = key,
        name = title,
        type = type,
        year = 2024,
        area = "内地",
    )

    /** 九类影视各一条（不含直播频道 —— 那是频道搜索的产物） */
    private fun allVodTypes(sourceId: Long): List<VodSummary> =
        ContentType.VOD_TYPES.mapIndexed { index, type -> summaryOf(sourceId, "k$index", "片-${type.name}", type) }
}
