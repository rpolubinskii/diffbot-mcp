package com.diffbot.mcp

import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.annotation.McpResource
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class DiffbotMcpSurface(
    private val state: RobotStateService,
    private val navigation: NavigationService,
    private val audio: AudioClientService,
    private val toolCallLogger: McpToolCallLogger,
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
        name = "ROS summary",
        uri = "robot://diagnostics/ros-summary",
        description = "Summarized ROS graph and key topic availability from ros-mcp.",
        mimeType = "application/json",
    )
    fun rosSummary(): String = mapper.writeValueAsString(state.rosSummary(includeRawLists = false))

    // TODO: decide what to do later. disabled for now
    //	@McpResource(
    //		name = "raw ROS diagnostics",
    //		uri = "robot://diagnostics/ros-raw",
    //		description = "Advanced escape hatch for low-level ROS inspection through ros-mcp.",
    //		mimeType = "application/json",
    //	)
    fun rosRaw(): String = mapper.writeValueAsString(state.rosSummary(includeRawLists = true))

    @McpTool(
        name = "vision.get_camera_image",
        description = "Capture and return the latest RealSense RGB image from /camera/camera/color/image_raw as MCP image content for direct multimodal analysis.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
    )
    fun getCameraImage(
        @McpToolParam(description = "Optional timeout in seconds.", required = false)
        timeoutSeconds: Double?,
    ): McpSchema.CallToolResult = toolCallLogger.log(
        direction = "inbound",
        tool = "vision.get_camera_image",
        context = mapOf("timeout_seconds" to timeoutSeconds),
    ) {
        state.cameraImageContent(timeoutSeconds)
    }

    @McpTool(
        name = "nav.get_pose",
        description = "Return compact robot pose using localization pose when available, then odometry fallbacks with source metadata.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
    )
    fun getPose(
        @McpToolParam(description = "Optional timeout in seconds.", required = false)
        timeoutSeconds: Double?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "nav.get_pose",
        context = mapOf("timeout_seconds" to timeoutSeconds),
    ) {
        state.pose(timeoutSeconds)
    }

    @McpTool(
        name = "nav.get_imu",
        description = "Return compact IMU orientation, angular velocity, and linear acceleration from /imu/external/data_body.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
    )
    fun getImu(
        @McpToolParam(description = "Optional timeout in seconds.", required = false)
        timeoutSeconds: Double?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "nav.get_imu",
        context = mapOf("timeout_seconds" to timeoutSeconds),
    ) {
        state.imu(timeoutSeconds)
    }

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
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "nav.move_to",
        context = mapOf(
            "x" to x,
            "y" to y,
            "yaw_radians" to yawRadians,
            "timeout_seconds" to timeoutSeconds,
        ),
    ) {
        navigation.moveTo(x, y, yawRadians, timeoutSeconds)
    }

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
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "nav.turn",
        context = mapOf("radians" to radians, "timeout_seconds" to timeoutSeconds),
    ) {
        navigation.turn(radians, timeoutSeconds)
    }

    @McpTool(
        name = "nav.stop",
        description = "Cancel remembered navigation goals and send the safest available stop command.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true),
        generateOutputSchema = true,
    )
    fun stop(): Map<String, Any?> = toolCallLogger.log(direction = "inbound", tool = "nav.stop") {
        navigation.stop()
    }

    @McpTool(
        name = "speak.say",
        description = "Speak text using the diffbot-audio Piper backend.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun say(
        @McpToolParam(description = "Text to speak.")
        text: String,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "speak.say",
        context = mapOf("text_length" to text.length),
    ) {
        audio.speak(text)
    }

    @McpTool(
        name = "memory.retrieve",
        description = "Retrieve memory using the future diffbot-rag backend.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun retrieveMemory(
        @McpToolParam(description = "Memory query.")
        query: String,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "memory.retrieve",
        context = mapOf("query_length" to query.length),
    ) {
        GatewayResult.backendUnavailable("diffbot-rag") + mapOf("query" to query)
    }

    @McpTool(
        name = "memory.memorize",
        description = "Store memory using the future diffbot-rag backend.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun memorize(
        @McpToolParam(description = "Memory content to store.")
        content: String,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "memory.memorize",
        context = mapOf("content_length" to content.length),
    ) {
        GatewayResult.backendUnavailable("diffbot-rag") + mapOf("content" to content)
    }
}
