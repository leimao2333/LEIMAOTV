package com.leimao.tv.feature.settings

import com.leimao.tv.core.data.UpdateCheckResult

/**
 * 更新页的显示状态（SET-07）。
 *
 * 保持成一个不可变的 data class，是因为它同时被四处改：检查开始 / 检查结束 / 下载进度 / 安装前探测权限。
 * 用散开的 `mutableStateOf` 会出现「正在下载但按钮已经变成安装」这种自相矛盾的中间态。
 */
data class UpdateUiState(
    val currentVersion: String,
    /** 正在查；期间按钮不可按，避免连点打出一串请求 */
    val checking: Boolean = false,
    /** 上一次检查的结论；null 表示还没查过 */
    val result: UpdateCheckResult? = null,
    /** 正在下载时的进度；null 表示没在下 */
    val downloading: DownloadProgress? = null,
    /** 已经下好、等着装的包路径 */
    val downloadedPath: String? = null,
    /** 系统还没允许本应用安装未知来源的应用 */
    val needsInstallPermission: Boolean = false,
    /** 一句补充说明（下载失败原因 / 装完回来后的提示） */
    val note: String? = null,
)

data class DownloadProgress(
    val bytesRead: Long,
    /** 0 表示对方没给总量，此时不显示百分比 */
    val totalBytes: Long,
) {
    val percent: Int?
        get() = if (totalBytes > 0L) ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100) else null
}

/**
 * 把 Release 正文变成几行能读的字。
 *
 * GitHub 的 body 是 markdown：`##` 标题、`**加粗**`、`-` 列表混在一起。电视上没法按 markdown 排版，
 * 但也不该原样照抄 —— 屏幕上出现一堆 `##` 比少显示几行更难看。这里只做**减法**：
 * 去掉标题记号、把列表符号换成 `·`、丢掉分隔线与空行，超长就截断。
 *
 * 截断的阈值按「几屏之内」定：正文动辄几十行，全铺出来会把「下载」按钮挤到看不见的地方。
 */
internal fun summarizeReleaseNotes(
    notes: String,
    maxLines: Int = 30,
    maxChars: Int = 1200,
): List<String> {
    if (notes.isBlank()) return emptyList()
    val lines = mutableListOf<String>()
    var used = 0
    for (raw in notes.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.all { it == '-' || it == '*' || it == '_' || it == '=' }) continue
        // 标题记号去掉后保留文字：「## 新增」→「新增」
        val text = line.trimStart('#', ' ').let { if (it.startsWith("- ")) "· ${it.removePrefix("- ")}" else it }
        if (text.isBlank()) continue
        if (lines.size >= maxLines || used + text.length > maxChars) {
            lines += "…（余下内容去发布页看）"
            break
        }
        lines += text
        used += text.length
    }
    return lines
}
