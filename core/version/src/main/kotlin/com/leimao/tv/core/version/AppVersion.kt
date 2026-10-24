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
    const val CURRENT_VERSION: String = "0.2.9.4"

    /**
     * 应用内「关于 → 更新日志」读的就是它。
     * 内容比根目录 CHANGELOG.md 少 —— 按 §10.3 的分工，这里去掉构建 / 测试 / 重构这类
     * 开发者才关心的条目，只留用户能感知的变化。
     */
    val CHANGELOG: List<VersionEntry> =
        listOf(
            VersionEntry(
                version = "0.2.9.4",
                date = "2026-10-24",
                changes =
                    listOf(
                        "设置里能看到缓存占了多大地方了：图片缓存和其他临时文件分开算",
                        "多了一个「清理缓存」按钮，清完会告诉你释放了多少空间",
                    ),
            ),
            VersionEntry(
                version = "0.2.9.3",
                date = "2026-10-24",
                changes =
                    listOf(
                        "搜索页能用了：一个词同时搜直播频道和影视，结果分两段显示",
                        "边打字边出结果，慢的源不会拖住——先搜到的先显示",
                        "搜过的词会留在历史里，可以单条删掉或一键清空",
                        "直播板块搜不到时说明原因，并给一个「去影视板块搜全部」的按钮",
                    ),
            ),
            VersionEntry(
                version = "0.2.9.2",
                date = "2026-10-24",
                changes =
                    listOf(
                        "搜索的后台打通了：一次能同时搜直播频道和影视（界面在下一个版本接上）",
                        "直播板块搜不到短剧、纪录片、资讯、预告片，影视板块全都能搜到",
                        "慢源不会拖住整个搜索：单条源最多等 8 秒，先搜到的先显示",
                    ),
            ),
            VersionEntry(
                version = "0.2.9.1",
                date = "2026-10-24",
                changes =
                    listOf(
                        "影视首页多了「标签」：动作、科幻、悬疑这些题材一键筛，和年份地区一起用",
                        "标签按片库里出现得最多的排，常看的题材排在最前面",
                        "标签只在当前栏目里生效；换栏目自动回到「不限」",
                    ),
            ),
            VersionEntry(
                version = "0.2.9.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "片子里有多条音轨时，控制条多一个「音轨」，可以切国语 / 粤语 / 原声",
                        "带内嵌字幕的片子多一个「字幕」，能选字幕也能关掉；关着时按钮上会写「字幕·关」",
                        "老片拷来的外挂字幕（SRT / ASS）也在同一条菜单里，不用另外设置",
                        "直播频道同样支持：多语解说的体育赛事可以切音轨",
                    ),
            ),
            VersionEntry(
                version = "0.2.8.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "影视详情页与播放页都能收藏了，首页多一行「我的收藏」",
                        "看完的片子不再从记录里消失：首页多一行「看过的」，想重温直接点",
                        "删掉影视源时，它的收藏也会一起清掉，不会留下点不进去的残影",
                    ),
            ),
            VersionEntry(
                version = "0.2.7.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "播放页可以直接换线路了：控制条多一个「线路」，按集号对齐、进度不丢",
                        "某条线路卡或者糊的时候，不用退回详情页再点进来",
                        "这一集播不起来时，失败提示里直接给「换线路」",
                    ),
            ),
            VersionEntry(
                version = "0.2.6.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "首页顶部加了 Hero 大图：接着看上次的片、正在播的台、今日推荐，停着不动每 3 秒自动换一张",
                        "焦点停在 Hero 上时不再自动切换，按 OK 就是你正在看的那一张",
                        "直播分区顶部会显示正在播的台和节目名，一眼知道现在在放什么",
                    ),
            ),
            VersionEntry(
                version = "0.2.5.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "看片进度自动记住：退出时看到第几分钟，下次就接着播",
                        "影视首页加了「继续观看」，一行卡片带进度条，点进去直接续播",
                        "详情页有进度时，第一眼看到的是「继续观看 第 N 集 · 12:34」",
                    ),
            ),
            VersionEntry(
                version = "0.2.4.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "影视可以真正播放了：详情页点任意一集直接开播",
                        "自动连播：一集演完自动接下一集，最后一条线路播完才停",
                        "播放页支持快进快退、暂停继续、上一集下一集，以及选集面板直接跳",
                    ),
            ),
            VersionEntry(
                version = "0.2.3.0",
                date = "2026-10-24",
                changes =
                    listOf(
                        "影视详情页上线：简介、导演主演、线路切换、按集选集，倒序一键切换",
                        "首页点海报就能进详情，进入时先用本地缓存的标题和海报撑住画面，不会白屏",
                        "源下架、源被删、网络出错分别给出不同提示，不再一律显示「加载失败」",
                    ),
            ),
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
