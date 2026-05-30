package com.diffbot.mcp

import org.springframework.ai.mcp.annotation.McpArg
import org.springframework.ai.mcp.annotation.McpPrompt
import org.springframework.ai.mcp.annotation.McpResource
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class DiffbotMcpSurface(
	private val state: RobotStateService,
	private val navigation: NavigationService,
	private val ros: RosMcpGateway,
	private val properties: DiffbotProperties,
) {
	private val mapper = ObjectMapper()

	@McpResource(
		name = "robot status",
		uri = "robot://status",
		description = "Compact robot state for one command turn. Excludes raw images, lidar, full ROS graph dumps, and large diagnostics.",
		mimeType = "application/json",
	)
	fun robotStatus(): String = mapper.writeValueAsString(state.compactStatus())

	@McpResource(
		name = "robot capabilities",
		uri = "robot://capabilities",
		description = "Available high-level robot capabilities and connected backends.",
		mimeType = "application/json",
	)
	fun robotCapabilities(): String = mapper.writeValueAsString(
		GatewayResult.ok(
			mapOf(
				"high_level_capabilities" to listOf(
					"vision.get_camera_image",
					"vision.describe_camera_image",
					"nav.get_pose",
					"nav.get_imu",
					"nav.move_to",
					"nav.turn",
					"nav.stop",
					"speak.say",
					"memory.retrieve",
					"memory.memorize",
				),
				"ros_mcp" to ros.clientSummary(),
				"future_backends" to mapOf(
					"diffbot_vlm" to properties.futureServices.vlmConfigured,
					"diffbot_tts" to properties.futureServices.ttsConfigured,
					"diffbot_rag" to properties.futureServices.ragConfigured,
				),
				"safety" to mapOf(
					"raw_cmd_vel_public_tool" to false
				),
			),
		),
	)

	@McpResource(
		name = "ROS summary",
		uri = "robot://diagnostics/ros-summary",
		description = "Summarized ROS graph and key topic/action availability from ros-mcp.",
		mimeType = "application/json",
	)
	fun rosSummary(): String = mapper.writeValueAsString(state.rosSummary(includeRawLists = false))

	@McpResource(
		name = "raw ROS diagnostics",
		uri = "robot://diagnostics/ros-raw",
		description = "Advanced escape hatch for low-level ROS inspection through ros-mcp.",
		mimeType = "application/json",
	)
	fun rosRaw(): String = mapper.writeValueAsString(state.rosSummary(includeRawLists = true))

	@McpTool(
		name = "vision.get_camera_image",
		description = "Return the latest RealSense RGB image metadata/content provided by ros-mcp from /camera/camera/color/image_raw.",
		annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
		generateOutputSchema = true,
	)
	fun getCameraImage(
		@McpToolParam(description = "Optional timeout in seconds.", required = false)
		timeoutSeconds: Double?,
	): Map<String, Any?> = state.cameraImage(timeoutSeconds)

	@McpTool(
		name = "vision.describe_camera_image",
		description = "Describe the latest camera image using the future diffbot-vlm backend.",
		annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false),
		generateOutputSchema = true,
	)
	fun describeCameraImage(): Map<String, Any?> = GatewayResult.backendUnavailable("diffbot-vlm")

	@McpTool(
		name = "nav.get_pose",
		description = "Return compact robot pose using localization pose when available, then odometry fallbacks with source metadata.",
		annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
		generateOutputSchema = true,
	)
	fun getPose(
		@McpToolParam(description = "Optional timeout in seconds.", required = false)
		timeoutSeconds: Double?,
	): Map<String, Any?> = state.pose(timeoutSeconds)

	@McpTool(
		name = "nav.get_imu",
		description = "Return compact IMU orientation, angular velocity, and linear acceleration from /imu/external/data_body.",
		annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
		generateOutputSchema = true,
	)
	fun getImu(
		@McpToolParam(description = "Optional timeout in seconds.", required = false)
		timeoutSeconds: Double?,
	): Map<String, Any?> = state.imu(timeoutSeconds)

	@McpTool(
		name = "nav.move_to",
		description = "Send a guarded Nav2 NavigateToPose goal in the map frame.",
		annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true),
		generateOutputSchema = true,
	)
	fun moveTo(
		@McpToolParam(description = "Target x coordinate in the map frame.")
		x: Double,
		@McpToolParam(description = "Target y coordinate in the map frame.")
		y: Double,
		@McpToolParam(description = "Target yaw in radians.")
		yawRadians: Double,
		@McpToolParam(description = "Optional action timeout in seconds.", required = false)
		timeoutSeconds: Double?,
	): Map<String, Any?> = navigation.moveTo(x, y, yawRadians, timeoutSeconds)

	@McpTool(
		name = "nav.turn",
		description = "Perform a bounded in-place rotation through the Nav2 Spin action.",
		annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true),
		generateOutputSchema = true,
	)
	fun turn(
		@McpToolParam(description = "Relative turn angle in radians.")
		radians: Double,
		@McpToolParam(description = "Optional action timeout in seconds.", required = false)
		timeoutSeconds: Double?,
	): Map<String, Any?> = navigation.turn(radians, timeoutSeconds)

	@McpTool(
		name = "nav.stop",
		description = "Cancel remembered navigation goals and send the safest available stop command.",
		annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true),
		generateOutputSchema = true,
	)
	fun stop(): Map<String, Any?> = navigation.stop()

	@McpTool(
		name = "speak.say",
		description = "Speak text using the future diffbot-tts backend.",
		annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
		generateOutputSchema = true,
	)
	fun say(
		@McpToolParam(description = "Text to speak.")
		text: String,
	): Map<String, Any?> = GatewayResult.backendUnavailable("diffbot-tts") + mapOf("text" to text)

	@McpTool(
		name = "memory.retrieve",
		description = "Retrieve memory using the future diffbot-rag backend.",
		annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false),
		generateOutputSchema = true,
	)
	fun retrieveMemory(
		@McpToolParam(description = "Memory query.")
		query: String,
	): Map<String, Any?> = GatewayResult.backendUnavailable("diffbot-rag") + mapOf("query" to query)

	@McpTool(
		name = "memory.memorize",
		description = "Store memory using the future diffbot-rag backend.",
		annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
		generateOutputSchema = true,
	)
	fun memorize(
		@McpToolParam(description = "Memory content to store.")
		content: String,
	): Map<String, Any?> = GatewayResult.backendUnavailable("diffbot-rag") + mapOf("content" to content)

	@McpPrompt(
		name = "diffbot.command_turn",
		title = "DiffBot Command Turn",
		description = "Prompt for one voice/user command turn against DiffBot.",
	)
	fun commandTurn(
		@McpArg(name = "vocal_command", description = "Voice command transcript.", required = true)
		vocalCommand: String?,
		@McpArg(name = "operator_text", description = "Optional operator text.", required = false)
		operatorText: String?,
		@McpArg(name = "robot_status", description = "Optional compact robot://status payload.", required = false)
		robotStatus: String?,
	): String =
		"""
		You are controlling DiffBot for one command turn.

		Vocal command:
		${vocalCommand.orEmpty()}

		Operator text:
		${operatorText.orEmpty()}

		Robot status:
		${robotStatus.orEmpty()}

		Rules:
		- Prefer high-level diffbot-mcp tools over diagnostics.
		- Use diagnostics only when high-level state is unavailable or inconsistent.
		- Do not assume images, lidar, raw ROS graph data, or memory search results unless you explicitly call the relevant tool/resource.
		- Stop or cancel motion on uncertainty, failed motion, timeout, or interruption.
		- Treat backend_unavailable, ros_graph_unavailable, localization_unavailable, navigation_rejected, timeout, and unsafe_request as actionable error classes.
		""".trimIndent()
}
