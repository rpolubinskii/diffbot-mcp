package com.diffbot.mcp

import com.diffbot.semantic.v1.Match
import com.diffbot.semantic.v1.Pose2D
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.PI

class SemanticClientServiceTest {
    @Test
    fun `matchToMap adds standoff goal facing object`() {
        val match = Match.newBuilder()
            .setLabel("vase")
            .setConfidence(0.9f)
            .setPosition(Pose2D.newBuilder().setX(2.0f).setY(2.0f).setYaw(0.0f))
            .build()

        val mapped = matchToMap(match, SemanticRobotPose(x = 2.0, y = 0.0, source = "test_pose"))
        val goal = mapped["suggested_goal"] as Map<*, *>

        assertEquals(2.0, goal["x"] as Double, 1e-6)
        assertEquals(1.2, goal["y"] as Double, 1e-6)
        assertEquals(PI / 2.0, goal["yaw"] as Double, 1e-6)
        assertEquals(0.8, goal["standoff_m"] as Double, 1e-6)
        assertEquals("test_pose", goal["pose_source"])
    }

    @Test
    fun `matchToMap leaves suggested goal unset without current map pose`() {
        val match = Match.newBuilder()
            .setLabel("vase")
            .setPosition(Pose2D.newBuilder().setX(2.0f).setY(2.0f))
            .build()

        val mapped = matchToMap(match)

        assertNull(mapped["suggested_goal"])
        assertEquals(2.0f, mapped["x"])
        assertEquals(2.0f, mapped["y"])
    }
}
