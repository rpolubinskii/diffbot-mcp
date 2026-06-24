package com.diffbot.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

// Graphiti tool + argument mapping, kept pure so the contract is unit-testable
// without an MCP client. Keep tool/param names in sync with the diffbot-memory
// (Graphiti MCP) version.
object MemoryRequests {
    fun remember(content: String, groupId: String, referenceTime: String): Pair<String, Map<String, Any?>> =
        "add_memory" to linkedMapOf(
            "name" to "diffbot episode",
            "episode_body" to content,
            "group_id" to groupId,
            "source" to "text",
            "reference_time" to referenceTime,
        )

    fun recall(query: String, groupId: String, maxFacts: Int): Pair<String, Map<String, Any?>> =
        "search_memory_facts" to linkedMapOf(
            "query" to query,
            "group_ids" to listOf(groupId),
            "max_facts" to maxFacts,
        )
}

@Component
class MemoryGateway(
    private val clientsProvider: ObjectProvider<List<McpSyncClient>>,
    private val properties: DiffbotProperties,
    private val toolCallLogger: McpToolCallLogger,
) {
    private val mapper = ObjectMapper()
    private val mapType = object : TypeReference<Map<String, Any?>>() {}
    private val selected = AtomicReference<McpSyncClient?>()

    fun remember(content: String): Map<String, Any?> {
        val (tool, args) = MemoryRequests.remember(content, properties.memory.groupId, Instant.now().toString())
        return call(tool, args)
    }

    fun recall(query: String): Map<String, Any?> {
        val (tool, args) = MemoryRequests.recall(query, properties.memory.groupId, properties.memory.recallMaxFacts)
        return call(tool, args)
    }

    private fun call(tool: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val client = selectClient()
            ?: return GatewayResult.error(
                "backend_unavailable",
                "diffbot-memory MCP client is not configured. Set " +
                    "spring.ai.mcp.client.streamable-http.connections.diffbot-memory.*",
                mapOf("tool" to tool),
            )

        return try {
            toolCallLogger.log(
                direction = "outbound",
                tool = tool,
                context = mapOf("server" to safeServerName(client)),
            ) {
                if (!client.isInitialized) {
                    client.initialize()
                }
                val request = McpSchema.CallToolRequest.builder(tool)
                    .arguments(arguments.filterValues { it != null }.mapValues { it.value as Any })
                    .build()
                normalize(tool, client.callTool(request))
            }
        } catch (ex: Exception) {
            GatewayResult.error(
                "memory_unavailable",
                ex.message ?: "diffbot-memory call failed.",
                mapOf("tool" to tool),
            )
        }
    }

    private fun selectClient(): McpSyncClient? {
        selected.get()?.let { return it }
        val clients = clientsProvider.ifAvailable ?: emptyList()
        val match = clients.firstOrNull {
            safeServerName(it).contains(properties.memory.serverNameMatch, ignoreCase = true)
        }
        if (match != null) {
            selected.compareAndSet(null, match)
        }
        return match
    }

    private fun safeServerName(client: McpSyncClient): String =
        try {
            client.serverInfo?.name() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    private fun normalize(tool: String, result: McpSchema.CallToolResult): Map<String, Any?> {
        val structured = result.structuredContent()
        if (structured is Map<*, *>) {
            return structured.entries.associate { it.key.toString() to it.value }
        }
        val textItems = result.content().orEmpty().mapNotNull {
            (it as? McpSchema.TextContent)?.text()
        }
        if (textItems.size == 1) {
            try {
                return mapper.readValue(textItems.first(), mapType)
            } catch (_: Exception) {
                // fall through to the wrapped form
            }
        }
        return linkedMapOf(
            "tool" to tool,
            "is_error" to (result.isError() == true),
            "content" to textItems,
        )
    }
}
