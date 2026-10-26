package com.leimao.tv.core.network

/**
 * GitHub 地址的镜像兜底。
 *
 * ## 为什么需要它
 *
 * 本项目的聚合订阅（`dist/sources.json`、`dist/live.txt`）放在 GitHub raw 上，
 * 而 `raw.githubusercontent.com` 在国内经常连不上 —— 直连的结果是「抓取超时」，
 * 用户看到的是「这个 App 的导入坏了」，实际上是网络到不了那个域名。
 * 聚合仓库自己也早就在用 `gh-proxy.com` 代理上游源，是同一件事的同一套解法。
 *
 * ## 为什么是「先直连、失败再换」
 *
 * 直连最快也最干净（不经过第三方）。只有直连**在传输层失败**（超时 / 连不上）时，
 * 才值得换镜像；HTTP 404 / 内容为空换镜像也是同一个结果，白白多等两轮。
 * 这个判断放在 [MirrorHttpClient] 里。
 *
 * 镜像只对 GitHub 的地址生效：普通源站被墙时套一层 gh-proxy 也救不回来
 * （代理只转发 GitHub），反而把用户的真实地址泄露给第三方。
 */
object GitHubMirrors {
    private const val RAW_PREFIX = "https://raw.githubusercontent.com/"

    /** Release 包地址（应用内更新 SET-07）同样常被墙，一并兜底 */
    private const val RELEASE_PREFIX = "https://github.com/"

    /**
     * 镜像前缀，按实测可用性排序（2026-10-26 本机实测）：
     * - `gh-proxy.com`：0.9 秒返回，聚合仓库自己也在用；
     * - `ghproxy.net`：能通但慢（约 9 秒），作为第二备选。
     *
     * `cdn.jsdelivr.net` 与 `raw.gitmirror.com` 实测握手失败，没有放进来 ——
     * 多一个「大概率失败」的候选，只是让用户多等一轮。
     */
    private val PROXIES = listOf("https://gh-proxy.com/", "https://ghproxy.net/")

    /**
     * 给定地址的候选列表：**原地址永远排第一**，后面依次是镜像。
     *
     * 不是 GitHub 的地址只返回它自己（长度 1），调用方据此可以直接跳过兜底逻辑。
     */
    fun candidates(url: String): List<String> {
        val trimmed = url.trim()
        val isGithub =
            trimmed.startsWith(RAW_PREFIX, ignoreCase = true) ||
                trimmed.startsWith(RELEASE_PREFIX, ignoreCase = true)
        if (!isGithub) return listOf(trimmed)
        return buildList {
            add(trimmed)
            PROXIES.forEach { add(it + trimmed) }
        }
    }

    /** 取主机名，用于告诉用户「这次是走镜像拿到的」（不暴露完整地址，RULES §2.5） */
    fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?')
}

/**
 * 给 [HttpClient] 套上「直连失败换镜像」的行为。
 *
 * 为什么做成装饰器而不是塞进 [OkHttpHttpClient]：装饰器可以在测试里用假 client
 * 直接验证「第几次尝试、按什么顺序」，而不用起一个真实服务器；也方便只给部分链路启用。
 *
 * **只在传输层失败时重试**（超时 / DNS / 连接被拒）：
 * - HTTP 4xx/5xx 换镜像也是同一个结果 —— 那是「这个地址没内容」，不是「路不通」；
 * - 内容为空同理。
 *
 * 代价是失败路径变长（最多 3 次尝试）。这个代价只落在**本来就失败了**的请求上，
 * 成功的请求一次就走完，所以播放列表那种几 MB 的抓取不会被放大成三倍流量。
 */
class MirrorHttpClient(
    private val inner: HttpClient,
) : HttpClient {
    override suspend fun fetch(
        url: String,
        headers: RequestHeaders,
    ): FetchOutcome {
        val candidates = GitHubMirrors.candidates(url)
        var lastFailure: FetchOutcome.Failure? = null

        candidates.forEachIndexed { index, candidate ->
            when (val outcome = inner.fetch(candidate, headers)) {
                is FetchOutcome.Success ->
                    // 第一次就是原地址：原样返回，不标 viaMirror
                    return if (index == 0) outcome else outcome.copy(viaMirror = GitHubMirrors.hostOf(candidate))

                is FetchOutcome.Failure -> {
                    if (outcome.kind != FetchErrorKind.TIMEOUT && outcome.kind != FetchErrorKind.NETWORK) {
                        return outcome
                    }
                    lastFailure = outcome
                }
            }
        }
        return lastFailure ?: FetchOutcome.Failure(FetchErrorKind.NETWORK, "全部镜像不可达")
    }
}
