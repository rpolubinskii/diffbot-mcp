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
        description = "Find mapped objects matching a description; returns ranked matches with each object's " +
            "map-frame coordinates (x, y). To navigate to one, pass its (x, y) to nav.plan_approach for a " +
            "reachable standoff pose, then nav.move_to — do not send the raw object centroid to nav.move_to. " +
            "Results may be stale or low-confidence and an empty result (no match) is a normal answer. By default " +
            "this also returns objects still forming in the local map that the map has not validated (often small " +
            "or movable items it may never keep); those carry metadata.state=provisional (plus is_low_mobility, " +
            "status, observed_num) and are low-trust — their position and label can churn. Set validated_only=true " +
            "to restrict to validated (promoted) objects.",
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
        @McpToolParam(
            description = "Restrict to validated (promoted) objects only. Omit to also include low-trust provisional local-map objects (metadata.state=provisional).",
            required = false,
        )
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
        description = "Return a compact, capped inventory snapshot of objects currently in the semantic map, " +
            "each with its map-frame coordinates (x, y). To navigate to one, pass its (x, y) to nav.plan_approach, " +
            "then nav.move_to. By default this includes low-trust provisional local-map objects (tagged " +
            "metadata.state=provisional), which may never be kept. Set validated_only=true for validated objects only.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
        generateOutputSchema = true,
        metaProvider = SemanticCategory::class,
    )
    fun listObjects(
        @McpToolParam(description = "Max objects to return. Omit for the server default cap.", required = false)
        limit: Int?,
        @McpToolParam(
            description = "Restrict to validated (promoted) objects only. Omit to also list low-trust provisional local-map objects (metadata.state=provisional).",
            required = false,
        )
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
        description = "List mapped objects around a map-frame point (pairs with nav.get_pose), nearest first. " +
            "To navigate to one, pass its (x, y) to nav.plan_approach for a reachable standoff pose, then nav.move_to.",
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
