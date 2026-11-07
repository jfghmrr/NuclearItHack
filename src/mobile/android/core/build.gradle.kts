import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("validateTranscript") {
    description = "Проверяет файл контракта: --args=\"<файл> <длительность_мс>\""
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.stenokot.core.contract.ValidateFileKt")
}
