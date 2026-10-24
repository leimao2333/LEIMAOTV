package com.leimao.tv.core.model.vod

/**
 * 一集在「源数据里的」位置（VOD-05）。
 *
 * 用 `(线路, 集)` 而不是单个下标：跨线路的集之间没有可比性 ——
 * 「线路甲的第三集」和「线路乙的第三集」可能连着两部不同的片子。
 */
data class VodEpisodePos(
    val lineIndex: Int,
    val episodeIndex: Int,
)

/**
 * 播完这一集之后该播什么。
 *
 * 规则只有一条，但必须写死在一处：**当前线路还有下一集就顺着走；到头了才换下一条线路的第一集**。
 *
 * 为什么换线路时要回到第一集而不是「线路乙的同一集」：不同线路的集数不一定对齐
 * （甲线路 40 集、乙线路 39 集，「第 40 集」在乙线路根本不存在），按集号硬对齐会跳空。
 * 从第一集重新开始虽然笨，但绝不会跳错。
 *
 * 返回 null 表示真的没有下一集了 —— 调用方据此显示「已播完」，而不是空转。
 */
fun VodDetail.nextEpisode(
    lineIndex: Int,
    episodeIndex: Int,
): VodEpisodePos? {
    val line = lines.getOrNull(lineIndex)
    if (line == null) {
        // 线路越界（换过源 / 数据被改）：退回第一条线路的第一集，总比什么都不做强
        return lines.firstOrNull()?.episodes?.takeIf { it.isNotEmpty() }?.let { VodEpisodePos(0, 0) }
    }
    if (episodeIndex + 1 < line.episodes.size) {
        return VodEpisodePos(lineIndex, episodeIndex + 1)
    }
    for (next in (lineIndex + 1)..lines.lastIndex) {
        if (lines[next].episodes.isNotEmpty()) return VodEpisodePos(next, 0)
    }
    return null
}

/**
 * 上一集在哪。
 *
 * 与 [nextEpisode] 不对称：往回退时**不跨线路** —— 用户在第二集按「上一集」，
 * 期望的是第一集，而不是跳到上一条线路的最后一集（那可能是完全不同的一集）。
 */
fun VodDetail.previousEpisode(
    lineIndex: Int,
    episodeIndex: Int,
): VodEpisodePos? {
    if (episodeIndex <= 0) return null
    val line = lines.getOrNull(lineIndex) ?: return null
    return if (episodeIndex <= line.episodes.lastIndex) {
        VodEpisodePos(lineIndex, episodeIndex - 1)
    } else {
        null
    }
}

/** 这一集的名字，取不到时给个能读的兜底（源里 `name` 可能是空串） */
fun VodDetail.episodeName(
    lineIndex: Int,
    episodeIndex: Int,
): String {
    val name = lines.getOrNull(lineIndex)?.episodes?.getOrNull(episodeIndex)?.name.orEmpty()
    return name.ifBlank { "第 ${episodeIndex + 1} 集" }
}

/** 这一集的播放地址与线路名。地址为空串的条目在解析阶段已被丢掉，这里再兜一层 */
fun VodDetail.episodeAt(
    lineIndex: Int,
    episodeIndex: Int,
): VodEpisode? = lines.getOrNull(lineIndex)?.episodes?.getOrNull(episodeIndex)

/**
 * 这条进度在这份详情里还成立吗（VOD-06）。
 *
 * 源会变：一部剧上周 40 集，这周可能只剩 30 集；线路也可能少了一条。
 * 拿一条指向「第 40 集」的进度去起播，得到的是一句「这一集没有播放地址」——
 * 用户完全不知道该怎么办。所以进详情页时先验一遍：不成立就当没进度，
 * 从头开始总好过点进去撞墙。
 */
fun VodDetail.acceptsProgress(progress: VodProgress): Boolean {
    if (progress.lineIndex < 0 || progress.episodeIndex < 0) return false
    val episodes = lines.getOrNull(progress.lineIndex)?.episodes ?: return false
    return progress.episodeIndex < episodes.size
}

/**
 * 同一集在各条线路上的地址，供内核做降级链（PLAY-02）。
 *
 * 注意**不是**「一条线路的所有集」：内核的 `lines` 语义是「同一份内容的多条备选地址」，
 * 失败时它换的是承载地址，不换内容。把不同集塞进去，一旦降级就会跳到另一集。
 *
 * 只有「其他线路恰好也有同一集号」时才作为备选 —— 集数不对齐时宁可只有一条地址，
 * 也不能在失败后跳到别处。
 */
fun VodDetail.episodeUrls(
    lineIndex: Int,
    episodeIndex: Int,
): List<String> {
    val primary = episodeAt(lineIndex, episodeIndex)?.url?.takeIf { it.isNotBlank() } ?: return emptyList()
    val alternatives =
        lines.indices
            .filter { it != lineIndex }
            .mapNotNull { index ->
                lines[index].episodes.getOrNull(episodeIndex)?.url?.takeIf { it.isNotBlank() }
            }

    return (listOf(primary) + alternatives).distinct()
}

/** 换线之后落在哪一集。[clamped] 为 true 表示目标线路集数不够，被压到了最后一集。 */
data class VodLineSwitch(
    val lineIndex: Int,
    val episodeIndex: Int,
    val clamped: Boolean,
)

/**
 * 换到另一条线路时该播哪一集（VOD-10）。
 *
 * 两条规矩：
 * - **按集号对齐**，不是按「第几条地址」。用户切线路是想同一次观看换个承载，
 *   不是为了看别的集。
 * - **对不齐时压到目标线路的最后一集**，不越界也不回到第 1 集：甲线路 40 集、
 *   乙线路 39 集时，从第 40 集切到乙线路，落在乙的第 39 集是最近的等价位置；
 *   弹回第 1 集等于把用户的进度清零。
 *
 * 返回 null 表示这条线路压根不是可选项（下标越界 / 一集都没有），调用方按「没换」处理。
 */
fun VodDetail.lineSwitchTarget(
    episodeIndex: Int,
    targetLine: Int,
): VodLineSwitch? {
    val target = lines.getOrNull(targetLine) ?: return null
    val lastIndex = target.episodes.lastIndex
    if (lastIndex < 0 || episodeIndex < 0) return null
    val clampedIndex = episodeIndex.coerceAtMost(lastIndex)
    return VodLineSwitch(
        lineIndex = targetLine,
        episodeIndex = clampedIndex,
        clamped = clampedIndex != episodeIndex,
    )
}
