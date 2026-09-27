plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
}

allprojects {
    group = "dev.spindle"
    version = "0.1.0-SNAPSHOT"

    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    // JVM-17 for the plain-JVM modules AND the Android module's Kotlin.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

