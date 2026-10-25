package com.leimao.tv.core.network.vod

import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MoonTV 聚合适配器的行为测试。
 *
 * 所有站点域名与片名都是 `example.com` / 「测试影片」这类占位，不碰真实源（RULES §2.1）。
 * HTTP 用脚本化的假实现：按 URL 返回构造的苹果CMS 响应，从而能在 JVM 里验证
 * 「解码清单 → fan-out 聚合 → sourceKey 前缀防撞车 → detail 路由」这一整条链路。
 */
class MoontvVodAdapterTest {
    private val source =
        VodSourceRef(id = 42, baseUrl = "https://example.com/sub.txt", format = VodSourceRef.FORMAT_MOONTV)

    // 两个站点的加密清单（用 BigInteger 实现的 base58Encode 编码，独立于生产解码）
    private val encodedConfig =
        base58Encode(
            """
            {
              "cache_time": 7200,
              "api_site": {
                "site-a.example.com": { "name": "测试站A", "api": "https://site-a.example.com/api.php/provide/vod" },
                "site-b.example.com": { "name": "测试站B", "api": "https://site-b.example.com/api.php/provide/vod" }
              }
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )

    @Test
    fun `分类取首个可用站的分类 并归一成九类名`() =
        runBlocking {
            val http = ScriptedHttp()
            http.respond("https://example.com/sub.txt", FetchOutcome.Success(encodedConfig))
            http.respond(
                "https://site-a.example.com/api.php/provide/vod?ac=list",
                FetchOutcome.Success(categoriesJson(listOf("电影" to "1", "电视剧" to "2", "福利" to "9"))),
            )

            val adapter = MoontvVodAdapter(http, source)
            val result = adapter.categories()

            assertTrue(result is VodFetchResult.Success)
            val categories = (result as VodFetchResult.Success).value
            // 福利映射不出九类被丢掉；电影/电视剧保留，且 typeId 是九类名
            assertEquals(listOf("MOVIE", "TV_SERIES"), categories.map { it.typeId })
            assertEquals(listOf(ContentType.MOVIE, ContentType.TV_SERIES), categories.map { it.type })
        }

    @Test
    fun `列表 fan-out 到两个站 并按站点 key 前缀防撞车`() =
        runBlocking {
            val http = ScriptedHttp()
            http.respond("https://example.com/sub.txt", FetchOutcome.Success(encodedConfig))
            // 站点 A 的分类：电影 type_id=1
            http.respond(
                "https://site-a.example.com/api.php/provide/vod?ac=list",
                FetchOutcome.Success(categoriesJson(listOf("电影" to "1"))),
            )
            // 站点 B 的分类：电影 type_id=7（与 A 不同，验证「各自映射」）
            http.respond(
                "https://site-b.example.com/api.php/provide/vod?ac=list",
                FetchOutcome.Success(categoriesJson(listOf("电影" to "7"))),
            )
            // 站点 A 的电影列表：vod_id=100
            http.respond(
                "https://site-a.example.com/api.php/provide/vod?ac=detail&t=1&pg=1",
                FetchOutcome.Success(listJson(listOf("100" to "测试影片甲"))),
            )
            // 站点 B 的电影列表：vod_id=100（撞车！）
            http.respond(
                "https://site-b.example.com/api.php/provide/vod?ac=detail&t=7&pg=1",
                FetchOutcome.Success(listJson(listOf("100" to "测试影片乙"))),
            )

            val adapter = MoontvVodAdapter(http, source)
            val result = adapter.list("MOVIE", page = 1)

            assertTrue(result is VodFetchResult.Success)
            val page = (result as VodFetchResult.Success).value
            assertEquals(2, page.items.size)
            // 两个站的 vod_id 都是 100，但 sourceKey 被站点 key 前缀区分开
            val keys = page.items.map { it.sourceKey }.toSet()
            assertTrue(keys.contains("site-a.example.com::100"))
            assertTrue(keys.contains("site-b.example.com::100"))
        }

    @Test
    fun `detail 按前缀路由到正确站点 并还原带前缀的 sourceKey`() =
        runBlocking {
            val http = ScriptedHttp()
            http.respond("https://example.com/sub.txt", FetchOutcome.Success(encodedConfig))
            http.respond(
                "https://site-b.example.com/api.php/provide/vod?ac=detail&ids=200",
                FetchOutcome.Success(detailJson("200", "测试影片乙")),
            )

            val adapter = MoontvVodAdapter(http, source)
            val result = adapter.detail("site-b.example.com::200")

            assertTrue(result is VodFetchResult.Success)
            val detail = (result as VodFetchResult.Success).value
            assertEquals("测试影片乙", detail?.summary?.name)
            // 详情返回的 sourceKey 要还原成带前缀的，与列表 / 进度对齐
            assertEquals("site-b.example.com::200", detail?.summary?.sourceKey)
        }

    @Test
    fun `清单拉取失败时 所有能力都返回失败`() =
        runBlocking {
            val http = ScriptedHttp()
            http.respond("https://example.com/sub.txt", FetchOutcome.Failure(FetchErrorKind.HTTP, "HTTP 404", 404))

            val adapter = MoontvVodAdapter(http, source)
            val result = adapter.categories()

            assertTrue(result is VodFetchResult.Failed)
            assertEquals(VodFailure.HTTP, (result as VodFetchResult.Failed).reason)
        }

    @Test
    fun `所有站都失败时 list 才算失败`() =
        runBlocking {
            val http = ScriptedHttp()
            http.respond("https://example.com/sub.txt", FetchOutcome.Success(encodedConfig))
            http.respond(
                "https://site-a.example.com/api.php/provide/vod?ac=list",
                FetchOutcome.Failure(FetchErrorKind.NETWORK),
            )
            http.respond(
                "https://site-b.example.com/api.php/provide/vod?ac=list",
                FetchOutcome.Failure(FetchErrorKind.NETWORK),
            )

            val adapter = MoontvVodAdapter(http, source)
            val result = adapter.list("MOVIE", page = 1)

            assertTrue(result is VodFetchResult.Failed)
        }

    // ---------------------------------------------------------------- 假数据构造

    private fun categoriesJson(entries: List<Pair<String, String>>): String {
        val arr = entries.joinToString(",") { (name, id) -> """{"type_id":"$id","type_name":"$name"}""" }
        return """{"code":1,"class":[$arr]}"""
    }

    private fun listJson(entries: List<Pair<String, String>>): String {
        val arr =
            entries.joinToString(",") { (id, name) ->
                """{"vod_id":"$id","vod_name":"$name","type_name":"电影","vod_remarks":"HD"}"""
            }
        return """{"code":1,"list":[$arr]}"""
    }

    private fun detailJson(
        id: String,
        name: String,
    ): String {
        val playUrl = "第01集${'$'}https://example.com/1.m3u8"
        val item =
            """{"vod_id":"$id","vod_name":"$name","type_name":"电影",""" +
                """"vod_play_from":"线路一","vod_play_url":"$playUrl"}"""
        return """{"code":1,"list":[$item]}"""
    }

    /** 按 URL 精确匹配返回预置响应；没匹配到就返回 NETWORK 失败（暴露「发了预期外的请求」）。 */
    private class ScriptedHttp : HttpClient {
        private val responses = mutableMapOf<String, FetchOutcome>()

        fun respond(
            url: String,
            outcome: FetchOutcome,
        ) {
            responses[url] = outcome
        }

        override suspend fun fetch(
            url: String,
            headers: RequestHeaders,
        ): FetchOutcome = responses[url] ?: FetchOutcome.Failure(FetchErrorKind.NETWORK, "未预期的请求: $url")
    }
}

/** 测试用 Base58 编码（BigInteger 实现，与生产解码独立，非「编解互证」）。 */
private fun base58Encode(bytes: ByteArray): String {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var n = java.math.BigInteger(1, bytes)
    val base = java.math.BigInteger.valueOf(58)
    val sb = StringBuilder()
    while (n > java.math.BigInteger.ZERO) {
        val rem = n.mod(base).toInt()
        sb.insert(0, alphabet[rem])
        n = n.divide(base)
    }
    var pad = 0
    for (b in bytes) {
        if (b.toInt() == 0) pad += 1 else break
    }
    return "1".repeat(pad) + sb
}
