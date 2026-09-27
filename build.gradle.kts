plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    group = "dev.spindle"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
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
