package com.leimao.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.layout.SafeAreaContainer
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.feature.iptv.ChannelBrowser
import com.leimao.tv.feature.iptv.groupChannels
import com.leimao.tv.feature.search.SearchScreen
import com.leimao.tv.feature.settings.SettingsScreen
import com.leimao.tv.feature.vod.VodHomeScreen

/**
 * 主页左侧导航项。
 *
 * **直播模式只留「直播」「搜索」**：电影/电视剧/动漫/综艺/体育都是影视板块的栏目，
 * 摆在直播模式的导航里会让"看电视"这件事变得比它该有的样子复杂。
 * 它们没有消失 —— 直播模式的搜索照样能搜到这些类型（见 [IPTV_ALLOWED_TYPES]），
 * 只是不提供"从导航直接跳进栏目"的路径，要浏览栏目请先切到影视模式。
 *
 * [mode] 非空表示「点它要切到哪个板块」—— 影视栏目本来就在影视板块里，
 * 从别处点进去必须顺带把模式切过去，否则用户会看到一个空内容区还以为坏了。
 *
 * [visibleIn] 决定这一项出现在哪些模式的导航里。不写成"直播模式隐藏哪些"，
 * 是因为以后加模式时，漏写等于默认可见 —— 那正是这次要避免的错。
 */
enum class HomeSection(
    val label: String,
    val mode: AppMode?,
    val visibleIn: Set<AppMode>,
) {
    LIVE("直播", AppMode.LIVE, setOf(AppMode.LIVE)),
    MOVIE("电影", AppMode.VOD, setOf(AppMode.VOD)),
    SERIES("电视剧", AppMode.VOD, setOf(AppMode.VOD)),
    ANIME("动漫", AppMode.VOD, setOf(AppMode.VOD)),
    VARIETY("综艺", AppMode.VOD, setOf(AppMode.VOD)),
    SPORTS("体育", AppMode.VOD, setOf(AppMode.VOD)),
    SEARCH("搜索", null, setOf(AppMode.LIVE, AppMode.VOD)),
    SETTINGS("设置", null, setOf(AppMode.LIVE, AppMode.VOD)),
    ;

    companion object {
        /** 某个模式下该显示的导航项，顺序取枚举声明顺序 */
        fun forMode(mode: AppMode): List<HomeSection> = entries.filter { mode in it.visibleIn }
    }
}

/**
 * 主页 —— 唯一的枢纽（RULES §6.4）。
 *
 * 它**不是启动落点**：冷启动直接进播放页，只有按返回才到这里。
 * 模式开关放在导航栏最顶部而不是内容区，是因为它的语义是「换一整套内容」，
 * 不是「换一个筛选条件」，放在内容区会让人以为只影响当前列表。
 */
@Composable
fun HomeScreen(
    mode: AppMode,
    section: HomeSection,
    onSectionChange: (HomeSection) -> Unit,
    onModeChange: (AppMode) -> Unit,
    onOpenPlayer: () -> Unit,
    channels: List<Channel>,
    favoriteIds: Set<Long>,
    onOpenChannel: (Long) -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExitConfirm by rememberSaveable { mutableStateOf(false) }
    val firstNavFocus = remember { FocusRequester() }
    val stayFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstNavFocus.requestFocus() }
    LaunchedEffect(showExitConfirm) { if (showExitConfirm) stayFocus.requestFocus() }

    // 主页按返回 = 退出确认，不回到播放页，避免「播放 ↔ 主页」来回打转（§6.4）
    BackHandler { showExitConfirm = true }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        SafeAreaContainer {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier
                            .width(232.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                ) {
                    ModeSwitch(
                        mode = mode,
                        onToggle = {
                            val next = if (mode == AppMode.LIVE) AppMode.VOD else AppMode.LIVE
                            // 切模式必须把当前分区一起带过去，否则会出现「模式是影视、内容还是直播」的错位
                            val nextSection = if (next == AppMode.LIVE) HomeSection.LIVE else HomeSection.MOVIE
                            onSectionChange(nextSection)
                            onModeChange(next)
                        },
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    HomeSection.forMode(mode).forEachIndexed { index, item ->
                        NavItem(
                            section = item,
                            selected = section == item,
                            modifier = if (index == 0) Modifier.focusRequester(firstNavFocus) else Modifier,
                            onClick = {
                                onSectionChange(item)
                                item.mode?.let { target -> if (target != mode) onModeChange(target) }
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.width(28.dp))

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                ) {
                    ContentArea(
                        section = section,
                        mode = mode,
                        onOpenPlayer = onOpenPlayer,
                        channels = channels,
                        favoriteIds = favoriteIds,
                        onOpenChannel = onOpenChannel,
                        onOpenSourceManager = onOpenSourceManager,
                        onOpenChangelog = onOpenChangelog,
                        onOpenLicense = onOpenLicense,
                        onOpenPlaybackSettings = onOpenPlaybackSettings,
                    )
                }
            }
        }

        if (showExitConfirm) {
            ExitConfirmDialog(
                stayFocusRequester = stayFocus,
                onStay = { showExitConfirm = false },
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun ContentArea(
    section: HomeSection,
    mode: AppMode,
    onOpenPlayer: () -> Unit,
    channels: List<Channel>,
    favoriteIds: Set<Long>,
    onOpenChannel: (Long) -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
) {
    // 分区与模式必须自洽：模式是内容的「大前提」，分区只是它下面的入口。
    // 两者不一致时以模式为准 —— 否则切模式会出现「影视模式下还显示直播分区」的错位
    // （这个错位是在模拟器上走查时真看到的，不是假设）。
    val effectiveSection =
        when {
            section.mode == null -> section
            section.mode == mode -> section
            mode == AppMode.LIVE -> HomeSection.LIVE
            else -> HomeSection.MOVIE
        }

    when (effectiveSection) {
        HomeSection.LIVE ->
            LiveSectionContent(
                mode = mode,
                channels = channels,
                favoriteIds = favoriteIds,
                onOpenPlayer = onOpenPlayer,
                onOpenChannel = onOpenChannel,
            )
        HomeSection.SEARCH ->
            SearchScreen(
                scope = if (mode == AppMode.LIVE) SearchScope.IPTV else SearchScope.VOD,
                // 焦点归主页导航统管，内容区不再抢（§6.2：每个页面只有一个明确的初始焦点）
                autoFocus = false,
            )
        HomeSection.SETTINGS ->
            SettingsScreen(
                onOpenPlaybackSettings = onOpenPlaybackSettings,
                onOpenChangelog = onOpenChangelog,
                onOpenSourceManager = onOpenSourceManager,
                onOpenLicense = onOpenLicense,
                autoFocus = false,
            )
        else -> VodHomeScreen(autoFocus = false)
    }
}

/**
 * 直播板块的内容区（HOME-06 / LIVE-10）。
 *
 * 这里是「主动挑台」的入口：分组 + 频道列表。启动时不经过它（启动直达播放，§6.6），
 * 所以它可以慢一点、可以一次把 5000 条频道都列出来，不占冷启动的预算。
 */
@Composable
private fun LiveSectionContent(
    mode: AppMode,
    channels: List<Channel>,
    favoriteIds: Set<Long>,
    onOpenPlayer: () -> Unit,
    onOpenChannel: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (mode == AppMode.LIVE) "直播" else "直播（影视模式下也可切回）",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                if (channels.isEmpty()) {
                    "还没有频道。去「源管理」添加一个订阅地址，或在调试包里首次启动自动种一条演示源。"
                } else {
                    "共 ${channels.size} 个频道 · ${channels.map { it.groupName }.distinct().size} 个分组"
                },
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        FocusableCard(onClick = onOpenPlayer) {
            Text(
                text = if (mode == AppMode.LIVE) "回到播放页（接着播上次那个台）" else "去直播播放页",
                style = TvText.current.label,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (channels.isNotEmpty()) {
            ChannelBrowser(
                groups = remember(channels) { groupChannels(channels) },
                selectedChannelId = null,
                onChannelClick = { channel -> onOpenChannel(channel.id) },
                favoriteIds = favoriteIds,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeSwitch(
    mode: AppMode,
    onToggle: () -> Unit,
) {
    FocusableCard(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = if (mode == AppMode.LIVE) "直播 ▸ 影视" else "影视 ▸ 直播",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "当前：${if (mode == AppMode.LIVE) "直播" else "影视"}　按 OK 切换",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NavItem(
    section: HomeSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = section.label,
            style = TvText.current.label,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

@Composable
private fun ExitConfirmDialog(
    stayFocusRequester: FocusRequester,
    onStay: () -> Unit,
    onExit: () -> Unit,
) {
    Dialog(onDismissRequest = onStay) {
        Column(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "退出磊毛TV？",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "下次打开会直接回到你上次看的那个台。",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row {
                FocusableCard(onClick = onStay, modifier = Modifier.focusRequester(stayFocusRequester)) {
                    Text(text = "留在应用", style = TvText.current.label)
                }
                Spacer(modifier = Modifier.width(16.dp))
                FocusableCard(onClick = onExit) {
                    Text(text = "退出", style = TvText.current.label)
                }
            }
        }
    }
}
