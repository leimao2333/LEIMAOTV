package com.leimao.tv.core.model

/**
 * 导航路由。放在 core:model 而不是 :app，是为了让 feature 模块只依赖「一个字符串常量」
 * 就能发起跳转，而不必互相 import（RULES §5.1：跨模块协作走 core:model + 导航事件）。
 */
object TvRoute {
    const val COMPLIANCE = "compliance"
    const val ADD_SOURCE = "add_source"

    /** 源管理（SET-03）。首启引导与设置里的入口共用同一页，只有返回行为不同 */
    const val SOURCE_MANAGER = "source_manager"
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val CHANGELOG = "changelog"
    const val LICENSE = "license"

    /** 播放页带两个查询参数：频道 id（-1 = 尚未确定）与是否仍在载入频道。 */
    const val PLAYER = "player?channelId={channelId}&loading={loading}"

    private const val NO_CHANNEL = -1L

    fun player(channelId: Long?, loading: Boolean = false): String =
        "player?channelId=${channelId ?: NO_CHANNEL}&loading=$loading"

    /** 从路由参数还原频道 id：-1 归一成 null，免得下游到处判魔法数。 */
    fun channelIdFrom(raw: Long): Long? = raw.takeIf { it != NO_CHANNEL }
}
