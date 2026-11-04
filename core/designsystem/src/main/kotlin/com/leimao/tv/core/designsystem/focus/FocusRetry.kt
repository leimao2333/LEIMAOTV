package com.leimao.tv.core.designsystem.focus

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * 「等节点真的能接焦点之后再请求」。
 *
 * ## 为什么不能只等一帧
 *
 * 浮层 / 控制条是条件组合出来的：`if (visible) { ... }`。它在**同一帧**里完成组合，
 * 但测量与挂载要到这一帧的布局阶段。`withFrameNanos {}` 只等到「下一帧开始」，
 * 这时节点往往还没有 attach，`FocusRequester.requestFocus()` 会抛
 * `IllegalStateException: FocusRequester is not initialized`。
 *
 * 之前四处调用点都是这个写法，而且都套了 `runCatching` 把异常吞掉 —— 于是失败完全没有痕迹，
 * 用户看到的是「按 OK 弹出了控制条，方向键一动不动，OK 也点不了任何按钮」
 * （2026-11-04 反馈）。高配机一帧内就布局完，问题不显；低配盒子（A55 四小核）经常要两帧。
 *
 * ## 为什么是重试而不是固定等两帧
 *
 * 两帧只是「大多数情况够」，不是保证：浮层里带图片解码、带 `AnimatedVisibility`、
 * 或者这一帧正好在 GC，都可能更晚。用**成功与否**驱动重试，成本最低也最可靠 ——
 * 一旦真的拿到焦点就立刻返回，不会为了等一个固定延时白白让焦点在空中悬着。
 *
 * 失败时返回 false 而不是抛异常：焦点没落位是体验问题，不该把整页拖崩。
 * 调用方可以忽略返回值（绝大多数场景），也可以在诊断里记一笔。
 */
suspend fun FocusRequester.requestFocusWhenReady(
    maxFrames: Int = FOCUS_RETRY_FRAMES,
): Boolean {
    repeat(maxFrames) {
        withFrameNanos { }
        // requestFocus() 成功即代表节点已 attach 且真正拿走了焦点（同步生效）。
        // 抛异常只有一种原因：还没 attach —— 那就再等一帧
        if (runCatching { requestFocus() }.isSuccess) return true
    }
    return false
}

/**
 * 最多等多少帧。
 *
 * 30 帧 ≈ 半秒（60Hz）到一秒（30Hz 的廉价盒子）。比这更晚还没挂上，说明这一层根本没被组合
 * （比如条件判断写在别处），继续等也没有意义。
 */
const val FOCUS_RETRY_FRAMES: Int = 30
