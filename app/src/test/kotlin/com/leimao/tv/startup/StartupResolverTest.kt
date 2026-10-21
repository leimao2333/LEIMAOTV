package com.leimao.tv.startup

import com.leimao.tv.core.database.startup.StartupFacts
import com.leimao.tv.core.database.startup.StartupFactsReader
import com.leimao.tv.core.model.AppMode
import com.leimao.tv.core.model.startup.StartDestination
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
                    settings = FakeSettingsStore(complianceAgreed = false, lastChannelId = 7L),
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
                    settings = FakeSettingsStore(complianceAgreed = true, lastChannelId = 7L),
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

        override suspend fun read(lastChannelIdHint: Long?): StartupFacts {
            readCount++
            return facts
        }
    }

    private class FakeSettingsStore(
        complianceAgreed: Boolean,
        lastChannelId: Long? = null,
    ) : SettingsStore {
        private val agreedFlow = MutableStateFlow(complianceAgreed)
        private val modeFlow = MutableStateFlow(AppMode.LIVE)
        private val lastChannelFlow = MutableStateFlow(lastChannelId)
        private val lastSectionFlow = MutableStateFlow<String?>(null)

        override val complianceAgreed: Flow<Boolean> = agreedFlow
        override val mode: Flow<AppMode> = modeFlow
        override val lastChannelId: Flow<Long?> = lastChannelFlow
        override val lastSection: Flow<String?> = lastSectionFlow

        override suspend fun setComplianceAgreed(agreed: Boolean) {
            agreedFlow.value = agreed
        }

        override suspend fun setMode(mode: AppMode) {
            modeFlow.value = mode
        }

        override suspend fun setLastChannelId(id: Long?) {
            lastChannelFlow.value = id
        }

        override suspend fun setLastSection(section: String?) {
            lastSectionFlow.value = section
        }
    }
}
