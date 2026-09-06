package com.dilfish.autoagent.engine

import com.dilfish.autoagent.accessibility.ClickAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一任务执行循环：所有 CommandSource（脚本/控制台/LLM/pi/远程）共用。
 * 循环：nextStep(快照, 历史) → 串行执行命令 → 结果进历史 → 直到 Done/Fail/空/停止。
 */
object TaskRunner {

    /** 防失控的循环步数上限 */
    var maxSteps = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopFlag = AtomicBoolean(true)
    private val mutex = Mutex()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _progress = MutableStateFlow("空闲")
    val progress: StateFlow<String> = _progress

    fun start(source: CommandSource, task: TaskContext) {
        scope.launch {
            mutex.withLock {
                stopFlag.set(false)
                _running.value = true
                try {
                    runLoop(source, task)
                } catch (t: Throwable) {
                    if (!stopFlag.get()) AgentBus.log("任务异常中断: ${t.message}")
                } finally {
                    try {
                        source.stop()
                    } catch (_: Exception) {
                    }
                    stopFlag.set(true)
                    _running.value = false
                    _progress.value = "空闲"
                }
            }
        }
    }

    fun stop() {
        stopFlag.set(true)
    }

    private suspend fun runLoop(source: CommandSource, task: TaskContext) {
        val svc = ClickAccessibilityService.instance
        if (svc == null) {
            AgentBus.log("无障碍服务未开启，无法执行")
            return
        }
        AgentBus.log("── 任务开始 [${source.label}] ${task.description}")
        source.start(task)

        val history = ArrayList<StepResult>()
        var stepNo = 0
        while (!stopFlag.get() && (source.unlimited || stepNo < maxSteps)) {
            val snap = AgentBus.snapshot.value
            val commands = try {
                source.nextStep(snap, history)
            } catch (t: Throwable) {
                if (stopFlag.get()) break
                AgentBus.log("命令生成失败: ${t.message}")
                break
            }
            if (commands.isEmpty()) {
                if (source.unlimited) {
                    kotlinx.coroutines.delay(300)
                    continue
                }
                break
            }

            stepNo++
            _progress.value = "[${source.label}] 第 $stepNo 步"
            val results = ArrayList<CommandResult>(commands.size)
            for (cmd in commands) {
                if (stopFlag.get()) break
                val res = svc.execute(cmd)
                results.add(res)
                logResult(source, res)
            }
            history.add(StepResult(stepNo, commands, results, snap?.text))

            val done = commands.filterIsInstance<Command.Done>().firstOrNull()
            val fail = commands.filterIsInstance<Command.Fail>().firstOrNull()
            if (done != null) {
                AgentBus.log("── 任务完成: ${done.summary}")
                break
            }
            if (fail != null) {
                AgentBus.log("── 任务失败: ${fail.reason}")
                break
            }
        }
        if (!source.unlimited && stepNo >= maxSteps) AgentBus.log("── 已达最大步数上限 $maxSteps，任务停止")
        if (stopFlag.get()) AgentBus.log("── 任务被手动停止")
        else AgentBus.log("── 任务结束 [${source.label}] 共 $stepNo 步")
    }

    private fun logResult(source: CommandSource, res: CommandResult) {
        AgentBus.log("[${source.label}] #${res.cmdId} " + if (res.ok) "✓" else "✗ ${res.error ?: "失败"}")
    }
}
