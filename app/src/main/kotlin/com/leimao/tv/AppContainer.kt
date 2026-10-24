package com.leimao.tv

import android.content.Context
import com.leimao.tv.core.data.EpgImporter
import com.leimao.tv.core.data.SourceRefresher
import com.leimao.tv.core.data.VodCatalogRefresher
import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.repository.ChannelRepository
import com.leimao.tv.core.database.repository.ChannelWriter
import com.leimao.tv.core.database.repository.EpgRepository
import com.leimao.tv.core.database.repository.FavoriteRepository
import com.leimao.tv.core.database.repository.HistoryRepository
import com.leimao.tv.core.database.repository.MediaRepository
import com.leimao.tv.core.database.repository.RoomChannelRepository
import com.leimao.tv.core.database.repository.RoomChannelWriter
import com.leimao.tv.core.database.repository.RoomFavoriteRepository
import com.leimao.tv.core.database.repository.RoomHistoryRepository
import com.leimao.tv.core.database.repository.RoomMediaRepository
import com.leimao.tv.core.database.repository.RoomSourceRepository
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.startup.RoomStartupFactsReader
import com.leimao.tv.core.database.startup.StartupFactsReader
import com.leimao.tv.core.model.playlist.MergedChannel
import com.leimao.tv.core.network.AppHttp
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.OkHttpHttpClient
import com.leimao.tv.core.player.BufferProfile
import com.leimao.tv.core.player.Media3PlayerController
import com.leimao.tv.core.player.PlayerController
import com.leimao.tv.data.DataStoreSettingsStore
import com.leimao.tv.data.SettingsStore
import com.leimao.tv.startup.StartupResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 手写依赖容器。
 *
 * 不引 Hilt 是有意的：模块数量还撑得住一份看得完的清单，多一个 kapt/ksp 环节就多一份
 * 构建时间（RULES §7 的构建与体积都要算账）。等这份清单看不懂了再引 DI 也不迟。
 *
 * 这里只有「谁依赖谁」的接线，没有任何业务判断 —— 判断都在 :core:model 与 :core:data。
 */
class AppContainer(context: Context) {
    /** 只给调试种子之类需要文件路径的场景用；业务代码不该拿它去拼 SQL 或路径 */
    internal val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LeimaoDatabase = LeimaoDatabase.build(appContext)

    val settings: SettingsStore = DataStoreSettingsStore(appContext)

    /**
     * 全应用共用一个 OkHttp 实例：连接池复用是它性能的大头，
     * 播放内核（Media3 的 data source）以后也拿同一个实例。
     */
    val httpClient: HttpClient = OkHttpHttpClient(AppHttp.client)

    val sourceRepository: SourceRepository = RoomSourceRepository(database)

    val channelRepository: ChannelRepository = RoomChannelRepository(database)

    val favoriteRepository: FavoriteRepository = RoomFavoriteRepository(database)

    val historyRepository: HistoryRepository = RoomHistoryRepository(database)

    private val channelWriter: ChannelWriter = RoomChannelWriter(database)

    /** 「抓取 → 解析 → 合并 → 落库」的唯一入口（源管理页手动刷新 / 启动刷新都走它） */
    val sourceRefresher: SourceRefresher =
        SourceRefresher(
            sources = sourceRepository,
            writer = channelWriter,
            http = httpClient,
        )

    /** 影视条目缓存（VOD-03）。首页只读它，不等网络 */
    val mediaRepository: MediaRepository = RoomMediaRepository(database)

    /** 影视源 → 分类 → 每类首页 → 落库。与直播的 [sourceRefresher] 是两条独立链路 */
    val vodCatalogRefresher: VodCatalogRefresher =
        VodCatalogRefresher(
            sources = sourceRepository,
            media = mediaRepository,
            http = httpClient,
        )

    val startupFacts: StartupFactsReader = RoomStartupFactsReader(database)

    /** 节目单（LIVE-20）：整份导入、只读查询当前/下一档 */
    val epgRepository: EpgRepository = EpgRepository(database.epgDao(), database.epgSourceDao())

    val epgImporter: EpgImporter = EpgImporter(epgRepository, httpClient)

    val startupResolver = StartupResolver(settings, startupFacts)

    /**
     * 只给 `src/debug` 的演示种子用：走与真实刷新完全相同的写入路径（含线路表）。
     *
     * 刻意不把 [ChannelWriter] 直接暴露成公开属性 —— 频道表的写入路径越少，
     * 「刷新不会换 id、不会连带删收藏」这个不变式越不容易被破坏。
     */
    internal suspend fun writeDemoChannels(
        sourceId: Long,
        channels: List<MergedChannel>,
    ) {
        channelWriter.replaceForSource(sourceId = sourceId, merged = channels)
    }

    /**
     * 播放器实例挂在应用层，不随页面销毁（架构设计 §2）。
     * 正因为它在容器里，「切模式不断流」和「从影视回来接着播」才不需要额外逻辑。
     *
     * 缓冲档位（SET-01）在构建时读一次：LoadControl 是 ExoPlayer 构造期参数，
     * 中途改档要重建内核，代价大且会打断播放。故档位变更**下次启动生效**，设置页会明说。
     * 这里用 `runBlocking` 同步读首值 —— 只在容器构造这一次，读的是 DataStore 首屏，
     * 冷启动预算里可忽略（真正的播放起播仍走异步）。
     */
    val player: PlayerController =
        Media3PlayerController(
            context = appContext,
            okHttpClient = AppHttp.client,
            bufferProfile = currentBufferProfile(settings),
        )

    private fun currentBufferProfile(settings: SettingsStore): BufferProfile =
        runBlocking { settings.playbackSettings.first().bufferProfile }
}
