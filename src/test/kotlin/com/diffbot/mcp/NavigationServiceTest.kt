package com.diffbot.mcp

import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationServiceTest {
    private val properties = DiffbotProperties()
    private val ros = FakeRosToolCaller()
    private val state = RobotStateService(ros, properties)
    private val navigation = NavigationService(ros, state, properties)

    @Test
    fun `cancelNavigateGoal cancels remembered NavigateToPose goal`() {
        ros.enqueue("send_action_goal", mapOf("ok" to true, "goal_id" to "goal-1"))
        ros.enqueue("cancel_action_goal", mapOf("ok" to true, "cancelled" to true))

        navigation.moveTo(1.0, 2.0, 0.25, null)
        val result = navigation.cancelNavigateGoal()

        assertEquals(true, result["ok"])
        assertEquals(true, result["cancelled"])
        assertEquals(properties.actions.navigateToPose, result["action"])
        assertEquals("goal-1", result["goal_id"])

        val cancelCall = ros.calls.single { it.tool == "cancel_action_goal" }
        assertEquals(properties.actions.navigateToPose, cancelCall.arguments["action_name"])
        assertEquals("goal-1", cancelCall.arguments["goal_id"])
    }

    @Test
    fun `cancelNavigateGoal reports no remembered goal on repeated calls`() {
        ros.enqueue("send_action_goal", mapOf("ok" to true, "goal_id" to "goal-1"))
        ros.enqueue("cancel_action_goal", mapOf("ok" to true, "cancelled" to true))

        navigation.moveTo(1.0, 2.0, 0.25, null)
        navigation.cancelNavigateGoal()
        val result = navigation.cancelNavigateGoal()

        assertEquals(true, result["ok"])
        assertEquals(false, result["cancelled"])
        assertEquals("no_remembered_goal", result["reason"])
        assertEquals(1, ros.calls.count { it.tool == "cancel_action_goal" })
    }

    @Test
    fun `cancelNavigateGoal surfaces ros cancellation errors`() {
        ros.enqueue("send_action_goal", mapOf("ok" to true, "goal_id" to "goal-2"))
        ros.enqueue(
            "cancel_action_goal",
            GatewayResult.error("ros_graph_unavailable", "ros-mcp unavailable"),
        )

        navigation.moveTo(1.0, 2.0, 0.25, null)
        val result = navigation.cancelNavigateGoal()

        assertEquals(false, result["ok"])
        assertEquals("navigation_cancel_failed", result["error_class"])
        assertEquals(true, result["cancelled"])
        assertEquals("goal-2", result["goal_id"])
        assertNotNull(result["ros"])
    }

    @Test
    fun `stop still cancels remembered goal and publishes zero velocity`() {
        ros.enqueue("send_action_goal", mapOf("ok" to true, "goal_id" to "goal-3"))
        ros.enqueue("cancel_action_goal", mapOf("ok" to true, "cancelled" to true))
        ros.enqueue("publish_once", mapOf("ok" to true, "topic" to properties.topics.cmdVel))
        ros.enqueue("publish_once", mapOf("ok" to true, "topic" to properties.topics.baseCmdVel))

        navigation.moveTo(1.0, 2.0, 0.25, null)
        val result = navigation.stop()

        assertEquals(true, result["ok"])
        assertEquals(1, ros.calls.count { it.tool == "cancel_action_goal" })
        assertEquals(2, ros.calls.count { it.tool == "publish_once" })

        val publishTopics = ros.calls
            .filter { it.tool == "publish_once" }
            .map { it.arguments["topic"] }
            .toSet()
        assertTrue(properties.topics.cmdVel in publishTopics)
        assertTrue(properties.topics.baseCmdVel in publishTopics)
    }

    private data class ToolCall(val tool: String, val arguments: Map<String, Any?>)

    private class FakeRosToolCaller : RosToolCaller {
        val calls = mutableListOf<ToolCall>()
        private val responses = mutableMapOf<String, ArrayDeque<Map<String, Any?>>>()

        fun enqueue(tool: String, response: Map<String, Any?>) {
            responses.getOrPut(tool) { ArrayDeque() }.add(response)
        }

        override fun isConfigured(): Boolean = true

        override fun clientSummary(): Map<String, Any?> = mapOf("configured" to true)

        override fun call(tool: String, arguments: Map<String, Any?>): Map<String, Any?> {
            calls += ToolCall(tool, arguments)
            return responses[tool]?.removeFirstOrNull() ?: GatewayResult.ok()
        }

        override fun callToolResult(tool: String, arguments: Map<String, Any?>): McpSchema.CallToolResult {
            error("callToolResult is not used by these tests")
        }

        override fun callRaw(tool: String, arguments: Map<String, Any?>): Map<String, Any?> {
            calls += ToolCall(tool, arguments)
            return responses[tool]?.removeFirstOrNull() ?: GatewayResult.ok()
        }
    }
}
