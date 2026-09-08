package com.dilfish.autoagent.engine

import com.dilfish.autoagent.accessibility.ClickAccessibilityService
import com.dilfish.autoagent.log.AppLog
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
        AppLog.d("engine", "start source=${source.id} unlimited=${source.unlimited} maxSteps=$maxSteps desc=${task.description}")
        source.start(task)

        val history = ArrayList<StepResult>()
        var stepNo = 0
        while (!stopFlag.get() && (source.unlimited || stepNo < maxSteps)) {
            val snap = AgentBus.snapshot.value
            AppLog.d(
                "engine",
                "nextStep#${stepNo + 1} history=${history.size} snapPkg=${snap?.packageName} elements=${snap?.entries?.size ?: 0}",
            )
            val commands = try {
                source.nextStep(snap, history)
            } catch (t: Throwable) {
                if (stopFlag.get()) break
                AgentBus.log("命令生成失败: ${t.message}")
                AppLog.e("engine", "nextStep 异常", t)
                break
            }
            if (commands.isEmpty()) {
                if (source.unlimited) {
                    kotlinx.coroutines.delay(300)
                    continue
                }
                AppLog.d("engine", "nextStep 返回空，结束")
                break
            }
            AppLog.d("engine", "got ${commands.size} cmds: ${commands.joinToString { briefCmd(it) }}")

            stepNo++
            _progress.value = "[${source.label}] 第 $stepNo 步"
            val results = ArrayList<CommandResult>(commands.size)
            for (cmd in commands) {
                if (stopFlag.get()) break
                AppLog.d("engine", "exec ${briefCmd(cmd)}")
                val res = svc.execute(cmd)
                results.add(res)
                logResult(source, res)
                if (!res.ok) AppLog.w("engine", "cmd#${res.cmdId} fail: ${res.error}")
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

    private fun briefCmd(cmd: Command): String = when (cmd) {
        is Command.Click -> "click#${cmd.cmdId}(id=${cmd.elementId},x=${cmd.x},y=${cmd.y})"
        is Command.LongClick -> "longClick#${cmd.cmdId}(id=${cmd.elementId},x=${cmd.x},y=${cmd.y})"
        is Command.Swipe -> "swipe#${cmd.cmdId}(${cmd.x1},${cmd.y1}->${cmd.x2},${cmd.y2},${cmd.durationMs}ms)"
        is Command.InputText -> "inputText#${cmd.cmdId}(id=${cmd.elementId},len=${cmd.text.length})"
        is Command.Scroll -> "scroll#${cmd.cmdId}(${cmd.direction},id=${cmd.elementId})"
        is Command.GlobalAction -> "global#${cmd.cmdId}(${cmd.action})"
        is Command.GetElements -> "getElements#${cmd.cmdId}"
        is Command.Wait -> "wait#${cmd.cmdId}(${cmd.ms}ms)"
        is Command.Done -> "done#${cmd.cmdId}"
        is Command.Fail -> "fail#${cmd.cmdId}"
    }
}
