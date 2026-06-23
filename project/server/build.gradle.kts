plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.childhelper.server"
version = "1.0.0"

application {
    mainClass.set("com.childhelper.server.ApplicationKt")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)

    // Shared models
    implementation(project(":core:common"))

    // Coroutines
    implementation(libs.coroutines.core)

    // SQLite for persistent store
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")

    // OkHttp for FCM HTTP calls
    implementation(libs.okhttp)

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")
}
