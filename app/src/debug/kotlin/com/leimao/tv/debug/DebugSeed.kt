package com.leimao.tv.debug

import com.leimao.tv.AppContainer
import com.leimao.tv.core.data.BackupImportResult
import com.leimao.tv.core.data.RefreshResult
import com.leimao.tv.core.database.repository.NewSource
import com.leimao.tv.core.model.playlist.MergedChannel
import kotlinx.coroutines.launch

/**
 * **仅调试包**：种一条「开发期默认测试源」并立刻刷新，让模拟器/真机验收开箱就有真实内容。
 *
 * 为什么需要它：真机/模拟器上做验收时，手输一个地址很麻烦，而「启动直达播放」这条主线
 * 必须先有频道才走得通。种一条真实聚合源之后，四条启动路径都能反复走一遍。
 *
 * 测试源（RULES §9.3）：本项目自己的聚合仓库 `leimao2333/leimaotv-sources` 的
 * `dist/sources.json` —— 这是**整份订阅**（直播 6 条 + 影视 2 条），不是单条播放列表。
 * 换成整份之后，调试包一次启动就能把「直播」和「影视」两个板块同时点亮，
 * 不用再手动去源管理里补一条影视接口才能验收 VOD。
 *
 * 它是**测试输入**、不是产品内容：只出现在 `src/debug`，正式包里根本没有这段代码
 * （不是「用开关关掉」，见 §2.1 / §9.3 边界第 1 条）。单测仍只用假数据（§9.1）。
 *
 * 走的是与用户手动导入完全相同的链路（[AppContainer.sourceBackupImporter] →
 * `SourceRepository.importJson`），所以「从地址导入」这条路径本身也顺带被验收了。
 */
object DebugSeed {
    /** §9.3 默认测试源：聚合订阅（直播 + 影视） */
    private const val TEST_BACKUP_URL =
        "https://raw.githubusercontent.com/leimao2333/leimaotv-sources/main/dist/sources.json"

    /**
     * 兜底用的单条直播源。
     *
     * 整份订阅拉不到时（仓库改名、JSON 格式变、网络只剩半通）退回它，
     * 保证「至少有一个能播的台」这条验收前提不依赖 JSON 解析是否成功。
     * 走 gh-proxy：国内直连 raw.githubusercontent.com 大概率超时。
     */
    private const val TEST_SOURCE_NAME = "leimaotv 聚合源（测试）"

    private const val TEST_SOURCE_URL =
        "https://gh-proxy.com/raw.githubusercontent.com/leimao2333/leimaotv-sources/main/dist/live.txt"

    suspend fun seedIfEmpty(container: AppContainer) {
        if (container.sourceRepository.countEnabled() > 0) return

        // 首选：整份聚合订阅。导入失败（网络 / 格式）都不抛，直接走下面的兜底
        val imported = runCatching { container.sourceBackupImporter.importFrom(TEST_BACKUP_URL) }.getOrNull()
        if (imported is BackupImportResult.Done && imported.summary.added > 0) {
            seedFromBackup(container, imported)
            return
        }

        seedSingleLiveSource(container)
    }

    /**
     * 整份订阅：**只同步刷第一条直播源**，其余丢后台。
     *
     * 这条调用卡在冷启动路径上（`MainActivity` 里 seed 完成后才决定落点），
     * 6 条直播 + 2 条影视全刷完要好几十秒 —— 期间用户看到的是一块纯黑（2026-10-26 实测）。
     * 而「启动直达播放」只需要**有台可播**，所以同步的预算只花在第一条上；
     * 剩下的源与整个影视目录交给 [AppContainer.applicationScope]，界面先出来、内容随后补齐。
     */
    private suspend fun seedFromBackup(
        container: AppContainer,
        imported: BackupImportResult.Done,
    ) {
        val liveIds = imported.summary.addedLiveIds
        liveIds.firstOrNull()?.let { id ->
            runCatching { container.sourceRefresher.refresh(id) }
        }

        container.applicationScope.launch {
            liveIds.drop(1).forEach { id ->
                runCatching { container.sourceRefresher.refresh(id) }
            }
            // 影视源不在 addedLiveIds 里（那个字段是直播专用的），单独整批刷一次。
            // 影视目录动辄几十个站点，是最慢的一环，必须放后台
            runCatching { container.vodCatalogRefresher.refreshAll() }
        }
    }

    /** 兜底：单条 live.txt。刷新失败也不抛 —— 测试源失效不代表功能坏（§9.3 边界第 3 条） */
    private suspend fun seedSingleLiveSource(container: AppContainer) {
        val sourceId =
            runCatching {
                container.sourceRepository.add(
                    NewSource(name = TEST_SOURCE_NAME, url = TEST_SOURCE_URL),
                )
            }.getOrNull() ?: return

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
