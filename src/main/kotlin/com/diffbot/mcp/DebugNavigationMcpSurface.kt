package com.diffbot.mcp

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "diffbot.debug", name = ["enabled"], havingValue = "true")
class DebugNavigationMcpSurface(
    private val navigation: NavigationService,
    private val toolCallLogger: McpToolCallLogger,
) {
    @McpTool(
        name = "nav.drive_on_heading",
        description = "Debug-only bounded relative drive using Nav2 collision checking. Positive distance moves forward and negative distance moves backward.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = true,
        ),
        generateOutputSchema = true,
    )
    fun driveOnHeading(
        @McpToolParam(description = "Signed travel distance in meters. Positive is forward and negative is backward.")
        distanceMeters: Double,
        @McpToolParam(description = "Optional positive speed magnitude in meters per second. Defaults to 0.15.", required = false)
        speedMetersPerSecond: Double?,
        @McpToolParam(description = "Optional positive action timeout in seconds. Defaults to 30.", required = false)
        timeoutSeconds: Double?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "nav.drive_on_heading",
        context = mapOf(
            "distance_meters" to distanceMeters,
            "speed_meters_per_second" to speedMetersPerSecond,
            "timeout_seconds" to timeoutSeconds,
        ),
    ) {
        navigation.driveOnHeading(distanceMeters, speedMetersPerSecond, timeoutSeconds)
    }
}
