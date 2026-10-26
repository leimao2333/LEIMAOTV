package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.theme.TvText

/** 遥控器上「确认」的三个码：OK（DPAD_CENTER）、回车、小键盘回车 */
private val OPEN_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * TV 上的单行输入框。
 *
 * **不唤起系统输入法**：按 OK 打开内置键盘（[TvKeyboardDialog]）。系统输入法在电视上
 * 是手机版，遥控器进去以后方向键被它吃掉、上下左右走不出来 —— 用户看到的就是
 * 「遥控器失灵」。而且盒子上基本没有可用的中文输入法，这条路本来就不该走。
 *
 * 焦点态用 2dp 描边而不是颜色变化：电视距离远，颜色差在低端面板上看不出来（RULES §6.3）。
 * 放在 core:designsystem 是因为频道抽屉（feature:iptv）的即时过滤也要用它，
 * 不能让两个 feature 各写一份。
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "按 OK 输入",
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Column {
        Text(
            text = label,
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            // 调用方的 modifier 必须落在**这个可聚焦的 Box** 上：对话框首焦点靠
            // `Modifier.focusRequester(...)` 传进来，挂在不可聚焦的外层 Column 上请求不到焦点
            modifier =
                modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color =
                            if (focused) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        shape = shape,
                    )
                    // clickable 同时负责「可聚焦」与「OK 键当点击」（RULES §6.2），
                    // 不再另加 focusable：双焦点目标会让遥控器搜索跳过一个
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { editing = true }
                    // 遥控器 OK 在 Compose 里是 DPAD_CENTER，触屏是点击：两条都要接。
                    // 实测只靠 clickable 时 OK 不触发（部分版本/焦点链下会被吞），
                    // 这里显式接一次，重复触发也无害（都是把 editing 置 true）
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && event.key in OPEN_KEYS) {
                            editing = true
                            true
                        } else {
                            false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                style = TvText.current.body,
                color =
                    if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (editing) {
        TvKeyboardDialog(
            title = label,
            initial = value,
            onTextChange = onValueChange,
            onDone = { editing = false },
        )
    }
}
