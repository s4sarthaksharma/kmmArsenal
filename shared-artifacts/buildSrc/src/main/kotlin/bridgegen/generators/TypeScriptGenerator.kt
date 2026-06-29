package bridgegen.generators

import bridgegen.*

object TypeScriptGenerator {

    /**
     * Generates src/GreetingModule.ts — the internal NativeModule declaration for one KMP class.
     * Enum types are represented as `string` here (what native actually sends); the public index.ts
     * wrapper re-types them as the proper TS enum.
     */
    fun generateNativeModuleDeclaration(cls: ClassInfo): String {
        val sb = StringBuilder()
        val flows = cls.functions.filter { it.kind == FunctionKind.FLOW }
        val clsName = cls.name

        sb.appendLine(HEADER)
        sb.appendLine("import { NativeModule, requireNativeModule } from 'expo';")
        sb.appendLine()

        if (flows.isNotEmpty()) {
            sb.appendLine("type ${clsName}Events = {")
            for (fn in flows) {
                // Flow element type at the native boundary is always string/number/boolean — no enum types here.
                val valueType = TypeMapping.tsType(fn.returnType)
                sb.appendLine("  on${fn.flowBaseName.cap()}Update: (event: { value: $valueType }) => void;")
            }
            sb.appendLine("};")
        } else {
            sb.appendLine("type ${clsName}Events = Record<string, never>;")
        }

        sb.appendLine()
        sb.appendLine("declare class ${clsName}Module extends NativeModule<${clsName}Events> {")

        for (fn in cls.functions) {
            when (fn.kind) {
                FunctionKind.SYNC -> {
                    // Enum params/returns are `string` at the NativeModule boundary (raw case name from native).
                    val params = fn.params.joinToString(", ") { "${it.name}: ${TypeMapping.tsType(it.type)}" }
                    val ret = TypeMapping.tsType(fn.returnType)
                    sb.append(formatComment(fn.docComment, "  "))
                    sb.appendLine("  ${fn.name}($params): $ret;")
                }
                FunctionKind.SUSPEND -> {
                    val params = fn.params.joinToString(", ") { "${it.name}: ${TypeMapping.tsType(it.type)}" }
                    val ret = TypeMapping.tsType(fn.returnType)
                    sb.append(formatComment(fn.docComment, "  "))
                    sb.appendLine("  ${fn.name}($params): Promise<$ret>;")
                }
                FunctionKind.FLOW -> {
                    val Cap = fn.flowBaseName.cap()
                    sb.append(formatComment(fn.docComment, "  "))
                    sb.appendLine("  start$Cap(): void;")
                    sb.appendLine("  stop$Cap(): void;")
                }
            }
        }

        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("export default requireNativeModule<${clsName}Module>('${clsName}');")

        return sb.toString()
    }

    /**
     * Generates index.ts — the public API surface exported from the npm package.
     * Each KMP class becomes an exported const object (e.g. `Greeting`, `Calculator`).
     * Enums are declared and exported here with proper string-valued TS enum syntax.
     * Flow functions include a typed `addXxxListener` convenience alongside start/stop.
     */
    fun generateIndex(classes: List<ClassInfo>, enums: List<EnumInfo>): String {
        val sb = StringBuilder()
        val enumNames = enums.map { it.name }.toSet()

        sb.appendLine(HEADER)

        // Import each class's internal native module.
        for (cls in classes) {
            sb.appendLine("import ${cls.name}Native from './src/${cls.name}Module';")
        }
        sb.appendLine()

        // Enum declarations — string-valued so runtime value equals the native case name.
        for (e in enums) {
            sb.appendLine("export enum ${e.name} {")
            for (case in e.cases) {
                sb.appendLine("  $case = '$case',")
            }
            sb.appendLine("}")
            sb.appendLine()
        }

        // One exported const object per KMP class.
        for (cls in classes) {
            val native = "${cls.name}Native"
            sb.appendLine("export const ${cls.name} = {")

            for (fn in cls.functions) {
                when (fn.kind) {
                    FunctionKind.SYNC -> {
                        val params = fn.params.joinToString(", ") { "${it.name}: ${TypeMapping.tsType(it.type, enumNames)}" }
                        val ret = TypeMapping.tsType(fn.returnType, enumNames)
                        val args = fn.params.joinToString(", ") { it.name }
                        // Enum returns arrive as string from native; cast to the enum type.
                        val call = if (TypeMapping.isEnum(fn.returnType, enumNames))
                            "$native.${fn.name}($args) as ${fn.returnType.trim()}"
                        else "$native.${fn.name}($args)"
                        sb.append(formatComment(fn.docComment, "  "))
                        sb.appendLine("  ${fn.name}: ($params): $ret => $call,")
                    }
                    FunctionKind.SUSPEND -> {
                        val params = fn.params.joinToString(", ") { "${it.name}: ${TypeMapping.tsType(it.type, enumNames)}" }
                        val ret = TypeMapping.tsType(fn.returnType, enumNames)
                        val args = fn.params.joinToString(", ") { it.name }
                        val call = if (TypeMapping.isEnum(fn.returnType, enumNames))
                            "$native.${fn.name}($args).then(v => v as ${fn.returnType.trim()})"
                        else "$native.${fn.name}($args)"
                        sb.append(formatComment(fn.docComment, "  "))
                        sb.appendLine("  ${fn.name}: ($params): Promise<$ret> => $call,")
                    }
                    FunctionKind.FLOW -> {
                        val base = fn.flowBaseName
                        val Cap = base.cap()
                        val valueType = TypeMapping.tsType(fn.returnType, enumNames)
                        val isEnum = TypeMapping.isEnum(fn.returnType, enumNames)
                        sb.append(formatComment(fn.docComment, "  "))
                        sb.appendLine("  start$Cap: (): void => $native.start$Cap(),")
                        sb.appendLine("  stop$Cap: (): void => $native.stop$Cap(),")
                        // Typed listener — casts enum event values from string to the enum type.
                        if (isEnum) {
                            sb.appendLine("  add${Cap}Listener: (handler: (event: { value: $valueType }) => void) =>")
                            sb.appendLine("    $native.addListener('on${Cap}Update', (e) => handler({ value: e.value as ${fn.returnType.trim()} })),")
                        } else {
                            sb.appendLine("  add${Cap}Listener: (handler: (event: { value: $valueType }) => void) =>")
                            sb.appendLine("    $native.addListener('on${Cap}Update', handler),")
                        }
                    }
                }
            }

            sb.appendLine("};")
            sb.appendLine()
        }

        return sb.toString()
    }
}

private fun formatComment(docComment: String?, indent: String = ""): String {
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
        if (content.isBlank()) null else "${indent}// $content"
    }
    return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
}

private fun String.cap() = replaceFirstChar { it.uppercase() }

private const val HEADER = "// AUTO-GENERATED by shared-artifacts bridge generator. DO NOT EDIT.\n" +
    "// Re-run `bash scripts/push-bridges.sh` from shared-artifacts to regenerate.\n"
