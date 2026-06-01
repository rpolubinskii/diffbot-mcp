package com.diffbot.mcp

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "diffbot")
class DiffbotProperties {
    var rosMcpConnectionName: String = "ros-mcp"
    var cameraTimeoutSeconds: Double = 10.0
    var rosTimeoutSeconds: Double = 5.0

    var rosbridge: Rosbridge = Rosbridge()
    var topics: Topics = Topics()
    var actions: Actions = Actions()
    var services: Services = Services()
    var audio: Audio = Audio()
    var futureServices: FutureServices = FutureServices()

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

    class FutureServices {
        var vlmConfigured: Boolean = false
        var ragConfigured: Boolean = false
    }
}
