package com.leimao.tv.core.model.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 附件名精确匹配与选包顺序（SET-07 / RULES §10.4 第 6 条）。
 *
 * 这一组用例守的是「不要把装不上的包递给用户」：名字差一个字不能抓、
 * 32 位盒子不许拿到 arm64 包。两条都出过一次就会得到一个说不清的安装失败。
 */
class ApkAssetsTest {
    private fun asset(
        name: String,
    ) = ReleaseAsset(name = name, downloadUrl = "https://example.test/$name", sizeBytes = 1)

    @Test
    fun `三个附件名按发版约定拼`() {
        assertEquals("leimao-tv-v0.2.9.7.apk", ApkAssets.arm64("0.2.9.7"))
        assertEquals("leimao-tv-v0.2.9.7-v7a.apk", ApkAssets.v7a("0.2.9.7"))
        assertEquals("leimao-tv-v0.2.9.7-universal.apk", ApkAssets.universal("0.2.9.7"))
        assertEquals(3, ApkAssets.expected("0.2.9.7").size)
    }

    @Test
    fun `arm64 设备优先拿主包`() {
        val assets = listOf(asset("leimao-tv-v0.2.9.7-universal.apk"), asset("leimao-tv-v0.2.9.7.apk"))
        assertEquals("leimao-tv-v0.2.9.7.apk", ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.ARM64)?.name)
    }

    @Test
    fun `只有 universal 时任何设备都退到它`() {
        val assets = listOf(asset("leimao-tv-v0.2.9.7-universal.apk"))
        assertEquals("leimao-tv-v0.2.9.7-universal.apk", ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.ARM64)?.name)
        assertEquals("leimao-tv-v0.2.9.7-universal.apk", ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.ARM32)?.name)
    }

    @Test
    fun `arm64 设备在主包与 universal 都缺时退到 v7a`() {
        val assets = listOf(asset("leimao-tv-v0.2.9.7-v7a.apk"))
        assertEquals("leimao-tv-v0.2.9.7-v7a.apk", ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.ARM64)?.name)
    }

    @Test
    fun `32 位设备绝不拿 arm64 包`() {
        val assets = listOf(asset("leimao-tv-v0.2.9.7.apk"), asset("leimao-tv-v0.2.9.7-v7a.apk"))
        assertEquals("leimao-tv-v0.2.9.7-v7a.apk", ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.ARM32)?.name)
    }

    @Test
    fun `32 位设备遇到只有 arm64 包时判定没有可用包`() {
        val assets = listOf(asset("leimao-tv-v0.2.9.7.apk"))
        assertNull(ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.ARM32))
    }

    @Test
    fun `架构未知时只认 universal`() {
        val assets = listOf(asset("leimao-tv-v0.2.9.7.apk"), asset("leimao-tv-v0.2.9.7-v7a.apk"))
        assertNull(ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.OTHER))
    }

    @Test
    fun `名字不完全一致就不算匹配`() {
        val assets =
            listOf(
                // 少了个 v、多了个架构后缀、大写 V —— 都不是约定里的名字
                asset("leimao-tv-0.2.9.7.apk"),
                asset("leimao-tv-v0.2.9.7-arm64.apk"),
                asset("leimao-tv-V0.2.9.7.apk"),
            )
        assertNull(ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.ARM64))
    }

    @Test
    fun `上一个版本的包不能顶这个版本`() {
        val assets = listOf(asset("leimao-tv-v0.2.9.6.apk"))
        assertNull(ApkAssets.pick(assets, "0.2.9.7", DeviceAbi.ARM64))
    }

    @Test
    fun `ABI 字符串归一`() {
        assertEquals(DeviceAbi.ARM64, DeviceAbi.fromAbi("arm64-v8a"))
        assertEquals(DeviceAbi.ARM32, DeviceAbi.fromAbi("armeabi-v7a"))
        assertEquals(DeviceAbi.ARM32, DeviceAbi.fromAbi("ARM"))
        assertEquals(DeviceAbi.OTHER, DeviceAbi.fromAbi("x86_64"))
        assertEquals(DeviceAbi.OTHER, DeviceAbi.fromAbi(null))
        // 多 ABI 列表按第一个算：Build.SUPPORTED_ABIS[0] 就是「最优的那个」
        assertEquals(DeviceAbi.ARM64, DeviceAbi.fromAbi("arm64-v8a,armeabi-v7a"))
    }
}
