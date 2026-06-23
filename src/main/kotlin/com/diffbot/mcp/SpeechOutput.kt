package com.diffbot.mcp

interface SpeechOutput {
    fun speak(text: String): Map<String, Any?>
}
