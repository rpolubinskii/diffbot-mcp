package com.diffbot.mcp

import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechAskServiceTest {
    @Test
    fun `tts failure prevents elicitation`() {
        val speech = FakeSpeechOutput(GatewayResult.error("speech_failed", "speaker failed"))
        val context = Mockito.mock(McpSyncRequestContext::class.java)
        val result = SpeechAskService(speech).ask(context, "Where should I go?", null)

        assertFalse(result["ok"] as Boolean)
        assertEquals(1, speech.calls)
        Mockito.verify(context, Mockito.never()).elicit(ArgumentMatchers.any(McpSchema.ElicitRequest::class.java))
    }

    @Test
    fun `tts success creates raw answer elicitation and maps accept`() {
        val speech = FakeSpeechOutput(GatewayResult.ok())
        val context = Mockito.mock(McpSyncRequestContext::class.java)
        Mockito.`when`(context.elicitEnabled()).thenReturn(true)
        Mockito.`when`(context.elicit(ArgumentMatchers.any(McpSchema.ElicitRequest::class.java)))
            .thenReturn(
                McpSchema.ElicitResult(
                    McpSchema.ElicitResult.Action.ACCEPT,
                    mapOf("answer" to "the kitchen"),
                ),
            )

        val result = SpeechAskService(speech).ask(context, "Where should I go?", 7.0)

        val request = captureElicitRequest(context)
        assertTrue(result["ok"] as Boolean)
        assertEquals("accept", result["action"])
        assertEquals("the kitchen", result["answer"])
        assertEquals("Where should I go?", request.message())
        assertEquals(7.0, request.meta()["timeoutSeconds"])
        assertEquals(listOf("answer"), request.requestedSchema()["required"])
        val properties = request.requestedSchema()["properties"] as Map<*, *>
        assertEquals(mapOf("type" to "string"), properties["answer"])
    }

    @Test
    fun `cancel result maps to cancel action`() {
        val speech = FakeSpeechOutput(GatewayResult.ok())
        val context = Mockito.mock(McpSyncRequestContext::class.java)
        Mockito.`when`(context.elicitEnabled()).thenReturn(true)
        Mockito.`when`(context.elicit(ArgumentMatchers.any(McpSchema.ElicitRequest::class.java)))
            .thenReturn(McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, null))

        val result = SpeechAskService(speech).ask(context, "Where should I go?", null)

        assertTrue(result["ok"] as Boolean)
        assertEquals("cancel", result["action"])
    }

    private fun captureElicitRequest(context: McpSyncRequestContext): McpSchema.ElicitRequest {
        val captor = ArgumentCaptor.forClass(McpSchema.ElicitRequest::class.java)
        Mockito.verify(context).elicit(captor.capture())
        return captor.value
    }

    private class FakeSpeechOutput(
        private val result: Map<String, Any?>,
    ) : SpeechOutput {
        var calls = 0

        override fun speak(text: String): Map<String, Any?> {
            calls += 1
            return result
        }
    }
}
