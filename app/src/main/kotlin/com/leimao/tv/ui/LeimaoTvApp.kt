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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.leimao.tv.core.data.UpdateCheckResult
import com.leimao.tv.core.data.VodDetailResult
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.TvRoute
import com.leimao.tv.core.model.appearance.FontScale
import com.leimao.tv.core.model.appearance.ThemeMode
import com.leimao.tv.core.model.cache.CacheUsage
import com.leimao.tv.core.model.epg.EpgProgramme
import com.leimao.tv.core.model.epg.currentAt
import com.leimao.tv.core.model.epg.nextAfter
import com.leimao.tv.core.model.network.NetworkSettings
import com.leimao.tv.core.model.release.ReleaseAsset
import com.leimao.tv.core.model.startup.StartDestination
import com.leimao.tv.core.model.vod.VodContinueItem
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodFavorite
import com.leimao.tv.core.model.vod.VodProgress
import com.leimao.tv.core.model.vod.VodProgressRules
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.core.model.vod.acceptsProgress
import com.leimao.tv.core.model.vod.episodeName
import com.leimao.tv.core.model.vod.episodeUrls
import com.leimao.tv.core.model.vod.lineSwitchTarget
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
import com.leimao.tv.core.player.TrackKind
import com.leimao.tv.core.player.VideoSurfaceHost
import com.leimao.tv.core.version.AppVersion
import com.leimao.tv.data.ApkInstaller
import com.leimao.tv.feature.iptv.PlayerScreen
import com.leimao.tv.feature.iptv.PlayerUiState
import com.leimao.tv.feature.iptv.ProgrammeLine
import com.leimao.tv.feature.iptv.groupChannels
import com.leimao.tv.feature.iptv.neighborOf
import com.leimao.tv.feature.settings.AppearanceSettingsScreen
import com.leimao.tv.feature.settings.CacheScreen
import com.leimao.tv.feature.settings.ChangelogScreen
import com.leimao.tv.feature.settings.DownloadProgress
import com.leimao.tv.feature.settings.LicenseScreen
import com.leimao.tv.feature.settings.NetworkSettingsScreen
import com.leimao.tv.feature.settings.PlaybackSettingsScreen
import com.leimao.tv.feature.settings.RefreshSettingsScreen
import com.leimao.tv.feature.settings.SettingsScreen
import com.leimao.tv.feature.settings.SourceManagerScreen
import com.leimao.tv.feature.settings.UpdateScreen
import com.leimao.tv.feature.settings.UpdateUiState
import com.leimao.tv.feature.vod.HeroItem
import com.leimao.tv.feature.vod.VodDetailScreen
import com.leimao.tv.feature.vod.VodDetailState
import com.leimao.tv.feature.vod.VodDetailStatus
import com.leimao.tv.feature.vod.VodHomeState
import com.leimao.tv.feature.vod.VodLineOption
import com.leimao.tv.feature.vod.VodPlayerActions
import com.leimao.tv.feature.vod.VodPlayerScreen
import com.leimao.tv.feature.vod.VodPlayerUiState
import com.leimao.tv.feature.vod.VodSourceRow
import com.leimao.tv.feature.vod.buildHeroItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

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
                BackHandler { navController.popBackStack() }
                SearchContent(
                    container = container,
                    onOpenChannel = { channelId -> navController.navigate(TvRoute.player(channelId)) },
                    onOpenVod = { sourceId, sourceKey ->
                        navController.navigate(TvRoute.vodDetail(sourceId, sourceKey))
                    },
                )
            }

            composable(TvRoute.SETTINGS) {
                BackHandler { navController.popBackStack() }
                val bootAutostart by
                    container.settings.bootAutostart.collectAsStateWithLifecycle(initialValue = false)
                SettingsScreen(
                    onOpenPlaybackSettings = { navController.navigate(TvRoute.PLAYBACK_SETTINGS) },
                    onOpenAppearanceSettings = {
                        navController.navigate(TvRoute.APPEARANCE_SETTINGS)
                    },
                    onOpenNetworkSettings = { navController.navigate(TvRoute.NETWORK_SETTINGS) },
                    onOpenRefreshSettings = { navController.navigate(TvRoute.REFRESH_SETTINGS) },
                    onOpenChangelog = { navController.navigate(TvRoute.CHANGELOG) },
                    onOpenUpdate = { navController.navigate(TvRoute.UPDATE) },
                    onOpenSourceManager = { navController.navigate(TvRoute.SOURCE_MANAGER) },
                    onOpenCache = { navController.navigate(TvRoute.CACHE) },
                    onOpenLicense = { navController.navigate(TvRoute.LICENSE) },
                    bootAutostart = bootAutostart,
                    onToggleBootAutostart = { enabled ->
                        container.applicationScope.launch {
                            container.settings.setBootAutostart(enabled)
                        }
                    },
                )
            }

            composable(TvRoute.APPEARANCE_SETTINGS) {
                BackHandler { navController.popBackStack() }
                AppearanceSettingsRoute(container = container)
            }

            composable(TvRoute.NETWORK_SETTINGS) {
                BackHandler { navController.popBackStack() }
                NetworkSettingsRoute(container = container)
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

            composable(TvRoute.UPDATE) {
                BackHandler { navController.popBackStack() }
                UpdateRoute(container = container)
            }

            composable(TvRoute.PLAYBACK_SETTINGS) {
                BackHandler { navController.popBackStack() }
                PlaybackSettingsRoute(container = container)
            }

            composable(TvRoute.REFRESH_SETTINGS) {
                BackHandler { navController.popBackStack() }
                RefreshSettingsRoute(container = container)
            }

            composable(TvRoute.CACHE) {
                BackHandler { navController.popBackStack() }
                CacheRoute(container = container)
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

/**
 * 存储与缓存（SET-05）的接线。
 *
 * 统计与清理都是磁盘 IO，所以都丢进协程：进页面先算一次（可能要扫几百个文件），
 * 清理期间 `clearing` 挡住重复点击 —— 连按两次会让 Coil 的 DiskCache 在清空中途被再清一次。
 */
@Composable
private fun CacheRoute(container: AppContainer) {
    var usage by remember { mutableStateOf<CacheUsage?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var clearedBytes by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { usage = container.cacheManager.usage() }

    CacheScreen(
        usage = usage,
        clearing = clearing,
        clearedBytes = clearedBytes,
        onClear = {
            if (!clearing) {
                clearing = true
                scope.launch {
                    // 清理前的数字优先用屏幕上那个（用户看到的那一版），没有再算一次
                    val before = usage?.totalBytes ?: container.cacheManager.usage().totalBytes
                    val after = container.cacheManager.clear()
                    usage = after
                    // 报「释放了多少」而不是「现在是 0」：Coil 会立刻开始重下当前页的台标，
                    // 清理后的大小往往不是 0，说成「已清空」是骗人
                    clearedBytes = (before - after.totalBytes).coerceAtLeast(0L)
                    clearing = false
                }
            }
        },
    )
}

/**
 * 检查更新（SET-07）的接线：查 → 下 → 装。
 *
 * 三步都留在页面作用域里，所以**离开这一页下载就会停**。这是有意的：
 * 更新包几十 MB，用户在下载中途按返回，多半是「不想装了」，而不是「希望它在后台继续占带宽」。
 * 已经下好的包留在 cacheDir，下次进页面查完再点下载会重下一份（同一版本，代价可以接受）。
 *
 * 进页面自动查一次：用户点进「检查更新」这件事本身就是「我想知道有没有新版」，
 * 再让他按一次「检查」等于把同一个意图问两遍。
 */
@Composable
private fun UpdateRoute(container: AppContainer) {
    var state by remember { mutableStateOf(UpdateUiState(currentVersion = AppVersion.CURRENT_VERSION)) }
    val scope = rememberCoroutineScope()

    fun check() {
        if (state.checking) return
        state = state.copy(checking = true, note = null)
        scope.launch {
            val result = container.updateChecker.check()
            state =
                state.copy(
                    checking = false,
                    result = result,
                    // 结论换了就把上一版留下的状态清掉：否则会拿着旧包的路径去「安装」
                    downloading = null,
                    downloadedPath = null,
                    needsInstallPermission = false,
                )
        }
    }

    fun download(asset: ReleaseAsset) {
        state = state.copy(downloading = DownloadProgress(bytesRead = 0L, totalBytes = asset.sizeBytes), note = null)
        scope.launch {
            container.apkInstaller.download(asset.downloadUrl, asset.name).collect { event ->
                when (event) {
                    is ApkInstaller.DownloadEvent.Progress ->
                        state =
                            state.copy(
                                downloading = DownloadProgress(event.bytesRead, event.totalBytes),
                            )

                    is ApkInstaller.DownloadEvent.Done ->
                        state =
                            state.copy(
                                downloading = null,
                                downloadedPath = event.file.absolutePath,
                                // 下完才知道装不装得上：权限是系统设置，只能到这里再问一次
                                needsInstallPermission = !container.apkInstaller.canInstall(),
                            )

                    is ApkInstaller.DownloadEvent.Failed ->
                        state = state.copy(downloading = null, note = event.reason)
                }
            }
        }
    }

    fun install() {
        val path = state.downloadedPath ?: return
        val launched = container.apkInstaller.install(File(path))
        state =
            state.copy(
                note =
                    if (launched) {
                        "已打开系统安装界面；装完重新打开应用就生效"
                    } else {
                        "没能打开安装界面，包可能不完整，重新下载一次"
                    },
            )
    }

    LaunchedEffect(Unit) { check() }

    // 从「允许安装未知应用」的设置页回来时重看一次开关：不然「去允许安装」会一直挂在按钮上
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state.downloadedPath != null) {
            state = state.copy(needsInstallPermission = !container.apkInstaller.canInstall())
        }
    }

    UpdateScreen(
        state = state,
        onCheck = { check() },
        onDownload = { (state.result as? UpdateCheckResult.Available)?.let { download(it.asset) } },
        onInstall = { install() },
        onOpenInstallSettings = {
            val opened = container.apkInstaller.openInstallPermissionSettings()
            if (!opened) {
                // 老盒子没有这个设置页，只能给路径让人自己找
                state = state.copy(note = "这台设备不能直接跳过去，请到「设置 → 安全 → 未知来源」手动允许")
            }
        },
    )
}

/**
 * 界面设置（SET-02）的接线。
 *
 * 两项都写 DataStore，但**不在这里等写入完成**：主题与字号的真实来源是 MainActivity 里
 * 那个 `collectAsStateWithLifecycle`，DataStore 一改 Flow 就发新值，整个界面重组。
 * 这里只负责把用户的点击落下去。
 */
@Composable
private fun AppearanceSettingsRoute(container: AppContainer) {
    val themeMode by
        container.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DEFAULT)
    val fontScale by
        container.settings.fontScale.collectAsStateWithLifecycle(initialValue = FontScale.DEFAULT)
    val scope = rememberCoroutineScope()

    AppearanceSettingsScreen(
        themeMode = themeMode,
        fontScale = fontScale,
        onThemeModeChange = { mode -> scope.launch { container.settings.setThemeMode(mode) } },
        onFontScaleChange = { scale -> scope.launch { container.settings.setFontScale(scale) } },
    )
}

/**
 * 网络设置（SET-04）的接线。
 *
 * 三项都只写 DataStore —— 真正生效在**下次启动**：容器构造时读一次，派生 OkHttpClient，
 * 之后播放内核与各仓储都拿着那一份。所以这里没有「应用」动作，改完就落库。
 */
@Composable
private fun NetworkSettingsRoute(container: AppContainer) {
    val settings by
        container.settings.networkSettings.collectAsStateWithLifecycle(initialValue = NetworkSettings())
    val scope = rememberCoroutineScope()

    NetworkSettingsScreen(
        settings = settings,
        onTimeoutChange = { profile -> scope.launch { container.settings.setTimeoutProfile(profile) } },
        onConcurrencyChange = { profile ->
            scope.launch { container.settings.setConcurrencyProfile(profile) }
        },
        onProxyChange = { proxy -> scope.launch { container.settings.setProxy(proxy) } },
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
    // 开机自启动（SET-08）：内嵌设置页开关的数据源
    val bootAutostart by
        container.settings.bootAutostart.collectAsStateWithLifecycle(initialValue = false)

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
    var vodTag by rememberSaveable { mutableStateOf<String?>(null) }
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
            vodTag = null
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
    LaunchedEffect(mode, vodTypeName, vodYear, vodArea, vodTag, vodRevision) {
        if (mode != AppMode.VOD) return@LaunchedEffect
        vodState = loadVodHomeState(container, vodType, vodYear, vodArea, vodTag)
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
        // 开机自启动（SET-08）：首页内嵌设置页与独立设置路由读的是同一份 DataStore
        bootAutostart = bootAutostart,
        onToggleBootAutostart = { enabled ->
            container.applicationScope.launch { container.settings.setBootAutostart(enabled) }
        },
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
                    // 标签是按类型统计的：换类型后旧标签可能根本不存在于新类型里，
                    // 留着它会让「这个栏目暂时没有内容」凭空出现
                    vodTag = null
                    // 有导航项的类型顺带同步导航高亮；短剧 / 纪录片没有导航项，保持当前项不动
                    type.toHomeSection()?.let { mapped ->
                        pickedSectionName = mapped.name
                        scope.launch { container.settings.setLastSection(mapped.name) }
                    }
                },
                onSelectYear = { vodYear = it },
                onSelectArea = { vodArea = it },
                onSelectTag = { vodTag = it },
                onSelectMedia = openVod,
                // 直接进播放页而不是先回详情页：用户点的就是「接着看」，
                // 中间插一页详情等于让他再确认一次自己刚做过的决定
                onResume = resumeVod,
                // 看过的（VOD-07）点进去是**详情页**而不是直接起播：
                // 那一集已经看完了，用户点它多半是「再看一遍」，从哪一集开始由他决定
                onOpenHistory = { item ->
                    navController.navigate(
                        TvRoute.vodDetail(item.progress.sourceId, item.progress.sourceKey),
                    )
                },
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
        onOpenUpdate = { navController.navigate(TvRoute.UPDATE) },
        onOpenLicense = { navController.navigate(TvRoute.LICENSE) },
        onOpenCache = { navController.navigate(TvRoute.CACHE) },
        onOpenPlaybackSettings = { navController.navigate(TvRoute.PLAYBACK_SETTINGS) },
        onOpenAppearanceSettings = { navController.navigate(TvRoute.APPEARANCE_SETTINGS) },
        onOpenNetworkSettings = { navController.navigate(TvRoute.NETWORK_SETTINGS) },
        onOpenRefreshSettings = { navController.navigate(TvRoute.REFRESH_SETTINGS) },
        onExit = onExitApp,
        // 主页导航项「搜索」与菜单键进来的搜索页是**同一块内容**：两处各写一套必然走样
        searchContent = {
            SearchContent(
                container = container,
                onOpenChannel = { channelId -> navController.navigate(TvRoute.player(channelId)) },
                onOpenVod = { sourceId, sourceKey ->
                    navController.navigate(TvRoute.vodDetail(sourceId, sourceKey))
                },
                // 焦点归主页导航统管，内容区不再抢（RULES §6.2：每页只有一个初始焦点）
                autoFocus = false,
            )
        },
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

    // 音轨 / 字幕（PLAY-07）：内核在起播与换台后自己刷这张表，界面只需要跟着读
    val tracks by container.player.tracks.collectAsStateWithLifecycle()

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
                tracks = tracks,
            ),
        surfaceHost = container.player as? VideoSurfaceHost,
        onSwitchChannel = { delta ->
            val next = neighborOf(channels, current?.id, delta)
            if (next != null) scope.launch { startChannel(next.id) }
        },
        onSelectChannel = { id -> scope.launch { startChannel(id) } },
        onSwitchLine = { delta -> container.player.switchLine(delta) },
        onSelectTrack = { id -> container.player.selectTrack(id) },
        onDisableTracks = { kind -> container.player.disableTracks(kind) },
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

    // 进入源管理页时的落点快照（§6.6）。用它判断「这次改动是不是让应用从『没得播』
    // 变成了『有得播』」—— 只有这种从无到有的跨越才接管导航（加完源直达播放）。
    // 日常的导入 / 新增 / 全部刷新只更新数据：把用户甩去播放页等于打断操作，
    // 他们还要看导入结果、继续调下一个源（2026-10-25 实测：导入 live.txt 被甩到
    // 一个失效流画面上，导入是否成功都看不到）。
    var landingAtEntry by remember { mutableStateOf<StartDestination?>(null) }
    LaunchedEffect(Unit) {
        landingAtEntry = container.startupResolver.resolve()
    }

    SourceManagerScreen(
        sourceRepository = container.sourceRepository,
        refresher = container.sourceRefresher,
        epgImporter = container.epgImporter,
        epgRepository = container.epgRepository,
        vodRefresher = container.vodCatalogRefresher,
        healthChecker = container.sourceHealthChecker,
        onBack =
            if (isFirstRun) {
                // 无源引导页按返回 = 退出确认（§6.6 降级 2：没有可播内容，不设主页中转）
                { onExitApp() }
            } else {
                { navController.popBackStack() }
            },
        onSourcesChanged = {
            // 源变了 → 只在「从『没得播』跨越到『有得播』」时才重算落点并跳转：
            // 无源引导页（AddSource）/ 只知道在加载（Player channelId=null）→ 解析出了可播频道；
            // 本来就能播的应用只是在更新数据，留在源管理页让用户看完结果（§6.6 + 用户实测反馈）
            scope.launch {
                val next = container.startupResolver.resolve()
                val entry = landingAtEntry
                val becamePlayable =
                    when {
                        entry == null -> false
                        next !is StartDestination.Player -> false
                        entry !is StartDestination.Player -> true // 从无源引导跨越到可播
                        else -> entry.channelId == null && next.channelId != null
                    }
                if (becamePlayable) navController.replaceStackWith(next.toRoute())
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
        // 进度只在本地库，进页面顺手读一次，不和回源串行。
        // 已看完的记录**不当进度用**（VOD-07）：那部片重新点进来是要从头看的，
        // 首页「继续观看」里也不会再出现它
        val saved = container.vodProgressRepository.find(sourceId, sourceKey)?.takeIf { !it.finished }
        // 收藏态同理，一次读库两件事，不必各开一个 effect
        val favorited =
            runCatching { container.vodFavoriteRepository.isFavorite(sourceId, sourceKey) }
                .getOrDefault(false)
        state =
            state.copy(
                summary = container.mediaRepository.find(sourceId, sourceKey) ?: state.summary,
                status = VodDetailStatus.LOADING,
                note = null,
                progress = saved,
                favorited = favorited,
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
        onToggleFavorite = {
            // 详情还没回来时用首页快照兜底：收藏只需要标题/海报/类型，
            // 这些在快照里都有，不该因为回源失败就收藏不了
            val summary = state.detail?.summary ?: state.summary
            if (summary != null) {
                val next = !state.favorited
                state = state.copy(favorited = next)
                container.applicationScope.launch {
                    if (next) {
                        container.vodFavoriteRepository.add(
                            VodFavorite(
                                sourceId = summary.sourceId,
                                sourceKey = summary.sourceKey,
                                title = summary.name,
                                poster = summary.pic,
                                type = summary.type,
                                year = summary.year,
                                area = summary.area,
                                remarks = summary.remarks,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        container.vodFavoriteRepository.remove(summary.sourceId, summary.sourceKey)
                    }
                }
            }
        },
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
    val tracks by container.player.tracks.collectAsStateWithLifecycle()
    val settings by
        container.settings.playbackSettings.collectAsStateWithLifecycle(
            initialValue = PlaybackSettings.DEFAULT,
        )

    // 起播即用记忆速度（PLAY-05）：速度是「播放的属性」，进播放页就该生效，
    // 不等用户按一次面板 —— 否则「记忆」只对这一集之后的操作生效，这次还是原速
    LaunchedEffect(settings.playbackSpeed) {
        container.player.setSpeed(settings.playbackSpeed)
    }

    var detail by remember { mutableStateOf<VodDetail?>(null) }
    var headers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var lineIndex by remember { mutableIntStateOf(initialLine) }
    var episodeIndex by remember { mutableIntStateOf(initialEpisode) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var preparing by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    /** 收藏态（VOD-07）：播放页里也能收，不必退回详情页 */
    var favorited by remember { mutableStateOf(false) }

    LaunchedEffect(sourceId, sourceKey) {
        favorited =
            runCatching { container.vodFavoriteRepository.isFavorite(sourceId, sourceKey) }
                .getOrDefault(false)
    }

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
     * 这里**不删**任何东西，也不写「看完了」。收尾（看完 / 从头看）由 [markFinished]
     * 单独负责 —— 让「写」和「收尾」共用一个入口，就会出现「自动连播写下了下一集，
     * 几毫秒后一个迟到的『已看完』把它盖成 finished」这种时序事故。
     * 少一个分支，少一种竞态。
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

    /**
     * 标记「这部片看完了」（VOD-07）。
     *
     * 不走 [persistProgress]：那边的 `shouldKeep` 会把「看到 95% 以上」判成不该记，
     * 而这里要写的恰恰就是那种记录 —— 只是它的目的是「看过的」，不是「继续观看」。
     *
     * 也不删行：删掉之后用户再也找不到自己看过什么。`vod_progress` 一张表两个用途，
     * 靠 `finished` 分流（见 `VodProgressDao` 的注释）。
     */
    fun markFinished() {
        val current = detail
        container.applicationScope.launch {
            if (current == null) {
                // 连详情都没拉到就退出了：这时候没有可写的标题快照，留给「继续观看」反而是条废记录
                container.vodProgressRepository.delete(sourceId, sourceKey)
                return@launch
            }
            container.vodProgressRepository.save(
                VodProgress(
                    sourceId = sourceId,
                    sourceKey = sourceKey,
                    lineIndex = lineIndex,
                    episodeIndex = episodeIndex,
                    // 位置写满：历史卡上不画进度条，但「看到片尾」这件事要如实留下
                    positionMs = maxOf(positionMs, durationMs),
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                    finished = true,
                ),
                title = current.summary.name,
                poster = current.summary.pic,
            )
        }
    }

    /** 收藏 / 取消收藏（VOD-07）：先改界面再落库，遥控器上不能等一次写盘 */
    fun toggleFavorite() {
        val current = detail ?: return
        val next = !favorited
        favorited = next
        container.applicationScope.launch {
            if (next) {
                container.vodFavoriteRepository.add(
                    VodFavorite(
                        sourceId = sourceId,
                        sourceKey = sourceKey,
                        title = current.summary.name,
                        poster = current.summary.pic,
                        type = current.summary.type,
                        year = current.summary.year,
                        area = current.summary.area,
                        remarks = current.summary.remarks,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                container.vodFavoriteRepository.remove(sourceId, sourceKey)
            }
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
        // 起播位置就是新位置。不能一律清零：换线时是带着原进度起播的（startAt > 0），
        // 清零后用户在缓冲阶段按返回，onDispose 会把进度写成 0 —— 下次「继续观看」从头开始
        positionMs = startAt
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

    /**
     * 换线路（VOD-10）。
     *
     * 按集号对齐、**保留当前位置**：用户换线是因为这条承载不行（卡 / 糊 / 断流），
     * 不是为了看下一集。对不齐时落到目标线路的最后一集（见 `lineSwitchTarget`），
     * 并在顶部说明一句 —— 否则用户只看到集号变了，以为按错了。
     *
     * 为什么 UI 层不自己「失败后自动换线」：内核的降级链本身就是「同一集在所有线路上的
     * 地址依次尝试」，走到 Failed 说明那一集真的都不行，重起一遍播放器只会更慢。
     * 这里补的是内核做不到的那一半 —— 让线路**可见、可换**。
     */
    fun switchLine(target: Int) {
        val current = detail ?: return
        val next = current.lineSwitchTarget(episodeIndex, target) ?: return
        playEpisodeAt(next.lineIndex, next.episodeIndex, startAt = positionMs)
        if (next.clamped) {
            note = "这条线路只有 ${next.episodeIndex + 1} 集，已停在最后一集"
        }
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
    // 看到片尾再退出标成「看完了」，而不是删掉：它还要当「看过的」历史用（VOD-07）
    DisposableEffect(sourceId, sourceKey) {
        onDispose {
            if (VodProgressRules.isFinished(positionMs, durationMs)) {
                markFinished()
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
            // 全剧看完（VOD-07）：置标记而不是删行 —— 它会变成「看过的」里的一条
            markFinished()
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
                lines =
                    current?.lines?.mapIndexed { index, vodLine ->
                        VodLineOption(index = index, name = vodLine.name, episodeCount = vodLine.episodes.size)
                    }.orEmpty(),
                playback = playback,
                positionMs = positionMs,
                durationMs = durationMs,
                finished = finished,
                preparing = preparing,
                note = note,
                favorited = favorited,
                tracks = tracks,
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
                onSelectLine = { target -> switchLine(target) },
                onToggleFavorite = { toggleFavorite() },
                onSelectTrack = { id -> container.player.selectTrack(id) },
                onDisableText = { container.player.disableTracks(TrackKind.TEXT) },
                onRetry = { playEpisodeAt(lineIndex, episodeIndex) },
                onBack = {
                    // 离开就停：影视是长视频，挂在后台继续拉流既费流量又会让回直播时打架
                    container.player.stop()
                    onBack()
                },
            ),
        surfaceHost = container.player as? VideoSurfaceHost,
        seekStepMs = settings.seekIncrementMs,
        speed = settings.playbackSpeed,
        // 长按快进的临时速度：只改内核不落库，松手回记忆值
        onApplySpeed = { container.player.setSpeed(it) },
        // 倍速面板的选择：落库记忆（PLAY-05），下次起播直接沿用
        onRememberSpeed = { newSpeed ->
            container.player.setSpeed(newSpeed)
            container.applicationScope.launch {
                container.settings.setPlaybackSettings(settings.copy(playbackSpeed = newSpeed))
            }
        },
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
    tag: String?,
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
        // 标签池只跟类型走，不随年份/地区缩小：切年份之后标签行不该突然少一半，
        // 那会让人以为「2024 年没有科幻片」
        tags = media.tagsOf(type),
        selectedTag = tag,
        items = media.byType(type, year, area, tag),
        recent = media.recent(),
        // 继续观看跨类型，不受当前选中的类型 / 筛选影响（VOD-06）
        continueWatching = container.vodProgressRepository.recent(CONTINUE_LIMIT),
        // 看过的与继续观看同源互补（VOD-07）：一部片只会出现在其中一个列表里
        history = container.vodProgressRepository.history(CONTINUE_LIMIT),
        favorites = container.vodFavoriteRepository.list(FAVORITE_LIMIT),
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

/** 首页「我的收藏」取多少条：收藏页是这几行里最可能往后翻的，多给一些 */
private const val FAVORITE_LIMIT = 24

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
