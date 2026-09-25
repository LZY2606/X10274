plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-java-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.20")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("app.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register<JavaExec>("regenerateCasePack") {
    group = "dicom"
    description = "Regenerates the bundled offline DICOM case pack under src/main/resources/cases"
    mainClass.set("app.tools.CasePackGeneratorKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
