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

    companion object {
        val NONE = RequestHeaders()
    }
}

data class BasicAuth(val username: String, val password: String) {
    fun toHeaderValue(): String =
        "Basic " +
            android.util.Base64.encodeToString(
                "$username:$password".toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
}

/**
 * 抓取的结局。
 *
 * 为什么不用「抛异常 + 上层 catch」：用户需要知道**为什么**失败 —— 超时、404、还是内容不是
 * 播放列表，对应三种完全不同的处置（换个时间重试 / 地址写错了 / 这个源不适用）。
 * 把这些折成一句「加载失败」，等于把排查成本转移给用户。
 */
sealed interface FetchOutcome {
    data class Success(val text: String) : FetchOutcome

    data class Failure(
        val kind: FetchErrorKind,
        /** 只放对用户有意义的信息（HTTP 状态码 / 异常类型），**不放完整地址**（RULES §2.5） */
        val detail: String? = null,
    ) : FetchOutcome
}

enum class FetchErrorKind {
    /** 连不上或读超时：稍后重试有意义 */
    TIMEOUT,

    /** 服务端回了非 2xx：多半是地址过期或需要鉴权 */
    HTTP,

    /** DNS / 连接被拒 / TLS 失败 */
    NETWORK,

    /** 通了但内容为空 */
    EMPTY,
}

/**
 * 统一的 HTTP 入口。超时 / UA / Referer / 认证注入都收在这里（RULES §4），
 * 解析器与源刷新都只依赖这个接口，测试时可以换成假实现。
 */
interface HttpClient {
    suspend fun fetch(url: String, headers: RequestHeaders = RequestHeaders.NONE): FetchOutcome
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
