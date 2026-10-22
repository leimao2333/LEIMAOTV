package com.leimao.tv.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.data.EpgFailure
import com.leimao.tv.core.data.EpgImportResult
import com.leimao.tv.core.data.EpgImporter
import com.leimao.tv.core.data.RefreshFailure
import com.leimao.tv.core.data.RefreshResult
import com.leimao.tv.core.data.SourceRefresher
import com.leimao.tv.core.database.repository.EpgImportSummary
import com.leimao.tv.core.database.repository.EpgRepository
import com.leimao.tv.core.database.repository.NewSource
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.layout.SafeAreaContainer
import com.leimao.tv.core.designsystem.theme.TvText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 源管理（SET-03 / LIVE-03 / LIVE-04）。
 *
 * 三条硬要求决定了这一页的形状：
 *
 * 1. **不能靠触摸**：所有操作都是 `FocusableCard`，失败信息用文字常驻而不是 toast；
 * 2. **失败要说清原因**：刷新结果直接显示 `source.lastRefreshNote`（「超时」/「HTTP 404」/
 *    「内容不是播放列表」），用户才知道该改地址还是换时间再试；
 * 3. **首启与设置里是同一页**：区别只在返回键去哪（无源引导页返回 = 退出确认，§6.6）。
 */
@Composable
fun SourceManagerScreen(
    sourceRepository: SourceRepository,
    refresher: SourceRefresher,
    onBack: () -> Unit,
    onSourcesChanged: () -> Unit,
    modifier: Modifier = Modifier,
    isFirstRun: Boolean = false,
    epgImporter: EpgImporter? = null,
    epgRepository: EpgRepository? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var addingEpg by remember { mutableStateOf(false) }
    var epgSummary by remember { mutableStateOf<EpgImportSummary?>(null) }
    var epgSourceUrl by remember { mutableStateOf<String?>(null) }

    val firstActionFocus = remember { FocusRequester() }

    suspend fun reload() {
        sources = sourceRepository.list()
        epgSummary = epgRepository?.summary()
        epgSourceUrl = epgRepository?.sourceUrl()
    }

    LaunchedEffect(Unit) {
        reload()
        if (isFirstRun) firstActionFocus.requestFocus()
    }

    // SAF 选文件：TV 上方向键 + OK 就能完成选择，不需要触摸（LIVE-04）
    val pickFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                val text =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                    }
                if (text.isNullOrBlank()) {
                    status = "读取文件失败：这个文件打不开或没有内容"
                    busy = false
                    return@launch
                }
                val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "本地文件" } ?: "本地文件"
                val result = refresher.importText(name = name, uri = uri.toString(), text = text)
                status = describe(result)
                busy = false
                reload()
                if (result is RefreshResult.Done) onSourcesChanged()
            }
        }

    // 节目单（XMLTV）走同一个 SAF 选文件流程：TV 上没有「把文件拖进来」这回事
    val pickEpg =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val importer = epgImporter ?: return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                val result =
                    importer.importXml {
                        context.contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("这个文件打不开")
                    }
                status = describeEpg(result)
                epgSummary = epgRepository?.summary()
                busy = false
            }
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        SafeAreaContainer {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = if (isFirstRun) "添加第一份订阅源" else "源管理",
                    style = TvText.current.display,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    // 说清边界：应用不提供任何源，用户得用自己的（RULES §2.3）
                    text = "本应用不提供任何频道源，请添加你有权访问的订阅地址（M3U / TXT）。",
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusableCard(
                        onClick = { adding = true },
                        modifier = Modifier.focusRequester(firstActionFocus),
                    ) {
                        Text(text = "添加订阅地址", style = TvText.current.label)
                    }
                    FocusableCard(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                        Text(text = "导入本地文件", style = TvText.current.label)
                    }
                    FocusableCard(
                        onClick = {
                            scope.launch {
                                busy = true
                                val results = refresher.refreshAll()
                                status = summarize(results)
                                busy = false
                                reload()
                                if (results.any { it is RefreshResult.Done }) onSourcesChanged()
                            }
                        },
                    ) {
                        Text(text = "全部刷新", style = TvText.current.label)
                    }
                    if (epgImporter != null) {
                        FocusableCard(onClick = { pickEpg.launch(arrayOf("*/*")) }) {
                            Text(text = "导入节目单", style = TvText.current.label)
                        }
                    }
                    if (epgImporter != null) {
                        FocusableCard(onClick = { addingEpg = true }) {
                            Text(text = "节目单订阅地址", style = TvText.current.label)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                StatusLine(status = status, busy = busy)
                epgSummary?.let { summary ->
                    Text(
                        text =
                            if (summary.programmeCount > 0) {
                                "节目单：${summary.channelCount} 个频道 · ${summary.programmeCount} 档节目"
                            } else {
                                "节目单：尚未导入（支持 XMLTV 的 .xml）"
                            },
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                epgSourceUrl?.let { url ->
                    Text(
                        text = "节目单订阅：${maskUrl(url)} · 每天自动刷新",
                        style = TvText.current.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (sources.isEmpty() && !busy) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "还没有订阅源",
                            style = TvText.current.title,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = sources, key = { it.id }) { source ->
                            SourceRow(
                                source = source,
                                busy = busy,
                                onRefresh = {
                                    scope.launch {
                                        busy = true
                                        val result = refresher.refresh(source.id)
                                        status = describe(result)
                                        busy = false
                                        reload()
                                        if (result is RefreshResult.Done) onSourcesChanged()
                                    }
                                },
                                onToggle = {
                                    scope.launch {
                                        sourceRepository.setEnabled(source.id, !source.enabled)
                                        reload()
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        sourceRepository.delete(source.id)
                                        status = "已删除「${source.name}」及其下的频道"
                                        reload()
                                        onSourcesChanged()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddSourceDialog(
            onDismiss = { adding = false },
            onConfirm = { name, url, userAgent, referer, basicUser, basicPass ->
                adding = false
                scope.launch {
                    busy = true
                    val id =
                        sourceRepository.add(
                            NewSource(
                                name = name.ifBlank { "订阅源" },
                                url = url.trim(),
                                userAgent = userAgent.takeIf { it.isNotBlank() },
                                referer = referer.takeIf { it.isNotBlank() },
                                basicUser = basicUser.takeIf { it.isNotBlank() },
                                basicPass = basicPass.takeIf { it.isNotBlank() },
                            ),
                        )
                    val result = refresher.refresh(id)
                    status = describe(result)
                    busy = false
                    reload()
                    if (result is RefreshResult.Done) onSourcesChanged()
                }
            },
        )
    }

    if (addingEpg) {
        AddEpgSourceDialog(
            onDismiss = { addingEpg = false },
            onConfirm = { url ->
                addingEpg = false
                val importer = epgImporter ?: return@AddEpgSourceDialog
                scope.launch {
                    busy = true
                    // 先登记地址（定时刷新靠它），再立刻抓一次
                    epgRepository?.saveSource(url, System.currentTimeMillis())
                    val result = importer.importUrl(url)
                    status = describeEpg(result)
                    busy = false
                    reload()
                    if (result is EpgImportResult.Done) onSourcesChanged()
                }
            },
        )
    }

    // 返回键：首启时退出，其他时候回上一页（由调用方决定，这里只透传）
    BackHandler { onBack() }
}

@Composable
private fun StatusLine(status: String?, busy: Boolean) {
    val text =
        when {
            busy -> "正在处理…"
            status != null -> status
            else -> ""
        }
    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = TvText.current.hint,
            color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SourceRow(
    source: Source,
    busy: Boolean,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.name,
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(260.dp),
            )
            Text(
                // 地址只显示域名部分：整条地址很可能是带订阅 token 的（RULES §2.5）
                text = maskUrl(source.url),
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (source.enabled) "已启用" else "已停用",
                style = TvText.current.hint,
                color =
                    if (source.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = source.lastRefreshNote ?: "尚未刷新过",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableCard(onClick = onRefresh, enabled = !busy) {
                Text(text = "刷新", style = TvText.current.label)
            }
            FocusableCard(onClick = onToggle) {
                Text(text = if (source.enabled) "停用" else "启用", style = TvText.current.label)
            }
            FocusableCard(onClick = onDelete) {
                Text(text = "删除", style = TvText.current.label)
            }
        }
    }
}

/** 结果 → 一句人话。成功的显示改了什么，失败的显示为什么。 */
internal fun describe(result: RefreshResult): String =
    when (result) {
        is RefreshResult.Done ->
            "「${result.outcome.sourceName}」已更新：${result.outcome.channelCount} 个频道" +
                if (result.outcome.skippedLines > 0) "（跳过 ${result.outcome.skippedLines} 行）" else ""

        is RefreshResult.Failed ->
            when (result.reason) {
                RefreshFailure.TIMEOUT -> "刷新超时：源没有在规定时间内响应"
                RefreshFailure.HTTP -> "刷新失败：源返回了错误状态（${result.detail ?: "HTTP"}）"
                RefreshFailure.NETWORK -> "刷新失败：网络不可达（${result.detail ?: "无法连接"}）"
                RefreshFailure.EMPTY -> "刷新失败：抓到的内容是空的"
                RefreshFailure.NO_CHANNELS -> "刷新失败：内容不是 M3U / TXT 播放列表"
                RefreshFailure.SOURCE_MISSING -> "这个源已经不存在了"
            }
    }

private fun summarize(results: List<RefreshResult>): String {
    val done = results.filterIsInstance<RefreshResult.Done>()
    val channels = done.sumOf { it.outcome.channelCount }
    val failed = results.size - done.size
    return "刷新完成：${done.size} 个源成功、$failed 个失败，共 $channels 个频道"
}

/**
 * 节目单导入结果 → 一句人话。
 *
 * 数字必须是真的：`channelCount` 是**同类合并之后**剩下的频道数，
 * 与合并前的数量差额就是「并掉了多少重复台」，用户看到这个才知道聚合节目单起了作用。
 */
internal fun describeEpg(result: EpgImportResult): String =
    when (result) {
        is EpgImportResult.Done ->
            "节目单导入完成：${result.summary.channelCount} 个频道 · " +
                "${result.summary.programmeCount} 档节目" + latestSuffix(result.summary)

        is EpgImportResult.Failed ->
            when (result.reason) {
                EpgFailure.CANNOT_READ -> "导入失败：文件打不开（${result.detail ?: "没有读取权限"}）"
                EpgFailure.NETWORK -> "抓取失败：${result.detail ?: "无法连接节目单地址"}"
                EpgFailure.NOT_XMLTV -> "导入失败：这个文件不是 XMLTV 节目单（没解析出频道或节目）"
                EpgFailure.BROKEN_XML -> "导入失败：节目单中途损坏，只读到半份（${result.detail ?: "解析出错"}）"
            }
    }

/** 「· 最新一档 10-22 19:00」：让人一眼判断这份节目单是不是过期了 */
private fun latestSuffix(summary: EpgImportSummary): String {
    val latest = summary.latestStartAt ?: return ""
    val text =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(latest))
    return " · 最新一档 $text"
}

/**
 * 地址打码：只留协议与域名。
 *
 * 订阅地址往往带着 token，截图或投屏时一露就等于把账号给了别人（RULES §2.5）。
 */
internal fun maskUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url.take(12) + "…"
    val rest = url.substring(schemeEnd + 3)
    val host = rest.substringBefore('/')
    return url.substring(0, schemeEnd + 3) + host + "/…"
}
