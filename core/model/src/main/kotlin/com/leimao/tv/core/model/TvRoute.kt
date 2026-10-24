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
    const val PLAYBACK_SETTINGS = "playback_settings"

    /** 界面设置（SET-02）：主题与字号，改了立即生效 */
    const val APPEARANCE_SETTINGS = "appearance_settings"

    /** 网络设置（SET-04）：超时 / 并发 / 代理，改了下次启动生效 */
    const val NETWORK_SETTINGS = "network_settings"
    const val REFRESH_SETTINGS = "refresh_settings"

    /** 存储与缓存（SET-05）：看占了多少、一键清空 */
    const val CACHE = "cache"

    /** 检查更新（SET-07）：读 GitHub 的 Latest Release，按附件名精确匹配 */
    const val UPDATE = "update"

    /** 播放页带两个查询参数：频道 id（-1 = 尚未确定）与是否仍在载入频道。 */
    const val PLAYER = "player?channelId={channelId}&loading={loading}"

    /**
     * 影视详情（VOD-04）。用 `(sourceId, sourceKey)` 而不是单片 id 定位：
     * 跨源的 `vod_id` 会撞，只有这一对唯一（见 `VodSummary` 的注释）。
     */
    const val VOD_DETAIL = "vod_detail?sourceId={sourceId}&sourceKey={sourceKey}"

    /**
     * 影视播放（VOD-05）。带上线路与集号：从详情页点某一集进来，就直接播那一集，
     * 而不是「先播第一集再让用户自己跳」。
     *
     * `startMs` 是续播点（VOD-06）：从「继续观看」进来时不是从头开始。
     */
    const val VOD_PLAY =
        "vod_play?sourceId={sourceId}&sourceKey={sourceKey}" +
            "&lineIndex={lineIndex}&episodeIndex={episodeIndex}&startMs={startMs}"

    private const val NO_CHANNEL = -1L

    fun player(channelId: Long?, loading: Boolean = false): String =
        "player?channelId=${channelId ?: NO_CHANNEL}&loading=$loading"

    /** 从路由参数还原频道 id：-1 归一成 null，免得下游到处判魔法数。 */
    fun channelIdFrom(raw: Long): Long? = raw.takeIf { it != NO_CHANNEL }

    fun vodDetail(
        sourceId: Long,
        sourceKey: String,
    ): String = "vod_detail?sourceId=$sourceId&sourceKey=${percentEncode(sourceKey)}"

    fun vodPlay(
        sourceId: Long,
        sourceKey: String,
        lineIndex: Int,
        episodeIndex: Int,
        startPositionMs: Long = 0L,
    ): String =
        "vod_play?sourceId=$sourceId&sourceKey=${percentEncode(sourceKey)}" +
            "&lineIndex=$lineIndex&episodeIndex=$episodeIndex" +
            // 负数在这里就归一：路由参数一路走到播放器，不必每层都判一次
            "&startMs=${startPositionMs.coerceAtLeast(0L)}"

    /**
     * 极简百分号编码。
     *
     * 为什么自己写而不用 `URLEncoder`：后者按 `application/x-www-form-urlencoded` 把空格编成
     * `+`，而导航组件按 URI 规范只认 `%20`，`+` 会被当成字面量原样传下去 ——
     * 于是「片名里有空格」的条目详情页会永远查不到。这个模块又是纯 JVM（RULES §5.2），
     * 借不到 `android.net.Uri`。二十行换一个不含糊的语义，值得。
     *
     * 只保留 URI 里无歧义的字符（字母、数字、`-._~`），其余一律编码：
     * 宁可多编码几个字符，也不要在某个冷门字符上出一次「点进去是空的」。
     */
    internal fun percentEncode(value: String): String =
        buildString(value.length) {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") {
                    append(char)
                } else {
                    append('%')
                    append(HEX[code shr 4])
                    append(HEX[code and 0x0F])
                }
            }
        }

    private const val HEX = "0123456789ABCDEF"
}
