package com.leimao.tv

import android.content.Context
import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.repository.ChannelRepository
import com.leimao.tv.core.database.repository.RoomChannelRepository
import com.leimao.tv.core.database.startup.RoomStartupFactsReader
import com.leimao.tv.core.database.startup.StartupFactsReader
import com.leimao.tv.core.player.PlayerController
import com.leimao.tv.core.player.StubPlayerController
import com.leimao.tv.data.DataStoreSettingsStore
import com.leimao.tv.data.SettingsStore
import com.leimao.tv.startup.StartupResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写依赖容器。
 *
 * 不引 Hilt 是有意的：11 个模块的 M0 骨架用不上注解处理，多一个 kapt/ksp 环节
 * 就多一份构建时间与被模板代码淹没的风险（RULES §7 的构建与体积都要算账）。
 * 依赖关系一旦复杂到这份手写清单看不懂，再引 DI 也不迟。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LeimaoDatabase = LeimaoDatabase.build(appContext)

    val settings: SettingsStore = DataStoreSettingsStore(appContext)

    val startupFacts: StartupFactsReader = RoomStartupFactsReader(database)

    val startupResolver = StartupResolver(settings, startupFacts)

    val channelRepository: ChannelRepository = RoomChannelRepository(database)

    /**
     * 播放器实例挂在应用层，不随页面销毁（架构设计 §2）。
     * 正因为它在容器里，「切模式不断流」和「从影视回来接着播」才不需要额外逻辑。
     */
    val player: PlayerController = StubPlayerController()
}
