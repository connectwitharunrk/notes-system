import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSpring) apply false
    alias(libs.plugins.kotlinJpa) apply false
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.springDependencyManagement) apply false
}

// Resolved here, in root script scope, then captured by the subprojects block.
// Referencing `libs` from inside `subprojects { }` would resolve against the
// subproject rather than the root catalog.
val javaVersion = libs.versions.java.get()
val springBootVersion = libs.versions.springBoot.get()
val kotlinTestJunit5 = libs.kotlin.test.junit5
val mockkLib = libs.mockk

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    // java-library gives us the `api` vs `implementation` distinction, which is
    // how the layer boundaries stay enforced rather than merely documented.
    apply(plugin = "java-library")

    group = "com.arunrk.notes"
    version = "1.0.0"

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            // Java nullability annotations become enforced Kotlin types, so
            // Spring's @Nullable/@NonNull actually mean something here.
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    dependencies {
        // Every module resolves against the Spring Boot BOM so third-party
        // versions stay consistent, including in modules with no Spring code.
        val bom = platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        add("implementation", bom)
        add("testImplementation", bom)
        add("testImplementation", kotlinTestJunit5)
        add("testImplementation", mockkLib)
    }
}
