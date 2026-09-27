plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":tools"))
    implementation(project(":provider-openai"))
    implementation(project(":provider-anthropic"))
    implementation(project(":store-sqlite"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.spindle.cli.MainKt")
}
