package com.leimao.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.layout.SafeAreaContainer
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.ContentType
import com.leimao.tv.core.model.SearchScope
import com.leimao.tv.core.model.vod.VodSummary
import com.leimao.tv.feature.iptv.ChannelBrowser
import com.leimao.tv.feature.iptv.ChannelLogo
import com.leimao.tv.feature.iptv.groupChannels
import com.leimao.tv.feature.search.SearchScreen
import com.leimao.tv.feature.settings.SettingsScreen
import com.leimao.tv.feature.vod.VodHomeScreen
import com.leimao.tv.feature.vod.VodHomeState

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

/** 导航项 → 影视类型。没有对应导航项的（搜索/设置/直播）返回 null。 */
internal fun HomeSection.toVodType(): ContentType? =
    when (this) {
        HomeSection.MOVIE -> ContentType.MOVIE
        HomeSection.SERIES -> ContentType.TV_SERIES
        HomeSection.ANIME -> ContentType.ANIME
        HomeSection.VARIETY -> ContentType.VARIETY
        HomeSection.SPORTS -> ContentType.SPORTS
        else -> null
    }

/** 影视类型 → 导航项，仅限有导航项的五类；短剧 / 纪录片返回 null */
internal fun ContentType.toHomeSection(): HomeSection? =
    HomeSection.entries.firstOrNull { it.toVodType() == this }

/**
 * 影视首页的四个交互出口。
 *
 * 打包成一个对象而不是四个平铺参数：HomeScreen 的参数列表已经长到「再加就要数不清」，
 * 而它们本来就是同一块内容区的配套回调，打包后调用处也不容易漏传。
 */
data class VodHomeActions(
    val onSelectType: (ContentType) -> Unit,
    val onSelectYear: (Int?) -> Unit,
    val onSelectArea: (String?) -> Unit,
    val onSelectMedia: (VodSummary) -> Unit,
)

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
    recentChannels: List<Channel>,
    vodState: VodHomeState,
    vodActions: VodHomeActions,
    onOpenChannel: (Long) -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    onOpenRefreshSettings: () -> Unit,
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
                        recentChannels = recentChannels,
                        vodState = vodState,
                        vodActions = vodActions,
                        onOpenChannel = onOpenChannel,
                        onOpenSourceManager = onOpenSourceManager,
                        onOpenChangelog = onOpenChangelog,
                        onOpenLicense = onOpenLicense,
                        onOpenPlaybackSettings = onOpenPlaybackSettings,
                        onOpenRefreshSettings = onOpenRefreshSettings,
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
    recentChannels: List<Channel>,
    vodState: VodHomeState,
    vodActions: VodHomeActions,
    onOpenChannel: (Long) -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    onOpenRefreshSettings: () -> Unit,
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
                recentChannels = recentChannels,
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
                onOpenRefreshSettings = onOpenRefreshSettings,
                onOpenChangelog = onOpenChangelog,
                onOpenSourceManager = onOpenSourceManager,
                onOpenLicense = onOpenLicense,
                autoFocus = false,
            )
        // 影视板块的五个栏目（电影/电视剧/动漫/综艺/体育）共用同一个首页，
        // 「当前是哪一类」由 vodState.selectedType 决定 —— 导航点击时在 HomeRoute 里同步过去。
        // 这里不再用 effectiveSection 覆盖类型：短剧 / 纪录片没有导航项，
        // 一覆盖就会被弹回电影，用户点了等于没点。
        else ->
            VodHomeScreen(
                state = vodState,
                onSelectType = vodActions.onSelectType,
                onSelectYear = vodActions.onSelectYear,
                onSelectArea = vodActions.onSelectArea,
                onSelectMedia = vodActions.onSelectMedia,
                onOpenSourceManager = onOpenSourceManager,
                autoFocus = false,
            )
    }
}

/**
 * 直播板块的内容区（HOME-06 / LIVE-10）。
 *
 * 这里是「主动挑台」的入口：分组 + 频道列表。启动时不经过它（启动直达播放，§6.6），
 * 所以它可以慢一点、可以一次把 5000 条频道都列出来，不占冷启动的预算。
 *
 * HOME-06 在完整列表上方加了三块快捷入口，都是「一眼看到、一键直达」：
 * - 收藏频道：用户主动收藏过的台，横向一行；
 * - 最近观看：history 表里最近的台，横向一行；
 * - 分组快捷入口：所有分组名一行，点中后下方完整列表切到该分组。
 */
@Composable
private fun LiveSectionContent(
    mode: AppMode,
    channels: List<Channel>,
    favoriteIds: Set<Long>,
    recentChannels: List<Channel>,
    onOpenPlayer: () -> Unit,
    onOpenChannel: (Long) -> Unit,
) {
    // 收藏频道 = 全量里被收藏的那批。不做独立查询：收藏 id 已经拿到，在已加载的全量里切一刀即可，
    // 且天然按频道顺序排列（收藏列表该按「列表顺序」而不是「收藏时间」排，用户找台更顺手）。
    val favoriteChannels = remember(channels, favoriteIds) { channels.filter { it.id in favoriteIds } }
    // 分组快捷入口要跳转的目标分组。null = 未触发；点中后传给 ChannelBrowser，它切完即用。
    var pendingGroup by rememberSaveable { mutableStateOf<String?>(null) }

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

        if (channels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            QuickRow(
                title = "收藏频道",
                emptyHint = "还没有收藏。播放时打开控制条点「收藏」，或在下方列表里选台。",
                items = favoriteChannels,
                itemKey = { it.id },
            ) { channel ->
                ChannelChip(channel = channel, isFavorite = true, onClick = { onOpenChannel(channel.id) })
            }

            Spacer(modifier = Modifier.height(12.dp))

            QuickRow(
                title = "最近观看",
                emptyHint = "还没有观看记录。随便打开一个台就有了。",
                items = recentChannels,
                itemKey = { it.id },
            ) { channel ->
                ChannelChip(
                    channel = channel,
                    isFavorite = channel.id in favoriteIds,
                    onClick = { onOpenChannel(channel.id) },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            QuickRow(
                title = "分组",
                emptyHint = null,
                items = remember(channels) { groupChannels(channels) },
                itemKey = { it.name },
            ) { group ->
                GroupChip(name = group.name, count = group.channels.size) { pendingGroup = group.name }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        if (channels.isNotEmpty()) {
            ChannelBrowser(
                groups = remember(channels) { groupChannels(channels) },
                selectedChannelId = null,
                onChannelClick = { channel -> onOpenChannel(channel.id) },
                favoriteIds = favoriteIds,
                pendingGroup = pendingGroup,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 一个快捷区块：标题 + 横向一行卡片。内容为空时用一句提示顶替，而不是空白 ——
 * 否则用户会以为「这个区块坏了没加载出来」。
 */
@Composable
private fun <T> QuickRow(
    title: String,
    emptyHint: String?,
    items: List<T>,
    itemKey: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (items.isEmpty() && emptyHint != null) {
            Text(
                text = emptyHint,
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = items, key = itemKey) { item ->
                    itemContent(item)
                }
            }
        }
    }
}

/**
 * 快捷区块里的一个频道卡：台标 + 名字（+ ★ 收藏标记）。固定宽度，横向排开。
 * 点击直达播放 —— 这是快捷入口的唯一动作，不搞二级菜单。
 */
@Composable
private fun ChannelChip(
    channel: Channel,
    isFavorite: Boolean,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier.width(200.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(logoUrl = channel.logoUrl)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = channel.name,
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isFavorite) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "★", style = TvText.current.hint, color = MATERIAL_GOLD)
            }
        }
    }
}

/**
 * 分组快捷入口卡：分组名 + 台数。点中后把下方完整列表切到该分组（HOME-06）。
 */
@Composable
private fun GroupChip(
    name: String,
    count: Int,
    onClick: () -> Unit,
) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier.width(160.dp),
    ) {
        Column {
            Text(
                text = name,
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$count 个台",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 收藏星标色。与频道列表/抽屉里的 ★ 同色（LIVE-14）。 */
private val MATERIAL_GOLD = Color(0xFFE6B422)
