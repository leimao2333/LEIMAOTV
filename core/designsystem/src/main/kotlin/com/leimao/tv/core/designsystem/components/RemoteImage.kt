package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 海报 / 台标这类「远程小图」的统一入口。
 *
 * 为什么不让各页面自己写 `AsyncImage`：**加载失败时必须有东西**。原来的写法失败后
 * 只剩一块灰底 —— 用户看到的是「这 App 没图片」，而不是「这张封面拉不到」。
 * 影视源的图床质量参差（有的整站只对中国大陆 IP 开放、有的防盗链、有的干脆挂了），
 * 失败是常态，占位必须和「源里没给封面」长得一样：片名首字。
 *
 * 首字垫在图片**下面**而不是用 `error` 参数：图片成功时它会盖住首字，
 * 失败时 Coil 什么都不画，首字自然露出来 —— 少一次 recomposition，也少一个分支。
 */
@Composable
fun RemoteImage(
    url: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var failed by remember(url) { mutableStateOf(false) }
    val showFallback = url.isNullOrBlank() || failed

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (showFallback) {
            Text(
                text = fallbackText.trim().take(1).ifEmpty { "?" },
                style = TvText.current.display,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(4.dp),
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) failed = true
                },
            )
        }
    }
}
