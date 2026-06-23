package com.diffbot.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaitServiceTest {
    private val waitService = WaitService()

    @Test
    fun `wait accepts a short positive duration`() {
        val result = waitService.wait(0.001)

        assertEquals(true, result["ok"])
        assertEquals(0.001, result["duration_seconds"])
        assertTrue((result["elapsed_milliseconds"] as Long) >= 0L)
    }

    @Test
    fun `wait rejects unsafe durations`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 300.001).forEach { duration ->
            val result = waitService.wait(duration)

            assertEquals(false, result["ok"])
            assertEquals("unsafe_request", result["error_class"])
        }
    }
}
