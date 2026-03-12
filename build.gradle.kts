plugins {
    application
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("io.ktor.plugin") version "3.4.1"
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
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    implementation("io.ktor:ktor-server-core-jvm:3.4.1")
    implementation("io.ktor:ktor-server-cio-jvm:3.4.1")
    implementation("io.ktor:ktor-server-cors:3.4.1")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")

    implementation("io.ktor:ktor-client-core-jvm:3.4.1")
    implementation("io.ktor:ktor-client-okhttp-jvm:3.4.1")
    implementation("io.ktor:ktor-client-logging:3.4.1")
    implementation("io.ktor:ktor-client-websockets:3.4.1")
    implementation("io.ktor:ktor-client-content-negotiation-jvm")

    implementation("io.github.nomisrev:kotlinx-serialization-jsonpath:0.2.0")
    // https://mvnrepository.com/artifact/org.jsoup/jsoup
    implementation("org.jsoup:jsoup:1.18.1")
    // https://mvnrepository.com/artifact/org.jline/jline
    implementation("org.jline:jline:3.30.6")
    // https://mvnrepository.com/artifact/commons-cli/commons-cli
    implementation("commons-cli:commons-cli:1.9.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.ktor:ktor-server-test-host-jvm")
}

tasks.test {
    useJUnitPlatform()
}