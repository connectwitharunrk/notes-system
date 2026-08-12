import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The offline-first synchronisation engine.
 *
 * Isolated in its own module on purpose: it is the highest-risk code in the
 * project (silent data loss lives here), so it gets its own test surface and
 * cannot be reached into by feature code.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    jvm()

    android {
        namespace = "com.arunrk.note.sync"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            implementation(project(":core:common"))
            implementation(project(":core:database"))
            implementation(project(":core:network"))
            implementation(project(":core:datastore"))

            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
    }
}
