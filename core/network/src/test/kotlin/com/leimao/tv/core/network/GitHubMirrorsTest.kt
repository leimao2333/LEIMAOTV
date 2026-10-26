package com.leimao.tv.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像兜底的纯逻辑测试（SET-10）。
 *
 * 不起服务器、不连网：候选列表是纯函数，[MirrorHttpClient] 只依赖 [HttpClient] 接口，
 * 用假实现就能把「第几次尝试、按什么顺序、什么情况下不该重试」全部钉住。
 */
class GitHubMirrorsTest {
    private val rawUrl = "https://raw.githubusercontent.com/leimao2333/leimaotv-sources/main/dist/sources.json"

    @Test
    fun `github 地址的第一个候选是它自己`() {
        val candidates = GitHubMirrors.candidates(rawUrl)
        assertEquals(rawUrl, candidates.first())
        assertTrue("应当带上镜像", candidates.size > 1)
        assertTrue(
            "gh-proxy 是实测可用的首选镜像",
            candidates[1].startsWith("https://gh-proxy.com/"),
        )
        assertTrue(candidates.all { it.endsWith(rawUrl) || it == rawUrl })
    }

    @Test
    fun `非 github 地址不套镜像`() {
        val url = "https://iptv.zsdc.eu.org/home/iptv.m3u8"
        assertEquals(listOf(url), GitHubMirrors.candidates(url))
    }

    @Test
    fun `首尾空白会被去掉`() {
        assertEquals(rawUrl, GitHubMirrors.candidates("  $rawUrl\n").first())
    }

    @Test
    fun `直连超时后换镜像并标记来源`() =
        runBlocking {
            val mirror = GitHubMirrors.candidates(rawUrl)[1]
            val http =
                FakeHttp(
                    mapOf(
                        rawUrl to FetchOutcome.Failure(FetchErrorKind.TIMEOUT),
                        mirror to FetchOutcome.Success("{}"),
                    ),
                )
            val outcome = MirrorHttpClient(http).fetch(rawUrl)

            assertTrue(outcome is FetchOutcome.Success)
            assertEquals("gh-proxy.com", (outcome as FetchOutcome.Success).viaMirror)
            assertEquals("应当先直连、再镜像", listOf(rawUrl, mirror), http.calls)
        }

    @Test
    fun `直连成功时不标记镜像`() =
        runBlocking {
            val http = FakeHttp(mapOf(rawUrl to FetchOutcome.Success("{}")))
            val outcome = MirrorHttpClient(http).fetch(rawUrl)

            assertNull((outcome as FetchOutcome.Success).viaMirror)
            assertEquals("成功就不要再打镜像", 1, http.calls.size)
        }

    @Test
    fun `HTTP 错误不换镜像`() =
        runBlocking {
            // 404 是「这个地址没内容」，换镜像也是同一个结果，重试只是让用户多等
            val http = FakeHttp(mapOf(rawUrl to FetchOutcome.Failure(FetchErrorKind.HTTP, "HTTP 404", 404)))
            val outcome = MirrorHttpClient(http).fetch(rawUrl)

            assertTrue(outcome is FetchOutcome.Failure)
            assertEquals(404, (outcome as FetchOutcome.Failure).httpStatus)
            assertEquals(1, http.calls.size)
        }

    @Test
    fun `全部失败时返回最后一次失败`() =
        runBlocking {
            val http = FakeHttp(emptyMap())
            val outcome = MirrorHttpClient(http).fetch(rawUrl)

            assertTrue(outcome is FetchOutcome.Failure)
            assertEquals(GitHubMirrors.candidates(rawUrl).size, http.calls.size)
        }

    /** 按 URL 回固定结果的假 client，并记录调用顺序 */
    private class FakeHttp(
        private val answers: Map<String, FetchOutcome>,
    ) : HttpClient {
        val calls = mutableListOf<String>()

        override suspend fun fetch(
            url: String,
            headers: RequestHeaders,
        ): FetchOutcome {
            calls += url
            return answers[url] ?: FetchOutcome.Failure(FetchErrorKind.NETWORK, "unreachable")
        }
    }
}
