package com.leimao.tv.feature.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.layout.SafeAreaContainer
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.player.PlaybackState

/** 起播失败 / 无频道时的两个出口（RULES §6.6 实现约束 3：停在播放页给明确出口，不回主页） */
private enum class PlayerExit { SOURCE_MANAGER, RETRY }

/**
 * 直播播放页（M0 占位）。
 *
 * 占位的是**画面**，不是行为：这一版已经把「载入中 / 无频道 / 起播失败」三种状态和
 * 对应出口都画出来了，M1 接上真实解码后只是把中间那块换成 PlayerView。
 *
 * 播放器实例不在这里 —— 它挂在应用层，所以切模式、回主页都不会打断播放（架构设计 §2）。
 */
@Composable
fun PlayerScreen(
    channelTitle: String?,
    loadingChannels: Boolean,
    playback: PlaybackState,
    onOpenSourceManager: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasNothingToPlay = channelTitle == null && !loadingChannels
    val firstExitFocus = remember { FocusRequester() }

    // 每个页面必须显式指定初始焦点，不依赖默认值（RULES §6.2 第 4 条）
    LaunchedEffect(hasNothingToPlay) {
        if (hasNothingToPlay) firstExitFocus.requestFocus()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF101820), Color(0xFF05080A))),
                ),
    ) {
        SafeAreaContainer {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = channelTitle ?: if (loadingChannels) "正在载入频道" else "暂无可播放的频道",
                        style = TvText.current.title,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (loadingChannels) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            // §6.6 降级 3：最多等 10s，超时给重试 / 去源管理
                            text = "正在载入频道…（10 秒内没好会给重试入口）",
                            style = TvText.current.hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "直播播放页（占位）",
                        style = TvText.current.display,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = describe(playback, loadingChannels),
                        style = TvText.current.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    if (hasNothingToPlay) {
                        Spacer(modifier = Modifier.height(28.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            FocusableCard(
                                onClick = onOpenSourceManager,
                                modifier = Modifier.focusRequester(firstExitFocus),
                            ) {
                                Text(text = "去源管理", style = TvText.current.label)
                            }
                            FocusableCard(onClick = onRetry) {
                                Text(text = "重试", style = TvText.current.label)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    // 按键映射固定，不允许各页自定义（RULES §6.5）
                    text = "↑↓ 换台　数字键 输入频道号　←→ 切换线路　OK 控制条　返回 主页",
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun describe(playback: PlaybackState, loadingChannels: Boolean): String =
    when {
        loadingChannels -> "有订阅源，但频道还没解析出来"
        playback is PlaybackState.Playing -> "已起播（M0 占位内核不解码，M1 接 Media3 后出真实画面）"
        playback is PlaybackState.Preparing -> "正在起播…"
        playback is PlaybackState.Failed -> "起播失败：${playback.reason}"
        else -> "等待频道"
    }
