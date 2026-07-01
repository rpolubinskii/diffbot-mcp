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

@Service
class SemanticClientService(
    private val properties: DiffbotProperties,
) : DisposableBean {
    @Volatile
    private var channel: ManagedChannel? = null

    fun find(query: String, topK: Int?, minConfidence: Double?, validatedOnly: Boolean?): Map<String, Any?> {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            return GatewayResult.error("invalid_request", "query must not be blank")
        }
        val request = FindRequest.newBuilder()
            .setQuery(normalized)
            .setTopK(topK ?: 0)
            .setMinConfidence((minConfidence ?: 0.0).toFloat())
            .setValidatedOnly(validatedOnly ?: false)
            .build()
        return guarded { stub ->
            val response = stub.find(request)
            GatewayResult.ok(
                mapOf(
                    "matches" to response.matchesList.map { matchToMap(it) },
                    "count" to response.matchesCount,
                ),
            )
        }
    }

    fun listObjects(limit: Int?, validatedOnly: Boolean?): Map<String, Any?> {
        val request = ListObjectsRequest.newBuilder()
            .setLimit(limit ?: 0)
            .setValidatedOnly(validatedOnly ?: false)
            .build()
        return guarded { stub ->
            val response = stub.listObjects(request)
            GatewayResult.ok(
                mapOf(
                    "objects" to response.objectsList.map { matchToMap(it) },
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
            GatewayResult.ok(mapOf("objects" to response.objectsList.map { matchToMap(it) }))
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
}

internal fun matchToMap(match: Match): Map<String, Any?> =
    linkedMapOf(
        "label" to match.label,
        "confidence" to match.confidence,
        "x" to match.position.x,
        "y" to match.position.y,
        "yaw" to match.position.yaw,
        "last_seen" to if (match.hasLastSeen()) {
            Instant.ofEpochSecond(match.lastSeen.seconds, match.lastSeen.nanos.toLong()).toString()
        } else {
            null
        },
        "backend" to match.backend,
        "metadata" to match.metadataMap,
    )
