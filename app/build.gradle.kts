plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:2.3.13")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.13")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.13")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.13")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("com.microsoft.playwright:playwright:1.48.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

application {
    mainClass.set("gsbreview.MainKt")
}

tasks.register<JavaExec>("execGen") {
    group = "casepack"
    description = "Generates the bundled offline DICOM case pack"
    mainClass.set("gsbreview.casepack.CasePackGeneratorKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.test {
    useJUnitPlatform {
        if (System.getenv("E2E_BROWSER") == null) excludeTags("browser")
    }
    testLogging { events("passed", "skipped", "failed") }
}
