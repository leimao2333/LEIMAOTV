package com.leimao.tv.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 给**图片请求**补 User-Agent 与 Referer。
 *
 * ## 为什么需要
 *
 * 真机实测：影视首页大量封面报 `SocketException: Connection reset`，
 * 同一批地址在浏览器里能打开 —— 这类图床（采集站的 `pic.xxx.com`）普遍做防盗链：
 * 请求没有 Referer、或者 UA 是 `okhttp/4.x` 这种非浏览器标识时，服务端直接把连接掐掉。
 *
 * ## 为什么 Referer 用「图片自己的域名」
 *
 * 防盗链判据基本是「Referer 的 host 是否在白名单里」。我们不知道每个源的站点域名，
 * 但图床自己的域名一定在它自己的白名单里 —— 用请求 URL 的 origin 当 Referer，
 * 是唯一不需要维护站点表、又能过掉同源校验的写法。
 *
 * ## 为什么只给图片用
 *
 * 接口请求（苹果CMS 的 `api.php/provide/vod`）有自己的 UA/Referer 配置，
 * 用户在源管理里填过就以用户填的为准（RULES §4）。全局塞浏览器 UA 会覆盖用户的设置，
 * 有些源反而会因为 UA 不匹配而拒绝。
 */
internal class ImageHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val referer = "${request.url.scheme}://${request.url.host}/"
        val patched =
            request
                .newBuilder()
                .header("User-Agent", BROWSER_UA)
                .header("Referer", referer)
                .build()
        return chain.proceed(patched)
    }

    private companion object {
        /**
         * 用一个常见的桌面 Chrome UA。
         *
         * 不用手机 UA：部分图床只对桌面 UA 放行（它们的白名单是按浏览器做的），
         * 而图片请求不需要任何移动端特性。
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
