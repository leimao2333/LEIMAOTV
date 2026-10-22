package com.leimao.tv.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.leimao.tv.core.player.BufferProfile
import com.leimao.tv.core.player.PlaybackSettings
import com.leimao.tv.core.player.SeekStep

/**
 * 播放设置页（SET-01）。
 *
 * 三项里的两项目前可调：缓冲档位、快进步长；「默认内核」M1 只有 Media3 一个实现
 * （VLC 是 D-02 决策、+20MB native 依赖，未引入），故只作说明不提供切换。
 *
 * 缓冲档位改了要**下次启动生效**：它是 ExoPlayer 构造期参数，中途改要重建内核、
 * 打断正在播的画面，得不偿失。这一句必须让用户看到，否则他会以为「改了没反应」。
 */
@Composable
fun PlaybackSettingsScreen(
    settings: PlaybackSettings,
    onBufferProfileChange: (BufferProfile) -> Unit,
    onSeekStepChange: (SeekStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "播放设置",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(20.dp))

        SectionTitle(text = "缓冲档位")
        Spacer(modifier = Modifier.height(8.dp))
        BufferProfile.entries.forEach { profile ->
            val rowModifier =
                if (profile == BufferProfile.BALANCED) {
                    Modifier.fillMaxWidth().focusRequester(firstFocus)
                } else {
                    Modifier.fillMaxWidth()
                }
            ChoiceRow(
                title = profile.label,
                subtitle = profile.description,
                selected = profile == settings.bufferProfile,
                modifier = rowModifier,
                onClick = { onBufferProfileChange(profile) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = "缓冲档位更改后下次启动生效。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))
        SectionTitle(text = "快进步长")
        Spacer(modifier = Modifier.height(8.dp))
        SeekStep.entries.forEach { step ->
            ChoiceRow(
                title = step.label,
                subtitle = "点播节目按一下前进 / 后退的时长",
                selected = step.millis == settings.seekIncrementMs,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSeekStepChange(step) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionTitle(text = "默认内核")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                "当前使用内置 Media3 内核（硬解优先，失败自动退软解）。" +
                    "可替换的内置软解内核（VLC）尚未引入。",
            style = TvText.current.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = TvText.current.title,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * 单选行：与 [SettingRow] 的区别是带「已选」态（左侧打勾 + 高亮描边），
 * 用于档位、步长这类「多选一」的配置，而不是「点一下跳走」的入口。
 */
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = TvText.current.label,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = if (selected) "✓" else "",
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
