package com.leimao.tv.core.model.epg

import com.leimao.tv.core.model.playlist.normalizeChannelName

/**
 * 节目单（EPG）的领域模型（LIVE-20）。
 *
 * 为什么把它放在 `core:model`：解析器要产出它、数据库要存它、播放页要读它，
 * 三方共用同一份结构，才不会出现「解析器叫 startTime、数据库叫 startAt」这种对不上的错。
 */
data class EpgChannel(
    /** XMLTV 里的 `id`，只在节目单内部有意义，跟播放列表的 tvg-id 不是一回事 */
    val id: String,
    val name: String,
)

/** 一条节目。时间统一用 epoch 毫秒，跨时区比较才不会出错。 */
data class EpgProgramme(
    val channelId: String,
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val description: String? = null,
) {
    fun isLiveAt(millis: Long): Boolean = millis >= startAt && millis < endAt
}

/**
 * 合并后的节目单频道：**同类合并的产物**。
 *
 * 一份聚合 EPG 里，同一个台经常有好几个 `id`（不同来源各写一份：`CCTV1`、`cctv-1`、
 * `CCTV1 高清`）。它们指的都是同一个台，节目内容也常常重复。合并规则见 [mergeEpgGuide]。
 */
data class MergedEpgChannel(
    val name: String,
    /** [normalizeEpgName] 的结果，匹配播放列表频道时用的就是它 */
    val normalizedName: String,
    val ids: List<String>,
)

/** 合并后的整份节目单：频道是合并过的，节目按「归一化名称」索引 */
data class EpgGuide(
    val channels: List<MergedEpgChannel>,
    val programmes: Map<String, List<EpgProgramme>>,
) {
    /** 节目单里有多少条节目（导入后给用户看的数字） */
    val programmeCount: Int get() = programmes.values.sumOf { it.size }

    fun isEmpty(): Boolean = channels.isEmpty() || programmes.isEmpty()
}

/**
 * 节目单专用的频道名归一化：在播放列表那套之上，**再去掉中间的分隔符**。
 *
 * 为什么必须多这一步：节目单作者写 `CCTV-1`、源作者写 `CCTV1` 是最常见的错位，
 * 播放列表那套归一化只去掉**结尾**的连字符（`CCTV-1 - 高清` → `cctv-1`），
 * 中间那个连字符会留下来，两边就永远匹配不上。实测的 `e.xml` 里 CCTV 系列全是这种写法。
 */
fun normalizeEpgName(raw: String): String =
    normalizeChannelName(raw).filterNot { it in "-_·|. \u3000" }

/**
 * 同类合并：把名字归一后相同的频道并成一个，节目按时间合并去重。
 *
 * 三条规则，缺一条都会出问题：
 *
 * 1. **按归一化名称分组**，用的是 [normalizeEpgName]（播放列表那套 + 去中间分隔符）——
 *    两套归一化就会「频道列表能合并、节目单却对不上」；
 * 2. **节目按 (开始时间, 标题) 去重**：同一档节目在多个 id 下重复出现是常态，
 *    不去重的话播放页的「下一档」会连着蹦出两个一模一样的名字；
 * 3. **按开始时间排序**：后面的「当前/下一档」查询用二分，乱序就查不出东西。
 *
 * 合并后展示名取该组**第一条**的名字（聚合 EPG 通常把最规范的写在前面），
 * 不取最长的也不取最短的 —— 那种"聪明"选择在真实数据里往往挑中最丑的那个。
 */
fun mergeEpgGuide(
    channels: List<EpgChannel>,
    programmes: List<EpgProgramme>,
): EpgGuide {
    if (channels.isEmpty()) return EpgGuide(emptyList(), emptyMap())

    val knownIds = channels.mapTo(mutableSetOf()) { it.id }

    // 只保留能对上频道的节目：对不上的要么是 EPG 自己的脏数据，要么是半份文件
    val byId = LinkedHashMap<String, MutableList<EpgProgramme>>()
    programmes.forEach { programme ->
        if (programme.channelId in knownIds && programme.endAt > programme.startAt) {
            byId.getOrPut(programme.channelId) { mutableListOf() }.add(programme)
        }
    }

    val groups = LinkedHashMap<String, MutableList<EpgChannel>>()
    channels.forEach { channel ->
        if (channel.name.isBlank()) return@forEach
        groups.getOrPut(normalizeEpgName(channel.name)) { mutableListOf() }.add(channel)
    }

    val mergedChannels =
        groups.map { (normalizedName, members) ->
            MergedEpgChannel(
                name = members.first().name,
                normalizedName = normalizedName,
                ids = members.map { it.id }.distinct(),
            )
        }

    val mergedProgrammes =
        mergedChannels.associate { channel ->
            val all =
                channel.ids
                    .flatMap { byId[it].orEmpty() }
                    .sortedBy { it.startAt }
                    .distinctBy { it.startAt to it.title }
            channel.normalizedName to all
        }

    return EpgGuide(channels = mergedChannels, programmes = mergedProgrammes)
}

/** 某个频道在 [atMillis] 正在播的节目（已合并、已排序，故用二分） */
fun EpgGuide.currentProgramme(channelName: String, atMillis: Long): EpgProgramme? {
    val list = programmes[normalizeEpgName(channelName)] ?: return null

    // 找到第一条「结束时间晚于此刻」的节目，它就是当前或即将播出的那一档
    var low = 0
    var high = list.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (list[mid].endAt > atMillis) {
            found = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return list.getOrNull(found)?.takeIf { it.isLiveAt(atMillis) }
}

/** 紧接着当前节目的下一档（当前没有节目时返回 null，不去猜） */
fun EpgGuide.nextProgramme(channelName: String, atMillis: Long): EpgProgramme? {
    val list = programmes[normalizeEpgName(channelName)] ?: return null
    val current = currentProgramme(channelName, atMillis) ?: return null
    val index = list.indexOf(current)
    return list.getOrNull(index + 1)
}

/**
 * 从数据库查回来的一小段节目（已按开始时间排序）里取当前档。
 *
 * 与 [EpgGuide.currentProgramme] 分开，是因为数据库那条路只取时间窗内的几条，
 * 不必把整份节目单建进内存 —— 一万多条节目在低端盒子上不是小数目。
 */
fun List<EpgProgramme>.currentAt(millis: Long): EpgProgramme? = firstOrNull { it.isLiveAt(millis) }

/** 下一档：开始时间晚于此刻的第一条 */
fun List<EpgProgramme>.nextAfter(millis: Long): EpgProgramme? = firstOrNull { it.startAt > millis }
