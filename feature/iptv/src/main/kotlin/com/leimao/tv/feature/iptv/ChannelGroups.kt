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
