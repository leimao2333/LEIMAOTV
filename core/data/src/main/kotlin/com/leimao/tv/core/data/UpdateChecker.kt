package com.leimao.tv.core.data

import com.leimao.tv.core.model.release.ApkAssets
import com.leimao.tv.core.model.release.DeviceAbi
import com.leimao.tv.core.model.release.ReleaseAsset
import com.leimao.tv.core.model.release.ReleaseInfo
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders
import com.leimao.tv.core.network.release.ReleaseJsonParser
import com.leimao.tv.core.version.release.ReleaseVersion

/**
 * 一次「检查更新」的结论（SET-07）。
 *
 * 六种状态对应六句不同的话，压成「有新版 / 没新版」是错的：
 * 「仓库还没发过版」「发版时漏了附件」「版本号看不懂」对用户和对开发者都是完全不同的两件事，
 * 混在一起只会让人以为是自己网络不好。
 */
sealed interface UpdateCheckResult {
    /** 已是新版（或装的比发布版还新，比如 debug 包） */
    data class UpToDate(val latestVersion: String) : UpdateCheckResult

    /** 有新版，且找到了适配本机架构的包 */
    data class Available(
        val release: ReleaseInfo,
        val asset: ReleaseAsset,
    ) : UpdateCheckResult

    /** 有新版本，但 release 里没有按约定命名的附件：发版时命名错了 / 忘传了 */
    data class MissingAsset(val release: ReleaseInfo) : UpdateCheckResult

    /** 仓库里还没有任何正式发布（GitHub 对 `/releases/latest` 回 404） */
    data object NoRelease : UpdateCheckResult

    /** 响应读不懂：不是发布对象，或版本号不是数字段 */
    data class Unreadable(val detail: String?) : UpdateCheckResult

    data class Failed(
        val kind: FetchErrorKind,
        val httpStatus: Int? = null,
        val detail: String? = null,
    ) : UpdateCheckResult
}

/**
 * 检查更新（SET-07）：抓 GitHub 的 Latest Release → 比较版本 → 按 ABI 选包。
 *
 * 只认 `/releases/latest`：它由 GitHub 保证是「最新的非草稿、非预发布」，自己拉列表再筛一遍
 * 等于把「什么算正式发布」的判断搬到客户端，还要处理分页。少一层判断，少一种不一致。
 *
 * 没有任何缓存与定时检查：电视盒子上「一天查八次」没有意义，流量和 API 配额都是白烧。
 * 只在用户进「检查更新」页时查一次（那次进入本身就是「我想知道有没有新版」），
 * 不做后台轮询，也不在启动路径上加一次网络请求。
 */
class UpdateChecker(
    private val http: HttpClient,
    private val currentVersion: String,
    private val deviceAbi: DeviceAbi = DeviceAbi.ARM64,
    private val apiBase: String = GITHUB_API_BASE,
    private val repository: String = GITHUB_REPOSITORY,
) {
    suspend fun check(): UpdateCheckResult {
        val url = "$apiBase/repos/$repository/releases/latest"
        // GitHub 要求带 User-Agent（缺了会被 403），顺带让它知道是谁在查
        val headers = RequestHeaders(userAgent = "$USER_AGENT_PREFIX$currentVersion")

        return when (val outcome = http.fetch(url, headers)) {
            is FetchOutcome.Failure ->
                if (outcome.httpStatus == HTTP_NOT_FOUND) {
                    UpdateCheckResult.NoRelease
                } else {
                    UpdateCheckResult.Failed(
                        kind = outcome.kind,
                        httpStatus = outcome.httpStatus,
                        detail = outcome.detail,
                    )
                }

            is FetchOutcome.Success -> evaluate(outcome.text)
        }
    }

    /** 纯逻辑部分：拆出来是为了能拿一段 JSON 直接测，不必起网络 */
    internal fun evaluate(
        body: String,
        abi: DeviceAbi = deviceAbi,
    ): UpdateCheckResult {
        val release =
            ReleaseJsonParser.parse(body)
                ?: return UpdateCheckResult.Unreadable("响应不是发布信息")

        val newer =
            ReleaseVersion.isNewer(release.version, currentVersion)
                ?: return UpdateCheckResult.Unreadable("版本号读不懂：${release.version}")

        if (!newer) return UpdateCheckResult.UpToDate(release.version)

        val asset =
            ApkAssets.pick(release.assets, release.version, abi)
                ?: return UpdateCheckResult.MissingAsset(release)

        return UpdateCheckResult.Available(release, asset)
    }

    companion object {
        /** GitHub 公共 API 的固定入口；不给设置项，普通用户不需要也不该改它 */
        const val GITHUB_API_BASE = "https://api.github.com"

        /** 发布来源仓库（与 README、RULES §10.4 是同一个仓库） */
        const val GITHUB_REPOSITORY = "leimao2333/LEIMAOTV"

        /** GitHub 对「查一个不存在的 Latest Release」回 404，这不算故障 */
        private const val HTTP_NOT_FOUND = 404

        private const val USER_AGENT_PREFIX = "leimao-tv/"
    }
}
