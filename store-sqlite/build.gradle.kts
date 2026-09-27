plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":core"))
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
