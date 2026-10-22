package com.leimao.tv.core.data

/**
 * 自动刷新周期（LIVE-07）。
 *
 * 「关闭 / 6 小时 / 12 小时 / 24 小时」四档。档位用「小时数」表达，比毫秒更贴近用户预期；
 * [WorkManager] 的最小周期是 15 分钟，这里最短 6 小时，远在阈值之上，不存在「配了却跑不起来」。
 *
 * [OFF] 不是「永不刷新」，只是不跑定时任务 —— 用户仍可手动单源刷新，启动时也仍会
 * 走一次异步刷新（那是「保证列表不过期」的兜底，与「要不要定时刷」是两件事）。
 */
enum class RefreshInterval(
    val label: String,
    val hours: Long?,
) {
    OFF(label = "关闭", hours = null),
    HOURS_6(label = "每 6 小时", hours = 6),
    HOURS_12(label = "每 12 小时", hours = 12),
    HOURS_24(label = "每 24 小时", hours = 24),
    ;

    companion object {
        val DEFAULT = HOURS_24

        fun fromName(name: String?): RefreshInterval =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
