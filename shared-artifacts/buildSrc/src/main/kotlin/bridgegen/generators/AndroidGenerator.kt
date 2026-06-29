package bridgegen.generators

import bridgegen.*

object AndroidGenerator {

    /** Generates one self-contained Expo Module file for a single KMP class. */
    fun generate(cls: ClassInfo, kmpPackageName: String, enumNames: Set<String>): String {
        val sb = StringBuilder()

        val flows = cls.functions.filter { it.kind == FunctionKind.FLOW }
        val hasSuspend = cls.functions.any { it.kind == FunctionKind.SUSPEND }
        val hasFlows = flows.isNotEmpty()
        val eventNames = flows.map { "on${it.flowBaseName.cap()}Update" }
        val androidPkg = "expo.modules.kmpbridge"
        val usedEnumNames = enumNames.filter { eName -> cls.functions.any { fn -> fn.referencesType(eName) } }
        val instance = cls.name.decap()

        sb.appendLine(HEADER)
        sb.appendLine("package $androidPkg")
        sb.appendLine()

        sb.appendLine("import $kmpPackageName.${cls.name}")
        for (eName in usedEnumNames) sb.appendLine("import $kmpPackageName.$eName")
        if (hasSuspend || hasFlows) sb.appendLine("import expo.modules.kotlin.Promise")
        sb.appendLine("import expo.modules.kotlin.modules.Module")
        sb.appendLine("import expo.modules.kotlin.modules.ModuleDefinition")
        if (hasSuspend || hasFlows) {
            sb.appendLine("import kotlinx.coroutines.CoroutineScope")
            sb.appendLine("import kotlinx.coroutines.Dispatchers")
            sb.appendLine("import kotlinx.coroutines.Job")
            sb.appendLine("import kotlinx.coroutines.SupervisorJob")
            sb.appendLine("import kotlinx.coroutines.cancel")
            sb.appendLine("import kotlinx.coroutines.launch")
        }

        sb.appendLine()
        sb.appendLine("class ${cls.name}Module : Module() {")
        sb.appendLine("  private val $instance = ${cls.name}()")
        if (hasSuspend || hasFlows) {
            sb.appendLine("  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())")
        }
        if (hasFlows) {
            val enumCases = flows.joinToString(", ") { it.flowBaseName.toSnakeUpperCase() }
            sb.appendLine("  private enum class FlowKey { $enumCases }")
            sb.appendLine("  private val flowJobs = mutableMapOf<FlowKey, Job>()")
        }

        sb.appendLine()
        sb.appendLine("  override fun definition() = ModuleDefinition {")
        sb.appendLine("""    Name("${cls.name}")""")

        if (eventNames.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("""    Events(${eventNames.joinToString(", ") { "\"$it\"" }})""")
        }

        if (hasSuspend || hasFlows) {
            sb.appendLine()
            sb.appendLine("    OnDestroy {")
            sb.appendLine("      scope.cancel()")
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

        return sb.toString()
    }

    private fun syncFunction(fn: FunctionInfo, instance: String, enumNames: Set<String>): String {
        val sb = StringBuilder()
        // Enums cross the bridge as their case-name String.
        val ret = if (TypeMapping.isEnum(fn.returnType, enumNames)) ".name" else ""
        sb.append(formatComment(fn.docComment))
        if (fn.params.isEmpty()) {
            sb.appendLine("""    Function("${fn.name}") {""")
            sb.appendLine("      $instance.${fn.name}()$ret")
        } else {
            val paramList = fn.params.joinToString(", ") { "${it.name}: ${TypeMapping.androidBridgeParamType(it.type, enumNames)}" }
            val callArgs = fn.params.joinToString(", ") { TypeMapping.androidCallArg(it.name, it.type, enumNames) }
            sb.appendLine("""    Function("${fn.name}") { $paramList ->""")
            sb.appendLine("      $instance.${fn.name}($callArgs)$ret")
        }
        sb.appendLine("    }")
        return sb.toString()
    }

    private fun suspendFunction(fn: FunctionInfo, instance: String, enumNames: Set<String>): String {
        val sb = StringBuilder()
        sb.append(formatComment(fn.docComment))
        val errorTag = "${fn.name.toSnakeUpperCase()}_ERROR"
        val ret = if (TypeMapping.isEnum(fn.returnType, enumNames)) ".name" else ""
        val paramList = (fn.params.map { "${it.name}: ${TypeMapping.androidBridgeParamType(it.type, enumNames)}" } + listOf("promise: Promise"))
            .joinToString(", ")
        val callArgs = fn.params.joinToString(", ") { TypeMapping.androidCallArg(it.name, it.type, enumNames) }

        sb.appendLine("""    AsyncFunction("${fn.name}") { $paramList ->""")
        sb.appendLine("      scope.launch {")
        sb.appendLine("        try {")
        sb.appendLine("          promise.resolve($instance.${fn.name}($callArgs)$ret)")
        sb.appendLine("        } catch (e: Exception) {")
        sb.appendLine("""          promise.reject("$errorTag", e.message, e)""")
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
        val enumKey = "FlowKey.${base.toSnakeUpperCase()}"
        val emit = if (TypeMapping.isEnum(fn.returnType, enumNames)) "value.name" else "value"

        sb.append(formatComment(fn.docComment))
        sb.appendLine("""    Function("start$Cap") {""")
        sb.appendLine("      flowJobs[$enumKey]?.cancel()")
        sb.appendLine("      flowJobs[$enumKey] = scope.launch {")
        sb.appendLine("        $instance.${fn.name}().collect { value ->")
        sb.appendLine("""          sendEvent("$eventName", mapOf("value" to $emit))""")
        sb.appendLine("        }")
        sb.appendLine("      }")
        sb.appendLine("    }")
        sb.appendLine()
        sb.appendLine("""    Function("stop$Cap") {""")
        sb.appendLine("      flowJobs[$enumKey]?.cancel()")
        sb.appendLine("      flowJobs.remove($enumKey)")
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

/** Converts camelCase to SNAKE_UPPER_CASE, e.g. "delayedEcho" → "DELAYED_ECHO". */
private fun String.toSnakeUpperCase() = replace(Regex("([A-Z])"), "_$1").uppercase().trimStart('_')

private const val HEADER = "// AUTO-GENERATED by shared-artifacts bridge generator. DO NOT EDIT.\n" +
    "// Re-run `bash scripts/push-bridges.sh` from shared-artifacts to regenerate."
