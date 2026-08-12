rootProject.name = "Note"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// ── Application shells ────────────────────────────────────────────────
include(":androidApp")
include(":desktopApp")

// ── Aggregator: App(), Koin startup, NavHost, iOS framework ───────────
include(":shared")

// ── Core infrastructure ───────────────────────────────────────────────
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:network")
include(":core:datastore")

// ── Clean Architecture layers ─────────────────────────────────────────
include(":domain")
include(":data")
include(":sync")

// ── Features ──────────────────────────────────────────────────────────
include(":feature:auth")
include(":feature:notes")
include(":feature:settings")
