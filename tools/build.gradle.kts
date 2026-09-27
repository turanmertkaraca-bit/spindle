plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
