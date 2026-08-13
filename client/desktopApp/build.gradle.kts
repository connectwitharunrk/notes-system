import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.arunrk.note.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            packageName = "Notes"
            packageVersion = "1.0.0"
            description = "Offline-first notes that sync across your devices"
            vendor = "arunrk"
            copyright = "© 2026 arunrk"

            // The app stores its database, preferences and session under
            // ~/.notes-system, so the installer needs no special permissions and
            // uninstalling leaves the user's notes intact.
            modules("java.sql", "java.naming")

            windows {
                menuGroup = "Notes"
                // Stable so upgrades replace the previous install rather than
                // sitting beside it. Must never change between releases.
                upgradeUuid = "6C6F8B2E-4E4D-4A2B-9E3F-5A1D2C3B4E5F"
                dirChooser = true
            }

            macOS {
                bundleID = "com.arunrk.note"
            }

            linux {
                packageName = "notes"
                debMaintainer = "dev@notes.local"
                appCategory = "Utility"
            }
        }
    }
}
