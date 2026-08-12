plugins {
    alias(libs.plugins.kotlinSpring)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":common"))

    implementation(libs.spring.boot.starter.mail)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
}
