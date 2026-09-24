plugins {
    kotlin("jvm") version "2.4.0"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.1.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.2")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.1.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.2")
    testImplementation("io.ktor:ktor-client-core-jvm:3.1.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("app.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
