package com.leimao.tv.core.network.vod

import com.leimao.tv.core.model.vod.VodCategory
import com.leimao.tv.core.model.vod.VodDetail
import com.leimao.tv.core.model.vod.VodPage
import com.leimao.tv.core.network.FetchErrorKind
import com.leimao.tv.core.network.FetchOutcome
import com.leimao.tv.core.network.HttpClient
import com.leimao.tv.core.network.RequestHeaders

/**
 * 一个影视源的连接信息（VOD-01）。
 *
 * [id] 是数据库里 `source.id`，用来给解析出来的条目打「来自哪个源」的标记 ——
 * 跨源的 `vod_id` 会撞，只有 `(sourceId, sourceKey)` 才唯一。
 *
 * [format] 是源的格式（`applecms` / `moontv`），决定 [VodAdapters.forSource] 造出
 * 哪种适配器。默认 `applecms`：大多数影视源都是苹果CMS 接口，显式标注 moontv 的
 * 才走聚合适配器。
 */
data class VodSourceRef(
    val id: Long,
    val baseUrl: String,
    val headers: RequestHeaders = RequestHeaders.NONE,
    val format: String = FORMAT_APPLECMS,
) {
    companion object {
        const val FORMAT_APPLECMS = "applecms"
        const val FORMAT_MOONTV = "moontv"
    }
}

/** 抓取失败的原因。文案在 UI 层翻译 —— 同一个原因在「添加源」和「刷新首页」两种场景下说法不同。 */
enum class VodFailure {
    /** 连接或读取超时：稍后重试有意义 */
    TIMEOUT,

    /** 服务端回了非 2xx：地址过期或需要鉴权 */
    HTTP,

    /** DNS / 拒绝连接 / TLS 失败 */
    NETWORK,

    /** 通了但响应为空 */
    EMPTY,

    /** 通了、也有内容，但不是影视源该有的结构（填错了地址、或源已换格式） */
    NOT_VOD_SOURCE,
}

sealed interface VodFetchResult<out T> {
    data class Success<T>(val value: T) : VodFetchResult<T>

    data class Failed(
        val reason: VodFailure,
        /** 只放对用户有意义的信息，**不放完整地址**（RULES §2.5） */
        val detail: String? = null,
    ) : VodFetchResult<Nothing>
}

/**
 * 影视源适配器（VOD-01）。
 *
 * 为什么抽成接口而不是直接一个类：这类源的格式在生态里有好几种（苹果CMS / TVBox 的
 * json 配置 / legado 规则），差异全在「怎么拼 URL」和「怎么解析响应」两处。
 * 上层（首页分区、详情、搜索）只关心这五个能力，格式换了不该牵动它们。
 *
 * 实现必须满足：**失败有明确原因，不许静默返回空列表**。空列表的语义是
 * 「这个分类确实没有内容」，与「抓取失败」是两件事。
 */
interface VodSourceAdapter {
    /** 分类。返回 `type` 可能为 null 的条目（源自定义分类），由上层决定要不要展示 */
    suspend fun categories(): VodFetchResult<List<VodCategory>>

    suspend fun list(
        typeId: String,
        page: Int = 1,
    ): VodFetchResult<VodPage>

    /** 详情。`Success(null)` 表示源里查不到这条（下架了），与抓取失败区分开 */
    suspend fun detail(sourceKey: String): VodFetchResult<VodDetail?>

    suspend fun search(
        keyword: String,
        page: Int = 1,
    ): VodFetchResult<VodPage>
}

/**
 * 苹果CMS v10 系接口的实现（VOD-01 的「JSON 规则实现」）。
 *
 * 只做三件事：拼 URL（[VodApi]）→ 发请求（[HttpClient]）→ 解析（[AppleCmsVodParser]）。
 * 这三件都被拆成了可单测的纯函数，这个类本身只剩「把失败翻成人话枚举」的胶水。
 */
class AppleCmsVodAdapter(
    private val http: HttpClient,
    private val source: VodSourceRef,
) : VodSourceAdapter {
    override suspend fun categories(): VodFetchResult<List<VodCategory>> =
        fetch(
            url = VodApi.categoriesUrl(source.baseUrl),
            parse = AppleCmsVodParser::parseCategories,
        )

    override suspend fun list(
        typeId: String,
        page: Int,
    ): VodFetchResult<VodPage> =
        fetch(
            url = VodApi.listUrl(source.baseUrl, typeId, page),
            parse = { AppleCmsVodParser.parseList(it, source.id) },
        )

    override suspend fun detail(sourceKey: String): VodFetchResult<VodDetail?> =
        fetch(
            url = VodApi.detailUrl(source.baseUrl, sourceKey),
            parse = { AppleCmsVodParser.parseDetail(it, source.id) },
        )

    override suspend fun search(
        keyword: String,
        page: Int,
    ): VodFetchResult<VodPage> =
        fetch(
            url = VodApi.searchUrl(source.baseUrl, keyword, page),
            parse = { AppleCmsVodParser.parseList(it, source.id) },
        )

    /**
     * 「发请求 → 解析」的唯一路径。
     *
     * 只捕 [IllegalArgumentException]：解析器用它表达「这份响应不是影视源」。
     * 其他异常（空指针、数组越界）一律不接 —— 那是解析器的 bug，吞掉只会变成
     * 「用户永远看到加载失败」，而开发期看不到任何信号。
     */
    private suspend fun <T> fetch(
        url: String,
        parse: (String) -> T,
    ): VodFetchResult<T> =
        when (val outcome = http.fetch(url, source.headers)) {
            is FetchOutcome.Success ->
                try {
                    VodFetchResult.Success(parse(outcome.text))
                } catch (e: IllegalArgumentException) {
                    VodFetchResult.Failed(VodFailure.NOT_VOD_SOURCE, e.message)
                }

            is FetchOutcome.Failure -> VodFetchResult.Failed(outcome.kind.toVodFailure(), outcome.detail)
        }
}

/** 按源的 format 造适配器。苹果CMS 是默认；moontv 加密订阅走聚合适配器。 */
object VodAdapters {
    fun forSource(
        http: HttpClient,
        source: VodSourceRef,
    ): VodSourceAdapter =
        when (source.format) {
            VodSourceRef.FORMAT_MOONTV -> MoontvVodAdapter(http, source)
            else -> AppleCmsVodAdapter(http, source)
        }
}

internal fun FetchErrorKind.toVodFailure(): VodFailure =
    when (this) {
        FetchErrorKind.TIMEOUT -> VodFailure.TIMEOUT
        FetchErrorKind.HTTP -> VodFailure.HTTP
        FetchErrorKind.NETWORK -> VodFailure.NETWORK
        FetchErrorKind.EMPTY -> VodFailure.EMPTY
    }
