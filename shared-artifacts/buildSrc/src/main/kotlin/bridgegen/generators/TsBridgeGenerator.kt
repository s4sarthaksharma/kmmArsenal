package bridgegen.generators

import bridgegen.*

private const val MAX_EXPO_FUNCTION_PARAMS = 8

object TsBridgeGenerator {

    fun generate(sourceFile: KmpSourceFile, module: KmpModule, onSkip: (String) -> Unit = {}): String {
        val enums    = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpEnum>()
        val datas    = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>()
        val sealeds  = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>()
        val classes  = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpClass>()
            .filter { cls ->
                when {
                    cls.isAbstract -> { onSkip("CLASS SKIPPED: ${cls.name} — abstract classes are not bridged."); false }
                    cls.functions.isEmpty() -> { onSkip("CLASS SKIPPED: ${cls.name} — no functions to bridge."); false }
                    else -> true
                }
            }
        val objects  = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpObject>()
            .filter { obj ->
                if (obj.functions.isEmpty()) { onSkip("OBJECT SKIPPED: ${obj.name} — no functions to bridge."); false }
                else true
            }
        val filescopes = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpFileScope>()
            .filter { scope ->
                if (scope.functions.isEmpty()) { onSkip("FILE SCOPE SKIPPED: ${scope.fileName} — no functions to bridge."); false }
                else true
            }
        sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpInterface>()
            .forEach { onSkip("CLASS SKIPPED: ${it.name} — interfaces are not bridged.") }
        for (dc in datas) {
            for (fn in dc.functions) {
                onSkip("FUNCTION SKIPPED: ${dc.name}.${fn.name}() — member functions on data classes are not bridged.")
            }
        }
        for (sealed in sealeds) {
            for (fn in sealed.functions) {
                onSkip("FUNCTION SKIPPED: ${sealed.name}.${fn.name}() — member functions on sealed classes are not bridged.")
            }
        }

        val hasBridgeable = classes.isNotEmpty() || objects.isNotEmpty() || filescopes.isNotEmpty()
        if (enums.isEmpty() && datas.isEmpty() && sealeds.isEmpty() && !hasBridgeable) return ""

        val enumNames   = module.declarations.filterIsInstance<KmpDeclaration.KmpEnum>().map { it.name }.toSet()
        val dataNames   = module.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>().map { it.name }.toSet()
        val sealedNames = module.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>().map { it.name }.toSet()

        return buildString {
            appendLine(HEADER)

            if (hasBridgeable) {
                appendLine()
                appendLine("import { requireNativeModule } from 'expo-modules-core';")
            }


            // 1. Enums
            for (e in enums) {
                appendLine()
                appendLine("export enum ${e.name} {")
                for (entry in e.entries) appendLine("  $entry = \"$entry\",")
                appendLine("}")
            }

            // 2. Data class types
            for (d in datas) {
                appendLine()
                appendLine("export type ${d.name} = {")
                for (field in d.fields) {
                    appendLine("  ${field.name}: ${field.type.toTsType(enumNames, dataNames, sealedNames)}")
                }
                appendLine("}")
            }

            // 3. Sealed class discriminated unions
            for (s in sealeds) {
                appendLine()
                appendLine("export type ${s.name} =")
                for (variant in s.variants) {
                    val fields = when (variant) {
                        is KmpVariant.DataVariant   -> variant.fields
                        is KmpVariant.ClassVariant  -> variant.fields
                        is KmpVariant.ObjectVariant -> emptyList()
                    }
                    val variantName = when (variant) {
                        is KmpVariant.DataVariant   -> variant.name
                        is KmpVariant.ClassVariant  -> variant.name
                        is KmpVariant.ObjectVariant -> variant.name
                    }
                    if (fields.isEmpty()) {
                        appendLine("  | { type: \"$variantName\" }")
                    } else {
                        val fieldStr = fields.joinToString("; ") { f ->
                            "${f.name}: ${f.type.toTsType(enumNames, dataNames, sealedNames)}"
                        }
                        appendLine("  | { type: \"$variantName\"; $fieldStr }")
                    }
                }
            }

            if (!hasBridgeable) return@buildString

            // 4. requireNativeModule instances (one per bridgeable class / object / file scope)
            val takenNames = (classes.map { it.name } + objects.map { it.name }).toSet()
            fun scopeName(scope: KmpDeclaration.KmpFileScope) =
                if (scope.fileName in takenNames) "${scope.fileName}Kt" else scope.fileName

            appendLine()
            appendLine("// ── Native module instances ───────────────────────────────────────────────────")
            appendLine()
            for (cls in classes) appendLine("const _${cls.name} = requireNativeModule('${cls.name}');")
            for (obj in objects) appendLine("const _${obj.name} = requireNativeModule('${obj.name}');")
            for (scope in filescopes) appendLine("const _${scopeName(scope)} = requireNativeModule('${scopeName(scope)}');")

            // 5. Classes — instance handle wrapper (TS class with private handle)
            for (cls in classes) {
                appendLine()
                appendLine("export class ${cls.name} {")
                appendLine("  private constructor(private readonly _handle: string) {}")
                appendLine()
                appendLine("  static create(): ${cls.name} {")
                appendLine("    return new ${cls.name}(_${cls.name}.create())")
                appendLine("  }")
                appendLine()
                appendLine("  destroy(): void { _${cls.name}.destroy(this._handle) }")
                for (fn in cls.functions) {
                    appendLine()
                    appendInstanceWrapperFunction(fn, cls.name, enumNames, dataNames, sealedNames, onSkip)
                }
                appendLine("}")
            }

            // 6. Objects — flat const wrapper (unchanged singleton pattern)
            for (obj in objects) {
                appendLine()
                appendLine("export const ${obj.name} = {")
                for (fn in obj.functions) {
                    appendWrapperFunction(fn, obj.name, enumNames, dataNames, sealedNames, onSkip)
                }
                appendLine("};")
            }

            // 7. File scopes — flat const wrapper (same pattern as objects)
            for (scope in filescopes) {
                val sName = scopeName(scope)
                appendLine()
                appendLine("export const $sName = {")
                for (fn in scope.functions) {
                    appendWrapperFunction(fn, sName, enumNames, dataNames, sealedNames, onSkip)
                }
                appendLine("};")
            }
        }
    }

    private fun StringBuilder.appendWrapperFunction(
        fn: KmpFunction,
        moduleName: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        onSkip: (String) -> Unit = {},
    ) {
        val native = "_$moduleName"
        when (fn.kind) {
            FunctionKind.SYNC -> {
                if (fn.params.size > MAX_EXPO_FUNCTION_PARAMS) {
                    onSkip("FUNCTION SKIPPED: $moduleName.${fn.name}() — too many params (${fn.params.size} > $MAX_EXPO_FUNCTION_PARAMS).")
                    return
                }
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)}"
                }
                val ret  = fn.returnType.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)
                val args = fn.params.joinToString(", ") { it.name }
                appendLine("  ${fn.name}: ($params): $ret => $native.${fn.name}($args),")
            }
            FunctionKind.SUSPEND -> {
                if (fn.params.size > MAX_EXPO_FUNCTION_PARAMS) {
                    onSkip("FUNCTION SKIPPED: $moduleName.${fn.name}() — too many params (${fn.params.size} > $MAX_EXPO_FUNCTION_PARAMS).")
                    return
                }
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)}"
                }
                val ret  = fn.returnType.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)
                val args = fn.params.joinToString(", ") { it.name }
                appendLine("  ${fn.name}: ($params): Promise<$ret> => $native.${fn.name}($args),")
            }
            FunctionKind.FLOW -> {
                val base = fn.flowBaseName
                val cap  = base.replaceFirstChar { it.uppercase() }
                val valueType = fn.returnType.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)
                appendLine("  start$cap: (): void => $native.start$cap(),")
                appendLine("  stop$cap: (): void => $native.stop$cap(),")
                appendLine("  add${cap}Listener: (handler: (event: { value: $valueType }) => void) =>")
                appendLine("    $native.addListener('on${cap}Update', handler),")
            }
        }
    }

    private fun StringBuilder.appendInstanceWrapperFunction(
        fn: KmpFunction,
        moduleName: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        onSkip: (String) -> Unit = {},
    ) {
        val native = "_$moduleName"
        when (fn.kind) {
            FunctionKind.SYNC -> {
                if (fn.params.size + 1 > MAX_EXPO_FUNCTION_PARAMS) {
                    onSkip("FUNCTION SKIPPED: $moduleName.${fn.name}() — too many params (${fn.params.size} + handle > $MAX_EXPO_FUNCTION_PARAMS).")
                    return
                }
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)}"
                }
                val ret  = fn.returnType.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)
                val args = (listOf("this._handle") + fn.params.map { it.name }).joinToString(", ")
                appendLine("  ${fn.name}($params): $ret { return $native.${fn.name}($args) }")
            }
            FunctionKind.SUSPEND -> {
                if (fn.params.size + 1 > MAX_EXPO_FUNCTION_PARAMS) {
                    onSkip("FUNCTION SKIPPED: $moduleName.${fn.name}() — too many params (${fn.params.size} + handle > $MAX_EXPO_FUNCTION_PARAMS).")
                    return
                }
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)}"
                }
                val ret  = fn.returnType.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)
                val args = (listOf("this._handle") + fn.params.map { it.name }).joinToString(", ")
                appendLine("  ${fn.name}($params): Promise<$ret> { return $native.${fn.name}($args) }")
            }
            FunctionKind.FLOW -> {
                val base = fn.flowBaseName
                val cap  = base.replaceFirstChar { it.uppercase() }
                val valueType = fn.returnType.toTsType(enumNames, dataNames, sealedNames, wrapperMode = true)
                appendLine()
                appendLine("  start$cap(): void { $native.start$cap(this._handle) }")
                appendLine("  stop$cap(): void { $native.stop$cap(this._handle) }")
                appendLine("  add${cap}Listener(handler: (event: { value: $valueType }) => void) {")
                appendLine("    return $native.addListener('on${cap}Update', (e: any) => {")
                appendLine("      if (e.instanceId === this._handle) handler({ value: e.value })")
                appendLine("    })")
                appendLine("  }")
            }
        }
    }

    private fun KmpTypeRef.toTsType(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        wrapperMode: Boolean = false,
    ): String {
        val base = when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "string"
                PrimitiveKind.BOOLEAN -> "boolean"
                PrimitiveKind.CHAR    -> "string"
                else                  -> "number"
            }
            this is KmpTypeRef.UnitType -> "void"
            // In wrapperMode expose the TS enum name; in type-declaration mode use plain string
            // (interfaces carry the raw bridge string, the wrapper casts to the typed enum).
            this is KmpTypeRef.ClassRef && simpleName in enumNames   -> if (wrapperMode) simpleName else "string"
            this is KmpTypeRef.ClassRef && simpleName in dataNames   -> simpleName
            this is KmpTypeRef.ClassRef && simpleName in sealedNames -> simpleName
            this is KmpTypeRef.ClassRef -> "unknown"
            this is KmpTypeRef.CollectionType -> when (kind) {
                CollectionKind.LIST, CollectionKind.SET -> {
                    val elem = (typeArgs.firstOrNull() as? KmpTypeArg.Invariant)
                        ?.type?.toTsType(enumNames, dataNames, sealedNames, wrapperMode) ?: "unknown"
                    "$elem[]"
                }
                CollectionKind.MAP -> {
                    val key = (typeArgs.getOrNull(0) as? KmpTypeArg.Invariant)
                        ?.type?.toTsType(enumNames, dataNames, sealedNames, wrapperMode) ?: "unknown"
                    val value = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)
                        ?.type?.toTsType(enumNames, dataNames, sealedNames, wrapperMode) ?: "unknown"
                    "{ [key: $key]: $value }"
                }
            }
            this is KmpTypeRef.FlowType -> "unknown"
            this is KmpTypeRef.TypeParam -> "unknown"
            else -> "unknown"
        }
        val nullable = when (this) {
            is KmpTypeRef.Primitive      -> nullable
            is KmpTypeRef.ClassRef       -> nullable
            is KmpTypeRef.CollectionType -> nullable
            is KmpTypeRef.UnitType       -> nullable
            is KmpTypeRef.FlowType       -> nullable
            is KmpTypeRef.TypeParam      -> nullable
        }
        return if (nullable) "$base | null" else base
    }
}

private const val HEADER = "// AUTO-GENERATED by shared-artifacts bridge generator. DO NOT EDIT.\n" +
    "// Re-run `bash scripts/push-bridges.sh` from shared-artifacts to regenerate."
