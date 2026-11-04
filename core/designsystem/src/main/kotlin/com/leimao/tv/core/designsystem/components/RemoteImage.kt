package com.leimao.tv.core.designsystem.components

import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
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
 *
 * ## 必须按目标尺寸解码（2026-11-04，低配机防闪退）
 *
 * 原来 `model = url` 是裸字符串，Coil 拿不到任何尺寸提示，**按图片原始像素解码**。
 * 源里的封面经常是 500×750 甚至更大的规格，一张 ARGB_8888 就是 1.5MB 以上；
 * 一屏 24 张滚动两屏，光图片就能吃掉 70MB —— 在 2GB 内存的盒子上（单进程堆上限
 * 通常只有 192MB），这是最直接的 OOM 来源。台标同理：4K 分辨率下 36dp 的台标
 * 按原图解码，比 1080p 又大四倍。
 *
 * 把目标像素尺寸交给 Coil 之后，解码阶段就按 `inSampleSize` 降采样，
 * 进内存的位图与「画在屏幕上要多大」严格对齐。同一个 `url` 在不同尺寸上
 * 要分别解码，由 Coil 的 keyer 负责区分，不会互相串用。
 */
@Composable
fun RemoteImage(
    url: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackTextStyle: TextStyle = TvText.current.display,
) {
    var failed by remember(url) { mutableStateOf(false) }
    val showFallback = url.isNullOrBlank() || failed
    val context = LocalContext.current

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // 只在约束有界时给尺寸：给无限高的容器（比如高度由内容撑开的行）传尺寸，
        // Coil 会把它当成一个巨大的目标尺寸，比不传更糟
        val targetWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else -1
        val targetHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else -1

        if (showFallback) {
            Text(
                text = fallbackText.trim().take(1).ifEmpty { "?" },
                style = fallbackTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // 小尺寸位（36dp 台标槽）里 display 字号的字会顶出边界，压成一行并截断
                maxLines = 1,
                modifier = Modifier.padding(4.dp),
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model =
                    ImageRequest.Builder(context)
                        .data(url)
                        .apply {
                            when {
                                targetWidth > 0 && targetHeight > 0 -> size(targetWidth, targetHeight)
                                // 无界约束下也不能退化成 Size.ORIGINAL（=按原图解码）。
                                // 给一个上限兜底：Coil 只会等比缩小，绝不会放大，所以
                                // 「上限给小了」最坏是画质略降，而「不给」是直接 OOM
                                else -> size(UNBOUNDED_DECODE_CAP_PX, UNBOUNDED_DECODE_CAP_PX)
                            }
                        }
                        .build(),
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

/**
 * 约束无界时的解码上限（像素）。
 *
 * 1024 不是拍的：电视端「宽度或高度无界」的容器基本都是行内小图（列表里的缩略图、
 * 详情页简介旁的小海报），显示尺寸远小于这个值；而一张 ARGB_8888 的 1024×1024
 * 位图是 4MB，仍在低配机可承受范围内。
 */
private const val UNBOUNDED_DECODE_CAP_PX = 1024
