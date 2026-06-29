package bridgegen

import bridgegen.generators.AndroidGenerator
import bridgegen.generators.SwiftGenerator
import bridgegen.generators.TypeScriptGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

abstract class GenerateBridgeTask : DefaultTask() {

    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val frameworkName: Property<String>

    @get:Input
    abstract val kmpPackageName: Property<String>

    @get:Input
    abstract val moduleName: Property<String>

    @TaskAction
    fun generate() {
        val ktFiles = sourceDir.asFileTree.matching { include("**/*.kt") }.files.toList()
        val parsed = KmpApiParser().parseFiles(ktFiles)
        val classes = parsed.classes
        val enums = parsed.enums

        if (classes.isEmpty()) {
            logger.warn("Bridge generator: no public classes with functions found in ${sourceDir.get()}")
            return
        }

        logger.lifecycle("Bridge generator: found ${classes.size} class(es): ${classes.map { it.name }}")
        if (enums.isNotEmpty()) {
            logger.lifecycle("Bridge generator: found ${enums.size} enum(s): ${enums.map { it.name }}")
        }

        val out = outputDir.get().asFile
        val fw = frameworkName.get()
        val pkg = kmpPackageName.get()
        val enumNames = enums.map { it.name }.toSet()

        // One native module file per KMP class — auto-discovered by expo-module-gradle-plugin (Android)
        // and by the podspec *.swift glob (iOS). No separate registration file needed.
        for (cls in classes) {
            writeFile(File(out, "ios/${cls.name}Module.swift"), SwiftGenerator.generate(cls, fw, enumNames))
            writeFile(File(out, "android/${cls.name}Module.kt"), AndroidGenerator.generate(cls, pkg, enumNames))
            writeFile(File(out, "ts/src/${cls.name}Module.ts"), TypeScriptGenerator.generateNativeModuleDeclaration(cls))
        }
        writeFile(File(out, "ts/index.ts"), TypeScriptGenerator.generateIndex(classes, enums))
        // expo-module.config.json must list every native module class explicitly —
        // the Expo toolchain reads this at build time to generate ExpoModulesProvider.
        writeFile(File(out, "expo-module.config.json"), buildExpoModuleConfig(classes))

        logger.lifecycle("Bridge generator: output written to ${out.absolutePath}")
    }

    private fun buildExpoModuleConfig(classes: List<ClassInfo>): String {
        val appleList  = classes.joinToString(",\n") { """      "${it.name}Module"""" }
        val androidList = classes.joinToString(",\n") { """      "expo.modules.kmpbridge.${it.name}Module"""" }
        return """{
  "platforms": ["apple", "android"],
  "apple": {
    "modules": [
$appleList
    ]
  },
  "android": {
    "modules": [
$androidList
    ]
  }
}
"""
    }

    private fun writeFile(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
