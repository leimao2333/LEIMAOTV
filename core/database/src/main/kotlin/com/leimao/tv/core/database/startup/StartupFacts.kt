package com.leimao.tv.core.database.startup

import com.leimao.tv.core.database.LeimaoDatabase

/**
 * 启动判定需要的事实。字段与 [com.leimao.tv.core.model.startup.StartupSnapshot] 对应，
 * 只差「合规是否已同意」——那个存在 DataStore，由 :app 合并后再算落点。
 */
data class StartupFacts(
    val hasEnabledSource: Boolean,
    val channelsParsed: Boolean,
    val lastChannelId: Long?,
    val favoriteChannelId: Long?,
    val firstChannelId: Long?,
)

interface StartupFactsReader {
    /**
     * @param lastChannelIdHint 从 DataStore 读到的「上次频道 id」，一次读一个键。
     *   这里会再验证它是否还存在 —— 不存在就返回 null，落点自然降级。
     */
    suspend fun read(lastChannelIdHint: Long?): StartupFacts
}

class RoomStartupFactsReader(
    private val database: LeimaoDatabase,
) : StartupFactsReader {
    override suspend fun read(lastChannelIdHint: Long?): StartupFacts {
        val sourceDao = database.sourceDao()
        val channelDao = database.channelDao()

        return StartupFacts(
            hasEnabledSource = sourceDao.countEnabled() > 0,
            // 有源但一个频道都没有 = 「解析尚未完成」，落点走播放页骨架 + 载入提示（§6.6 降级 3）
            channelsParsed = channelDao.count() > 0,
            lastChannelId = lastChannelIdHint?.let { channelDao.existingIdOrNull(it) },
            // 收藏表随 LIVE-14 进来，在那之前「收藏第一个」这一级恒为空，直接跳到下一级
            favoriteChannelId = null,
            firstChannelId = channelDao.firstChannelId(),
        )
    }
}
