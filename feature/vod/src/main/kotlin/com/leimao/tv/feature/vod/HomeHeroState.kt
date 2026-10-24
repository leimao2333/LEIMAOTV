package com.leimao.tv.feature.vod

import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.vod.VodContinueItem
import com.leimao.tv.core.model.vod.VodSummary

/**
 * 首页 Hero 区（HOME-04）的一张大图。
 *
 * 三种来源统一成一个类型，是为了让「轮播」这件事只有一份实现：
 * 继续观看、正在直播、推荐在视觉与交互上完全一样 —— 都是「一张图 + 一句话 + 按 OK 就走」，
 * 分成三个组件的结果只会是三套不同的间距，以及三处各自忘记处理空数据。
 *
 * [key] 必须**稳定且唯一**：轮播下标按位置推进，列表重建时如果 key 变了，
 * 用户刚看清的那一张就会在他要按 OK 的瞬间换成别的。所以 key 用的是片 / 台的业务主键，
 * 而不是下标。
 */
sealed interface HeroItem {
    /** 稳定且唯一的 key，用于渲染时定位这一张（轮播切换时不让焦点跟着跳）。 */
    val key: String

    /**
     * 去重身份：**「这是同一部片 / 同一个台」**。
     *
     * 与 [key] 分开是因为两者回答的问题不一样 —— [key] 回答「这一屏是哪一张」，
     * 继续观看与推荐是两屏条目；[identity] 回答「用户眼里这是不是同一个东西」，
     * 同一部片只该出现一次，无论它以哪种身份进来。
     */
    val identity: String

    val title: String
    val poster: String?
    val kicker: String
    val meta: String
    val actionLabel: String

    /** 看了一半的片子。优先级最高 —— 它是首页上唯一「不做就要重新找一遍」的东西。 */
    data class Continue(
        val item: VodContinueItem,
    ) : HeroItem {
        override val key: String
            get() = "continue:${item.progress.sourceId}:${item.progress.sourceKey}"
        override val identity: String
            get() = "media:${item.progress.sourceId}:${item.progress.sourceKey}"
        override val title: String get() = item.title
        override val poster: String? get() = item.poster
        override val kicker: String get() = KICKER_CONTINUE
        override val meta: String
            get() = "${item.episodeLabel} · 已看到 ${formatTime(item.progress.positionMs)}"
        override val actionLabel: String get() = "接着看"
    }

    /**
     * 正在直播的台。
     *
     * [nowPlaying] 来自节目单（XMLTV）；没导节目单时为 null，此时用分组名顶上 ——
     * 与其留一行空白，不如告诉用户「这是央视的一组」。
     */
    data class Live(
        val channel: Channel,
        val nowPlaying: String? = null,
    ) : HeroItem {
        override val key: String get() = "live:${channel.id}"
        override val identity: String get() = key
        override val title: String get() = channel.name
        override val poster: String? get() = channel.logoUrl
        override val kicker: String get() = KICKER_LIVE
        override val meta: String
            get() =
                listOfNotNull(
                    nowPlaying?.takeIf { it.isNotBlank() }?.let { "正在播 $it" },
                    channel.groupName.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
        override val actionLabel: String get() = "回直播"
    }

    /** 推荐：本地快照里最近更新的片子。 */
    data class Recommend(
        val item: VodSummary,
    ) : HeroItem {
        override val key: String get() = "recommend:${item.sourceId}:${item.sourceKey}"
        override val identity: String get() = "media:${item.sourceId}:${item.sourceKey}"
        override val title: String get() = item.name
        override val poster: String? get() = item.pic
        override val kicker: String get() = KICKER_RECOMMEND
        override val meta: String
            get() =
                listOfNotNull(
                    item.remarks?.takeIf { it.isNotBlank() },
                    item.year?.toString(),
                    item.area?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                    .ifBlank { item.type.displayName }
        override val actionLabel: String get() = "看详情"
    }

    companion object {
        const val KICKER_CONTINUE: String = "继续观看"
        const val KICKER_LIVE: String = "正在直播"
        const val KICKER_RECOMMEND: String = "今日推荐"

        /** 轮播一圈的总条数。六条正好是「三分钟不看也不会重样」的量，再多就记不住了。 */
        const val HERO_LIMIT: Int = 6

        /** 继续观看最多占三席：它是入口，不是列表 —— 真要翻更多，下面还有整行 */
        const val CONTINUE_LIMIT: Int = 3

        /** 直播最多两席：只在「最近看过的台」里挑，多了会把片子挤掉 */
        const val LIVE_LIMIT: Int = 2
    }
}

/**
 * 组装 Hero 的轮播序列。
 *
 * 排序是**刻意固定**的：继续观看 → 正在直播 → 推荐。理由是「越靠前的越经不起重新找一遍」：
 * 片子看到第 12 集，位置只有应用记着；直播台用户自己大概记得是哪个；推荐本来就是在逛。
 *
 * 去重按**片子的业务主键**（`sourceId + sourceKey`）而不是标题：同名的翻拍、同名不同源
 * 都是不同的片子，按标题去重会把用户正在追的那条真的删掉。直播按 `channel.id` 去重。
 *
 * [nowPlaying] 是 `channelId → 当前节目名`，查不到就不带节目名 —— 详见 [HeroItem.Live]。
 */
fun buildHeroItems(
    continueWatching: List<VodContinueItem> = emptyList(),
    liveChannels: List<Channel> = emptyList(),
    nowPlaying: Map<Long, String> = emptyMap(),
    recommendations: List<VodSummary> = emptyList(),
    limit: Int = HeroItem.HERO_LIMIT,
): List<HeroItem> {
    if (limit <= 0) return emptyList()

    val out = ArrayList<HeroItem>(limit)
    // 去重按「这部片是谁」而不是按类型：同一部片既在继续观看又在推荐里时，
    // 只留继续观看那条（它多带了进度），否则首页会出现两张一样的海报
    val seen = HashSet<String>(limit)

    fun offer(item: HeroItem) {
        if (out.size >= limit) return
        if (!seen.add(item.identity)) return
        out += item
    }

    continueWatching
        .take(HeroItem.CONTINUE_LIMIT)
        .forEach { offer(HeroItem.Continue(it)) }

    liveChannels
        .distinctBy { it.id }
        .take(HeroItem.LIVE_LIMIT)
        .forEach { offer(HeroItem.Live(it, nowPlaying[it.id])) }

    recommendations.forEach { summary ->
        if (out.size >= limit) return@forEach
        offer(HeroItem.Recommend(summary))
    }

    return out
}

/**
 * 轮播下一张。
 *
 * 单独抽出来是因为「最后一张之后回第一张」在长按场景下很容易写成 `index++` 然后越界，
 * 或者写成 `% size` 但在 `size == 0` 时除零 —— 这两种错在真机上都是「首页整块消失」。
 */
fun nextHeroIndex(
    current: Int,
    size: Int,
): Int {
    if (size <= 0) return 0
    val normalized = if (current < 0) 0 else current % size
    return (normalized + 1) % size
}
