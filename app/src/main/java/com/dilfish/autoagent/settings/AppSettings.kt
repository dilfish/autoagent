package com.dilfish.autoagent.settings

import android.content.Context

/** 全局设置：LLM API、远程中转等配置（SharedPreferences 持久化） */
object AppSettings {
    private const val PREFS = "autoagent_settings"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- B3 内置 LLM ----
    fun llmProvider(ctx: Context): String = prefs(ctx).getString("llm_provider", "openai") ?: "openai"
    fun llmBaseUrl(ctx: Context): String = prefs(ctx).getString("llm_base_url", "") ?: ""
    fun llmApiKey(ctx: Context): String = prefs(ctx).getString("llm_api_key", "") ?: ""
    fun llmModel(ctx: Context): String = prefs(ctx).getString("llm_model", "") ?: ""

    fun setLlm(ctx: Context, provider: String, baseUrl: String, apiKey: String, model: String) {
        prefs(ctx).edit()
            .putString("llm_provider", provider)
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

    // ---- B4 pi agent ----
    const val PI_MODE_SSH = "ssh"
    const val PI_MODE_STUB = "http-stub"
    fun piMode(ctx: Context): String = prefs(ctx).getString("pi_mode", PI_MODE_SSH) ?: PI_MODE_SSH
    fun piStubUrl(ctx: Context): String = prefs(ctx).getString("pi_stub_url", "") ?: ""
    fun piHost(ctx: Context): String = prefs(ctx).getString("pi_host", "") ?: ""
    fun piPort(ctx: Context): Int = prefs(ctx).getString("pi_port", "22")?.toIntOrNull() ?: 22
    fun piUser(ctx: Context): String = prefs(ctx).getString("pi_user", "root") ?: "root"
    fun piPassword(ctx: Context): String = prefs(ctx).getString("pi_password", "") ?: ""
    fun piBinPath(ctx: Context): String =
        prefs(ctx).getString("pi_bin_path", "/root/.local/share/pi-node/node-v22.23.2-linux-x64/bin/pi") ?: ""

    fun setPi(ctx: Context, host: String, port: Int, user: String, password: String, binPath: String) {
        prefs(ctx).edit()
            .putString("pi_host", host.trim())
            .putString("pi_port", port.toString())
            .putString("pi_user", user.trim())
            .putString("pi_password", password)
            .putString("pi_bin_path", binPath.trim())
            .apply()
    }

    fun setPiBackend(ctx: Context, mode: String, stubUrl: String) {
        prefs(ctx).edit()
            .putString("pi_mode", if (mode == PI_MODE_STUB) PI_MODE_STUB else PI_MODE_SSH)
            .putString("pi_stub_url", stubUrl.trim().removeSuffix("/"))
            .apply()
    }
}
