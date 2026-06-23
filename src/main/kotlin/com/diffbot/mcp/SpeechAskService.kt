package com.diffbot.mcp

import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext
import org.springframework.stereotype.Service

private const val DEFAULT_ANSWER_TIMEOUT_SECONDS = 120.0

@Service
class SpeechAskService(
    private val speech: SpeechOutput,
) {
    fun ask(context: McpSyncRequestContext, message: String, timeoutSeconds: Double?): Map<String, Any?> {
        val timeout = timeoutSeconds ?: DEFAULT_ANSWER_TIMEOUT_SECONDS
        if (timeout <= 0.0) {
            return GatewayResult.error("invalid_request", "timeoutSeconds must be greater than zero")
        }

        val speechResult = speech.speak(message)
        if (speechResult["ok"] != true) {
            return speechResult
        }
        if (!context.elicitEnabled()) {
            return GatewayResult.error("elicitation_unavailable", "MCP client does not support elicitation.")
        }

        return try {
            val result = context.elicit(rawAnswerRequest(message, timeout))
            when (result.action()) {
                McpSchema.ElicitResult.Action.ACCEPT -> accepted(result)
                McpSchema.ElicitResult.Action.DECLINE,
                McpSchema.ElicitResult.Action.CANCEL,
                    -> GatewayResult.ok(mapOf("action" to result.action().name.lowercase()))
            }
        } catch (ex: RuntimeException) {
            GatewayResult.error("elicitation_failed", ex.message ?: "MCP elicitation failed.")
        }
    }

    private fun accepted(result: McpSchema.ElicitResult): Map<String, Any?> {
        val answer = result.content()?.get("answer") as? String
        if (answer.isNullOrBlank()) {
            return GatewayResult.error("elicitation_invalid_response", "Elicitation accepted without a non-empty answer.")
        }
        return GatewayResult.ok(mapOf("action" to "accept", "answer" to answer))
    }

    private fun rawAnswerRequest(message: String, timeoutSeconds: Double): McpSchema.ElicitRequest =
        McpSchema.ElicitRequest.builder(message, RAW_ANSWER_SCHEMA)
            .meta(mapOf("timeoutSeconds" to timeoutSeconds))
            .build()

    companion object {
        val RAW_ANSWER_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "answer" to mapOf("type" to "string"),
            ),
            "required" to listOf("answer"),
        )
    }
}
