package com.leimao.tv.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
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
                        return@use FetchOutcome.Failure(FetchErrorKind.HTTP, "HTTP ${response.code}")
                    }
                    val text = response.body?.string().orEmpty()
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
 * 全应用共用的 OkHttp 实例。
 *
 * 抓取播放列表与播放视频流走同一个连接池：很多源对同一域名的连接数有限制，
 * 两套连接池等于自己跟自己抢配额。放在这里而不是各模块 new，也是为了只有一个地方能调参。
 */
object AppHttp {
    val client: OkHttpClient by lazy { OkHttpHttpClient.defaultClient() }
}
