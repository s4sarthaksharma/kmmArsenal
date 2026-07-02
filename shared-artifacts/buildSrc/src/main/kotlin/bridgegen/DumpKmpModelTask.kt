package bridgegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that reads a compiled KMP `.klib` file and dumps the parsed [KmpModule] as a
 * human-readable tree to [outputFile] via [KmpModelPrinter].
 *
 * No hardcoding — the model is produced entirely from the klib by [KlibApiReader].
 *
 * Run with:
 * ```
 * ./gradlew :shared-artifacts:dumpKmpModel
 * ```
 * Then open `build/kmp-model-dump.txt` and compare it against the fixture source.
 */
abstract class DumpKmpModelTask : DefaultTask() {

    /**
     * The `commonMain` metadata klib directory produced by the `metadataCommonMainClasses` task.
     * Typically at `<shared>/build/classes/kotlin/metadata/commonMain/`.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val klibDir: DirectoryProperty

    /** The Kotlin package whose declarations should be included (e.g. `"com.example.shared"`). */
    @get:Input
    abstract val targetPackage: Property<String>

    /** Destination file for the printed model tree. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun dump() {
        val klib = klibDir.get().asFile
        val pkg  = targetPackage.get()

        logger.lifecycle("Reading klib: ${klib.absolutePath}")
        val module = KlibApiReader.read(klib, pkg)

        val text = KmpModelPrinter.print(module)
        val out  = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(text)

        logger.lifecycle("KMP model dump → ${out.absolutePath}")
        logger.lifecycle("Files: ${module.files.size}  Declarations: ${module.declarations.size}")
    }
}
