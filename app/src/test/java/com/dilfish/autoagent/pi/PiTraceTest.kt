package com.dilfish.autoagent.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PiTraceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `record 落盘命名与回读`() {
        val base = tmp.newFolder("files")
        val session = PiTrace.newSessionDir(base)
        assertTrue(session.isDirectory)

        PiTrace.record(session, 1, "prompt-1", "[{\"type\":\"click\"}]", null, "HttpStubBackend")
        PiTrace.record(session, 2, "prompt-2", null, "boom", "SshCliBackend")

        assertTrue(session.resolve("step01-prompt.txt").readText() == "prompt-1")
        assertTrue(session.resolve("step01-reply.txt").exists())
        assertTrue(session.resolve("step02-error.txt").readText() == "boom")
        assertTrue(session.resolve("step02-meta.json").readText().contains("SshCliBackend"))

        val sessions = PiTrace.listSessions(base)
        assertEquals(1, sessions.size)
        assertEquals(session, sessions.first())
    }
}
