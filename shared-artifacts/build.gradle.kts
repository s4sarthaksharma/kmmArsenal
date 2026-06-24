import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

group = "com.example.shared.artifacts"
version = "1.0.0"

val sharedVersion = "1.0.0"
val sharedGroup = "com.example.shared"

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

    val xcframework = XCFramework("Shared")
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcframework.add(this)
            export("$sharedGroup:shared:$sharedVersion")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("$sharedGroup:shared:$sharedVersion")
        }
    }
}

val sharedAar by configurations.creating {
    isTransitive = false
}

dependencies {
    sharedAar("$sharedGroup:shared-android:$sharedVersion@aar")
}

val resolveAndroidAar by tasks.registering(Copy::class) {
    from(sharedAar)
    into(layout.buildDirectory.dir("outputs/android"))
    rename { "shared.aar" }
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
