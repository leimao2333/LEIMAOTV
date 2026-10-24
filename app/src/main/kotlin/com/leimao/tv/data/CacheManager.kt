package com.leimao.tv.data

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.leimao.tv.core.model.cache.CacheUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存统计与清理（SET-05）。
 *
 * 只做两件事：算出磁盘上现在占了多少、把它清掉。**不做自动清理** —— 什么时候腾空间
 * 该由用户决定，后台偷偷删缓存只会让「刚看过的台标又变空白」成为一个说不出原因的怪现象。
 *
 * 统计口径是整个缓存目录，再单独把图片缓存那一块摘出来。之所以不用
 * `ImageLoader.diskCache.size`：那个值要 Coil 自己记过账才准，而用户真正关心的是
 * 「这台机器上我的 App 占了多少地方」—— 目录里有什么才算什么。
 *
 * 构造参数收的是目录和「清图片缓存」这一个动作，而不是 `Context`：这样整段删除逻辑
 * 能在 JVM 单测里用临时目录跑一遍。这类代码一旦出错就是「点了没反应」或者「删错东西」，
 * 不该只靠肉眼审。
 */
class CacheManager(
    private val cacheDir: File,
    private val clearImageCache: () -> Unit,
) {
    suspend fun usage(): CacheUsage = withContext(Dispatchers.IO) { measure() }

    /**
     * 清空缓存，返回清理后的占用。
     *
     * 图片缓存必须交给 Coil 自己清：那是个带 journal 的 DiskCache，
     * 直接 `deleteRecursively` 会让它下次写入对不上账。剩下的目录才交给文件系统删。
     */
    suspend fun clear(): CacheUsage =
        withContext(Dispatchers.IO) {
            runCatching { clearImageCache() }
            cacheDir.listFiles()?.forEach { file ->
                // 图片目录已经由 Coil 清过了，这里不再动：谁的数据谁负责
                if (file.name == IMAGE_CACHE_DIR) return@forEach
                runCatching { file.deleteRecursively() }
            }
            measure()
        }

    private fun measure(): CacheUsage {
        val imageBytes = File(cacheDir, IMAGE_CACHE_DIR).bytesOnDisk()
        val totalBytes = cacheDir.bytesOnDisk()
        // 目录统计在极个别设备上会拿不到权限返回 0，夹一下免得界面上出现负值
        return CacheUsage(imageBytes = imageBytes, otherBytes = (totalBytes - imageBytes).coerceAtLeast(0L))
    }

    private fun File.bytesOnDisk(): Long =
        runCatching {
            walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    companion object {
        /** 与 `LeimaoTvApplication` 里给 Coil 的磁盘缓存目录保持一致 */
        const val IMAGE_CACHE_DIR = "logo_cache"

        @OptIn(ExperimentalCoilApi::class)
        fun create(context: Context): CacheManager =
            CacheManager(context.cacheDir) {
                val loader = context.imageLoader
                // DiskCache.clear() 至今仍标着 ExperimentalCoilApi；这里只用它最基础的
                // 一条语义（把缓存清空），不涉及会改签名的重载
                loader.diskCache?.clear()
                loader.memoryCache?.clear()
            }
    }
}
