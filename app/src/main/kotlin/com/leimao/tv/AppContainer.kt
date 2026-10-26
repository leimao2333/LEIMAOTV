package com.leimao.tv

import android.content.Context
import android.os.Build
import com.leimao.tv.core.data.EpgAutoBinder
import com.leimao.tv.core.data.EpgImporter
import com.leimao.tv.core.data.LineScheduler
import com.leimao.tv.core.data.PinyinIme
import com.leimao.tv.core.data.PinyinTable
import com.leimao.tv.core.data.SearchRepository
import com.leimao.tv.core.data.SourceBackupImporter
import com.leimao.tv.core.data.SourceHealthChecker
import com.leimao.tv.core.data.SourceHealthStore
import com.leimao.tv.core.data.SourceRefresher
import com.leimao.tv.core.data.UpdateChecker
import com.leimao.tv.core.data.VodCatalogRefresher
import com.leimao.tv.core.data.VodDetailLoader
import com.leimao.tv.core.database.LeimaoDatabase
import com.leimao.tv.core.database.repository.ChannelLineQualityRepository
import com.leimao.tv.core.database.repository.ChannelRepository
import com.leimao.tv.core.database.repository.ChannelWriter
import com.leimao.tv.core.database.repository.EpgRepository
import com.leimao.tv.core.database.repository.FavoriteRepository
import com.leimao.tv.core.database.repository.HistoryRepository
import com.leimao.tv.core.database.repository.MediaRepository
import com.leimao.tv.core.database.repository.RoomChannelLineQualityRepository
import com.leimao.tv.core.database.repository.RoomChannelRepository
import com.leimao.tv.core.database.repository.RoomChannelWriter
import com.leimao.tv.core.database.repository.RoomFavoriteRepository
import com.leimao.tv.core.database.repository.RoomHistoryRepository
import com.leimao.tv.core.database.repository.RoomMediaRepository
import com.leimao.tv.core.database.repository.RoomSourceRepository
import com.leimao.tv.core.database.repository.RoomVodFavoriteRepository
import com.leimao.tv.core.database.repository.RoomVodProgressRepository
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.VodFavoriteRepository
import com.leimao.tv.core.database.repository.VodProgressRepository
import com.leimao.tv.core.database.startup.RoomStartupFactsReader
import com.leimao.tv.core.database.startup.StartupFactsReader
import com.leimao.tv.core.model.network.NetworkSettings
import com.leimao.tv.core.model.playlist.MergedChannel
import com.leimao.tv.core.model.release.DeviceAbi
import com.leimao.tv.core.network.AppHttp
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.MirrorHttpClient
import com.leimao.tv.core.network.OkHttpHttpClient
import com.leimao.tv.core.network.buildNetworkClient
import com.leimao.tv.core.player.BufferProfile
import com.leimao.tv.core.player.FallbackPlayerController
import com.leimao.tv.core.player.Media3PlayerController
import com.leimao.tv.core.player.PlayerController
import com.leimao.tv.core.player.VlcPlayerController
import com.leimao.tv.core.version.AppVersion
import com.leimao.tv.data.ApkInstaller
import com.leimao.tv.data.CacheManager
import com.leimao.tv.data.DataStoreSettingsStore
import com.leimao.tv.data.RemoteSettingsApplier
import com.leimao.tv.data.SettingsStore
import com.leimao.tv.startup.StartupResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

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

    /** 缓存统计与清理（SET-05）：只读 `cacheDir`，没有别的依赖 */
    val cacheManager: CacheManager = CacheManager.create(appContext)

    /**
     * 网络设置（SET-04）：超时、并发、代理。
     *
     * 在容器构造时读一次，之后**不再跟随设置变化** —— 三项都是 OkHttpClient 的构造参数，
     * 而那个实例被播放内核与各仓储在构造期就抓走了，中途换要重建整条链并打断正在播的画面。
     * 故与缓冲档位同口径：改了下次启动生效，设置页会明说。
     */
    private val networkSettings: NetworkSettings = currentNetworkSettings(settings)

    /**
     * 全应用共用一个 OkHttp 实例：连接池复用是它性能的大头，
     * 播放内核（Media3 的 data source）与图片加载（Coil）也拿同一个实例。
     *
     * 从 [AppHttp] 的基线 client 派生出带用户超时/代理的那份：`newBuilder()` 共用连接池与
     * Dispatcher，不会因为「多一层配置」就丢掉连接复用。图片走同一份代理 / 超时，
     * 否则隔离网络下海报全都拉不到。
     */
    val okHttpClient: OkHttpClient = buildNetworkClient(AppHttp.client, networkSettings)

    /**
     * 抓取链路统一入口。
     *
     * 外面套一层 [MirrorHttpClient]：`raw.githubusercontent.com` 在国内经常连不上，
     * 直连超时后自动换 GitHub 镜像（聚合订阅、更新检查都吃这一层）。
     * 非 GitHub 地址候选列表只有它自己，等于没有额外开销。
     */
    val httpClient: HttpClient = MirrorHttpClient(OkHttpHttpClient(okHttpClient))

    /**
     * 检查更新（SET-07）：读 GitHub 的 Latest Release 并与当前版本比。
     *
     * 设备架构在构造时探一次就够了：ABI 不会中途变，每次检查都重读一遍只会让「选包」看起来像有状态。
     *
     * 注意声明位置：它依赖上面的 [httpClient]，而 Kotlin 的属性按**书写顺序**初始化 ——
     * 挪到 `httpClient` 之前，拿到的就是一个 null。
     */
    val updateChecker: UpdateChecker =
        UpdateChecker(
            http = httpClient,
            currentVersion = AppVersion.CURRENT_VERSION,
            deviceAbi = DeviceAbi.fromAbi(Build.SUPPORTED_ABIS.firstOrNull()),
        )

    /** 下载与安装更新包（SET-07）：与网络层共用同一个 client，用户配的代理对它同样生效 */
    val apkInstaller: ApkInstaller = ApkInstaller(context = appContext, client = okHttpClient)

    val sourceRepository: SourceRepository = RoomSourceRepository(database)

    /** 源健康检查（LIVE-09）：只探测可达性，不解析不落库，与刷新互不干扰。
     * 必须声明在 [sourceRepository] 与 `httpClient` 之后 —— 属性按书写顺序初始化（MEMORY 坑） */
    val sourceHealthChecker: SourceHealthChecker =
        SourceHealthChecker(sources = sourceRepository, http = httpClient)

    /** 健康检查结果的会话内缓存（SET-09）：放在容器里，离开源管理页再回来结论还在 */
    val sourceHealthStore: SourceHealthStore = SourceHealthStore()

    val channelRepository: ChannelRepository = RoomChannelRepository(database)

    val favoriteRepository: FavoriteRepository = RoomFavoriteRepository(database)

    val historyRepository: HistoryRepository = RoomHistoryRepository(database)

    /** 频道线路质量（LIVE-13 智能调度）：记住每条线路成功/失败，挑最优 */
    val channelLineQualityRepository: ChannelLineQualityRepository =
        RoomChannelLineQualityRepository(database.channelLineQualityDao())

    /** 线路调度：按历史质量排序 + 无历史时并发探测，起播前挑最优线路 */
    val lineScheduler: LineScheduler =
        LineScheduler(
            quality = channelLineQualityRepository,
            http = httpClient,
        )

    private val channelWriter: ChannelWriter = RoomChannelWriter(database)

    /** 节目单（LIVE-20）：整份导入、只读查询当前/下一档 */
    val epgRepository: EpgRepository = EpgRepository(database.epgDao(), database.epgSourceDao())

    val epgImporter: EpgImporter = EpgImporter(epgRepository, httpClient)

    /**
     * 订阅源自带节目单地址时自动挂上（LIVE-27）。
     *
     * 放在 [sourceRefresher] 之前声明：容器的属性按书写顺序初始化，后声明的在这里还是 null。
     */
    private val epgAutoBinder: EpgAutoBinder = EpgAutoBinder(epgRepository, epgImporter)

    /** 「抓取 → 解析 → 合并 → 落库」的唯一入口（源管理页手动刷新 / 启动刷新都走它） */
    val sourceRefresher: SourceRefresher =
        SourceRefresher(
            sources = sourceRepository,
            writer = channelWriter,
            http = httpClient,
            epgBinder = epgAutoBinder,
        )

    /** 备份里的远程偏好（LIVE-08）：解析在 core，落地在这里（DataStore 只有 app 层认识） */
    private val remoteSettingsApplier: RemoteSettingsApplier = RemoteSettingsApplier(settings)

    /** 从地址导入源备份（SET-10）：电视上没有好用的文件管理器，清单往往就是一个 URL */
    val sourceBackupImporter: SourceBackupImporter =
        SourceBackupImporter(
            http = httpClient,
            sources = sourceRepository,
            applySettings = { remote -> remoteSettingsApplier.apply(remote) },
        )

    /** 影视条目缓存（VOD-03）。首页只读它，不等网络 */
    val mediaRepository: MediaRepository = RoomMediaRepository(database)

    /** 影视源 → 分类 → 每类首页 → 落库。与直播的 [sourceRefresher] 是两条独立链路 */
    val vodCatalogRefresher: VodCatalogRefresher =
        VodCatalogRefresher(
            sources = sourceRepository,
            media = mediaRepository,
            http = httpClient,
            maxConcurrency = networkSettings.concurrency.refreshPermits,
        )

    /** 单片详情（VOD-04）：`(sourceId, sourceKey)` → 源 → 适配器 → 详情。不落库 */
    val vodDetailLoader: VodDetailLoader =
        VodDetailLoader(
            sources = sourceRepository,
            http = httpClient,
        )

    /**
     * 跨库搜索（SEARCH-01）：本地频道与影视快照先出，远端逐源补入。
     *
     * 白名单（SEARCH-02）在它的出口过滤，UI 层不再存第二份判断 —— 这里没有可配项，
     * 也没有「要不要过滤」的开关：一个能被关掉的合规开关，迟早会被关掉。
     *
     * 拼音表（SEARCH-06）：assets 里约 211KB，解析成四张数组后约 400KB 常驻；
     * 读不到就退化成空表（只按名字搜），不让搜索因为缺一份词典整个不可用。
     */
    private val pinyinTable: PinyinTable by lazy {
        runCatching {
            context.assets.open("pinyin_table.txt").bufferedReader().use { PinyinTable(it.readText()) }
        }.getOrElse { PinyinTable.EMPTY }
    }

    /**
     * 输入法候选表（SET-11）：打 `wo` 出「我」。
     *
     * 267KB / 1.5 万个拼音，首次搜索时才解析（懒加载）—— 冷启动路径上不碰它。
     * 读不到就退化成空表：候选行只是少了输入法建议，搜索本身照常。
     */
    private val pinyinIme: PinyinIme by lazy {
        runCatching {
            context.assets.open("pinyin_ime.txt").bufferedReader().use { PinyinIme.parse(it.readText()) }
        }.getOrElse { PinyinIme.EMPTY }
    }

    val searchRepository: SearchRepository =
        SearchRepository(
            channels = channelRepository,
            media = mediaRepository,
            sources = sourceRepository,
            http = httpClient,
            perSourceTimeoutMs = networkSettings.timeout.searchTimeoutMs,
            maxConcurrency = networkSettings.concurrency.permits,
            pinyin = pinyinTable,
            ime = pinyinIme,
        )

    /** 看片进度（VOD-06）：播放页写、首页与详情页读 */
    val vodProgressRepository: VodProgressRepository = RoomVodProgressRepository(database)

    /** 影视收藏（VOD-07）：详情页与播放页都能收，首页读它画一行 */
    val vodFavoriteRepository: VodFavoriteRepository = RoomVodFavoriteRepository(database)

    val startupFacts: StartupFactsReader = RoomStartupFactsReader(database)

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
        FallbackPlayerController(
            primary =
                Media3PlayerController(
                    context = appContext,
                    okHttpClient = okHttpClient,
                    bufferProfile = currentBufferProfile(settings),
                ),
            // 软解兜底（PLAY-02 / D-02）：Media3 把整份请求的所有线路都试过仍失败时接管。
            // VLC 自己重连/换线的预算与 Media3 一致，保证「换内核」不改变失败观感。
            //
            // 传**工厂**而不是实例：libVLC 构造会加载约 50MB 原生库。放在容器构造里等于
            // 每次启动都付这份钱，而绝大多数起播用不到它（Media3 直接成功）。
            // 实测常驻内存因此从 130MB 降到 95MB（PSS，模拟器 1080p 播放中）。
            fallbackFactory = { VlcPlayerController(context = appContext) },
        )

    private fun currentBufferProfile(settings: SettingsStore): BufferProfile =
        runBlocking { settings.playbackSettings.first().bufferProfile }

    private fun currentNetworkSettings(settings: SettingsStore): NetworkSettings =
        runBlocking { settings.networkSettings.first() }
}
