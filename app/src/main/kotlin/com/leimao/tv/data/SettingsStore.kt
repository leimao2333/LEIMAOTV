package com.leimao.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.leimao.tv.core.data.RefreshInterval
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.player.BufferProfile
import com.leimao.tv.core.player.PlaybackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tvSettings: DataStore<Preferences> by preferencesDataStore(name = "tv-settings")

/**
 * 本地设置。只放「启动判定需要读的那几项」与轻量偏好，频道等结构化数据在 Room。
 *
 * M1 起「上次看的台」搬进了 `history` 表：它要参与「最近观看」列表、要跟频道一起被删，
 * 存在 DataStore 里就得两边同步。唯一的例外是合规标记与模式 —— 它们跟数据库没关系，
 * 留在 DataStore 才能在启动时一次读完、不碰 Room（RULES §6.6 约束 2）。
 */
interface SettingsStore {
    val complianceAgreed: Flow<Boolean>
    val mode: Flow<AppMode>

    /** 上次停在主页的哪个导航项（HOME-03 的「主页内落点记忆」），null 表示从未选过 */
    val lastSection: Flow<String?>

    /** 播放设置（SET-01）。整包读写，避免一项项散落成多个 Flow */
    val playbackSettings: Flow<PlaybackSettings>

    /** 自动刷新周期（LIVE-07）。关闭 / 6 / 12 / 24 小时 */
    val refreshInterval: Flow<RefreshInterval>

    suspend fun setComplianceAgreed(agreed: Boolean)

    suspend fun setMode(mode: AppMode)

    suspend fun setLastSection(section: String?)

    suspend fun setPlaybackSettings(settings: PlaybackSettings)

    suspend fun setRefreshInterval(interval: RefreshInterval)
}

class DataStoreSettingsStore(private val context: Context) : SettingsStore {
    override val complianceAgreed: Flow<Boolean> =
        context.tvSettings.data.map { it[KEY_COMPLIANCE_AGREED] ?: false }

    override val mode: Flow<AppMode> =
        context.tvSettings.data.map { prefs ->
            // 用 firstOrNull 而不是 valueOf：存档里出现不认识的模式（降级安装、手工改档）
            // 时应该回落到默认，而不是崩在启动路径上
            prefs[KEY_MODE]?.let { saved -> AppMode.entries.firstOrNull { it.name == saved } }
                ?: AppMode.LIVE
        }

    override val lastSection: Flow<String?> =
        context.tvSettings.data.map { it[KEY_LAST_SECTION] }

    override val playbackSettings: Flow<PlaybackSettings> =
        context.tvSettings.data.map { prefs ->
            PlaybackSettings(
                bufferProfile = BufferProfile.fromName(prefs[KEY_BUFFER_PROFILE]),
                seekIncrementMs = prefs[KEY_SEEK_STEP_MS] ?: PlaybackSettings.DEFAULT.seekIncrementMs,
            )
        }

    override val refreshInterval: Flow<RefreshInterval> =
        context.tvSettings.data.map { prefs ->
            RefreshInterval.fromName(prefs[KEY_REFRESH_INTERVAL])
        }

    override suspend fun setComplianceAgreed(agreed: Boolean) {
        context.tvSettings.edit { it[KEY_COMPLIANCE_AGREED] = agreed }
    }

    override suspend fun setMode(mode: AppMode) {
        context.tvSettings.edit { it[KEY_MODE] = mode.name }
    }

    override suspend fun setLastSection(section: String?) {
        context.tvSettings.edit { prefs ->
            if (section == null) prefs.remove(KEY_LAST_SECTION) else prefs[KEY_LAST_SECTION] = section
        }
    }

    override suspend fun setPlaybackSettings(settings: PlaybackSettings) {
        context.tvSettings.edit { prefs ->
            prefs[KEY_BUFFER_PROFILE] = settings.bufferProfile.name
            prefs[KEY_SEEK_STEP_MS] = settings.seekIncrementMs
        }
    }

    override suspend fun setRefreshInterval(interval: RefreshInterval) {
        context.tvSettings.edit { it[KEY_REFRESH_INTERVAL] = interval.name }
    }

    private companion object {
        val KEY_COMPLIANCE_AGREED = booleanPreferencesKey("compliance_agreed")
        val KEY_MODE = stringPreferencesKey("app_mode")
        val KEY_LAST_SECTION = stringPreferencesKey("last_section")
        val KEY_BUFFER_PROFILE = stringPreferencesKey("buffer_profile")
        val KEY_SEEK_STEP_MS = longPreferencesKey("seek_step_ms")
        val KEY_REFRESH_INTERVAL = stringPreferencesKey("refresh_interval")
    }
}
