package com.diffbot.mcp

import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
class McpToolCallLogger {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun <T> log(
        direction: String,
        tool: String,
        context: Map<String, Any?> = emptyMap(),
        call: () -> T,
    ): T {
        val callId = UUID.randomUUID().toString()
        val startedAt = System.nanoTime()

        logger.atInfo()
            .addCallContext(direction, tool, callId, context)
            .log("MCP tool call started")

        return try {
            val result = call()
            val outcome = outcomeOf(result)
            val event = if (outcome.success) logger.atInfo() else logger.atWarn()
            event
                .addCallContext(direction, tool, callId, context)
                .addKeyValue("success", outcome.success)
                .addKeyValue("duration_ms", elapsedMilliseconds(startedAt))
                .apply {
                    outcome.errorClass?.let { addKeyValue("error_class", it) }
                }
                .log("MCP tool call completed")
            result
        } catch (ex: Exception) {
            logger.atError()
                .addCallContext(direction, tool, callId, context)
                .addKeyValue("success", false)
                .addKeyValue("duration_ms", elapsedMilliseconds(startedAt))
                .addKeyValue("exception_class", ex::class.qualifiedName ?: ex::class.simpleName)
                .setCause(ex)
                .log("MCP tool call failed")
            throw ex
        }
    }

    private fun LoggingEventBuilder.addCallContext(
        direction: String,
        tool: String,
        callId: String,
        context: Map<String, Any?>,
    ): LoggingEventBuilder {
        addKeyValue("operation", "mcp_tool_call")
        addKeyValue("direction", direction)
        addKeyValue("tool", tool)
        addKeyValue("call_id", callId)
        context.forEach { (key, value) ->
            if (value != null) {
                addKeyValue(key, value)
            }
        }
        return this
    }

    private fun outcomeOf(result: Any?): ToolCallOutcome =
        when (result) {
            is McpSchema.CallToolResult -> ToolCallOutcome(
                success = result.isError() != true,
                errorClass = if (result.isError() == true) "tool_error" else null,
            )

            is Map<*, *> -> {
                val failed = result["ok"] == false ||
                    result["is_error"] == true ||
                    result["success"] == false ||
                    result["error"] != null
                ToolCallOutcome(
                    success = !failed,
                    errorClass = if (failed) result["error_class"]?.toString() ?: "tool_error" else null,
                )
            }

            else -> ToolCallOutcome(success = true)
        }

    private fun elapsedMilliseconds(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private data class ToolCallOutcome(
        val success: Boolean,
        val errorClass: String? = null,
    )
}
