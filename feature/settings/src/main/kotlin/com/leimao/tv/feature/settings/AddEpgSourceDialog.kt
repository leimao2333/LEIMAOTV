package com.leimao.tv.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.components.TvModal
import com.leimao.tv.core.designsystem.components.TvTextField
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import kotlinx.coroutines.delay

/**
 * 「添加节目单订阅地址」对话框（LIVE-23）。
 *
 * 与 [AddSourceDialog] 的区别：节目单只有一个 URL，没有名称、UA 这些字段。
 * 填地址 → 立即抓取刷新一次，并把这个地址登记为定时刷新的目标。
 */
@Composable
fun AddEpgSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    // 只用于「地址没填时把焦点送回输入框」；初始焦点靠布局顺序（输入框是第一个可聚焦元素）
    val urlFieldFocus = remember { FocusRequester() }

    // 弹层打开时底层内容整棵被移除，焦点系统要先把焦点「搬」到新树里；
    // 这个搬迁完成前请求的焦点会被覆盖掉，第一次按键则被用来完成搬迁 ——
    // 表现就是「打开后第一次按 OK 没反应」。给一小段延时让搬迁落定（实测一帧不够）
    LaunchedEffect(Unit) {
        delay(120)
        urlFieldFocus.requestFocus()
    }

    // 树内弹层（SET-12）：不用 Dialog 窗口 —— 窗口聚焦会吃掉打开后的第一次按键
    TvModal {
        Text(
            text = "添加节目单订阅地址",
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text =
                "支持 XMLTV 格式（.xml 或 .xml.gz 对应的 http/https 地址）。" +
                    "登记后每天自动刷新一次。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            TvTextField(
                value = url,
                onValueChange = { url = it },
                label = "节目单地址（http:// 或 https:// 开头）",
                modifier = Modifier.fillMaxWidth().focusRequester(urlFieldFocus),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableCard(
                onClick = {
                    if (url.isBlank()) urlFieldFocus.requestFocus() else onConfirm(url.trim())
                },
            ) {
                Text(text = "添加并刷新", style = TvText.current.label)
            }
            FocusableCard(onClick = onDismiss) {
                Text(text = "取消", style = TvText.current.label)
            }
        }
    }
}
