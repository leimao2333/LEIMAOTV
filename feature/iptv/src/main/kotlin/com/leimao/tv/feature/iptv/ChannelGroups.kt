package com.leimao.tv.feature.iptv

import com.leimao.tv.core.model.Channel

/** 一个分组下的频道集合。分组与频道都在仓库层一次查好，UI 不做二次聚合（§7 列表性能）。 */
data class ChannelGroup(
    val name: String,
    val channels: List<Channel>,
)

/**
 * 把「全量频道」按分组切成若干组，保持传入顺序（已按频道号排好）。
 *
 * 为什么不在 SQL 里 GROUP BY 再拼：分组只是展示用的切片，保持原顺序能保证
 * 「列表第一个」「上下键换台」与分组展示用的是同一套顺序，不会出现「列表里第一个台
 * 和按上键跳到的不一致」这种只有用户能发现的错位。
 */
fun groupChannels(channels: List<Channel>): List<ChannelGroup> =
    channels
        .groupBy { it.groupName }
        .map { (name, items) -> ChannelGroup(name = name, channels = items) }

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
