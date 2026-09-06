package com.dilfish.autoagent.script

import com.dilfish.autoagent.engine.Command
import com.dilfish.autoagent.engine.CommandSource
import com.dilfish.autoagent.engine.NodeTreeSnapshot
import com.dilfish.autoagent.engine.StepResult
import com.dilfish.autoagent.engine.TaskContext

/**
 * B1 手动脚本执行器（纯本地，不依赖 AI 和网络）。
 * 把脚本展开成命令序列：按步骤顺序循环，rounds=0 表示无限。
 */
class ScriptSource(private val script: Script) : CommandSource {

    override val id = "script"
    override val label = "脚本"

    private var round = 0
    private var index = 0

    override suspend fun start(task: TaskContext) {
        round = 0
        index = 0
        if (script.steps.isEmpty()) {
            AgentLog.log("脚本「${script.name}」没有步骤")
        }
    }

    override suspend fun nextStep(
        screen: NodeTreeSnapshot?,
        history: List<StepResult>,
    ): List<Command> {
        if (script.steps.isEmpty()) return emptyList()
        if (script.rounds > 0 && round >= script.rounds) return emptyList()

        val step = script.steps[index]
        index++
        if (index >= script.steps.size) {
            index = 0
            round++
        }
        return buildList {
            repeat(step.repeat.coerceAtLeast(1)) {
                add(
                    if (step.action == "longClick") {
                        Command.LongClick(Command.nextId(), x = step.x, y = step.y)
                    } else {
                        Command.Click(Command.nextId(), x = step.x, y = step.y)
                    },
                )
            }
            if (step.postDelayMs > 0) add(Command.Wait(Command.nextId(), step.postDelayMs))
        }
    }

    override fun stop() {}
}

private object AgentLog {
    fun log(msg: String) = com.dilfish.autoagent.engine.AgentBus.log(msg)
}
