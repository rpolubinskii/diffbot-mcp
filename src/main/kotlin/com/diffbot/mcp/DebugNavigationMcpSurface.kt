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
        description = "Debug: bounded relative drive (Nav2 collision-checked). +distance forward, -distance backward.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = true,
        ),
        generateOutputSchema = true,
    )
    fun driveOnHeading(
        @McpToolParam(description = "Signed distance (m): + forward, - backward.")
        distanceMeters: Double,
        @McpToolParam(description = "Speed magnitude (m/s). Default 0.15.", required = false)
        speedMetersPerSecond: Double?,
        @McpToolParam(description = "Action timeout (s). Default 30.", required = false)
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
