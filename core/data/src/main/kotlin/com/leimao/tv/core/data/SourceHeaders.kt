package com.leimao.tv.core.data

import com.leimao.tv.core.database.repository.Source
import com.leimao.tv.core.network.BasicAuth
import com.leimao.tv.core.network.RequestHeaders

/**
 * 把库里存的源配置翻成请求头（LIVE-06）。
 *
 * 直播刷新与影视刷新都要用：同一个源的两套内容（频道列表 / 采集接口）通常挂在同一台
 * 服务器上，UA 与 Referer 的校验规则也一致。各写一份迟早出现「直播能刷、影视刷不动」
 * 这种只在一条链路上出现的怪问题。
 *
 * 空串按「没配」处理 —— 表单里留空保存下来的是 `""`，不是 null。
 */
internal fun Source.toRequestHeaders(): RequestHeaders =
    RequestHeaders(
        userAgent = userAgent?.takeIf { it.isNotBlank() },
        referer = referer?.takeIf { it.isNotBlank() },
        basicAuth =
            basicUser?.takeIf { it.isNotBlank() }?.let { user ->
                BasicAuth(username = user, password = basicPass.orEmpty())
            },
    )
