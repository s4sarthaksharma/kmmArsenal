package bridgegen

import bridgegen.generators.AndroidGenerator
import bridgegen.generators.SwiftGenerator
import bridgegen.generators.TsBridgeGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

/**
 * Reads the compiled `commonMain` klib and writes bridge files directly into the consumer package:
 * - androidOutDir — Expo Module .kt files (Kotlin) per source file.
 * - iosOutDir     — Expo Module .swift files per source file.
 * - tsOutDir      — TypeScript types + requireNativeModule wrappers.
 * - consumerRoot  — expo-module.config.json (Expo module registration).
 *
 * Output directories are declared `@Internal` — Gradle does not track them for incremental
 * builds. This task is intended to be run on-demand via [push-bridges.sh], not as part of
 * the incremental app build graph.
 *
 * Run via:
 * ```
 * bash scripts/push-bridges.sh [--publish] [--ios]
 * ```
 */
abstract class GeneratePlatformBridgesTask : DefaultTask() {

    /** The `commonMain` metadata klib produced by `metadataCommonMainClasses`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val klibDir: DirectoryProperty

    /** The `commonMain` source directory — used to resolve source file names from klib parts. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** Root Kotlin package of the KMP module (e.g. `"com.example.shared"`). */
    @get:Input
    abstract val kmpPackageName: Property<String>

    /** KMP XCFramework name used in Swift `import` statements (e.g. `"Shared"`). */
    @get:Input
    abstract val frameworkName: Property<String>

    /**
     * Android package for the generated bridge modules, derived from the consumer npm package
     * name (e.g. "kmp-bridge" -> "expo.modules.kmpbridge").
     */
    @get:Input
    abstract val androidPackage: Property<String>

    /** Destination for generated Android `.kt` files. Not tracked by Gradle. */
    @get:Internal
    abstract val androidOutDir: DirectoryProperty

    /** Destination for generated iOS `.swift` files. Not tracked by Gradle. */
    @get:Internal
    abstract val iosOutDir: DirectoryProperty

    /** Destination for generated TypeScript `.ts` files. Not tracked by Gradle. */
    @get:Internal
    abstract val tsOutDir: DirectoryProperty

    /** Consumer package root — receives `expo-module.config.json`. Not tracked by Gradle. */
    @get:Internal
    abstract val consumerRoot: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkg        = kmpPackageName.get()
        val fw         = frameworkName.get()
        val androidPkg = androidPackage.get()
        val module     = KlibApiReader.read(klibDir.get().asFile, pkg, sourceDir.get().asFile)

        val androidOut     = androidOutDir.get().asFile
        val iosOut         = iosOutDir.get().asFile
        val tsOut          = tsOutDir.get().asFile
        val consumerRootFile = consumerRoot.get().asFile

        // Clean stale generated files before writing fresh output.
        androidOut.mkdirs()
        androidOut.listFiles { f -> f.extension == "kt" }?.forEach { it.delete() }

        iosOut.mkdirs()
        iosOut.listFiles { f ->
            f.extension == "swift" && f.name != "KmpBridgeModule.swift"
        }?.forEach { it.delete() }

        tsOut.deleteRecursively()
        tsOut.mkdirs()

        var ktCount    = 0
        var swiftCount = 0
        var tsCount    = 0
        val moduleClasses = mutableListOf<String>()

        for (sourceFile in module.files) {

            val ktContent = AndroidGenerator.generateFile(sourceFile, module, pkg, androidPkg, onSkip = { logger.quiet("\n  >> [Android] $it") })
            if (ktContent.isNotBlank()) {
                File(androidOut, "${sourceFile.fileName}Module.kt").writeText(ktContent)
                logger.lifecycle("  android/${sourceFile.fileName}Module.kt")
                Regex("""^class (\w+Module)\s*:\s*Module\(\)""", RegexOption.MULTILINE)
                    .findAll(ktContent).mapTo(moduleClasses) { it.groupValues[1] }
                ktCount++
            }

            val swiftContent = SwiftGenerator.generateFile(sourceFile, module, fw, onSkip = { logger.quiet("\n  >> [iOS] $it") })
            if (swiftContent.isNotBlank()) {
                File(iosOut, "${sourceFile.fileName}Module.swift").writeText(swiftContent)
                logger.lifecycle("  ios/${sourceFile.fileName}Module.swift")
                swiftCount++
            }

            val tsContent = TsBridgeGenerator.generate(sourceFile, module, onSkip = { logger.quiet("\n  >> [TS] $it") })
            if (tsContent.isNotBlank()) {
                File(tsOut, "${sourceFile.fileName}.ts").writeText(tsContent)
                logger.lifecycle("  ts/${sourceFile.fileName}.ts")
                tsCount++
            }
        }

        // Write expo-module.config.json directly to consumer root.
        moduleClasses.sort()
        File(consumerRootFile, "expo-module.config.json").writeText(buildExpoModuleConfig(moduleClasses, androidPkg))
        logger.lifecycle("  expo-module.config.json (${moduleClasses.size} modules)")

        if (ktCount == 0 && swiftCount == 0 && tsCount == 0) {
            logger.warn("Platform bridge generator: nothing to generate for $pkg")
        } else {
            logger.lifecycle("Platform bridge generator: done")
        }
    }

    private fun buildExpoModuleConfig(classes: List<String>, androidPkg: String): String {
        val apple   = classes.joinToString(", ") { "\"$it\"" }
        val android = classes.joinToString(", ") { "\"$androidPkg.$it\"" }
        return "{\n" +
            "  \"platforms\": [\"apple\", \"android\"],\n" +
            "  \"apple\": {\n" +
            "    \"modules\": [${apple}]\n" +
            "  },\n" +
            "  \"android\": {\n" +
            "    \"modules\": [${android}]\n" +
            "  }\n" +
            "}\n"
    }
}
