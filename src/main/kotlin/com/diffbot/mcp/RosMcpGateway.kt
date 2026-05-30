package com.diffbot.mcp

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Component
class RosMcpGateway(
	private val clientsProvider: ObjectProvider<List<McpSyncClient>>,
	private val properties: DiffbotProperties,
) {
	private val mapper = ObjectMapper()
	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val selectedClient = AtomicReference<McpSyncClient?>()
	private val rosbridgeConnected = AtomicBoolean(false)
	private val rosbridgeConnectionLock = Any()

	fun isConfigured(): Boolean = selectClient() != null

	fun clientSummary(): Map<String, Any?> {
		val clients = clientsProvider.ifAvailable ?: emptyList()
		val selected = selectClient()
		return mapOf(
			"configured" to (selected != null),
			"client_count" to clients.size,
			"selected_server" to selected?.let { safeServerName(it) },
			"rosbridge" to mapOf(
				"ip" to properties.rosbridge.ip,
				"port" to properties.rosbridge.port,
				"connected" to rosbridgeConnected.get(),
			),
		)
	}

	fun call(tool: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?> {
		val client = selectClient()
			?: return GatewayResult.error(
				"backend_unavailable",
				"ros-mcp client is not configured. Configure spring.ai.mcp.client.* to connect to ros-mcp.",
				mapOf("tool" to tool),
			)

		return try {
			if (tool != "connect_to_robot") {
				ensureRosbridgeConnected(client)?.let { return it }
			}
			callClientTool(client, tool, arguments)
		} catch (ex: Exception) {
			GatewayResult.error(
				classifyRosException(ex),
				ex.message ?: "ros-mcp call failed.",
				mapOf("tool" to tool),
			)
		}
	}

	fun callRaw(tool: String, arguments: Map<String, Any?> = emptyMap()): Map<String, Any?> {
		val client = selectClient()
			?: return GatewayResult.error(
				"backend_unavailable",
				"ros-mcp client is not configured. Configure spring.ai.mcp.client.* to connect to ros-mcp.",
				mapOf("tool" to tool),
			)

		return try {
			callClientTool(client, tool, arguments)
		} catch (ex: Exception) {
			GatewayResult.error(
				classifyRosException(ex),
				ex.message ?: "ros-mcp call failed.",
				mapOf("tool" to tool),
			)
		}
	}

	private fun ensureRosbridgeConnected(client: McpSyncClient): Map<String, Any?>? {
		if (rosbridgeConnected.get()) {
			return null
		}

		synchronized(rosbridgeConnectionLock) {
			if (rosbridgeConnected.get()) {
				return null
			}

			val rosbridge = properties.rosbridge
			val rosbridgeAddress = mapOf("ip" to rosbridge.ip, "port" to rosbridge.port)
			val result = try {
				callClientTool(
					client,
					"connect_to_robot",
					mapOf(
						"ip" to rosbridge.ip,
						"port" to rosbridge.port,
						"ping_timeout" to rosbridge.connectTimeoutSeconds,
						"port_timeout" to rosbridge.connectTimeoutSeconds,
					),
				)
			} catch (ex: Exception) {
				return GatewayResult.error(
					"ros_graph_unavailable",
					"ros-mcp could not connect to rosbridge.",
					mapOf("rosbridge" to rosbridgeAddress, "cause" to ex.message),
				)
			}

			if (isSuccessfulRosbridgeConnection(result)) {
				rosbridgeConnected.set(true)
				return null
			}

			return GatewayResult.error(
				"ros_graph_unavailable",
				"ros-mcp could not connect to rosbridge.",
				mapOf("rosbridge" to rosbridgeAddress, "ros" to result),
			)
		}
	}

	private fun callClientTool(client: McpSyncClient, tool: String, arguments: Map<String, Any?>): Map<String, Any?> {
		if (!client.isInitialized) {
			client.initialize()
		}
		val request = McpSchema.CallToolRequest.builder(tool)
			.arguments(arguments.filterValues { it != null }.mapValues { it.value as Any })
			.build()
		val result = client.callTool(request)
		return normalizeResult(tool, result)
	}

	private fun isSuccessfulRosbridgeConnection(result: Map<String, Any?>): Boolean {
		if (result["ok"] == false || result["error"] != null || result["is_error"] == true || result["success"] == false) {
			return false
		}

		val connectivity = result["connectivity_test"] as? Map<*, *>
		val overallStatus = connectivity?.get("overall_status")?.toString()
		if (overallStatus != null) {
			return overallStatus.startsWith("fully_accessible", ignoreCase = true)
		}

		val ping = connectivity?.get("ping") as? Map<*, *>
		val portCheck = connectivity?.get("port_check") as? Map<*, *>
		if (ping?.get("success") == true && portCheck?.get("open") == true) {
			return true
		}

		return result["message"] != null
	}

	private fun selectClient(): McpSyncClient? {
		selectedClient.get()?.let { return it }

		val clients = clientsProvider.ifAvailable ?: emptyList()
		if (clients.isEmpty()) {
			return null
		}

		val selected = clients.firstOrNull { safeServerName(it).contains(properties.rosMcpConnectionName, ignoreCase = true) }
			?: clients.first()
		selectedClient.compareAndSet(null, selected)
		return selected
	}

	private fun safeServerName(client: McpSyncClient): String =
		try {
			client.serverInfo?.name() ?: "unknown"
		} catch (_: Exception) {
			"unknown"
		}

	private fun normalizeResult(tool: String, result: McpSchema.CallToolResult): Map<String, Any?> {
		val structured = result.structuredContent()
		if (structured is Map<*, *>) {
			return structured.entries.associate { it.key.toString() to it.value }
		}

		val textItems = result.content()
			.orEmpty()
			.mapNotNull {
				when (it) {
					is McpSchema.TextContent -> it.text()
					is McpSchema.ImageContent -> mapper.writeValueAsString(
						mapOf("mime_type" to it.mimeType(), "data" to it.data(), "meta" to it.meta()),
					)
					else -> it.toString()
				}
			}

		if (textItems.size == 1) {
			val parsed = parseJsonObject(textItems.first())
			if (parsed != null) {
				return parsed
			}
		}

		return mapOf(
			"tool" to tool,
			"is_error" to (result.isError() == true),
			"content" to textItems,
			"meta" to result.meta(),
		)
	}

	private fun parseJsonObject(text: String): Map<String, Any?>? =
		try {
			mapper.readValue(text, mapType)
		} catch (_: Exception) {
			null
		}

	private fun classifyRosException(ex: Exception): String {
		val message = ex.message.orEmpty().lowercase()
		return when {
			"timeout" in message -> "timeout"
			"unavailable" in message || "connection" in message || "connect" in message -> "ros_graph_unavailable"
			else -> "ros_graph_unavailable"
		}
	}
}
