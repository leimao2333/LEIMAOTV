package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.RemoteSettings
import com.leimao.tv.core.database.repository.SourceImportSummary
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient

/**
 * 从**地址**导入源备份（SET-10）。
 *
 * 电视上没有「下载文件 → 文件管理器 → 选文件」这条路：盒子里的浏览器与 SAF 都很难用，
 * 而聚合订阅这类清单天生就是给人一条 URL 的。以前只能导本地文件，等于把「一份备份」
 * 变成了必须先想办法落到本地的东西 —— 于是绝大多数人干脆一条条手输。
 *
 * 只做三件事：抓、交给 [SourceRepository.importJson]、把失败翻译成人话。
 * 去重与字段解析都在既有链路里（[SourceRepository.importJson] / `SourceJson`），
 * 这里不重复实现一遍，否则两条导入路径迟早对不上。
 */
class SourceBackupImporter(
    private val http: HttpClient,
    private val sources: SourceRepository,
    /**
     * 备份里的 `settings` 段落到哪里（LIVE-08）。由应用层注入 ——
     * 偏好存在 :app 的 SettingsStore 里，:core:data 不该知道它。
     */
    private val applySettings: (suspend (RemoteSettings?) -> Unit)? = null,
) {
    suspend fun importFrom(url: String): BackupImportResult {
        val outcome = http.fetch(url)
        if (outcome is FetchOutcome.Failure) return BackupImportResult.Failed(describe(outcome))
        return importText((outcome as FetchOutcome.Success).text)
    }

    /**
     * 文本入口：**本地文件导入也走这里**（SET-03 / SET-10）。
     *
     * 两条入口共用一份实现，是因为「带 settings 的备份」在两边都得生效 ——
     * 各写一份的话，迟早出现「从地址导入会改主题、选文件导入不会」这种鬼故事。
     */
    suspend fun importText(text: String): BackupImportResult =
        try {
            val summary = sources.importJson(text)
            applySettings?.invoke(summary.settings)
            BackupImportResult.Done(summary)
        } catch (e: IllegalArgumentException) {
            // 抓回来的是网页 / 播放列表 / 半截 JSON：解析层给的原因已经够具体（「找不到 sources 列表」）
            BackupImportResult.Failed(e.message ?: "不是有效的源备份文件")
        }

    private fun describe(failure: FetchOutcome.Failure): String =
        when (failure.kind) {
            FetchErrorKind.TIMEOUT -> "抓取超时：地址没有在规定时间内响应"
            FetchErrorKind.HTTP -> "抓取失败：${failure.detail ?: "HTTP 错误"}"
            FetchErrorKind.NETWORK -> "网络错误：${failure.detail ?: "不可达"}"
            FetchErrorKind.EMPTY -> "抓到的内容是空的"
        }
}

sealed interface BackupImportResult {
    data class Done(val summary: SourceImportSummary) : BackupImportResult

    data class Failed(val detail: String) : BackupImportResult
}
