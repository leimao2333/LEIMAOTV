package com.leimao.tv.core.data

import com.leimao.tv.core.database.dao.EpgDao
import com.leimao.tv.core.database.dao.EpgSourceDao
import com.leimao.tv.core.database.entity.EpgChannelEntity
import com.leimao.tv.core.database.entity.EpgProgrammeEntity
import com.leimao.tv.core.database.entity.EpgSourceEntity
import com.leimao.tv.core.database.repository.EpgRepository
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅源自带节目单地址的自动挂载（LIVE-27）。
 *
 * 断言的重点不是「抓到了节目单」，而是**什么时候不该动手**：用户配过就别覆盖、
 * 同一个地址别重复抓。刷新是高频操作，这两条错了的代价是每次刷新多下一次几 MB 的文件，
 * 或者把用户精心挑的节目单悄悄换掉。
 */
class EpgAutoBinderTest {
    private val epgDao = FakeEpgDao()
    private val sourceDao = FakeEpgSourceDao()
    private val repository = EpgRepository(epgDao, sourceDao)

    private fun binder(http: HttpClient) = EpgAutoBinder(repository, EpgImporter(repository, http), clock = { 42L })

    @Test
    fun `源没带节目单地址时什么都不做`() =
        runTest {
            val http = RecordingHttp(mapOf())

            assertEquals(EpgBindOutcome.NoAddress, binder(http).bind(emptyList()))
            assertTrue(http.requested.isEmpty())
            assertNull(repository.sourceUrl())
        }

    @Test
    fun `首次绑定会登记地址并导入节目单`() =
        runTest {
            val http = RecordingHttp(mapOf("https://example.com/epg.xml" to FetchOutcome.Success(XMLTV)))

            val outcome = binder(http).bind(listOf("https://example.com/epg.xml"))

            assertTrue(outcome is EpgBindOutcome.Bound)
            assertEquals("https://example.com/epg.xml", repository.sourceUrl())
            assertEquals(1, (outcome as EpgBindOutcome.Bound).summary.channelCount)
            assertTrue(outcome.note!!.contains("节目单"))
        }

    @Test
    fun `已配着同一地址时不重复抓`() =
        runTest {
            repository.saveSource("https://example.com/epg.xml", 1L)
            val http = RecordingHttp(mapOf("https://example.com/epg.xml" to FetchOutcome.Success(XMLTV)))

            assertEquals(EpgBindOutcome.AlreadyBound, binder(http).bind(listOf("https://example.com/epg.xml")))
            // 刷新一次就重下一份几 MB 的节目单是不可接受的
            assertTrue(http.requested.isEmpty())
        }

    @Test
    fun `用户配了别的地址时保留用户的选择`() =
        runTest {
            repository.saveSource("https://example.com/mine.xml", 1L)
            val http = RecordingHttp(mapOf("https://example.com/epg.xml" to FetchOutcome.Success(XMLTV)))

            val outcome = binder(http).bind(listOf("https://example.com/epg.xml"))

            assertTrue(outcome is EpgBindOutcome.KeptUserChoice)
            assertEquals("https://example.com/mine.xml", repository.sourceUrl())
            assertTrue(http.requested.isEmpty())
            // 用户的选择被保留，这件事不值得占用状态行
            assertNull(outcome.note)
        }

    @Test
    fun `第一个地址抓不到就试下一个`() =
        runTest {
            val http =
                RecordingHttp(
                    mapOf(
                        "https://example.com/broken.xml" to FetchOutcome.Failure(FetchErrorKind.HTTP, "HTTP 404"),
                        "https://example.com/epg.xml" to FetchOutcome.Success(XMLTV),
                    ),
                )

            val outcome =
                binder(http).bind(listOf("https://example.com/broken.xml", "https://example.com/epg.xml"))

            assertTrue(outcome is EpgBindOutcome.Bound)
            assertEquals("https://example.com/epg.xml", repository.sourceUrl())
            assertEquals(listOf("https://example.com/broken.xml", "https://example.com/epg.xml"), http.requested)
        }

    @Test
    fun `全部地址都抓不到时如实报失败`() =
        runTest {
            val http =
                RecordingHttp(
                    mapOf("https://example.com/epg.xml" to FetchOutcome.Failure(FetchErrorKind.TIMEOUT)),
                )

            val outcome = binder(http).bind(listOf("https://example.com/epg.xml"))

            assertTrue(outcome is EpgBindOutcome.Failed)
            // 地址仍然登记着：源管理页要能显示「这个地址没抓成功」，而不是一片空白
            assertEquals("https://example.com/epg.xml", repository.sourceUrl())
            assertTrue(outcome.note!!.contains("失败"))
        }

    private class RecordingHttp(
        private val outcomes: Map<String, FetchOutcome>,
    ) : HttpClient {
        val requested = mutableListOf<String>()

        override suspend fun fetch(
            url: String,
            headers: RequestHeaders,
        ): FetchOutcome {
            requested += url
            return outcomes[url] ?: FetchOutcome.Failure(FetchErrorKind.NETWORK, "单测不联网")
        }
    }

    private class FakeEpgDao : EpgDao {
        private val channelRows = mutableListOf<EpgChannelEntity>()
        private val programmeRows = mutableListOf<EpgProgrammeEntity>()

        override suspend fun programmeCount(): Int = programmeRows.size

        override suspend fun channelCount(): Int = channelRows.size

        override suspend fun latestStartAt(): Long? = programmeRows.maxOfOrNull { it.startAt }

        override suspend fun insertChannels(channels: List<EpgChannelEntity>) {
            channelRows += channels
        }

        override suspend fun insertProgrammes(programmes: List<EpgProgrammeEntity>) {
            programmeRows += programmes
        }

        override suspend fun clearProgrammes() {
            programmeRows.clear()
        }

        override suspend fun clearChannels() {
            channelRows.clear()
        }

        override suspend fun programmesInWindow(
            normalizedNames: List<String>,
            from: Long,
            to: Long,
        ): List<EpgProgrammeEntity> = emptyList()

        override suspend fun channelsByNormalizedName(normalizedName: String): List<EpgChannelEntity> =
            channelRows.filter { it.normalizedName == normalizedName }
    }

    private class FakeEpgSourceDao : EpgSourceDao {
        private var row: EpgSourceEntity? = null

        override suspend fun first(): EpgSourceEntity? = row

        override suspend fun all(): List<EpgSourceEntity> = listOfNotNull(row)

        override suspend fun upsert(source: EpgSourceEntity): Long {
            row = if (source.id == 0L) source.copy(id = 1L) else source
            return row!!.id
        }

        override suspend fun delete(id: Long) {
            if (row?.id == id) row = null
        }
    }

    private companion object {
        /**
         * 构造的最小 XMLTV：一个台一档节目，够走通「解析 → 合并 → 落库」（RULES §2：不夹带真实数据）。
         *
         * 用 trimIndent 而不是直接给原始串：XML 声明必须出现在**第一个字节**，
         * 前面多一个换行 SAX 就会报「prolog 里不允许有内容」。
         */
        val XMLTV =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="demo"><display-name>示例频道</display-name></channel>
              <programme channel="demo" start="20260101080000 +0800" stop="20260101090000 +0800">
                <title>示例节目</title>
              </programme>
            </tv>
            """.trimIndent()
    }
}
