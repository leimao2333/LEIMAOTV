package com.leimao.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.leimao.tv.AppContainer
import com.leimao.tv.SourceRefreshWorker
import com.leimao.tv.core.data.RefreshInterval
import com.leimao.tv.core.data.VodDetailResult
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.TvRoute
import com.leimao.tv.core.model.epg.EpgProgramme
import com.leimao.tv.core.model.epg.currentAt
import com.leimao.tv.core.model.epg.nextAfter
import com.leimao.tv.core.model.startup.StartDestination
import com.leimao.tv.core.network.BasicAuth
import com.leimao.tv.core.network.RequestHeaders
import com.leimao.tv.core.network.vod.VodFailure
import com.leimao.tv.core.player.PlaybackRequest
import com.leimao.tv.core.player.PlaybackSettings
import com.leimao.tv.core.player.VideoSurfaceHost
import com.leimao.tv.feature.iptv.PlayerScreen
import com.leimao.tv.feature.iptv.PlayerUiState
import com.leimao.tv.feature.iptv.ProgrammeLine
import com.leimao.tv.feature.iptv.groupChannels
import com.leimao.tv.feature.iptv.neighborOf
import com.leimao.tv.feature.search.SearchScreen
import com.leimao.tv.feature.settings.ChangelogScreen
import com.leimao.tv.feature.settings.LicenseScreen
import com.leimao.tv.feature.settings.PlaybackSettingsScreen
import com.leimao.tv.feature.settings.RefreshSettingsScreen
import com.leimao.tv.feature.settings.SettingsScreen
import com.leimao.tv.feature.settings.SourceManagerScreen
import com.leimao.tv.feature.vod.VodDetailScreen
import com.leimao.tv.feature.vod.VodDetailState
import com.leimao.tv.feature.vod.VodDetailStatus
import com.leimao.tv.feature.vod.VodHomeState
import com.leimao.tv.feature.vod.VodSourceRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 导航图。页面树是**以播放页为根的单枢纽结构**（RULES §6.4）：
 * 播放页只往主页去，主页只往退出确认去，两者不互为返回目标。
 */
@Composable
fun LeimaoTvApp(
    container: AppContainer,
    startDestination: StartDestination,
    menuSignal: Int,
    onExitApp: () -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val startRoute = remember(startDestination) { startDestination.toRoute() }
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    // 全局菜单（HOME-08）：菜单键信号一到就 toggle。信号来自 Activity 层（任意页面、
    // 任意 Dialog 之上的最外层），所以这里不依赖某个页面的按键处理。
    LaunchedEffect(menuSignal) {
        if (menuSignal > 0) menuOpen = !menuOpen
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = startRoute) {
            composable(TvRoute.COMPLIANCE) {
                ComplianceScreen(
                    onAgree = {
                        scope.launch {
                            container.settings.setComplianceAgreed(true)
                            // 同意后立刻重走落点判定，直接去该去的地方 —— 不许绕回主页（§6.6）
                            val next = container.startupResolver.resolve()
                            navController.replaceStackWith(next.toRoute())
                        }
                    },
                    onDecline = onExitApp,
                )
            }

            composable(TvRoute.ADD_SOURCE) {
                SourceManagerRoute(
                    container = container,
                    navController = navController,
                    isFirstRun = true,
                    onExitApp = onExitApp,
                )
            }

            composable(route = TvRoute.PLAYER, arguments = playerArguments) { entry ->
                PlayerRoute(
                    container = container,
                    channelId = TvRoute.channelIdFrom(entry.arguments?.getLong(ARG_CHANNEL_ID) ?: -1L),
                    loadingChannels = entry.arguments?.getBoolean(ARG_LOADING) ?: false,
                    onBackToHome = { navController.replaceStackWith(TvRoute.HOME) },
                    onOpenSourceManager = { navController.navigate(TvRoute.SETTINGS) },
                )
            }

            composable(TvRoute.HOME) {
                HomeRoute(container = container, navController = navController, onExitApp = onExitApp)
            }

            composable(route = TvRoute.VOD_DETAIL, arguments = vodDetailArguments) { entry ->
                VodDetailRoute(
                    container = container,
                    sourceId = entry.arguments?.getLong(ARG_SOURCE_ID) ?: 0L,
                    sourceKey = entry.arguments?.getString(ARG_SOURCE_KEY).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenSourceManager = { navController.navigate(TvRoute.SOURCE_MANAGER) },
                )
            }

            composable(TvRoute.SEARCH) {
                val mode by container.settings.mode.collectAsStateWithLifecycle(initialValue = AppMode.LIVE)
                BackHandler { navController.popBackStack() }
                SearchScreen(
                    scope = if (mode == AppMode.LIVE) SearchScope.IPTV else SearchScope.VOD,
                )
            }

            composable(TvRoute.SETTINGS) {
                BackHandler { navController.popBackStack() }
                SettingsScreen(
                    onOpenPlaybackSettings = { navController.navigate(TvRoute.PLAYBACK_SETTINGS) },
                    onOpenRefreshSettings = { navController.navigate(TvRoute.REFRESH_SETTINGS) },
                    onOpenChangelog = { navController.navigate(TvRoute.CHANGELOG) },
                    onOpenSourceManager = { navController.navigate(TvRoute.SOURCE_MANAGER) },
                    onOpenLicense = { navController.navigate(TvRoute.LICENSE) },
                )
            }

            composable(TvRoute.SOURCE_MANAGER) {
                SourceManagerRoute(
                    container = container,
                    navController = navController,
                    isFirstRun = false,
                    onExitApp = onExitApp,
                )
            }

            composable(TvRoute.CHANGELOG) {
                BackHandler { navController.popBackStack() }
                ChangelogScreen()
            }

            composable(TvRoute.LICENSE) {
                BackHandler { navController.popBackStack() }
                LicenseScreen()
            }

            composable(TvRoute.PLAYBACK_SETTINGS) {
                BackHandler { navController.popBackStack() }
                PlaybackSettingsRoute(container = container)
            }

            composable(TvRoute.REFRESH_SETTINGS) {
                BackHandler { navController.popBackStack() }
                RefreshSettingsRoute(container = container)
            }
        }

        GlobalMenuOverlay(
            open = menuOpen,
            onOpenSearch = { navController.navigate(TvRoute.SEARCH) },
            onOpenSourceManager = { navController.navigate(TvRoute.SOURCE_MANAGER) },
            onOpenSettings = { navController.navigate(TvRoute.SETTINGS) },
            onExit = onExitApp,
            onDismiss = { menuOpen = false },
        )
    }
}

@Composable
private fun RefreshSettingsRoute(container: AppContainer) {
    val interval by
        container.settings.refreshInterval.collectAsStateWithLifecycle(
            initialValue = RefreshInterval.DEFAULT,
        )
    val scope = rememberCoroutineScope()

    RefreshSettingsScreen(
        interval = interval,
        onIntervalChange = { next ->
            scope.launch {
                container.settings.setRefreshInterval(next)
                // 改周期要立刻反映到 WorkManager：注册新周期或取消（关闭时）
                SourceRefreshWorker.schedule(container.appContext, next)
            }
        },
    )
}

@Composable
private fun PlaybackSettingsRoute(container: AppContainer) {
    val settings by
        container.settings.playbackSettings.collectAsStateWithLifecycle(
            initialValue = PlaybackSettings.DEFAULT,
        )
    val scope = rememberCoroutineScope()

    PlaybackSettingsScreen(
        settings = settings,
        onBufferProfileChange = { profile ->
            scope.launch { container.settings.setPlaybackSettings(settings.copy(bufferProfile = profile)) }
        },
        onSeekStepChange = { step ->
            scope.launch { container.settings.setPlaybackSettings(settings.copy(seekIncrementMs = step.millis)) }
        },
    )
}

@Composable
private fun HomeRoute(
    container: AppContainer,
    navController: NavHostController,
    onExitApp: () -> Unit,
) {
    val mode by container.settings.mode.collectAsStateWithLifecycle(initialValue = AppMode.LIVE)
    // 主页内落点记忆（HOME-03）：DataStore 里存的是名字，这里解析成枚举；
    // 存档里出现不认识的名字（降级安装 / 手工改档）时回落到「直播」，不让启动路径崩
    val savedSectionName by container.settings.lastSection.collectAsStateWithLifecycle(initialValue = null)
    var pickedSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section =
        HomeSection.entries.firstOrNull { it.name == (pickedSectionName ?: savedSectionName) }
            ?: HomeSection.LIVE
    val scope = rememberCoroutineScope()

    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var recentChannels by remember { mutableStateOf<List<Channel>>(emptyList()) }

    // 主页每次回到前台都刷新一次列表：用户可能刚在源管理里加完源
    LaunchedEffect(Unit) {
        channels = container.channelRepository.listFromEnabledSources()
        favoriteIds = container.favoriteRepository.favoriteChannelIds().toSet()
        recentChannels = container.historyRepository.recent()
    }

    // ---- 影视首页（VOD-03）----
    // 类型 / 年份 / 地区三个「用户选择」用 rememberSaveable：进详情页再返回时不该被重置
    var vodState by remember { mutableStateOf(VodHomeState()) }
    var vodTypeName by rememberSaveable { mutableStateOf(ContentType.MOVIE.name) }
    var vodYear by rememberSaveable { mutableStateOf<Int?>(null) }
    var vodArea by rememberSaveable { mutableStateOf<String?>(null) }
    // 后台刷新完成一次就 +1，用来驱动下面的重新读库
    var vodRevision by remember { mutableStateOf(0) }
    val vodType = ContentType.entries.firstOrNull { it.name == vodTypeName } ?: ContentType.MOVIE

    // 点导航「电视剧」要真的切到电视剧，而不是停在电影
    LaunchedEffect(section) {
        val mapped = section.toVodType() ?: return@LaunchedEffect
        if (mapped.name != vodTypeName) {
            vodTypeName = mapped.name
            vodYear = null
            vodArea = null
        }
    }

    // 进影视板块时按缓存时效决定要不要回源：一个源就是「分类 + 每类首页」七八个请求，
    // 每次切板块都打一轮既慢又容易被对方限流
    LaunchedEffect(mode) {
        if (mode != AppMode.VOD) return@LaunchedEffect
        val last = container.mediaRepository.lastFetchedAt()
        val stale = last == null || System.currentTimeMillis() - last > VOD_CACHE_TTL_MS
        if (!stale) return@LaunchedEffect
        vodState = vodState.copy(loading = true)
        container.vodCatalogRefresher.refreshAll()
        vodRevision++
    }

    // 读库组装：类型 / 筛选 / 刷新完成，任一变化都重算。首页只读本地快照，不等网络
    LaunchedEffect(mode, vodTypeName, vodYear, vodArea, vodRevision) {
        if (mode != AppMode.VOD) return@LaunchedEffect
        vodState = loadVodHomeState(container, vodType, vodYear, vodArea)
    }

    HomeScreen(
        mode = mode,
        section = section,
        onSectionChange = { picked ->
            pickedSectionName = picked.name
            scope.launch { container.settings.setLastSection(picked.name) }
        },
        onModeChange = { next -> scope.launch { container.settings.setMode(next) } },
        onOpenPlayer = {
            scope.launch {
                // 回播放页时继续播上次那个台，不重建播放器（§6.4 最后一条）。
                // 「上次」的真相在 history 表里（LIVE-15），不再另存一份
                val lastChannelId = container.historyRepository.latestChannelId()
                navController.navigate(TvRoute.player(lastChannelId))
            }
        },
        channels = channels,
        favoriteIds = favoriteIds,
        recentChannels = recentChannels,
        vodState = vodState,
        vodActions =
            VodHomeActions(
                onSelectType = { type ->
                    vodTypeName = type.name
                    vodYear = null
                    vodArea = null
                    // 有导航项的类型顺带同步导航高亮；短剧 / 纪录片没有导航项，保持当前项不动
                    type.toHomeSection()?.let { mapped ->
                        pickedSectionName = mapped.name
                        scope.launch { container.settings.setLastSection(mapped.name) }
                    }
                },
                onSelectYear = { vodYear = it },
                onSelectArea = { vodArea = it },
                onSelectMedia = { summary ->
                    navController.navigate(TvRoute.vodDetail(summary.sourceId, summary.sourceKey))
                },
            ),
        onOpenChannel = { channelId -> navController.navigate(TvRoute.player(channelId)) },
        onOpenSourceManager = { navController.navigate(TvRoute.SOURCE_MANAGER) },
        onOpenChangelog = { navController.navigate(TvRoute.CHANGELOG) },
        onOpenLicense = { navController.navigate(TvRoute.LICENSE) },
        onOpenPlaybackSettings = { navController.navigate(TvRoute.PLAYBACK_SETTINGS) },
        onOpenRefreshSettings = { navController.navigate(TvRoute.REFRESH_SETTINGS) },
        onExit = onExitApp,
    )
}

/**
 * 播放页的数据接线：按 channelId 取频道 → 起播 → 记一笔历史。
 *
 * 这三步是**组装顺序**，不是业务规则（业务规则在 :core:model 的落点判定里）。
 * 之所以留在 :app，是因为它把导航参数、播放器实例、设置存储三样东西缝在一起，
 * 而这三样恰好都归 :app 管（RULES §5.5）。
 */
@Composable
private fun PlayerRoute(
    container: AppContainer,
    channelId: Long?,
    loadingChannels: Boolean,
    onBackToHome: () -> Unit,
    onOpenSourceManager: () -> Unit,
) {
    val playback by container.player.state.collectAsStateWithLifecycle()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var current by remember { mutableStateOf<Channel?>(null) }
    var favoriteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var recentChannels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var programme by remember { mutableStateOf<ProgrammeLine?>(null) }
    var epgProgrammes by remember { mutableStateOf<List<EpgProgramme>>(emptyList()) }
    val scope = rememberCoroutineScope()

    suspend fun refreshRecent() {
        recentChannels = container.historyRepository.recent()
    }

    suspend fun startChannel(targetId: Long) {
        val channel =
            channels.firstOrNull { it.id == targetId }
                ?: container.channelRepository.find(targetId)
                ?: return
        val config = container.channelRepository.playbackConfig(channel.id) ?: return

        current = channel
        // 换台先清空上一个台的节目单：留着的话打开网格会看到上一个台的节目
        // 铺在当前这个台的名字下面，比空白更糟
        epgProgrammes = emptyList()
        container.player.play(
            PlaybackRequest(
                channelId = channel.id,
                url = config.lines.first(),
                headers = config.toHeaders(),
                lines = config.lines,
            ),
        )
        // 记住「刚看了这个台」：启动落点优先级 #1 读的就是这条记录（§6.6）
        container.historyRepository.touch(channel.id, System.currentTimeMillis())
        // 最近观看分组要跟着换台更新，否则抽屉里的「最近观看」一直是旧名单
        refreshRecent()
    }

    LaunchedEffect(Unit) {
        channels = container.channelRepository.listFromEnabledSources()
        favoriteIds = container.favoriteRepository.favoriteChannelIds().toSet()
        refreshRecent()

        // 落点已经由 StartupResolver 算过；这里只处理「当时没算出来、现在有频道了」
        val target = channelId ?: container.historyRepository.latestChannelId() ?: channels.firstOrNull()?.id
        if (target != null) startChannel(target)
    }

    // 节目单对表：跨过整点要换成下一档，不刷新就会一直挂着上一档的名字。
    // 跟着 current 一起重算 —— 换台必须立刻换成新台的节目，不能等这一分钟走完。
    // 一次查询同时喂两处：顶部的「正在播 / 下一档」与节目单网格（LIVE-22），
    // 两边各自查库会出现「顶部说这档、网格说那档」的自相矛盾。
    LaunchedEffect(current?.id) {
        val channel = current ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val list = container.epgRepository.programmesFor(channel.name, now)
            epgProgrammes = list

            val currentProgramme = list.currentAt(now)
            val nextProgramme = list.nextAfter(now)
            programme =
                ProgrammeLine(
                    current = currentProgramme?.title,
                    currentEndsAt = currentProgramme?.endAt,
                    next = nextProgramme?.title,
                    nextStartsAt = nextProgramme?.startAt,
                ).takeUnless { it.isEmpty }

            delay(EPG_REFRESH_MS)
        }
    }

    // 返回回主页这件事由 PlayerScreen 内部统一调度（先收节目单/抽屉/控制条，
    // 无可收才回调 onBack）。这里不能自己挂 BackHandler：Compose 的 effect 先子后父，
    // 外层注册得更晚、优先级更高，会把内层的返回处理整个抢走（实测：网格开着按返回
    // 直接回主页）。onBackToHome 通过参数传下去。
    PlayerScreen(
        state =
            PlayerUiState(
                playback = playback,
                channel = current,
                groups = remember(channels) { groupChannels(channels) },
                favoriteIds = favoriteIds,
                loadingChannels = loadingChannels && channels.isEmpty(),
                programme = programme,
                recentChannels = recentChannels,
                epgProgrammes = epgProgrammes,
            ),
        videoPlayer = (container.player as? VideoSurfaceHost)?.videoPlayer,
        onSwitchChannel = { delta ->
            val next = neighborOf(channels, current?.id, delta)
            if (next != null) scope.launch { startChannel(next.id) }
        },
        onSelectChannel = { id -> scope.launch { startChannel(id) } },
        onSwitchLine = { delta -> container.player.switchLine(delta) },
        onToggleFavorite = {
            val id = current?.id ?: return@PlayerScreen
            scope.launch {
                container.favoriteRepository.toggle(id, System.currentTimeMillis())
                favoriteIds = container.favoriteRepository.favoriteChannelIds().toSet()
            }
        },
        onRetry = {
            current?.let { container.player.retry() }
        },
        onOpenSourceManager = onOpenSourceManager,
        onBack = onBackToHome,
    )
}

/** 源管理页的接线：首次运行与设置里进入是同一页，只是返回行为不同。 */
@Composable
private fun SourceManagerRoute(
    container: AppContainer,
    navController: NavHostController,
    isFirstRun: Boolean,
    onExitApp: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    SourceManagerScreen(
        sourceRepository = container.sourceRepository,
        refresher = container.sourceRefresher,
        epgImporter = container.epgImporter,
        epgRepository = container.epgRepository,
        vodRefresher = container.vodCatalogRefresher,
        onBack =
            if (isFirstRun) {
                // 无源引导页按返回 = 退出确认（§6.6 降级 2：没有可播内容，不设主页中转）
                { onExitApp() }
            } else {
                { navController.popBackStack() }
            },
        onSourcesChanged = {
            // 源变了就重算一次落点：加完源直接进播放页，而不是让用户自己回主页再点直播（§6.6）
            scope.launch {
                navController.replaceStackWith(container.startupResolver.resolve().toRoute())
            }
        },
    )
}

/**
 * 影视详情页的接线（VOD-04）。
 *
 * 两段式载入：
 * 1. **先读本地快照**——首页点进来时 `media` 表里一定已经有这一条，用它把标题和海报
 *    先画出来，让用户立刻看见「点对了」，而不是对着白屏等一次往返；
 * 2. **再回源拿详情**——简介、线路、选集只在详情接口里有。
 *
 * 回源失败时**不销毁 1 的画面**：status 变成 FAILED，头部照旧显示本地摘要，
 * 只有选集区换成「没能读出来 + 重试」。整页塌成错误页是最难看的做法。
 */
@Composable
private fun VodDetailRoute(
    container: AppContainer,
    sourceId: Long,
    sourceKey: String,
    onBack: () -> Unit,
    onOpenSourceManager: () -> Unit,
) {
    BackHandler { onBack() }

    var state by remember { mutableStateOf(VodDetailState()) }
    // 重试计数器：改一下就重新跑一次 LaunchedEffect，不必自己管 Job
    var reloadTag by remember { mutableStateOf(0) }

    LaunchedEffect(sourceId, sourceKey, reloadTag) {
        state =
            state.copy(
                summary = container.mediaRepository.find(sourceId, sourceKey) ?: state.summary,
                status = VodDetailStatus.LOADING,
                note = null,
            )

        when (val result = container.vodDetailLoader.load(sourceId, sourceKey)) {
            is VodDetailResult.Loaded ->
                state =
                    state.copy(
                        detail = result.detail,
                        summary = result.detail.summary,
                        status = VodDetailStatus.READY,
                        note = null,
                        // 换片要重置线路与选集：从 A 片的第 20 集返回、再点进 B 片，
                        // 停在第 20 集毫无意义，而 B 片可能只有 10 集
                        lineIndex = 0,
                        episodeIndex = 0,
                    )

            VodDetailResult.Gone -> state = state.copy(status = VodDetailStatus.GONE)
            VodDetailResult.NoSource -> state = state.copy(status = VodDetailStatus.NO_SOURCE)
            is VodDetailResult.Failed ->
                state =
                    state.copy(
                        status = VodDetailStatus.FAILED,
                        note = describeDetailFailure(result.reason, result.detail),
                    )
        }
    }

    VodDetailScreen(
        state = state,
        onRetry = { reloadTag++ },
        onSelectLine = { index -> state = state.copy(lineIndex = index, episodeIndex = 0) },
        onToggleReverse = { state = state.copy(reversed = !state.reversed) },
        onSelectEpisode = { shown ->
            // 展示下标 → 原始下标在 state 里换算，这里不重复一遍倒序逻辑
            val original = state.originalIndexAt(shown)
            state = state.copy(episodeIndex = original)
        },
        onOpenSourceManager = onOpenSourceManager,
    )
}

/**
 * 详情页场景下的失败文案。
 *
 * 与源管理页那份**故意不同**：[VodFailure] 的注释里写明「同一个原因在不同场景下说法不同」。
 * 这里源地址已经验证过（能进到详情页说明首页抓过内容），所以「返回的不是影视接口」
 * 这种提示只会让人怀疑自己是不是点错了 —— 换成「对方接口这会儿不对」更准确。
 */
private fun describeDetailFailure(
    failure: VodFailure,
    detail: String?,
): String =
    when (failure) {
        VodFailure.TIMEOUT -> "对方接口超时了，稍后再试一次"
        VodFailure.HTTP -> "对方接口返回了错误（${detail ?: "HTTP 错误"}），可能是临时故障"
        VodFailure.NETWORK -> "网络不通（${detail ?: "无法连接"}），检查一下盒子或路由"
        VodFailure.EMPTY -> "对方返回了空内容，这部片可能刚下架"
        VodFailure.NOT_VOD_SOURCE -> "对方返回的内容不像详情（${detail ?: "格式不符"}），过一会儿再试"
    }

/**
 * 从本地快照组装影视首页状态（VOD-03）。
 *
 * 为什么在 :app 而不在 feature 模块：读数据属于组装层的职责，feature 只拿现成数据 ——
 * 与直播板块把 `channels` 传进 HomeScreen 是同一套做法（架构设计 §1 的依赖方向）。
 */
private suspend fun loadVodHomeState(
    container: AppContainer,
    type: ContentType,
    year: Int?,
    area: String?,
): VodHomeState {
    val media = container.mediaRepository
    val vodSources = container.sourceRepository.list().filter { it.type == SourceType.VOD && it.enabled }
    val rows =
        vodSources.mapNotNull { source ->
            val items = media.bySource(source.id)
            // 这次没抓到内容的源不占一行空白
            if (items.isEmpty()) null else VodSourceRow(source.id, source.name, items)
        }
    return VodHomeState(
        selectedType = type,
        years = media.yearsOf(type),
        areas = media.areasOf(type),
        selectedYear = year,
        selectedArea = area,
        items = media.byType(type, year, area),
        recent = media.recent(),
        sourceRows = rows,
        hasSource = vodSources.isNotEmpty(),
    )
}

/** 影视首页缓存有效期：半小时内直接读库，不为「切个板块」打一轮请求 */
private const val VOD_CACHE_TTL_MS = 30 * 60 * 1000L

private const val ARG_CHANNEL_ID = "channelId"
private const val ARG_LOADING = "loading"
private const val ARG_SOURCE_ID = "sourceId"
private const val ARG_SOURCE_KEY = "sourceKey"

/**
 * 节目单对表间隔。
 *
 * 一分钟一次：节目单是分钟粒度的，一档节目至少半小时，一分钟的误差肉眼看不出来；
 * 再密就是在为「精确到秒」做无用功 —— 每次都要过一遍 Room 的时间窗查询。
 */
private const val EPG_REFRESH_MS = 60_000L

private val playerArguments =
    listOf(
        navArgument(ARG_CHANNEL_ID) {
            type = NavType.LongType
            defaultValue = -1L
        },
        navArgument(ARG_LOADING) {
            type = NavType.BoolType
            defaultValue = false
        },
    )

private val vodDetailArguments =
    listOf(
        navArgument(ARG_SOURCE_ID) { type = NavType.LongType },
        // 必须给默认值：StringType 没有默认值时空串会让路由匹配失败，
        // 那比进到页面里查不到更难看
        navArgument(ARG_SOURCE_KEY) {
            type = NavType.StringType
            defaultValue = ""
        },
    )

private fun StartDestination.toRoute(): String =
    when (this) {
        StartDestination.ComplianceNotice -> TvRoute.COMPLIANCE
        StartDestination.AddSource -> TvRoute.ADD_SOURCE
        is StartDestination.Player -> TvRoute.player(channelId = channelId, loading = channelsLoading)
    }

/**
 * 把返回栈换成「只剩这一个页面」。
 *
 * 为什么不用 popBackStack()：从播放页回主页时，若播放页是起点，popBackStack 会直接把
 * Activity 弹掉（用户以为按了退出）；从播放页去设置再回来时，又可能留下两份主页。
 * 与其到处判断栈里有谁，不如显式重建 —— 单枢纽结构本来就只该有一个枢纽页。
 */
private fun NavHostController.replaceStackWith(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** 源级请求头 → 播放头。Basic 认证只在用户名非空时才注入。 */
private fun com.leimao.tv.core.database.repository.PlaybackConfig.toHeaders(): Map<String, String> =
    RequestHeaders(
        userAgent = userAgent?.takeIf { it.isNotBlank() },
        referer = referer?.takeIf { it.isNotBlank() },
        basicAuth =
            basicUser?.takeIf { it.isNotBlank() }?.let { user ->
                BasicAuth(username = user, password = basicPass.orEmpty())
            },
    ).toPlaybackHeaders()
