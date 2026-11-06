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

    /** 本次起播的开始时刻（elapsedRealtime）。0 = 还没开始缓冲 */
    private var startAtMs = 0L

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        Log.d(
            tag,
            "视频流 ${format.width}x${format.height} ${format.sampleMimeType} " +
                "${format.frameRate.takeIf { it > 0 } ?: "?"}fps " +
                // 码率是「供不上」判据的分母（见 isLineStarved）。容器不声明时这里是 0，
                // 那种情况判据不生效 —— 日志里能一眼看出是哪一种
                "码率 ${if (format.bitrate > 0) "${format.bitrate / 1000} kbps" else "未声明"}",
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
        // 记下这次起播的起点：`onRenderedFirstFrame` 给的 renderTimeMs 是**开机以来的毫秒数**，
        // 直接打出来是「首帧渲染完成 873819ms」这种谁也看不懂的数字（实测就踩到过），
        // 真正有用的是「从开始缓冲到出画等了多久」
        if (state == Player.STATE_BUFFERING && startAtMs == 0L) startAtMs = eventTime.realtimeMs
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
        val waited = if (startAtMs > 0L) (renderTimeMs - startAtMs).coerceAtLeast(0L) else -1L
        if (waited >= 0) {
            Log.d(tag, "首帧渲染完成 等待 ${waited}ms")
        } else {
            // 没有起播基准（暂停后恢复、换轨重渲染）：此时 renderTimeMs 是开机以来的毫秒数，
            // 打出来是「等待 1395540ms」这种纯噪音，不如只说明出了帧
            Log.d(tag, "首帧渲染完成")
        }
        startAtMs = 0L
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

    /** 视频 / 音频各自的声明码率（bps）。分开存是因为**音轨通常比视频轨晚到** */
    private var videoBitrate = 0
    private var audioBitrate = 0

    /** 换台/换集时清零：新的一路流从 0 开始算，否则浮层会挂着上一路的数字 */
    fun reset() {
        bytes = 0L
        videoBitrate = 0
        audioBitrate = 0
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
        // bitrate <= 0 表示容器没声明码率。保留上一次的值没意义（换台已经 reset 过），
        // 直接写 0 让下游走「不知道就不判断」那条路
        videoBitrate = format.bitrate.takeIf { it > 0 } ?: 0
        sink.update {
            it.copy(
                width = format.width,
                height = format.height,
                bitrateBps = videoBitrate + audioBitrate,
            )
        }
    }

    /**
     * 音轨码率也要算进来。
     *
     * 一路 1080p 直播是「视频 10 Mbps + 音频 192 kbps」，只拿视频码率当下限，
     * 判断会偏乐观 2%；更要紧的是**纯音频流**（广播台）没有视频轨，只认视频的话
     * 码率永远是 0，判据直接失效。
     */
    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        audioBitrate = format.bitrate.takeIf { it > 0 } ?: 0
        sink.update { it.copy(bitrateBps = videoBitrate + audioBitrate) }
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
