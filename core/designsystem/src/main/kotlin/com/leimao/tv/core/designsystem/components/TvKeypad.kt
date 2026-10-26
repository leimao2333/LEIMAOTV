package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 自绘 26 键 + 数字键盘（SEARCH-06 / SET-11）。
 *
 * 为什么自绘而不是只靠系统输入法：TV 盒子的输入法千奇百怪 —— 有的要触摸才能调出拼音候选，
 * 有的干脆没有中文。遥控器上能做到的输入必须由应用自己保证（RULES §6.1 全流程要求）。
 *
 * 为什么是 26 键而不是九宫格：**拼音要能打全拼**（`hunanweishi`）。九宫格一个键三四个字母，
 * 连按既慢又容易按错；26 键一个键一个字母，方向键走位 = 手指走位，一次就对。
 * 打出来的字母由搜索页转成中文候选（`hnws`、`hunan`、混拼 `hunws` 都能提示「湖南卫视」）。
 *
 * 键位对齐真实键盘：每行按「列宽」排，短行右侧补空位，纵向移动时手指不会错行。
 *
 * 放在 core:designsystem 而不是某个 feature 里：搜索页与「添加源 / 节目单地址 / 导入备份」
 * 三个弹层都用它 —— 两套键盘的键位与手感一旦分叉，用户每换一个页面就要重新适应一次。
 *
 * [symbolPage] 为 true 时把字母区换成符号区（地址输入要 `/ : . - _` 这些）。
 * 搜索用不到符号，所以默认 false，符号切换键也不出现。
 */
@Composable
fun TvKeypad(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    symbolPage: Boolean = false,
    onToggleSymbols: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    /** 回车：搜索页用它提交（记历史 / 收起键盘），弹层用它代替「完成」 */
    onSubmit: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        (if (symbolPage) SYMBOL_ROWS else LETTER_ROWS).forEach { row ->
            // 必须 fillMaxWidth：Row 是 wrap-content 时，weight 算出来的宽度会把行撑出屏幕
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { slot ->
                    if (slot.key === Key.Empty) {
                        // 空位只占宽度：别让方向键跳进一片虚无
                        Box(modifier = Modifier.weight(slot.weight).height(KEY_HEIGHT))
                    } else {
                        KeypadCell(
                            key = slot.key,
                            modifier = Modifier.weight(slot.weight),
                            onClick = {
                                when (val key = slot.key) {
                                    is Key.Char -> onQueryChange(query + key.value)
                                    Key.Backspace -> if (query.isNotEmpty()) onQueryChange(query.dropLast(1))
                                    Key.Clear -> onQueryChange("")
                                    Key.Empty -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }

        // 底部操作行：删除 / 清空 /（符号切换）/（完成）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (onToggleSymbols != null) {
                KeypadCell(
                    key = Key.Empty,
                    label = if (symbolPage) "字母" else "符号",
                    modifier = Modifier.weight(2f),
                    onClick = onToggleSymbols,
                )
            }
            KeypadCell(
                key = Key.Backspace,
                modifier = Modifier.weight(2f),
                onClick = { if (query.isNotEmpty()) onQueryChange(query.dropLast(1)) },
            )
            KeypadCell(
                key = Key.Clear,
                modifier = Modifier.weight(2f),
                onClick = { onQueryChange("") },
            )
            // 回车：`onSubmit`（搜索）与 `onDone`（完成）是同一颗键的两种语义 ——
            // 电视上没有实体回车键，没有它就只能靠「移开焦点」来确认输入
            val confirm = onDone ?: onSubmit
            if (confirm != null) {
                KeypadCell(
                    key = Key.Empty,
                    label = if (onDone != null) "完成" else "搜索",
                    accent = true,
                    modifier = Modifier.weight(3f),
                    onClick = confirm,
                )
            }
        }
    }
}

@Composable
private fun KeypadCell(
    key: Key,
    modifier: Modifier,
    onClick: () -> Unit,
    label: String? = null,
    accent: Boolean = false,
) {
    val title =
        label ?: when (key) {
            is Key.Char -> key.value.toString()
            Key.Backspace -> "退格"
            Key.Clear -> "清空"
            Key.Empty -> ""
        }
    FocusableCard(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        containerColor = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = if (key is Key.Char) TvText.current.label else TvText.current.body,
                color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private sealed interface Key {
    data class Char(
        val value: kotlin.Char,
    ) : Key

    data object Backspace : Key

    data object Clear : Key

    /** 占位：让短行与长行对齐到同一套列 */
    data object Empty : Key
}

/** 一行里的一个格子：键 + 占几列宽 */
private data class Slot(
    val key: Key,
    val weight: Float = 1f,
)

private fun keys(spec: String): List<Slot> = spec.map { Slot(Key.Char(it)) }

/** 每行按 10 列排；短行右侧补空位 */
private val LETTER_ROWS: List<List<Slot>> =
    listOf(
        keys("1234567890"),
        keys("qwertyuiop"),
        keys("asdfghjkl") + Slot(Key.Empty),
        keys("zxcvbnm") + List(3) { Slot(Key.Empty) },
    )

/** 地址输入要用的符号，按「URL 里出现频率」排 */
private val SYMBOL_ROWS: List<List<Slot>> =
    listOf(
        keys("/:._-?=&%#@"),
        keys("+~,;'\"()[]"),
        keys("{}|\\*!$^<>`"),
    )

/**
 * 键高：4 行字母 + 1 行操作要塞进 411dp 的可视区（1080p 面板按 420dpi 算），
 * 同时给上方的输入回显留出空间。42dp 时整块键盘约 230dp。
 */
private val KEY_HEIGHT = 42.dp
