package com.diffbot.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryGatewayTest {

    @Test
    fun `remember maps to Graphiti add_memory`() {
        val (tool, args) = MemoryRequests.remember(
            content = "the charging dock is by the window",
            groupId = "diffbot",
            referenceTime = "2026-06-23T12:00:00Z",
        )
        assertEquals("add_memory", tool)
        assertEquals("the charging dock is by the window", args["episode_body"])
        assertEquals("diffbot", args["group_id"])
        assertEquals("text", args["source"])
        assertEquals("2026-06-23T12:00:00Z", args["reference_time"])
    }

    @Test
    fun `recall maps to Graphiti search_memory_facts`() {
        val (tool, args) = MemoryRequests.recall(query = "where is the dock", groupId = "diffbot", maxFacts = 10)
        assertEquals("search_memory_facts", tool)
        assertEquals("where is the dock", args["query"])
        assertEquals(listOf("diffbot"), args["group_ids"])
        assertEquals(10, args["max_facts"])
    }
}
