package com.leimao.tv.core.network

/**
 * 每源可配的请求头（LIVE-06）。
 *
 * 为什么单独一个类型而不是 Map<String, String>：UA / Referer 是**业务字段**，
 * 要在 UI 上编辑、要落库、要注入解析与播放两条链路；用裸 Map 会让「哪个 key 是 UA」散落各处。
 */
data class RequestHeaders(
    val userAgent: String? = null,
    val referer: String? = null,
    val basicAuth: BasicAuth? = null,
) {
    fun toPlaybackHeaders(): Map<String, String> =
        buildMap {
            userAgent?.let { put("User-Agent", it) }
            referer?.let { put("Referer", it) }
            basicAuth?.let { put("Authorization", it.toHeaderValue()) }
        }
}

data class BasicAuth(val username: String, val password: String) {
    fun toHeaderValue(): String =
        "Basic " +
            android.util.Base64.encodeToString(
                "$username:$password".toByteArray(),
                android.util.Base64.NO_WRAP,
            )
}

/**
 * 统一的 HTTP 入口。M1 用 OkHttp 实现（RULES §4：超时 / 重试 / UA / Referer 注入都收在这里）。
 * 现在只有契约，没有实现 —— M0 不联网。
 */
interface HttpClient {
    suspend fun getText(url: String, headers: RequestHeaders = RequestHeaders()): String
}

/**
 * 超时与并发一律提成常量并写清依据（RULES §8.4：禁止魔法数）。
 */
object NetworkTimeouts {
    /** 连不上就该尽快告诉用户，别让人对着转圈等半分钟 */
    const val CONNECT_SECONDS = 10L

    /** 直播源列表动辄几 MB，读超时要比连接宽松 */
    const val READ_SECONDS = 15L

    /** SEARCH-09：单源 8s 超时，慢源不许拖住已经出结果的那部分 */
    const val SEARCH_PER_SOURCE_SECONDS = 8L

    /** 断流重连次数（PLAY-08）：3 次之后就该给用户明确出口，而不是无限重试 */
    const val PLAYBACK_RETRY_COUNT = 3
}
