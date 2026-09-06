package com.dilfish.autoagent.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 全局共享状态：日志、最新节点树快照、执行状态。UI 与各模块都从这里读写。 */
object AgentBus {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _snapshot = MutableStateFlow<NodeTreeSnapshot?>(null)
    val snapshot: StateFlow<NodeTreeSnapshot?> = _snapshot

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun log(msg: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $msg"
        _logs.value = (listOf(line) + _logs.value).take(500)
    }

    fun publishSnapshot(snap: NodeTreeSnapshot?) {
        _snapshot.value = snap
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
