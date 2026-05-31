package com.diffbot.mcp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(DiffbotProperties::class)
class McpApplication

fun main(args: Array<String>) {
    runApplication<McpApplication>(*args)
}
