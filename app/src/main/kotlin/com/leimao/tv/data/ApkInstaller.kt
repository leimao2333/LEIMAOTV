package com.leimao.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 下载与安装更新包（SET-07）。
 *
 * 为什么不在 `:core:data` 里：下载要写 `cacheDir`、安装要起系统安装器并拿 `FileProvider` 授权，
 * 这两件事都得碰 Android 运行时；而 `UpdateChecker` 保持「字符串进、结论出」的形态，
 * 单测不必起模拟器。所以**判断**留在 core，**动作**落在 :app。
 */
class ApkInstaller(
    context: Context,
    private val client: OkHttpClient,
) {
    private val appContext = context.applicationContext

    /** 只管自己这一个子目录：清理时不会碰到台标缓存（那是 Coil 的地盘，SET-05） */
    private val updatesDir = File(appContext.cacheDir, DIR_NAME)

    /** 下载进度与结局。用事件流而不是回调，是为了让调用方在**自己的**协程里收进度、改状态 */
    sealed interface DownloadEvent {
        data class Progress(
            val bytesRead: Long,
            /** 对方没给 `Content-Length` 时是 0，表示「不知道总量」 */
            val totalBytes: Long,
        ) : DownloadEvent

        data class Done(val file: File) : DownloadEvent

        /** 已经是能直接念给用户的话，不含地址（RULES §2.5） */
        data class Failed(val reason: String) : DownloadEvent
    }

    /**
     * 流式下载到 `cacheDir/updates`。
     *
     * 落 cacheDir 而不是 filesDir：装完就没用了，系统空间紧张时它该是最先被回收的那份；
     * 放用户数据区反而会长期留下一个几十 MB 的旧包。
     *
     * 下完**校验长度**：截断的 APK 交给系统安装器只会得到一句「解析软件包时出现问题」，
     * 用户根本猜不到是没下完。宁可在这一步就说清楚。
     */
    fun download(
        url: String,
        fileName: String,
    ): Flow<DownloadEvent> =
        flow {
            val target = File(updatesDir, sanitize(fileName))
            // 上一次的残包先清掉：同一目录里躺两份 APK，安装器可能拿到旧的那份
            runCatching {
                updatesDir.mkdirs()
                updatesDir.listFiles()?.forEach { if (it != target) it.delete() }
            }

            val request =
                Request
                    .Builder()
                    .url(url)
                    // Release 附件是二进制流，别让对方按文本做内容协商
                    .header("Accept", "application/octet-stream")
                    .build()

            val response =
                try {
                    client.newCall(request).execute()
                } catch (_: IOException) {
                    emit(DownloadEvent.Failed("连不上下载地址，检查一下网络再重试"))
                    return@flow
                }

            response.use {
                if (!it.isSuccessful) {
                    emit(DownloadEvent.Failed("下载失败（HTTP ${it.code}），过一会儿再试"))
                    return@flow
                }
                val body = it.body
                if (body == null) {
                    emit(DownloadEvent.Failed("对方没有返回内容"))
                    return@flow
                }
                val total = body.contentLength()
                val written = body.byteStream().use { input -> copyToFile(input, target, total) }

                if (total > 0 && written != total) {
                    target.delete()
                    emit(DownloadEvent.Failed("只下到 $written / $total 字节，网络中断了，重试一次"))
                } else {
                    emit(DownloadEvent.Progress(written, maxOf(total, written)))
                    emit(DownloadEvent.Done(target))
                }
            }
        }.catch { error ->
            // 取消（用户按返回离开页面）不是失败，别把它变成一句「写入本地失败」
            if (error is CancellationException) throw error
            emit(DownloadEvent.Failed("写入本地失败（${error.javaClass.simpleName}），清点空间再试"))
        }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<DownloadEvent>.copyToFile(
        input: InputStream,
        target: File,
        total: Long,
    ): Long {
        var written = 0L
        var lastReported = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                // 每 256KB 报一次：再密只是让界面每秒重组几十次，进度条看不出差别
                if (written - lastReported >= PROGRESS_STEP_BYTES) {
                    lastReported = written
                    emit(DownloadEvent.Progress(written, maxOf(total, 0L)))
                }
            }
        }
        return written
    }

    /**
     * 现在能不能装。
     *
     * API 26 起「允许安装未知应用」是按应用授权的（`canRequestPackageInstalls`）；
     * 更老的盒子上它是全局开关，读不到也没法精确跳转，只能先放行、
     * 让安装失败时给一句明确的提示。
     */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            legacyUnknownSourcesAllowed()
        }

    @Suppress("DEPRECATION")
    private fun legacyUnknownSourcesAllowed(): Boolean =
        Settings.Secure.getInt(
            appContext.contentResolver,
            Settings.Secure.INSTALL_NON_MARKET_APPS,
            1,
        ) == 1

    /**
     * 把包交给系统安装器。
     *
     * 不自己调 `PackageInstaller`：系统安装界面会展示应用名、权限与版本，用户有最后一道确认 ——
     * 一个 TV 应用不该拥有「静默替换自己」的能力。
     */
    fun install(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val uri =
            runCatching {
                FileProvider.getUriForFile(appContext, "${appContext.packageName}$AUTHORITY_SUFFIX", file)
            }.getOrNull() ?: return false

        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent) }.isSuccess
    }

    /** 跳到「允许安装未知应用」的设置页；跳不过去返回 false，由界面改用文字说明 */
    fun openInstallPermissionSettings(): Boolean {
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}"),
                )
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent) }.isSuccess
    }

    /**
     * 附件名直接当文件名之前先净化。
     *
     * 名字来自网络（对方的仓库），带 `/` 或 `..` 就能写到 updates 目录外面去。
     * 只取最后一段并强制 `.apk` 结尾，是这里唯一允许的「信任」。
     */
    private fun sanitize(fileName: String): String {
        val base =
            fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
                .ifEmpty { FALLBACK_NAME }
        return if (base.endsWith(".apk", ignoreCase = true)) base else "$base.apk"
    }

    private companion object {
        const val DIR_NAME = "updates"
        const val FALLBACK_NAME = "leimao-tv-update.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val AUTHORITY_SUFFIX = ".fileprovider"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024L
    }
}
