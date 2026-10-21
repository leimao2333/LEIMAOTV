package com.leimao.tv.debug

import com.leimao.tv.AppContainer

/**
 * **正式包**的 DebugSeed：什么都不做。
 *
 * 与 src/debug 的同名 object 构成「编译期二选一」——
 * 演示数据不是被 if 判断挡住，而是压根不会被编译进发布包（RULES §2.1 零内置内容）。
 */
object DebugSeed {
    @Suppress("UNUSED_PARAMETER")
    suspend fun seedIfEmpty(container: AppContainer) = Unit
}
