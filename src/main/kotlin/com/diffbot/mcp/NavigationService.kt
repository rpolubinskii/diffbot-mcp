package com.diffbot.mcp

import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.floor

@Service
class NavigationService(
    private val ros: RosMcpGateway,
    private val state: RobotStateService,
    private val properties: DiffbotProperties,
) {
    private val lastNavigateGoalId = AtomicReference<String?>()
    private val lastSpinGoalId = AtomicReference<String?>()
    private val lastDriveOnHeadingGoalId = AtomicReference<String?>()

    fun moveTo(x: Double, y: Double, yawRadians: Double, timeoutSeconds: Double?): Map<String, Any?> {
        if (!x.isFinite() || !y.isFinite() || !yawRadians.isFinite()) {
            return GatewayResult.error("unsafe_request", "Target pose must use finite numeric values.")
        }

        val goal = mapOf(
            "pose" to mapOf(
                "header" to mapOf("frame_id" to "map"),
                "pose" to mapOf(
                    "position" to mapOf("x" to x, "y" to y, "z" to 0.0),
                    "orientation" to state.quaternionFromYaw(state.normalizeRadians(yawRadians)),
                ),
            ),
            "behavior_tree" to "",
        )

        val result = ros.call(
            "send_action_goal",
            mapOf(
                "action_name" to properties.actions.navigateToPose,
                "action_type" to "nav2_msgs/action/NavigateToPose",
                "goal" to goal,
                "timeout" to (timeoutSeconds ?: 60.0),
            ),
        )

        rememberGoalId(result, lastNavigateGoalId)
        return if (isToolError(result)) {
            stop()
            GatewayResult.error("navigation_rejected", "Nav2 NavigateToPose goal failed or was rejected.", mapOf("ros" to result))
        } else {
            GatewayResult.ok(mapOf("action" to properties.actions.navigateToPose, "goal" to goal, "ros" to result))
        }
    }

    fun turn(radians: Double, timeoutSeconds: Double?): Map<String, Any?> {
        if (!radians.isFinite()) {
            return GatewayResult.error("unsafe_request", "Turn angle must be finite.")
        }
        val timeout = timeoutSeconds ?: 15.0
        val goal = mapOf(
            "target_yaw" to radians,
            "time_allowance" to mapOf("sec" to timeout.toInt(), "nanosec" to 0),
        )

        val result = ros.call(
            "send_action_goal",
            mapOf(
                "action_name" to properties.actions.spin,
                "action_type" to "nav2_msgs/action/Spin",
                "goal" to goal,
                "timeout" to timeout,
            ),
        )

        rememberGoalId(result, lastSpinGoalId)
        return if (isToolError(result)) {
            stop()
            GatewayResult.error("navigation_rejected", "Nav2 Spin goal failed or was rejected.", mapOf("ros" to result))
        } else {
            GatewayResult.ok(mapOf("action" to properties.actions.spin, "goal" to goal, "ros" to result))
        }
    }

    fun driveOnHeading(
        distanceMeters: Double,
        speedMetersPerSecond: Double?,
        timeoutSeconds: Double?,
    ): Map<String, Any?> {
        if (!distanceMeters.isFinite() || distanceMeters == 0.0) {
            return GatewayResult.error("unsafe_request", "Distance must be a finite, non-zero value.")
        }

        val speedMagnitude = speedMetersPerSecond ?: 0.15
        if (!speedMagnitude.isFinite() || speedMagnitude <= 0.0) {
            return GatewayResult.error("unsafe_request", "Speed must be a finite, positive value.")
        }

        val timeout = timeoutSeconds ?: 30.0
        if (!timeout.isFinite() || timeout <= 0.0 || timeout > Int.MAX_VALUE.toDouble()) {
            return GatewayResult.error(
                "unsafe_request",
                "Timeout must be a finite, positive value representable as a ROS duration.",
            )
        }

        val signedSpeed = if (distanceMeters < 0.0) -abs(speedMagnitude) else abs(speedMagnitude)
        val goal = mapOf(
            "target" to mapOf("x" to distanceMeters, "y" to 0.0, "z" to 0.0),
            "speed" to signedSpeed,
            "time_allowance" to duration(timeout),
        )

        val result = ros.call(
            "send_action_goal",
            mapOf(
                "action_name" to properties.actions.driveOnHeading,
                "action_type" to "nav2_msgs/action/DriveOnHeading",
                "goal" to goal,
                "timeout" to timeout,
            ),
        )

        rememberGoalId(result, lastDriveOnHeadingGoalId)
        return if (isToolError(result)) {
            stop()
            GatewayResult.error(
                "navigation_rejected",
                "Nav2 DriveOnHeading goal failed or was rejected.",
                mapOf("ros" to result),
            )
        } else {
            GatewayResult.ok(mapOf("action" to properties.actions.driveOnHeading, "goal" to goal, "ros" to result))
        }
    }

    fun stop(): Map<String, Any?> {
        val cancellations = mutableListOf<Map<String, Any?>>()
        lastNavigateGoalId.getAndSet(null)?.let {
            cancellations += ros.call("cancel_action_goal", mapOf("action_name" to properties.actions.navigateToPose, "goal_id" to it))
        }
        lastSpinGoalId.getAndSet(null)?.let {
            cancellations += ros.call("cancel_action_goal", mapOf("action_name" to properties.actions.spin, "goal_id" to it))
        }
        lastDriveOnHeadingGoalId.getAndSet(null)?.let {
            cancellations += ros.call(
                "cancel_action_goal",
                mapOf("action_name" to properties.actions.driveOnHeading, "goal_id" to it),
            )
        }

        val zeroTwist = mapOf(
            "linear" to mapOf("x" to 0.0, "y" to 0.0, "z" to 0.0),
            "angular" to mapOf("x" to 0.0, "y" to 0.0, "z" to 0.0),
        )

        val stopCommands = listOf(
            ros.call(
                "publish_once",
                mapOf("topic" to properties.topics.cmdVel, "msg_type" to "geometry_msgs/msg/Twist", "msg" to zeroTwist)
            ),
            ros.call(
                "publish_once",
                mapOf("topic" to properties.topics.baseCmdVel, "msg_type" to "geometry_msgs/msg/Twist", "msg" to zeroTwist)
            ),
        )

        return GatewayResult.ok(
            mapOf(
                "cancelled_goals" to cancellations,
                "stop_commands" to stopCommands,
                "raw_cmd_vel_exposed" to false,
            ),
        )
    }

    private fun duration(seconds: Double): Map<String, Int> {
        val wholeSeconds = floor(seconds).toInt()
        val nanoseconds = ((seconds - wholeSeconds) * 1_000_000_000).toInt()
        return mapOf("sec" to wholeSeconds, "nanosec" to nanoseconds)
    }

    private fun rememberGoalId(result: Map<String, Any?>, target: AtomicReference<String?>) {
        val goalId = result["goal_id"]?.toString()
            ?: (result["result"] as? Map<*, *>)?.get("goal_id")?.toString()
            ?: (result["ros"] as? Map<*, *>)?.get("goal_id")?.toString()
        if (!goalId.isNullOrBlank()) {
            target.set(goalId)
        }
    }

    private fun isToolError(result: Map<String, Any?>): Boolean =
        result["ok"] == false || result["error"] != null || result["is_error"] == true || result["success"] == false
}
