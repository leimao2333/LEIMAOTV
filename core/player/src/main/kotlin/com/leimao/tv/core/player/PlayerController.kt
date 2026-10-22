package com.leimao.tv.core.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * 一次起播请求。
 *
 * 带上**全部线路**而不是单个地址：播放失败时「换下一条线路」是内核自己的职责
 * （PLAY-02 的降级链），让 UI 去拼下一轮的地址，等于把降级逻辑拆散在两层。
 */
data class PlaybackRequest(
    val channelId: Long,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** 至少一条；第一条通常等于 [url] */
    val lines: List<String> = listOf(url),
) {
    val normalizedLines: List<String> get() = if (lines.isEmpty()) listOf(url) else lines
}

/** 播放状态机。UI 只读它，不直接碰内核（架构设计 §6）。 */
sealed interface PlaybackState {
    data object Idle : PlaybackState

    data class Preparing(
        val url: String,
        val channelId: Long,
        val lineIndex: Int,
        val lineCount: Int,
    ) : PlaybackState

    data class Playing(
        val url: String,
        val channelId: Long,
        val lineIndex: Int,
        val lineCount: Int,
    ) : PlaybackState

    /**
     * 失败必须带出口信息：降级链走完仍失败时，页面要给「换线路 / 换台 / 去源管理」三个出口
     * （RULES §6.6 实现约束 3：不允许静默退回主页）。
     */
    data class Failed(
        val url: String,
        val channelId: Long,
        val reason: String,
        val lineIndex: Int,
        val lineCount: Int,
    ) : PlaybackState {
        val canSwitchLine: Boolean get() = lineCount > 1
    }
}

/**
 * 播放内核的唯一接口（架构设计 §6）。
 *
 * 默认实现是 Media3（PLAY-01）；换内核（比如以后接 VLC）只换实现，UI 与 ViewModel 一行都不改
 * —— 这是本项目最重要的解耦点之一。
 */
interface PlayerController {
    val state: StateFlow<PlaybackState>

    fun play(request: PlaybackRequest)

    /** 同一条线路重试（错误恢复入口，PLAY-08） */
    fun retry()

    /** 切换到相邻线路；[delta] 为 +1 / -1。返回是否切换成功（只有一条线路时返回 false） */
    fun switchLine(delta: Int): Boolean

    fun stop()

    fun release()
}

/**
 * 「这块内核能把播放画面挂到 View 上」的标记接口。
 *
 * 为什么单独一个接口：`PlayerController` 要保持内核无关，不能出现 Media3 的 `Player` 类型；
 * 而 PlayerView 偏偏需要它。用标记接口隔离，UI 侧只在自己支持的情况下拿画面。
 */
interface VideoSurfaceHost {
    val videoPlayer: Player
}
