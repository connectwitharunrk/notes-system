plugins {
    alias(libs.plugins.kotlinSpring)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":common"))

    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.kotlin.reflect)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    implementation(libs.bucket4j.core)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
