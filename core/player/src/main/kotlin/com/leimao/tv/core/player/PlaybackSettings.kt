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
 * 内核只认 [BufferProfile.loadControl] 吐出来的 `DefaultLoadControl`，谁也不用去背具体毫秒数。
 * **点播不吃档位**：电影 / 剧集固定走 [BufferProfile.VOD_DURATIONS]（见那里的说明）。
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
 *
 * ## 只管直播，点播不吃这一档（2026-11-04）
 *
 * 档位里的「最大缓冲 30 秒」是被**直播**逼出来的：HLS 直播窗口通常只有 3~6 个分片
 * （18~36 秒），上限再大，内核就会反复刷播放列表去凑一份根本还不存在的数据，反而更卡。
 *
 * 点播没有这个约束 —— playlist 是完整的，想往前缓存多少都行。所以点播走
 * [VOD_BUFFER] 那一组独立参数（见 [loadControl] 的 `forVod`），不再被直播的上限连累。
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
        description = "默认档：攒够 10 秒、最多存 30 秒，抗住常见抖动",
        // 2026-11-02 上调：原来 5s / 20s 在聚合源上太薄 —— 那些线路经常在 20 秒内
        // 抖两三次，缓冲一空就是一次可见的卡顿。10 秒的下限能把大部分抖动吃掉。
        //
        // 2026-11-04 把上限从 60s 收回 30s（卡顿优化）：直播的 HLS 窗口通常只有
        // 3~6 个分片（18~36 秒），上限设到 60 秒，内核会因为「还没填满」而反复刷新
        // 播放列表去凑一份根本不存在的数据；同时 60 秒的 1080p 约 75MB，
        // 在 1GB 内存的盒子上直接换来频繁 GC —— 表现正是「播几秒卡一下」。
        minBufferMs = 10_000,
        maxBufferMs = 30_000,
        bufferForPlaybackMs = 1_000,
        // 卡住之后恢复的门槛要低：源里不少线路是 10 Mbps 的 1080p，
        // 把「恢复前要攒多久」调大，在慢线路上等于把一次卡顿拖成十几秒
        // （实测 2.5s 的门槛 → 每次卡 11 秒，回到 1.5s 才合理）
        bufferAfterRebufferMs = 1_500,
    ),
    ANTI_JITTER(
        label = "抗卡顿",
        description = "多攒缓冲，弱网更稳；换台和起播会慢一些",
        // 弱网专用档：宁可晚两秒出画，也不要在正片中间卡。
        // 上限同样受直播窗口限制，60 秒已经够覆盖「来回拖十秒」的点播场景
        minBufferMs = 20_000,
        maxBufferMs = 60_000,
        bufferForPlaybackMs = 2_000,
        bufferAfterRebufferMs = 3_000,
    ),
    ;

    /**
     * 把档位翻译成 Media3 的缓冲控制（PLAY-03）。
     *
     * [level] 只改一件事：**时间与字节谁优先**。
     *
     * 原来固定 `prioritizeTimeOverSizeThresholds(true)`，意思是「只要时间没攒够就继续下载，
     * 字节超了也不管」。低配机上这条必须反过来 —— 否则 Media3 按自己估的字节上限形同虚设，
     * 4K 码流能把 2GB 机器的进程撑到被系统杀掉（表现是「看着看着退回桌面」）。
     *
     * **不自己设 `targetBufferBytes`**：试过给 24MB，结果播放直接卡死 ——
     * Media3 的分配器是「按整段分片」分配的，一个分片就顶满预算之后就不再加载，
     * 而播放器还在等数据，看门狗 3 秒判一次超时，表现为「BUFFERING → IDLE」无限循环。
     * 让 Media3 按码率自己估，只把「尊重字节上限」这个开关打开，才是安全的做法。
     *
     * [forVod] 为 true 时改用点播专用的四段参数（[VOD_BUFFER]）：不再是直播那套
     * 「最多攒 30 秒就停手」。点播要的是**一直往前走**，网络抖一下手里还有几分钟的货。
     * 单个 ExoPlayer 的 LoadControl 是构造期参数，所以切到点播时内核会重建一次
     * （见 `Media3PlayerController.ensureKernelFor`）。
     */
    fun loadControl(
        level: MemoryLevel = MemoryLevel.NORMAL,
        forVod: Boolean = false,
    ): DefaultLoadControl {
        val d = if (forVod) VOD_DURATIONS else durations()
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                d.minBufferMs,
                d.maxBufferMs,
                d.bufferForPlaybackMs,
                d.bufferAfterRebufferMs,
            )
            // 低配机字节优先：宁可偶尔薄一点，也不能让 4K 码流把进程压垮
            .setPrioritizeTimeOverSizeThresholds(!level.constrained)
            .build()
    }

    /** 这一档换算出来的四段时长。点播不用它，走 [VOD_DURATIONS] */
    internal fun durations(): BufferDurations =
        BufferDurations(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferAfterRebufferMs)

    companion object {
        fun fromName(name: String?): BufferProfile =
            entries.firstOrNull { it.name == name } ?: BALANCED

        /**
         * 点播专用的四段缓冲（2026-11-04，用户反馈「影视一直要缓冲」）。
         *
         * 数值不是拍脑袋：
         *
         * - **最小 30 秒**：低于这个数，一次 5 秒的抽风就能把缓冲抽干。点播可以等，
         *   直播不能 —— 直播的 10 秒下限就是被「换台要快」压下来的。
         * - **最大 180 秒**：这就是用户说的「一直缓存」。内核会一直往前预读，直到攒够三分钟
         *   或撞上字节上限。看剧时来回拖进度、切清晰度重开，命中的部分全在手里。
         *   不设更长的原因：**内存里的 buffer 就是内存**，180 秒 1080p 约 150~200MB，
         *   2GB 盒子上再加就得跟系统抢。三分钟已覆盖「去倒杯水回来接着看」。
         * - **起播攒 3 秒**（原来是 1 秒）：1 秒是「赶紧给我出画」的直播思维，
         *   点播起播慢半秒没人会抱怨，但刚出画就卡一下所有人都会骂。
         * - **卡后攒 5 秒**（原来是 1.5 秒）：恢复门槛太低就会「卡—放半秒—再卡」，
         *   反复抖比一次多等两秒难受得多。
         */
        internal val VOD_DURATIONS: BufferDurations =
            BufferDurations(
                minBufferMs = 30_000,
                maxBufferMs = 180_000,
                bufferForPlaybackMs = 3_000,
                bufferAfterRebufferMs = 5_000,
            )
    }
}

/**
 * 四段缓冲时长的载具。
 *
 * 单独抽出来是因为「点播」不需要一整个 [BufferProfile]：档位是给用户选的（直播三档），
 * 点播只有一套固定参数，拿枚举去 `copy` 也不成立（枚举没有 copy）。
 */
internal data class BufferDurations(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferAfterRebufferMs: Int,
)

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
