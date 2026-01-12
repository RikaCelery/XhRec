plugins {
    application
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
    id("io.ktor.plugin") version "3.3.3"
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
repositories {
    mavenCentral()
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.4.12")
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-client-core-jvm")
    implementation("io.ktor:ktor-server-cio-jvm")
    implementation("io.ktor:ktor-client-okhttp-jvm:3.3.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.github.nomisrev:kotlinx-serialization-jsonpath:0.2.0")
    // https://mvnrepository.com/artifact/org.jsoup/jsoup
    implementation("org.jsoup:jsoup:1.18.1")
    // https://mvnrepository.com/artifact/org.jline/jline
    implementation("org.jline:jline:3.29.0")
    // https://mvnrepository.com/artifact/commons-cli/commons-cli
    implementation("commons-cli:commons-cli:1.9.0")
    implementation("io.ktor:ktor-server-cors:3.3.3")
    implementation("io.ktor:ktor-client-cio-jvm:3.3.3")
    implementation("io.ktor:ktor-client-jetty:3.3.3")
    implementation("io.ktor:ktor-client-logging:3.3.3")

    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}