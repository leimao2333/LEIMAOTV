package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * TV 上的单行输入框。
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
    placeholder: String = "点此输入",
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Column(modifier = modifier) {
        Text(
            text = label,
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TvText.current.body.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier =
                Modifier
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
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TvText.current.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
