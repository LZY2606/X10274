plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories { mavenCentral() }

application { mainClass.set("app.MainKt") }

kotlin { jvmToolchain(17) }

dependencies {
    val ktorVersion = "3.6.0"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.htmlunit:htmlunit:5.5.0")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

tasks.processResources {
    from(layout.projectDirectory.dir("cases")) { into("cases") }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
