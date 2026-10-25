package com.leimao.tv.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 健康检查结果的会话内缓存（SET-09）。
 *
 * 为什么是内存而不是数据库 / DataStore：
 * 1. **不落 Room**：健康状态是「刚才那一下探测」的结论，不是源的属性。为它加一列要迁移，
 *    而迁移是有风险的 —— 一次纯 UI 改动不该带来这种代价（SET-09 的验收要求写明了这一点）。
 * 2. **不落 DataStore**：落盘会让它跨重启存活，用户下次打开看到「失效」，
 *    其实那是三天前的结论，反而误导。内存缓存的生命周期就是「这次打开应用」。
 *
 * 存放在 `AppContainer` 里而不是页面里：离开源管理页再回来，状态还在（用户刚查完、
 * 去看了眼频道、再回来改源，这时候状态不该凭空回到「未测」）。
 */
class SourceHealthStore {
    /** 一条源的健康结论 + 什么时候测的。时间用于卡片上的「3 分钟前」 */
    data class Entry(
        val health: SourceHealthChecker.Health,
        val checkedAt: Long,
    )

    private val _entries = MutableStateFlow<Map<Long, Entry>>(emptyMap())

    /** 按 sourceId 索引。UI 只读这一份，不自己维护副本 */
    val entries: StateFlow<Map<Long, Entry>> = _entries.asStateFlow()

    /** 记一批探测结果（一次「健康检查」的全部结论） */
    fun record(
        healths: List<SourceHealthChecker.Health>,
        at: Long = System.currentTimeMillis(),
    ) {
        if (healths.isEmpty()) return
        _entries.update { current ->
            current + healths.associate { it.sourceId to Entry(it, at) }
        }
    }

    /** 删源时把它那一格清掉，否则同 id 复用时会出现「新源带着旧源的体检结论」 */
    fun forget(sourceId: Long) {
        _entries.update { it - sourceId }
    }
}
