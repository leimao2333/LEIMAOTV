package com.leimao.tv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.components.TvTextField
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 「从地址导入源备份」对话框（SET-10）。
 *
 * 与「导入备份」（选本地文件）的区别只在数据从哪来：电视上没有好用的文件管理器，
 * 而别人给的清单往往就是一个 URL。填地址 → 抓 JSON → 走同一条导入链路。
 */
@Composable
fun ImportBackupDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    // 只用于「地址没填时把焦点送回输入框」；初始焦点靠布局顺序（输入框是第一个可聚焦元素）
    val urlFieldFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .width(720.dp)
                    .fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
        ) {
            Text(
                text = "从地址导入源备份",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "填一份源备份 JSON 的 http/https 地址。已存在同地址的源会被跳过，导入后自动刷新新增的直播源。",
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
                    label = "备份地址（http:// 或 https:// 开头）",
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
                    Text(text = "导入并刷新", style = TvText.current.label)
                }
                FocusableCard(onClick = onDismiss) {
                    Text(text = "取消", style = TvText.current.label)
                }
            }
        }
    }
}
