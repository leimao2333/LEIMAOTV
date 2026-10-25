package com.leimao.tv.core.model.playlist

import com.leimao.tv.core.model.catchup.Catchup

/**
 * 合并后的频道：一个频道 + 它的若干条线路（LIVE-13）。
 *
 * 为什么要有这一层：同一个台在不同源里常出现多条地址（备用线路），
 * 若当成多个频道，用户在列表里会看到一排同名项，换台也会在同一个台上反复横跳。
 */
data class MergedChannel(
    val name: String,
    val groupName: String,
    val tvgId: String?,
    val logoUrl: String?,
    val numberInPlaylist: Int?,
    /** 线路地址，按源文件出现顺序去重；第一条为首选 */
    val lines: List<String>,
    /** 回看能力（LIVE-24）：取第一条声明了能力的线路，见 [toMergedChannel] */
    val catchup: Catchup? = null,
) {
    val primaryLine: String get() = lines.first()
}

/**
 * 同名频道合并（LIVE-13）。
 *
 * 规则（顺序即优先级）：
 *
 * 1. **`tvg-id` 相同时一定合并** —— 这是源作者给出的稳定标识，比名字可靠；
 * 2. 没有 `tvg-id` 的条目，按[归一化名称][normalizeChannelName]匹配到**有 `tvg-id` 的同名组**，
 *    并入该组（宁可并进有标识的那组，也不要反向把标识弄丢）；
 * 3. 两边都没有 `tvg-id` 且归一化名称相同 → 合并；
 * 4. 其余一律视为不同频道。
 *
 * 合并后的展示信息取「有 `tvg-id` 的那条」优先，其次取第一条 —— 台标与分组通常
 * 只有带标识的条目才填得全。
 */
fun mergeChannels(channels: List<ParsedChannel>): List<MergedChannel> {
    if (channels.isEmpty()) return emptyList()

    // 有序分组：链表保序，最后按首次出现位置输出，用户的列表顺序才不会因为合并而乱跳
    val groups = LinkedHashMap<String, MutableList<ParsedChannel>>()

    channels.forEach { channel ->
        val key = mergeKeyOf(channel)
        groups.getOrPut(key) { mutableListOf() }.add(channel)
    }

    // 第 2 步：把「只有名字、没有 tvg-id」的组并进同名的「有 tvg-id」组。
    // 同名但 tvg-id 不同的两个组（源作者自己标错）不在此互相合并 —— 保持「id 优先」，
    // 只让无标识的那组认第一个遇到的标识组，避免两个都对的台被硬拧成一个。
    val namedGroupsWithTvgId = mutableMapOf<String, String>()
    groups.forEach { (key, members) ->
        if (key.startsWith(TVG_PREFIX)) {
            namedGroupsWithTvgId.putIfAbsent(normalizeChannelName(members.first().name), key)
        }
    }

    val merged = LinkedHashMap<String, MutableList<ParsedChannel>>()
    groups.forEach { (key, members) ->
        val targetKey =
            if (key.startsWith(TVG_PREFIX)) {
                key
            } else {
                namedGroupsWithTvgId[key.removePrefix(NAME_PREFIX)] ?: key
            }
        merged.getOrPut(targetKey) { mutableListOf() }.addAll(members)
    }

    return merged.values.map { members -> members.toMergedChannel() }
}

/**
 * 名称归一（LIVE-13 的「名称归一」）。
 *
 * 只做**保守**归一：去空白、转小写、去掉结尾的清晰度后缀与分隔符。
 * 刻意不做「去括号内容」「拼音相似度」这类激进匹配 —— 把 CCTV-5 和 CCTV-5+ 并成一个台，
 * 比不合并更糟：用户换台时会被送到错的频道，且看不出为什么。
 */
fun normalizeChannelName(raw: String): String {
    var name =
        raw
            .replace('\u3000', ' ')
            .filterNot { it.isWhitespace() }
            .lowercase()

    // 结尾的分隔符先去掉，否则「CCTV-1 - 高清」会留下一个孤零零的连字符
    name = name.trimEnd('-', '_', '.', '·', '|')

    var changed = true
    while (changed) {
        changed = false
        for (suffix in QUALITY_SUFFIXES) {
            if (name.length > suffix.length && name.endsWith(suffix)) {
                name = name.removeSuffix(suffix).trimEnd('-', '_', '.', '·', '|')
                changed = true
            }
        }
    }
    return name
}

private fun MutableList<ParsedChannel>.toMergedChannel(): MergedChannel {
    // 展示信息优先取「有 tvg-id」的那条：台标 / 分组通常只有带标识的条目才填得全
    val primary = firstOrNull { !it.tvgId.isNullOrBlank() } ?: first()
    val group =
        primary.groupName.takeIf { it != UNGROUPED }
            ?: firstOrNull { it.groupName != UNGROUPED }?.groupName
            ?: UNGROUPED

    return MergedChannel(
        name = primary.name.ifBlank { first().name },
        groupName = group,
        tvgId = primary.tvgId,
        logoUrl = primary.logoUrl ?: firstNotNullOfOrNull { it.logoUrl },
        numberInPlaylist = primary.numberInPlaylist ?: firstNotNullOfOrNull { it.numberInPlaylist },
        lines = map { it.url }.distinct(),
        // 回看能力取「第一条声明了能力的线路」：同一台的多条线路里往往只有一条支持回看，
        // 取 primary 会漏掉它（primary 是按 tvg-id 挑的，跟能力无关）
        catchup = primary.catchup ?: firstNotNullOfOrNull { it.catchup },
    )
}

private fun mergeKeyOf(channel: ParsedChannel): String =
    channel.tvgId?.takeIf { it.isNotBlank() }?.let { "$TVG_PREFIX$it" }
        ?: "$NAME_PREFIX${normalizeChannelName(channel.name)}"

private const val TVG_PREFIX = "id:"
private const val NAME_PREFIX = "name:"

/**
 * 结尾的清晰度标记。合并不该因为「同一个台有高清/标清两路」而分裂成两个频道。
 * 只列**常见且含义确定**的：像「备份」「备用」这类词有时是同名不同台，不在此列。
 */
private val QUALITY_SUFFIXES: List<String> =
    listOf(
        "高清", "超清", "标清", "蓝光", "原画",
        "hd", "fhd", "uhd", "4k", "8k", "1080p", "720p", "576p", "480p",
    )
