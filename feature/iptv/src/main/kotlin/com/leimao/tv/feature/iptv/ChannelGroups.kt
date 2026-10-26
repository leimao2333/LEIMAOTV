package com.leimao.tv.feature.iptv

import com.leimao.tv.core.model.Channel
import com.leimao.tv.core.model.CityProvince

/** 一个分组下的频道集合。分组与频道都在仓库层一次查好，UI 不做二次聚合（§7 列表性能）。 */
data class ChannelGroup(
    val name: String,
    val channels: List<Channel>,
)

/**
 * 把「全量频道」切成**固定的六个分类**（LIVE-29）。
 *
 * ```
 * 最近观看（虚拟组，由 ChannelBrowser 插在最前）
 * 央视频道 / 省份地方 / 戏曲频道 / 音乐频道 / 广播频道 / 其他频道
 * ```
 *
 * ## 为什么不再按上游的 group-title 分
 *
 * 聚合订阅里同一个台会在六个源里带着六种不同的组名（「央视频道」「CCTV」「央视高清」
 * 「中央台」…），真机上有 **104 个分组**：左边分组栏要翻好几屏，而且同一个台散在
 * 三四个组里。固定分类是「用户找台时的脑内目录」，不是源的元数据 ——
 * 源的组名只作为**判定依据**，不再直接当分组名。
 *
 * ## 判定顺序即优先级
 *
 * 广播 → 戏曲 → 音乐 → 央视 → 省份地方 → 其他：
 * 「CCTV-11 戏曲」要进戏曲而不是央视，「音乐广播」要进广播而不是音乐，
 * 「CCTV-15 音乐」要进音乐而不是央视。所以窄类别必须先判。
 *
 * 保持传入顺序（已按频道号排好）：分组只是展示切片，顺序与「上下键换台」共用一套，
 * 不会出现「列表里第一个台和按上键跳到的不一致」这种只有用户能发现的错位。
 */
fun groupChannels(channels: List<Channel>): List<ChannelGroup> {
    val buckets = LinkedHashMap<String, MutableList<Channel>>()
    CANONICAL_GROUPS.forEach { buckets[it] = mutableListOf() }
    channels.forEach { channel ->
        buckets.getValue(categoryOf(channel)).add(channel)
    }
    // 空分类不出现：一个「戏曲频道」点进去 0 个台，比没有这一栏更让人怀疑坏了
    val ordered =
        buckets.mapNotNull { (name, items) ->
            items.takeIf { it.isNotEmpty() }?.let { ChannelGroup(name = name, channels = it) }
        }

    // 编号在这里**重排**（LIVE-30）。
    //
    // 上游给的号是「各源播放列表里的序号」：六个源合并后既有大量重号（六个源都有 001），
    // 又有空洞（源里跳号）。播放页按号换台用的是 `firstOrNull { it.number == n }`，
    // 重号意味着「按 1 可能跳到另一个源的同号台」，空洞意味着「按 5 什么也没发生」。
    // 按最终展示顺序重排成 1..N，编号才和用户看到的那一列一一对应。
    var next = 1
    return ordered.map { group ->
        group.copy(channels = group.channels.map { it.copy(number = next++) })
    }
}

/** 固定分类的名字与顺序。顺序就是左侧分组栏从上到下的顺序 */
val CANONICAL_GROUPS: List<String> =
    listOf(
        GROUP_CCTV,
        GROUP_LOCAL,
        GROUP_MOVIE,
        GROUP_SPORTS,
        GROUP_OPERA,
        GROUP_MUSIC,
        GROUP_NETWORK,
        GROUP_RADIO,
        GROUP_OTHER,
    )

private const val GROUP_CCTV = "央视频道"

private const val GROUP_LOCAL = "省份地方"

private const val GROUP_MOVIE = "电影频道"

private const val GROUP_SPORTS = "体育频道"

private const val GROUP_OPERA = "戏曲频道"

private const val GROUP_MUSIC = "音乐频道"

private const val GROUP_NETWORK = "网络直播"

private const val GROUP_RADIO = "广播频道"

private const val GROUP_OTHER = "其他频道"

/**
 * 一个频道该进哪一类。
 *
 * 判定同时看**组名**与**台名**：源里「CCTV-11 戏曲」的组名常常只是「央视频道」，
 * 只看组名会把它归到央视；而「梨园」这种台名没有「戏曲」二字，只能靠组名认出来。
 */
internal fun categoryOf(channel: Channel): String {
    val group = channel.groupName
    val name = channel.name
    return when {
        // 判定顺序 = 优先级：窄类别必须排在央视前面，
        // 否则「CCTV-6 电影」「CCTV-5 体育」都会被央视抢走
        hits(group, name, RADIO_WORDS) -> GROUP_RADIO
        hits(group, name, NETWORK_WORDS) -> GROUP_NETWORK
        hits(group, name, MOVIE_WORDS) -> GROUP_MOVIE
        hits(group, name, SPORTS_WORDS) -> GROUP_SPORTS
        hits(group, name, OPERA_WORDS) -> GROUP_OPERA
        hits(group, name, MUSIC_WORDS) -> GROUP_MUSIC
        hits(group, name, CCTV_WORDS) -> GROUP_CCTV
        isLocal(group, name) -> GROUP_LOCAL
        else -> GROUP_OTHER
    }
}

private fun hits(
    group: String,
    name: String,
    words: List<String>,
): Boolean = words.any { group.contains(it, ignoreCase = true) || name.contains(it, ignoreCase = true) }

/**
 * 省份地方：卫视、带省名的组、或台名以城市名开头（[CityProvince] 反查）。
 *
 * 反查只对**台名开头**生效（「上虞新闻综合」），不会把「浙江卫视」这种已经带省名的漏掉 ——
 * 它先被 `卫视` 命中。
 */
private fun isLocal(
    group: String,
    name: String,
): Boolean {
    if (group.contains("卫视") || name.contains("卫视")) return true
    if (group.contains("地方") || group.contains("城市")) return true
    if (PROVINCE_WORDS.any { group.contains(it) }) return true
    return CityProvince.provinceOf(name) != null
}

/** 广播：电台/广播/FM。排在最前，「音乐广播」「戏曲广播」都该进广播 */
private val RADIO_WORDS = listOf("广播", "电台", "FM", "Radio")

/** 戏曲：含曲艺（相声/评书）—— 用户找「戏曲」时这两类是一起找的 */
private val OPERA_WORDS =
    listOf("戏曲", "曲艺", "京剧", "越剧", "黄梅", "豫剧", "评书", "相声", "梆子", "昆曲", "梨园")

private val MUSIC_WORDS = listOf("音乐", "MV", "演唱会", "歌曲")

/**
 * 电影频道。
 *
 * 只认「电影/影院/影视」这类**频道定位**词：不能拿「片」去匹配，
 * 否则「纪录片」「动画片」「片花」全会被吸过来。
 */
private val MOVIE_WORDS = listOf("电影", "影院", "影视", "剧场")

private val SPORTS_WORDS =
    listOf("体育", "赛事", "足球", "篮球", "网球", "奥运", "NBA", "CBA", "高尔夫", "台球", "电竞")

/**
 * 网络直播。
 *
 * 不认单独一个「直播」——几乎每个台的组名里都可能带「直播」，
 * 那样会把大半个频道表吸进这一组。只认「网络直播 / 直播平台」这种**整体定位**词。
 */
private val NETWORK_WORDS =
    listOf("网络直播", "直播平台", "网络频道", "网络电视", "主播", "网红", "摄像头")

private val CCTV_WORDS = listOf("央视", "CCTV", "中央", "CGTN", "CGTN", "总台")

/** 组名里出现这些词就算地方台（源里常见「北京频道」「广东频道」这种写法） */
private val PROVINCE_WORDS =
    listOf(
        "北京", "上海", "天津", "重庆", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
        "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南", "广东",
        "广西", "海南", "四川", "贵州", "云南", "西藏", "陕西", "甘肃", "青海", "宁夏",
        "新疆", "香港", "澳门", "台湾",
    )

/**
 * 换台用：按当前频道在全量列表里的位置，取相邻的一个。
 *
 * 到头**翻页**而不是停住：直播场景里「按了上键却什么都没发生」跟「按键坏了」没法区分，
 * 用户只会以为遥控器失灵。翻页是电视机的一贯行为，也让「第一个台往上」有去处。
 *
 * 只有一个台时返回 null —— 否则换台会变成原地重播，把正在看的画面打断。
 */
fun neighborOf(
    channels: List<Channel>,
    currentId: Long?,
    delta: Int,
): Channel? {
    if (channels.isEmpty()) return null
    if (channels.size == 1) return null
    val index = channels.indexOfFirst { it.id == currentId }
    if (index < 0) return channels.first()
    return channels[(index + delta).mod(channels.size)]
}

/**
 * 列表内即时过滤（LIVE-16）：按名称包含关键词筛频道，空关键词原样返回。
 *
 * 匹配不区分大小写；分组的壳保留，但**没有命中频道的分组会被整个去掉** ——
 * 否则过滤后列表里躺着一堆「0 台」的空分组，比不过滤还难用。
 * 与跨库搜索（SEARCH 段）是两回事：这里只在这份已经加载的频道列表里筛，不查库、不联网。
 */
fun filterGroups(
    groups: List<ChannelGroup>,
    query: String,
): List<ChannelGroup> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return groups
    return groups
        .mapNotNull { group ->
            val matched = group.channels.filter { it.name.contains(keyword, ignoreCase = true) }
            if (matched.isEmpty()) null else ChannelGroup(group.name, matched)
        }
}
