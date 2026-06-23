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
- `nav.get_pose`
- `nav.get_imu`
- `nav.move_to`
- `nav.turn`
- `nav.cancel_goal`
- `nav.stop`
- `system.wait`
- `speak.say`
- `memory.retrieve`
- `memory.memorize`

Debug mode can additionally expose:

- `nav.drive_on_heading`

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

Direct relative movement is disabled by default. Enable the debug MCP surface at startup to expose
`nav.drive_on_heading`, which uses Nav2's collision-aware `DriveOnHeading` behavior rather than raw `/cmd_vel`:

```properties
diffbot.debug.enabled=true
```

The tool accepts a signed distance in meters, an optional positive speed magnitude (default `0.15` m/s), and an
optional timeout (default `30` seconds). Positive distances move forward and negative distances move backward.

`nav.cancel_goal` cancels only the last `NavigateToPose` goal remembered by `diffbot-mcp`. Use `nav.stop` when the
robot should also receive zero velocity commands. `system.wait` blocks the current tool call for a positive duration
up to 300 seconds.

Configure `diffbot-audio` separately. `speak.say` calls its streaming gRPC `Speak` RPC and waits for `FINISHED` or `FAILED` before
returning:

```properties
diffbot.audio.host=localhost
diffbot.audio.port=50052
diffbot.audio.deadline-seconds=60.0
```

Future VLM and RAG services are intentionally stubs in v1 and return `backend_unavailable` until configured.
