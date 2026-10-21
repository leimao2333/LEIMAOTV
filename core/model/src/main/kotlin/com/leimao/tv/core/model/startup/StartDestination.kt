package com.leimao.tv.core.model.startup

/**
 * 冷启动落点。RULES §6.6 是硬规则：**默认直达播放页**，只有三条降级条件能出现别的页面。
 *
 * 做成纯函数而不是写在 Activity 里，是因为这条规则最容易在重构中被「顺手加一个首页」破坏；
 * 落成纯函数后，四条启动路径都能被单测钉住（RULES §9.2 第一条要求的四种情况）。
 */
sealed interface StartDestination {
    /** 降级 1：首次启动、合规提示未同意（RULES §2.4）。只弹一次。 */
    data object ComplianceNotice : StartDestination

    /** 降级 2：一个订阅源都没有。按返回 = 退出确认，不设主页中转。 */
    data object AddSource : StartDestination

    /**
     * 正常落点：播放页。
     *
     * @param channelId 要起播的频道；null 表示「还没解析出频道」，此时页面放骨架 + 顶部提示，
     *   不允许先跳主页让用户自己找（RULES §6.6 降级 3）。
     * @param channelsLoading 为 true 时最多等 10s，超时给「重试 / 去源管理」出口。
     */
    data class Player(
        val channelId: Long?,
        val channelsLoading: Boolean,
    ) : StartDestination
}

/**
 * 启动判定的输入。刻意只放这几项 —— 启动关键路径上只允许读
 * 「合规标记 + 是否有源 + 上次频道」这一条记录（RULES §6.6 实现约束 2）。
 *
 * [lastChannelId] 由数据层保证「频道仍存在」：查询用 JOIN，频道被删就返回 null，
 * 于是「上次频道没了」自然降级到收藏第一个，不需要在这里再判断一次。
 */
data class StartupSnapshot(
    val complianceAgreed: Boolean,
    val hasEnabledSource: Boolean,
    val channelsParsed: Boolean,
    val lastChannelId: Long? = null,
    val favoriteChannelId: Long? = null,
    val firstChannelId: Long? = null,
)

/**
 * 落点优先级：上次观看的频道 → 收藏第一个 → 频道列表第一个（RULES §6.6）。
 * 三者都没有（有源但一个频道都没解析出来）时仍停在播放页给出口，**不回主页**。
 */
fun resolveStartDestination(snapshot: StartupSnapshot): StartDestination {
    if (!snapshot.complianceAgreed) return StartDestination.ComplianceNotice
    if (!snapshot.hasEnabledSource) return StartDestination.AddSource
    if (!snapshot.channelsParsed) return StartDestination.Player(channelId = null, channelsLoading = true)

    val target =
        snapshot.lastChannelId
            ?: snapshot.favoriteChannelId
            ?: snapshot.firstChannelId

    return StartDestination.Player(channelId = target, channelsLoading = false)
}
