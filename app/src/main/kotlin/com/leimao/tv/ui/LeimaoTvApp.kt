package com.leimao.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.leimao.tv.core.model.vod.VodContinueItem
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodProgress
import com.leimao.tv.core.model.vod.VodProgressRules
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.model.vod.acceptsProgress
import com.leimao.tv.core.model.vod.episodeName
import com.leimao.tv.core.model.vod.episodeUrls
import com.leimao.tv.core.model.vod.nextEpisode
import com.leimao.tv.core.model.vod.previousEpisode
import com.leimao.tv.core.network.BasicAuth
import com.leimao.tv.core.network.RequestHeaders
import com.leimao.tv.core.network.vod.VodFailure
import com.leimao.tv.core.player.PlaybackItem
import com.leimao.tv.core.player.PlaybackKind
import com.leimao.tv.core.player.PlaybackRequest
import com.leimao.tv.core.player.PlaybackSettings
import com.leimao.tv.core.player.PlaybackState
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
import com.leimao.tv.feature.vod.HeroItem
import com.leimao.tv.feature.vod.VodDetailScreen
import com.leimao.tv.feature.vod.VodDetailState
import com.leimao.tv.feature.vod.VodDetailStatus
import com.leimao.tv.feature.vod.VodHomeState
import com.leimao.tv.feature.vod.VodPlayerActions
import com.leimao.tv.feature.vod.VodPlayerScreen
import com.leimao.tv.feature.vod.VodPlayerUiState
import com.leimao.tv.feature.vod.VodSourceRow
import com.leimao.tv.feature.vod.buildHeroItems
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
                    navController = navController,
                    sourceId = entry.arguments?.getLong(ARG_SOURCE_ID) ?: 0L,
                    sourceKey = entry.arguments?.getString(ARG_SOURCE_KEY).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenSourceManager = { navController.navigate(TvRoute.SOURCE_MANAGER) },
                )
            }

            composable(route = TvRoute.VOD_PLAY, arguments = vodPlayArguments) { entry ->
                VodPlayRoute(
                    container = container,
                    sourceId = entry.arguments?.getLong(ARG_SOURCE_ID) ?: 0L,
                    sourceKey = entry.arguments?.getString(ARG_SOURCE_KEY).orEmpty(),
                    initialLine = entry.arguments?.getInt(ARG_LINE_INDEX) ?: 0,
                    initialEpisode = entry.arguments?.getInt(ARG_EPISODE_INDEX) ?: 0,
                    initialPositionMs = entry.arguments?.getLong(ARG_START_MS) ?: 0L,
                    // 回详情页而不是回首页：用户是从详情页点进来的，退回去该看到选集
                    onBack = { navController.popBackStack() },
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

    // ---- 首页 Hero 区（HOME-04）----
    // 「正在直播」只从「最近看过的台 + 收藏的台」里挑：Hero 想回答的是「你可能正在看的那个台」，
    // 而不是频道表里的第一个 —— 那一个用户大概率没兴趣
    val liveCandidates =
        remember(channels, favoriteIds, recentChannels) {
            (recentChannels + channels.filter { it.id in favoriteIds })
                .distinctBy { it.id }
                .take(HeroItem.LIVE_LIMIT)
        }

    // 节目单是加分项，不是前提：查不到就只显示台名与分组，首页不会因为没导 EPG 就少一块
    var nowPlaying by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(liveCandidates) {
        nowPlaying = loadNowPlaying(container, liveCandidates)
    }

    val hero =
        remember(vodState.continueWatching, vodState.recent, liveCandidates, nowPlaying) {
            buildHeroItems(
                continueWatching = vodState.continueWatching,
                liveChannels = liveCandidates,
                nowPlaying = nowPlaying,
                recommendations = vodState.recent,
            )
        }

    // 三个跳转出口：Hero 与首页各行用的是同一套动作，不各写一份
    val resumeVod: (VodContinueItem) -> Unit = { item ->
        navController.navigate(
            TvRoute.vodPlay(
                sourceId = item.progress.sourceId,
                sourceKey = item.progress.sourceKey,
                lineIndex = item.progress.lineIndex,
                episodeIndex = item.progress.episodeIndex,
                startPositionMs = item.progress.positionMs,
            ),
        )
    }
    val openVod: (VodSummary) -> Unit = { summary ->
        navController.navigate(TvRoute.vodDetail(summary.sourceId, summary.sourceKey))
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
                onSelectMedia = openVod,
                // 直接进播放页而不是先回详情页：用户点的就是「接着看」，
                // 中间插一页详情等于让他再确认一次自己刚做过的决定
                onResume = resumeVod,
            ),
        hero = hero,
        onHeroOpen = { item ->
            when (item) {
                is HeroItem.Continue -> resumeVod(item.item)
                is HeroItem.Live -> navController.navigate(TvRoute.player(item.channel.id))
                is HeroItem.Recommend -> openVod(item.item)
            }
        },
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
                item = PlaybackItem.Channel(channel.id),
                url = config.lines.first(),
                headers = config.toHeaders(),
                lines = config.lines,
                kind = PlaybackKind.LIVE,
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
    navController: NavHostController,
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
        // 进度只在本地库，进页面顺手读一次，不和回源串行
        val saved = container.vodProgressRepository.find(sourceId, sourceKey)
        state =
            state.copy(
                summary = container.mediaRepository.find(sourceId, sourceKey) ?: state.summary,
                status = VodDetailStatus.LOADING,
                note = null,
                progress = saved,
            )

        when (val result = container.vodDetailLoader.load(sourceId, sourceKey)) {
            is VodDetailResult.Loaded -> {
                // 进度指向的线路/集在源里可能已经不存在了（源换了集数、少了一条线）。
                // 这种进度不能拿去起播，也不能让详情页停在第 40 集却只有 30 集可选 ——
                // 直接当「没进度」处理，界面回到第 1 集，用户只会觉得进度没了，
                // 而不会点进一个必然失败的页面
                val resume = saved?.takeIf { result.detail.acceptsProgress(it) }
                state =
                    state.copy(
                        detail = result.detail,
                        summary = result.detail.summary,
                        status = VodDetailStatus.READY,
                        note = null,
                        progress = resume,
                        // 有进度就停在看到那一集；否则回到第一集。
                        // 换片同样落到这里：从 A 片的第 20 集返回再点进 B 片，
                        // 停在第 20 集毫无意义，而 B 片可能只有 10 集
                        lineIndex = resume?.lineIndex ?: 0,
                        episodeIndex = resume?.episodeIndex ?: 0,
                    )
            }

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
            // 点一集就播这一集（VOD-05）：播放页自己会再拉一次详情拿全集地址，
            // 所以这里只传「哪条线路的第几集」，不传地址
            navController.navigate(
                TvRoute.vodPlay(sourceId, sourceKey, state.lineIndex, original),
            )
        },
        // 继续观看（VOD-06）：线路、集号、位置三样都来自进度，不从头开始
        onResume = { progress ->
            navController.navigate(
                TvRoute.vodPlay(
                    sourceId = sourceId,
                    sourceKey = sourceKey,
                    lineIndex = progress.lineIndex,
                    episodeIndex = progress.episodeIndex,
                    startPositionMs = progress.positionMs,
                ),
            )
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
 * 影视播放页的接线（VOD-05 / VOD-06）。
 *
 * 四件事在这里缝起来：**拉详情**（拿全集地址）、**起播**（走同一个内核，但 kind = VOD）、
 * **自动连播**（监听 Ended 决定下一集）、**记进度**（10 秒一次 + 离开时补一次）。
 * 缝在 :app 是因为它同时碰到导航参数、播放器实例与数据加载三样东西，
 * 而这三样都归 :app 管（RULES §5.5）。
 *
 * 为什么不把剧集列表从详情页传过来：一集一个地址，几十上百条，塞不进导航参数；
 * 而重新拉一次详情只是一个请求，比在两层之间维护一份「传过去的快照」便宜得多。
 */
@Composable
private fun VodPlayRoute(
    container: AppContainer,
    sourceId: Long,
    sourceKey: String,
    initialLine: Int,
    initialEpisode: Int,
    initialPositionMs: Long,
    onBack: () -> Unit,
) {
    val playback by container.player.state.collectAsStateWithLifecycle()
    val settings by
        container.settings.playbackSettings.collectAsStateWithLifecycle(
            initialValue = PlaybackSettings.DEFAULT,
        )

    var detail by remember { mutableStateOf<VodDetail?>(null) }
    var headers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var lineIndex by remember { mutableIntStateOf(initialLine) }
    var episodeIndex by remember { mutableIntStateOf(initialEpisode) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var preparing by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    /**
     * 上次落库时的位置，用来算「离上次写够不够 10 秒」。
     *
     * 换集时归零（[playEpisodeAt] 里）：不归零的话，从 42:00 换到下一集后，
     * 要等新一集播到 42 分钟才会再写一次 —— 而那期间退出就什么都没记下。
     */
    var lastSavedMs by remember { mutableStateOf(0L) }

    /**
     * 写一次进度（VOD-06）。
     *
     * 用应用级作用域而不是页面作用域：**离开页面那一刻也要能写**。
     * 页面作用域在 `onDispose` 里已经被取消，写库请求会随之作废，
     * 用户退出播放时最后那几分钟就白看了。
     *
     * 参数可以显式传：换集时要写的是「上一集的位置」，而那一刻
     * `episodeIndex` 已经被改成新集了。
     *
     * 这里**不删**任何东西。删除（看完 / 从头看）由 [forgetProgress] 单独负责 ——
     * 让「写」和「删」共用一个入口，就会出现「自动连播写下了下一集，几毫秒后
     * 一个迟到的『已看完』把它删掉」这种时序事故。少一个分支，少一种竞态。
     */
    fun persistProgress(
        line: Int = lineIndex,
        episode: Int = episodeIndex,
        position: Long = positionMs,
        duration: Long = durationMs,
    ) {
        val current = detail ?: return
        // 太短不记：遥控器误触进来十秒就退，不该在首页留下一条记录
        if (!VodProgressRules.shouldKeep(position, duration)) return
        container.applicationScope.launch {
            container.vodProgressRepository.save(
                VodProgress(
                    sourceId = sourceId,
                    sourceKey = sourceKey,
                    lineIndex = line,
                    episodeIndex = episode,
                    positionMs = position,
                    durationMs = duration,
                    updatedAt = System.currentTimeMillis(),
                ),
                title = current.summary.name,
                poster = current.summary.pic,
            )
        }
    }

    /** 忘掉这部片的进度：全剧看完、或看到 95% 以上退出来时用 */
    fun forgetProgress() {
        container.applicationScope.launch {
            container.vodProgressRepository.delete(sourceId, sourceKey)
        }
    }

    /**
     * 记下「刚开始看第 N 集」。
     *
     * 自动连播跨集时用：上一集刚播完、下一集才 0 秒，按 [persistProgress] 的规则
     * 会被「太短不记」挡掉，可此刻退出的话，继续观看应该指向这一集 ——
     * 而不是上一集（它已经看完了）。
     */
    fun markEpisodeStarted(
        line: Int,
        episode: Int,
    ) {
        val current = detail ?: return
        container.applicationScope.launch {
            container.vodProgressRepository.save(
                VodProgress(
                    sourceId = sourceId,
                    sourceKey = sourceKey,
                    lineIndex = line,
                    episodeIndex = episode,
                    positionMs = 0L,
                    durationMs = 0L,
                    updatedAt = System.currentTimeMillis(),
                ),
                title = current.summary.name,
                poster = current.summary.pic,
            )
        }
    }

    /** 起播某一集。地址为空时不给内核喂空串 —— 那会得到一个「解析失败」的错误码，说不清是哪一步坏 */
    fun playEpisodeAt(
        line: Int,
        episode: Int,
        startAt: Long = 0L,
        recordPrevious: Boolean = true,
    ) {
        val current = detail ?: return
        val urls = current.episodeUrls(line, episode)
        if (urls.isEmpty()) {
            preparing = false
            note = "这一集没有播放地址，换一条线路试试"
            return
        }
        // 换集前把上一集的位置落一次：周期写最多滞后 10 秒，不补这一下，
        // 「看完第 3 集手动点第 4 集」会让第 3 集的进度停在十几秒前。
        // 自动连播那条路不走这里（recordPrevious = false）：上一集是**播完**的，
        // 写它没有意义，而且会和紧接着的 markEpisodeStarted 抢同一个主键
        if (recordPrevious && (line != lineIndex || episode != episodeIndex)) persistProgress()
        lineIndex = line
        episodeIndex = episode
        finished = false
        preparing = false
        note = null
        positionMs = 0L
        durationMs = 0L
        lastSavedMs = 0L
        container.player.play(
            PlaybackRequest(
                item = PlaybackItem.Episode(sourceId = sourceId, sourceKey = sourceKey, index = episode),
                url = urls.first(),
                // 影视 CDN 大多校验 Referer，少了它表现为「详情能拉到、一播就 403」
                headers = headers,
                lines = urls,
                kind = PlaybackKind.VOD,
                startPositionMs = startAt,
            ),
        )
    }

    // 详情只拉一次：切集、连播都复用这份，不再打网络
    LaunchedEffect(sourceId, sourceKey) {
        when (val result = container.vodDetailLoader.load(sourceId, sourceKey)) {
            is VodDetailResult.Loaded -> {
                detail = result.detail
                headers = result.headers
                // 续播点只在第一次起播时生效：之后换集都是从头（用户点的是「下一集」）
                playEpisodeAt(initialLine, initialEpisode, initialPositionMs)
            }

            VodDetailResult.Gone -> {
                preparing = false
                note = "源里已经找不到这部片子了"
            }

            VodDetailResult.NoSource -> {
                preparing = false
                note = "这个影视源已经不可用了"
            }

            is VodDetailResult.Failed -> {
                preparing = false
                note = describeDetailFailure(result.reason, result.detail)
            }
        }
    }

    // 离开页面补记一次。放在 onDispose 是因为返回键、导航被替换都会走到它 ——
    // 只挂在返回键上的话，另一条路就会丢进度。
    // 看到片尾再退出不必记：删掉更干净（否则「继续观看」里会挂着只剩字幕的片子）
    DisposableEffect(sourceId, sourceKey) {
        onDispose {
            if (VodProgressRules.isFinished(positionMs, durationMs)) {
                forgetProgress()
            } else {
                persistProgress()
            }
        }
    }

    // 自动连播：内核说「播完了」就接下一集。
    // 依赖整个 playback 而不只是 Ended：起播后状态变成 Preparing，这个 effect 会重跑，
    // 于是同一集的 Ended 不会被处理两次
    LaunchedEffect(playback) {
        if (playback !is PlaybackState.Ended) return@LaunchedEffect
        val current = detail ?: return@LaunchedEffect
        val next = current.nextEpisode(lineIndex, episodeIndex)
        if (next == null) {
            finished = true
            // 全剧看完：继续观看里不该再留着它
            container.vodProgressRepository.delete(sourceId, sourceKey)
        } else {
            // recordPrevious = false：上一集是**播完**的，写它没有意义，
            // 还会和紧接着的 markEpisodeStarted 抢同一个主键（谁后到谁赢）
            playEpisodeAt(next.lineIndex, next.episodeIndex, recordPrevious = false)
            markEpisodeStarted(next.lineIndex, next.episodeIndex)
        }
    }

    // 进度轮询：500ms 一次。内核没有位置回调，而进度条只需要「肉眼看着在走」，
    // 再密就是在为看不见的精度烧 CPU
    LaunchedEffect(playback) {
        if (playback !is PlaybackState.Playing) return@LaunchedEffect
        while (true) {
            positionMs = container.player.positionMs()
            durationMs = container.player.durationMs()
            // 10 秒落一次库。用户要的是「接着上次看」，10 秒的偏差在长视频里
            // 连一句台词都不到；再密就是拿闪存寿命换看不见的精度
            if (positionMs - lastSavedMs >= PROGRESS_SAVE_INTERVAL_MS) {
                lastSavedMs = positionMs
                persistProgress()
            }
            delay(POSITION_POLL_MS)
        }
    }

    val current = detail
    val line = current?.lines?.getOrNull(lineIndex)
    VodPlayerScreen(
        state =
            VodPlayerUiState(
                title = current?.summary?.name.orEmpty(),
                episodeName = current?.episodeName(lineIndex, episodeIndex).orEmpty(),
                episodeIndex = episodeIndex,
                episodeCount = line?.episodes?.size ?: 0,
                lineName = line?.name.orEmpty(),
                lineIndex = lineIndex,
                lineCount = current?.lines?.size ?: 0,
                playback = playback,
                positionMs = positionMs,
                durationMs = durationMs,
                finished = finished,
                preparing = preparing,
                note = note,
            ),
        actions =
            VodPlayerActions(
                onTogglePause = {
                    if (playback is PlaybackState.Playing) container.player.pause() else container.player.resume()
                },
                onSeekBy = { delta ->
                    val target = (positionMs + delta).coerceIn(0L, maxOf(durationMs, 0L))
                    container.player.seekTo(target)
                    positionMs = target
                },
                onPrevious = {
                    current?.previousEpisode(lineIndex, episodeIndex)?.let {
                        playEpisodeAt(it.lineIndex, it.episodeIndex)
                    }
                },
                onNext = {
                    current?.nextEpisode(lineIndex, episodeIndex)?.let {
                        playEpisodeAt(it.lineIndex, it.episodeIndex)
                    }
                },
                onSelectEpisode = { index -> playEpisodeAt(lineIndex, index) },
                onRetry = { playEpisodeAt(lineIndex, episodeIndex) },
                onBack = {
                    // 离开就停：影视是长视频，挂在后台继续拉流既费流量又会让回直播时打架
                    container.player.stop()
                    onBack()
                },
            ),
        videoPlayer = (container.player as? VideoSurfaceHost)?.videoPlayer,
        seekStepMs = settings.seekIncrementMs,
    )
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
        // 继续观看跨类型，不受当前选中的类型 / 筛选影响（VOD-06）
        continueWatching = container.vodProgressRepository.recent(CONTINUE_LIMIT),
        sourceRows = rows,
        hasSource = vodSources.isNotEmpty(),
    )
}

/** 影视首页缓存有效期：半小时内直接读库，不为「切个板块」打一轮请求 */
private const val VOD_CACHE_TTL_MS = 30 * 60 * 1000L

/**
 * 给 Hero 上的直播条目找「现在在播什么」（HOME-04）。
 *
 * 只查候选的那两三个台，不查全量：节目单是按频道名归一后查的，一次一次走 Room，
 * 频道表有五千条时全量查完够用户看完一集。查不到就当没有 —— 节目单是加分项，不是前提。
 */
private suspend fun loadNowPlaying(
    container: AppContainer,
    channels: List<Channel>,
): Map<Long, String> {
    if (channels.isEmpty()) return emptyMap()
    val now = System.currentTimeMillis()
    return channels
        .mapNotNull { channel ->
            val title =
                runCatching { container.epgRepository.nowNext(channel.name, now).current?.title }
                    .getOrNull()
            title?.takeIf { it.isNotBlank() }?.let { channel.id to it }
        }.toMap()
}

private const val ARG_CHANNEL_ID = "channelId"
private const val ARG_LOADING = "loading"
private const val ARG_SOURCE_ID = "sourceId"
private const val ARG_SOURCE_KEY = "sourceKey"
private const val ARG_LINE_INDEX = "lineIndex"
private const val ARG_EPISODE_INDEX = "episodeIndex"
private const val ARG_START_MS = "startMs"

/**
 * 节目单对表间隔。
 *
 * 一分钟一次：节目单是分钟粒度的，一档节目至少半小时，一分钟的误差肉眼看不出来；
 * 再密就是在为「精确到秒」做无用功 —— 每次都要过一遍 Room 的时间窗查询。
 */
private const val EPG_REFRESH_MS = 60_000L

/** 影视进度轮询间隔：肉眼看着在走就够了，再密只是烧 CPU */
private const val POSITION_POLL_MS = 500L

/**
 * 进度落库间隔（VOD-06）。
 *
 * 10 秒：用户要的是「接着上次看」，10 秒的偏差在长视频里连一句台词都不到。
 * 更密除了多写闪存没有任何收益；更疏（比如 60 秒）则会在「看完一集就退出」时
 * 少记最后一分钟。
 */
private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L

/** 首页「继续观看」取多少条：一行放得下 5～6 张，多取一倍够往回翻 */
private const val CONTINUE_LIMIT = 12

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

private val vodPlayArguments =
    vodDetailArguments +
        listOf(
            navArgument(ARG_LINE_INDEX) {
                type = NavType.IntType
                defaultValue = 0
            },
            navArgument(ARG_EPISODE_INDEX) {
                type = NavType.IntType
                defaultValue = 0
            },
            navArgument(ARG_START_MS) {
                type = NavType.LongType
                defaultValue = 0L
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
