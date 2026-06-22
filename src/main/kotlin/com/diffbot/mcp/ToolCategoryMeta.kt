package com.diffbot.mcp

import org.springframework.ai.mcp.annotation.context.MetaProvider

// CATEGORY_KEY and the category strings are a contract with diffbot-agent
// (openai_agents_runtime.TOOL_CATEGORY_META_KEY, episode.TOOL_CATEGORIES).
const val CATEGORY_KEY: String = "diffbot.dev/category"

abstract class ToolCategoryMeta(private val category: String) : MetaProvider {
    override fun getMeta(): Map<String, Any> = mapOf(CATEGORY_KEY to category)
}

class SpeechCategory : ToolCategoryMeta("speech")
class NavigationCategory : ToolCategoryMeta("navigation")
class SafetyCategory : ToolCategoryMeta("safety")
class StatusCategory : ToolCategoryMeta("status")
class VisionCategory : ToolCategoryMeta("vision")
class GenericToolCategory : ToolCategoryMeta("tool")
