package com.diffbot.mcp

import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Service
class RobotStateService(
	private val ros: RosMcpGateway,
	private val properties: DiffbotProperties,
) {
	fun cameraImage(timeoutSeconds: Double? = null): Map<String, Any?> {
		val result = ros.call(
			"subscribe_once",
			mapOf(
				"topic" to properties.topics.cameraImage,
				"msg_type" to "sensor_msgs/msg/Image",
				"timeout" to (timeoutSeconds ?: properties.cameraTimeoutSeconds),
				"queue_length" to 5,
				"throttle_rate_ms" to 500,
				"expects_image" to "true",
			),
		)
		return if (result["error"] != null) {
			GatewayResult.error("timeout", "Timed out waiting for camera image.", mapOf("ros" to result))
		} else {
			GatewayResult.ok(
				mapOf(
					"topic" to properties.topics.cameraImage,
					"image" to result["msg"],
					"ros_message" to result["message"],
					"source" to "ros-mcp.subscribe_once",
				),
			)
		}
	}

	fun imu(timeoutSeconds: Double? = null): Map<String, Any?> {
		val result = ros.call(
			"subscribe_once",
			mapOf(
				"topic" to properties.topics.imu,
				"msg_type" to "sensor_msgs/msg/Imu",
				"timeout" to (timeoutSeconds ?: properties.rosTimeoutSeconds),
				"expects_image" to "false",
			),
		)
		val msg = result["msg"] as? Map<*, *>
			?: return GatewayResult.error("timeout", "Timed out waiting for IMU data.", mapOf("ros" to result))

		return GatewayResult.ok(
			mapOf(
				"topic" to properties.topics.imu,
				"frame_id" to msg.path("header", "frame_id"),
				"stamp" to msg.path("header", "stamp"),
				"orientation" to msg["orientation"],
				"angular_velocity" to msg["angular_velocity"],
				"linear_acceleration" to msg["linear_acceleration"],
				"source" to "ros-mcp.subscribe_once",
			),
		)
	}

	fun pose(timeoutSeconds: Double? = null): Map<String, Any?> {
		val candidates = listOf(
			PoseCandidate(properties.topics.localizationPose, "geometry_msgs/msg/PoseWithCovarianceStamped", "map", "rtabmap_localization_pose"),
			PoseCandidate(properties.topics.odom, "nav_msgs/msg/Odometry", "odom", "ekf_odom"),
			PoseCandidate(properties.topics.baseOdom, "nav_msgs/msg/Odometry", "odom", "base_controller_odom"),
		)

		val failures = mutableListOf<Map<String, Any?>>()
		for (candidate in candidates) {
			val result = ros.call(
				"subscribe_once",
				mapOf(
					"topic" to candidate.topic,
					"msg_type" to candidate.messageType,
					"timeout" to (timeoutSeconds ?: properties.rosTimeoutSeconds),
					"expects_image" to "false",
				),
			)
			val msg = result["msg"] as? Map<*, *>
			if (msg == null) {
				failures += mapOf("topic" to candidate.topic, "result" to result)
				continue
			}

			val pose = extractPose(msg)
				?: run {
					failures += mapOf("topic" to candidate.topic, "result" to result)
					continue
				}

			return GatewayResult.ok(
				mapOf(
					"frame_id" to (msg.path("header", "frame_id") ?: candidate.frame),
					"topic" to candidate.topic,
					"source" to candidate.source,
					"position" to pose["position"],
					"orientation" to pose["orientation"],
					"yaw_radians" to yawFromQuaternion(pose["orientation"] as? Map<*, *>),
					"stamp" to msg.path("header", "stamp"),
				),
			)
		}

		return GatewayResult.error(
			"localization_unavailable",
			"No pose source published within the timeout.",
			mapOf("attempts" to failures),
		)
	}

	fun compactStatus(): Map<String, Any?> {
		val pose = pose(timeoutSeconds = 1.0)
		val imu = imu(timeoutSeconds = 1.0)
		val velocity = currentVelocity(timeoutSeconds = 1.0)

		return GatewayResult.ok(
			mapOf(
				"pose" to pose.compactOrError(),
				"current_velocity" to velocity.compactOrError(),
				"motor_safety" to mapOf("stop_tool" to "nav.stop", "raw_cmd_vel_exposed" to false),
				"imu" to imu.compactOrError(),
			),
		)
	}

	fun rosSummary(includeRawLists: Boolean): Map<String, Any?> {
		val topics = ros.call("get_topics")
		val services = ros.call("get_services")
		val actionServers = ros.call(
			"call_service",
			mapOf(
				"service_name" to properties.services.rosActionServers,
				"service_type" to properties.services.rosActionServersType,
				"request" to emptyMap<String, Any?>(),
				"timeout" to properties.rosTimeoutSeconds,
			),
		)

		val topicNames = stringList(topics["topics"])
		val serviceNames = stringList(services["services"])
		val actionNames = stringList((actionServers["result"] as? Map<*, *>)?.get("action_servers"))

		val summary = linkedMapOf<String, Any?>(
			"ros_mcp" to ros.clientSummary(),
			"topic_count" to (topics["topic_count"] ?: topicNames.size),
			"service_count" to (services["service_count"] ?: serviceNames.size),
			"action_count" to actionNames.size,
			"key_topics" to mapOf(
				"camera_image" to topicNames.contains(properties.topics.cameraImage),
				"imu" to topicNames.contains(properties.topics.imu),
				"odom" to topicNames.contains(properties.topics.odom),
				"base_odom" to topicNames.contains(properties.topics.baseOdom),
				"cmd_vel" to topicNames.contains(properties.topics.cmdVel),
			),
		)

		if (includeRawLists) {
			summary["topics"] = topicNames
			summary["services"] = serviceNames
			summary["actions"] = actionNames
		}

		return GatewayResult.ok(summary)
	}

	private fun currentVelocity(timeoutSeconds: Double): Map<String, Any?> {
		for (topic in listOf("/cmd_vel_smoothed", properties.topics.cmdVel)) {
			val result = ros.call(
				"subscribe_once",
				mapOf(
					"topic" to topic,
					"msg_type" to "geometry_msgs/msg/Twist",
					"timeout" to timeoutSeconds,
					"expects_image" to "false",
				),
			)
			val msg = result["msg"] as? Map<*, *> ?: continue
			return GatewayResult.ok(mapOf("topic" to topic, "linear" to msg["linear"], "angular" to msg["angular"]))
		}
		return GatewayResult.error("timeout", "No velocity sample received within the timeout.")
	}

	private fun extractPose(msg: Map<*, *>): Map<String, Any?>? {
		val pose = msg["pose"] as? Map<*, *> ?: return null
		val nestedPose = pose["pose"] as? Map<*, *> ?: pose
		return mapOf(
			"position" to nestedPose["position"],
			"orientation" to nestedPose["orientation"],
		)
	}

	private fun yawFromQuaternion(q: Map<*, *>?): Double? {
		if (q == null) return null
		val x = (q["x"] as? Number)?.toDouble() ?: return null
		val y = (q["y"] as? Number)?.toDouble() ?: return null
		val z = (q["z"] as? Number)?.toDouble() ?: return null
		val w = (q["w"] as? Number)?.toDouble() ?: return null
		return atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
	}

	fun quaternionFromYaw(yaw: Double): Map<String, Double> =
		mapOf("x" to 0.0, "y" to 0.0, "z" to sin(yaw / 2.0), "w" to cos(yaw / 2.0))

	fun distanceFromCurrentPose(x: Double, y: Double): Double? {
		val pose = pose(timeoutSeconds = 1.0)
		if (pose["ok"] != true) return null
		val position = pose["position"] as? Map<*, *> ?: return null
		val currentX = (position["x"] as? Number)?.toDouble() ?: return null
		val currentY = (position["y"] as? Number)?.toDouble() ?: return null
		return hypot(x - currentX, y - currentY)
	}

	fun normalizeRadians(value: Double): Double {
		var v = value
		while (v > PI) v -= 2.0 * PI
		while (v < -PI) v += 2.0 * PI
		return v
	}

	private fun Map<*, *>.path(vararg keys: String): Any? {
		var current: Any? = this
		for (key in keys) {
			current = (current as? Map<*, *>)?.get(key) ?: return null
		}
		return current
	}

	private fun Map<String, Any?>.compactOrError(): Map<String, Any?> =
		if (this["ok"] == true) {
			this.filterKeys { it != "ok" && it != "timestamp" }
		} else {
			mapOf("error_class" to this["error_class"], "message" to this["message"])
		}

	private fun stringList(value: Any?): List<String> =
		(value as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

	private data class PoseCandidate(
		val topic: String,
		val messageType: String,
		val frame: String,
		val source: String,
	)
}
