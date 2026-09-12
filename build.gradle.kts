plugins {
    application
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("io.ktor.plugin") version "3.5.2"
//    id("org.graalvm.buildtools.native") version "0.9.19"
}

group = "github.rikacelery"
version = "1.0-SNAPSHOT"
kotlin {
    jvmToolchain(17)
}
application {
    mainClass.set("github.rikacelery.v3.MainKt")

}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.6.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    implementation("io.ktor:ktor-server-core-jvm:3.5.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.5.2")
    implementation("io.ktor:ktor-server-websockets-jvm:3.5.2")
    implementation("io.ktor:ktor-network-tls-certificates-jvm:3.5.2")
    implementation("io.ktor:ktor-server-cors:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.5.2")

    implementation("io.ktor:ktor-client-core-jvm:3.5.2")
    implementation("io.ktor:ktor-client-okhttp-jvm:3.5.2")
    implementation("io.ktor:ktor-client-logging:3.5.2")
    implementation("io.ktor:ktor-client-websockets:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:3.5.2")

    implementation("io.github.nomisrev:kotlinx-serialization-jsonpath:1.0.0")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("commons-cli:commons-cli:1.11.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-client-mock-jvm:3.5.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.5.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()

    // Suites run in separate JVMs, not one shared one: the component graph is built on
    // process-wide singletons (Diagnostics keys actors by name, plus CdnSelector, Hosts,
    // SensitiveStringRegistry, PredictionSampleStore), so two fixtures in the same JVM would
    // collide. Every fixture binds ephemeral ports (port 0) and its own temp directory, and the
    // test logback config keeps them off the shared logs/xhrec.log, so forks are independent.
    //
    // Measured on an 8-core box (whole suite, 308 tests): 1 fork 107s, 2 → 81s, 3 → 41s,
    // 4 → 46s, 6 → 35-45s, 8 → 42-45s; wall time plateaus from ~6 forks on. Default to one fork
    // per CPU capped at 8, overridable with -PtestForks=N.
    maxParallelForks = providers.gradleProperty("testForks").map { it.toInt() }.getOrElse(
        Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    )
    maxHeapSize = "768m"
}
