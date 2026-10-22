package com.leimao.tv.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.version.AppVersion
import com.leimao.tv.feature.settings.changelog.renderBoldMarkdown

/**
 * 设置页（M0 占位 + 已经能用的两项：版本号与更新日志）。
 *
 * SET-06「关于」要求的四件东西这里齐了：版本号、更新日志入口、开源许可、**免责声明**。
 * 免责声明不是「有空再加」的文案 —— RULES §2.3 要求它必须始终存在，所以 M0 就写死在这。
 */
@Composable
fun SettingsScreen(
    onOpenChangelog: () -> Unit,
    onOpenSourceManager: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) firstFocus.requestFocus() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "设置",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(20.dp))

        SettingRow(
            title = "播放设置",
            subtitle = "默认内核 / 缓冲档位 / 快进步长 —— M1 先固定为直播档（SET-01）",
            modifier = Modifier.focusRequester(firstFocus),
            onClick = { },
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingRow(
            title = "界面设置",
            subtitle = "主题 / 字号缩放 / 导航自动隐藏 —— M4 落地（SET-02）",
            onClick = { },
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingRow(
            title = "源管理",
            subtitle = "添加订阅源、单源刷新、本地导入（SET-03）",
            onClick = onOpenSourceManager,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingRow(
            title = "网络",
            subtitle = "超时 / 并发 / 代理 —— M4 落地（SET-04）",
            onClick = { },
        )

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "关于",
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(
            title = "更新日志",
            subtitle = "当前版本 ${AppVersion.CURRENT_VERSION}",
            onClick = onOpenChangelog,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingRow(
            title = "开源许可",
            subtitle = "Apache-2.0；内置 VLC(libvlc) 为 LGPL-2.1+，动态链接可替换",
            onClick = { },
        )

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "免责声明",
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                "本应用是播放器，不提供、不内置、不分发任何直播源、影视源或内容；所有源均由用户自行添加。" +
                    "请仅使用你有权访问的源，并遵守当地法律法规与内容版权。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text = title,
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 应用内更新日志（SYS-03）。数据源是 AppVersion.CHANGELOG，与版本号同源。 */
@Composable
fun ChangelogScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "更新日志",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(20.dp))

        AppVersion.CHANGELOG.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = "v${entry.version}",
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.date,
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.changes.forEach { change ->
                Text(
                    // 走 renderBoldMarkdown 而不是把星号 replace 掉 —— SYS-03 要求的是「支持加粗渲染」
                    text = renderBoldMarkdown("· $change"),
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
