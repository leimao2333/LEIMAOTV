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
import com.leimao.tv.core.model.appearance.FontScale
import com.leimao.tv.core.model.appearance.ThemeMode
import com.leimao.tv.core.model.network.ConcurrencyProfile
import com.leimao.tv.core.model.network.NetworkSettings
import com.leimao.tv.core.model.network.ProxyConfig
import com.leimao.tv.core.model.network.TimeoutProfile
import com.leimao.tv.core.model.network.proxyFromStorage
import com.leimao.tv.core.model.search.decodeSearchHistory
import com.leimao.tv.core.model.search.encodeSearchHistory
import com.leimao.tv.core.model.search.pushSearchHistory
import com.leimao.tv.core.model.search.removeSearchHistory
import com.leimao.tv.core.player.BufferProfile
import com.leimao.tv.core.player.PlaybackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tvSettings: DataStore<Preferences> by preferencesDataStore(name = "tv-settings")

/**
 * 冷启动阶段要用到的设置快照（见 [SettingsStore.startupSnapshot]）。
 *
 * 四项都有「读不到就按默认值走」的语义，所以这个对象**永远不会是 null** ——
 * 启动路径上不该出现「还得判空」的分支。
 */
data class StartupSnapshot(
    val themeMode: ThemeMode,
    val keepScreenOn: Boolean,
    val playbackSettings: PlaybackSettings,
    val networkSettings: NetworkSettings,
) {
    companion object {
        /** 全默认：DataStore 读失败（文件损坏、迁移异常）时的退路 */
        val Default =
            StartupSnapshot(
                themeMode = ThemeMode.DEFAULT,
                keepScreenOn = true,
                playbackSettings = PlaybackSettings.DEFAULT,
                networkSettings = NetworkSettings(),
            )
    }
}

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

    /** 搜索历史（SEARCH-07）：最近 10 条，新的在前。存原文，去重按归一化后的词 */
    val searchHistory: Flow<List<String>>

    /** 界面主题（SET-02）：深色 / 浅色 / 跟随系统 */
    val themeMode: Flow<ThemeMode>

    /** 全局字号缩放（SET-02）。四档，只影响排版不影响布局 */
    val fontScale: Flow<FontScale>

    /** 网络设置（SET-04）。整包读写；改动**下次启动生效**，见 [NetworkSettings] 的说明 */
    val networkSettings: Flow<NetworkSettings>

    /** 开机自启动（SET-08）。默认关闭；只有这个开关开着时 BootReceiver 才会拉起应用 */
    val bootAutostart: Flow<Boolean>

    /**
     * 屏幕常亮（SET-13）。**默认开启** —— 电视上息屏不是「省电」，是「应用像是挂了」。
     *
     * 开关只决定窗口要不要带 `FLAG_KEEP_SCREEN_ON`；窗口一旦不可见（按 Home、切到别的应用），
     * 该标记自动失效，不会在后台拖着电视不睡。
     */
    val keepScreenOn: Flow<Boolean>

    /**
     * 冷启动要用的那一组设置，**一次读完**。
     *
     * 为什么单独给一个方法：这几项分别由 `AppContainer`（缓冲档位、网络设置）和
     * `MainActivity`（主题、常亮）在**主线程**同步读取，原来是各自的 `runBlocking { xxx.first() }`。
     * 单看每次都不贵，但在 2GB 盒子的低速 eMMC 上串起来就是 80~300ms 纯主线程阻塞，
     * 而且发生在 `setContent` 之前 —— 用户在这一段里连遥控器都按不动，冷启动体感极差。
     *
     * 这里的四项来自**同一个** DataStore 文件（`tv-settings`），本来就是一次文件读，
     * 拆成四次 `first()` 只是把同一次读做了四遍。
     */
    suspend fun startupSnapshot(): StartupSnapshot

    suspend fun setComplianceAgreed(agreed: Boolean)

    suspend fun setMode(mode: AppMode)

    suspend fun setLastSection(section: String?)

    suspend fun setPlaybackSettings(settings: PlaybackSettings)

    suspend fun setRefreshInterval(interval: RefreshInterval)

    suspend fun rememberSearchQuery(query: String)

    suspend fun forgetSearchQuery(query: String)

    suspend fun clearSearchHistory()

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setFontScale(scale: FontScale)

    suspend fun setTimeoutProfile(profile: TimeoutProfile)

    suspend fun setConcurrencyProfile(profile: ConcurrencyProfile)

    /** `null` = 直连。地址的合法性由调用方先用 `parseProxyAddress` 校过 */
    suspend fun setProxy(proxy: ProxyConfig?)

    suspend fun setBootAutostart(enabled: Boolean)

    /** 屏幕常亮（SET-13）。改动立即生效，不需要重启应用 */
    suspend fun setKeepScreenOn(enabled: Boolean)
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
        context.tvSettings.data.map { prefs -> prefs.decodePlaybackSettings() }

    override val refreshInterval: Flow<RefreshInterval> =
        context.tvSettings.data.map { prefs ->
            RefreshInterval.fromName(prefs[KEY_REFRESH_INTERVAL])
        }

    override val searchHistory: Flow<List<String>> =
        context.tvSettings.data.map { decodeSearchHistory(it[KEY_SEARCH_HISTORY]) }

    override val themeMode: Flow<ThemeMode> =
        context.tvSettings.data.map { prefs -> prefs.decodeThemeMode() }

    override val fontScale: Flow<FontScale> =
        context.tvSettings.data.map { FontScale.fromStorage(it[KEY_FONT_SCALE]) }

    override val networkSettings: Flow<NetworkSettings> =
        context.tvSettings.data.map { prefs -> prefs.decodeNetworkSettings() }

    override val bootAutostart: Flow<Boolean> =
        context.tvSettings.data.map { it[KEY_BOOT_AUTOSTART] ?: false }

    // 默认 true：老用户升级上来也要立刻常亮 —— 「没设过」和「用户主动关掉」必须分开，
    // 所以默认值写在读的一侧，而不是写进存档
    override val keepScreenOn: Flow<Boolean> =
        context.tvSettings.data.map { prefs -> prefs.decodeKeepScreenOn() }

    override suspend fun startupSnapshot(): StartupSnapshot {
        // 只读一次文件，四个字段都从这一份 Preferences 里解出来
        val prefs = context.tvSettings.data.first()
        return StartupSnapshot(
            themeMode = prefs.decodeThemeMode(),
            keepScreenOn = prefs.decodeKeepScreenOn(),
            playbackSettings = prefs.decodePlaybackSettings(),
            networkSettings = prefs.decodeNetworkSettings(),
        )
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
            prefs[KEY_PLAYBACK_SPEED] = settings.playbackSpeed.toString()
        }
    }

    override suspend fun setBootAutostart(enabled: Boolean) {
        context.tvSettings.edit { it[KEY_BOOT_AUTOSTART] = enabled }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        context.tvSettings.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    override suspend fun setRefreshInterval(interval: RefreshInterval) {
        context.tvSettings.edit { it[KEY_REFRESH_INTERVAL] = interval.name }
    }

    override suspend fun rememberSearchQuery(query: String) {
        // 读改写都在 edit 里完成：DataStore 的 edit 自身串行，手写「先读再写」会丢条
        context.tvSettings.edit { prefs ->
            prefs[KEY_SEARCH_HISTORY] =
                encodeSearchHistory(pushSearchHistory(decodeSearchHistory(prefs[KEY_SEARCH_HISTORY]), query))
        }
    }

    override suspend fun forgetSearchQuery(query: String) {
        context.tvSettings.edit { prefs ->
            prefs[KEY_SEARCH_HISTORY] =
                encodeSearchHistory(removeSearchHistory(decodeSearchHistory(prefs[KEY_SEARCH_HISTORY]), query))
        }
    }

    override suspend fun clearSearchHistory() {
        context.tvSettings.edit { it.remove(KEY_SEARCH_HISTORY) }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.tvSettings.edit { it[KEY_THEME_MODE] = mode.name }
    }

    override suspend fun setFontScale(scale: FontScale) {
        context.tvSettings.edit { it[KEY_FONT_SCALE] = scale.name }
    }

    override suspend fun setTimeoutProfile(profile: TimeoutProfile) {
        context.tvSettings.edit { it[KEY_TIMEOUT_PROFILE] = profile.name }
    }

    override suspend fun setConcurrencyProfile(profile: ConcurrencyProfile) {
        context.tvSettings.edit { it[KEY_CONCURRENCY_PROFILE] = profile.name }
    }

    override suspend fun setProxy(proxy: ProxyConfig?) {
        // 直连就把键删掉：留一个空串在存档里，「没设过」和「设成空了」就分不清了
        context.tvSettings.edit { prefs ->
            if (proxy == null) prefs.remove(KEY_PROXY) else prefs[KEY_PROXY] = proxy.address
        }
    }

    // --------------------------------------------------------------------- //
    // 解码：Flow 与 startupSnapshot 共用同一份，避免两处判定漂移
    // --------------------------------------------------------------------- //

    /**
     * 做成类的成员扩展而不是顶层函数：键都锁在 `private companion object` 里，
     * 顶层函数拿不到。共用是硬要求 —— 一旦「启动快照」和「运行期 Flow」对同一个键
     * 给出不同的默认值，就会出现「刚启动是 A、过一会儿变 B」的诡异现象。
     */
    private fun Preferences.decodePlaybackSettings(): PlaybackSettings =
        PlaybackSettings(
            bufferProfile = BufferProfile.fromName(this[KEY_BUFFER_PROFILE]),
            seekIncrementMs = this[KEY_SEEK_STEP_MS] ?: PlaybackSettings.DEFAULT.seekIncrementMs,
            playbackSpeed =
                this[KEY_PLAYBACK_SPEED]?.toFloatOrNull()
                    ?: PlaybackSettings.DEFAULT.playbackSpeed,
        )

    private fun Preferences.decodeNetworkSettings(): NetworkSettings =
        NetworkSettings(
            timeout = TimeoutProfile.fromStorage(this[KEY_TIMEOUT_PROFILE]),
            concurrency = ConcurrencyProfile.fromStorage(this[KEY_CONCURRENCY_PROFILE]),
            proxy = proxyFromStorage(this[KEY_PROXY]),
        )

    private fun Preferences.decodeThemeMode(): ThemeMode = ThemeMode.fromStorage(this[KEY_THEME_MODE])

    // 默认 true：「没设过」和「用户主动关掉」必须分开，默认值写在读的一侧
    private fun Preferences.decodeKeepScreenOn(): Boolean = this[KEY_KEEP_SCREEN_ON] ?: true

    private companion object {
        val KEY_COMPLIANCE_AGREED = booleanPreferencesKey("compliance_agreed")
        val KEY_MODE = stringPreferencesKey("app_mode")
        val KEY_LAST_SECTION = stringPreferencesKey("last_section")
        val KEY_BUFFER_PROFILE = stringPreferencesKey("buffer_profile")
        val KEY_SEEK_STEP_MS = longPreferencesKey("seek_step_ms")
        val KEY_PLAYBACK_SPEED = stringPreferencesKey("playback_speed")
        val KEY_BOOT_AUTOSTART = booleanPreferencesKey("boot_autostart")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_REFRESH_INTERVAL = stringPreferencesKey("refresh_interval")
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        val KEY_TIMEOUT_PROFILE = stringPreferencesKey("timeout_profile")
        val KEY_CONCURRENCY_PROFILE = stringPreferencesKey("concurrency_profile")
        val KEY_PROXY = stringPreferencesKey("proxy")
    }
}
