package com.leimao.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.leimao.tv.AppContainer
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.TvRoute
import com.leimao.tv.core.model.startup.StartDestination
import com.leimao.tv.feature.iptv.PlayerScreen
import com.leimao.tv.feature.search.SearchScreen
import com.leimao.tv.feature.settings.ChangelogScreen
import com.leimao.tv.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * 导航图。M0 的验收标准就是这张图能走通：
 * 播放页（起点）→ 返回 → 主页 → 切模式 → 返回 → 退出确认。
 *
 * 页面树是**以播放页为根的单枢纽结构**（RULES §6.4）：
 * 播放页只往主页去，主页只往退出确认去，两者不互为返回目标。
 */
@Composable
fun LeimaoTvApp(
    container: AppContainer,
    startDestination: StartDestination,
    onExitApp: () -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val startRoute = remember(startDestination) { startDestination.toRoute() }

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
            AddSourceScreen(onExit = onExitApp)
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
            SettingsScreen(onOpenChangelog = { navController.navigate(TvRoute.CHANGELOG) })
        }

        composable(TvRoute.CHANGELOG) {
            BackHandler { navController.popBackStack() }
            ChangelogScreen()
        }
    }
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
        onOpenChangelog = { navController.navigate(TvRoute.CHANGELOG) },
        onExit = onExitApp,
    )
}

/**
 * 播放页的数据接线：按 channelId 取频道 → 起播 → 记住「上次看的台」。
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
    var channel by remember(channelId) { mutableStateOf<Channel?>(null) }

    LaunchedEffect(channelId) {
        val found = channelId?.let { id -> container.channelRepository.find(id) }
        channel = found
        if (found != null) {
            container.player.play(found.streamUrl)
            // 记住「刚看了这个台」：启动落点优先级 #1 读的就是这条记录（§6.6）
            container.historyRepository.touch(found.id, System.currentTimeMillis())
        }
    }

    BackHandler { onBackToHome() }

    PlayerScreen(
        channelTitle = channel?.name,
        loadingChannels = loadingChannels,
        playback = playback,
        onOpenSourceManager = onOpenSourceManager,
        onRetry = { channel?.let { current -> container.player.play(current.streamUrl) } },
    )
}

private const val ARG_CHANNEL_ID = "channelId"
private const val ARG_LOADING = "loading"

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
