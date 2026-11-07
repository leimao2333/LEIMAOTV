package com.leimao.tv.core.player

/**
 * 宿主（Activity）前后台切换时该怎么处置播放（2026-11-07 用户反馈：电视不该有后台播放）。
 *
 * 这个 App 没有画中画、后台音频、MediaSession，也没有任何「离开界面还要继续出声」的功能。
 * 播放器又是挂在 Application 容器上的单例（架构设计 §2），Activity `finish()` 之后进程
 * 常常还活着 —— 只结束 Activity，ExoPlayer / libVLC 会继续解码音频，用户以为退出了，
 * 桌面却突然响起声音。
 *
 * 所以规则只有一条：**宿主不在前台，就不许有播放**。
 */
enum class HostPlaybackAction {
    /**
     * 进后台：`stop()` 而不是 `pause()`。
     *
     * `pause()` 只是把 `playWhenReady` 置假，watchdog、统计轮询、直播窗口自愈、下一集
     * 这些异步路径里的 `play()` 会把它重新打开；`stop()` 同时停掉 watchdog 与轮询，
     * 并把状态清回 Idle，后台不可能再有自愈流程把声音拉回来。
     */
    STOP_ALL,

    /**
     * 回前台：什么都不做，等用户自己按 OK / 重新点台。
     *
     * 不能自动 `resume()` —— 用户刚说过「退出后还以为真退了，突然响起声音」，
     * 回到应用时自己响起来同样吓人。电视上「回来是静止的，按一下才继续」才是可预期的。
     */
    NO_AUTO_RESUME,
}

/** 纯函数，便于单测钉住这条规则不被后来的「顺手优化」改掉。 */
internal fun playbackActionOnHostChange(hostForeground: Boolean): HostPlaybackAction =
    if (hostForeground) HostPlaybackAction.NO_AUTO_RESUME else HostPlaybackAction.STOP_ALL
