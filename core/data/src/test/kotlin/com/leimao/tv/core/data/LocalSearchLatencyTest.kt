package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelWithSource
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本地搜索耗时基准（M3 验收：本地结果 < 300ms）。
 *
 * 口径：**26 键输入的现实负载** —— 真实拼音词典（2.6 万字）+ 全量频道（≈1000），
 * 每个键入都要全量跑一遍拼音匹配。影视侧同词典同一套匹配，量频道已足以代表算法成本。
 *
 * 真机绝对值会有出入，这里在 JVM 上量「算法本身的成本」：如果连这个都超 300ms，
 * 真机只会更差；如果远低于（预期 <50ms），真机达标就有把握。
 *
 * 词典文件在 app 模块的 assets 里；测试从相对路径读，读不到就跳过（Assume）——
 * 单测仍要离线可跑（RULES §9.1），只是这份基准在没有词典的环境里没有意义。
 */
class LocalSearchLatencyTest {
    private fun loadPinyinTable(): PinyinTable? {
        // Gradle 单测的工作目录是模块根（core/data），但不同环境可能不同；
        // 把「项目根」与「模块根」两种基准都列出来，取第一个存在的
        val root = File("").absoluteFile
        val candidates =
            listOf(
                File(root, "app/src/main/assets/pinyin_table.txt"),
                File(root, "../app/src/main/assets/pinyin_table.txt"),
                File(root, "../../app/src/main/assets/pinyin_table.txt"),
                File("app/src/main/assets/pinyin_table.txt"),
                File("../app/src/main/assets/pinyin_table.txt"),
            )
        val file = candidates.firstOrNull { it.exists() } ?: return null
        return PinyinTable(file.readText())
    }

    private fun fakeChannels(count: Int): List<ChannelWithSource> =
        (1..count).map { i ->
            ChannelWithSource(
                channel =
                    Channel(
                        id = i.toLong(),
                        name = "测试频道$i",
                        groupName = "测试分组",
                        logoUrl = null,
                        streamUrl = "https://example.com/$i.m3u8",
                        number = i,
                    ),
                sourceId = 1,
                sourceName = "测试源",
            )
        }

    @Test
    fun `本地拼音搜索在300ms预算内`() =
        runTest {
            val table = loadPinyinTable()
            org.junit.Assume.assumeTrue("找不到拼音词典，跳过基准", table != null)

            val allChannels =
                fakeChannels(1000) +
                    listOf("湖南卫视", "浙江卫视", "短剧搞笑社").mapIndexed { i, name ->
                        ChannelWithSource(
                            channel =
                                Channel(
                                    id = 10000L + i,
                                    name = name,
                                    groupName = "地方频道",
                                    logoUrl = null,
                                    streamUrl = "https://example.com/x.m3u8",
                                    number = 9000 + i,
                                ),
                            sourceId = 2,
                            sourceName = "源",
                        )
                    }
            val repo =
                SearchRepository(
                    channels = FakeChannelRepository(allChannels = allChannels),
                    media = FakeMediaRepository(),
                    sources = FakeSourceRepository(listOf()),
                    http = FakeHttp(),
                    pinyin = table!!,
                )

            // 预热：第一次跑会触发类加载与 JIT，不算数
            repo.search("hnws", SearchScope.IPTV).toList()

            val start = System.nanoTime()
            val frames = repo.search("hnws", SearchScope.IPTV).toList()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            // 命中断言：简写能搜到湖南卫视
            assertTrue(
                "hnws 应命中湖南卫视",
                frames.last().hits.any { it.title == "湖南卫视" },
            )
            println("M3 验收基准：本地搜索（1000 频道 + 2.6 万字词典）耗时 ${elapsedMs}ms")
            assertTrue(
                "本地搜索耗时 ${elapsedMs}ms，超过 300ms 预算（M3 验收红线）",
                elapsedMs < 300,
            )
        }

    private class FakeHttp : HttpClient {
        override suspend fun fetch(
            url: String,
            headers: RequestHeaders,
        ): FetchOutcome = FetchOutcome.Failure(FetchErrorKind.NETWORK)
    }
}
