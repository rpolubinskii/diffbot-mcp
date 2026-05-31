# diffbot-mcp

Agent-facing MCP gateway for DiffBot. The server exposes a compact robot interface and wraps a configured `ros-mcp`
backend instead of exposing the full ROS graph as normal agent tools.

## MCP Surface

Resources:

- `robot://status`
- `robot://diagnostics/ros-summary`
- `robot://diagnostics/ros-raw`

Tools:

- `vision.get_camera_image`
- `vision.describe_camera_image`
- `nav.get_pose`
- `nav.get_imu`
- `nav.move_to`
- `nav.turn`
- `nav.stop`
- `speak.say`
- `memory.retrieve`
- `memory.memorize`

## Running

The project targets JDK 25 and uses Spring AI MCP annotation scanning.

```bash
./gradlew bootRun
```

The default public MCP transport is Streamable HTTP at `http://localhost:8080/mcp`. Configure a downstream `ros-mcp`
connection with Spring AI MCP client properties for your deployment:

```properties
spring.ai.mcp.client.streamable-http.connections.ros-mcp.url=http://127.0.0.1:9000
spring.ai.mcp.client.streamable-http.connections.ros-mcp.endpoint=/mcp
```

Configure the robot rosbridge address separately. `diffbot-mcp` sends this to `ros-mcp` with `connect_to_robot` before
the first ROS-backed tool call:

```properties
diffbot.rosbridge.ip=192.168.0.147
diffbot.rosbridge.port=9090
diffbot.rosbridge.connect-timeout-seconds=2.0
```

Configure `diffbot-audio` separately. `speak.say` calls its streaming gRPC `Speak` RPC and waits for `FINISHED` or `FAILED` before returning:

```properties
diffbot.audio.host=localhost
diffbot.audio.port=50052
diffbot.audio.deadline-seconds=60.0
```

Future VLM and RAG services are intentionally stubs in v1 and return `backend_unavailable` until configured.
