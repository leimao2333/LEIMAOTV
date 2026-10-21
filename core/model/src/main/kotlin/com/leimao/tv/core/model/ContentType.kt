package com.leimao.tv.core.model

/**
 * 内容类型全集。
 *
 * 为什么要枚举出「不许出现」的类型（短剧 / 纪录片 / 资讯 / 预告片），而不是只留白名单那几类：
 * 白名单是靠**差集**起作用的 —— 只有把所有可能出现的类型都列出来，
 * Repository 出口才能确定「剩下的都不许进」，不会有一类内容以「未分类」的身份漏过去。
 */
enum class ContentType(val displayName: String) {
    /** 直播频道。只在搜索里作为结果类型出现，不属于影视库的分类。 */
    LIVE_CHANNEL("直播频道"),
    MOVIE("电影"),
    TV_SERIES("电视剧"),
    ANIME("动漫"),
    VARIETY("综艺"),
    SPORTS("体育"),

    // ↓ 以下四类：影视板块能搜到，直播板块一律不返回（RULES §3.1）
    SHORT_PLAY("短剧"),
    DOCUMENTARY("纪录片"),
    NEWS("资讯"),
    TRAILER("预告片"),
    ;

    companion object {
        /** 影视库的九个分类（VOD-02），不含直播频道。 */
        val VOD_TYPES: Set<ContentType> = entries.toSet() - LIVE_CHANNEL
    }
}
