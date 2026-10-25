package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.ChannelWriteStats
import com.leimao.tv.core.database.repository.ChannelWriter
import com.leimao.tv.core.database.repository.NewSource
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.model.playlist.MergedChannel
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新编排（LIVE-03 / LIVE-05）。
 *
 * 这一层的错误处置比「解析对不对」更影响体验：抓取失败、抓到的不是播放列表、
 * 源被删掉，三种情况用户的下一步完全不同。断言的是「失败原因与落库记录」，
 * 因为那是用户在源管理页唯一能看到的东西。
 */
class SourceRefresherTest {
    private val sources = FakeSourceRepository()
    private val writer = FakeChannelWriter()
    private var now = 1_000L

    private fun refresher(http: HttpClient) =
        SourceRefresher(sources = sources, writer = writer, http = http, clock = { now })

    @Test
    fun `抓取超时时记录原因并返回超时`() =
        runTest {
            sources.addSource(Source(id = 1, name = "示例源", url = "https://example.com/a.m3u"))

            val result = refresher(FakeHttp(FetchOutcome.Failure(FetchErrorKind.TIMEOUT))).refresh(1)

            assertTrue(result is RefreshResult.Failed)
            assertEquals(RefreshFailure.TIMEOUT, (result as RefreshResult.Failed).reason)
            assertTrue(sources.noteOf(1)!!.contains("超时"))
            assertTrue(writer.calls.isEmpty())
        }

    @Test
    fun `抓到的内容不是播放列表时不会当成功`() =
        runTest {
            sources.addSource(Source(id = 1, name = "示例源", url = "https://example.com/a.m3u"))

            val result = refresher(FakeHttp(FetchOutcome.Success("<html>404</html>"))).refresh(1)

            assertEquals(RefreshFailure.NO_CHANNELS, (result as RefreshResult.Failed).reason)
            assertTrue(sources.noteOf(1)!!.contains("解析失败"))
            assertTrue(writer.calls.isEmpty())
        }

    @Test
    fun `成功刷新把合并后的频道交给写入层`() =
        runTest {
            sources.addSource(Source(id = 1, name = "示例源", url = "https://example.com/a.m3u"))
            val playlist =
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="demo" group-title="测试",Demo 高清
                https://example.com/1
                #EXTINF:-1 tvg-id="demo" group-title="测试",Demo
                https://example.com/2
                """.trimIndent()

            val result = refresher(FakeHttp(FetchOutcome.Success(playlist))).refresh(1)

            val done = result as RefreshResult.Done
            // 同名同标识的两条被合并成一条、两条线路
            assertEquals(1, done.outcome.channelCount)
            assertEquals(1, writer.calls.single().size)
            assertEquals(2, writer.calls.single().single().lines.size)
            assertEquals("1 个频道", sources.noteOf(1))
        }

    @Test
    fun `源不存在时返回 SOURCE_MISSING`() =
        runTest {
            val result = refresher(FakeHttp(FetchOutcome.Success("x"))).refresh(99)

            assertEquals(RefreshFailure.SOURCE_MISSING, (result as RefreshResult.Failed).reason)
        }

    @Test
    fun `每源请求头被注入到抓取请求`() =
        runTest {
            sources.addSource(
                Source(
                    id = 1,
                    name = "示例源",
                    url = "https://example.com/a.m3u",
                    userAgent = "UA/1.0",
                    referer = "https://example.com",
                    basicUser = "user",
                    basicPass = "pass",
                ),
            )
            val http = FakeHttp(FetchOutcome.Success("#EXTINF:-1,A\nhttps://example.com/1"))

            refresher(http).refresh(1)

            val headers = http.lastHeaders!!
            assertEquals("UA/1.0", headers.userAgent)
            assertEquals("https://example.com", headers.referer)
            assertEquals("user", headers.basicAuth?.username)
            assertEquals("pass", headers.basicAuth?.password)
        }

    @Test
    fun `本地导入会先建源再落库`() =
        runTest {
            val result =
                refresher(FakeHttp(FetchOutcome.Failure(FetchErrorKind.NETWORK)))
                    .importText(
                        name = "本地文件",
                        uri = "content://example/demo.m3u",
                        text = "#EXTINF:-1,A\nhttps://example.com/1",
                    )

            assertTrue(result is RefreshResult.Done)
            assertEquals(1, sources.all().size)
            assertEquals("本地文件", sources.all().single().name)
            assertTrue(sources.noteOf(1)!!.contains("本地文件"))
        }

    @Test
    fun `refreshAll 只刷启用的源`() =
        runTest {
            sources.addSource(Source(id = 1, name = "启用", url = "https://example.com/1.m3u"))
            sources.addSource(Source(id = 2, name = "停用", url = "https://example.com/2.m3u", enabled = false))

            val results = refresher(FakeHttp(FetchOutcome.Success("#EXTINF:-1,A\nhttps://example.com/a"))).refreshAll()

            assertEquals(1, results.size)
            assertEquals(1L, (results.single() as RefreshResult.Done).outcome.sourceId)
        }

    // ---------------- 假实现 ----------------

    private class FakeHttp(private val outcome: FetchOutcome) : HttpClient {
        var lastHeaders: RequestHeaders? = null

        override suspend fun fetch(url: String, headers: RequestHeaders): FetchOutcome {
            lastHeaders = headers
            return outcome
        }
    }

    private class FakeChannelWriter : ChannelWriter {
        val calls = mutableListOf<List<MergedChannel>>()

        override suspend fun replaceForSource(sourceId: Long, merged: List<MergedChannel>): ChannelWriteStats {
            calls += merged
            return ChannelWriteStats(
                inserted = merged.size,
                updated = 0,
                removed = 0,
                lineCount = merged.sumOf { it.lines.size },
            )
        }
    }

    private class FakeSourceRepository : SourceRepository {
        private val data = mutableListOf<Source>()
        private val notes = mutableMapOf<Long, String?>()
        private var nextId = 1L

        fun addSource(source: Source) {
            data += source
            nextId = maxOf(nextId, source.id + 1)
        }

        fun all(): List<Source> = data

        fun noteOf(id: Long): String? = notes[id]

        override suspend fun list(): List<Source> = data

        override suspend fun enabled(): List<Source> = data.filter { it.enabled }

        override suspend fun find(id: Long): Source? = data.firstOrNull { it.id == id }

        override suspend fun countEnabled(): Int = data.count { it.enabled }

        override suspend fun countEnabledLive(): Int = data.count { it.enabled && it.type == "LIVE" }

        override suspend fun add(source: NewSource): Long {
            val id = nextId++
            data +=
                Source(
                    id = id,
                    type = source.type,
                    name = source.name,
                    url = source.url,
                    enabled = true,
                    updatedAt = 0,
                    lastRefreshNote = null,
                    userAgent = source.userAgent,
                    referer = source.referer,
                    basicUser = source.basicUser,
                    basicPass = source.basicPass,
                )
            return id
        }

        override suspend fun delete(id: Long) {
            data.removeAll { it.id == id }
        }

        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            val index = data.indexOfFirst { it.id == id }
            if (index >= 0) data[index] = data[index].copy(enabled = enabled)
        }

        override suspend fun markRefreshed(id: Long, at: Long, note: String?) {
            notes[id] = note
            val index = data.indexOfFirst { it.id == id }
            if (index >= 0) data[index] = data[index].copy(updatedAt = at, lastRefreshNote = note)
        }

        override suspend fun move(id: Long, direction: Int) = Unit

        override suspend fun entryCounts(): Map<Long, Int> = emptyMap()

        override suspend fun exportJson(): String = ""

        override suspend fun importJson(json: String): Int = 0
    }
}
