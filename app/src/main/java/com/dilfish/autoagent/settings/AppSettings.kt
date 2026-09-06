package com.dilfish.autoagent.settings

import android.content.Context

/** 全局设置：LLM API、远程中转等配置（SharedPreferences 持久化） */
object AppSettings {
    private const val PREFS = "autoagent_settings"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- B3 内置 LLM ----
    fun llmBaseUrl(ctx: Context): String = prefs(ctx).getString("llm_base_url", "") ?: ""
    fun llmApiKey(ctx: Context): String = prefs(ctx).getString("llm_api_key", "") ?: ""
    fun llmModel(ctx: Context): String = prefs(ctx).getString("llm_model", "") ?: ""

    fun setLlm(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        prefs(ctx).edit()
            .putString("llm_base_url", baseUrl.trim().removeSuffix("/"))
            .putString("llm_api_key", apiKey.trim())
            .putString("llm_model", model.trim())
            .apply()
    }

    // ---- B5 远程中转 ----
    fun remoteWsUrl(ctx: Context): String = prefs(ctx).getString("remote_ws_url", "") ?: ""
    fun remoteToken(ctx: Context): String = prefs(ctx).getString("remote_token", "") ?: ""

    fun setRemote(ctx: Context, wsUrl: String, token: String) {
        prefs(ctx).edit()
            .putString("remote_ws_url", wsUrl.trim())
            .putString("remote_token", token.trim())
            .apply()
    }

    // ---- B4 pi agent（预留，未实现；配置项在实现时再加） ----
}
