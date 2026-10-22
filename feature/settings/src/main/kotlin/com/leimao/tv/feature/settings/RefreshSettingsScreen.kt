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
import com.leimao.tv.core.data.RefreshInterval
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 自动刷新设置页（LIVE-07）。
 *
 * 控制「直播源多久自动刷一次」。四档：关闭 / 每 6 / 12 / 24 小时。
 * 「关闭」只是不跑定时任务，手动单源刷新与启动时的后台刷新仍然有效。
 */
@Composable
fun RefreshSettingsScreen(
    interval: RefreshInterval,
    onIntervalChange: (RefreshInterval) -> Unit,
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
            text = "自动刷新",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "直播源多久自动更新一次。关闭后仍可在源管理里手动刷新。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))

        RefreshInterval.entries.forEach { option ->
            val rowModifier =
                if (option == RefreshInterval.DEFAULT) {
                    Modifier.fillMaxWidth().focusRequester(firstFocus)
                } else {
                    Modifier.fillMaxWidth()
                }
            ChoiceRow(
                title = option.label,
                subtitle = option.description(),
                selected = option == interval,
                modifier = rowModifier,
                onClick = { onIntervalChange(option) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun RefreshInterval.description(): String =
    when (this) {
        RefreshInterval.OFF -> "不自动刷新，仅在启动时后台更新一次"
        RefreshInterval.HOURS_6 -> "较新，适合频道变动频繁的源"
        RefreshInterval.HOURS_12 -> "折中，白天和晚上各更新一次"
        RefreshInterval.HOURS_24 -> "每天更新一次，默认"
    }
