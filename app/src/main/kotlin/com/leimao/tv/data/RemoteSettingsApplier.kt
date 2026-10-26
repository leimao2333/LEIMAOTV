package com.leimao.tv.data

import com.leimao.tv.core.data.RefreshInterval
import com.leimao.tv.core.database.repository.RemoteSettings

/**
 * 把备份里的 `settings` 段落成真实设置（LIVE-08 远程配置）。
 *
 * 为什么放在 :app：偏好存在 [SettingsStore]（DataStore），而 :core:data 不认识它。
 * 让 core 层反向依赖 app 层是绝对不行的，所以这里用「注入一个 lambda」的办法 ——
 * 解析在 core（纯函数、可单测），落地在 app（知道枚举与 DataStore）。
 *
 * 只认三类安全项（刷新周期 / 主题 / 字号）：远程 JSON 改不了代理、认证、开机自启 ——
 * 那些改了要么把用户锁在门外，要么改变流量走向。**失败只影响这一项**：
 * 某一项落地失败就跳过它，其余照常，本地原值不动。
 */
class RemoteSettingsApplier(
    private val settings: SettingsStore,
) {
    suspend fun apply(remote: RemoteSettings?) {
        if (remote == null) return

        remote.refreshHours?.let { hours ->
            // 0 = 关闭；其余档位在解析阶段已限定为 6 / 12 / 24
            val interval = RefreshInterval.entries.firstOrNull { (it.hours ?: 0L) == hours.toLong() }
            if (interval != null) runCatching { settings.setRefreshInterval(interval) }
        }
        remote.theme?.let { mode -> runCatching { settings.setThemeMode(mode) } }
        remote.fontScale?.let { scale -> runCatching { settings.setFontScale(scale) } }
    }
}
