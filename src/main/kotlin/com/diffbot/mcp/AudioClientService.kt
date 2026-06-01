package com.diffbot.mcp

import com.diffbot.audio.v1.AudioServiceGrpc
import com.diffbot.audio.v1.SpeakRequest
import com.diffbot.audio.v1.SpeakState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class AudioClientService(
    private val properties: DiffbotProperties,
) : DisposableBean {
    @Volatile
    private var channel: ManagedChannel? = null

    fun speak(text: String): Map<String, Any?> {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return GatewayResult.error("invalid_request", "text must not be blank")
        }

        val request = SpeakRequest.newBuilder()
            .setText(normalized)
            .build()

        return try {
            val events = AudioServiceGrpc.newBlockingStub(channel())
                .withDeadlineAfter((properties.audio.deadlineSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
                .speak(request)

            while (events.hasNext()) {
                val event = events.next()
                when (event.state) {
                    SpeakState.STARTED -> Unit
                    SpeakState.FINISHED -> return GatewayResult.ok()
                    SpeakState.FAILED -> return GatewayResult.error(
                        "speech_failed",
                        event.error.ifBlank { "Audio service reported speech failure." },
                    )

                    SpeakState.SPEAK_STATE_UNSPECIFIED,
                    SpeakState.UNRECOGNIZED -> Unit
                }
            }

            GatewayResult.error("speech_failed", "Audio service ended the Speak stream without FINISHED or FAILED.")
        } catch (ex: StatusRuntimeException) {
            GatewayResult.error(
                "audio_unavailable",
                "diffbot-audio Speak RPC failed: ${ex.status.description ?: ex.status.code.name}",
            )
        } catch (ex: RuntimeException) {
            GatewayResult.error("audio_unavailable", "diffbot-audio Speak RPC failed: ${ex.message ?: ex::class.simpleName}")
        }
    }

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
                    .forAddress(properties.audio.host, properties.audio.port)
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
