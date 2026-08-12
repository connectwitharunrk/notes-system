plugins {
    alias(libs.plugins.kotlinSpring)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":common"))
    implementation(project(":infrastructure:security"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jackson.moduleKotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc.openapi)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.springmockk)
}
