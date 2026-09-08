package com.dilfish.autoagent.pi

import android.content.Context
import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.log.AppLog
import com.dilfish.autoagent.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * 真机后端：SSH 到服务器，调用 `pi -p --no-session --no-tools`。
 * 与旧 PiSource 行为一致：每步一次 SSH exec，120s 超时。
 */
class SshCliBackend(
    private val appContext: Context,
) : PiBackend {

    private var ssh: SSHClient? = null

    private fun binPath() = AppSettings.piBinPath(appContext)

    private fun cmdPrefix(): String {
        val bin = binPath()
        val dir = bin.substringBeforeLast('/')
        // pi 是脚本，依赖 node；服务器 PATH 只在 .zshrc，非交互 SSH 必须手动补
        return "export PATH=\"$dir:\$PATH\"; \"$bin\""
    }

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            try {
                Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            } catch (_: Exception) {
            }
            val host = AppSettings.piHost(appContext)
            if (host.isEmpty()) throw RuntimeException("请先在设置里配置 pi 服务器")
            val port = AppSettings.piPort(appContext)
            val user = AppSettings.piUser(appContext)
            AppLog.d("pi", "ssh connect $user@$host:$port bin=${binPath()}")
            val client = SSHClient(DefaultConfig())
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connect(host, port)
            client.authPassword(user, AppSettings.piPassword(appContext))
            ssh = client
            AgentBus.log("已连接 pi 服务器 $host")
            AppLog.i("pi", "ssh connected $user@$host:$port")
        }
    }

    override suspend fun exec(payload: String): String =
        withContext(Dispatchers.IO) { execPi(payload) }

    private fun execPi(payload: String): String {
        val client = ssh ?: throw RuntimeException("SSH 未连接")
        val session = client.startSession()
        try {
            val shell = "${cmdPrefix()} -p --no-session --no-tools"
            AppLog.d("pi", "exec `$shell` payloadChars=${payload.length}")
            val t0 = System.currentTimeMillis()
            val cmd = session.exec(shell)
            cmd.outputStream.write(payload.toByteArray())
            cmd.outputStream.flush()
            cmd.outputStream.close()
            val out = IOUtils.readFully(cmd.inputStream).toString()
            val err = try {
                IOUtils.readFully(cmd.errorStream).toString()
            } catch (_: Exception) {
                ""
            }
            cmd.join(120, TimeUnit.SECONDS)
            AppLog.d(
                "pi",
                "exec done ${System.currentTimeMillis() - t0}ms outChars=${out.length} errChars=${err.length}",
            )
            if (err.isNotBlank()) AppLog.d("pi", "stderr: ${err.take(1000)}")
            if (out.isBlank() && err.isNotBlank()) throw RuntimeException("pi 执行失败: ${err.take(300)}")
            AppLog.d("pi", "stdout: ${out.take(4000)}")
            return out
        } finally {
            session.close()
        }
    }

    override fun stop() {
        try {
            ssh?.disconnect()
        } catch (_: Exception) {
        }
        ssh = null
    }
}
