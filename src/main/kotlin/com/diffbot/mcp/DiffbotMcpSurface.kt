package com.diffbot.mcp

import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.annotation.McpResource
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class DiffbotMcpSurface(
    private val state: RobotStateService,
    private val navigation: NavigationService,
    private val waitService: WaitService,
    private val audio: AudioClientService,
    private val speechAsk: SpeechAskService,
    private val memory: MemoryGateway,
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
        metaProvider = VisionCategory::class,
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
        metaProvider = StatusCategory::class,
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
        metaProvider = StatusCategory::class,
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
        metaProvider = NavigationCategory::class,
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
        metaProvider = NavigationCategory::class,
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
        metaProvider = SafetyCategory::class,
    )
    fun stop(): Map<String, Any?> = toolCallLogger.log(direction = "inbound", tool = "nav.stop") {
        navigation.stop()
    }

    @McpTool(
        name = "nav.cancel_goal",
        description = "Cancel the last remembered Nav2 NavigateToPose goal without publishing velocity stop commands.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = true),
        generateOutputSchema = true,
    )
    fun cancelNavigationGoal(): Map<String, Any?> = toolCallLogger.log(direction = "inbound", tool = "nav.cancel_goal") {
        navigation.cancelNavigateGoal()
    }

    @McpTool(
        name = "system.wait",
        description = "Block this tool call for a fixed duration in seconds.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun wait(
        @McpToolParam(description = "Positive wait duration in seconds, up to 300.")
        durationSeconds: Double,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "system.wait",
        context = mapOf("duration_seconds" to durationSeconds),
    ) {
        waitService.wait(durationSeconds)
    }

    @McpTool(
        name = "speak.say",
        description = "Speak text using the diffbot-audio Piper backend.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        generateOutputSchema = true,
        metaProvider = SpeechCategory::class,
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
        name = "speak.ask",
        description = "Speak a question, then wait for one raw text answer from the operator.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        generateOutputSchema = true,
        metaProvider = SpeechCategory::class,
    )
    fun ask(
        context: McpSyncRequestContext,
        @McpToolParam(description = "Question text to speak.")
        message: String,
        @McpToolParam(description = "Optional answer timeout in seconds. Defaults to 120.", required = false)
        timeoutSeconds: Double?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "speak.ask",
        context = mapOf("message_length" to message.length, "timeout_seconds" to timeoutSeconds),
    ) {
        speechAsk.ask(context, message, timeoutSeconds)
    }

    @McpTool(
        name = "memory.recall",
        description = "Recall relevant facts from long-term memory for a natural-language query.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        generateOutputSchema = true,
        metaProvider = GenericToolCategory::class,
    )
    fun recallMemory(
        @McpToolParam(description = "What to recall, in natural language.")
        query: String,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "memory.recall",
        context = mapOf("query_length" to query.length),
    ) {
        memory.recall(query)
    }

    @McpTool(
        name = "memory.remember",
        description = "Store a fact or observation in long-term memory for future recall.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        generateOutputSchema = true,
        metaProvider = GenericToolCategory::class,
    )
    fun rememberMemory(
        @McpToolParam(description = "The fact or observation to remember.")
        content: String,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "memory.remember",
        context = mapOf("content_length" to content.length),
    ) {
        memory.remember(content)
    }
}
