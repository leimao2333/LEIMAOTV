package com.leimao.tv.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 不解码任何内容的占位内核。
 *
 * 保留它有两个用处：契约测试（内核切换接口的行为基线）与「还没接内核」时的降级。
 * 真实内核见 [Media3PlayerController]。
 *
 * 不打印任何地址或凭据，也不在 release 打日志（RULES §2.5、§8.6）。
 */
class StubPlayerController : PlayerController {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var current: PlaybackRequest? = null
    private var lineIndex = 0

    override fun play(request: PlaybackRequest) {
        current = request
        lineIndex = 0
        emitPreparingThenPlaying(request)
    }

    override fun retry() {
        current?.let { emitPreparingThenPlaying(it) }
    }

    override fun switchLine(delta: Int): Boolean {
        val request = current ?: return false
        val lines = request.normalizedLines
        if (lines.size <= 1) return false
        lineIndex = (lineIndex + delta).coerceIn(0, lines.lastIndex)
        emitPreparingThenPlaying(request)
        return true
    }

    override fun stop() {
        _state.value = PlaybackState.Idle
    }

    override fun release() {
        current = null
        _state.value = PlaybackState.Idle
    }

    private fun emitPreparingThenPlaying(request: PlaybackRequest) {
        val lines = request.normalizedLines
        val url = lines[lineIndex.coerceIn(0, lines.lastIndex)]
        // 占位实现没有缓冲过程，直接进入 Playing；真实内核会停在 Preparing 直到首帧
        _state.value =
            PlaybackState.Preparing(
                url = url,
                channelId = request.channelId,
                lineIndex = lineIndex,
                lineCount = lines.size,
            )
        _state.value =
            PlaybackState.Playing(
                url = url,
                channelId = request.channelId,
                lineIndex = lineIndex,
                lineCount = lines.size,
            )
    }
}
