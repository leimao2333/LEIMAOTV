package com.leimao.tv.feature.vod

import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodEpisode
import com.leimao.tv.core.model.vod.VodLine
import com.leimao.tv.core.model.vod.VodProgress
import com.leimao.tv.core.model.vod.VodSummary

/** 详情页的四种结局。UI 必须对每一种给出**不同的说法**（见 [VodDetailStatus] 的注释）。 */
enum class VodDetailStatus {
    /** 正在回源 */
    LOADING,

    /** 有数据了 */
    READY,

    /** 源还在，但它查不到这条（下架 / 改 id） */
    GONE,

    /** 源被删了或停用了 —— 这页没法自愈，只能引导去源管理 */
    NO_SOURCE,

    /** 网络或解析失败，可以重试 */
    FAILED,
}

/**
 * 影视详情页的全部展示状态（VOD-04）。
 *
 * [summary] 与 [detail] 是两个来源：前者来自首页快照（本地库，**立刻有**），
 * 后者来自回源（要等）。两者都留着而不是「detail 到了就把 summary 丢掉」，
 * 是因为回源失败时还得靠它把标题和海报撑住 —— 直接白屏最伤。
 *
 * [lineIndex] / [episodeIndex] 都指向**源数据里的原始顺序**，`reversed` 只影响
 * 渲染顺序。这样切倒序不会把「上次看到第几集」搞丢，VOD-06 记进度时也不必关心
 * 用户当时是不是开着倒序。
 */
data class VodDetailState(
    val summary: VodSummary? = null,
    val detail: VodDetail? = null,
    val status: VodDetailStatus = VodDetailStatus.LOADING,
    /** 需要直接告诉用户的一句话（失败原因等） */
    val note: String? = null,
    val lineIndex: Int = 0,
    val episodeIndex: Int = 0,
    val reversed: Boolean = false,
    /**
     * 上次看到哪儿（VOD-06）。为 null 表示这部片没看过、或已经看完。
     *
     * 与 [lineIndex] / [episodeIndex] 分开存：那两个是**当前选中**（用户可能正在翻选集），
     * 这个是「上次真的播到哪」，点「继续观看」时用它，翻选集不该把它改掉。
     */
    val progress: VodProgress? = null,
    /** 收藏了没有（VOD-07）。详情页顶部的「收藏」按钮据此显示 收藏 / 已收藏 */
    val favorited: Boolean = false,
) {
    /** 标题 / 海报优先取回源结果：详情里的字段比首页快照新 */
    val title: String get() = detail?.summary?.name ?: summary?.name.orEmpty()

    val poster: String? get() = detail?.summary?.pic ?: summary?.pic

    val lines: List<VodLine> get() = detail?.lines.orEmpty()

    val currentLine: VodLine? get() = lines.getOrNull(lineIndex)

    /** 按当前倒序偏好排好的选集；空线路时为空表（UI 据此显示「这条线路没有地址」） */
    val episodes: List<VodEpisode>
        get() {
            val raw = currentLine?.episodes.orEmpty()
            return if (reversed) raw.asReversed() else raw
        }

    /**
     * 当前选中的集**在展示顺序里的**下标。
     *
     * 用一个派生值而不是再存一份 state：两份下标一起维护，切倒序时必然有一步是错的。
     */
    val shownEpisodeIndex: Int
        get() {
            val size = episodes.size
            if (size == 0) return 0
            val raw = episodeIndex.coerceIn(0, size - 1)
            return if (reversed) size - 1 - raw else raw
        }

    /** 展示顺序的下标 → 源数据里的原始下标。UI 点选集时用它换算，免得渲染层也去判 [reversed]。 */
    fun originalIndexAt(shown: Int): Int {
        val size = episodes.size
        if (size == 0) return 0
        val index = shown.coerceIn(0, size - 1)
        return if (reversed) size - 1 - index else index
    }

    val ready: Boolean get() = status == VodDetailStatus.READY

    /** 副信息行：评分 · 年份 · 地区 · 语言，缺哪个都不留空位 */
    val metaLine: String
        get() {
            val s = detail?.summary ?: summary ?: return ""
            return listOfNotNull(
                detail?.score?.let { "评分 $it" },
                s.year?.toString(),
                s.area?.takeIf { it.isNotBlank() },
                detail?.lang?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }
}
