import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: no Android library is on this module's classpath, so any use of an
// Android class fails to compile. That is the guarantee the stack decision requires.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // JSON for the progress export, and later for the Kavita API (decision of 27/10/2026).
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
