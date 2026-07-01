package com.diffbot.mcp

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "diffbot")
class DiffbotProperties {
    var rosMcpConnectionName: String = "ros-mcp"
    var cameraTimeoutSeconds: Double = 10.0
    var rosTimeoutSeconds: Double = 5.0

    var debug: Debug = Debug()
    var rosbridge: Rosbridge = Rosbridge()
    var topics: Topics = Topics()
    var actions: Actions = Actions()
    var services: Services = Services()
    var nav: Nav = Nav()
    var audio: Audio = Audio()
    var semantic: Semantic = Semantic()
    var memory: Memory = Memory()

    class Memory {
        var connectionName: String = "diffbot-memory"
        // Substring matched (case-insensitive) against the upstream MCP server's
        // reported name if the configured connection name is unavailable.
        var serverNameMatch: String = "graphiti"
        // Graph namespace; must match diffbot-memory's GRAPHITI_GROUP_ID.
        var groupId: String = "diffbot"
        var recallMaxFacts: Int = 10
    }

    class Debug {
        var enabled: Boolean = false
    }

    class Rosbridge {
        var ip: String = "192.168.0.147"
        var port: Int = 9090
        var connectTimeoutSeconds: Double = 2.0
    }

    class Topics {
        var cameraImage: String = "/camera/camera/color/image_raw"
        var imu: String = "/imu/external/data_body"
        var localizationPose: String = "/localization_pose"
        var odom: String = "/odom"
        var baseOdom: String = "/diffbot_base_controller/odom"
        var cmdVel: String = "/cmd_vel"
        var baseCmdVel: String = "/diffbot_base_controller/cmd_vel_unstamped"
    }

    class Actions {
        var navigateToPose: String = "/navigate_to_pose"
        var spin: String = "/spin"
        var driveOnHeading: String = "/drive_on_heading"
        var computePathToPose: String = "/compute_path_to_pose"
    }

    class Nav {
        // Must match a planner id in Nav2's planner_server.planner_plugins.
        var plannerId: String = "GridBased"
        var approachStandoffM: Double = 0.8
        var approachCandidates: Int = 8
        var planTimeoutSeconds: Double = 5.0
    }

    class Services {
        var rosActionServers: String = "/rosapi/action_servers"
        var rosActionServersType: String = "rosapi_msgs/srv/GetActionServers"
    }

    class Audio {
        var host: String = "localhost"
        var port: Int = 50052
        var deadlineSeconds: Double = 60.0
    }

    class Semantic {
        var host: String = "192.168.0.176"
        var port: Int = 50053
        var deadlineSeconds: Double = 10.0
        var currentPoseTopic: String = "/dualmap/odom"
        var currentPoseTimeoutSeconds: Double = 1.0
    }
}
