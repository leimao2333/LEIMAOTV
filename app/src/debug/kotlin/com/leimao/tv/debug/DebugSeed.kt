package com.leimao.tv.debug

import com.leimao.tv.AppContainer
import com.leimao.tv.core.database.repository.NewSource
import com.leimao.tv.core.model.playlist.MergedChannel

/**
 * **仅调试包**：种一条演示源。
 *
 * 为什么需要它：真机/模拟器上做验收时，手输一个地址很麻烦，而「启动直达播放」这条主线
 * 必须先有频道才走得通。种一条假源之后，四条启动路径都能反复走一遍。
 *
 * 合规边界（RULES §2.1）：地址一律 `example.com` 占位，不含任何真实源地址、频道名、站点名；
 * 且这个文件只存在于 `src/debug` —— 正式包里根本没有这段代码，不是「用开关关掉」。
 *
 * 频道一律经过 `ChannelWriter` 写入（跟真实刷新同一条路径），所以演示数据也带线路表 ——
 * 否则「换线路」这个功能在调试时永远只有一条线路，等于测不到。
 */
object DebugSeed {
    private const val DEMO_HOST = "https://example.com"
    private const val DEMO_SOURCE_NAME = "演示源（仅调试包）"
    private const val DEMO_CHANNEL_COUNT = 6

    suspend fun seedIfEmpty(container: AppContainer) {
        if (container.sourceRepository.countEnabled() > 0) return

        val sourceId =
            container.sourceRepository.add(
                NewSource(name = DEMO_SOURCE_NAME, url = "$DEMO_HOST/demo.m3u"),
            )

        val channels =
            (1..DEMO_CHANNEL_COUNT).map { number ->
                MergedChannel(
                    name = "演示频道 $number",
                    groupName = if (number % 2 == 0) "演示分组 B" else "演示分组 A",
                    tvgId = "demo-$number",
                    logoUrl = null,
                    numberInPlaylist = number,
                    // 每个台两条线路：验证「播放失败切下一条」与「手动切线路」都要用到
                    lines =
                        listOf(
                            "$DEMO_HOST/demo/channel-$number.m3u8",
                            "$DEMO_HOST/demo/channel-$number-backup.m3u8",
                        ),
                )
            }

        container.writeDemoChannels(sourceId = sourceId, channels = channels)
    }
}
