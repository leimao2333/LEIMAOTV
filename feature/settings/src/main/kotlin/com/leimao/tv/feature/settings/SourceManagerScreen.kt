package com.leimao.tv.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.data.BackupImportResult
import com.leimao.tv.core.data.EpgFailure
import com.leimao.tv.core.data.EpgImportResult
import com.leimao.tv.core.data.EpgImporter
import com.leimao.tv.core.data.RefreshFailure
import com.leimao.tv.core.data.RefreshResult
import com.leimao.tv.core.data.SourceBackupImporter
import com.leimao.tv.core.data.SourceHealthStore
import com.leimao.tv.core.data.SourceRefresher
import com.leimao.tv.core.data.VodCatalogRefresher
import com.leimao.tv.core.data.VodCatalogReport
import com.leimao.tv.core.database.repository.EpgImportSummary
import com.leimao.tv.core.database.repository.EpgRepository
import com.leimao.tv.core.database.repository.NewSource
import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.database.repository.SourceImportSummary
import com.leimao.tv.core.database.repository.SourceRepository
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.database.repository.SourceTypeGuesser
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.layout.SafeAreaContainer
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.network.vod.VodFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「这台电视没有文件选择器」时写进状态行的话。
 *
 * 一句提示要同时做到两件事：说明为什么点了没反应，以及**给出这条电视上确实存在**的替代入口 ——
 * 只说「不支持」等于把用户堵在原地。
 */
private const val NO_PICKER_SOURCES = "这台电视没有文件选择器，选不了本地文件；源可以直接用「添加订阅地址」加，不需要选文件。"
private const val NO_PICKER_BACKUP = "这台电视没有文件选择器，选不了本地文件；备份可以改用「从地址导入备份」。"
private const val NO_PICKER_EPG = "这台电视没有文件选择器，选不了本地文件；节目单可以改用「节目单订阅地址」。"
private const val NO_PICKER_EXPORT = "这台电视没有文件选择器，导不出备份文件。"

/**
 * 拉起选择器之后，等多久才认定「它根本没起来」。
 *
 * 2.5 秒：正常的选择器从 `startActivity` 到盖住我们的窗口只要几百毫秒。
 * 拉长到 5 秒会让「点了没反应」的抱怨变成「点了半天才报错」，缩短又容易误判慢机器。
 */
internal const val PICKER_SILENT_MS = 2_500L

/**
 * 这个 activity 是不是**假的选择器**。
 *
 * AOSP 的 `frameworkpackagestubs` 会声明一个 `Stubs$DocumentsStub` 来「占位」，声明里
 * 带着 OPEN_DOCUMENT 的 intent-filter，于是 `queryIntentActivities` 查得到、`startActivity`
 * 也不抛异常 —— 但它不显示任何界面，也永远不回调结果。用户看到的就是「点了没反应」。
 *
 * 判定只认两条硬特征：包名是那个占位包，或类名里带 `Stub`。不去猜「哪些包名像文件管理器」——
 * 第三方文件管理器叫什么的都有，猜错会把能用的机器判成没有。
 */
internal fun documentPickerIsStub(
    packageName: String?,
    className: String?,
): Boolean {
    val pkg = packageName.orEmpty()
    val cls = className.orEmpty()
    return pkg.startsWith(STUB_PACKAGE_PREFIX) || cls.contains("Stub")
}

/**
 * 拉起选择器之后，是否该判定它**静默失败**了。
 *
 * 唯一可靠的信号是窗口焦点：真的选择器一出现就会把焦点从我们这里拿走。所以「够久了」
 * 且「焦点还在自己身上」两个条件同时成立才算没起来。回调里会把 awaitingSince 清 0，
 * 于是「用户真的选了文件（或按返回取消）」这条路径不可能走到这里。
 */
internal fun pickerStillSilent(
    awaitingSince: Long,
    nowMs: Long,
    windowHasFocus: Boolean,
): Boolean = awaitingSince > 0L && nowMs - awaitingSince >= PICKER_SILENT_MS && windowHasFocus

/** 占位包的名字前缀。只做前缀匹配：镜像不同后缀也不一样 */
private const val STUB_PACKAGE_PREFIX = "frameworkpackagestubs"

/** 顺着 ContextWrapper 找到 Activity（判断窗口焦点要用）。找不到就返回 null，由调用方当成「没焦点」 */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * 这台机器有没有一个**真的能用**的文件选择器。
 *
 * 两个条件都要满足：查得到 handler，且不是占位实现。
 * 查询失败（包可见性被 ROM 收紧等）一律当成「没有」：宁可提示用户改用地址导入，
 * 也不要让他按下去之后对着不动的屏幕猜。
 */
private fun Context.canResolveDocumentPicker(): Boolean {
    val intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
    val handlers =
        runCatching { packageManager.queryIntentActivities(intent, 0) }
            .getOrNull()
            .orEmpty()
    return handlers.any { !documentPickerIsStub(it.activityInfo.packageName, it.activityInfo.name) }
}

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
    /**
     * 影视源的刷新器（VOD-03）。可空是为了让预览与旧测试不必造全套依赖；
     * 为 null 时添加影视源只入库不预抓，首页首次进入时再抓。
     */
    vodRefresher: VodCatalogRefresher? = null,
    /** 源健康检查（LIVE-09）。可空；为 null 时不显示「健康检查」按钮 */
    healthChecker: com.leimao.tv.core.data.SourceHealthChecker? = null,
    /**
     * 从地址导入源备份（SET-10）。可空：为 null 时不显示这个入口。
     *
     * 电视上没有好用的文件管理器，别人给的清单往往就是一个 URL —— 只能选本地文件等于
     * 让用户先想办法把 JSON 落到盒子里。
     */
    backupImporter: SourceBackupImporter? = null,
    /**
     * 健康检查结果的会话内缓存（SET-09）。
     *
     * 默认在页面内建一个：预览与测试不必造依赖。真正跑应用时由 `AppContainer` 传进来 ——
     * 那样离开页面再回来，卡片上的「正常 / 失效」还在。
     */
    healthStore: SourceHealthStore = remember { SourceHealthStore() },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var addingEpg by remember { mutableStateOf(false) }
    var importingBackup by remember { mutableStateOf(false) }
    var epgSummary by remember { mutableStateOf<EpgImportSummary?>(null) }
    var epgSourceUrl by remember { mutableStateOf<String?>(null) }

    /** 每条源的条目数（SET-09）：直播数频道、影视数影片 */
    var entryCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }

    /** 健康状态按 sourceId 索引，来自会话缓存（SET-09） */
    val healthEntries by healthStore.entries.collectAsState()

    /**
     * 有弹层开着时**不组合底层内容**（SET-12）。
     *
     * 树内弹层没有窗口边界，方向键能走到后面的按钮上；锁焦点最可靠的办法不是拦截按键，
     * 而是「后面根本没有可聚焦的东西」。副作用是遮罩后面看不到列表 —— 可接受：
     * 弹层本来就该独占注意力，比「焦点跑到看不见的按钮上」好得多。
     */
    val modalOpen = adding || addingEpg || importingBackup

    val firstActionFocus = remember { FocusRequester() }
    // 另外两个弹层的入口按钮：关闭弹层后要把焦点还给**打开它的那个**按钮，
    // 否则回来的是一个没有焦点的页面（见 pendingEntryFocus）
    val addEpgFocus = remember { FocusRequester() }
    val importBackupFocus = remember { FocusRequester() }

    /**
     * 弹层关掉之后要接住焦点的入口按钮。
     *
     * 树内弹层打开时底层**整棵不组合**（[modalOpen]），关掉时那棵树是新建的，
     * 没有任何元素持有焦点 —— 焦点系统不会「记得」用户是从哪进来的，
     * 于是下一次方向键被用来做初始焦点搜索，看起来像「遥控器失灵了」。
     * 所以每次关闭都记下入口，等底层重新布局后再点名请求一次。
     */
    var pendingEntryFocus by remember { mutableStateOf<FocusRequester?>(null) }

    LaunchedEffect(pendingEntryFocus) {
        val target = pendingEntryFocus ?: return@LaunchedEffect
        // 两帧：第一帧底层节点刚进组合，第二帧才测量完，太早请求会落到还没布局的节点上
        withFrameNanos { }
        withFrameNanos { }
        runCatching { target.requestFocus() }
        pendingEntryFocus = null
    }

    suspend fun reload() {
        sources = sourceRepository.list()
        entryCounts = sourceRepository.entryCounts()
        epgSummary = epgRepository?.summary()
        epgSourceUrl = epgRepository?.sourceUrl()
    }

    /**
     * 备份导入之后把**所有**新增的源刷一遍（SET-14 混合导入）。
     *
     * 两份 id 必须分开跑：直播源交给 [refresher]「下载播放列表 → 解析频道」，
     * 影视源交给 [vodRefresher]「调采集接口 → 按分类抓条目」。混在一起的结果是
     * 每条影视源都如实报一次「内容不是 M3U / TXT 播放列表」——
     * 用户刚导入就看到满屏失败，还以为整份备份都坏了。
     *
     * 不刷更不行：卡片上全是「还没有频道 / 还没有影片」，用户得逐条点刷新才知道能不能用。
     */
    suspend fun refreshImported(
        summary: SourceImportSummary,
        viaMirror: String? = null,
    ): String {
        val liveResults = refreshConcurrently(summary.addedLiveIds, refresher)
        val vodSources =
            if (vodRefresher == null) {
                emptyList<Source>()
            } else {
                summary.addedVodIds.mapNotNull { sourceRepository.find(it) }
            }
        val vodReport = vodRefresher?.refresh(vodSources)
        return describeImport(summary, liveResults, viaMirror, vodReport)
    }

    LaunchedEffect(Unit) {
        reload()
        if (isFirstRun) firstActionFocus.requestFocus()
    }

    // ── SAF 选文件 ─────────────────────────────────────────────────────────────
    //
    // TV 上方向键 + OK 就能完成选择，不需要触摸（LIVE-04）。但**不是每台电视都有文件选择器**：
    // 小米 / 海信 / TCL 与各类盒子的精简 ROM 常常不带 DocumentUI，这时
    // `ActivityResultLauncher.launch()` 会在 startActivity 那一步**同步抛
    // ActivityNotFoundException** —— 没人接住就是「点一下『导入本地文件』整个应用闪退」
    // （2026-11-01 实测，v0.5.0.0 / v0.5.0.1 都有）。
    //
    // 所以每条入口都走 [launchSaf]：先 OpenDocument（能拿到长期读权限，体验最好），
    // 系统里没有就退到 GetContent（有些盒子的「文件管理」只实现了后者），
    // 两条都走不通才写一行提示。结果由系统能力决定，不由崩溃决定。

    /**
     * 这台机器到底有没有**能用**的文件选择器。
     *
     * 比「启动时抛不抛异常」更进一步：Android TV 的部分镜像（含 AOSP 的 android-tv 系统镜像）
     * 把 DocumentsUI 换成了一个空壳实现，`OPEN_DOCUMENT` 能解析、Activity 能启动、
     * 但界面不出现、结果永不回调 —— 从代码角度看「成功」，从用户角度看**点了没反应**。
     * 这是 2026-11-02 在 Android 11 TV 模拟器上实测到的：后台日志显示启动的是
     * `frameworkpackagestubs/.Stubs$DocumentsStub`。所以启动前先问一句，别让用户白等。
     */
    val pickerAvailable = remember(context) { context.canResolveDocumentPicker() }

    /**
     * 最近一次拉起选择器的时刻（`elapsedRealtime`）。0 表示「没有待确认的选择器」。
     *
     * 用途是兜住 pickerAvailable 判断不到的那批 ROM：预检看起来正常、实际还是没起来。
     * 拉起后给几秒，如果窗口**一直有焦点**（选择器盖上来必然抢走焦点），就说明它没开。
     * 任何回调（选了文件 / 用户按返回取消）都会把它清 0，所以「3 秒内返回」不会被误判。
     */
    var pickerAwaitingSince by remember { mutableStateOf(0L) }

    /** 拉起系统选文件；两条通道都走不通（或压根没起来）时把原因写在状态行，不把应用带走。 */
    fun launchSaf(
        primary: () -> Unit,
        missing: String,
        /** GET_CONTENT 退路。导出用 CreateDocument，没有等价退路，传 null 即可 */
        fallback: (() -> Unit)? = null,
    ) {
        // 预检不过就别拉起：让它「成功启动、静默失败」比直接告诉用户更糟
        if (!pickerAvailable) {
            status = missing
            return
        }
        // 接 Exception 而不是只接 ActivityNotFoundException：同一个「拉不起来」还有别的形态 ——
        // 选择器存在但缺权限会抛 SecurityException，ROM 把它标成不导出会是别的运行时异常。
        // 这些全都是环境差异，不是我们的逻辑错，一律降级成一行提示。
        try {
            primary()
        } catch (e: Exception) {
            if (fallback == null) {
                status = missing
                return
            }
            try {
                fallback()
            } catch (e2: Exception) {
                status = missing
                return
            }
        }
        // 拉起来了，但可能是空壳：几秒后回头看窗口焦点还在这儿没有
        pickerAwaitingSince = SystemClock.elapsedRealtime()
        scope.launch {
            delay(PICKER_SILENT_MS)
            val stillSilent =
                pickerStillSilent(
                    awaitingSince = pickerAwaitingSince,
                    nowMs = SystemClock.elapsedRealtime(),
                    windowHasFocus = context.findActivity()?.hasWindowFocus() ?: false,
                )
            if (stillSilent) {
                status = missing
                pickerAwaitingSince = 0L
            }
        }
    }

    /** 备份文本 → 落地 + 刷新，返回状态行那句话。选备份文件 / 选到备份的普通文件共用 */
    suspend fun importBackupText(text: String): String =
        runCatching {
            // 走 importer 而不是直接调仓储：备份里可能带 settings 段（LIVE-08），
            // 两条入口（文件 / 地址）必须都能把它落地
            val summary =
                backupImporter?.importText(text)?.let { result ->
                    when (result) {
                        is BackupImportResult.Done -> result.summary
                        is BackupImportResult.Failed -> throw IllegalArgumentException(result.detail)
                    }
                } ?: sourceRepository.importJson(text)
            // 导入完顺手刷一遍新增的源（SET-03 / SET-14）：不刷的话卡片上全是
            // 「还没有频道」，用户还得逐条点刷新才知道这份备份到底能不能用
            refreshImported(summary)
        }.getOrElse { e ->
            "导入失败：${e.message ?: "不是有效的源备份文件"}"
        }

    fun importSourceFile(uri: Uri) {
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
            // 用户手里是什么就导什么（SET-14）：选到的可能是一份**源备份**（`.json`，
            // 或者被改过扩展名的备份），而不是播放列表。让他先分清「该点哪个导入按钮」
            // 是把机器的活交给人的设计 —— 认错的代价是一句「这个文件不是可识别的源」
            if (looksLikeSourceBackupText(text)) {
                status = importBackupText(text)
                busy = false
                reload()
                onSourcesChanged()
                return@launch
            }
            // 兜住整段导入：文本解析、落库、刷新任何一步抛出来都只是「这个文件导不进去」，
            // 不能让它顺着协程抛到主线程变成闪退
            val result =
                runCatching { refresher.importText(name = name, uri = uri.toString(), text = text) }
                    .getOrElse { e ->
                        status = "导入失败：${e.message ?: "这个文件不是可识别的源"}（$name）"
                        busy = false
                        return@launch
                    }
            status = describe(result)
            busy = false
            reload()
            if (result is RefreshResult.Done) onSourcesChanged()
        }
    }

    // 每个选择器回调的第一件事都是「把静默检测清掉」：能回到这里就说明选择器确实起来了，
    // 无论用户选了文件还是按返回取消（uri 为 null）
    val pickFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pickerAwaitingSince = 0L
            if (uri != null) importSourceFile(uri)
        }
    val pickFileCompat =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            pickerAwaitingSince = 0L
            if (uri != null) importSourceFile(uri)
        }

    // 导入源备份（SET-03）：与「导入本地文件」不同，这里读的是我们自己导出的 JSON
    fun importBackupFile(uri: Uri) {
        scope.launch {
            busy = true
            val text =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
            if (text.isNullOrBlank()) {
                status = "导入失败：文件打不开或没有内容"
                busy = false
                return@launch
            }
            status = importBackupText(text)
            busy = false
            reload()
            onSourcesChanged()
        }
    }

    val pickJson =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pickerAwaitingSince = 0L
            if (uri != null) importBackupFile(uri)
        }
    val pickJsonCompat =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            pickerAwaitingSince = 0L
            if (uri != null) importBackupFile(uri)
        }

    // 导出源备份（SET-03）：SAF 创建文件，写到用户选的位置
    val createJson =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            pickerAwaitingSince = 0L
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                status =
                    runCatching {
                        val json = sourceRepository.exportJson()
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(json.toByteArray(Charsets.UTF_8))
                            } ?: throw java.io.IOException("无法写入所选位置")
                        }
                        "已导出 ${sources.size} 个源到备份文件"
                    }.getOrElse { e ->
                        "导出失败：${e.message ?: "无法写入文件"}"
                    }
                busy = false
            }
        }

    // 节目单（XMLTV）走同一个 SAF 选文件流程：TV 上没有「把文件拖进来」这回事
    fun importEpgFile(uri: Uri) {
        val importer = epgImporter ?: return
        scope.launch {
            busy = true
            // 同上：读文件、解析 XML 都可能抛，落地成一行提示而不是闪退
            val result =
                runCatching {
                    importer.importXml {
                        context.contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("这个文件打不开")
                    }
                }.getOrElse { e ->
                    status = "导入节目单失败：${e.message ?: "这个文件不是有效的 XMLTV"}"
                    busy = false
                    return@launch
                }
            status = describeEpg(result)
            epgSummary = epgRepository?.summary()
            busy = false
        }
    }

    val pickEpg =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pickerAwaitingSince = 0L
            if (uri != null) importEpgFile(uri)
        }
    val pickEpgCompat =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            pickerAwaitingSince = 0L
            if (uri != null) importEpgFile(uri)
        }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!modalOpen) {
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

                        // horizontalScroll：六个按钮在窄屏 / 大字号下放不下一行，不加的话最后一张
                        // 卡片会被挤压换行、撑高整行，把列表顶出屏幕（2026-10-24 模拟器实测抓出）
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FocusableCard(
                                onClick = { adding = true },
                                modifier = Modifier.focusRequester(firstActionFocus),
                            ) {
                                Text(text = "添加订阅地址", style = TvText.current.label)
                            }
                            FocusableCard(
                                onClick = {
                                    launchSaf(
                                        primary = { pickFile.launch(arrayOf("*/*")) },
                                        fallback = { pickFileCompat.launch("*/*") },
                                        missing = NO_PICKER_SOURCES,
                                    )
                                },
                            ) {
                                Text(text = "导入本地文件", style = TvText.current.label)
                            }
                            FocusableCard(
                                onClick = {
                                    // 同样不禁用按钮，只是忙时不重复触发（连按两下会并发刷两轮）
                                    if (!busy) {
                                        scope.launch {
                                            busy = true
                                            val results = refresher.refreshAll()
                                            status = summarize(results)
                                            busy = false
                                            reload()
                                            if (results.any { it is RefreshResult.Done }) onSourcesChanged()
                                        }
                                    }
                                },
                            ) {
                                Text(text = "全部刷新", style = TvText.current.label)
                            }
                            if (healthChecker != null) {
                                FocusableCard(
                                    onClick = {
                                        // 忙时不是「禁用」，而是「按下不做事」：禁用会让焦点从这颗按钮上
                                        // 消失、跳回页面第一个按钮（RULES §6.2 第 2 条：焦点不许丢）。
                                        // 探测本身要几秒，用户很可能连按两下。
                                        if (!busy) {
                                            scope.launch {
                                                busy = true
                                                // 状态行先给出「在查」的明确提示：并发探测也要几秒，
                                                // 不说明的话用户只会觉得按钮按了没反应
                                                status = "正在逐条探测可用性…"
                                                val healths = healthChecker.checkAll()
                                                // 结论沉淀到卡片上（SET-09）：报告只闪一次的毛病在这里收口
                                                healthStore.record(healths)
                                                status = healthSummary(healths)
                                                busy = false
                                            }
                                        }
                                    },
                                ) {
                                    Text(text = "健康检查", style = TvText.current.label)
                                }
                            }
                            if (epgImporter != null) {
                                FocusableCard(
                                    onClick = {
                                        launchSaf(
                                            primary = { pickEpg.launch(arrayOf("*/*")) },
                                            fallback = { pickEpgCompat.launch("*/*") },
                                            missing = NO_PICKER_EPG,
                                        )
                                    },
                                ) {
                                    Text(text = "导入节目单", style = TvText.current.label)
                                }
                            }
                            if (epgImporter != null) {
                                FocusableCard(
                                    onClick = { addingEpg = true },
                                    modifier = Modifier.focusRequester(addEpgFocus),
                                ) {
                                    Text(text = "节目单订阅地址", style = TvText.current.label)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FocusableCard(
                                onClick = {
                                    // 导出只有一条通道（CreateDocument 没有 GET_CONTENT 那种退路），
                                    // 但也同样要过预检与静默检测 —— 否则「导出备份」在没选择器的电视上
                                    // 同样是按下去毫无动静
                                    launchSaf(
                                        primary = { createJson.launch("leimao-sources.json") },
                                        missing = NO_PICKER_EXPORT,
                                    )
                                },
                            ) {
                                Text(text = "导出备份", style = TvText.current.label)
                            }
                            FocusableCard(
                                onClick = {
                                    launchSaf(
                                        primary = { pickJson.launch(arrayOf("application/json", "*/*")) },
                                        fallback = { pickJsonCompat.launch("*/*") },
                                        missing = NO_PICKER_BACKUP,
                                    )
                                },
                            ) {
                                Text(text = "导入备份", style = TvText.current.label)
                            }
                            if (backupImporter != null) {
                                FocusableCard(
                                    onClick = { importingBackup = true },
                                    modifier = Modifier.focusRequester(importBackupFocus),
                                ) {
                                    Text(text = "从地址导入备份", style = TvText.current.label)
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
                            // weight(1f) 而不是 fillMaxSize：Column 里非 weight 子项会拿满整个父高度，
                            // 排在头部内容之后就被顶出屏幕外，列表 / 空态整个看不见
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "还没有订阅源",
                                    style = TvText.current.title,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(items = sources, key = { it.id }) { source ->
                                    SourceRow(
                                        source = source,
                                        entryCount = entryCounts[source.id],
                                        health = healthEntries[source.id],
                                        busy = busy,
                                        isFirst = sources.first().id == source.id,
                                        isLast = sources.last().id == source.id,
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
                                        onMove = { direction ->
                                            scope.launch {
                                                sourceRepository.move(source.id, direction)
                                                reload()
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
                                                // 同 id 被复用时不该带着上一条源的体检结论（SET-09）
                                                healthStore.forget(source.id)
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
        }
    }

    if (adding) {
        AddSourceDialog(
            onDismiss = {
                adding = false
                pendingEntryFocus = firstActionFocus
            },
            onConfirm = { declared, url, userAgent, referer, basicUser, basicPass ->
                adding = false
                pendingEntryFocus = firstActionFocus
                scope.launch {
                    busy = true
                    // 「自动」按地址判定（SET-14）。这里拿不到正文（还没抓），
                    // 所以只用地址特征；猜不出时兜底直播 —— 直播源失败看得见，影视源失败是静默的
                    val type =
                        if (declared == SourceType.AUTO) {
                            SourceTypeGuesser.resolve(null, url.trim())
                        } else {
                            declared
                        }
                    // 贴进来的是**源备份**（`.json`）时直接按备份导入（SET-10）。
                    // 不这么做的话会先建一条源、再去当播放列表解析，最后报「内容不是
                    // M3U / TXT 播放列表」—— 用户只会以为自己贴错了地址。
                    // 明确选了「影视」时不走这条：影视采集接口也可能挂在 .json 路径上
                    val importer = backupImporter
                    if (type != SourceType.VOD && importer != null && looksLikeSourceBackupUrl(url)) {
                        when (val result = importer.importFrom(url.trim())) {
                            is BackupImportResult.Done -> {
                                status = refreshImported(result.summary, result.viaMirror)
                                reload()
                                onSourcesChanged()
                            }

                            is BackupImportResult.Failed -> status = "导入失败：${result.detail}"
                        }
                        busy = false
                        return@launch
                    }

                    val id =
                        sourceRepository.add(
                            NewSource(
                                type = type,
                                // 名字由地址自动生成（SET-03）：让用户在遥控器上敲一个
                                // 「订阅源」既费劲又没有信息量，主机名反而一眼能认出是哪条
                                name = sourceNameFromUrl(url, type),
                                url = url.trim(),
                                userAgent = userAgent.takeIf { it.isNotBlank() },
                                referer = referer.takeIf { it.isNotBlank() },
                                basicUser = basicUser.takeIf { it.isNotBlank() },
                                basicPass = basicPass.takeIf { it.isNotBlank() },
                            ),
                        )
                    if (type == SourceType.VOD) {
                        // 影视源不走直播刷新（那是播放列表解析，必然失败）。
                        // 加完立刻抓一次，好让用户当场知道这个接口到底通不通。
                        val source = sourceRepository.find(id)
                        status = describeVod(vodRefresher?.refresh(listOfNotNull(source)))
                        busy = false
                        reload()
                        // 刻意**不**调用 onSourcesChanged：直播链路没有任何变化，
                        // 触发启动落点重算只会把只加了影视源的用户甩回「还没有源」的引导页
                    } else {
                        val result = refresher.refresh(id)
                        status = describe(result)
                        busy = false
                        reload()
                        if (result is RefreshResult.Done) onSourcesChanged()
                    }
                }
            },
        )
    }

    if (addingEpg) {
        AddEpgSourceDialog(
            onDismiss = {
                addingEpg = false
                pendingEntryFocus = addEpgFocus
            },
            onConfirm = { url ->
                addingEpg = false
                pendingEntryFocus = addEpgFocus
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

    if (importingBackup) {
        ImportBackupDialog(
            onDismiss = {
                importingBackup = false
                pendingEntryFocus = importBackupFocus
            },
            onConfirm = { url ->
                importingBackup = false
                pendingEntryFocus = importBackupFocus
                val importer = backupImporter ?: return@ImportBackupDialog
                scope.launch {
                    busy = true
                    when (val result = importer.importFrom(url)) {
                        is BackupImportResult.Done -> {
                            status = refreshImported(result.summary, result.viaMirror)
                            reload()
                            onSourcesChanged()
                        }

                        is BackupImportResult.Failed -> status = "导入失败：${result.detail}"
                    }
                    busy = false
                }
            },
        )
    }

    // 返回键：有弹层先关弹层（树内弹层没有窗口，BACK 不会自动关它），否则交给调用方
    BackHandler {
        when {
            adding -> {
                adding = false
                pendingEntryFocus = firstActionFocus
            }
            addingEpg -> {
                addingEpg = false
                pendingEntryFocus = addEpgFocus
            }
            importingBackup -> {
                importingBackup = false
                pendingEntryFocus = importBackupFocus
            }
            else -> onBack()
        }
    }
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

/**
 * 一条源 = 一张卡片（SET-09）。
 *
 * 卡片上四块信息，顺序按「用户最想先知道什么」排：
 * ① 名字 + 类型徽标（直播 / 影视）+ 条目数 —— 先分清这是哪条源、有没有东西；
 * ② 地址（打码）与最近刷新时间 —— 判断「列表是新的还是旧的」；
 * ③ 健康状态（正常 / 慢 / 失效 / 未测）+ 上次失败原因 —— 判断「该刷新、该等，还是该换源」；
 * ④ 操作按钮。
 *
 * **失效源置灰但不消失**：它可能只是今天抽风，直接藏起来等于把用户配过的源弄丢了；
 * 灰掉 + 写明原因，用户才知道该停用还是再等等。停用按钮就在手边，一键。
 */
@Composable
private fun SourceRow(
    source: Source,
    entryCount: Int?,
    health: SourceHealthStore.Entry?,
    busy: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onRefresh: () -> Unit,
    onMove: (Int) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val isVod = source.type == SourceType.VOD
    // 置灰的两种情形：用户主动停用、探测判定失效。两种都保留在列表里
    val dimmed = !source.enabled || health?.health?.ok == false

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (dimmed) DIMMED_ALPHA else 1f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.name,
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(240.dp),
            )
            TypeBadge(isVod = isVod)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                // 条目数是「这条源到底抓到了东西没有」的直接证据
                text = describeEntryCount(source.type, entryCount),
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(120.dp),
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
                maxLines = 1,
                modifier = Modifier.width(64.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            HealthBadge(health = health)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // 地址只显示域名部分：整条地址很可能是带订阅 token 的（RULES §2.5）
                text = maskUrl(source.url),
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = describeRefreshTime(source.updatedAt),
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 上次失败原因单独一行：与健康状态不是一回事（刷新失败 vs 探测连不上），
        // 混在一行里会让人以为「刷新失败」等于「源坏了」
        source.lastRefreshNote?.let { note ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "上次刷新：$note",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 这几个按钮**不做禁用**（不用 enabled=false）：禁用会让焦点从按钮上消失、
            // 跳回页面第一个按钮（RULES §6.2 第 2 条）。边界与忙碌改成「按下不做事」，
            // 焦点留在原地，用户继续按也没问题。
            FocusableCard(onClick = { if (!isFirst) onMove(-1) }) {
                Text(text = "上移", style = TvText.current.label)
            }
            FocusableCard(onClick = { if (!isLast) onMove(1) }) {
                Text(text = "下移", style = TvText.current.label)
            }
            FocusableCard(onClick = { if (!busy) onRefresh() }) {
                Text(text = "刷新", style = TvText.current.label)
            }
            FocusableCard(onClick = onToggle) {
                // 失效时这个按钮就是「一键停用」：文案随状态变，用户不用先想该按哪个
                Text(text = if (source.enabled) "停用" else "启用", style = TvText.current.label)
            }
            FocusableCard(onClick = onDelete) {
                Text(text = "删除", style = TvText.current.label)
            }
        }
    }
}

/** 类型徽标：直播 / 影视。底色区分，不靠文字细看（SET-09） */
@Composable
private fun TypeBadge(isVod: Boolean) {
    Text(
        text = if (isVod) "影视" else "直播",
        style = TvText.current.hint,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier =
            Modifier
                .background(
                    if (isVod) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 健康徽标（SET-09）：正常 / 慢 / 失效 / 未测。
 *
 * 「未测」必须显式写出来：空白会让人以为这条源没问题，而它其实从来没被检查过。
 */
@Composable
private fun HealthBadge(health: SourceHealthStore.Entry?) {
    val ok = health?.health?.ok
    val slow = ok == true && health.health.latencyMs >= SLOW_SOURCE_MS
    val color =
        when {
            ok == null -> MaterialTheme.colorScheme.onSurfaceVariant
            !ok -> MaterialTheme.colorScheme.error
            slow -> WARN_COLOR
            else -> MaterialTheme.colorScheme.primary
        }
    Text(
        text = describeHealth(health),
        style = TvText.current.hint,
        color = color,
        maxLines = 1,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = BADGE_TINT_ALPHA))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 健康状态 → 一句人话（SET-09）。
 *
 * 带延迟：「正常」两个字分不出 80ms 和 2.8s，而后者正是用户刷不动列表的原因。
 * 失效时把原因带出来 —— 只说「失效」，用户还得再点一次健康检查才知道为什么。
 */
internal fun describeHealth(
    entry: SourceHealthStore.Entry?,
    slowMs: Long = SLOW_SOURCE_MS,
): String =
    when {
        entry == null -> "未测"
        !entry.health.ok -> "失效（${entry.health.detail ?: "未知原因"}）"
        entry.health.latencyMs >= slowMs -> "慢 ${formatLatency(entry.health.latencyMs)}"
        else -> "正常 ${formatLatency(entry.health.latencyMs)}"
    }

/** 延迟格式化：1s 以下给毫秒，以上给一位小数的秒 —— 「2843ms」在客厅里读不出来 */
internal fun formatLatency(ms: Long): String =
    if (ms < 1_000) {
        "${ms}ms"
    } else {
        String.format(Locale.US, "%.1fs", ms / 1000.0)
    }

/** 条目数 → 一句人话（SET-09）。直播数频道、影视数影片，量词不能混 */
internal fun describeEntryCount(
    type: String,
    count: Int?,
): String =
    when {
        count == null || count <= 0 -> if (type == SourceType.VOD) "还没有影片" else "还没有频道"
        type == SourceType.VOD -> "$count 部影片"
        else -> "$count 个频道"
    }

/**
 * 最近刷新时间 → 相对时间（SET-09）。
 *
 * 用相对时间而不是「10-25 14:03」：用户看这张卡片时问的是「这份列表新不新」，
 * 相对时间直接回答这个问题；绝对时间还得自己减一遍。
 */
internal fun describeRefreshTime(
    at: Long,
    now: Long = System.currentTimeMillis(),
): String {
    if (at <= 0) return "从未刷新"
    val minutes = (now - at).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "刚刚刷新"
        minutes < 60 -> "$minutes 分钟前刷新"
        minutes < 60 * 24 -> "${minutes / 60} 小时前刷新"
        else -> "${minutes / (60 * 24)} 天前刷新"
    }
}

/** 失效 / 停用的卡片压到多暗：还能读清，但一眼看得出「这条现在不用管它」 */
private const val DIMMED_ALPHA = 0.55f

/** 徽标底色的透明度：够看出是一块，又不至于把文字压得看不清 */
private const val BADGE_TINT_ALPHA = 0.18f

/** 「慢」的提示色。不用 error（那是失效），也不用 primary（那是正常） */
private val WARN_COLOR = Color(0xFFFFB300)

/** 结果 → 一句人话。成功的显示改了什么，失败的显示为什么。 */
internal fun describe(result: RefreshResult): String =
    when (result) {
        is RefreshResult.Done -> {
            val base =
                "「${result.outcome.sourceName}」已更新：${result.outcome.channelCount} 个频道" +
                    if (result.outcome.skippedLines > 0) "（跳过 ${result.outcome.skippedLines} 行）" else ""
            // 订阅自己声明了节目单地址时顺带说一句（LIVE-27）：不提示的话，
            // 用户不知道播放页里的节目单是哪来的，也不知道该怎么换
            result.outcome.epgNote?.let { "$base · $it" } ?: base
        }

        is RefreshResult.Failed ->
            when (result.reason) {
                RefreshFailure.TIMEOUT -> "刷新超时：源没有在规定时间内响应"
                RefreshFailure.HTTP -> "刷新失败：源返回了错误状态（${result.detail ?: "HTTP"}）"
                RefreshFailure.NETWORK -> "刷新失败：网络不可达（${result.detail ?: "无法连接"}）"
                RefreshFailure.EMPTY -> "刷新失败：抓到的内容是空的"
                RefreshFailure.NO_CHANNELS -> "刷新失败：内容不是 M3U / TXT 播放列表"
                RefreshFailure.BACKUP_JSON -> "这个地址是源备份（JSON），不是播放列表 —— 用「从地址导入备份」导入它"
                RefreshFailure.SOURCE_MISSING -> "这个源已经不存在了"
            }
    }

/**
 * 影视源刷新结果 → 一句人话（VOD-03）。
 *
 * 与 [describe] 分开写：两种源「成功」的指标不同（频道数 vs 条目数与分类数），
 * 失败原因也各有一套，混在一个 when 里最后只会互相串味。
 */
internal fun describeVod(report: VodCatalogReport?): String {
    if (report == null) return "已添加影视源"
    val outcome = report.outcomes.firstOrNull() ?: return "已添加影视源"
    if (outcome.ok) {
        return "「${outcome.sourceName}」已更新：${outcome.itemCount} 条内容、${outcome.fetchedTypes.size} 个分类"
    }
    val reason = outcome.failure?.let { describeVodFailure(it, outcome.detail) } ?: "源里没有能识别的分类"
    return "「${outcome.sourceName}」已添加，但没抓到内容：$reason"
}

private fun describeVodFailure(
    failure: VodFailure,
    detail: String?,
): String =
    when (failure) {
        VodFailure.TIMEOUT -> "接口超时，源没有在规定时间内响应"
        VodFailure.HTTP -> "接口返回了错误状态（${detail ?: "HTTP"}）"
        VodFailure.NETWORK -> "网络不可达（${detail ?: "无法连接"}）"
        VodFailure.EMPTY -> "接口返回的内容是空的"
        VodFailure.NOT_VOD_SOURCE ->
            "返回的不是影视接口（${detail ?: "格式不符"}），确认地址形如 …/api.php/provide/vod"
    }

private fun summarize(results: List<RefreshResult>): String {
    val done = results.filterIsInstance<RefreshResult.Done>()
    val channels = done.sumOf { it.outcome.channelCount }
    val failed = results.size - done.size
    return "刷新完成：${done.size} 个源成功、$failed 个失败，共 $channels 个频道"
}

/**
 * 并发刷新「刚导入的那批直播源」。
 *
 * 串行刷 6 条源要一分钟以上：每条都要建连 + 下载整份播放列表，而用户在导入框上
 * 只看到一句「正在导入…」，体感就是「导入卡死了 / 超时了」（2026-10-26 实测）。
 * 并发之后总耗时≈最慢的那一条，源与源之间本来就没有先后依赖。
 *
 * 失败不抛：单条源抓不到是常态（对方站点挂了），导入本身已经成功，
 * 结果交给 [describeImport] 汇总成「成功 5 / 失败 1」。
 */
private suspend fun refreshConcurrently(
    ids: List<Long>,
    refresher: SourceRefresher,
): List<RefreshResult> =
    coroutineScope {
        ids.map { id -> async { refresher.refresh(id) } }.awaitAll()
    }

/**
 * 导入结果 → 一句人话（SET-03 / SET-10）。
 *
 * 两条导入路径（本地文件、地址）共用这一句：同一份备份从哪儿来不影响用户想看到什么 ——
 * 进来几个、跳过几个（说明导入是幂等的）、新增的源刷成什么样。
 */
internal fun describeImport(
    summary: SourceImportSummary,
    results: List<RefreshResult>,
    viaMirror: String? = null,
    vodReport: VodCatalogReport? = null,
): String =
    buildString {
        // 直连 GitHub 超时、改走镜像时先说一句：不然用户会奇怪「为什么这次导入慢了几秒」
        if (viaMirror != null) append("（直连超时，已走镜像 $viaMirror）")
        append("已从备份导入 ${summary.added} 个源")
        if (summary.skipped > 0) append("（${summary.skipped} 个地址已存在，跳过）")
        if (results.isNotEmpty()) append("；").append(summarize(results))
        // 混合备份里影视源走的是另一条链路，结果必须分开说：
        // 一句「2 个源成功」既不是频道数也不是影片数，用户看不出哪一半能用
        vodReport?.takeIf { it.hasSource }?.let { append("；").append(summarizeVod(it)) }
        // 备份里带了远程偏好（LIVE-08）就说一句：设置被远程改过，用户有权知道
        summary.settings?.let { append("；已应用远程偏好：${it.describe()}") }
    }

/** 影视那批的刷新结果 → 一句话（SET-14）。与 [summarize] 分开，因为单位不同：部 / 频道 */
private fun summarizeVod(report: VodCatalogReport): String {
    val ok = report.outcomes.count { it.ok }
    val failed = report.outcomes.size - ok
    return "影视 $ok 个源成功、$failed 个失败，共 ${report.totalItems} 部影片"
}

/**
 * 健康检查结果 → 一句人话（LIVE-09）。
 *
 * 抽成 internal 纯函数是为了单测：真实探测要等网络。失效的源**逐个点名**，
 * 「3 个失败」与「「A」「B」连不上」是两种信息量 —— 前者让人不知所措，后者直接能动手。
 */
internal fun healthSummary(healths: List<com.leimao.tv.core.data.SourceHealthChecker.Health>): String {
    if (healths.isEmpty()) return "没有启用的源可查"
    val ok = healths.count { it.ok }
    val slowest = healths.filter { it.ok }.maxByOrNull { it.latencyMs }
    val failed = healths.filter { !it.ok }
    val head = "健康检查：$ok / ${healths.size} 个源可用"
    val slow =
        slowest?.takeIf { it.latencyMs >= SLOW_SOURCE_MS }?.let { "；最慢：「${it.sourceName}」${it.latencyMs}ms" }.orEmpty()
    val dead =
        if (failed.isEmpty()) {
            ""
        } else {
            "；失效：" + failed.joinToString("、") { "「${it.sourceName}」（${it.detail ?: "未知"}）" }
        }
    return head + slow + dead
}

/** 超过这个延时的源在结果里点名提醒：能通但很慢，用户刷不动时第一个该怀疑它 */
internal const val SLOW_SOURCE_MS = 3_000L

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
