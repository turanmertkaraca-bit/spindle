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

// The `run` task forks a JVM that does NOT inherit Gradle's proxy/truststore
// system properties. Forward them from the environment so a sandboxed dev
// machine (and the live smoke) can reach providers. No-ops on CI.
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

// Long-session STRESS HARNESS. This is a main-class runnable, not a JUnit test,
// so CI (which runs `:cli:classes`) compiles and never executes it. It lives in
// the test source set so it is also invisible to `:cli:test`.
//
//   OPENROUTER_API_KEY=... ./gradlew :cli:stress --no-daemon --args="--turns 25"
//
// `--no-daemon` matters: the forked JVM inherits the Gradle process environment,
// and a reused daemon would not see a freshly exported key.
tasks.register<JavaExec>("stress") {
    group = "verification"
    description = "Long-session stress harness against a live provider (requires OPENROUTER_API_KEY)."
    dependsOn(tasks.named("testClasses"))
    mainClass.set("dev.spindle.cli.StressHarness")
    classpath = sourceSets["test"].runtimeClasspath

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
    System.getenv("SPINDLE_STRESS_XMX")?.takeIf { it.isNotBlank() }?.let { jvmArgs("-Xmx$it") }
    // Stream harness output as it is produced (Gradle buffers JavaExec stdout by default).
    standardOutput = System.out
    errorOutput = System.err
}
