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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节目单订阅地址刷新（LIVE-23）。
 *
 * 本地导入与订阅刷新必须走同一条「解析 → 合并 → 落库」链路 —— 否则迟早出现
 * 「手动导入的台对得上、定时刷新拉回来的对不上」。这里的断言守的就是这条链路：
 * 抓取结果如何变成库里的频道与节目、失败原因有没有留痕。
 */
class EpgImporterUrlTest {
    private var now = 5_000L

    private class FakeEpgDao : EpgDao {
        val channels = mutableListOf<EpgChannelEntity>()
        val programmes = mutableListOf<EpgProgrammeEntity>()

        override suspend fun programmeCount() = programmes.size

        override suspend fun channelCount() = channels.size

        override suspend fun latestStartAt(): Long? = programmes.maxOfOrNull { it.startAt }

        override suspend fun insertChannels(items: List<EpgChannelEntity>) {
            channels += items
        }

        override suspend fun insertProgrammes(items: List<EpgProgrammeEntity>) {
            programmes += items
        }

        override suspend fun clearProgrammes() = programmes.clear()

        override suspend fun clearChannels() = channels.clear()

        override suspend fun programmesInWindow(
            normalizedNames: List<String>,
            from: Long,
            to: Long,
        ): List<EpgProgrammeEntity> =
            programmes.filter { it.startAt in from..to }

        override suspend fun channelsByNormalizedName(name: String): List<EpgChannelEntity> =
            channels.filter { it.normalizedName == name }

        override suspend fun replaceAll(
            newChannels: List<EpgChannelEntity>,
            newProgrammes: List<EpgProgrammeEntity>,
        ) {
            clearProgrammes()
            clearChannels()
            insertChannels(newChannels)
            insertProgrammes(newProgrammes)
        }
    }

    private class FakeEpgSourceDao : EpgSourceDao {
        var row: EpgSourceEntity? = null

        override suspend fun first(): EpgSourceEntity? = row

        override suspend fun all(): List<EpgSourceEntity> = listOfNotNull(row)

        override suspend fun upsert(source: EpgSourceEntity): Long {
            row = source
            return 1L
        }

        override suspend fun delete(id: Long) {
            if (row?.id == id) row = null
        }
    }

    private class FakeHttp(
        private val outcome: FetchOutcome,
    ) : HttpClient {
        var lastUrl: String? = null

        override suspend fun fetch(
            url: String,
            headers: RequestHeaders,
        ): FetchOutcome {
            lastUrl = url
            return outcome
        }
    }

    private val xmltv =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="cctv-1"><display-name>CCTV-1 高清</display-name></channel>
          <programme start="20261022190000 +0800" stop="20261022200000 +0800" channel="cctv-1">
            <title>新闻联播</title>
          </programme>
        </tv>
        """.trimIndent()

    @Test
    fun `订阅刷新走网络并落库`() =
        runTest {
            val dao = FakeEpgDao()
            val sourceDao = FakeEpgSourceDao()
            val repo = EpgRepository(dao, sourceDao)
            val http = FakeHttp(FetchOutcome.Success(xmltv))
            val importer = EpgImporter(repo, http, clock = { now })

            val result = importer.importUrl("https://epg.example.com/pp.xml")

            val done = result as EpgImportResult.Done
            assertEquals("https://epg.example.com/pp.xml", http.lastUrl)
            // 「CCTV-1 高清」归一成 cctv1 后落库
            assertEquals(1, done.summary.channelCount)
            assertEquals(1, done.summary.programmeCount)
            assertEquals(1, dao.channels.size)
            assertEquals(1, dao.programmes.size)
        }

    @Test
    fun `抓取失败留痕但不写节目表`() =
        runTest {
            val dao = FakeEpgDao()
            val sourceDao = FakeEpgSourceDao()
            sourceDao.upsert(EpgSourceEntity(id = 1, url = "https://epg.example.com/pp.xml"))
            val repo = EpgRepository(dao, sourceDao)
            val http = FakeHttp(FetchOutcome.Failure(FetchErrorKind.TIMEOUT))
            val importer = EpgImporter(repo, http, clock = { now })

            val result = importer.importUrl("https://epg.example.com/pp.xml")

            assertTrue(result is EpgImportResult.Failed)
            assertEquals(EpgFailure.NETWORK, (result as EpgImportResult.Failed).reason)
            assertTrue(dao.programmes.isEmpty())
            assertTrue(sourceDao.row!!.lastRefreshNote!!.contains("超时"))
            assertEquals(now, sourceDao.row!!.updatedAt)
        }

    @Test
    fun `抓到非节目单时明确报不是 XMLTV`() =
        runTest {
            val dao = FakeEpgDao()
            val sourceDao = FakeEpgSourceDao()
            val repo = EpgRepository(dao, sourceDao)
            val http = FakeHttp(FetchOutcome.Success("<html>maintenance</html>"))
            val importer = EpgImporter(repo, http, clock = { now })

            val result = importer.importUrl("https://epg.example.com/pp.xml")

            assertEquals(EpgFailure.NOT_XMLTV, (result as EpgImportResult.Failed).reason)
            assertTrue(dao.programmes.isEmpty())
        }

    @Test
    fun `订阅刷新成功后登记刷新时间与条数`() =
        runTest {
            val dao = FakeEpgDao()
            val sourceDao = FakeEpgSourceDao()
            sourceDao.upsert(EpgSourceEntity(id = 1, url = "https://epg.example.com/pp.xml"))
            val repo = EpgRepository(dao, sourceDao)
            val http = FakeHttp(FetchOutcome.Success(xmltv))
            val importer = EpgImporter(repo, http, clock = { now })

            importer.importUrl("https://epg.example.com/pp.xml")

            val row = sourceDao.row!!
            assertEquals(now, row.updatedAt)
            assertTrue(row.lastRefreshNote!!.contains("1 个频道"))
        }
}
