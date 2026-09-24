plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "org.research.gsb"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.1"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
}

application {
    mainClass.set("org.research.gsb.dicom.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // The end-to-end browser test discovers an installed Chrome/Chromium and
    // skips itself when none is available.
    systemProperty("gsb.e2e", System.getProperty("gsb.e2e", "1"))
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

kotlin {
    jvmToolchain(17)
}
