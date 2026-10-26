package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.EpgImportSummary
import com.leimao.tv.core.database.repository.EpgRepository

/**
 * 订阅源自带的节目单地址要不要用（LIVE-27）。
 *
 * 聚合类订阅会在 M3U 头部写 `x-tvg-url`，等于作者替用户挑好了节目单。以前这条信息在
 * 解析阶段就被丢掉了，用户只能自己再去翻一遍源的说明页，手工把地址贴进「节目单订阅地址」。
 *
 * 三条规则，核心是「不抢用户的手动配置」：
 *
 * 1. **没配过 → 自动登记并抓一次**。先登记再抓，抓失败也能在源管理页看到这个地址与失败原因；
 * 2. **配过且同一个地址 → 什么都不做**。刷新是高频操作，每次重抓一份几 MB 的节目单
 *    是不可接受的（也正因为如此，抓失败的地址同样会留在库里，下一轮就走这条分支，不会反复重试）；
 * 3. **配过但是别的地址 → 保留用户的**。他可能是故意换了一份更全的节目单，
 *    自动覆盖等于把用户的选择悄悄改掉。
 */
class EpgAutoBinder(
    private val repository: EpgRepository,
    private val importer: EpgImporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun bind(urls: List<String>): EpgBindOutcome {
        val candidates = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (candidates.isEmpty()) return EpgBindOutcome.NoAddress

        val existing = repository.sourceUrl()?.takeIf { it.isNotBlank() }
        if (existing != null) {
            return if (existing in candidates) {
                EpgBindOutcome.AlreadyBound
            } else {
                EpgBindOutcome.KeptUserChoice(existing)
            }
        }

        for (url in candidates) {
            repository.saveSource(url, clock())
            when (val result = importer.importUrl(url)) {
                is EpgImportResult.Done -> return EpgBindOutcome.Bound(url, result.summary)
                // 第一个地址不通就试下一个：聚合源给多个地址，本来就是让客户端挑能用的那个
                is EpgImportResult.Failed -> Unit
            }
        }
        return EpgBindOutcome.Failed(candidates.first())
    }
}

/** 自动挂载的结局。只有真的挂上/失败才值得在界面上说一句，其余保持安静。 */
sealed interface EpgBindOutcome {
    /** 源里没写节目单地址（TXT 源、以及大多数单台源都是这样） */
    data object NoAddress : EpgBindOutcome

    /** 已经配着同一个地址，这次不重复抓 */
    data object AlreadyBound : EpgBindOutcome

    data class Bound(
        val url: String,
        val summary: EpgImportSummary,
    ) : EpgBindOutcome

    /** 用户自己配了另一份节目单，保持不动 */
    data class KeptUserChoice(val url: String) : EpgBindOutcome

    data class Failed(val url: String) : EpgBindOutcome

    /**
     * 给源管理页状态行的一句话。null 表示「没什么可说的」——
     * 每次刷新都提示一遍「节目单已就绪」只会把真正重要的失败信息挤掉。
     */
    val note: String?
        get() =
            when (this) {
                NoAddress, AlreadyBound, is KeptUserChoice -> null
                is Bound -> "已按订阅声明挂上节目单（${summary.channelCount} 个频道）"
                is Failed -> "订阅声明的节目单地址抓取失败，可稍后重试或在节目单设置里手工添加"
            }
}
