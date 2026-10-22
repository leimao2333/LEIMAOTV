package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.EpgImportSummary
import com.leimao.tv.core.database.repository.EpgRepository
import com.leimao.tv.core.model.epg.mergeEpgGuide
import com.leimao.tv.core.network.epg.XmltvParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** 节目单导入失败的原因。文案在 UI 层翻译，这里只给结论。 */
enum class EpgFailure {
    /** 打不开文件（权限、路径失效） */
    CANNOT_READ,

    /** 不是可解析的 XMLTV：没有任何频道，或没有任何节目 */
    NOT_XMLTV,

    /** 解析中途炸了（半份文件、编码错误） */
    BROKEN_XML,
}

sealed interface EpgImportResult {
    data class Done(val summary: EpgImportSummary) : EpgImportResult

    data class Failed(val reason: EpgFailure, val detail: String? = null) : EpgImportResult
}

/**
 * 「读取 → 解析 → 同类合并 → 落库」的唯一编排入口（LIVE-20）。
 *
 * 为什么要单独一层：解析（core:network）与落库（core:database）彼此不该知道对方存在，
 * 合并规则（core:model）也不该知道文件从哪来。三者在 UI 里拼起来的话，
 * 「本地文件导入」与「订阅地址导入」就会各写一遍，很快出现两份不一致的合并逻辑。
 *
 * 整段都在 [ioDispatcher] 上跑：6MB 级别的节目单解析要几百毫秒，
 * 放主线程直接顶穿 §7 的启动与滑动红线。
 */
class EpgImporter(
    private val repository: EpgRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun importXml(open: () -> InputStream): EpgImportResult =
        withContext(ioDispatcher) {
            val parsed =
                try {
                    open().use { XmltvParser.parse(it) }
                } catch (e: java.io.IOException) {
                    return@withContext EpgImportResult.Failed(EpgFailure.CANNOT_READ, e.message)
                } catch (e: org.xml.sax.SAXException) {
                    return@withContext EpgImportResult.Failed(EpgFailure.BROKEN_XML, e.message)
                } catch (e: javax.xml.parsers.ParserConfigurationException) {
                    return@withContext EpgImportResult.Failed(EpgFailure.BROKEN_XML, e.message)
                }

            if (parsed.channels.isEmpty() || parsed.programmes.isEmpty()) {
                // 抓到的是网页错误页 / 空文件时，SAX 往往不报错，只是啥也没解析出来。
                // 这种情况必须明确告诉用户「这不是节目单」，而不是"导入成功 0 条"
                return@withContext EpgImportResult.Failed(
                    EpgFailure.NOT_XMLTV,
                    "频道 ${parsed.channels.size} / 节目 ${parsed.programmes.size}",
                )
            }

            val guide = mergeEpgGuide(parsed.channels, parsed.programmes)
            repository.replaceAll(guide)
            EpgImportResult.Done(repository.summary())
        }
}
