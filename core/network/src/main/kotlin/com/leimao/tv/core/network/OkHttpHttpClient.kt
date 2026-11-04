package com.leimao.tv.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * OkHttp 实现（LIVE-03 / LIVE-06）。
 *
 * 整个 App 共用**一个** [OkHttpClient]：连接池与线程池复用是它性能的大头，
 * 每处 new 一个等于把连接复用全丢掉。播放内核也拿同一个实例（见 :core:data 的装配）。
 */
class OkHttpHttpClient(
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpClient {
    override suspend fun fetch(url: String, headers: RequestHeaders): FetchOutcome =
        withContext(ioDispatcher) {
            val request =
                try {
                    Request.Builder()
                        .url(url)
                        .apply {
                            headers.userAgent?.let { header("User-Agent", it) }
                            headers.referer?.let { header("Referer", it) }
                            headers.basicAuth?.let { header("Authorization", it.toHeaderValue()) }
                        }
                        .build()
                } catch (_: IllegalArgumentException) {
                    // 地址本身不合法（缺协议头、含空格）：这不是网络问题，没必要发出去
                    return@withContext FetchOutcome.Failure(FetchErrorKind.NETWORK, "地址格式不对")
                }

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // 只回状态码：body 里可能有服务端回显的地址，写进日志就泄露了（RULES §2.5）
                        return@use FetchOutcome.Failure(
                            kind = FetchErrorKind.HTTP,
                            detail = "HTTP ${response.code}",
                            httpStatus = response.code,
                        )
                    }
                    val body = response.body
                    val text =
                        if (body == null) {
                            ""
                        } else {
                            // 不用 body.string()：它只按响应头里的 charset 解码，不认「正文本身是
                            // gzip 文件」这种情况（.xml.gz 节目单就是这样发的）。见 HttpBody 注释。
                            HttpBody.decode(
                                bytes = body.bytes(),
                                charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8,
                            )
                        }
                    if (text.isBlank()) {
                        FetchOutcome.Failure(FetchErrorKind.EMPTY)
                    } else {
                        FetchOutcome.Success(text)
                    }
                }
            } catch (e: SocketTimeoutException) {
                FetchOutcome.Failure(FetchErrorKind.TIMEOUT)
            } catch (e: UnknownHostException) {
                FetchOutcome.Failure(FetchErrorKind.NETWORK, "域名解析失败")
            } catch (e: IOException) {
                // 连接被拒 / TLS 失败 / 中途断开都落在这里；只报类型，不打地址
                FetchOutcome.Failure(FetchErrorKind.NETWORK, e.javaClass.simpleName)
            }
        }

    /**
     * 流式抓取（节目单 `.xml.gz` 专用通道）。
     *
     * 关键点是**不 materialize**：`response.body.byteStream()` 直接包一层
     * `BufferedInputStream` + 按魔数套 `GZIPInputStream`，然后交给 [consume]。
     * 整条链上只有 8KB 缓冲在内存里，与文件大小无关。
     *
     * `use {}` 保证 [consume] 返回（或抛异常）之后响应体一定被关闭 —— 否则连接不会
     * 回到池子里，多刷几次节目单就把连接池耗光了。
     */
    override suspend fun <T> fetchStream(
        url: String,
        headers: RequestHeaders,
        consume: (InputStream) -> T,
    ): StreamOutcome<T> =
        withContext(ioDispatcher) {
            val request =
                try {
                    Request.Builder()
                        .url(url)
                        .apply {
                            headers.userAgent?.let { header("User-Agent", it) }
                            headers.referer?.let { header("Referer", it) }
                            headers.basicAuth?.let { header("Authorization", it.toHeaderValue()) }
                        }
                        .build()
                } catch (_: IllegalArgumentException) {
                    return@withContext StreamOutcome.Failure(FetchErrorKind.NETWORK, "地址格式不对")
                }

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use StreamOutcome.Failure(
                            kind = FetchErrorKind.HTTP,
                            detail = "HTTP ${response.code}",
                            httpStatus = response.code,
                        )
                    }
                    val body = response.body ?: return@use StreamOutcome.Failure(FetchErrorKind.EMPTY)
                    StreamOutcome.Success(consume(HttpBody.gzipIfNeeded(body.byteStream())))
                }
            } catch (e: SocketTimeoutException) {
                StreamOutcome.Failure(FetchErrorKind.TIMEOUT)
            } catch (e: UnknownHostException) {
                StreamOutcome.Failure(FetchErrorKind.NETWORK, "域名解析失败")
            } catch (e: IOException) {
                StreamOutcome.Failure(FetchErrorKind.NETWORK, e.javaClass.simpleName)
            }
        }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(NetworkTimeouts.CONNECT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(NetworkTimeouts.READ_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}

/**
 * 全应用共用的 OkHttp **基线**实例。
 *
 * 抓取播放列表与播放视频流走同一个连接池：很多源对同一域名连接数有限制，
 * 两套连接池等于自己跟自己抢配额。放在这里而不是各模块 new，也是为了只有一个地方能调参。
 *
 * SET-04 起它只是「基线」：真正交给各处的 client 由 `buildNetworkClient()` 从它派生，
 * 好让用户配的超时与代理生效。派生会共用这里的连接池与 Dispatcher，
 * 所以「一个实例」的前提没变。
 */
object AppHttp {
    val client: OkHttpClient by lazy { OkHttpHttpClient.defaultClient() }
}
