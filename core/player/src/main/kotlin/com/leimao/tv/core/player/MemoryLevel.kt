package com.leimao.tv.core.player

import android.app.ActivityManager
import android.content.Context

/**
 * 设备内存档位（2026-11-04，低配机防闪退）。
 *
 * 目标机器是「4K 面板 + Cortex-A55×4 + Mali-G31 MP2 + 2GB 内存」这一类入门电视盒子。
 * 拿 1080p 中端盒子的参数直接跑，最典型的崩溃就是**播放中被系统杀掉**：
 * 4K 码流的缓冲按字节算比 1080p 大三四倍，而 2GB 机器给单个应用的堆上限往往只有 192MB。
 *
 * 判据用两条，取更保守的那个：
 *
 * - `ActivityManager.isLowRamDevice`：系统自己标记的低内存设备（厂商按总内存配的开关），
 *   这是最贴近实际的一条 —— 2GB 的盒子基本都是 true。
 * - `ActivityManager.memoryClass`：系统建议的单进程堆上限（MB）。
 *   `isLowRamDevice` 偶尔被厂商写死成 false（廉价盒子很常见），这条兜住。
 *
 * 不用 `Runtime.maxMemory()` 当判据：它在部分 ART 实现上会给一个远大于
 * `memoryClass` 的数字（堆可以涨，涨到就被杀），拿它算档位会偏乐观。
 *
 * 档位只影响**内存相关的参数**，不影响功能：低配机器一样能看 4K，
 * 只是缓冲得更薄、图片缓存更小。把画质或功能砍掉是另一种做法，本项目不采用。
 */
enum class MemoryLevel(
    /** 诊断日志与设置页里给人看的说法 */
    val label: String,
) {
    /** 2GB 及以下：缓冲按字节收紧，图片缓存减半 */
    LOW("低内存"),

    /** 3~4GB：介于两者之间 */
    MID("中等内存"),

    /** 6GB 以上：按原设计跑 */
    NORMAL("常规"),
    ;

    /** 是不是需要收紧内存参数 */
    val constrained: Boolean get() = this != NORMAL

    companion object {
        /** 堆上限低于这个值就按低内存对待。2GB 盒子普遍上报 192 */
        private const val LOW_HEAP_MB = 256

        /** 3~4GB 盒子常见 256~384 */
        private const val MID_HEAP_MB = 512

        fun of(context: Context): MemoryLevel {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (manager == null) return NORMAL

            // API 19 起才有 isLowRamDevice，本项目 minSdk 21，直接调用即可
            if (manager.isLowRamDevice) return LOW

            val heapMb = manager.memoryClass
            return when {
                heapMb <= LOW_HEAP_MB -> LOW
                heapMb <= MID_HEAP_MB -> MID
                else -> NORMAL
            }
        }

        /**
         * 仅用于诊断日志：把「这台机器被判成什么档、依据是什么」打出来。
         *
         * 用户报「低配盒子闪退」时，第一件事就是确认应用到底有没有认出这是低配机 ——
         * 判错了的话下面所有收紧参数都不会生效，而现象和没改过一模一样。
         */
        fun describe(context: Context): String {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            runCatching { manager?.getMemoryInfo(info) }
            val totalMb = info.totalMem / (1024L * 1024L)
            return "${of(context).label}（isLowRamDevice=${manager?.isLowRamDevice}, " +
                "memoryClass=${manager?.memoryClass}MB, 总内存约 ${totalMb}MB）"
        }
    }
}
