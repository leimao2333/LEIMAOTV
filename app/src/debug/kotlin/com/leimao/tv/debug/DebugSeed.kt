package com.leimao.tv.debug

import com.leimao.tv.AppContainer
import com.leimao.tv.core.data.RefreshResult
import com.leimao.tv.core.database.repository.NewSource
import com.leimao.tv.core.model.playlist.MergedChannel

/**
 * **仅调试包**：种一条「开发期默认测试源」并立刻刷新，让模拟器/真机验收开箱就有真实频道。
 *
 * 为什么需要它：真机/模拟器上做验收时，手输一个地址很麻烦，而「启动直达播放」这条主线
 * 必须先有频道才走得通。种一条真实聚合源之后，四条启动路径都能反复走一遍。
 *
 * 测试源（RULES §9.3）：本项目自己的聚合仓库 `leimao2333/leimaotv-sources` 的
 * `dist/live.txt` —— 把上游直播源合并、去重、重新分组后输出，每天自动更新两次。
 * 它是**测试输入**、不是产品内容：只出现在 `src/debug`，正式包里根本没有这段代码
 * （不是「用开关关掉」，见 §2.1 / §9.3 边界第 1 条）。单测仍只用假数据（§9.1）。
 *
 * 与旧实现的差别：不再手工捏 `example.com` 假频道，而是种一条真实聚合源后走
 * [AppContainer.sourceRefresher] 做一次真实网络刷新 —— 拉回来的是真实频道表
 * （数百个真实可播的台），走的是与正式刷新完全相同的「抓取 → 解析 → 合并 → 落库」链路，
 * 验收时「能播 / 不能播」反映的是真实世界，而不是假数据。
 */
object DebugSeed {
    /** §9.3 默认测试源：聚合源 live.txt（走 gh-proxy 加速，直连见注释里的备选） */
    private const val TEST_SOURCE_NAME = "leimaotv 聚合源（测试）"
    private const val TEST_SOURCE_URL =
        "https://gh-proxy.com/raw.githubusercontent.com/leimao2333/leimaotv-sources/main/dist/live.txt"
    // 直连备选（本机代理正常或海外网络）：
    //   https://raw.githubusercontent.com/leimao2333/leimaotv-sources/main/dist/live.txt

    suspend fun seedIfEmpty(container: AppContainer) {
        if (container.sourceRepository.countEnabled() > 0) return

        val sourceId =
            container.sourceRepository.add(
                NewSource(name = TEST_SOURCE_NAME, url = TEST_SOURCE_URL),
            )

        // 立刻刷新一次：种下的是「地址」，频道要等刷新拉回来。刷新失败也不抛 ——
        // 测试源失效不代表功能坏（§9.3 边界第 3 条），启动链路照常往下走，
        // 源管理页会如实显示「上次为什么没成功」。
        runCatching {
            val result = container.sourceRefresher.refresh(sourceId)
            // 拉回来的是空列表（比如聚合源临时只剩空分组）时，补一条本地演示台兜底，
            // 保证「换线路」「失败降级」这些交互仍有台可操作
            if (result is RefreshResult.Done && result.outcome.channelCount == 0) {
                seedLocalFallback(container, sourceId)
            }
        }
    }

    /** 本地演示视频兜底：只认应用自己的外部目录，不需要任何存储权限 */
    private suspend fun seedLocalFallback(
        container: AppContainer,
        sourceId: Long,
    ) {
        localDemoFile(container)?.let { uri ->
            container.writeDemoChannels(
                sourceId = sourceId,
                channels =
                    listOf(
                        MergedChannel(
                            name = "本地演示画面（调试）",
                            groupName = "演示分组 A",
                            tvgId = "demo-local",
                            logoUrl = null,
                            numberInPlaylist = 0,
                            lines = listOf(uri),
                        ),
                    ),
            )
        }
    }

    /** 找本地演示视频：只认应用自己的外部目录，不需要任何存储权限 */
    private fun localDemoFile(container: AppContainer): String? {
        val file = container.appContext.getExternalFilesDir(null)?.resolve(DEMO_FILE_NAME) ?: return null
        return if (file.exists() && file.length() > 0) file.toURI().toString() else null
    }

    private const val DEMO_FILE_NAME = "demo.mp4"
}
