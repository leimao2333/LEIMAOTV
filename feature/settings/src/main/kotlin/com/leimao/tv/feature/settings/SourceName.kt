package com.leimao.tv.feature.settings

import com.leimao.tv.core.database.repository.SourceType
import java.net.URI

/**
 * 源名称由地址自动生成（SET-03）。
 *
 * 以前「添加订阅地址」要求手填名称。电视上打一次字要按几十下方向键，而绝大多数人填的
 * 就是「订阅源」三个字 —— 既没有信息量，又在列表里制造一堆同名卡片，想分清哪条是哪条
 * 还得点进去看地址。改成从地址取**主机名**：一眼能看出这条源从哪来，也省掉一步输入。
 *
 * 中转地址（`gh-proxy.com/raw.githubusercontent.com/...`）取路径里那段真实主机 ——
 * 否则所有走加速的源都会叫 `gh-proxy.com`，等于没名字。
 */
internal fun sourceNameFromUrl(
    url: String,
    type: String,
): String {
    val fallback = if (type == SourceType.VOD) "影视源" else "订阅源"
    val uri = parseUri(url) ?: return fallback
    val host = uri.host?.removePrefix("www.")?.trim().orEmpty()
    if (!host.contains('.')) return fallback

    // 路径第一段本身是主机名（`/raw.githubusercontent.com/...`）→ 那才是源的真名
    val firstSegment = uri.path?.trim('/')?.substringBefore('/').orEmpty()
    if (host in PROXY_HOSTS && firstSegment.contains('.')) return firstSegment
    return host
}

private fun parseUri(url: String): URI? {
    val text = url.trim()
    if (text.isEmpty()) return null
    // 用户常常只贴 `example.com/live.m3u`：补个协议头才解析得出 host
    val normalized = if (text.contains("://")) text else "https://$text"
    return runCatching { URI(normalized) }.getOrNull()
}

/**
 * 这个地址是不是一份**源备份**（`{ "version":1, "sources":[...] }`）。
 *
 * 判据取路径后缀：备份文件名几乎都叫 `.json`，而播放列表是 `.m3u/.m3u8/.txt`。
 * 判错也不会出事 —— 真拿一个 JSON 播放列表进来，备份导入会如实报「找不到 sources 列表」。
 */
internal fun looksLikeSourceBackupUrl(url: String): Boolean {
    val path = url.trim().substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".json")
}

/**
 * 已知的中转/加速域名。只列确定会「把真地址放在路径里」的这几个 ——
 * 判据不能放宽成「主机名短就用路径」，那会把正常的深层路径也当成主机。
 */
private val PROXY_HOSTS =
    setOf(
        "gh-proxy.com",
        "ghproxy.net",
        "gh-proxy.net",
        "ghfast.top",
    )
