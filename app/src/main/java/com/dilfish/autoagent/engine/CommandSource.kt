package com.dilfish.autoagent.engine

import com.dilfish.autoagent.engine.NodeTreeSnapshot

/** 任务上下文：交给命令生成器的任务描述与配置 */
data class TaskContext(
    val description: String,
)

/** 一步的执行记录：生成的命令 + 执行结果 + 当时的屏幕快照 */
data class StepResult(
    val stepNo: Int,
    val commands: List<Command>,
    val results: List<CommandResult>,
    val snapshotText: String?,
)

/**
 * 模块 B 统一接口：命令生成器。
 * 引擎(TaskRunner)循环调用 nextStep(屏幕快照, 历史)，生成器返回下一批命令，
 * 直到返回空列表或命令中包含 Done/Fail。
 */
interface CommandSource {
    val id: String
    val label: String

    /** true 表示交互式来源（如控制台），不受步数上限约束，空轮询视为继续等待 */
    val unlimited: Boolean get() = false

    suspend fun start(task: TaskContext)

    suspend fun nextStep(
        screen: NodeTreeSnapshot?,
        history: List<StepResult>,
    ): List<Command>

    fun stop()
}
