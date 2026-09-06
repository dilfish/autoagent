package com.dilfish.autoagent.console

import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.Command
import com.dilfish.autoagent.engine.CommandSource
import com.dilfish.autoagent.engine.NodeTreeSnapshot
import com.dilfish.autoagent.engine.StepResult
import com.dilfish.autoagent.engine.TaskContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * B2 指令控制台：用户逐条手动下发命令。
 * nextStep 从队列取用户已输入的命令；队列为空时最多等 3 秒后返回空，让引擎稍后重试。
 */
class ConsoleSource : CommandSource {

    override val id = "console"
    override val label = "控制台"
    override val unlimited = true

    private val queue = Channel<Command>(Channel.UNLIMITED)

    override suspend fun start(task: TaskContext) {
        AgentBus.log("控制台模式：在控制台页输入命令执行（如 click [03] / back / inputText [02] 你好）")
    }

    fun submit(input: String): Boolean {
        val cmd = ConsoleParser.parse(input, AgentBus.snapshot.value) ?: return false
        queue.trySend(cmd)
        return true
    }

    override suspend fun nextStep(
        screen: NodeTreeSnapshot?,
        history: List<StepResult>,
    ): List<Command> {
        // 等待用户输入；3 秒无输入则空转一轮，保持可停止
        val cmd = withTimeoutOrNull(3_000) { queue.receive() }
            ?: return listOf(Command.Wait(Command.nextId(), 100))
        return listOf(cmd)
    }

    override fun stop() {
        queue.close()
    }
}
