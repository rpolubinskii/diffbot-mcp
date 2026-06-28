package com.diffbot.mcp

import com.diffbot.semantic.v1.DescribeNearRequest
import com.diffbot.semantic.v1.FindRequest
import com.diffbot.semantic.v1.GetStatusRequest
import com.diffbot.semantic.v1.ListObjectsRequest
import com.diffbot.semantic.v1.Match
import com.diffbot.semantic.v1.Pose2D
import com.diffbot.semantic.v1.SemanticMapGrpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.hypot

@Service
class SemanticClientService(
    private val properties: DiffbotProperties,
    private val state: RobotStateService,
    private val ros: RosToolCaller,
) : DisposableBean {
    @Volatile
    private var channel: ManagedChannel? = null

    fun find(query: String, topK: Int?, minConfidence: Double?): Map<String, Any?> {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            return GatewayResult.error("invalid_request", "query must not be blank")
        }
        val request = FindRequest.newBuilder()
            .setQuery(normalized)
            .setTopK(topK ?: 0)
            .setMinConfidence((minConfidence ?: 0.0).toFloat())
            .build()
        return guarded { stub ->
            val response = stub.find(request)
            val currentPose = currentMapPoseIfNeeded(response.matchesCount)
            GatewayResult.ok(
                mapOf(
                    "matches" to response.matchesList.map { matchToMap(it, currentPose) },
                    "count" to response.matchesCount,
                ),
            )
        }
    }

    fun listObjects(limit: Int?): Map<String, Any?> {
        val request = ListObjectsRequest.newBuilder().setLimit(limit ?: 0).build()
        return guarded { stub ->
            val response = stub.listObjects(request)
            val currentPose = currentMapPoseIfNeeded(response.objectsCount)
            GatewayResult.ok(
                mapOf(
                    "objects" to response.objectsList.map { matchToMap(it, currentPose) },
                    "total_tracked" to response.totalTracked,
                ),
            )
        }
    }

    fun describeNear(x: Double, y: Double, radius: Double?, limit: Int?): Map<String, Any?> {
        val request = DescribeNearRequest.newBuilder()
            .setPoint(Pose2D.newBuilder().setX(x.toFloat()).setY(y.toFloat()))
            .setRadius((radius ?: 0.0).toFloat())
            .setLimit(limit ?: 0)
            .build()
        return guarded { stub ->
            val response = stub.describeNear(request)
            val currentPose = currentMapPoseIfNeeded(response.objectsCount)
            GatewayResult.ok(mapOf("objects" to response.objectsList.map { matchToMap(it, currentPose) }))
        }
    }

    fun status(): Map<String, Any?> = guarded { stub ->
        val status = stub.getStatus(GetStatusRequest.getDefaultInstance())
        GatewayResult.ok(
            mapOf(
                "ready" to status.ready,
                "backend" to status.backend,
                "map_frame" to status.mapFrame,
                "frames_processed" to status.framesProcessed,
                "objects_tracked" to status.objectsTracked,
                "last_update_age_s" to status.lastUpdateAgeS,
                "pose_feed_live" to status.poseFeedLive,
                "detail" to status.detail,
            ),
        )
    }

    private fun guarded(block: (SemanticMapGrpc.SemanticMapBlockingStub) -> Map<String, Any?>): Map<String, Any?> =
        try {
            block(stub())
        } catch (ex: StatusRuntimeException) {
            GatewayResult.error(
                "semantic_unavailable",
                "diffbot-semantic RPC failed: ${ex.status.description ?: ex.status.code.name}",
            )
        } catch (ex: RuntimeException) {
            GatewayResult.error("semantic_unavailable", "diffbot-semantic RPC failed: ${ex.message ?: ex::class.simpleName}")
        }

    private fun stub(): SemanticMapGrpc.SemanticMapBlockingStub =
        SemanticMapGrpc.newBlockingStub(channel())
            .withDeadlineAfter((properties.semantic.deadlineSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)

    private fun channel(): ManagedChannel {
        val existing = channel
        if (existing != null && !existing.isShutdown && !existing.isTerminated) {
            return existing
        }

        return synchronized(this) {
            val current = channel
            if (current != null && !current.isShutdown && !current.isTerminated) {
                current
            } else {
                ManagedChannelBuilder
                    .forAddress(properties.semantic.host, properties.semantic.port)
                    .usePlaintext()
                    .build()
                    .also { channel = it }
            }
        }
    }

    override fun destroy() {
        channel?.shutdownNow()?.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun currentMapPoseIfNeeded(matchCount: Int): SemanticRobotPose? =
        if (matchCount > 0) currentMapPose() else null

    private fun currentMapPose(): SemanticRobotPose? =
        currentSemanticPose() ?: currentNavPose()

    private fun currentSemanticPose(): SemanticRobotPose? {
        val topic = properties.semantic.currentPoseTopic.trim()
        if (topic.isEmpty()) {
            return null
        }
        val result = ros.call(
            "subscribe_once",
            mapOf(
                "topic" to topic,
                "msg_type" to "nav_msgs/msg/Odometry",
                "timeout" to properties.semantic.currentPoseTimeoutSeconds,
                "expects_image" to "false",
            ),
        )
        val msg = result["msg"] as? Map<*, *> ?: return null
        return semanticRobotPoseFromOdometry(msg, topic)
    }

    private fun currentNavPose(): SemanticRobotPose? {
        val pose = state.pose(timeoutSeconds = 1.0)
        if (pose["ok"] != true || pose["frame_id"] != "map") {
            return null
        }
        val position = pose["position"] as? Map<*, *> ?: return null
        val x = (position["x"] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
        val y = (position["y"] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
        return SemanticRobotPose(x, y, pose["source"]?.toString())
    }
}

private const val SEMANTIC_GOAL_STANDOFF_M = 0.8
private const val MIN_APPROACH_VECTOR_M = 0.05

internal data class SemanticRobotPose(val x: Double, val y: Double, val source: String?)

internal fun semanticRobotPoseFromOdometry(msg: Map<*, *>, topic: String): SemanticRobotPose? {
    if (msg.path("header", "frame_id") != "map") {
        return null
    }
    val pose = msg["pose"] as? Map<*, *> ?: return null
    val nestedPose = pose["pose"] as? Map<*, *> ?: pose
    val position = nestedPose["position"] as? Map<*, *> ?: return null
    val x = (position["x"] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
    val y = (position["y"] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
    return SemanticRobotPose(x, y, "semantic_current_pose:$topic")
}

internal fun matchToMap(match: Match, currentPose: SemanticRobotPose? = null): Map<String, Any?> =
    linkedMapOf(
        "label" to match.label,
        "confidence" to match.confidence,
        "x" to match.position.x,
        "y" to match.position.y,
        "yaw" to match.position.yaw,
        "suggested_goal" to suggestedGoal(match, currentPose),
        "last_seen" to if (match.hasLastSeen()) {
            Instant.ofEpochSecond(match.lastSeen.seconds, match.lastSeen.nanos.toLong()).toString()
        } else {
            null
        },
        "backend" to match.backend,
        "metadata" to match.metadataMap,
    )

private fun suggestedGoal(match: Match, currentPose: SemanticRobotPose?): Map<String, Any?>? {
    currentPose ?: return null
    val objectX = match.position.x.toDouble()
    val objectY = match.position.y.toDouble()
    val dx = currentPose.x - objectX
    val dy = currentPose.y - objectY
    val distance = hypot(dx, dy)
    if (!distance.isFinite() || distance < MIN_APPROACH_VECTOR_M) {
        return null
    }

    val scale = SEMANTIC_GOAL_STANDOFF_M / distance
    val goalX = objectX + dx * scale
    val goalY = objectY + dy * scale
    return linkedMapOf(
        "x" to goalX,
        "y" to goalY,
        "yaw" to atan2(objectY - goalY, objectX - goalX),
        "standoff_m" to SEMANTIC_GOAL_STANDOFF_M,
        "source" to "current_pose_to_object",
        "pose_source" to currentPose.source,
    )
}

private fun Map<*, *>.path(vararg keys: String): Any? {
    var current: Any? = this
    for (key in keys) {
        current = (current as? Map<*, *>)?.get(key) ?: return null
    }
    return current
}
