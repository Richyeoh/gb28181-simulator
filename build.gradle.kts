plugins {
    kotlin("jvm") version "1.9.25"
    application
}

group = "com.goway.gb28181"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.goway.gb28181.MainKt")
}
