package com.leimao.tv.core.model.playlist

/**
 * 刷新时「库里已有什么」的快照。只带合并判断要用的字段 —— 计划函数不该依赖数据库实体。
 */
data class ExistingChannel(
    val id: Long,
    val name: String,
    val tvgId: String?,
    val number: Int,
    val groupName: String,
    val logoUrl: String?,
    val primaryUrl: String,
)

/** 对一条频道的写入动作。 */
sealed interface ChannelWrite {
    /** 新频道：必须 INSERT 拿新 id */
    data class Insert(val merged: MergedChannel, val number: Int) : ChannelWrite

    /** 已有频道：UPDATE 原行，id 不变 —— 收藏与历史才挂得住 */
    data class Update(
        val id: Long,
        val merged: MergedChannel,
        val number: Int,
        /** 字段是否真的变了；没变就跳过写，减少一次数据库事务 */
        val changed: Boolean,
    ) : ChannelWrite
}

data class ChannelWritePlan(
    val writes: List<ChannelWrite>,
    /** 源里已经没有的频道，刷新后要删掉 */
    val deleteIds: List<Long>,
    /** 下一个可用的频道号，调用方后续新增频道时接着用 */
    val nextNumber: Int,
)

/**
 * 刷新落库的**计划**（LIVE-05 的「单源刷新」核心）。
 *
 * 为什么不直接「删光重插」：favorite / history 通过外键挂在 channel 上，删了频道就等于
 * 删了用户的收藏和观看记录 —— 一次刷新把用户数据清空，是最不可接受的缺陷类型。
 * 所以刷新必须**按 id 原地更新**，只有真正消失的频道才删。
 *
 * 匹配顺序：`tvg-id` → 名称完全相同 → 名称归一后相同。前者最可靠；
 * 后两者覆盖「源作者改了清晰度后缀」这类改名，让 id 尽量稳住。
 */
fun planChannelWrites(
    existing: List<ExistingChannel>,
    merged: List<MergedChannel>,
): ChannelWritePlan {
    val untouched = existing.associateBy { it.id }.toMutableMap()
    val writes = mutableListOf<ChannelWrite>()

    // 已占用的频道号集合：**包含仍被保留的已有频道**，所以不能只看 untouched。
    // 这里不追求「填空」（把删掉的频道号让给新频道），因为频道号对用户是记忆锚点，
    // 变来变去比空着几个更难用
    val occupiedNumbers = existing.map { it.number }.toMutableSet()
    var nextNumber = (existing.maxOfOrNull { it.number } ?: 0) + 1

    merged.forEach { channel ->
        val matched = findMatch(channel, untouched.values)
        if (matched == null) {
            val requested = channel.numberInPlaylist?.takeIf { it !in occupiedNumbers }
            val assigned = requested ?: nextNumber
            occupiedNumbers += assigned
            if (assigned >= nextNumber) nextNumber = assigned + 1
            writes += ChannelWrite.Insert(merged = channel, number = assigned)
        } else {
            untouched.remove(matched.id)
            writes +=
                ChannelWrite.Update(
                    id = matched.id,
                    merged = channel,
                    number = matched.number,
                    changed = hasChanged(matched, channel),
                )
        }
    }

    return ChannelWritePlan(
        writes = writes,
        deleteIds = untouched.keys.toList(),
        nextNumber = nextNumber,
    )
}

private fun findMatch(channel: MergedChannel, candidates: Collection<ExistingChannel>): ExistingChannel? {
    val byTvgId =
        channel.tvgId?.takeIf { it.isNotBlank() }?.let { id ->
            candidates.firstOrNull { it.tvgId == id }
        }
    if (byTvgId != null) return byTvgId

    candidates.firstOrNull { it.name == channel.name }?.let { return it }

    val normalized = normalizeChannelName(channel.name)
    return candidates.firstOrNull { normalizeChannelName(it.name) == normalized }
}

private fun hasChanged(existing: ExistingChannel, merged: MergedChannel): Boolean =
    existing.name != merged.name ||
        existing.groupName != merged.groupName ||
        existing.logoUrl != merged.logoUrl ||
        existing.primaryUrl != merged.primaryLine ||
        existing.tvgId != merged.tvgId
