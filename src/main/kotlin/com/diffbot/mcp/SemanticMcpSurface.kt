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
        description = "Find mapped objects by description; returns matches with map-frame (x, y). To go to one, " +
            "use nav.plan_approach then nav.move_to (not the raw x,y). Empty result = no match (normal). Includes " +
            "low-trust provisional objects (metadata.state=provisional) by default; validated_only=true for confirmed only.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
        metaProvider = SemanticCategory::class,
    )
    fun find(
        @McpToolParam(description = "What to look for, in natural language (e.g. \"couch\").")
        query: String,
        @McpToolParam(description = "Max matches. Omit for default.", required = false)
        topK: Int?,
        @McpToolParam(description = "Min normalized confidence 0..1. Omit for default.", required = false)
        minConfidence: Double?,
        @McpToolParam(description = "Confirmed objects only (omit to also include provisional).", required = false)
        validatedOnly: Boolean?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "semantic.find",
        context = mapOf(
            "query_length" to query.length,
            "top_k" to topK,
            "min_confidence" to minConfidence,
            "validated_only" to validatedOnly,
        ),
    ) {
        semantic.find(query, topK, minConfidence, validatedOnly)
    }

    @McpTool(
        name = "semantic.list_objects",
        description = "Capped inventory of mapped objects with map-frame (x, y). Navigate via nav.plan_approach " +
            "then nav.move_to. Includes provisional objects (metadata.state=provisional) by default; " +
            "validated_only=true for confirmed only.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
        metaProvider = SemanticCategory::class,
    )
    fun listObjects(
        @McpToolParam(description = "Max objects. Omit for default cap.", required = false)
        limit: Int?,
        @McpToolParam(description = "Confirmed objects only (omit to also include provisional).", required = false)
        validatedOnly: Boolean?,
    ): Map<String, Any?> = toolCallLogger.log(
        direction = "inbound",
        tool = "semantic.list_objects",
        context = mapOf("limit" to limit, "validated_only" to validatedOnly),
    ) {
        semantic.listObjects(limit, validatedOnly)
    }

    @McpTool(
        name = "semantic.describe_near",
        description = "Mapped objects near a map-frame point, nearest first (pairs with nav.get_pose). " +
            "Navigate via nav.plan_approach then nav.move_to.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
        metaProvider = SemanticCategory::class,
    )
    fun describeNear(
        @McpToolParam(description = "Map-frame x of the query point.")
        x: Double,
        @McpToolParam(description = "Map-frame y of the query point.")
        y: Double,
        @McpToolParam(description = "Search radius (m). Omit for default.", required = false)
        radius: Double?,
        @McpToolParam(description = "Max objects. Omit for default cap.", required = false)
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
        description = "Semantic-map health: readiness, object count, last-update age, pose-feed liveness. " +
            "Distinguishes 'no match' from 'map not ready'.",
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
