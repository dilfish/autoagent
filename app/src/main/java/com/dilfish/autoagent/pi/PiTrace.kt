package com.dilfish.autoagent.pi

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * pi 录制回放：每步 prompt/reply（或 error）落盘，供事后复现坏回复与 prompt 定位。
 * 纯 java.io 实现，不依赖 Android，可进 JVM 单测。
 *
 * 目录：<baseDir>/pi-trace/<yyyyMMdd-HHmmss>/stepNN-prompt.txt ...
 */
object PiTrace {

    fun newSessionDir(baseDir: File): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(baseDir, "pi-trace/$ts")
        dir.mkdirs()
        return dir
    }

    data class Record(
        val sessionDir: File,
        val stepNo: Int,
        val promptFile: File,
        val replyFile: File?,
        val errorFile: File?,
        val metaFile: File,
    )

    fun record(
        sessionDir: File,
        stepNo: Int,
        prompt: String,
        reply: String?,
        error: String?,
        backendId: String,
    ): Record {
        sessionDir.mkdirs()
        val tag = "step%02d".format(stepNo)
        val promptFile = File(sessionDir, "$tag-prompt.txt").apply { writeText(prompt) }
        val replyFile = reply?.let { File(sessionDir, "$tag-reply.txt").apply { writeText(it) } }
        val errorFile = error?.let { File(sessionDir, "$tag-error.txt").apply { writeText(it) } }
        val metaFile = File(sessionDir, "$tag-meta.json").apply {
            writeText(
                """{"step":$stepNo,"backend":"${backendId.replace("\"", "")}","ok":${reply != null}}""",
            )
        }
        return Record(sessionDir, stepNo, promptFile, replyFile, errorFile, metaFile)
    }

    /** 列出已有录制会话（按目录名倒序，最新的在前）。 */
    fun listSessions(baseDir: File): List<File> {
        val root = File(baseDir, "pi-trace")
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }
    }
}
