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
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.TvRoute
import com.leimao.tv.core.model.epg.EpgProgramme
import com.leimao.tv.core.model.epg.currentAt
import com.leimao.tv.core.model.epg.nextAfter
import com.leimao.tv.core.model.startup.StartDestination
import com.leimao.tv.core.network.BasicAuth
import com.leimao.tv.core.network.RequestHeaders
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

    // 主页每次回到前台都刷新一次列表：用户可能刚在源管理里加完源
    LaunchedEffect(Unit) {
        channels = container.channelRepository.listFromEnabledSources()
        favoriteIds = container.favoriteRepository.favoriteChannelIds().toSet()
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

private const val ARG_CHANNEL_ID = "channelId"
private const val ARG_LOADING = "loading"

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
