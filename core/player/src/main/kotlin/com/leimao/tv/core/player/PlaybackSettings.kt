package com.leimao.tv.core.player

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * 播放设置（SET-01）。
 *
 * 只放「真正会改播放行为」的三样，且每一项都落到可持久化的值上：
 *
 * - [bufferProfile]：直播缓冲档位，映射到 Media3 的 [DefaultLoadControl] 四段缓冲时长。
 * - [seekIncrementMs]：快进步长，供点播（VOD）用；直播不涉及快进。
 * - 默认内核：M1 只有 Media3 一个实现（VLC 是 D-02 决策、+20MB native 依赖，未引入），
 *   故内核不做可选项，只在此注明。
 *
 * 档位是「用户能懂的词」与「内核能懂的参数」之间的翻译层：UI 只认 [BufferProfile]，
 * 内核只认 `BufferProfile.durationsMs`，谁也不用去背具体毫秒数。
 */
data class PlaybackSettings(
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    val seekIncrementMs: Long = SeekStep.SECONDS_30.millis,
    /** 记住的播放速度（PLAY-05）。1.0 = 原速；只对点播生效 */
    val playbackSpeed: Float = 1.0f,
) {
    companion object {
        val DEFAULT = PlaybackSettings()
    }
}

/** 点播倍速档位（PLAY-05）。档位是离散的：连续调速在遥控器上没有操作方式。 */
enum class PlaybackSpeed(
    val label: String,
    val value: Float,
) {
    S_0_75("0.75×", 0.75f),
    S_1_0("1.0×", 1.0f),
    S_1_25("1.25×", 1.25f),
    S_1_5("1.5×", 1.5f),
    S_2_0("2.0×", 2.0f),
    S_3_0("3.0×", 3.0f),
    ;

    companion object {
        fun fromValue(value: Float): PlaybackSpeed =
            entries.firstOrNull { it.value == value } ?: S_1_0

        fun fromName(name: String?): PlaybackSpeed =
            entries.firstOrNull { it.name == name } ?: S_1_0
    }
}

/**
 * 直播缓冲档位（PLAY-03 的可配化）。
 *
 * 三档分别服务三种诉求：低延迟给「追实时」，抗卡顿给「网络差」，均衡是默认。
 * 四个时长依次是：最小缓冲 / 最大缓冲 / 起播前攒够 / 卡顿后重新起播。
 */
enum class BufferProfile(
    val label: String,
    val description: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferAfterRebufferMs: Int,
) {
    LOW_LATENCY(
        label = "低延迟",
        description = "追实时，换台最快；网络稍差容易卡",
        minBufferMs = 3_000,
        maxBufferMs = 12_000,
        bufferForPlaybackMs = 300,
        bufferAfterRebufferMs = 800,
    ),
    BALANCED(
        label = "均衡",
        description = "起播快、抗轻微抖动，默认档",
        minBufferMs = 5_000,
        maxBufferMs = 20_000,
        bufferForPlaybackMs = 500,
        bufferAfterRebufferMs = 1_000,
    ),
    ANTI_JITTER(
        label = "抗卡顿",
        description = "多攒缓冲，弱网更稳；换台稍慢",
        minBufferMs = 10_000,
        maxBufferMs = 30_000,
        bufferForPlaybackMs = 1_000,
        bufferAfterRebufferMs = 2_000,
    ),
    ;

    /** 把档位翻译成 Media3 的缓冲控制（PLAY-03）。 */
    fun loadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    companion object {
        fun fromName(name: String?): BufferProfile =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

/** 快进步长（点播用）。档位用「秒数」表达，比毫秒更贴近用户在遥控器上按一下的预期。 */
enum class SeekStep(
    val label: String,
    val seconds: Long,
) {
    SECONDS_10(label = "10 秒", seconds = 10),
    SECONDS_30(label = "30 秒", seconds = 30),
    SECONDS_60(label = "60 秒", seconds = 60),
    ;

    val millis: Long get() = seconds * 1_000L

    companion object {
        fun fromMillis(millis: Long): SeekStep =
            entries.firstOrNull { it.millis == millis } ?: SECONDS_30
    }
}
