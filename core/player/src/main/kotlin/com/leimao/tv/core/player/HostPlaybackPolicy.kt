package com.leimao.tv.core.player

/**
 * 宿主（Activity）前后台切换时该怎么处置播放。
 *
 * 2026-11-07 第一次反馈「退出后后台还响」，当时的方案是编排器进后台 `stop()`：
 * 停两个内核、停 watchdog、拒绝后台起播。2026-11-16 用户二次反馈「直播退出还是会在
 * 后台响，直接关机它还响」，并明确要求「只要这个软件到后台就直接杀死，不是保留」。
 *
 * 只 `stop()` 不够，三条漏点：
 * 1. 播放器是 Application 级单例，`finish()` 之后进程还活着；libVLC 的音频线程在
 *    `stop()` 之后不一定立刻收声，native 层还可能把缓冲里的最后几帧吐完。
 * 2. 异步路径能绕过闸门：编排器 `stop()` 时没清 `currentRequest`，primary 迟到一拍的
 *    `Failed` 会在 `onPrimaryState` 里直接 `soft.play(currentRequest!!)` 把兜底内核
 *    在后台拉起来 —— 这条路径根本没走带闸门的 `play()`。
 * 3. 电视按电源键「关机」多数只是待机：系统只发 `ACTION_SCREEN_OFF`，Activity 可能
 *    连 `onPause` 都收不到，生命周期那套钩子完全没机会跑。
 *
 * 所以规则升级为：**宿主不在前台，整个进程终止，不保留任何后台**。
 * 两层各做各的那一份，缺一不可：
 * - 编排器（:core:player）：`stop()` 并拒绝后台所有起播 / 自愈，负责「杀进程之前那一瞬
 *   也不许出声」；
 * - 宿主（:app）：`release()` 两个内核 → `finishAndRemoveTask()` → `killProcess()`，
 *   负责「进程真的没了」。
 */
enum class HostPlaybackAction {
    /** 进后台：编排器停流 + 宿主终止进程。见文件头注释。 */
    TERMINATE_PROCESS,

    /**
     * 回前台：什么都不做，等用户自己按 OK / 重新点台。
     *
     * 不能自动 `resume()` —— 用户刚说过「退出后还以为真退了，突然响起声音」，
     * 回到应用时自己响起来同样吓人。电视上「回来是静止的，按一下才继续」才是可预期的。
     */
    NO_AUTO_RESUME,
}

/** 纯函数，便于单测钉住这条规则不被后来的「顺手优化」改掉。 */
fun playbackActionOnHostChange(hostForeground: Boolean): HostPlaybackAction =
    if (hostForeground) HostPlaybackAction.NO_AUTO_RESUME else HostPlaybackAction.TERMINATE_PROCESS
