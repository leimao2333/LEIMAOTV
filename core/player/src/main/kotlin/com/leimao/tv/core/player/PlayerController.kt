package com.leimao.tv.core.player

import kotlinx.coroutines.flow.StateFlow

/** 播放状态机。UI 只读它，不直接碰内核（架构设计 §6）。 */
sealed interface PlaybackState {
    data object Idle : PlaybackState

    data class Preparing(val url: String) : PlaybackState

    data class Playing(val url: String, val positionMs: Long) : PlaybackState

    /**
     * 失败必须带出口信息：降级链走完仍失败时，页面要给「换线路 / 去源管理」两个明确按钮
     * （RULES §6.6 实现约束 3：不允许静默退回主页）。
     */
    data class Failed(val url: String, val reason: String) : PlaybackState
}

/**
 * 播放内核的唯一接口（架构设计 §6）。
 *
 * 默认实现是 Media3（M1 的 PLAY-01），软解兜底是 VLC（D-02 已定）；
 * 换内核只换实现，UI 与 ViewModel 一行都不改 —— 这是本项目最重要的解耦点之一。
 */
interface PlayerController {
    val state: StateFlow<PlaybackState>

    fun play(url: String, headers: Map<String, String> = emptyMap(), startMs: Long = 0L)

    fun stop()

    fun release()
}
