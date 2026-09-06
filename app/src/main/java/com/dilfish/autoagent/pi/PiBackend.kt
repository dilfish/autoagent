package com.dilfish.autoagent.pi

/**
 * pi 执行后端抽象：PiSource 只负责组 prompt + 解析 reply，
 * 具体"怎么拿到 reply"由后端决定（真 pi 经 SSH / 调试走桌面 stub）。
 */
interface PiBackend {
    suspend fun start()
    suspend fun exec(payload: String): String
    fun stop()
}
