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
    suspend fun read(): StartupFacts
}

class RoomStartupFactsReader(
    private val database: LeimaoDatabase,
) : StartupFactsReader {
    /**
     * 读启动事实。
     *
     * **命中「上次看过的台」时只发一条查询**：history 与 channel / source 的 join 能取到 id，
     * 就同时证明了「有启用的源」「频道已解析」两件事，后面两个优先级根本不用再查。
     * 冷启动的每一毫秒都算在 §7 的 2 秒红线里，省下来的三次查询不是洁癖。
     */
    override suspend fun read(): StartupFacts {
        val lastChannelId = database.historyDao().latestChannelId()
        if (lastChannelId != null) {
            return StartupFacts(
                hasEnabledSource = true,
                channelsParsed = true,
                lastChannelId = lastChannelId,
                favoriteChannelId = null,
                firstChannelId = null,
            )
        }

        val sourceDao = database.sourceDao()
        val channelDao = database.channelDao()
        // 只数直播源：只有影视源的用户没有「台」可播，应该走无源引导而不是播放页骨架
        val hasEnabledSource = sourceDao.countEnabledLive() > 0

        return StartupFacts(
            hasEnabledSource = hasEnabledSource,
            // 有源但一个频道都没有 = 「解析尚未完成」，落点走播放页骨架 + 载入提示（§6.6 降级 3）
            channelsParsed = channelDao.count() > 0,
            lastChannelId = null,
            favoriteChannelId = database.favoriteDao().firstFavoriteChannelId(),
            firstChannelId = channelDao.firstChannelId(),
        )
    }
}
