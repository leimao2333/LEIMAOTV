package com.leimao.tv.core.model.release

/**
 * GitHub Release 里一条附件（SET-07）。
 *
 * 只留三样：名字（唯一用途是**精确匹配**，RULES §10.4 第 6 条）、下载地址、字节数（用来算进度、
 * 也用来校验下载有没有被截断）。`browser_download_url` 之类展示用的字段一律不带 ——
 * 这个模型的服务对象是更新器，不是渲染 Release 页面。
 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * 一次发布里本应用关心的部分。
 *
 * [version] 是去掉 `v` 前缀后的四段式版本号；[tagName] 保留原样，是为了在「版本号看不懂」时
 * 还能指着对方的 tag 说事。两者都留着不是冗余：一个给比较用，一个给用户看。
 */
data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val assets: List<ReleaseAsset>,
)

/** 设备主 ABI 的三分类。分三档是因为「装不上的包」比「没有包」更难解释。 */
enum class DeviceAbi {
    /** 64 位 ARM（绝大多数电视盒子） */
    ARM64,

    /** 32 位 ARM（老盒子、部分廉价方案） */
    ARM32,

    /** x86 或读不出来：只能赌 universal 包 */
    OTHER,
    ;

    companion object {
        /**
         * 从 `Build.SUPPORTED_ABIS[0]` 之类的字符串归一。
         *
         * 只认前缀，不认具体后缀版本：`arm64-v8a` / `armeabi-v7a` / `arm64-v8a,armeabi-v7a`
         * 这几种写法在不同厂商的固件上都见过，按前缀判最省事，误判的后果也只是「多试一个包」。
         */
        fun fromAbi(abi: String?): DeviceAbi {
            val value = abi?.trim()?.lowercase().orEmpty()
            return when {
                value.startsWith("arm64") -> ARM64
                value.startsWith("arm") || value.startsWith("aarch32") -> ARM32
                else -> OTHER
            }
        }
    }
}

/**
 * 附件命名与选包规则（RULES §10.4 第 6 条）。
 *
 * 三个名字是**写死的约定**，发版清单里已经在用；更新器这边只认这三个名字 ——
 * 「名字差不多就拿来用」会在某个版本的包名被改成 `leimao-tv-arm64-v0.2.9.7.apk` 时
 * 悄悄抓到 32 位包，或者抓到一个根本不是 APK 的附件。
 */
object ApkAssets {
    fun arm64(version: String): String = "leimao-tv-v$version.apk"

    fun v7a(version: String): String = "leimao-tv-v$version-v7a.apk"

    fun universal(version: String): String = "leimao-tv-v$version-universal.apk"

    /** 这个版本本该有的三个附件名，顺带当「缺哪个」的解释素材用 */
    fun expected(version: String): List<String> = listOf(arm64(version), v7a(version), universal(version))

    /**
     * 按设备 ABI 选一个能装上的包。
     *
     * 顺序是有讲究的：**首选自己那份架构的专用包**（体积小、无多余 so），
     * 退一步才是 universal（一定能装，但大一倍），绝不把 arm64 的包递给 32 位盒子 ——
     * 那会得到一个 `INSTALL_FAILED_NO_MATCHING_ABIS`，用户看到的是「下载好了但装不上」，
     * 比直接说「这个版本没有适合你的包」难解释得多。
     */
    fun pick(
        assets: List<ReleaseAsset>,
        version: String,
        abi: DeviceAbi = DeviceAbi.ARM64,
    ): ReleaseAsset? {
        val byName = assets.associateBy { it.name }
        return preference(version, abi).firstNotNullOfOrNull { byName[it] }
    }

    /** 该设备在这个版本下的候选名，按优先级从高到低。公开出来是为了让「没包」的提示能说清做了什么取舍 */
    fun preference(
        version: String,
        abi: DeviceAbi,
    ): List<String> =
        when (abi) {
            DeviceAbi.ARM64 -> listOf(arm64(version), universal(version), v7a(version))
            // 32 位盒子上 arm64 包装不上，不进候选
            DeviceAbi.ARM32 -> listOf(v7a(version), universal(version))
            // 连架构都认不出来时，只有 fat 包值得一试
            DeviceAbi.OTHER -> listOf(universal(version))
        }
}
