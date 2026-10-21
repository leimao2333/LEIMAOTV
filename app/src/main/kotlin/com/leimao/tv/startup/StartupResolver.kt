package com.leimao.tv.startup

import com.leimao.tv.core.database.startup.StartupFactsReader
import com.leimao.tv.core.model.startup.StartDestination
import com.leimao.tv.core.model.startup.StartupSnapshot
import com.leimao.tv.core.model.startup.resolveStartDestination
import com.leimao.tv.data.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * 把「本地标记 + 数据库事实」拼成一次落点判定。
 *
 * 顺序是有讲究的：**合规未同意时根本不去碰数据库** —— 首次启动的用户还没同意任何东西，
 * 这时候多查两次库纯属浪费，而冷启动的每一毫秒都算在 §7 的 2 秒红线里。
 */
class StartupResolver(
    private val settings: SettingsStore,
    private val factsReader: StartupFactsReader,
) {
    suspend fun resolve(): StartDestination {
        if (!settings.complianceAgreed.first()) {
            return StartDestination.ComplianceNotice
        }

        // 「上次看的台」在 history 表里查：一条命中索引的 join，同时证明了
        // 「有启用的源」「频道已解析」，比先读 DataStore 再校验少一次 IO
        val facts = factsReader.read()

        return resolveStartDestination(
            StartupSnapshot(
                complianceAgreed = true,
                hasEnabledSource = facts.hasEnabledSource,
                channelsParsed = facts.channelsParsed,
                lastChannelId = facts.lastChannelId,
                favoriteChannelId = facts.favoriteChannelId,
                firstChannelId = facts.firstChannelId,
            ),
        )
    }
}
