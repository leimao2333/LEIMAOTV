package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 一个筛选维度的文案（VOD-12 / LIVE-26 共用口径）。
 *
 * 纯函数、可单测：未选显示维度名（「地区」），已选显示所选值（「美国」）。
 */
fun filterLabel(
    label: String,
    selected: String?,
): String = selected?.takeIf { it.isNotBlank() } ?: label

/**
 * TV 版常驻筛选行（VOD-12）。
 *
 * 一行放若干 [FilterChip]，每个维度独立下拉。与旧的「折叠按钮 + 展开全部」相比：
 * 每个维度各自收展，聚合源下几十个地区 / 年份不会一次性把影片网格顶出屏幕。
 *
 * 弹层是**内联 Box 覆盖**（不用 `Popup` / `DropdownMenu`）：TV 上 Popup 是独立窗口、
 * 默认不可聚焦，焦点进不去、`onDismissRequest` 在焦点系统下还会误关。内联覆盖层
 * 跟页面同一棵焦点树，方向键移动、OK 确认、BACK 收层都是可预期行为。
 */
@Composable
fun FilterBar(
    filters: List<FilterSpec>,
    modifier: Modifier = Modifier,
) {
    var openIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            filters.forEachIndexed { index, spec ->
                FilterChip(
                    spec = spec,
                    expanded = openIndex == index,
                    onToggle = { openIndex = if (openIndex == index) null else index },
                )
            }
        }

        openIndex?.let { index ->
            val spec = filters.getOrNull(index) ?: return@let
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 56.dp)
                        .zIndex(1f),
            ) {
                ValueMenu(
                    values = spec.values,
                    selected = spec.selected,
                    onPick = { value ->
                        spec.onSelect(value)
                        openIndex = null
                    },
                    onDismiss = { openIndex = null },
                )
            }
        }
    }
}

/** 一个筛选维度：维度名 + 可选值 + 当前选中值 + 选中回调 */
data class FilterSpec(
    val label: String,
    val values: List<String>,
    val selected: String?,
    val onSelect: (String?) -> Unit,
)

@Composable
private fun FilterChip(
    spec: FilterSpec,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    FocusableCard(
        onClick = onToggle,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = filterLabel(spec.label, spec.selected),
                style = TvText.current.label,
                color =
                    if (spec.selected != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (expanded) "▴" else "▾",
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 值弹层：竖向选项列表，遥控器全流程。
 *
 * [onPick] 返回 null 表示「选了全部」（清空筛选），非 null 是选中的值。
 * BACK 走 [onDismiss]（收层、不选值）；OK 走 [onPick]（选值、收层）。
 */
@Composable
private fun ValueMenu(
    values: List<String>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val allOptions = listOf<String?>(null) + values

    Box(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
    ) {
        LazyColumn(
            modifier = Modifier.width(220.dp).heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(allOptions, key = { it ?: "全部" }) { value ->
                val label = value ?: "全部"
                val isSelected = value == selected
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onPick(value) }
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = label,
                        style = TvText.current.body,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
