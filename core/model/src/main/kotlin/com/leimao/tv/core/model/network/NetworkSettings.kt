package com.leimao.tv.core.model.network

/**
 * 网络设置（SET-04）：超时、并发、代理。
 *
 * ## 为什么是档位而不是数字
 *
 * 「连接超时（秒）」这种输入框在遥控器上要按十几次方向键才能填一个数，而用户并不知道
 * 10 秒和 12 秒的差别 —— 他只知道「老是超时」。档位把「我不知道该填多少」这件事
 * 变成「我现在是哪种情况」，选错也只是快慢之差，不会把 App 配坏。
 *
 * ## 为什么改完要重启才生效
 *
 * 这三项都落在 OkHttpClient 的构造参数上，而那个实例被播放内核（构造期传入
 * `OkHttpDataSource.Factory`）、抓取链路（构造期注入各仓储）同时持有。要做到「立刻生效」
 * 就得重建整条链，还会打断正在播的画面。故与缓冲档位同口径：**下次启动生效**，
 * 页面上必须写出来，否则用户会说「我改了没反应」。
 */
data class NetworkSettings(
    val timeout: TimeoutProfile = TimeoutProfile.STANDARD,
    val concurrency: ConcurrencyProfile = ConcurrencyProfile.STANDARD,
    /** 代理；`null` 表示直连。只影响本 App，不动系统设置 */
    val proxy: ProxyConfig? = null,
)

/**
 * 超时档位。
 *
 * 两个数是分开给的，因为它们的成因不同：连不上是网络到不了对方（DNS、路由、墙），
 * 读不完是对方在慢慢吐（源站慢、切片大）。直播源列表动辄几 MB，读超时必须比连接宽松。
 */
enum class TimeoutProfile(
    val connectSeconds: Int,
    val readSeconds: Int,
    /**
     * 搜索时单条源的预算（SEARCH-09 的 8 秒）。
     *
     * 它跟着档位走而不是固定 8 秒：为了境外源把超时调宽松的人，如果搜索还在 8 秒时
     * 就判「这条源没查完」，那宽松档对他等于没用 —— 他调它的原因正是「源慢」。
     * 每个档位都保证它小于读超时（见单测）：源都没连上就先说「查完了」是自相矛盾。
     */
    val searchTimeoutMs: Long,
    val label: String,
    val description: String,
) {
    /** 网络好、源都在国内：失败早点暴露，别对着转圈等半分钟 */
    FAST(5, 10, 5_000, "快速", "连接 5 秒 / 读取 10 秒。网络好、源都在国内时用，失败会更快暴露"),

    /** 默认：与 RULES §8.4 的原值一致，多数场景够用 */
    STANDARD(10, 15, 8_000, "标准", "连接 10 秒 / 读取 15 秒。默认档，多数情况够用"),

    /** 境外源或弱网：宁可多等，也不要每次都判超时 */
    RELAXED(20, 40, 15_000, "宽松", "连接 20 秒 / 读取 40 秒。源在境外、家里带宽紧张时用"),
    ;

    companion object {
        val DEFAULT = STANDARD

        /** 认不出的值一律回默认：枚举改名后旧字符串还留在 DataStore 里，不该为此崩掉设置页 */
        fun fromStorage(raw: String?): TimeoutProfile = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * 并发档位。
 *
 * 一个数字管两处：搜索（用户正等着结果）与目录刷新（后台跑）。
 * 但两者的合理上限不同 —— 搜索要「快」，刷新要「不打扰」。
 * 所以刷新取 `permits + REFRESH_HEADROOM`：
 * - 保守 2 → 搜索 2 / 刷新 4
 * - 标准 4 → 搜索 4 / 刷新 6（与 SET-04 之前的写死值一致）
 * - 激进 8 → 搜索 8 / 刷新 10
 */
enum class ConcurrencyProfile(
    val permits: Int,
    val label: String,
    val description: String,
) {
    /** 源多、被限流过：同时打的请求少一点 */
    CONSERVATIVE(2, "保守", "同时最多 2 条源。源多、经常被对方限流时用"),

    /** 默认：4 条源同时搜，单源 8 秒超时 */
    STANDARD(4, "标准", "同时最多 4 条源。默认档，多数情况够用"),

    /** 源少、想快：8 条一起上，注意别把老盒子拖卡 */
    AGGRESSIVE(8, "激进", "同时最多 8 条源。源少又想快时用，老设备可能有点卡"),
    ;

    /** 目录刷新用的并发：用户不在等它，可以比搜索稍宽，但不能无限（同样会引风控） */
    val refreshPermits: Int get() = permits + REFRESH_HEADROOM

    companion object {
        val DEFAULT = STANDARD

        /** 刷新比搜索多放两条：后台任务没有「用户正盯着」的时限，但也不该翻倍打对方 */
        const val REFRESH_HEADROOM = 2

        fun fromStorage(raw: String?): ConcurrencyProfile = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** 代理地址。只支持 HTTP 代理（CONNECT 隧道），不支持 SOCKS —— TV 上没人配这个 */
data class ProxyConfig(val host: String, val port: Int) {
    /** 规范化写法，也是存进 DataStore 的形式 */
    val address: String get() = "$host:$port"

    override fun toString(): String = address
}

/** 代理输入框的解析结果 */
sealed interface ProxyParse {
    /** 空输入 = 直连。与「格式错」必须分开：前者是用户的正常选择，后者要提示 */
    data object Direct : ProxyParse

    data class Ok(val config: ProxyConfig) : ProxyParse

    /** [reason] 直接拿来当输入框下面的提示，写「错在哪」而不是「格式错误」 */
    data class Invalid(val reason: String) : ProxyParse
}

/**
 * 解析用户在设置页填的代理地址。
 *
 * 宽容的地方：允许粘贴 `http://192.168.1.2:7890`（浏览器地址栏的习惯写法）、允许末尾斜杠、
 * 允许两端空白 —— 这些都是「同一件事的不同写法」，纠它们只会浪费用户时间。
 *
 * 不宽容的地方：端口缺失或越界、主机名带空格或斜杠、未加方括号的 IPv6。
 * 这三种没法猜出用户本意，猜错了会得到一个沉默的「所有请求都失败」。
 */
fun parseProxyAddress(raw: String): ProxyParse {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ProxyParse.Direct

    val withoutScheme =
        when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            // socks 不是「换个前缀」就能用的：OkHttp 的 HTTP 代理与 SOCKS 代理是两种
            // java.net.Proxy.Type，混着当 HTTP 代理用只会在连接阶段报一个看不懂的错
            trimmed.startsWith("socks", ignoreCase = true) ->
                return ProxyParse.Invalid("只支持 HTTP 代理，不支持 SOCKS")

            else -> trimmed
        }

    val body = withoutScheme.trimEnd('/')
    if (body.isEmpty()) return ProxyParse.Invalid("只填了协议头，缺少主机和端口")

    // 主机里的用户名密码：OkHttp 需要单独的 Authenticator，不是拼进地址就能用
    if ('@' in body) {
        return ProxyParse.Invalid("暂不支持需要账号密码的代理")
    }

    // IPv6 必须写成 [::1]:8080 —— 裸写 ::1:8080 无法判断哪一段是端口
    val host: String
    val portText: String
    if (body.startsWith("[")) {
        val close = body.indexOf(']')
        if (close <= 1) return ProxyParse.Invalid("IPv6 地址要写成 [地址]:端口")
        host = body.substring(1, close)
        val rest = body.substring(close + 1)
        if (!rest.startsWith(":")) return ProxyParse.Invalid("缺少端口")
        portText = rest.substring(1)
    } else {
        val colon = body.lastIndexOf(':')
        if (colon <= 0) return ProxyParse.Invalid("缺少端口，要写成 主机:端口")
        host = body.substring(0, colon)
        portText = body.substring(colon + 1)
        if (':' in host) return ProxyParse.Invalid("IPv6 地址要写成 [地址]:端口")
    }

    if (host.isBlank()) return ProxyParse.Invalid("缺少主机")
    if (host.any { it.isWhitespace() || it == '/' }) return ProxyParse.Invalid("主机里不能有空格或斜杠")

    // 只认纯数字：允许 "08080" 这种写法但按下标解析时会得到 8080，不算错
    val port = portText.toIntOrNull()
    if (port == null) return ProxyParse.Invalid("端口要填数字")
    if (port !in 1..65535) return ProxyParse.Invalid("端口要在 1~65535 之间")

    return ProxyParse.Ok(ProxyConfig(host = host, port = port))
}

/** 从存储里读回代理：认不出就按直连处理（宁可直连，也不要拿一个坏地址把所有请求堵死） */
fun proxyFromStorage(raw: String?): ProxyConfig? = (parseProxyAddress(raw.orEmpty()) as? ProxyParse.Ok)?.config

/** 给设置页显示用的一句话口径 */
fun describeProxy(proxy: ProxyConfig?): String = proxy?.let { "经 ${it.address}" } ?: "直连（不走代理）"
