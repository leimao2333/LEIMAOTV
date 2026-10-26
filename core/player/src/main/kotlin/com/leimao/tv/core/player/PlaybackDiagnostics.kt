package com.leimao.tv.core.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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

/**
 * 从 AnalyticsListener 里攒出「加载了多少字节、什么分辨率、哪个解码器」（PLAY-12）。
 *
 * 与 [PlaybackDiagnostics] 分开而不是塞进去：那个只负责**打日志**（release 会被 R8 整条裁掉），
 * 这个负责**喂 UI**，两者生命周期不同 —— 混在一起的话，裁日志时容易顺手把 UI 数据也裁掉。
 */
internal class LoadStatsTracker(
    private val sink: MutableStateFlow<LoadStats>,
) : AnalyticsListener {
    private var bytes = 0L

    /** 换台/换集时清零：新的一路流从 0 开始算，否则浮层会挂着上一路的数字 */
    fun reset() {
        bytes = 0L
        sink.value = LoadStats.None
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        // 只累加媒体数据本身：清单/字幕也走这个回调，把它们算进去会让「已加载」虚高
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
        bytes += loadEventInfo.bytesLoaded
        sink.update { it.copy(bytesLoaded = bytes) }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        sink.update { it.copy(width = format.width, height = format.height) }
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        sink.update { it.copy(decoder = decoderName) }
    }
}
