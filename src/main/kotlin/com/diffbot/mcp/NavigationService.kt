package com.diffbot.mcp

import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

@Service
class NavigationService(
    private val ros: RosToolCaller,
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

    fun planApproach(x: Double, y: Double, standoff: Double?): Map<String, Any?> {
        if (!x.isFinite() || !y.isFinite()) {
            return GatewayResult.error("unsafe_request", "Object position must use finite numeric values.")
        }
        val radius = standoff ?: properties.nav.approachStandoffM
        if (!radius.isFinite() || radius <= 0.0) {
            return GatewayResult.error("unsafe_request", "Standoff must be a finite, positive distance.")
        }

        // Ring of candidate standoff poses around the object, ordered so the side
        // facing the robot is tried first. Nav2's planner is the authority on
        // whether each is collision-free and reachable.
        val bearings = candidateBearings(x, y, properties.nav.approachCandidates.coerceAtLeast(1))
        var tried = 0
        var transportFailures = 0
        for (bearing in bearings) {
            tried++
            val candidateX = x + radius * cos(bearing)
            val candidateY = y + radius * sin(bearing)
            val yaw = state.normalizeRadians(atan2(y - candidateY, x - candidateX))
            val result = computePath(candidateX, candidateY, yaw)

            if (isValidPath(result)) {
                return GatewayResult.ok(
                    mapOf(
                        "reachable" to true,
                        "pose" to mapOf("x" to candidateX, "y" to candidateY, "yaw" to yaw),
                        "standoff_m" to radius,
                        "planner" to properties.nav.plannerId,
                        "candidates_tried" to tried,
                    ),
                )
            }
            if (result["error_class"] == "backend_unavailable") {
                return GatewayResult.error(
                    "planner_unavailable",
                    "ros-mcp is not connected; cannot reach Nav2 to validate an approach pose.",
                    mapOf("ros" to result),
                )
            }
            if (isTransportError(result)) {
                transportFailures++
            }
        }

        if (transportFailures == tried) {
            return GatewayResult.error(
                "planner_unavailable",
                "Nav2 ComputePathToPose did not respond; cannot validate an approach pose.",
            )
        }
        return GatewayResult.ok(
            mapOf(
                "reachable" to false,
                "standoff_m" to radius,
                "candidates_tried" to tried,
                "detail" to "No collision-free, reachable approach pose found around the object.",
            ),
        )
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
        cancelRememberedGoal(properties.actions.navigateToPose, lastNavigateGoalId)?.let { cancellations += it.result }
        cancelRememberedGoal(properties.actions.spin, lastSpinGoalId)?.let { cancellations += it.result }
        cancelRememberedGoal(properties.actions.driveOnHeading, lastDriveOnHeadingGoalId)?.let { cancellations += it.result }

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

    fun cancelNavigateGoal(): Map<String, Any?> {
        val cancellation = cancelRememberedGoal(properties.actions.navigateToPose, lastNavigateGoalId)
            ?: return GatewayResult.ok(
                mapOf(
                    "cancelled" to false,
                    "action" to properties.actions.navigateToPose,
                    "reason" to "no_remembered_goal",
                ),
            )

        val data = mapOf(
            "cancelled" to true,
            "action" to cancellation.actionName,
            "goal_id" to cancellation.goalId,
            "ros" to cancellation.result,
        )
        return if (isToolError(cancellation.result)) {
            GatewayResult.error(
                "navigation_cancel_failed",
                "Nav2 NavigateToPose goal cancellation failed.",
                data,
            )
        } else {
            GatewayResult.ok(data)
        }
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

    private fun cancelRememberedGoal(
        actionName: String,
        target: AtomicReference<String?>,
    ): RememberedGoalCancellation? {
        val goalId = target.getAndSet(null) ?: return null
        val result = ros.call("cancel_action_goal", mapOf("action_name" to actionName, "goal_id" to goalId))
        return RememberedGoalCancellation(actionName, goalId, result)
    }

    private fun isToolError(result: Map<String, Any?>): Boolean =
        result["ok"] == false || result["error"] != null || result["is_error"] == true || result["success"] == false

    // Bearings (object -> candidate) evenly spaced around the object, starting at the
    // object -> robot direction (when known) and spiralling outward so the near side wins.
    private fun candidateBearings(objX: Double, objY: Double, count: Int): List<Double> {
        val step = 2.0 * PI / count
        val base = robotBearing(objX, objY) ?: 0.0
        val bearings = mutableListOf(base)
        var k = 1
        while (bearings.size < count) {
            bearings += state.normalizeRadians(base + k * step)
            if (bearings.size < count) {
                bearings += state.normalizeRadians(base - k * step)
            }
            k++
        }
        return bearings
    }

    private fun robotBearing(objX: Double, objY: Double): Double? {
        val pose = state.pose(timeoutSeconds = 1.0)
        if (pose["ok"] != true || pose["frame_id"] != "map") return null
        val position = pose["position"] as? Map<*, *> ?: return null
        val robotX = (position["x"] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
        val robotY = (position["y"] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
        return atan2(robotY - objY, robotX - objX)
    }

    private fun computePath(x: Double, y: Double, yaw: Double): Map<String, Any?> {
        val goal = mapOf(
            "goal" to mapOf(
                "header" to mapOf("frame_id" to "map"),
                "pose" to mapOf(
                    "position" to mapOf("x" to x, "y" to y, "z" to 0.0),
                    "orientation" to state.quaternionFromYaw(yaw),
                ),
            ),
            "planner_id" to properties.nav.plannerId,
            // Plan from the robot's real TF pose, not a caller-supplied start.
            "use_start" to false,
        )
        return ros.call(
            "send_action_goal",
            mapOf(
                "action_name" to properties.actions.computePathToPose,
                "action_type" to "nav2_msgs/action/ComputePathToPose",
                "goal" to goal,
                "timeout" to properties.nav.planTimeoutSeconds,
            ),
        )
    }

    private fun isValidPath(result: Map<String, Any?>): Boolean =
        !isToolError(result) && findPoses(result).isNotEmpty()

    // ComputePathToPose returns nav_msgs/Path; find its (possibly nested) `poses` list.
    private fun findPoses(value: Any?): List<*> {
        when (value) {
            is Map<*, *> -> {
                (value["poses"] as? List<*>)?.let { if (it.isNotEmpty()) return it }
                for (nested in value.values) {
                    val found = findPoses(nested)
                    if (found.isNotEmpty()) return found
                }
            }
            is List<*> -> for (nested in value) {
                val found = findPoses(nested)
                if (found.isNotEmpty()) return found
            }
        }
        return emptyList<Any?>()
    }

    private fun isTransportError(result: Map<String, Any?>): Boolean =
        result["error_class"] in TRANSPORT_ERROR_CLASSES

    private data class RememberedGoalCancellation(
        val actionName: String,
        val goalId: String,
        val result: Map<String, Any?>,
    )

    private companion object {
        // ros.call error classes that mean "ROS/planner unreachable", not "this goal failed".
        val TRANSPORT_ERROR_CLASSES = setOf("backend_unavailable", "ros_graph_unavailable", "timeout")
    }
}
