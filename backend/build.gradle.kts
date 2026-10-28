plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

repositories { mavenCentral() }
kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
application { mainClass.set("com.toked.smartshopping.backend.ApplicationKt") }

dependencies {
    val ktor = "3.2.3"
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.10.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.10.2")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("com.google.api-client:google-api-client:2.7.2")
    implementation("org.eclipse.angus:jakarta.mail:2.0.3")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.81")
}

tasks.test {
    inputs.file("../Frontend/app/src/test/resources/shopping/plan-view.json")
    inputs.file("postman/auth00.postman_collection.json")
    inputs.file("postman/profile01.postman_collection.json")
    inputs.file("postman/plan01.postman_collection.json")
    inputs.dir("src/test/javascript")
    inputs.file("scripts/run-local.ps1")
    inputs.dir("src/test/powershell")
    // PostgreSQL integration is mandatory: absence of a test URL is a failure, not a skip.
    maxParallelForks = 1
    testLogging { events("failed", "skipped"); showStandardStreams = false }
}

// Only test classes contain the fixture HTTP routes. No fixture enters the application distribution.
tasks.register("prepareAuthFixture") {
    dependsOn(tasks.testClasses)
    doLast {
        layout.buildDirectory.file("auth-fixture-classpath.txt").get().asFile
            .writeText(sourceSets["test"].runtimeClasspath.asPath)
    }
}
