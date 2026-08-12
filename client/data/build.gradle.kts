import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        namespace = "com.arunrk.note.data"
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
        }
        jvmTest.dependencies {
            // Real SQLite, in memory. Testing a repository against a mocked DAO
            // proves the mock works, not that the SQL does.
            implementation(libs.sqldelight.driver.sqlite)
        }
    }
}
