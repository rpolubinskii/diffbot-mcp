package com.diffbot.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class McpToolCallLoggerTests {
    private val logger = LoggerFactory.getLogger(McpToolCallLogger::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().also {
        it.start()
        logger.addAppender(it)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `logs successful tool call with structured context`() {
        val result = McpToolCallLogger().log(
            direction = "inbound",
            tool = "nav.turn",
            context = mapOf("radians" to 1.5),
        ) {
            GatewayResult.ok()
        }

        assertEquals(true, result["ok"])
        assertEquals(2, appender.list.size)

        val completion = appender.list.last()
        assertEquals(Level.INFO, completion.level)
        assertEquals("MCP tool call completed", completion.formattedMessage)
        assertEquals("mcp_tool_call", completion.fields()["operation"])
        assertEquals("inbound", completion.fields()["direction"])
        assertEquals("nav.turn", completion.fields()["tool"])
        assertEquals(true, completion.fields()["success"])
        assertNotNull(completion.fields()["call_id"])
        assertNotNull(completion.fields()["duration_ms"])
    }

    @Test
    fun `logs returned tool errors without logging sensitive values`() {
        McpToolCallLogger().log(
            direction = "inbound",
            tool = "speak.say",
            context = mapOf("text_length" to 14),
        ) {
            GatewayResult.error("audio_unavailable", "backend failed")
        }

        val completion = appender.list.last()
        assertEquals(Level.WARN, completion.level)
        assertEquals(false, completion.fields()["success"])
        assertEquals("audio_unavailable", completion.fields()["error_class"])
        assertEquals(14, completion.fields()["text_length"])
        assertFalse(completion.fields().containsKey("text"))
        assertFalse(completion.formattedMessage.contains("backend failed"))
    }

    @Test
    fun `logs exceptions and rethrows them`() {
        val failure = runCatching {
            McpToolCallLogger().log(direction = "outbound", tool = "subscribe_once") {
                throw IllegalStateException("connection failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val event = appender.list.last()
        assertEquals(Level.ERROR, event.level)
        assertEquals(false, event.fields()["success"])
        assertEquals(IllegalStateException::class.qualifiedName, event.fields()["exception_class"])
        assertNotNull(event.throwableProxy)
        assertNull(event.fields()["error_class"])
    }

    private fun ILoggingEvent.fields(): Map<String, Any?> =
        keyValuePairs.orEmpty().associate { it.key to it.value }
}
