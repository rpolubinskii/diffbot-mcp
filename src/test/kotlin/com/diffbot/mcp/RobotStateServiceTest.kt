package com.diffbot.mcp

import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RobotStateServiceTest {
    private val properties = DiffbotProperties()
    private val ros = FakeRosToolCaller()
    private val state = RobotStateService(ros, properties)

    @Test
    fun `compactStatus returns compact pose imu and velocity payload`() {
        ros.subscribeResponses[properties.topics.localizationPose] = mapOf(
            "msg" to mapOf(
                "header" to mapOf("frame_id" to "map", "stamp" to mapOf("sec" to 1)),
                "pose" to mapOf(
                    "pose" to mapOf(
                        "position" to mapOf("x" to 1.0, "y" to 2.0, "z" to 0.0),
                        "orientation" to state.quaternionFromYaw(0.5),
                    ),
                ),
            ),
        )
        ros.subscribeResponses[properties.topics.imu] = mapOf(
            "msg" to mapOf(
                "header" to mapOf("frame_id" to "imu", "stamp" to mapOf("sec" to 2)),
                "orientation" to mapOf("x" to 0.0, "y" to 0.0, "z" to 0.0, "w" to 1.0),
                "angular_velocity" to mapOf("z" to 0.1),
                "linear_acceleration" to mapOf("x" to 0.2),
            ),
        )
        ros.subscribeResponses[properties.topics.odom] = mapOf(
            "msg" to mapOf(
                "header" to mapOf("frame_id" to "odom", "stamp" to mapOf("sec" to 3)),
                "child_frame_id" to "base_link",
                "twist" to mapOf(
                    "twist" to mapOf(
                        "linear" to mapOf("x" to 0.3),
                        "angular" to mapOf("z" to 0.4),
                    ),
                ),
            ),
        )

        val result = state.compactStatus()

        assertEquals(true, result["ok"])
        assertTrue(result["pose"] is Map<*, *>)
        assertTrue(result["imu"] is Map<*, *>)
        assertTrue(result["current_velocity"] is Map<*, *>)
        assertEquals(mapOf("stop_tool" to "nav.stop", "raw_cmd_vel_exposed" to false), result["motor_safety"])
    }

    @Test
    fun `rosSummary returns summarized graph without raw lists`() {
        ros.responses["get_topics"] = mapOf(
            "topic_count" to 2,
            "topics" to listOf(properties.topics.cameraImage, properties.topics.imu),
        )
        ros.responses["get_services"] = mapOf(
            "service_count" to 1,
            "services" to listOf(properties.services.rosActionServers),
        )
        ros.responses["call_service"] = mapOf(
            "result" to mapOf("action_servers" to listOf(properties.actions.navigateToPose)),
        )

        val result = state.rosSummary(includeRawLists = false)

        assertEquals(true, result["ok"])
        assertEquals(2, result["topic_count"])
        assertEquals(1, result["service_count"])
        assertEquals(1, result["action_count"])
        assertFalse(result.containsKey("topics"))
        assertFalse(result.containsKey("services"))
        assertFalse(result.containsKey("actions"))
    }

    private class FakeRosToolCaller : RosToolCaller {
        val responses = mutableMapOf<String, Map<String, Any?>>()
        val subscribeResponses = mutableMapOf<String, Map<String, Any?>>()

        override fun isConfigured(): Boolean = true

        override fun clientSummary(): Map<String, Any?> = mapOf("configured" to true)

        override fun call(tool: String, arguments: Map<String, Any?>): Map<String, Any?> {
            if (tool == "subscribe_once") {
                val topic = arguments["topic"]?.toString()
                return subscribeResponses[topic] ?: GatewayResult.error("timeout", "No fake response.")
            }
            return responses[tool] ?: GatewayResult.ok()
        }

        override fun callToolResult(tool: String, arguments: Map<String, Any?>): McpSchema.CallToolResult {
            error("callToolResult is not used by these tests")
        }

        override fun callRaw(tool: String, arguments: Map<String, Any?>): Map<String, Any?> = call(tool, arguments)
    }
}
