package com.leimao.tv

import com.leimao.tv.data.CrashArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 崩溃本地留档（SYS-04）。
 *
 * 真实崩溃在单测里造不出来，能钉住的是「记录里必须有什么」与「淘汰策略删谁留谁」。
 */
class CrashArchiveTest {
    @Test
    fun `记录含时间 线程 异常与完整堆栈`() {
        val error =
            IllegalStateException("缓存目录没了").apply {
                initCause(IllegalArgumentException("path is blank"))
            }
        val text = CrashArchive.format(Thread.currentThread(), error, atMs = 0L)

        assertTrue(text.contains("time: "))
        assertTrue(text.contains("thread: "))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("缓存目录没了"))
        // 顶层堆栈与 cause 链都要在：只记顶层等于把「Caused by」这条最有用的线索扔了
        assertTrue(text.contains("    at "))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException"))
    }

    @Test
    fun `自引用 cause 不会死循环`() {
        // initCause 在 Java 层就禁止自引用（会抛 Self-causation not permitted），
        // 反射设 cause 又被 JDK 9+ 的模块强封装挡掉。用一个重写 getCause() 的
        // 异常子类模拟「cause 指回自己」这种只能靠反序列化造出来的畸形结构。
        val error =
            object : RuntimeException("loop") {
                // Kotlin 里 getCause() 映射为 cause 属性，override 属性即可
                override val cause: Throwable
                    get() = this
            }
        val text = CrashArchive.format(Thread.currentThread(), error, atMs = 0L)
        // cause 链只记录一次，不会因为 cause 指回自己而无限循环
        assertEquals(0, Regex("Caused by: ").findAll(text).count())
    }

    @Test
    fun `淘汰只留最新的几份`() {
        val dir = createTempDir()
        val files = (1..7).map { n -> File(dir, "crash-00000000000$n.log").apply { createNewFile() } }
        val toDelete = CrashArchive.prune(files, keep = CrashArchive.KEEP)

        assertEquals(2, toDelete.size)
        // 删的是最旧的两份（文件名里是时间戳，按名排序 = 按时间排序）
        assertEquals("crash-000000000001.log", toDelete[0].name)
        assertEquals("crash-000000000002.log", toDelete[1].name)
        assertFalse(toDelete.contains(files.last()))
        dir.deleteRecursively()
    }
}
