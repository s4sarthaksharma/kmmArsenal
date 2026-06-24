import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

val kmpGroup         = (project.findProperty("kmpGroup")         as String?) ?: "com.example.shared"
val kmpArtifact      = (project.findProperty("kmpArtifact")      as String?) ?: "shared"
val kmpVersion       = (project.findProperty("kmpVersion")       as String?) ?: "1.0.0"
val kmpFrameworkName = (project.findProperty("kmpFrameworkName") as String?) ?: "Shared"

group   = "com.example.shared.artifacts"
version = kmpVersion

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    val xcframework = XCFramework(kmpFrameworkName)
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = kmpFrameworkName
            isStatic = true
            xcframework.add(this)
            export("$kmpGroup:$kmpArtifact:$kmpVersion")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("$kmpGroup:$kmpArtifact:$kmpVersion")
        }
    }
}

val sharedAar by configurations.creating {
    isTransitive = false
}

dependencies {
    sharedAar("$kmpGroup:$kmpArtifact-android:$kmpVersion@aar")
}

val resolveAndroidAar by tasks.registering(Copy::class) {
    from(sharedAar)
    into(layout.buildDirectory.dir("outputs/android"))
    rename { "$kmpArtifact.aar" }
}

android {
    namespace = "com.example.shared.artifacts"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
