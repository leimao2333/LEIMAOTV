package com.leimao.tv.core.player

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 「这条线路到底有没有回过东西」的实测探针（2026-11-04 起播优化）。
 *
 * ## 为什么不能用 AnalyticsListener 判
 *
 * 判死线必须回答「**这次**请求有没有得到响应」。Media3 的事件不给这个粒度：
 * `onLoadCompleted` 只会在一整段数据下完之后回调，而且**上一条线路的迟到事件**
 * 会落到下一条的计时窗口里 —— 实测就是「连续 20 条死线全都判成活的」，
 * 收敛规则永远不触发（见 `Media3PlayerController.handleFailure`）。
 *
 * ## 用响应头当判据
 *
 * [intercept] 里 `chain.proceed()` 返回的那一刻，就是**服务器把响应头送回来**的那一刻。
 * 死线路（连接挂着不回包）永远走不到这一步。这个信号：
 *
 * - 比「字节数增长」早得多 —— 后者要等整个媒体分片下完，6 秒的 1080p 分片要好几秒；
 * - 比 Media3 的任何回调都准 —— 它是这一次请求自己的，不掺别的线路。
 *
 * 只读时间戳，不碰响应实体，也不改请求。
 *
 * 时钟做成可注入：判定逻辑全在时间戳比较上，而 `SystemClock` 在纯 JVM 单测里是桩，
 * 注入之后这套语义才测得了（见 `PlaybackNetworkMonitorTest`）。
 */
internal class PlaybackNetworkMonitor(
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : Interceptor {
    /** 最近一次「收到响应头」的时刻（elapsedRealtime）。0 = 从未收到 */
    @Volatile
    private var lastResponseAt = 0L

    /** 新线路开始计时时调用：把之前的记录清掉，只认这之后发生的响应 */
    fun reset() {
        lastResponseAt = 0L
    }

    /** 从 [sinceElapsed] 之后，有没有任何一次请求收到过响应头 */
    fun respondedSince(sinceElapsed: Long): Boolean = lastResponseAt >= sinceElapsed && lastResponseAt > 0L

    /**
     * 记一次响应。**只认成功响应**：源失效时服务器往往很快回一个 404 / 403 ——
     * 响应头确实到了，但那是「这个地址不存在」，不是「这条线路能用」。把它算成活的，
     * 判死逻辑就会退化成走满出画预算再重试三次，用户又回到转圈里。
     */
    fun markResponse(successful: Boolean) {
        if (successful) {
            lastResponseAt = now()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        markResponse(response.isSuccessful)
        return response
    }
}
