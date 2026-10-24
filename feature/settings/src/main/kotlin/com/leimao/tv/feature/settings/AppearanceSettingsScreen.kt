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
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.appearance.FontScale
import com.leimao.tv.core.model.appearance.ThemeMode

/**
 * 界面设置页（SET-02）：主题与字号。
 *
 * 两项都是**即时生效**：改了立刻在眼前变，不需要「保存」也不需要重启 ——
 * 主题和字号恰恰是那种「得先看见才能判断」的设置，让你点确定再看效果，
 * 结果只能是反复进出这一页。
 *
 * 字号用整体缩放而不是逐页调：RULES §6.3 的 16sp / 14sp 底线是按 1.0 定的，
 * 各页自己改字号就没法保证任意页面都不低于底线。
 */
@Composable
fun AppearanceSettingsScreen(
    themeMode: ThemeMode,
    fontScale: FontScale,
    onThemeModeChange: (ThemeMode) -> Unit,
    onFontScaleChange: (FontScale) -> Unit,
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
            text = "界面设置",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(20.dp))

        SectionLabel("主题")
        Spacer(modifier = Modifier.height(8.dp))
        ThemeMode.entries.forEach { mode ->
            ChoiceRow(
                title = mode.label,
                subtitle = mode.description,
                selected = mode == themeMode,
                modifier =
                    if (mode == ThemeMode.DEFAULT) {
                        Modifier.fillMaxWidth().focusRequester(firstFocus)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                onClick = { onThemeModeChange(mode) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel("字号")
        Spacer(modifier = Modifier.height(8.dp))
        FontScale.entries.forEach { option ->
            ChoiceRow(
                title = option.label,
                subtitle = fontScaleDescription(option),
                selected = option == fontScale,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onFontScaleChange(option) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "字号改变后部分文字会换行更多，布局本身不变。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * 字号档位的说明写成百分比而不是「小 / 中 / 大」：百分比是能对照的，
 * 「大」到底比「标准」大多少没人知道。
 */
internal fun fontScaleDescription(option: FontScale): String {
    val percent = (option.scale * 100).toInt()
    return when (option) {
        FontScale.STANDARD -> "默认大小"
        FontScale.SMALL -> "整体缩到 $percent%，屏幕小时能多放一行"
        FontScale.LARGE -> "整体放大到 $percent%"
        FontScale.HUGE -> "整体放大到 $percent%，坐得远也能看清；部分文字会换行"
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = TvText.current.title,
        color = MaterialTheme.colorScheme.onBackground,
    )
}
