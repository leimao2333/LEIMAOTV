package com.leimao.tv.core.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * 播放诊断日志（PLAY-01 / PLAY-02 的排查入口）。
 *
 * 用户说「卡」时，第一个要回答的问题永远是两句：**硬解起了没有**、**掉了多少帧**。
 * 这两个答案 ExoPlayer 只在 [AnalyticsListener] 里给，界面上看不到 —— 没有这行日志，
 * 「卡」就只能靠猜（是解码跟不上、还是源在抖、还是渲染掉了）。
 *
 * 判据：解码器名里带 `software` / `c2.android.` / `goldfish` / `OMX.google.` 的都是软解；
 * 其余（`c2.qcom.` / `c2.mtk.` / `OMX.amlogic.` 之类）是厂商硬解。**模拟器只有 goldfish**，
 * 所以模拟器上永远是软解 —— 这是模拟器的属性，不是 App 没开硬解。
 *
 * 用 `Log.d`：release 构建会通过 R8 的 `-assumenosideeffects` 整条裁掉（见 app/proguard-rules.pro），
 * 所以常开也不会有生产日志噪音。
 */
internal class PlaybackDiagnostics(
    private val tag: String = TAG,
) : AnalyticsListener {
    private var decoder: String? = null

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        Log.d(
            tag,
            "视频流 ${format.width}x${format.height} ${format.sampleMimeType} " +
                "${format.frameRate.takeIf { it > 0 } ?: "?"}fps",
        )
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        decoder = decoderName
        Log.d(
            tag,
            "解码器 $decoderName（${if (isSoftware(decoderName)) "软解" else "硬解"}）" +
                " 初始化 ${initializationDurationMs}ms",
        )
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        Log.d(tag, "丢帧 $droppedFrames 帧（窗口 ${elapsedMs}ms，解码器 ${decoder ?: "未初始化"}）")
    }

    /**
     * 缓冲状态：**卡顿是解码跟不上还是源在抖，看这一行就能分**。
     *
     * 一直 READY 却看着卡 → 解码/渲染；反复 BUFFERING → 网络或上游不稳。
     * 少了这条，只能凭「感觉卡」猜原因。
     */
    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int,
    ) {
        val name =
            when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> state.toString()
            }
        Log.d(tag, "播放状态 $name")
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        Log.d(tag, "首帧渲染完成 ${renderTimeMs}ms")
    }

    internal companion object {
        const val TAG = "LeimaoPlay"

        /** 名字里这些片段 = 系统软解。判据宁可漏判成「硬解」也不要反着来：说错了方向更误导 */
        private val SOFTWARE_MARKERS = listOf("software", "c2.android.", "goldfish", "OMX.google.")

        fun isSoftware(decoderName: String): Boolean =
            SOFTWARE_MARKERS.any { decoderName.contains(it, ignoreCase = true) }
    }
}
