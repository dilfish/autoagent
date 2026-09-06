package com.dilfish.autoagent.console

import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.TaskContext
import com.dilfish.autoagent.engine.TaskRunner

/** 控制台会话管理：维护当前 ConsoleSource，随用随起 */
object ConsoleSession {

    private var source: ConsoleSource? = null

    fun submit(input: String): Boolean {
        if (!TaskRunner.running.value) {
            source = ConsoleSource()
            TaskRunner.start(source!!, TaskContext("控制台会话"))
        }
        val ok = source?.submit(input) ?: false
        if (!ok) AgentBus.log("命令未提交（解析失败或会话未就绪）")
        return ok
    }

    fun stop() {
        TaskRunner.stop()
    }
}
