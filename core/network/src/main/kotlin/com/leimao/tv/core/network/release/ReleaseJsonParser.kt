package com.leimao.tv.core.network.release

import com.leimao.tv.core.model.release.ReleaseAsset
import com.leimao.tv.core.model.release.ReleaseInfo
import com.leimao.tv.core.network.json.MiniJson
import com.leimao.tv.core.network.json.array
import com.leimao.tv.core.network.json.asObjectOrNull
import com.leimao.tv.core.network.json.long
import com.leimao.tv.core.network.json.text

/**
 * GitHub Release 响应的解析（SET-07）。
 *
 * 只读 `GET /repos/{owner}/{repo}/releases/latest` 那一个对象，字段全部走 `MiniJson` ——
 * 不为一个解析器引 kotlinx.serialization（RULES §4），也就不用给 Release 的每个字段维护 `@SerialName`。
 *
 * 解析结果是**可空**的而不是抛异常：更新检查是「用户按了一下按钮」的场景，
 * 对方哪天改了响应结构，正确反应是「这次没查到，说清楚」，而不是让线程带着堆栈崩掉。
 */
object ReleaseJsonParser {
    /**
     * @return 解析成功且至少有 `tag_name` 的发布对象；不是发布对象（404 的错误体、数组、纯文本）时给 null
     */
    fun parse(text: String): ReleaseInfo? {
        val root = runCatching { MiniJson.parse(text).asObjectOrNull() }.getOrNull() ?: return null
        val tagName = root.text("tag_name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return ReleaseInfo(
            tagName = tagName,
            // 这里只去掉 v 前缀，合法性交给 `ReleaseVersion.parse` 判 ——
            // 解析器不该替业务决定「什么算合法的版本号」
            version = tagName.removePrefix("v").removePrefix("V"),
            title = root.text("name")?.trim().orEmpty().ifEmpty { tagName },
            // 正文可能很长（几百行 markdown），原样带走，展示层自己截断
            notes = root.text("body").orEmpty(),
            publishedAt = root.text("published_at").orEmpty(),
            assets =
                root.array("assets").mapNotNull { element ->
                    val asset = element.asObjectOrNull() ?: return@mapNotNull null
                    val name = asset.text("name")?.trim().orEmpty()
                    val url = asset.text("browser_download_url")?.trim().orEmpty()
                    // 名字与地址缺一个这条附件就没法用：名字是匹配依据，地址是下载依据
                    if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
                    ReleaseAsset(
                        name = name,
                        downloadUrl = url,
                        // size 缺失按 0 处理：0 表示「不知道」，进度就不显示百分比，
                        // 下载后的长度校验也会自动跳过（见 ApkDownloader）
                        sizeBytes = asset.long("size") ?: 0L,
                    )
                },
        )
    }
}
