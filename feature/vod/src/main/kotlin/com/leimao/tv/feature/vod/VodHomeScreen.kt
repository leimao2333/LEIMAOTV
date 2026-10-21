package com.leimao.tv.feature.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

/**
 * 影视首页（M0 占位）。真正的分区与筛选是 M2 的 VOD-03 / HOME-05。
 *
 * 这里的骨架先按「每源一行 + 按类型分栏」的形状摆出来，因为海报墙的列数与单屏密度
 * 是 §6.3 定的（4~6 列），布局定得越晚，后面调焦点越麻烦。
 */
@Composable
fun VodHomeScreen(
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) firstCardFocus.requestFocus() }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "影视",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "M2 起这里会是「最近更新 / 按类型分栏 / 每个源一行」的内容区（VOD-03）",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        PlaceholderRow(title = "继续观看", count = 6, focusRequester = firstCardFocus)
        Spacer(modifier = Modifier.height(20.dp))
        PlaceholderRow(title = "最近更新", count = 6)
        Spacer(modifier = Modifier.height(20.dp))
        PlaceholderRow(title = "电影", count = 6)
    }
}

@Composable
private fun PlaceholderRow(
    title: String,
    count: Int,
    focusRequester: FocusRequester? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // key 必填：列表刷新时焦点才不会跳到第一项（RULES §7 硬性工程要求）
        ) {
            items(items = List(count) { it }, key = { it }) { index ->
                FocusableCard(
                    onClick = { },
                    modifier =
                        if (index == 0 && focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(120.dp).height(160.dp))
                        Text(
                            text = "占位 ${index + 1}",
                            style = TvText.current.hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
