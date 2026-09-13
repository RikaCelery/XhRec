// XhCut — the cutting/editing front end for XhRec recordings.
//
// Plugin versions are intentionally omitted: the root build script already puts
// kotlin("jvm"), kotlin("plugin.serialization") and io.ktor.plugin on the build
// classpath, and a subproject may apply them without re-declaring a version.
plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
}

group = "github.rikacelery"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("github.rikacelery.cutter.MainKt")
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.6.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    implementation("io.ktor:ktor-server-core-jvm:3.5.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.5.2")
    implementation("io.ktor:ktor-server-cors:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-server-compression:3.5.2")
    // Byte-range support so the browser can play a source file directly (raw mode).
    implementation("io.ktor:ktor-server-partial-content:3.5.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("commons-cli:commons-cli:1.11.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.5.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "768m"
}

// The container copies `cutter-all.jar`; ShadowJar extends Jar, so naming the task
// this way works whether the Ktor plugin resolves the GradleUp or johnrengelman fork.
tasks.named<Jar>("shadowJar") {
    archiveBaseName.set("cutter")
    archiveClassifier.set("all")
    archiveVersion.set("")
}
