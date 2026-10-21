package com.leimao.tv.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * M0 的占位播放器：**不解码任何内容**，只把状态机跑起来。
 *
 * 存在的意义是让「启动直达播放页 → 返回主页 → 切模式 → 回来接着播」这条链路
 * 在还没有解码器的阶段就能被走通、被验收（M0 验收项）。M1 接 Media3 时，
 * 换成 `Media3PlayerController` 即可，上层不变。
 *
 * 不打印任何 URL 之外的凭据信息，也不在 release 打日志（RULES §2.5、§8.6）。
 */
class StubPlayerController : PlayerController {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun play(url: String, headers: Map<String, String>, startMs: Long) {
        // 占位实现没有缓冲过程，直接进入 Playing；真实内核会停在 Preparing 直到首帧
        _state.value = PlaybackState.Preparing(url)
        _state.value = PlaybackState.Playing(url = url, positionMs = startMs)
    }

    override fun stop() {
        _state.value = PlaybackState.Idle
    }

    override fun release() {
        _state.value = PlaybackState.Idle
    }
}
