rootProject.name = "notes-server"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Runnable Spring Boot application - wiring and configuration only.
include(":app")

// Cross-cutting primitives with no Spring dependency.
include(":common")

// PURE KOTLIN business core: models, ports, use cases. No Spring on the classpath.
include(":domain")

// Inbound adapter: REST controllers, DTOs, error handling.
include(":api")

// Outbound adapters implementing the domain ports.
include(":infrastructure:persistence")
include(":infrastructure:security")
include(":infrastructure:mail")
