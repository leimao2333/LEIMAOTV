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
import androidx.compose.ui.Alignment
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
    onOpenPlaybackSettings: () -> Unit,
    onOpenRefreshSettings: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenLicense: () -> Unit,
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
            subtitle = "缓冲档位 / 快进步长 / 默认内核（SET-01）",
            modifier = Modifier.focusRequester(firstFocus),
            onClick = onOpenPlaybackSettings,
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
            title = "自动刷新",
            subtitle = "直播源多久自动更新一次（LIVE-07）",
            onClick = onOpenRefreshSettings,
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
            onClick = onOpenLicense,
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

/**
 * 开源许可页（SET-06）。四件套里「开源许可」的详情落点：
 * 项目自身 Apache-2.0，内置 VLC(libvlc) 为 LGPL-2.1+ 动态链接。
 *
 * 只做摘要 + 关键义务，不整篇贴许可证原文 —— 电视上滚几十屏 Apache 正文没意义，
 * 用户需要的是「能不能商用、要不要开源自己的代码」这类结论，全文留在根目录 LICENSE。
 */
@Composable
fun LicenseScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "开源许可",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(20.dp))

        LicenseBlock(
            title = "本项目（磊毛TV）",
            license = "Apache-2.0",
            points =
                listOf(
                    "可商用、可修改、可再分发，需保留版权声明与许可文本",
                    "不要求开源你自己的修改",
                    "分发时需注明本软件按 Apache-2.0 许可",
                ),
        )
        Spacer(modifier = Modifier.height(16.dp))

        LicenseBlock(
            title = "内置播放内核 VLC（libvlc）",
            license = "LGPL-2.1+",
            points =
                listOf(
                    "以动态链接方式集成，可被用户替换，不传染本项目源码",
                    "修改 libvlc 本身需公开修改部分",
                    "再分发需同时提供 libvlc 的许可文本与获取源码的途径",
                ),
        )
        Spacer(modifier = Modifier.height(16.dp))

        LicenseBlock(
            title = "其余第三方依赖",
            license = "Apache-2.0 / MIT / BSD",
            points =
                listOf(
                    "仅引入 Apache-2.0 / MIT / BSD 三类许可的依赖（RULES §5.3）",
                    "禁止 GPL / AGPL 依赖进入主工程",
                ),
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "完整许可文本见项目根目录 LICENSE 文件。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun LicenseBlock(
    title: String,
    license: String,
    points: List<String>,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = license,
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        points.forEach { point ->
            Text(
                text = "· $point",
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
