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
    const val CURRENT_VERSION: String = "0.2.2.0"

    /**
     * 应用内「关于 → 更新日志」读的就是它。
     * 内容比根目录 CHANGELOG.md 少 —— 按 §10.3 的分工，这里去掉构建 / 测试 / 重构这类
     * 开发者才关心的条目，只留用户能感知的变化。
     */
    val CHANGELOG: List<VersionEntry> =
        listOf(
            VersionEntry(
                version = "0.2.2.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "影视首页开放：按类型分栏、最近更新、每个影视源各占一行",
                        "支持按年份、地区筛选片单，选中的条件会保留",
                        "影视源可在源管理里直接添加，与直播源分开管理",
                    ),
            ),
            VersionEntry(
                version = "0.2.1.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "影视源接入能力就位：能读取苹果CMS 系影视源的分类与片单，影视首页在后续版本开放",
                    ),
            ),
            VersionEntry(
                version = "0.2.0.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "直播分区上线：收藏频道、最近观看、分组快捷入口一眼直达",
                    ),
            ),
            VersionEntry(
                version = "0.1.9.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "按菜单键随时呼出全局菜单：搜索 / 源管理 / 设置 / 退出",
                    ),
            ),
            VersionEntry(
                version = "0.1.8.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "频道列表可即时按名称过滤，输入即筛",
                    ),
            ),
            VersionEntry(
                version = "0.1.7.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "频道抽屉新增「最近观看」分组，换台后实时更新",
                    ),
            ),
            VersionEntry(
                version = "0.1.6.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "源管理可上下移动排序、导出/导入源备份（JSON）",
                    ),
            ),
            VersionEntry(
                version = "0.1.5.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "新增自动刷新：直播源可按 6/12/24 小时定时更新，也可关闭",
                        "应用启动时会在后台自动更新一次源，不影响正在播的内容",
                    ),
            ),
            VersionEntry(
                version = "0.1.4.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "新增播放设置：可调缓冲档位（低延迟/均衡/抗卡顿）与快进步长",
                        "缓冲档位改动后下次启动生效，设置页已明确标注",
                    ),
            ),
            VersionEntry(
                version = "0.1.3.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "添加源时能配 Referer 与 Basic 认证了（收在「高级」里，默认不展开）",
                        "「关于」里新增开源许可详情页，可查看本应用与内置内核的许可说明",
                    ),
            ),
            VersionEntry(
                version = "0.1.2.0",
                date = "2026-10-22",
                changes =
                    listOf(
                        "直播真的能播了：接入 Media3 内核，硬解直出，换台不再黑屏",
                        "频道列表：分组浏览、★收藏、当前台高亮，按菜单键随时拉出来",
                        "遥控器手感：上下换台到头会翻回去；数字键输入频道号 2 秒内自动跳转",
                        "播放页顶部显示正在播什么、下一档是什么，几秒后自动淡出，不挡画面",
                        "可以导入节目单（XMLTV）了，同一个台的多种写法会自动合并",
                        "源管理：地址打码显示，刷新失败会直接说原因",
                        "按键映射：小键盘方向键 / 小键盘回车 / ESC 可当遥控器用，方便在电脑上试",
                        "修复：明亮画面上的白字看不清、控制条按钮按不动、对话框按钮被裁出屏幕",
                    ),
            ),
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
