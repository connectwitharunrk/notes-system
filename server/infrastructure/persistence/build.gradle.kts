plugins {
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.kotlinJpa)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":common"))

    implementation(libs.spring.boot.starter.dataJpa)
    implementation(libs.jackson.moduleKotlin)
    implementation(libs.kotlin.reflect)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
