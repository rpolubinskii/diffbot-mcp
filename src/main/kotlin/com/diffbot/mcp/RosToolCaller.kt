package com.diffbot.mcp

import io.modelcontextprotocol.spec.McpSchema

interface RosToolCaller {
    fun isConfigured(): Boolean

    fun clientSummary(): Map<String, Any?>

    fun call(tool: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?>

    fun callToolResult(tool: String, arguments: Map<String, Any?> = emptyMap()): McpSchema.CallToolResult

    fun callRaw(tool: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?>
}
