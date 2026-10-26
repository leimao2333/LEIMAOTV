package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 电视端内置键盘（SET-11）。
 *
 * 为什么不用系统输入法：电视上的输入法基本是手机版（Gboard 之类），遥控器进去以后
 * 方向键被它吃掉，上下左右走不出输入框 —— 用户看到的就是「遥控器失灵了」。
 * 而且中文输入法在盒子上大多不可用，输中文这条路本来就不该指望。
 *
 * 这里的键位只覆盖本项目真正需要输入的东西：**地址、UA、端口、拼音**，全是 ASCII。
 * 中文输入不是需求（搜索走拼音表，见 `PinyinTable`），所以不做中文键盘。
 *
 * 尺寸按「最差情况」设计：屏幕高度只有 ~411dp 的盒子（1080p 面板按 420dpi 算）
 * 也要放得下 —— 键盘比可视高度高的话，Compose 会直接裁掉底部那排按钮。
 */
@Composable
fun TvKeyboardDialog(
    title: String,
    initial: String,
    onTextChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var shift by remember { mutableStateOf(false) }
    var symbolPage by remember { mutableStateOf(false) }
    val firstKeyFocus = remember { FocusRequester() }

    fun input(char: String) {
        text = text + char
        onTextChange(text)
    }

    LaunchedEffect(Unit) {
        // 等两帧再请求焦点：Dialog 窗口拿到焦点在组合之后，抢跑会被窗口的首次聚焦顶掉
        withFrameNanos { }
        withFrameNanos { }
        firstKeyFocus.requestFocus()
    }

    // 用 Popup 而不是 Dialog：键盘经常是从「添加源 / 节目单」这些**弹层里**唤起的，
    // Dialog 套 Dialog 时新窗口可能被压在下面（实测：键盘开了但看不见，按键还能打进去）。
    // Popup 挂在调用方所在的窗口里，一定盖在它上面。
    Dialog(
        // 返回键收起键盘（不传 onDismissRequest 的话 BACK 什么都不做，用户会被困在键盘里）
        onDismissRequest = onDone,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                // 电视上没有「点外面关闭」：留着它，打开键盘的那一次按键会把刚出现的键盘关掉，
                // 表现就是「第一次按 OK 没反应」。只保留返回键关闭
                dismissOnClickOutside = false,
            ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(0.94f)
                        .widthIn(max = 1040.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
            ) {
                Text(text = title, style = TvText.current.title, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(6.dp))
                // 当前输入内容常显：键盘挡住了原输入框，不显示的话用户不知道自己在打什么
                Text(
                    text = text.ifEmpty { "（还没输入）" },
                    style = TvText.current.body,
                    color =
                        if (text.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (symbolPage) {
                    SymbolPage(onInput = ::input)
                } else {
                    LetterPage(shift = shift, onInput = ::input, firstKeyFocus = firstKeyFocus)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!symbolPage) {
                        Key(
                            label = if (shift) "ABC" else "abc",
                            modifier = Modifier.weight(1f),
                            onClick = { shift = !shift },
                        )
                    }
                    Key(
                        label = if (symbolPage) "字母" else "符号",
                        modifier = Modifier.weight(1f),
                        onClick = { symbolPage = !symbolPage },
                    )
                    Key(label = "空格", modifier = Modifier.weight(1f), onClick = { input(" ") })
                    Key(
                        label = "退格",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (text.isNotEmpty()) {
                                text = text.dropLast(1)
                                onTextChange(text)
                            }
                        },
                    )
                    Key(
                        label = "清空",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            text = ""
                            onTextChange("")
                        },
                    )
                    Key(label = "完成", modifier = Modifier.weight(1f), accent = true, onClick = onDone)
                }
            }
        }
    }
}

@Composable
private fun LetterPage(
    shift: Boolean,
    onInput: (String) -> Unit,
    firstKeyFocus: FocusRequester,
) {
    val rows =
        listOf(
            "1234567890".map { it.toString() },
            "qwertyuiop".map { it.toString() },
            "asdfghjkl/".map { it.toString() },
            "zxcvbnm.:-".map { it.toString() },
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { rowIndex, keys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEach { key ->
                    val label = if (shift && key.first().isLetter()) key.uppercase() else key
                    Key(
                        label = label,
                        modifier = Modifier.weight(1f).height(KEY_HEIGHT),
                        focusRequester = if (rowIndex == 1 && key == "q") firstKeyFocus else null,
                        onClick = { onInput(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SymbolPage(onInput: (String) -> Unit) {
    val rows =
        listOf(
            listOf("?", "=", "&", "%", "#", "@", "+", "~"),
            listOf(",", ";", ":", "/", ".", "-", "_", "*"),
            listOf("(", ")", "[", "]", "{", "}", "|", "\\"),
            listOf("!", "$", "^", "<", ">", "'", "\"", "`"),
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { keys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEach { key ->
                    Key(
                        label = key,
                        modifier = Modifier.weight(1f).height(KEY_HEIGHT),
                        onClick = { onInput(key) },
                    )
                }
            }
        }
    }
}

/** 键高：四行字母 + 一行操作，在 411dp 高的可视区里也放得下 */
private val KEY_HEIGHT = 40.dp

/**
 * 单个按键。
 *
 * 用 [FocusableCard] 而不是自绘按钮：焦点描边与 OK 键接管都在组件里（RULES §6.2），
 * 各页面自绘的结果就是「有的键按得动、有的键要摸一下才反应」。
 */
@Composable
private fun Key(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    accent: Boolean = false,
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        containerColor = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else null,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = TvText.current.body,
                color = if (accent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
