/**
 * buildSrc — Gradle build logic for the shared-artifacts composite build.
 *
 * This is a special Gradle directory: Kotlin sources placed here are compiled before any main
 * build script runs, and the resulting classes are placed on every build-script's classpath
 * automatically — no publishing, versioning, or explicit `classpath` dependency declarations
 * are needed. Any build.gradle.kts inside shared-artifacts can import these classes directly.
 *
 * What lives here:
 *   bridgegen/  — the KMP-to-native bridge code generator. [GenerateKlibBridgeTask] reads
 *                 the compiled klib and emits Swift, Kotlin (Android), and TypeScript bridge
 *                 modules for all platforms.
 *
 * Plugin: `kotlin-dsl` compiles the Kotlin sources with Gradle API support and type-safe accessors.
 * Repository: mavenCentral is declared here for any future runtime build-logic dependencies.
 */
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.20")
}
