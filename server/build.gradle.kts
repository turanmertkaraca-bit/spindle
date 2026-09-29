plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":tools"))
    implementation(project(":provider-openai"))
    implementation(project(":provider-anthropic"))
    implementation(project(":store-sqlite"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

application {
    mainClass.set("dev.spindle.server.MainKt")
}

// The `run` task forks a JVM that does NOT inherit Gradle's proxy/truststore
// system properties. Forward them so a sandboxed dev machine can reach providers.
tasks.named<JavaExec>("run") {
    val proxy = System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY")
    if (!proxy.isNullOrBlank()) {
        val hp = proxy.removePrefix("http://").removePrefix("https://")
        val host = hp.substringBefore(":")
        val port = hp.substringAfter(":")
        jvmArgs(
            "-Dhttps.proxyHost=$host", "-Dhttps.proxyPort=$port",
            "-Dhttp.proxyHost=$host", "-Dhttp.proxyPort=$port",
            "-Dhttps.nonProxyHosts=localhost|127.0.0.1|::1",
            "-Dhttp.nonProxyHosts=localhost|127.0.0.1|::1",
        )
    }
    System.getenv("SPINDLE_TRUSTSTORE")?.takeIf { it.isNotBlank() }?.let { ts ->
        jvmArgs(
            "-Djavax.net.ssl.trustStore=$ts",
            "-Djavax.net.ssl.trustStorePassword=" + (System.getenv("SPINDLE_TRUSTSTORE_PASSWORD") ?: "changeit"),
            "-Djavax.net.ssl.trustStoreType=PKCS12",
        )
    }
}
