package com.dilfish.autoagent.script

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 单个脚本步骤：在 (x, y) 执行动作，重复 repeat 次，每步之间等待 postDelayMs */
@Serializable
data class ScriptStep(
    val x: Float,
    val y: Float,
    val action: String = "click", // click | longClick
    val postDelayMs: Long = 500,
    val repeat: Int = 1,
)

/** rounds = 0 表示无限循环 */
@Serializable
data class Script(
    val id: String,
    val name: String,
    val steps: List<ScriptStep> = emptyList(),
    val rounds: Int = 0,
)

/** 脚本持久化：SharedPreferences + JSON，支持保存多个脚本 */
object ScriptStore {
    private val json = Json { ignoreUnknownKeys = true }
    private const val PREFS = "autoagent_scripts"
    private const val KEY_LIST = "list"
    private const val KEY_LAST = "last_selected"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(ctx: Context): List<Script> = try {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(Script.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }

    fun save(ctx: Context, script: Script) {
        val all = loadAll(ctx).toMutableList()
        val idx = all.indexOfFirst { it.id == script.id }
        if (idx >= 0) all[idx] = script else all.add(script)
        prefs(ctx).edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(Script.serializer()), all))
            .apply()
    }

    fun delete(ctx: Context, id: String) {
        val all = loadAll(ctx).filterNot { it.id == id }
        prefs(ctx).edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(Script.serializer()), all))
            .apply()
        if (lastSelected(ctx) == id) setLastSelected(ctx, null)
    }

    fun lastSelected(ctx: Context): String? = prefs(ctx).getString(KEY_LAST, null)

    fun setLastSelected(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(KEY_LAST, id).apply()
    }

    fun newId(): String = "s${System.currentTimeMillis()}"
}
