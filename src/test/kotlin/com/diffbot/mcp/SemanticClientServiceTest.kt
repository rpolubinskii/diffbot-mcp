package com.diffbot.mcp

import com.diffbot.semantic.v1.Match
import com.diffbot.semantic.v1.Pose2D
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SemanticClientServiceTest {
    @Test
    fun `matchToMap exposes object coordinates without a suggested goal`() {
        val match = Match.newBuilder()
            .setLabel("vase")
            .setConfidence(0.9f)
            .setPosition(Pose2D.newBuilder().setX(2.0f).setY(2.0f).setYaw(0.0f))
            .setBackend("dualmap")
            .putMetadata("state", "provisional")
            .build()

        val mapped = matchToMap(match)

        assertEquals("vase", mapped["label"])
        assertEquals(0.9f, mapped["confidence"])
        assertEquals(2.0f, mapped["x"])
        assertEquals(2.0f, mapped["y"])
        assertEquals("dualmap", mapped["backend"])
        assertEquals(mapOf("state" to "provisional"), mapped["metadata"])
        assertFalse(mapped.containsKey("suggested_goal"))
    }
}
