package com.dilfish.autoagent.log

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.dilfish.autoagent.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 调试日志模块：分级 + 模块标签 + 内存环缓 + 落盘 + 导出/分享。
 * DEBUG 级别仅在 BuildConfig.VERBOSE_LOG=true（debug 包默认开）时写入 UI/文件；
 * INFO/WARN/ERROR 始终记录。
 */
object AppLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private const val TAG = "AutoAgent"
    private const val MAX_UI_LINES = 800
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val io = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile private var appContext: Context? = null
    @Volatile private var logDir: File? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    /** 供主页/控制台展示（含 DEBUG，调试包会更密） */
    val lines: StateFlow<List<String>> = _lines

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        logDir = File(app.filesDir, "logs").also { it.mkdirs() }
        i("log", "AppLog init verbose=${BuildConfig.VERBOSE_LOG} v=${BuildConfig.VERSION_NAME}")
    }

    fun d(module: String, msg: String, t: Throwable? = null) {
        if (!BuildConfig.VERBOSE_LOG) {
            // 仍打到 Logcat，方便 adb，但不占 UI/文件
            if (t != null) Log.d("$TAG/$module", msg, t) else Log.d("$TAG/$module", msg)
            return
        }
        write(Level.DEBUG, module, msg, t)
    }

    fun i(module: String, msg: String, t: Throwable? = null) = write(Level.INFO, module, msg, t)
    fun w(module: String, msg: String, t: Throwable? = null) = write(Level.WARN, module, msg, t)
    fun e(module: String, msg: String, t: Throwable? = null) = write(Level.ERROR, module, msg, t)

    /** 兼容旧调用：当作 INFO / ui */
    fun legacy(msg: String) = i("ui", msg)

    fun clear() {
        _lines.value = emptyList()
        io.execute {
            logDir?.listFiles()?.forEach { runCatching { it.delete() } }
        }
        i("log", "日志已清空")
    }

    /**
     * 导出当前内存日志 + 今日落盘文件：
     * 1) 写入应用私有 cache（供 FileProvider 分享）
     * 2) 尽量写入公共 Download/AutoAgent/（MediaStore，Q+ 无需存储权限）
     * 返回用于分享的 cache 文件。
     */
    fun export(context: Context): File? {
        val stamp = stampFmt.format(Date())
        val body = buildString {
            appendLine("=== AutoAgent log export ===")
            appendLine("time=$stamp version=${BuildConfig.VERSION_NAME} verbose=${BuildConfig.VERBOSE_LOG}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            appendLine()
            // 落盘文件（按文件名排序）
            logDir?.listFiles()?.sortedBy { it.name }?.forEach { f ->
                appendLine("--- file ${f.name} (${f.length()} bytes) ---")
                runCatching { append(f.readText()) }
                appendLine()
            }
            appendLine("--- memory ring (${_lines.value.size} lines) ---")
            // 内存是新→旧，导出时倒过来按时间正序
            _lines.value.asReversed().forEach { appendLine(it) }
        }

        val cacheDir = File(context.cacheDir, "log_export").also { it.mkdirs() }
        val cacheFile = File(cacheDir, "autoagent-$stamp.log")
        cacheFile.writeText(body)

        // 公共 Download
        runCatching { writeToDownloads(context, "autoagent-$stamp.log", body) }
            .onFailure { e("log", "写入 Download 失败: ${it.message}", it) }
            .onSuccess { i("log", "已写入 Download/AutoAgent/autoagent-$stamp.log") }

        i("log", "导出完成 ${cacheFile.name} (${cacheFile.length()} bytes)")
        return cacheFile
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AutoAgent logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出日志"))
    }

    private fun write(level: Level, module: String, msg: String, t: Throwable?) {
        val ts = timeFmt.format(Date())
        val levelTag = when (level) {
            Level.DEBUG -> "D"
            Level.INFO -> "I"
            Level.WARN -> "W"
            Level.ERROR -> "E"
        }
        val base = "$ts $levelTag/$module  $msg"
        val line = if (t != null) "$base | ${t.javaClass.simpleName}: ${t.message}" else base

        when (level) {
            Level.DEBUG -> if (t != null) Log.d("$TAG/$module", msg, t) else Log.d("$TAG/$module", msg)
            Level.INFO -> if (t != null) Log.i("$TAG/$module", msg, t) else Log.i("$TAG/$module", msg)
            Level.WARN -> if (t != null) Log.w("$TAG/$module", msg, t) else Log.w("$TAG/$module", msg)
            Level.ERROR -> if (t != null) Log.e("$TAG/$module", msg, t) else Log.e("$TAG/$module", msg)
        }

        val next = (listOf(line) + _lines.value).take(MAX_UI_LINES)
        _lines.value = next
        io.execute { appendToFile(line, t) }
    }

    private fun appendToFile(line: String, t: Throwable?) {
        val dir = logDir ?: return
        val file = File(dir, "autoagent-${dayFmt.format(Date())}.log")
        runCatching {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val rotated = File(dir, "${file.nameWithoutExtension}-${stampFmt.format(Date())}.log")
                file.renameTo(rotated)
            }
            file.appendText(line + "\n")
            if (t != null) {
                file.appendText(Log.getStackTraceString(t) + "\n")
            }
        }
    }

    private fun writeToDownloads(context: Context, name: String, body: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AutoAgent")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AutoAgent")
        dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(body.toByteArray()) }
        return Uri.fromFile(file)
    }
}
