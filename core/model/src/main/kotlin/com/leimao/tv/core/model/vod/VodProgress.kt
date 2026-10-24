package com.leimao.tv.core.model.vod

/**
 * 一部片子的看片进度（VOD-06）。
 *
 * 用 `(sourceId, sourceKey)` 主键而不是本地自增 id：进度是**跟着片子走**的，
 * 换个源看同一部片就是另一条进度 —— 井水不犯河水，也免得在两张表之间维持一个映射。
 *
 * [lineIndex] 一起存下来是必要的：同一条线路才有意义。甲线路看到 20 分钟、
 * 乙线路的集数对不上（39 集 vs 40 集），跨线路拿位置去 seek 会落到完全不相干的地方。
 *
 * [durationMs] 为 0 表示内核还没报到时长（HLS 未取到 manifest 时就是这样），
 * 此时进度条不画、也不算「看完」—— 拿 0 当分母会把一切都算成 100%。
 */
data class VodProgress(
    val sourceId: Long,
    val sourceKey: String,
    val lineIndex: Int,
    val episodeIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * 「第 N 集」。
     *
     * 不用源里的集名：苹果CMS 的集名从「第01集」到「EP01」再到直接留空什么都有，
     * 而进度记的是序号 —— 按序号说反而永远不会说错。
     */
    val episodeLabel: String get() = "第 ${episodeIndex + 1} 集"
}

/**
 * 「继续观看」里的一张卡：进度 + 展示所需的标题与海报。
 *
 * 标题和海报是**入库时的快照**，不 JOIN `media` 表。原因很实在：
 * `media` 是首页快照，每次刷新都整类替换，一部看到第 30 集的老剧早就被挤出那一页了。
 * 那时 JOIN 会得到空标题，用户看到的就是一张没有名字的卡 —— 而他要找的恰恰是它。
 */
data class VodContinueItem(
    val progress: VodProgress,
    val title: String,
    val poster: String?,
) {
    val fraction: Float get() = progress.fraction

    val episodeLabel: String get() = progress.episodeLabel
}

/**
 * 什么时候该记、什么时候算看完。
 *
 * 单独拎出来是因为这条规则有两个消费者：播放页（决定写不写）与详情页 / 首页
 * （决定要不要把 stale 记录当成「看不到一半的片子」展示）。两边各写一份阈值，
 * 迟早会出现「播放页觉得该记、首页觉得不该显示」的鬼故事。
 */
object VodProgressRules {
    /**
     * 短于这个时长不记。
     *
     * 遥控器误触进一集、看十秒退出来，是很常见的操作。每次都留一条「继续观看」，
     * 首页那块很快就会被自己点错的记录占满 —— 而它本来是用来看「我真的在追什么」的。
     */
    const val MIN_KEEP_MS = 15_000L

    /**
     * 看到这个比例就算看完。
     *
     * 不走满 100%：片尾曲一响用户就走了，留一条「继续观看 第 40 集 42:31」等于
     * 让他下次点进去看两分钟黑屏字幕。
     */
    const val FINISHED_RATIO = 0.95f

    /** 能不能记。时长未知（0）时按「够长了就记」处理，不拿 0 去比 */
    fun shouldKeep(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = positionMs >= MIN_KEEP_MS && !isFinished(positionMs, durationMs)

    fun isFinished(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = durationMs > 0L && positionMs >= (durationMs * FINISHED_RATIO).toLong()
}
