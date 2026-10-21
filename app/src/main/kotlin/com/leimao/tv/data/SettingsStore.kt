package com.leimao.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.leimao.tv.core.model.AppMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tvSettings: DataStore<Preferences> by preferencesDataStore(name = "tv-settings")

/**
 * 本地设置。只放「启动判定需要读的那几项」与轻量偏好，频道等结构化数据在 Room。
 *
 * 为什么合规标记、模式、上次频道都挤在一个 DataStore 里：启动路径上要读的就是这三个键，
 * 一次文件读取解决，比「三个文件各读一次」在冷启动阶段便宜得多（RULES §6.6 约束 2）。
 */
interface SettingsStore {
    val complianceAgreed: Flow<Boolean>
    val mode: Flow<AppMode>
    val lastChannelId: Flow<Long?>

    /** 上次停在主页的哪个导航项（HOME-03 的「主页内落点记忆」），null 表示从未选过 */
    val lastSection: Flow<String?>

    suspend fun setComplianceAgreed(agreed: Boolean)

    suspend fun setMode(mode: AppMode)

    suspend fun setLastChannelId(id: Long?)

    suspend fun setLastSection(section: String?)
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

    override val lastChannelId: Flow<Long?> =
        context.tvSettings.data.map { it[KEY_LAST_CHANNEL_ID] }

    override val lastSection: Flow<String?> =
        context.tvSettings.data.map { it[KEY_LAST_SECTION] }

    override suspend fun setComplianceAgreed(agreed: Boolean) {
        context.tvSettings.edit { it[KEY_COMPLIANCE_AGREED] = agreed }
    }

    override suspend fun setMode(mode: AppMode) {
        context.tvSettings.edit { it[KEY_MODE] = mode.name }
    }

    override suspend fun setLastChannelId(id: Long?) {
        context.tvSettings.edit { prefs ->
            if (id == null) prefs.remove(KEY_LAST_CHANNEL_ID) else prefs[KEY_LAST_CHANNEL_ID] = id
        }
    }

    override suspend fun setLastSection(section: String?) {
        context.tvSettings.edit { prefs ->
            if (section == null) prefs.remove(KEY_LAST_SECTION) else prefs[KEY_LAST_SECTION] = section
        }
    }

    private companion object {
        val KEY_COMPLIANCE_AGREED = booleanPreferencesKey("compliance_agreed")
        val KEY_MODE = stringPreferencesKey("app_mode")
        val KEY_LAST_CHANNEL_ID = longPreferencesKey("last_channel_id")
        val KEY_LAST_SECTION = stringPreferencesKey("last_section")
    }
}
