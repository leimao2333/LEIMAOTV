package com.leimao.tv.startup

import com.leimao.tv.core.data.RefreshInterval
import com.leimao.tv.core.database.startup.StartupFacts
import com.leimao.tv.core.database.startup.StartupFactsReader
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.appearance.FontScale
import com.leimao.tv.core.model.appearance.ThemeMode
import com.leimao.tv.core.model.network.ConcurrencyProfile
import com.leimao.tv.core.model.network.NetworkSettings
import com.leimao.tv.core.model.network.ProxyConfig
import com.leimao.tv.core.model.network.TimeoutProfile
import com.leimao.tv.core.model.search.pushSearchHistory
import com.leimao.tv.core.model.search.removeSearchHistory
import com.leimao.tv.core.model.startup.StartDestination
import com.leimao.tv.core.player.PlaybackSettings
import com.leimao.tv.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 落点解析的「接线」测试：确认 [StartupResolver] 把本地标记与数据库事实按正确顺序拼起来了。
 *
 * 纯函数的优先级规则在 :core:model 已经测过，这里只管一件事：
 * **合规未同意时不碰数据库**（冷启动关键路径上能少一次 IO 就少一次，§7 只给 2 秒）。
 */
class StartupResolverTest {
    @Test
    fun `未同意合规提示时直接返回提示页且不查数据库`() =
        runTest {
            val facts = FakeFactsReader(parsed(source = true))
            val resolver =
                StartupResolver(
                    settings = FakeSettingsStore(complianceAgreed = false),
                    factsReader = facts,
                )

            assertEquals(StartDestination.ComplianceNotice, resolver.resolve())
            assertEquals("合规未同意就不该碰数据库", 0, facts.readCount)
        }

    @Test
    fun `已同意但没有源时落到添加源引导页`() =
        runTest {
            val resolver =
                StartupResolver(
                    settings = FakeSettingsStore(complianceAgreed = true),
                    factsReader = FakeFactsReader(parsed(source = false)),
                )

            assertEquals(StartDestination.AddSource, resolver.resolve())
        }

    @Test
    fun `已同意且有上次频道时直达该频道`() =
        runTest {
            val resolver =
                StartupResolver(
                    settings = FakeSettingsStore(complianceAgreed = true),
                    factsReader = FakeFactsReader(parsed(source = true, lastChannelId = 7L)),
                )

            assertEquals(
                StartDestination.Player(channelId = 7L, channelsLoading = false),
                resolver.resolve(),
            )
        }

    @Test
    fun `有源但频道还没解析出来时停在播放页骨架`() =
        runTest {
            val resolver =
                StartupResolver(
                    settings = FakeSettingsStore(complianceAgreed = true),
                    factsReader =
                        FakeFactsReader(
                            StartupFacts(
                                hasEnabledSource = true,
                                channelsParsed = false,
                                lastChannelId = null,
                                favoriteChannelId = null,
                                firstChannelId = null,
                            ),
                        ),
                )

            assertEquals(
                StartDestination.Player(channelId = null, channelsLoading = true),
                resolver.resolve(),
            )
        }

    private fun parsed(
        source: Boolean,
        lastChannelId: Long? = null,
    ) = StartupFacts(
        hasEnabledSource = source,
        channelsParsed = true,
        lastChannelId = lastChannelId,
        favoriteChannelId = null,
        firstChannelId = 1L,
    )

    private class FakeFactsReader(private val facts: StartupFacts) : StartupFactsReader {
        var readCount = 0
            private set

        override suspend fun read(): StartupFacts {
            readCount++
            return facts
        }
    }

    private class FakeSettingsStore(
        complianceAgreed: Boolean,
    ) : SettingsStore {
        private val agreedFlow = MutableStateFlow(complianceAgreed)
        private val modeFlow = MutableStateFlow(AppMode.LIVE)
        private val lastSectionFlow = MutableStateFlow<String?>(null)
        private val playbackSettingsFlow = MutableStateFlow(PlaybackSettings.DEFAULT)
        private val refreshIntervalFlow = MutableStateFlow(RefreshInterval.DEFAULT)
        private val searchHistoryFlow = MutableStateFlow<List<String>>(emptyList())
        private val themeModeFlow = MutableStateFlow(ThemeMode.DEFAULT)
        private val fontScaleFlow = MutableStateFlow(FontScale.DEFAULT)
        private val networkSettingsFlow = MutableStateFlow(NetworkSettings())

        override val complianceAgreed: Flow<Boolean> = agreedFlow
        override val mode: Flow<AppMode> = modeFlow
        override val lastSection: Flow<String?> = lastSectionFlow
        override val playbackSettings: Flow<PlaybackSettings> = playbackSettingsFlow
        override val refreshInterval: Flow<RefreshInterval> = refreshIntervalFlow
        override val searchHistory: Flow<List<String>> = searchHistoryFlow
        override val themeMode: Flow<ThemeMode> = themeModeFlow
        override val fontScale: Flow<FontScale> = fontScaleFlow
        override val networkSettings: Flow<NetworkSettings> = networkSettingsFlow

        override suspend fun setComplianceAgreed(agreed: Boolean) {
            agreedFlow.value = agreed
        }

        override suspend fun setMode(mode: AppMode) {
            modeFlow.value = mode
        }

        override suspend fun setLastSection(section: String?) {
            lastSectionFlow.value = section
        }

        override suspend fun setPlaybackSettings(settings: PlaybackSettings) {
            playbackSettingsFlow.value = settings
        }

        override suspend fun setRefreshInterval(interval: RefreshInterval) {
            refreshIntervalFlow.value = interval
        }

        override suspend fun rememberSearchQuery(query: String) {
            searchHistoryFlow.value = pushSearchHistory(searchHistoryFlow.value, query)
        }

        override suspend fun forgetSearchQuery(query: String) {
            searchHistoryFlow.value = removeSearchHistory(searchHistoryFlow.value, query)
        }

        override suspend fun clearSearchHistory() {
            searchHistoryFlow.value = emptyList()
        }

        override suspend fun setThemeMode(mode: ThemeMode) {
            themeModeFlow.value = mode
        }

        override suspend fun setFontScale(scale: FontScale) {
            fontScaleFlow.value = scale
        }

        override suspend fun setTimeoutProfile(profile: TimeoutProfile) {
            networkSettingsFlow.value = networkSettingsFlow.value.copy(timeout = profile)
        }

        override suspend fun setConcurrencyProfile(profile: ConcurrencyProfile) {
            networkSettingsFlow.value = networkSettingsFlow.value.copy(concurrency = profile)
        }

        override suspend fun setProxy(proxy: ProxyConfig?) {
            networkSettingsFlow.value = networkSettingsFlow.value.copy(proxy = proxy)
        }
    }
}
