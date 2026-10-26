package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.RemoteSettings
import com.leimao.tv.core.database.repository.SourceImportSummary
import com.leimao.tv.core.model.appearance.ThemeMode
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从地址导入源备份（SET-10）。
 *
 * 这一层只负责「抓 → 交给导入链路 → 把失败翻译成人话」，所以断言的是三种结局的边界：
 * 抓不到、抓到但不是备份、抓到且是备份。解析与去重另有单测（`SourceJsonTest`）。
 */
class SourceBackupImporterTest {
    private val sources = FakeSourceRepository(emptyList())

    @Test
    fun `抓到备份就交给导入链路`() =
        runTest {
            sources.importSummary = SourceImportSummary(added = 3, skipped = 1, addedLiveIds = listOf(1L, 2L))
            val http = TextHttp(FetchOutcome.Success(BACKUP))

            val result = SourceBackupImporter(http, sources).importFrom("https://example.com/sources.json")

            assertTrue(result is BackupImportResult.Done)
            assertEquals(3, (result as BackupImportResult.Done).summary.added)
            assertEquals(1, result.summary.skipped)
        }

    @Test
    fun `抓不到时说清是超时还是 HTTP`() =
        runTest {
            val timeout =
                SourceBackupImporter(TextHttp(FetchOutcome.Failure(FetchErrorKind.TIMEOUT)), sources)
                    .importFrom("https://example.com/sources.json")
            assertTrue((timeout as BackupImportResult.Failed).detail.contains("超时"))

            val notFound =
                SourceBackupImporter(TextHttp(FetchOutcome.Failure(FetchErrorKind.HTTP, "HTTP 404")), sources)
                    .importFrom("https://example.com/sources.json")
            assertTrue((notFound as BackupImportResult.Failed).detail.contains("404"))
        }

    @Test
    fun `内容不是备份文件时如实报错`() =
        runTest {
            // 抓回来一整个网页：解析层抛的是「找不到 sources 列表」，比「导入失败」有用得多
            sources.importError = IllegalArgumentException("不是有效的源备份文件：找不到 sources 列表")

            val result =
                SourceBackupImporter(TextHttp(FetchOutcome.Success("<html>404</html>")), sources)
                    .importFrom("https://example.com/sources.json")

            assertTrue(result is BackupImportResult.Failed)
            assertTrue((result as BackupImportResult.Failed).detail.contains("sources"))
        }

    @Test
    fun `备份里的 settings 会被交给应用层落地`() =
        runTest {
            // 解析本身由 SourceJsonTest 钉；这里验的是「导入器把 summary.settings 交出去」
            sources.importSummary =
                SourceImportSummary(added = 1, skipped = 0, settings = RemoteSettings(12, ThemeMode.DARK))
            var applied: RemoteSettings? = null
            val importer =
                SourceBackupImporter(
                    http = TextHttp(FetchOutcome.Success(BACKUP_WITH_SETTINGS)),
                    sources = sources,
                    applySettings = { applied = it },
                )

            importer.importFrom("https://example.com/sources.json")

            assertEquals(12, applied?.refreshHours)
            assertEquals(ThemeMode.DARK, applied?.theme)
        }

    @Test
    fun `没有 settings 段时不打扰设置`() =
        runTest {
            var called = false
            val importer =
                SourceBackupImporter(
                    http = TextHttp(FetchOutcome.Success(BACKUP)),
                    sources = sources,
                    applySettings = { called = true },
                )

            importer.importFrom("https://example.com/sources.json")

            // 解析出来是 null 也照样回调一次：应用层拿到 null 直接返回，
            // 语义上「这次没有偏好要改」，比「悄悄不回调」更好排查
            assertTrue(called)
        }

    private class TextHttp(
        private val outcome: FetchOutcome,
    ) : HttpClient {
        override suspend fun fetch(
            url: String,
            headers: RequestHeaders,
        ): FetchOutcome = outcome
    }

    private companion object {
        /** 构造的备份样本（RULES §2：示例域名，不夹带真实源地址） */
        val BACKUP =
            """
            {"version":1,"sources":[
              {"type":"LIVE","name":"示例源","url":"https://example.com/a.m3u"}
            ]}
            """.trimIndent()

        /** 带远程偏好（LIVE-08）的备份 */
        val BACKUP_WITH_SETTINGS =
            """
            {"version":1,"settings":{"refreshHours":12,"theme":"DARK"},"sources":[
              {"type":"LIVE","name":"示例源","url":"https://example.com/a.m3u"}
            ]}
            """.trimIndent()
    }
}
