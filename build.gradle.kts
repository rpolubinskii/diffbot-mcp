import com.google.protobuf.gradle.id

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.5"
}

group = "com.diffbot"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.0-M8"
extra["springGrpcVersion"] = "1.0.3"

dependencies {
	implementation("io.grpc:grpc-services")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springframework.ai:spring-ai-starter-mcp-client")
	implementation("org.springframework.ai:spring-ai-starter-mcp-server")
	implementation("org.springframework.grpc:spring-grpc-client-spring-boot-starter")
	implementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.grpc:spring-grpc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
		mavenBom("org.springframework.grpc:spring-grpc-dependencies:${property("springGrpcVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

protobuf {
	protoc {
		artifact = "com.google.protobuf:protoc"
	}
	plugins {
		id("grpc") {
			artifact = "io.grpc:protoc-gen-grpc-java"
		}
	}
	generateProtoTasks {
		all().forEach {
			it.plugins {
				id("grpc") {
					option("@generated=omit")
				}
			}
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
