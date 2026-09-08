package com.dilfish.autoagent.engine

import com.dilfish.autoagent.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 全局共享状态：日志（转调 AppLog）、最新节点树快照、执行状态。 */
object AgentBus {
    /** 兼容旧 UI：直接暴露 AppLog 行缓冲 */
    val logs: StateFlow<List<String>> = AppLog.lines

    private val _snapshot = MutableStateFlow<NodeTreeSnapshot?>(null)
    val snapshot: StateFlow<NodeTreeSnapshot?> = _snapshot

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun log(msg: String) {
        AppLog.legacy(msg)
    }

    fun publishSnapshot(snap: NodeTreeSnapshot?) {
        _snapshot.value = snap
        if (snap != null) {
            AppLog.d(
                "a11y",
                "snapshot pkg=${snap.packageName} elements=${snap.entries.size} chars=${snap.text.length}",
            )
        }
    }

    suspend fun <T> withBusy(block: suspend () -> T): T {
        _busy.value = true
        try {
            return block()
        } finally {
            _busy.value = false
        }
    }
}
