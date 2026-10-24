package com.leimao.tv.feature.settings

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 设置子页里的分组标题（播放设置、网络设置共用）。
 *
 * 不用 `MaterialTheme.colorScheme.primary`：它已经被 `ChoiceRow` 的「已选」占用，
 * 分组标题再用同一个颜色，一眼扫过去分不清「这是标题」还是「这是选中的那项」。
 */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = TvText.current.title,
        color = MaterialTheme.colorScheme.onBackground,
    )
}
