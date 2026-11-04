import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// EPUBs made in code for tests, never a binary in the repository. Pure Kotlin
// so core, the emulator tests and the Kavita tests can all use it; nothing here reaches the APK.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
