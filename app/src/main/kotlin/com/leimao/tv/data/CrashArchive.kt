package com.leimao.tv.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃本地留档（SYS-04）。
 *
 * 只写本地文件，**不上传**（RULES §1：无账号、无数据上传）。留档的用途是「用户报障时
 * 能拿出东西看」：盒子上复现一次崩溃的成本很高，不记下来就什么线索都没有。
 *
 * 留档数量有上限（[KEEP] 份，超出按时间淘汰最旧的）：崩溃日志一 crash 一份，
 * 一个坏插件式的源能把用户在 10 分钟里砸出几十个崩溃 —— 不设上限就是拿用户
 * 的存储给调试铺路。
 */
object CrashArchive {
    /** 最多留几份崩溃记录 */
    const val KEEP = 5

    const val DIR_NAME = "crash"

    /**
     * 装进默认未捕获异常处理链：先写留档，再交给系统原有的处理者（它负责真正杀进程）。
     * 写留档本身所有异常都吞掉 —— 留档失败绝不能影响崩溃按原样结束。
     */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, format(thread, error, System.currentTimeMillis())) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 一份崩溃记录的内容。抽出纯函数是为了能单测：真实崩溃要等它真的发生 */
    fun format(
        thread: Thread,
        error: Throwable,
        atMs: Long,
    ): String {
        val time =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(atMs))
        return buildString {
            appendLine("time: $time")
            appendLine("thread: ${thread.name}")
            appendLine(error.toString())
            error.stackTrace.forEach { appendLine("    at $it") }
            // cause 链：Android 崩溃最常见的就是「Caused by:」——只记顶层等于把线索扔了
            var cause = error.cause
            while (cause != null && cause !== error) {
                appendLine("Caused by: $cause")
                cause.stackTrace.forEach { appendLine("    at $it") }
                cause = cause.cause
            }
        }
    }

    /** 写一份留档并淘汰超量的旧记录。文件名用时间戳，天然按名排序 = 按时间排序 */
    fun write(
        context: Context,
        content: String,
    ) {
        val dir = File(context.filesDir, DIR_NAME)
        runCatching {
            dir.mkdirs()
            val file = File(dir, "crash-${System.currentTimeMillis()}.log")
            file.writeText(content)
            prune(dir.listFiles()?.toList().orEmpty(), KEEP).forEach { it.delete() }
        }
    }

    /** 该删掉哪些：最新的 [keep] 份保留，其余淘汰。纯函数，排序规则与文件名生成配套 */
    fun prune(
        files: List<File>,
        keep: Int,
    ): List<File> = files.sortedBy { it.name }.dropLast(keep.coerceAtLeast(0))
}
