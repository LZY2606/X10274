plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.2.3")
    implementation("io.ktor:ktor-server-netty:3.2.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("io.ktor:ktor-server-test-host:3.2.3")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("app.MainKt")
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }
