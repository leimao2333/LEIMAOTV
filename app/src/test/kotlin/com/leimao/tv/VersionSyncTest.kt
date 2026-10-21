package com.leimao.tv

import com.leimao.tv.core.version.AppVersion
import com.leimao.tv.core.version.AppVersionNumber
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SYS-10 的核心：版本号只有一个来源（RULES §10.2、§10.3 第 1、2 项）。
 *
 * `app/build.gradle.kts` 里的 versionName 是**手写的字面量**，不是从 AppVersion 读的
 * （构建脚本拿不到模块的 classpath）。正因为手写，才必须有这条测试盯着 ——
 * 否则发版时改了 gradle 忘了改 AppVersion，用户看到的版本和应用内更新日志就对不上。
 */
class VersionSyncTest {
    @Test
    fun `versionName 与 AppVersion_CURRENT_VERSION 逐字符相同`() {
        assertEquals(AppVersion.CURRENT_VERSION, BuildConfig.VERSION_NAME)
    }

    @Test
    fun `versionCode 与四段式版本号按 RULES 公式一致`() {
        val expected = AppVersionNumber.parse(AppVersion.CURRENT_VERSION).versionCode
        assertEquals(expected, BuildConfig.VERSION_CODE)
    }
}
