plugins {
    application
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.20"
    id("io.ktor.plugin") version "3.4.2"
//    id("org.graalvm.buildtools.native") version "0.9.19"
}

group = "github.rikacelery"
version = "1.0-SNAPSHOT"
kotlin {
    jvmToolchain(17)
}
application {
    mainClass.set("github.rikacelery.MainKt")

}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.2")

    implementation("io.ktor:ktor-server-core-jvm:3.4.2")
    implementation("io.ktor:ktor-server-cio-jvm:3.4.2")
    implementation("io.ktor:ktor-server-cors:3.4.2")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.4.2")

    implementation("io.ktor:ktor-client-core-jvm:3.4.2")
    implementation("io.ktor:ktor-client-okhttp-jvm:3.4.2")
    implementation("io.ktor:ktor-client-logging:3.4.2")
    implementation("io.ktor:ktor-client-websockets:3.4.2")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:3.4.2")

    implementation("io.github.nomisrev:kotlinx-serialization-jsonpath:1.0.0")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("org.jline:jline:3.30.6")
    implementation("commons-cli:commons-cli:1.11.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}
