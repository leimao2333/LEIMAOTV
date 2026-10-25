package com.leimao.tv.feature.search

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
 * 自绘 26 键 + 数字键盘（SEARCH-06）。
 *
 * 为什么自绘而不是只靠系统输入法：TV 盒子的输入法千奇百怪 —— 有的要触摸才能调出拼音候选，
 * 有的干脆没有中文。遥控器上能做到的输入必须由应用自己保证（RULES §6.1 全流程要求）。
 *
 * 为什么是 26 键而不是九宫格：**拼音要能打全拼**（`hunanweishi`）。九宫格一个键三四个字母，
 * 连按既慢又容易按错；26 键一个键一个字母，方向键走位 = 手指走位，一次就对。
 * 打出来的字母会由搜索页转成中文候选（`hnws` 与 `hunan` 都能提示「湖南卫视」）。
 *
 * 键位对齐真实键盘：每行按「列宽」排，短行右侧补空位，纵向移动时手指不会错行。
 */
@Composable
internal fun TvKeypad(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                                    is Key.Letter -> onQueryChange(query + key.char)
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
    }
}

@Composable
private fun KeypadCell(
    key: Key,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val title =
        when (key) {
            is Key.Letter -> key.char.toString()
            Key.Backspace -> "删除"
            Key.Clear -> "清空"
            Key.Empty -> ""
        }
    FocusableCard(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = if (key is Key.Letter) TvText.current.label else TvText.current.body,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private sealed interface Key {
    data class Letter(
        val char: Char,
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

private fun keys(spec: String): List<Slot> = spec.map { Slot(Key.Letter(it)) }

/** 每行按 10 列排；短行右侧补空位 */
private val ROWS: List<List<Slot>> =
    listOf(
        keys("1234567890"),
        keys("qwertyuiop"),
        keys("asdfghjkl") + Slot(Key.Empty),
        keys("zxcvbnm") + List(3) { Slot(Key.Empty) },
        listOf(
            Slot(Key.Backspace, weight = 3f),
            Slot(Key.Empty, weight = 4f),
            Slot(Key.Clear, weight = 3f),
        ),
    )

/**
 * 键高：5 行 + 间距要塞进 1080p 的 540dp 高里，同时给结果区留出至少两行。
 * 46dp 时整块键盘约 250dp。
 */
private val KEY_HEIGHT = 46.dp
