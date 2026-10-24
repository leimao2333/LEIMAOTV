package com.leimao.tv.core.data

import com.leimao.tv.core.model.release.DeviceAbi
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新（SET-07）。
 *
 * 一半用例打的是 `check()` 的接线（地址、UA、404 语义），另一半打的是 `evaluate()` 的判断
 * （要不要劝升级、附件能不能匹配）。后者是纯逻辑，给一段 JSON 就能跑，不必起网络。
 */
class UpdateCheckerTest {
    private fun newChecker(
        outcome: FetchOutcome,
        currentVersion: String = "0.2.9.6",
        abi: DeviceAbi = DeviceAbi.ARM64,
    ): Pair<UpdateChecker, StubHttp> {
        val http = StubHttp(outcome)
        return UpdateChecker(http = http, currentVersion = currentVersion, deviceAbi = abi) to http
    }

    @Test
    fun `请求 Latest Release 并带上自己的 UA`() =
        runTest {
            val (sut, http) = newChecker(FetchOutcome.Success(RELEASE_JSON))

            sut.check()

            assertEquals(
                "https://api.github.com/repos/leimao2333/LEIMAOTV/releases/latest",
                http.lastUrl,
            )
            // GitHub 缺 UA 会回 403，UA 里带版本号也方便在对方日志里认出是谁在查
            assertEquals("leimao-tv/0.2.9.6", http.lastHeaders?.userAgent)
        }

    @Test
    fun `仓库没有发布过任何版本时不算故障`() =
        runTest {
            // 实测：一个 Release 都没有的仓库，`releases/latest` 回 404
            val (sut, _) = newChecker(FetchOutcome.Failure(FetchErrorKind.HTTP, "HTTP 404", 404))
            assertEquals(UpdateCheckResult.NoRelease, sut.check())
        }

    @Test
    fun `403 要如实说是被拒绝了`() =
        runTest {
            val (sut, _) = newChecker(FetchOutcome.Failure(FetchErrorKind.HTTP, "HTTP 403", 403))
            val result = sut.check()
            assertTrue(result is UpdateCheckResult.Failed)
            assertEquals(403, (result as UpdateCheckResult.Failed).httpStatus)
        }

    @Test
    fun `超时按超时报`() =
        runTest {
            val (sut, _) = newChecker(FetchOutcome.Failure(FetchErrorKind.TIMEOUT))
            val result = sut.check()
            assertEquals(FetchErrorKind.TIMEOUT, (result as UpdateCheckResult.Failed).kind)
        }

    @Test
    fun `有新版本时给出 arm64 主包`() =
        runTest {
            val (sut, _) = newChecker(FetchOutcome.Success(RELEASE_JSON))
            val result = sut.check()
            assertTrue(result is UpdateCheckResult.Available)
            result as UpdateCheckResult.Available
            assertEquals("0.2.9.7", result.release.version)
            assertEquals("leimao-tv-v0.2.9.7.apk", result.asset.name)
            assertEquals(28_000_000L, result.asset.sizeBytes)
        }

    @Test
    fun `同版本判为已是最新`() =
        runTest {
            val (sut, _) = newChecker(FetchOutcome.Success(RELEASE_JSON), currentVersion = "0.2.9.7")
            assertEquals(UpdateCheckResult.UpToDate("0.2.9.7"), sut.check())
        }

    @Test
    fun `装得比发布版还新时也判为已是最新`() =
        runTest {
            // 开发机上跑 debug 包（版本领先于已发布版本）时，不该被劝去「升级」到旧版
            val (sut, _) = newChecker(FetchOutcome.Success(RELEASE_JSON), currentVersion = "0.3.0.0")
            assertEquals(UpdateCheckResult.UpToDate("0.2.9.7"), sut.check())
        }

    @Test
    fun `有新版本但附件名全对不上时单独报`() =
        runTest {
            // 三个附件全被改名（发版时换了命名规则），一个都不能拿来用
            val json = RELEASE_JSON.replace("leimao-tv-v0.2.9.7", "LEIMAO-TV-0.2.9.7")
            val (sut, _) = newChecker(FetchOutcome.Success(json))
            val result = sut.check()
            assertTrue(result is UpdateCheckResult.MissingAsset)
            // 报错时要能指出是哪个版本缺包，而不是只说「没有可用更新」
            assertEquals("0.2.9.7", (result as UpdateCheckResult.MissingAsset).release.version)
        }

    @Test
    fun `主包缺失时退到 universal 而不是判成没包`() =
        runTest {
            val json = RELEASE_JSON.replace("$ARM64_ASSET,", "")
            val (sut, _) = newChecker(FetchOutcome.Success(json))
            val result = sut.check()
            assertEquals(
                "leimao-tv-v0.2.9.7-universal.apk",
                (result as UpdateCheckResult.Available).asset.name,
            )
        }

    @Test
    fun `32 位设备在只有 arm64 包时判定没有可用包`() =
        runTest {
            val json = RELEASE_JSON.replace(",$V7A_ASSET", "").replace(",$UNIVERSAL_ASSET", "")
            val (sut, _) = newChecker(FetchOutcome.Success(json), abi = DeviceAbi.ARM32)
            assertTrue(sut.check() is UpdateCheckResult.MissingAsset)
        }

    @Test
    fun `32 位设备在有 v7a 包时拿 v7a`() {
        val (sut, _) = newChecker(FetchOutcome.Success(""), abi = DeviceAbi.ARM32)
        val result = sut.evaluate(RELEASE_JSON)
        assertEquals("leimao-tv-v0.2.9.7-v7a.apk", (result as UpdateCheckResult.Available).asset.name)
    }

    @Test
    fun `版本号读不懂时不要猜成已是最新`() {
        val (sut, _) = newChecker(FetchOutcome.Success(""))
        assertTrue(sut.evaluate("""{"tag_name":"nightly","assets":[]}""") is UpdateCheckResult.Unreadable)
    }

    @Test
    fun `不是发布对象时说清楚而不是崩`() {
        val (sut, _) = newChecker(FetchOutcome.Success(""))
        assertTrue(sut.evaluate("<html>网关错误</html>") is UpdateCheckResult.Unreadable)
        assertTrue(sut.evaluate("""{"message":"API rate limit exceeded"}""") is UpdateCheckResult.Unreadable)
    }

    private class StubHttp(private val outcome: FetchOutcome) : HttpClient {
        var lastUrl: String? = null
        var lastHeaders: RequestHeaders? = null

        override suspend fun fetch(
            url: String,
            headers: RequestHeaders,
        ): FetchOutcome {
            lastUrl = url
            lastHeaders = headers
            return outcome
        }
    }

    private companion object {
        const val ARM64_ASSET =
            """{"name":"leimao-tv-v0.2.9.7.apk","size":28000000,""" +
                """"browser_download_url":"https://example.test/leimao-tv-v0.2.9.7.apk"}"""

        const val V7A_ASSET =
            """{"name":"leimao-tv-v0.2.9.7-v7a.apk","size":25000000,""" +
                """"browser_download_url":"https://example.test/leimao-tv-v0.2.9.7-v7a.apk"}"""

        const val UNIVERSAL_ASSET =
            """{"name":"leimao-tv-v0.2.9.7-universal.apk","size":45000000,""" +
                """"browser_download_url":"https://example.test/leimao-tv-v0.2.9.7-universal.apk"}"""

        /**
         * 单行紧凑 JSON：多行模板 + `trimIndent` 会让内插常量的缩进被改写，
         * 于是「拿常量去 replace 掉某个附件」这种写法会静默失效。挤成一行最不容易出错。
         */
        const val RELEASE_JSON =
            """{"tag_name":"v0.2.9.7","name":"v0.2.9.7 · 检查更新",""" +
                """"published_at":"2026-10-24T04:00:00Z","body":"## 新增\n- 设置里多了「检查更新」",""" +
                """"assets":[$ARM64_ASSET,$V7A_ASSET,$UNIVERSAL_ASSET]}"""
    }
}
