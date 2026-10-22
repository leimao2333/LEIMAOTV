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
import androidx.compose.runtime.LaunchedEffect
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
    val urlFieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { urlFieldFocus.requestFocus() }

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
}
