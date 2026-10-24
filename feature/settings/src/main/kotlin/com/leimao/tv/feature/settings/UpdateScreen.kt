package com.leimao.tv.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.data.UpdateCheckResult
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.cache.formatCacheSize
import com.leimao.tv.core.model.release.ApkAssets
import com.leimao.tv.core.model.release.ReleaseAsset
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.feature.settings.changelog.renderBoldMarkdown

/**
 * 检查更新（SET-07）。
 *
 * 只有**一个**可聚焦目标，因为这一页的所有操作都是同一件事的下一步：查 → 下 → 装。
 * 把「重新检查」和「下载」并排放，用户会先按错一次再发现按错。
 *
 * 报错文案一律写成「谁的问题 + 现在能做什么」：这一页是用户主动点进来的，
 * 看到「检查失败」而没有下一步，比没做这个功能更让人恼火。
 */
@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) actionFocus.requestFocus() }

    val action =
        primaryAction(
            state = state,
            onCheck = onCheck,
            onDownload = onDownload,
            onInstall = onInstall,
            onOpenInstallSettings = onOpenInstallSettings,
        )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "检查更新",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "当前版本 v${state.currentVersion}",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))
        StatusBlock(state = state)

        Spacer(modifier = Modifier.height(20.dp))
        FocusableCard(
            onClick = { if (action.enabled) action.run() },
            enabled = action.enabled,
            modifier = Modifier.fillMaxWidth().focusRequester(actionFocus),
        ) {
            Column {
                Text(
                    text = action.title,
                    style = TvText.current.label,
                    color =
                        if (action.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = action.subtitle,
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.note?.let { note ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = note,
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 按钮的三要素。抽出来是为了让「按什么键做什么事」只有一个判断处，界面层不再散落一堆 if */
private class PrimaryAction(
    val title: String,
    val subtitle: String,
    val enabled: Boolean = true,
    val run: () -> Unit = {},
)

private fun primaryAction(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
): PrimaryAction {
    // 先绑成局部量：这里要读好几个字段，跨模块 val 属性的智能转换不该成为负担
    val downloading = state.downloading
    val downloaded = state.downloadedPath != null

    return when {
        state.checking ->
            PrimaryAction(title = "正在检查…", subtitle = "正在问 GitHub 有没有新版本", enabled = false)

        downloading != null ->
            PrimaryAction(
                title = downloading.percent?.let { "正在下载 $it%" } ?: "正在下载…",
                subtitle = describeProgress(downloading),
                enabled = false,
            )

        // 下载完但系统不放行：先引导去开开关，而不是按下去什么也不发生
        downloaded && state.needsInstallPermission ->
            PrimaryAction(
                title = "去允许安装",
                subtitle = "系统默认不让装应用商店之外的包，去打开这个开关再回来",
                run = onOpenInstallSettings,
            )

        downloaded ->
            PrimaryAction(
                title = "安装",
                subtitle = "会打开系统安装界面；装完当前进度与源都会保留",
                run = onInstall,
            )

        state.result is UpdateCheckResult.Available ->
            PrimaryAction(
                title = "下载新版本",
                subtitle = "下完再按一次「安装」",
                run = onDownload,
            )

        else ->
            PrimaryAction(
                title = if (state.result == null) "检查更新" else "重新检查",
                subtitle = "只查一次，不在后台偷偷查",
                run = onCheck,
            )
    }
}

private fun describeProgress(progress: DownloadProgress): String {
    val read = formatCacheSize(progress.bytesRead)
    return if (progress.totalBytes > 0L) {
        "$read / ${formatCacheSize(progress.totalBytes)}"
    } else {
        "$read（对方没给总大小）"
    }
}

@Composable
private fun StatusBlock(state: UpdateUiState) {
    val result = state.result
    if (result == null) {
        if (!state.checking) {
            Text(
                text = "点下面的按钮，去 GitHub 看看有没有新版本。",
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    when (result) {
        is UpdateCheckResult.UpToDate -> {
            Headline(text = "已是最新（发布版 v${result.latestVersion}）", highlight = true)
            Hint(text = "如果不是从发布页装的，版本号可能比发布版还新，这是正常的。")
        }

        is UpdateCheckResult.Available -> {
            Headline(text = "有新版本 v${result.release.version}", highlight = true)
            Spacer(modifier = Modifier.height(8.dp))
            AssetLines(asset = result.asset, publishedAt = result.release.publishedAt)
            ReleaseNotes(notes = result.release.notes)
        }

        is UpdateCheckResult.MissingAsset -> {
            Headline(text = "v${result.release.version} 已经发布，但没有能装的包")
            Hint(
                text =
                    "对方这次没按约定给附件命名（应为 ${ApkAssets.arm64(result.release.version)} 等三个名字之一），" +
                        "晚一点再查一次。",
            )
        }

        UpdateCheckResult.NoRelease -> {
            Headline(text = "仓库里还没有发布过正式版本")
            Hint(text = "你现在装的是开发中的版本，正式版发布后会在这里提示。")
        }

        is UpdateCheckResult.Unreadable -> {
            Headline(text = "没能读懂对方的响应")
            Hint(text = result.detail ?: "响应不是发布信息，可能是接口改版了。")
        }

        is UpdateCheckResult.Failed -> {
            Headline(text = "这次没查成")
            Hint(text = describeFailure(result))
        }
    }
}

@Composable
private fun Headline(
    text: String,
    highlight: Boolean = false,
) {
    Text(
        text = text,
        style = TvText.current.title,
        color =
            if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground
            },
    )
}

@Composable
private fun Hint(text: String) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = text,
        style = TvText.current.hint,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AssetLines(
    asset: ReleaseAsset,
    publishedAt: String,
) {
    Text(
        text = "安装包 ${asset.name}",
        style = TvText.current.body,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val size = if (asset.sizeBytes > 0L) formatCacheSize(asset.sizeBytes) else "大小未知"
    Text(
        text = "$size · ${publishedAt.take(10)}",
        style = TvText.current.hint,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 发布说明：markdown 的减法在上面那个纯函数里做，这里只管排版 */
@Composable
private fun ReleaseNotes(notes: String) {
    val lines = remember(notes) { summarizeReleaseNotes(notes) }
    if (lines.isEmpty()) return
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "这个版本改了什么",
        style = TvText.current.label,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(modifier = Modifier.height(6.dp))
    lines.forEach { line ->
        Text(
            text = renderBoldMarkdown(line),
            style = TvText.current.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 失败文案：同一个原因在不同页面上说法不同，这里只说「用户现在能做什么」 */
private fun describeFailure(result: UpdateCheckResult.Failed): String =
    when (result.kind) {
        FetchErrorKind.TIMEOUT -> "连 GitHub 超时了，过一会儿再试一次"
        FetchErrorKind.NETWORK -> "网络不通（${result.detail ?: "无法连接"}），检查一下盒子或路由"
        FetchErrorKind.EMPTY -> "GitHub 返回了空内容，过一会儿再试"
        FetchErrorKind.HTTP ->
            when (result.httpStatus) {
                // 未登录的 GitHub API 每小时只给 60 次；同一出口 IP 下多台设备共用这个额度
                403, 429 -> "GitHub 拒绝了这次请求（HTTP ${result.httpStatus}），多半是查得太频繁，过几分钟再试"
                else -> "GitHub 返回了错误（HTTP ${result.httpStatus ?: "未知"}），过一会儿再试"
            }
    }
