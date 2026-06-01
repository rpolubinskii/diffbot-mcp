package com.diffbot.mcp

import java.time.Instant

object GatewayResult {
    fun ok(data: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        linkedMapOf(
            "ok" to true,
            "timestamp" to Instant.now().toString(),
        ) + data

    fun error(errorClass: String, message: String, details: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        linkedMapOf(
            "ok" to false,
            "error_class" to errorClass,
            "message" to message,
            "timestamp" to Instant.now().toString(),
        ) + details

    fun backendUnavailable(backend: String): Map<String, Any?> =
        error(
            "backend_unavailable",
            "$backend backend is not configured for diffbot-mcp v1.",
            mapOf("backend" to backend),
        )
}
