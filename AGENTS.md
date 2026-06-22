# Repository Guidelines

## Project Structure & Module Organization

This is a Kotlin/Spring Boot MCP gateway for DiffBot. Application code lives in `src/main/kotlin/com/diffbot/mcp`, with MCP surfaces, ROS gateway logic, navigation, robot state, audio client, and logging services grouped in that package. Protocol definitions live in `src/main/proto`. Docker-related files are under `docker/`.

## Build, Test, and Development Commands

- `./gradlew build` compiles the project, generates protobuf/gRPC sources, and runs tests.
- `./gradlew test` runs the JUnit 5 test suite.
- `./gradlew bootRun` starts the MCP server locally.

The public MCP transport defaults to `http://localhost:8080/mcp`. Configure downstream ROS MCP, rosbridge, and audio service settings through Spring properties as shown in `README.md`.
