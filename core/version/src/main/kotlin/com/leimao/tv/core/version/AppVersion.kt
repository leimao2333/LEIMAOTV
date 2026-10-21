package com.leimao.tv.core.version

/**
 * 四段式版本号 `顶端.大.小.修订`（RULES §10.2）。
 *
 * 刻意把这个类做成纯逻辑并配单测：版本号是**发版流程里最容易算错**的东西
 * （进位、`versionCode` 公式、`1.0.0.0` 例外），一旦靠手算，错一次就是一版白发包。
 */
data class AppVersionNumber(
    val top: Int,
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    init {
        require(top >= 0 && major in 0..9 && minor in 0..9 && patch in 0..99) {
            "段位越界：$top.$major.$minor.$patch（大/小版本 0~9，修订 0~99）"
        }
    }

    val versionName: String get() = "$top.$major.$minor.$patch"

    /**
     * `顶端版本 × 1000000 + 大版本 × 10000 + 小版本 × 100 + 修订`（RULES §10.2）。
     * 与手机端同公式，保证同设备共存安装时排序正确。
     */
    val versionCode: Int
        get() = top * 1_000_000 + major * 10_000 + minor * 100 + patch

    /**
     * 里程碑内的日常发版：只动小版本；小版本走满十个数（0~9）时向大版本进位。
     */
    fun nextMinorRelease(): AppVersionNumber =
        when {
            minor < 9 -> copy(minor = minor + 1)
            major < 9 -> copy(major = major + 1, minor = 0)
            else -> copy(top = top + 1, major = 0, minor = 0)
        }

    /**
     * 进入下一个里程碑：大版本 +1、小版本归零；大版本走满十个数时向顶端版本进位。
     */
    fun nextMilestone(): AppVersionNumber =
        when {
            major < 9 -> copy(major = major + 1, minor = 0, patch = 0)
            else -> copy(top = top + 1, major = 0, minor = 0, patch = 0)
        }

    /** 同一小版本内打补丁。只给「已发布版本修 bug」用，新功能不许走这一位（§10.5 反模式）。 */
    fun nextPatch(): AppVersionNumber = copy(patch = patch + 1)

    /**
     * 首个正式版 `1.0.0.0` —— §10.2 允许的**唯一**语义化例外：
     * 即使大版本没走满十个数也直接进 1.x，因为「能推荐给别人用了」这个信号比机械进位值钱。
     */
    fun toFirstStable(): AppVersionNumber = AppVersionNumber(top = 1, major = 0, minor = 0, patch = 0)

    companion object {
        fun parse(version: String): AppVersionNumber {
            val parts = version.trim().removePrefix("v").split(".")
            require(parts.size == 4) { "版本号必须是四段式：$version" }
            return AppVersionNumber(
                top = parts[0].toInt(),
                major = parts[1].toInt(),
                minor = parts[2].toInt(),
                patch = parts[3].toInt(),
            )
        }
    }
}

/**
 * 版本与更新日志的唯一来源（SYS-03 / SYS-10）。
 *
 * 发版四处同步里的第 2、3 项都指向这里（RULES §10.3）：
 * `app/build.gradle.kts` 的 versionName 必须与 [CURRENT_VERSION] 逐字符相等（有单测钉住），
 * [CHANGELOG] 必须与仓库根 `CHANGELOG.md` 的条目版本、日期、顺序一致（同样有单测）。
 */
object AppVersion {
    const val CURRENT_VERSION: String = "0.1.1.0"

    /**
     * 应用内「关于 → 更新日志」读的就是它。
     * 内容比根目录 CHANGELOG.md 少 —— 按 §10.3 的分工，这里去掉构建 / 测试 / 重构这类
     * 开发者才关心的条目，只留用户能感知的变化。
     */
    val CHANGELOG: List<VersionEntry> =
        listOf(
            VersionEntry(
                version = "0.1.1.0",
                date = "2026-10-21",
                changes =
                    listOf(
                        "打开就是画面：冷启动直接进直播播放页，不再先停一页主页",
                        "播放页按返回才到主页，主页按返回是退出确认，两边不再互相打转",
                        "主页支持遥控器一键切换「直播 / 影视」，切换不影响正在播的内容",
                        "首次启动会说明：本应用不提供任何源，请使用你有权访问的源",
                        "新增版本号单一来源与应用内更新日志，版本信息不再各处对不上",
                    ),
            ),
            VersionEntry(
                version = "0.1.0.0",
                date = "2026-10-21",
                changes =
                    listOf(
                        "项目立项：确立产品边界、功能台账与项目规则（本版无代码）",
                    ),
            ),
        )
}

data class VersionEntry(
    val version: String,
    val date: String,
    val changes: List<String>,
)
