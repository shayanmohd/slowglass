import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin logic with no Android imports: stacking maths, detectors, timers, codecs.
// The app module's JVM tests exercise it, so build.sh counts them.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
}
