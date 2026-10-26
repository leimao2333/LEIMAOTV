package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 电视端内置键盘（SET-11）。
 *
 * 为什么不用系统输入法：电视上的输入法基本是手机版（Gboard 之类），遥控器进去以后
 * 方向键被它吃掉，上下左右走不出输入框 —— 用户看到的就是「遥控器失灵了」。
 * 而且中文输入法在盒子上大多不可用，输中文这条路本来就不该指望。
 *
 * 键位复用 [TvKeypad]（与搜索页同一套 26 键）：两套键盘的手感一旦分叉，
 * 用户每换一个页面就要重新适应一次。这里只额外提供**符号页**（地址要用 `/ : . - _`）。
 *
 * 用 `Dialog` 而不是树内覆盖层：它是从树内弹层里打开的，是**单个** Dialog，
 * 不会出现「Dialog 套 Dialog」的层叠问题；而 Popup 在窄屏上会把右侧的键切掉（实测）。
 */
@Composable
fun TvKeyboardDialog(
    title: String,
    initial: String,
    onTextChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var symbolPage by remember { mutableStateOf(false) }

    Dialog(
        // 返回键收起键盘（不传 onDismissRequest 的话 BACK 什么都不做，用户会被困在键盘里）
        onDismissRequest = onDone,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                // 电视上没有「点外面关闭」：留着它，打开键盘的那一次按键会把刚出现的键盘关掉
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
                        // 用 fillMaxWidth 而不是百分比：Dialog 窗口的可用宽度不一定等于屏幕宽度
                        // （刘海/insets 会让窗口更窄），按屏幕比例算就会把右侧的键切掉
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
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

                TvKeypad(
                    query = text,
                    onQueryChange = {
                        text = it
                        onTextChange(it)
                    },
                    symbolPage = symbolPage,
                    onToggleSymbols = { symbolPage = !symbolPage },
                    onDone = onDone,
                )
            }
        }
    }
}
