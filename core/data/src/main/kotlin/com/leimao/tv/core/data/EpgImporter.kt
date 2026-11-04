package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.EpgImportSummary
import com.leimao.tv.core.database.repository.EpgRepository
import com.leimao.tv.core.model.epg.EpgChannel
import com.leimao.tv.core.model.epg.EpgProgramme
import com.leimao.tv.core.model.epg.mergeEpgGuide
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.StreamOutcome
import com.leimao.tv.core.network.epg.XmltvParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** 节目单导入失败的原因。文案在 UI 层翻译，这里只给结论。 */
enum class EpgFailure {
    /** 打不开文件（权限、路径失效） */
    CANNOT_READ,

    /** 抓取订阅地址失败（超时 / HTTP / 网络） */
    NETWORK,

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
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 本地文件导入（LIVE-20）：从 [open] 拿到输入流。
     *
     * 文件已由 UI 层（SAF）负责打开，这里只关心「把流解析成节目单并落库」。
     */
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

            store(parsed.channels, parsed.programmes)
        }

    /**
     * 订阅地址刷新（LIVE-23）：抓 URL → 解析 → 落库，与本地导入共用同一条存储链路。
     *
     * 失败会把原因写进 [EpgRepository.markRefreshed]，下次打开源管理页还能看到
     * 「上次为什么没成功」，而不是只在后台静默失败。
     */
    suspend fun importUrl(url: String): EpgImportResult =
        withContext(ioDispatcher) {
            // 流式解析，**不先变成 String**（2026-11-04，2GB 机器防闪退）。
            //
            // 原来的链路是 fetch → body.bytes()（压缩包 6.6MB）→ 解压 ByteArray（50~90MB）
            // → UTF-16 String（100~180MB）→ byteInputStream()（又一份）→ SAX。
            // 四份同时活着，2GB 机 192MB 的堆必然 OOM，而且死在内存分配上、
            // Java 层拿不到堆栈，用户看到的就是「点刷新节目单，App 直接没了」。
            //
            // 现在只有 8KB 缓冲常驻，SAX 边读边吐事件。
            // 解析异常在 lambda 里捕成 Result：只有这三类算「节目单坏了」，
            // OutOfMemoryError / LinkageError 之类照旧往上抛，不该被伪装成格式问题。
            val outcome =
                http.fetchStream(url) { stream ->
                    try {
                        Result.success(XmltvParser.parse(stream))
                    } catch (e: org.xml.sax.SAXException) {
                        Result.failure<XmltvParser.Result>(e)
                    } catch (e: javax.xml.parsers.ParserConfigurationException) {
                        Result.failure<XmltvParser.Result>(e)
                    } catch (e: java.io.IOException) {
                        Result.failure<XmltvParser.Result>(e)
                    }
                }

            if (outcome is StreamOutcome.Failure) {
                repository.markRefreshed(url, clock(), describe(outcome))
                return@withContext EpgImportResult.Failed(EpgFailure.NETWORK, outcome.detail)
            }

            val parsed =
                (outcome as StreamOutcome.Success).value.getOrElse { e ->
                    val cannotRead = e is java.io.IOException
                    repository.markRefreshed(
                        url,
                        clock(),
                        if (cannotRead) "读取失败：${e.message}" else "节目单损坏：${e.message}",
                    )
                    return@withContext EpgImportResult.Failed(
                        if (cannotRead) EpgFailure.CANNOT_READ else EpgFailure.BROKEN_XML,
                        e.message,
                    )
                }

            val result = store(parsed.channels, parsed.programmes)
            if (result is EpgImportResult.Done) {
                repository.markRefreshed(url, clock(), "${result.summary.channelCount} 个频道")
            }
            result
        }

    private suspend fun store(
        channels: List<EpgChannel>,
        programmes: List<EpgProgramme>,
    ): EpgImportResult {
        if (channels.isEmpty() || programmes.isEmpty()) {
            // 抓到的是网页错误页 / 空文件时，SAX 往往不报错，只是啥也没解析出来。
            // 这种情况必须明确告诉用户「这不是节目单」，而不是"导入成功 0 条"
            return EpgImportResult.Failed(
                EpgFailure.NOT_XMLTV,
                "频道 ${channels.size} / 节目 ${programmes.size}",
            )
        }

        val guide = mergeEpgGuide(channels, programmes)
        repository.replaceAll(guide)
        return EpgImportResult.Done(repository.summary())
    }

    private fun describe(failure: StreamOutcome.Failure): String =
        when (failure.kind) {
            FetchErrorKind.TIMEOUT -> "超时：节目单源没有在规定时间内响应"
            FetchErrorKind.HTTP -> "抓取失败：${failure.detail ?: "HTTP 错误"}"
            FetchErrorKind.NETWORK -> "网络错误：${failure.detail ?: "不可达"}"
            FetchErrorKind.EMPTY -> "抓到的内容是空的"
        }
}
