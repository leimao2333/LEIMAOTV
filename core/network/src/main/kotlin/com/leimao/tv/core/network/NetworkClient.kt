package com.leimao.tv.core.network

import com.leimao.tv.core.model.network.NetworkSettings
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 把设置页的三项（SET-04）落到 OkHttp 上。
 *
 * 从 [base] **派生**而不是重新 new 一个：`newBuilder()` 会共用同一个连接池、
 * Dispatcher 与线程池 —— 这是「全应用一个 OkHttp 实例」的收益所在。每次改设置都 new 一个，
 * 等于把已经建立的连接全丢掉，还多留一堆线程池在那儿等 GC。
 *
 * 超时只改 connect / read：write 超时对 GET 为主的抓取没有意义，call 总超时也没设 ——
 * 设了会在读大文件（几 MB 的播放列表）时半路掐断，反而是个新坑。
 */
fun buildNetworkClient(
    base: OkHttpClient,
    settings: NetworkSettings,
): OkHttpClient =
    base
        .newBuilder()
        .connectTimeout(settings.timeout.connectSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(settings.timeout.readSeconds.toLong(), TimeUnit.SECONDS)
        /*
         * 直连用 Proxy.NO_PROXY，不是 null。
         *
         * `proxy(null)` 只是「本 client 不指定代理」，OkHttp 随后会回落到 ProxySelector ——
         * 而默认那个读的是**系统代理**。用户在这里明确选了直连，就该真的直连。
         * 被系统代理悄悄接管是最难排查的一类问题：电视盒子上那个代理往往是别的 App 装的，
         * 用户在系统设置里翻不到，只会觉得「这个 App 的网络坏了」。
         */
        .proxy(
            settings.proxy?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress(it.host, it.port)) }
                ?: Proxy.NO_PROXY,
        )
        .build()
