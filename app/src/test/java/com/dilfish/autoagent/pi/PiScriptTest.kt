package com.dilfish.autoagent.pi

import com.dilfish.autoagent.ai.ReplyParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PiScriptTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun scriptsDir(): File {
        // app/src/test → app → autoagent → server/pi-scripts
        var dir = File(".").absoluteFile
        while (dir.name != "autoagent" && dir.parentFile != null) dir = dir.parentFile!!
        return File(dir, "server/pi-scripts")
    }

    @Test
    fun `超步后重复最后一步`() {
        val script = PiScript("t", listOf(PiScriptStep("[1]"), PiScriptStep("[2]")))
        assertEquals("[1]", script.replyFor(0).reply)
        assertEquals("[2]", script.replyFor(1).reply)
        assertEquals("[2]", script.replyFor(99).reply)
    }

    @Test
    fun `剧本文件可解析且语义正确`() {
        val dir = scriptsDir()
        assertTrue("找不到剧本目录: ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertFalse("剧本目录为空", files.isEmpty())
        for (f in files) {
            val script = json.decodeFromString(PiScript.serializer(), f.readText())
            assertFalse("${f.name} steps 为空", script.steps.isEmpty())
            for (step in script.steps) {
                val parsed = ReplyParser.parse(step.reply)
                if (step.expectParseFail) {
                    assertNull("${f.name} 期望解析失败但成功了", parsed)
                } else {
                    assertNotNull("${f.name} 某步 reply 无法解析: ${step.reply.take(80)}", parsed)
                }
            }
        }
    }
}
