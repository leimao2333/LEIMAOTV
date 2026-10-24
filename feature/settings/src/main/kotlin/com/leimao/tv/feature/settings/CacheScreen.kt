package com.leimao.tv.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.cache.CacheUsage
import com.leimao.tv.core.model.cache.formatCacheSize

/**
 * 存储与缓存（SET-05）。
 *
 * 为什么这个页面值得单独存在：电视盒子普遍只有 8GB 存储，而台标缓存是个只涨不跌的目录 ——
 * 用户换过几次源之后，几百个台标就躺在那里再也不会被打开。给一个能看见数字、能一键清空的入口，
 * 比在文档里写「缓存有上限」有用。
 *
 * 页面只做一件事，所以只有**一个**可聚焦目标（清理按钮）：遥控器进来按一下就能走，
 * 不用在数值行之间挪。数值行是纯文本 —— 能聚焦但点不动的行只会让用户多按几次方向键。
 */
@Composable
fun CacheScreen(
    usage: CacheUsage?,
    clearing: Boolean,
    /** 上一次清理释放的字节数；null 表示还没清过 */
    clearedBytes: Long?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val clearFocus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) clearFocus.requestFocus() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "存储与缓存",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text =
                "台标与海报的缩略图会缓存在本地，删掉不影响播放，只是下次打开要重新加载一遍。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))
        if (usage == null) {
            Text(
                text = "正在计算…",
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            UsageRow(label = "图片缓存（台标 / 海报）", value = usage.imageBytes)
            Spacer(modifier = Modifier.height(10.dp))
            UsageRow(label = "其他临时文件", value = usage.otherBytes)
            Spacer(modifier = Modifier.height(10.dp))
            UsageRow(label = "合计", value = usage.totalBytes, emphasized = true)
        }

        Spacer(modifier = Modifier.height(24.dp))
        FocusableCard(
            onClick = { if (!clearing) onClear() },
            modifier = Modifier.fillMaxWidth().focusRequester(clearFocus),
        ) {
            Column {
                Text(
                    text = "清理缓存",
                    style = TvText.current.label,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        when {
                            clearing -> "正在清理…"
                            // 没有可清的东西时按钮仍然在：焦点总得有个落点，
                            // 让它可以被聚焦但什么也不发生，比「焦点悬空、按返回都回不去」好
                            usage?.isEmpty == true -> "暂无可清理的缓存"
                            else -> "删除本地的台标 / 海报缩略图与临时文件"
                        },
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (clearedBytes != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text =
                    if (clearedBytes > 0L) {
                        "已释放 ${formatCacheSize(clearedBytes)}"
                    } else {
                        "没有可清理的内容"
                    },
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun UsageRow(
    label: String,
    value: Long,
    emphasized: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = if (emphasized) TvText.current.title else TvText.current.body,
            color =
                if (emphasized) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        Text(
            text = formatCacheSize(value),
            style = if (emphasized) TvText.current.title else TvText.current.hint,
            color =
                if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
