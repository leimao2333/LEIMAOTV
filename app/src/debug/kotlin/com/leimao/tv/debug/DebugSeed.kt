package com.leimao.tv.debug

import com.leimao.tv.AppContainer
import com.leimao.tv.core.database.entity.ChannelEntity
import com.leimao.tv.core.database.entity.SourceEntity

/**
 * **仅调试包**：种一条演示源。
 *
 * 为什么需要它：M0 还没有源管理界面，若库里一条源都没有，冷启动必然落到「添加源引导页」，
 * 那「直达播放页」这条主线就没法验收。种一条假源之后，四条启动路径都能走一遍。
 *
 * 合规边界（RULES §2.1）：地址一律用 `example.com` 占位，不含任何真实源地址、频道名、站点名；
 * 且这个文件只存在于 src/debug —— 正式包里根本没有这段代码，不是「用开关关掉」。
 */
object DebugSeed {
    private const val DEMO_HOST = "https://example.com"
    private const val DEMO_SOURCE_NAME = "演示源（仅调试包）"

    suspend fun seedIfEmpty(container: AppContainer) {
        val sourceDao = container.database.sourceDao()
        if (sourceDao.countEnabled() > 0) return

        val demoSource =
            SourceEntity(
                type = "LIVE",
                name = DEMO_SOURCE_NAME,
                url = "$DEMO_HOST/demo.m3u",
            )
        val sourceId = sourceDao.insert(demoSource)

        val channelDao = container.database.channelDao()
        insertChannel(channelDao, sourceId = sourceId, number = 1)
        insertChannel(channelDao, sourceId = sourceId, number = 2)
    }

    private suspend fun insertChannel(
        channelDao: com.leimao.tv.core.database.dao.ChannelDao,
        sourceId: Long,
        number: Int,
    ) {
        val channel =
            ChannelEntity(
                sourceId = sourceId,
                name = "演示频道 $number",
                groupName = "未分组",
                url = "$DEMO_HOST/demo/channel-$number.m3u8",
                number = number,
            )
        channelDao.insert(channel)
    }
}
