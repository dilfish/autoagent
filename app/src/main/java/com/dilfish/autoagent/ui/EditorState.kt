package com.dilfish.autoagent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dilfish.autoagent.script.Script
import com.dilfish.autoagent.script.ScriptStep

/**
 * 脚本编辑会话：App 内编辑器与悬浮球选点共享。
 * 选点模式下点击屏幕的坐标会追加到 current 的步骤末尾。
 */
object EditorState {
    var current by mutableStateOf<Script?>(null)
        private set

    var dirty by mutableStateOf(false)
        private set

    fun open(script: Script?) {
        current = script
        dirty = false
    }

    fun setName(name: String) {
        current = current?.copy(name = name)
        dirty = true
    }

    fun setRounds(rounds: Int) {
        current = current?.copy(rounds = rounds)
        dirty = true
    }

    fun addPoint(x: Float, y: Float) {
        val s = current ?: return
        current = s.copy(steps = s.steps + ScriptStep(x = x, y = y))
        dirty = true
    }

    fun addStep(step: ScriptStep) {
        val s = current ?: return
        current = s.copy(steps = s.steps + step)
        dirty = true
    }

    fun updateStep(index: Int, step: ScriptStep) {
        val s = current ?: return
        if (index !in s.steps.indices) return
        current = s.copy(steps = s.steps.toMutableList().also { it[index] = step })
        dirty = true
    }

    fun removeStep(index: Int) {
        val s = current ?: return
        if (index !in s.steps.indices) return
        current = s.copy(steps = s.steps.toMutableList().also { it.removeAt(index) })
        dirty = true
    }

    fun moveStep(index: Int, delta: Int) {
        val s = current ?: return
        val to = index + delta
        if (index !in s.steps.indices || to !in s.steps.indices) return
        val list = s.steps.toMutableList()
        val item = list.removeAt(index)
        list.add(to, item)
        current = s.copy(steps = list)
        dirty = true
    }
}
