plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.kotlinSpring)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":domain"))
    implementation(project(":common"))
    implementation(project(":infrastructure:persistence"))
    implementation(project(":infrastructure:security"))
    implementation(project(":infrastructure:mail"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.dataJpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.moduleKotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc.openapi)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.springmockk)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("notes-server.jar")
}
