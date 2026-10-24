package com.leimao.tv.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 缓存统计与清理（SET-05）。
 *
 * 用真实临时目录跑，不用 mock：这段逻辑的错法只有两种 —— 算错大小、删错东西，
 * 两种都只有落在真文件上才看得出来。
 */
class CacheManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(
        dir: File,
        name: String,
        bytes: Int,
    ): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun `图片缓存与其余文件分开统计`() =
        runTest {
            val root = tmp.newFolder("cache")
            val imageDir = File(root, CacheManager.IMAGE_CACHE_DIR).apply { mkdirs() }
            touch(imageDir, "logo1.jpg", 100)
            touch(imageDir, "logo2.jpg", 300)
            touch(root, "tmp.dat", 50)

            val usage = CacheManager(root) {}.usage()

            assertEquals(400L, usage.imageBytes)
            assertEquals(50L, usage.otherBytes)
            assertEquals(450L, usage.totalBytes)
        }

    @Test
    fun `清理只动图片目录以外的文件`() =
        runTest {
            val root = tmp.newFolder("cache")
            val imageDir = File(root, CacheManager.IMAGE_CACHE_DIR).apply { mkdirs() }
            val logo = touch(imageDir, "logo1.jpg", 100)
            val temp = touch(root, "tmp.dat", 50)

            CacheManager(root) {}.clear()

            // 图片目录由 Coil 负责清，这里连它的文件都不该碰
            assertTrue(imageDir.exists())
            assertTrue(logo.exists())
            assertFalse(temp.exists())
        }

    @Test
    fun `清理会把图片缓存那一份交给 Coil，且只交一次`() =
        runTest {
            val root = tmp.newFolder("cache")
            File(root, CacheManager.IMAGE_CACHE_DIR).apply { mkdirs() }
            var calls = 0

            CacheManager(root) {
                calls++
                // 模仿 Coil：把自己的目录掏空，但不删目录本身
                File(root, CacheManager.IMAGE_CACHE_DIR).listFiles()?.forEach { it.delete() }
            }.clear()

            assertEquals(1, calls)
        }

    @Test
    fun `清理后返回的占用是清理后的`() =
        runTest {
            val root = tmp.newFolder("cache")
            val imageDir = File(root, CacheManager.IMAGE_CACHE_DIR).apply { mkdirs() }
            touch(imageDir, "logo1.jpg", 100)
            touch(root, "tmp.dat", 50)

            val after =
                CacheManager(root) {
                    imageDir.listFiles()?.forEach { it.delete() }
                }.clear()

            assertEquals(0L, after.imageBytes)
            assertEquals(0L, after.otherBytes)
            assertEquals(0L, after.totalBytes)
        }

    @Test
    fun `缓存目录不存在时给 0 而不是抛异常`() =
        runTest {
            val missing = File(tmp.root, "never-created")

            val usage = CacheManager(missing) {}.usage()

            assertEquals(0L, usage.imageBytes)
            assertEquals(0L, usage.otherBytes)
            assertTrue(usage.isEmpty)
        }

    @Test
    fun `图片缓存清理抛异常也不妨碍删掉其余临时文件`() =
        runTest {
            val root = tmp.newFolder("cache")
            val temp = touch(root, "tmp.dat", 50)

            val after =
                CacheManager(root) {
                    throw IllegalStateException("Coil 还没初始化")
                }.clear()

            assertFalse(temp.exists())
            assertEquals(0L, after.totalBytes)
        }
}
