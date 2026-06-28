package com.diffbot.mcp

import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class SemanticMcpSurface(
    private val semantic: SemanticClientService,
    private val toolCallLogger: McpToolCallLogger,
) {
    @McpTool(
        name = "semantic.find",
        description = "Find mapped objects matching a description; returns ranked matches with map-frame " +
            "object coordinates plus a suggested_goal standoff pose for nav.move_to when the current map pose " +
            "is available. Use suggested_goal for navigation, not the raw object centroid. Results may be stale " +
            "or low-confidence and an empty result (no match) is a normal answer. Navigation targets still pass " +
            "standard goal validation.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
        metaProvider = SemanticCategory::class,
    )
    fun find(
        @McpToolParam(description = "What to look for, in natural language (e.g. \"couch\", \"coffee machine\").")
        query: String,
        @McpToolParam(description = "Max matches to return. Omit for the server default.", required = false)
        topK: Int?,
        @McpToolParam(description = "Minimum normalized confidence in 0..1. Omit for the server default.", required = false)
        minConfidence: Double?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "semantic.find",
        context = mapOf("query_length" to query.length, "top_k" to topK, "min_confidence" to minConfidence),
    ) {
        semantic.find(query, topK, minConfidence)
    }

    @McpTool(
        name = "semantic.list_objects",
        description = "Return a compact, capped inventory snapshot of objects currently in the semantic map. " +
            "Each object includes a suggested_goal standoff pose for nav.move_to when the current map pose is available.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
        metaProvider = SemanticCategory::class,
    )
    fun listObjects(
        @McpToolParam(description = "Max objects to return. Omit for the server default cap.", required = false)
        limit: Int?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "semantic.list_objects",
        context = mapOf("limit" to limit),
    ) {
        semantic.listObjects(limit)
    }

    @McpTool(
        name = "semantic.describe_near",
        description = "List mapped objects around a map-frame point (pairs with nav.get_pose), nearest first. " +
            "Use each object's suggested_goal for navigation when present, not the raw object centroid.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
        metaProvider = SemanticCategory::class,
    )
    fun describeNear(
        @McpToolParam(description = "Map-frame x coordinate of the point to describe around.")
        x: Double,
        @McpToolParam(description = "Map-frame y coordinate of the point to describe around.")
        y: Double,
        @McpToolParam(description = "Search radius in meters. Omit for the server default.", required = false)
        radius: Double?,
        @McpToolParam(description = "Max objects to return. Omit for the server default cap.", required = false)
        limit: Int?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "semantic.describe_near",
        context = mapOf("x" to x, "y" to y, "radius" to radius, "limit" to limit),
    ) {
        semantic.describeNear(x, y, radius, limit)
    }

    @McpTool(
        name = "semantic.status",
        description = "Report semantic-map health: readiness, objects tracked, last-update age, and pose-feed " +
            "liveness. Call this when finds fail to tell 'nothing matched' from 'the map is not ready'.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
        metaProvider = SemanticCategory::class,
    )
    fun status(): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "semantic.status",
    ) {
        semantic.status()
    }
}
