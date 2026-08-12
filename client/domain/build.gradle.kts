import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * PURE KOTLIN domain layer.
 *
 * This module deliberately depends on nothing but the stdlib, coroutines,
 * datetime and :core:common. No Ktor, no SQLDelight, no Compose, no Koin.
 * That constraint is the enforcement mechanism for Clean Architecture -
 * if you find yourself wanting to add a framework here, the design is wrong.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    jvm()

    android {
        namespace = "com.arunrk.note.domain"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            implementation(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
        }
    }
}
