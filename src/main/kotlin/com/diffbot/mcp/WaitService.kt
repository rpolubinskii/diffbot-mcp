package com.diffbot.mcp

import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class WaitService {
    fun wait(durationSeconds: Double): Map<String, Any?> {
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0 || durationSeconds > MAX_DURATION_SECONDS) {
            return GatewayResult.error(
                "unsafe_request",
                "durationSeconds must be a finite positive value no greater than $MAX_DURATION_SECONDS seconds.",
                mapOf("max_duration_seconds" to MAX_DURATION_SECONDS),
            )
        }

        val durationNanos = (durationSeconds * 1_000_000_000.0).toLong().coerceAtLeast(1L)
        val startedAt = System.nanoTime()
        sleepUntil(startedAt + durationNanos)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        return GatewayResult.ok(
            mapOf(
                "duration_seconds" to durationSeconds,
                "elapsed_milliseconds" to elapsedMillis,
            ),
        )
    }

    private fun sleepUntil(deadlineNanos: Long) {
        var interrupted = false
        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                break
            }
            try {
                Thread.sleep(
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                    (remainingNanos % 1_000_000L).toInt(),
                )
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val MAX_DURATION_SECONDS = 300.0
    }
}
