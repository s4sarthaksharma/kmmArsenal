package bridgegen.generators

import bridgegen.*

object SwiftGenerator {

    /** Generates one self-contained Expo Module file for a single KMP class. */
    fun generate(cls: ClassInfo, frameworkName: String, enumNames: Set<String>): String {
        val sb = StringBuilder()

        val flows = cls.functions.filter { it.kind == FunctionKind.FLOW }
        val hasFlows = flows.isNotEmpty()
        val eventNames = flows.map { "on${it.flowBaseName.cap()}Update" }
        val instance = cls.name.decap()
        // Only enums used as parameters need a String→enum decode helper in this file.
        val decodedEnumNames = enumNames.filter { eName ->
            cls.functions.any { fn -> fn.params.any { it.type.trim() == eName } }
        }

        sb.appendLine(HEADER)
        sb.appendLine("import ExpoModulesCore")
        sb.appendLine("import $frameworkName")
        sb.appendLine()
        sb.appendLine("public class ${cls.name}Module: Module {")
        sb.appendLine("  private let $instance = ${cls.name}()")
        if (hasFlows) {
            val enumCases = "case " + flows.joinToString(", ") { it.flowBaseName }
            sb.appendLine("  private enum FlowKey { $enumCases }")
            sb.appendLine("  private var flowTasks: [FlowKey: Task<Void, Never>] = [:]")
        }

        sb.appendLine()
        sb.appendLine("  public func definition() -> ModuleDefinition {")
        sb.appendLine("""    Name("${cls.name}")""")

        if (eventNames.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("""    Events(${eventNames.joinToString(", ") { "\"$it\"" }})""")
        }

        if (hasFlows) {
            sb.appendLine()
            sb.appendLine("    OnDestroy {")
            sb.appendLine("      self.flowTasks.values.forEach { \$0.cancel() }")
            sb.appendLine("    }")
        }

        for (fn in cls.functions) {
            sb.appendLine()
            when (fn.kind) {
                FunctionKind.SYNC -> sb.append(syncFunction(fn, instance, enumNames))
                FunctionKind.SUSPEND -> sb.append(suspendFunction(fn, instance, enumNames))
                FunctionKind.FLOW -> sb.append(flowFunctions(fn, instance, enumNames))
            }
        }

        sb.appendLine("  }")
        sb.appendLine("}")

        // String -> KMP enum decoders. NOTE: relies on SKIE/Kotlin-Native exposing `entries` on the
        // bridged enum — verify against an actual iOS build.
        for (eName in decodedEnumNames) {
            sb.appendLine()
            sb.appendLine("fileprivate func decode${eName}(_ raw: String) -> ${eName} {")
            sb.appendLine("  return ${eName}.allCases.first(where: { \$0.name == raw }) ?? ${eName}.allCases[0]")
            sb.appendLine("}")
        }

        return sb.toString()
    }

    private fun syncFunction(fn: FunctionInfo, instance: String, enumNames: Set<String>): String {
        val sb = StringBuilder()
        // Enums cross the bridge as their case-name String.
        val ret = if (TypeMapping.isEnum(fn.returnType, enumNames)) ".name" else ""
        sb.append(formatComment(fn.docComment))
        if (fn.params.isEmpty()) {
            sb.appendLine("""    Function("${fn.name}") {""")
            sb.appendLine("      return self.$instance.${fn.name}()$ret")
        } else {
            val paramList = fn.params.joinToString(", ") { "${it.name}: ${TypeMapping.swiftBridgeParamType(it.type, enumNames)}" }
            val callArgs = fn.params.joinToString(", ") { "${it.name}: ${TypeMapping.swiftCallArg(it.name, it.type, enumNames)}" }
            sb.appendLine("""    Function("${fn.name}") { ($paramList) in""")
            sb.appendLine("      return self.$instance.${fn.name}($callArgs)$ret")
        }
        sb.appendLine("    }")
        return sb.toString()
    }

    private fun suspendFunction(fn: FunctionInfo, instance: String, enumNames: Set<String>): String {
        val sb = StringBuilder()
        sb.append(formatComment(fn.docComment))
        val ret = if (TypeMapping.isEnum(fn.returnType, enumNames)) ".name" else ""
        val allParams = fn.params.map { "${it.name}: ${TypeMapping.swiftBridgeParamType(it.type, enumNames)}" } + listOf("promise: Promise")
        val paramList = allParams.joinToString(", ")
        val callArgs = fn.params.joinToString(", ") { "${it.name}: ${TypeMapping.swiftCallArg(it.name, it.type, enumNames)}" }

        sb.appendLine("""    AsyncFunction("${fn.name}") { ($paramList) in""")
        sb.appendLine("      Task { [weak self] in")
        sb.appendLine("        guard let self else { return }")
        sb.appendLine("        do {")
        sb.appendLine("          let result = try await self.$instance.${fn.name}($callArgs)$ret")
        sb.appendLine("          promise.resolve(result)")
        sb.appendLine("        } catch {")
        sb.appendLine("          promise.reject(error)")
        sb.appendLine("        }")
        sb.appendLine("      }")
        sb.appendLine("    }")
        return sb.toString()
    }

    private fun flowFunctions(fn: FunctionInfo, instance: String, enumNames: Set<String>): String {
        val sb = StringBuilder()
        val base = fn.flowBaseName
        val Cap = base.cap()
        val eventName = "on${Cap}Update"
        // Enum elements cross as their case-name String; primitives are unboxed from their NSNumber wrapper.
        val unbox = if (TypeMapping.isEnum(fn.returnType, enumNames)) ".name" else TypeMapping.swiftFlowUnbox(fn.returnType)

        sb.append(formatComment(fn.docComment))
        sb.appendLine("""    Function("start$Cap") {""")
        sb.appendLine("      self.flowTasks[.$base]?.cancel()")
        sb.appendLine("      self.flowTasks[.$base] = Task { [weak self] in")
        sb.appendLine("        guard let self else { return }")
        sb.appendLine("        for await value in self.$instance.${fn.name}() {")
        sb.appendLine("""          self.sendEvent("$eventName", ["value": value$unbox])""")
        sb.appendLine("        }")
        sb.appendLine("      }")
        sb.appendLine("    }")
        sb.appendLine()
        sb.appendLine("""    Function("stop$Cap") {""")
        sb.appendLine("      self.flowTasks[.$base]?.cancel()")
        sb.appendLine("      self.flowTasks[.$base] = nil")
        sb.appendLine("    }")
        return sb.toString()
    }
}

private fun formatComment(docComment: String?, indent: String = "    "): String {
    if (docComment == null) return ""
    val lines = docComment.lines().mapNotNull { line ->
        val content = line.trim()
            .removePrefix("/**").removePrefix("*/").removePrefix("/*")
            .let {
                when {
                    it.startsWith("* ") -> it.drop(2)
                    it.startsWith("*") -> it.drop(1).trimStart()
                    it.startsWith("// ") -> it.drop(3)
                    it.startsWith("//") -> it.drop(2).trimStart()
                    else -> it
                }
            }.trim()
        if (content.isBlank()) null else "$indent// $content"
    }
    return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
}

private fun String.cap() = replaceFirstChar { it.uppercase() }
private fun String.decap() = replaceFirstChar { it.lowercase() }

private const val HEADER = "// AUTO-GENERATED by shared-artifacts bridge generator. DO NOT EDIT.\n" +
    "// Re-run `bash scripts/push-bridges.sh` from shared-artifacts to regenerate.\n"
