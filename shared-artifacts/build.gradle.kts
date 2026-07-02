import bridgegen.DumpKmpModelTask
import bridgegen.GeneratePlatformBridgesTask

val kmpGroup          = (project.findProperty("kmpGroup")          as String?) ?: "com.example.shared"
val kmpArtifact       = (project.findProperty("kmpArtifact")       as String?) ?: "shared"
val kmpVersion        = (project.findProperty("kmpVersion")        as String?) ?: "1.0.0"
val kmpFrameworkName  = (project.findProperty("kmpFrameworkName")  as String?) ?: "Shared"
val kmpAndroidPackage = (project.findProperty("kmpAndroidPackage") as String?) ?: "expo.modules.kmpbridge"
val kmpConsumerDir    = (project.findProperty("kmpConsumerDir")    as String?) ?: "../kmp-bridge"
val kmpSourceDir      = "../$kmpArtifact/src/commonMain"
val androidPkgPath    = kmpAndroidPackage.replace('.', '/')

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

val dumpKmpModel by tasks.registering(DumpKmpModelTask::class) {
    klibDir.set(file("../$kmpArtifact/build/classes/kotlin/metadata/commonMain"))
    targetPackage.set(kmpGroup)
    outputFile.set(layout.buildDirectory.file("kmp-model-dump.txt"))
}

val generatePlatformBridges by tasks.registering(GeneratePlatformBridgesTask::class) {
    klibDir.set(file("../$kmpArtifact/build/classes/kotlin/metadata/commonMain"))
    sourceDir.set(file(kmpSourceDir))
    kmpPackageName.set(kmpGroup)
    frameworkName.set(kmpFrameworkName)
    androidPackage.set(kmpAndroidPackage)
    androidOutDir.set(file("$kmpConsumerDir/android/src/main/java/$androidPkgPath"))
    iosOutDir.set(file("$kmpConsumerDir/ios"))
    tsOutDir.set(file("$kmpConsumerDir/src"))
    consumerRoot.set(file(kmpConsumerDir))
}
