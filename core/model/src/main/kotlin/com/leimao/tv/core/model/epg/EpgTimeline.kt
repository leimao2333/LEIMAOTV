package com.leimao.tv.core.model.epg

/**
 * 节目单网格里一块节目的摆放位置（LIVE-22）。
 *
 * 单位统一用**分钟**而不是像素：[buildEpgTimeline] 不知道 dpi、字号、全局缩放，
 * 这些只有 UI 层知道。让它算像素的后果是「改了字号，网格就错位」，
 * 而且这类错误在单测里根本断言不了。
 */
data class EpgBlock(
    val programme: EpgProgramme,
    /** 相对窗口起点的开始分钟。跨到窗口左边界的节目会被夹到 0 */
    val startMinute: Int,
    /** **可见**时长（分钟）。跨边界的节目会被截短，不是原始时长 */
    val durationMinutes: Int,
)

/**
 * 一个时间窗内的时间轴布局。
 *
 * [tickMinutes] 是刻度相对窗口起点的分钟数，固定 [SLOT_MINUTES] 一格 ——
 * 刻度必须跟着窗口起点走，否则「窗口平移半格」时刻度会和整点对不上。
 */
data class EpgTimeline(
    val windowStart: Long,
    val windowEnd: Long,
    val blocks: List<EpgBlock>,
    val tickMinutes: List<Int>,
) {
    val windowMinutes: Int get() = ((windowEnd - windowStart) / MINUTE_MILLIS).toInt()

    val isEmpty: Boolean get() = blocks.isEmpty()

    /**
     * 当前时间在窗口里的位置（分钟）。
     *
     * 窗口外返回 null —— 让调用方决定「不画竖线」，而不是把它夹到边界上。
     * 夹到边界会在屏幕最左/最右立一根假竖线，看起来像「现在就在这一刻」，是错的。
     */
    fun nowOffsetMinutes(nowMillis: Long): Int? {
        if (nowMillis < windowStart || nowMillis > windowEnd) return null
        return ((nowMillis - windowStart) / MINUTE_MILLIS).toInt()
    }

    /** 当前正在播的那块（用于进场时把焦点/滚动对准它） */
    fun blockAt(nowMillis: Long): EpgBlock? = blocks.firstOrNull { it.programme.isLiveAt(nowMillis) }
}

/** 默认往回留半格：刚过整点那档还在播，用户最想看的就是它 */
const val EPG_LOOK_BEHIND_MINUTES: Int = 30

/** 默认往后看 6 小时：覆盖一晚的黄金时段，又不至于把窗口拉到「滑不到头」 */
const val EPG_WINDOW_MINUTES: Int = 360

/** 刻度间隔，同时也是窗口起点的对齐粒度 */
const val SLOT_MINUTES: Int = 30

private const val MINUTE_MILLIS = 60_000L

/**
 * 把一堆节目摆到时间轴上。
 *
 * 三条规则：
 *
 * 1. **窗口起点对齐到 [SLOT_MINUTES]**：写 11:43 打开节目单，窗口从 11:00 开始，
 *    而不是 10:43 —— 刻度必须是整点半点，用户扫一眼就知道几点。
 * 2. **与窗口求交后才摆放**：一档节目可能 10:00~12:00，窗口从 11:00 起，
 *    那它就只画 11:00~12:00 这段。直接按原始时长画会把后面所有块整体右推。
 * 3. **丢弃完全在窗外的块**，但不丢弃「跨越边界」的块 —— 后者正是第 2 条要处理的。
 */
fun buildEpgTimeline(
    programmes: List<EpgProgramme>,
    nowMillis: Long,
    lookBehindMinutes: Int = EPG_LOOK_BEHIND_MINUTES,
    windowMinutes: Int = EPG_WINDOW_MINUTES,
    slotMinutes: Int = SLOT_MINUTES,
): EpgTimeline {
    require(windowMinutes > 0) { "窗口时长必须为正" }
    require(slotMinutes > 0) { "刻度间隔必须为正" }

    // 先把「现在」按刻度对齐，再往回留一格：这样窗口起点永远落在整点或半点上
    val alignedNow = nowMillis - nowMillis.mod(slotMinutes * MINUTE_MILLIS)
    val windowStart = alignedNow - lookBehindMinutes * MINUTE_MILLIS
    val windowEnd = windowStart + windowMinutes * MINUTE_MILLIS

    val blocks =
        programmes
            .mapNotNull { programme ->
                val from = maxOf(programme.startAt, windowStart)
                val to = minOf(programme.endAt, windowEnd)
                // 求交后为空：整档都在窗口外，或者数据本身 end <= start
                if (to <= from) return@mapNotNull null
                EpgBlock(
                    programme = programme,
                    startMinute = ((from - windowStart) / MINUTE_MILLIS).toInt(),
                    durationMinutes = ((to - from) / MINUTE_MILLIS).toInt(),
                )
            }
            .sortedBy { it.startMinute }

    val ticks = (0..windowMinutes step slotMinutes).toList()

    return EpgTimeline(
        windowStart = windowStart,
        windowEnd = windowEnd,
        blocks = blocks,
        tickMinutes = ticks,
    )
}
