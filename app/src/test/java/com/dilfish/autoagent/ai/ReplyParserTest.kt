package com.dilfish.autoagent.ai

import com.dilfish.autoagent.engine.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyParserTest {

    @Test
    fun `裸数组解析`() {
        val cmds = ReplyParser.parse("""[{"type":"click","elementId":3}]""")
        assertNotNull(cmds)
        val click = cmds!!.first() as Command.Click
        assertEquals(3, click.elementId)
    }

    @Test
    fun `markdown 围栏解析`() {
        val cmds = ReplyParser.parse("好的\n```json\n[{\"type\":\"done\",\"summary\":\"ok\"}]\n```")
        assertNotNull(cmds)
        assertTrue(cmds!!.first() is Command.Done)
    }

    @Test
    fun `夹带文本括号配对`() {
        val cmds = ReplyParser.parse("我先点一下 [99] 然后 [{\"type\":\"wait\",\"ms\":800}] 结束")
        assertNotNull(cmds)
        assertTrue(cmds!!.first() is Command.Wait)
    }

    @Test
    fun `异形 action 归一化`() {
        val cmds = ReplyParser.parse("""[{"action":"tap","id":5}]""")
        assertNotNull(cmds)
        val click = cmds!!.first() as Command.Click
        assertEquals(5, click.elementId)
    }

    @Test
    fun `坏回复返回 null`() {
        assertNull(ReplyParser.parse("""[{"type":"click","elementId":"""))
        assertNull(ReplyParser.parse("今天天气不错"))
    }
}
