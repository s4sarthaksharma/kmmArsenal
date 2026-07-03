package bridgegen.generators

import bridgegen.*

/** Expo's Function/AsyncFunction DSL has overloads up to this many parameters. */
private const val MAX_EXPO_FUNCTION_PARAMS = 8

/**
 * Generates the TypeScript surface for one KMP source file: exported type declarations (enums,
 * data class object types, sealed discriminated unions, type-only interfaces) plus the runtime
 * `requireNativeModule` wrappers that call into the native Android/iOS bridge modules generated
 * by `AndroidGenerator` / `SwiftGenerator`.
 *
 * All generated TS lives in a single file per KMP source file, split into two halves: type
 * declarations first (so they can be imported even from a consumer that never calls into the
 * native module), then runtime wrappers gated on there being anything bridgeable at all.
 */
object TsBridgeGenerator {

    /**
     * Generates the full TypeScript file for [sourceFile].
     *
     * Interfaces/abstract classes with at least one function become runtime-backed wrapper
     * classes (their instances are opaque native handles, see [appendInstanceWrapperFunction]);
     * those with no functions are emitted as plain type-only `interface` declarations instead,
     * since there is nothing to dispatch through a bridge.
     *
     * @return the complete file text, or `""` if this source file has no bridgeable types or
     *         functions.
     */
    fun generate(sourceFile: KmpSourceFile, module: KmpModule, onSkip: (String) -> Unit = {}): String {
        val enums      = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpEnum>()
        val datas      = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>()
        val sealeds    = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>()
        val interfaces = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpInterface>()
        val abstracts  = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpClass>()
            .filter { it.isAbstract }
        val interfaceModules = (interfaces.filter { it.functions.isNotEmpty() } +
            abstracts.filter { it.functions.isNotEmpty() })
        val interfaceTypesOnly = (interfaces.filter { it.functions.isEmpty() } +
            abstracts.filter { it.functions.isEmpty() })
        val classes    = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpClass>()
            .filter { cls ->
                when {
                    cls.isAbstract -> false
                    cls.functions.isEmpty() -> { onSkip("CLASS SKIPPED: ${cls.name} — no functions to bridge."); false }
                    else -> true
                }
            }
        val objects    = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpObject>()
            .filter { obj ->
                if (obj.functions.isEmpty()) { onSkip("OBJECT SKIPPED: ${obj.name} — no functions to bridge."); false }
                else true
            }
        val filescopes = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpFileScope>()
            .filter { scope ->
                if (scope.functions.isEmpty()) { onSkip("FILE SCOPE SKIPPED: ${scope.fileName} — no functions to bridge."); false }
                else true
            }
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

        val hasBridgeable = classes.isNotEmpty() || objects.isNotEmpty() || filescopes.isNotEmpty() || interfaceModules.isNotEmpty()
        val hasTypes = enums.isNotEmpty() || datas.isNotEmpty() || sealeds.isNotEmpty() ||
            interfaces.isNotEmpty() || abstracts.isNotEmpty()
        if (!hasTypes && !hasBridgeable) return ""

        val enumNames      = module.declarations.filterIsInstance<KmpDeclaration.KmpEnum>().map { it.name }.toSet()
        val dataNames      = module.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>().map { it.name }.toSet()
        val sealedNames    = module.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>().map { it.name }.toSet()
        val interfaceNames = module.declarations.filterIsInstance<KmpDeclaration.KmpInterface>().map { it.name }.toSet()
        val abstractNames  = module.declarations.filterIsInstance<KmpDeclaration.KmpClass>().filter { it.isAbstract }.map { it.name }.toSet()

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
                    appendLine("  ${field.name}: ${field.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames)}")
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
                            "${f.name}: ${f.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames)}"
                        }
                        appendLine("  | { type: \"$variantName\"; $fieldStr }")
                    }
                }
            }

            // 4. Type-only interfaces/abstracts (no functions)
            for (decl in interfaceTypesOnly) {
                val declName = when (decl) {
                    is KmpDeclaration.KmpInterface -> decl.name
                    is KmpDeclaration.KmpClass     -> decl.name
                    else -> continue
                }
                val fns = when (decl) {
                    is KmpDeclaration.KmpInterface -> decl.functions
                    is KmpDeclaration.KmpClass     -> decl.functions
                    else -> emptyList()
                }
                appendLine()
                appendLine("export interface $declName {")
                for (fn in fns) {
                    appendInterfaceMethod(fn, enumNames, dataNames, sealedNames, interfaceNames, abstractNames)
                }
                appendLine("}")
            }

            if (!hasBridgeable) return@buildString

            // 5. requireNativeModule instances (one per bridgeable class / object / file scope / interface module)
            // Interfaces/abstracts claim their module name on the native side even when they have
            // no functions (the registry module is always generated), so count all of them.
            val takenNames = (classes.map { it.name } + objects.map { it.name } +
                interfaces.map { it.name } + abstracts.map { it.name }).toSet()
            fun scopeName(scope: KmpDeclaration.KmpFileScope) =
                if (scope.fileName in takenNames) "${scope.fileName}Kt" else scope.fileName

            appendLine()
            appendLine("// ── Native module instances ───────────────────────────────────────────────────")
            appendLine()
            for (cls in classes) appendLine("const _${cls.name} = requireNativeModule('${cls.name}');")
            for (obj in objects) appendLine("const _${obj.name} = requireNativeModule('${obj.name}');")
            for (scope in filescopes) appendLine("const _${scopeName(scope)} = requireNativeModule('${scopeName(scope)}');")
            for (decl in interfaceModules) {
                val n = when (decl) {
                    is KmpDeclaration.KmpInterface -> decl.name
                    is KmpDeclaration.KmpClass     -> decl.name
                    else -> continue
                }
                appendLine("const _$n = requireNativeModule('$n');")
            }

            // 7. Classes — instance handle wrapper (TS class with private handle)
            for (cls in classes) {
                appendLine()
                appendLine("export class ${cls.name} {")
                appendLine("  /** @internal */ readonly _handle: string")
                appendLine("  private constructor(handle: string) { this._handle = handle }")
                appendLine()
                appendLine("  static create(): ${cls.name} {")
                appendLine("    return new ${cls.name}(_${cls.name}.create())")
                appendLine("  }")
                appendLine()
                appendLine("  destroy(): void { _${cls.name}.destroy(this._handle) }")
                for (fn in cls.functions) {
                    appendLine()
                    appendInstanceWrapperFunction(fn, cls.name, enumNames, dataNames, sealedNames, interfaceNames, abstractNames, onSkip)
                }
                appendLine("}")
            }

            // 7b. Interface/abstract runtime bridge classes (Option A — replace export interface)
            for (decl in interfaceModules) {
                val declName = when (decl) {
                    is KmpDeclaration.KmpInterface -> decl.name
                    is KmpDeclaration.KmpClass     -> decl.name
                    else -> continue
                }
                val fns = when (decl) {
                    is KmpDeclaration.KmpInterface -> decl.functions
                    is KmpDeclaration.KmpClass     -> decl.functions
                    else -> emptyList()
                }
                // create()/resolve<Fn> only exist when the native side can build an anonymous
                // subtype — mirrors AndroidGenerator/SwiftGenerator's isJsImplementable guard.
                val jsImplementable = decl.isJsImplementable()
                if (!jsImplementable) {
                    onSkip("CREATE SKIPPED: $declName — cannot be JS-implemented (${decl.jsImplementabilityGap()}).")
                }
                appendLine()
                appendLine("export class $declName {")
                appendLine("  /** @internal */ readonly _handle: string")
                appendLine("  private constructor(handle: string) { this._handle = handle }")
                appendLine("  /** @internal */")
                appendLine("  static _wrap(handle: string): $declName { return new $declName(handle) }")
                if (jsImplementable) {
                    appendLine("  static create(): $declName { return $declName._wrap(_$declName.create()) }")
                }
                appendLine()
                appendLine("  destroy(): void { _$declName.destroy(this._handle) }")
                for (fn in fns) {
                    appendLine()
                    appendInstanceWrapperFunction(fn, declName, enumNames, dataNames, sealedNames, interfaceNames, abstractNames, onSkip)
                }
                // Task 5: reverse-bridge listener + resolve per SUSPEND method
                for (fn in (if (jsImplementable) fns.filter { it.kind == FunctionKind.SUSPEND } else emptyList())) {
                    val fnCap        = fn.name.replaceFirstChar { it.uppercase() }
                    val eventName    = "call$fnCap"
                    val listenerName = "add${eventName.replaceFirstChar { it.uppercase() }}Listener"
                    val resolveName  = "resolve$fnCap"
                    val handlerFields = buildString {
                        append("callId: string")
                        for (p in fn.params) {
                            val pt = p.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames)
                            append("; ${p.name}: $pt")
                        }
                    }
                    val eFields = buildString {
                        append("callId: e.callId")
                        for (p in fn.params) append(", ${p.name}: e.${p.name}")
                    }
                    val retType = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)
                    val isUnit  = fn.returnType is KmpTypeRef.UnitType
                    appendLine()
                    appendLine("  $listenerName(handler: (event: { $handlerFields }) => void) {")
                    appendLine("    return _$declName.addListener('$eventName', (e: any) => {")
                    appendLine("      if (e.instanceId === this._handle) handler({ $eFields })")
                    appendLine("    })")
                    appendLine("  }")
                    appendLine()
                    if (isUnit) {
                        appendLine("  $resolveName(callId: string): void {")
                        appendLine("    _$declName.$resolveName(this._handle, callId)")
                    } else {
                        appendLine("  $resolveName(callId: string, result: $retType): void {")
                        appendLine("    _$declName.$resolveName(this._handle, callId, result)")
                    }
                    appendLine("  }")
                }
                appendLine("}")
            }

            // 8. Objects — flat const wrapper (unchanged singleton pattern)
            for (obj in objects) {
                appendLine()
                appendLine("export const ${obj.name} = {")
                for (fn in obj.functions) {
                    appendWrapperFunction(fn, obj.name, enumNames, dataNames, sealedNames, interfaceNames, abstractNames, onSkip)
                }
                appendLine("};")
            }

            // 9. File scopes — flat const wrapper (same pattern as objects)
            for (scope in filescopes) {
                val sName = scopeName(scope)
                appendLine()
                appendLine("export const $sName = {")
                for (fn in scope.functions) {
                    appendWrapperFunction(fn, sName, enumNames, dataNames, sealedNames, interfaceNames, abstractNames, onSkip)
                }
                appendLine("};")
            }
        }
    }

    /**
     * Appends one method signature line to a type-only `export interface` body (see [generate],
     * step 4) — for an interface/abstract class with no functions to bridge, so nothing else
     * generates a runtime implementation for it.
     *
     * Sync/suspend methods appear as plain (possibly `Promise`-wrapped) method signatures; a
     * Flow method expands into its `start`/`stop`/`add<Name>Listener` triplet, mirroring the
     * shape the native bridge would expose if this type were ever made runtime-backed.
     */
    private fun StringBuilder.appendInterfaceMethod(
        fn: KmpFunction,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        interfaceNames: Set<String>,
        abstractNames: Set<String>,
    ) {
        when (fn.kind) {
            FunctionKind.SYNC -> {
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames)}"
                }
                val ret = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames)
                appendLine("  ${fn.name}($params): $ret")
            }
            FunctionKind.SUSPEND -> {
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames)}"
                }
                val ret = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames)
                appendLine("  ${fn.name}($params): Promise<$ret>")
            }
            FunctionKind.FLOW -> {
                val base = fn.flowBaseName
                val cap  = base.replaceFirstChar { it.uppercase() }
                val valueType = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames)
                appendLine("  start$cap(): void")
                appendLine("  stop$cap(): void")
                appendLine("  add${cap}Listener(handler: (event: { value: $valueType }) => void): void")
            }
        }
    }

    /**
     * Appends one method to a flat object-literal wrapper (`export const Foo = { ... }`) for an
     * `object` or file-scope module, calling straight through to the `requireNativeModule`
     * handle captured in [moduleName]'s `_<name>` const.
     *
     * Interface/abstract-class parameters are unwrapped to their opaque `_handle` string before
     * the native call, and interface/abstract-class return values are re-wrapped via
     * `<Type>._wrap(id)` after it — mirroring `AndroidGenerator`'s `toReturnSuffix`/`toCallArg`
     * on the Kotlin side.
     *
     * Emits nothing (after reporting via [onSkip]) if the function has more parameters than
     * [MAX_EXPO_FUNCTION_PARAMS].
     */
    private fun StringBuilder.appendWrapperFunction(
        fn: KmpFunction,
        moduleName: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        interfaceNames: Set<String>,
        abstractNames: Set<String>,
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
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)}"
                }
                val ret  = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)
                val args = fn.params.joinToString(", ") { p ->
                    val pRef = p.type as? KmpTypeRef.ClassRef
                    val isIface = pRef != null && (pRef.simpleName in interfaceNames || pRef.simpleName in abstractNames)
                    when { !isIface -> p.name; pRef!!.nullable -> "${p.name}?._handle ?? null"; else -> "${p.name}._handle" }
                }
                val nativeCall = "$native.${fn.name}($args)"
                val retRef = fn.returnType as? KmpTypeRef.ClassRef
                val isIfaceReturn = retRef != null && (retRef.simpleName in interfaceNames || retRef.simpleName in abstractNames)
                val callExpr = when {
                    !isIfaceReturn -> nativeCall
                    retRef!!.nullable -> "{ const _i = $nativeCall; return _i !== null ? ${retRef.simpleName}._wrap(_i) : null }"
                    else -> "${retRef.simpleName}._wrap($nativeCall)"
                }
                appendLine("  ${fn.name}: ($params): $ret => $callExpr,")
            }
            FunctionKind.SUSPEND -> {
                if (fn.params.size > MAX_EXPO_FUNCTION_PARAMS) {
                    onSkip("FUNCTION SKIPPED: $moduleName.${fn.name}() — too many params (${fn.params.size} > $MAX_EXPO_FUNCTION_PARAMS).")
                    return
                }
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)}"
                }
                val ret  = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)
                val args = fn.params.joinToString(", ") { p ->
                    val pRef = p.type as? KmpTypeRef.ClassRef
                    val isIface = pRef != null && (pRef.simpleName in interfaceNames || pRef.simpleName in abstractNames)
                    when { !isIface -> p.name; pRef!!.nullable -> "${p.name}?._handle ?? null"; else -> "${p.name}._handle" }
                }
                val nativeCall = "$native.${fn.name}($args)"
                val retRef = fn.returnType as? KmpTypeRef.ClassRef
                val isIfaceReturn = retRef != null && (retRef.simpleName in interfaceNames || retRef.simpleName in abstractNames)
                val callExpr = when {
                    !isIfaceReturn -> nativeCall
                    retRef!!.nullable -> "$nativeCall.then((id: string | null) => id !== null ? ${retRef.simpleName}._wrap(id) : null)"
                    else -> "$nativeCall.then((id: string) => ${retRef.simpleName}._wrap(id))"
                }
                appendLine("  ${fn.name}: ($params): Promise<$ret> => $callExpr,")
            }
            FunctionKind.FLOW -> {
                if (fn.params.size > MAX_EXPO_FUNCTION_PARAMS) {
                    onSkip("FUNCTION SKIPPED: $moduleName.${fn.name}() — too many params (${fn.params.size} > $MAX_EXPO_FUNCTION_PARAMS).")
                    return
                }
                val base = fn.flowBaseName
                val cap  = base.replaceFirstChar { it.uppercase() }
                val valueType = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)}"
                }
                val args = fn.params.joinToString(", ") { p ->
                    val pRef = p.type as? KmpTypeRef.ClassRef
                    val isIface = pRef != null && (pRef.simpleName in interfaceNames || pRef.simpleName in abstractNames)
                    when { !isIface -> p.name; pRef!!.nullable -> "${p.name}?._handle ?? null"; else -> "${p.name}._handle" }
                }
                appendLine("  start$cap: ($params): void => $native.start$cap($args),")
                appendLine("  stop$cap: (): void => $native.stop$cap(),")
                appendLine("  add${cap}Listener: (handler: (event: { value: $valueType }) => void) =>")
                appendLine("    $native.addListener('on${cap}Update', handler),")
            }
        }
    }

    /**
     * Appends one instance method to a class-based wrapper (`export class Foo { ... }`) for a
     * bridged class or a runtime-backed interface/abstract class, threading `this._handle`
     * through as the leading argument to every native call.
     *
     * Otherwise identical to [appendWrapperFunction] — same interface-handle unwrap/rewrap
     * logic — except the effective parameter count includes the synthetic handle argument when
     * checking against [MAX_EXPO_FUNCTION_PARAMS].
     */
    private fun StringBuilder.appendInstanceWrapperFunction(
        fn: KmpFunction,
        moduleName: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        interfaceNames: Set<String>,
        abstractNames: Set<String>,
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
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)}"
                }
                val ret  = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)
                val args = (listOf("this._handle") + fn.params.map { p ->
                    val pRef = p.type as? KmpTypeRef.ClassRef
                    val isIface = pRef != null && (pRef.simpleName in interfaceNames || pRef.simpleName in abstractNames)
                    when { !isIface -> p.name; pRef!!.nullable -> "${p.name}?._handle ?? null"; else -> "${p.name}._handle" }
                }).joinToString(", ")
                val nativeCall = "$native.${fn.name}($args)"
                val retRef = fn.returnType as? KmpTypeRef.ClassRef
                val isIfaceReturn = retRef != null && (retRef.simpleName in interfaceNames || retRef.simpleName in abstractNames)
                when {
                    !isIfaceReturn -> appendLine("  ${fn.name}($params): $ret { return $nativeCall }")
                    retRef!!.nullable -> appendLine("  ${fn.name}($params): $ret { const _i = $nativeCall; return _i !== null ? ${retRef.simpleName}._wrap(_i) : null }")
                    else -> appendLine("  ${fn.name}($params): $ret { return ${retRef.simpleName}._wrap($nativeCall) }")
                }
            }
            FunctionKind.SUSPEND -> {
                if (fn.params.size + 1 > MAX_EXPO_FUNCTION_PARAMS) {
                    onSkip("FUNCTION SKIPPED: $moduleName.${fn.name}() — too many params (${fn.params.size} + handle > $MAX_EXPO_FUNCTION_PARAMS).")
                    return
                }
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)}"
                }
                val ret  = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)
                val args = (listOf("this._handle") + fn.params.map { p ->
                    val pRef = p.type as? KmpTypeRef.ClassRef
                    val isIface = pRef != null && (pRef.simpleName in interfaceNames || pRef.simpleName in abstractNames)
                    when { !isIface -> p.name; pRef!!.nullable -> "${p.name}?._handle ?? null"; else -> "${p.name}._handle" }
                }).joinToString(", ")
                val nativeCall = "$native.${fn.name}($args)"
                val retRef = fn.returnType as? KmpTypeRef.ClassRef
                val isIfaceReturn = retRef != null && (retRef.simpleName in interfaceNames || retRef.simpleName in abstractNames)
                when {
                    !isIfaceReturn -> appendLine("  ${fn.name}($params): Promise<$ret> { return $nativeCall }")
                    retRef!!.nullable -> appendLine("  ${fn.name}($params): Promise<$ret> { return $nativeCall.then((id: string | null) => id !== null ? ${retRef.simpleName}._wrap(id) : null) }")
                    else -> appendLine("  ${fn.name}($params): Promise<$ret> { return $nativeCall.then((id: string) => ${retRef.simpleName}._wrap(id)) }")
                }
            }
            FunctionKind.FLOW -> {
                if (fn.params.size + 1 > MAX_EXPO_FUNCTION_PARAMS) {
                    onSkip("FUNCTION SKIPPED: $moduleName.${fn.name}() — too many params (${fn.params.size} + handle > $MAX_EXPO_FUNCTION_PARAMS).")
                    return
                }
                val base = fn.flowBaseName
                val cap  = base.replaceFirstChar { it.uppercase() }
                val valueType = fn.returnType.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)
                val params = fn.params.joinToString(", ") {
                    "${it.name}: ${it.type.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode = true)}"
                }
                val args = (listOf("this._handle") + fn.params.map { p ->
                    val pRef = p.type as? KmpTypeRef.ClassRef
                    val isIface = pRef != null && (pRef.simpleName in interfaceNames || pRef.simpleName in abstractNames)
                    when { !isIface -> p.name; pRef!!.nullable -> "${p.name}?._handle ?? null"; else -> "${p.name}._handle" }
                }).joinToString(", ")
                appendLine()
                appendLine("  start$cap($params): void { $native.start$cap($args) }")
                appendLine("  stop$cap(): void { $native.stop$cap(this._handle) }")
                appendLine("  add${cap}Listener(handler: (event: { value: $valueType }) => void) {")
                appendLine("    return $native.addListener('on${cap}Update', (e: any) => {")
                appendLine("      if (e.instanceId === this._handle) handler({ value: e.value })")
                appendLine("    })")
                appendLine("  }")
            }
        }
    }

    /**
     * Maps a KMP type reference to its TypeScript type.
     *
     * Enums map to their string-literal-backed TS `enum` type name when [wrapperMode] is set
     * (i.e. the value has already round-tripped through a typed wrapper), or to plain `string`
     * when not (the raw shape the native module itself exposes). Interfaces/abstract classes
     * always map to their wrapper class name — callers are expected to already be working with
     * the wrapped instance, never the raw native handle. Anything the generator doesn't
     * specifically recognize (generics, `Flow` itself before it's unwrapped to
     * start/stop/listener methods, ...) maps to `unknown`.
     */
    private fun KmpTypeRef.toTsType(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
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
            this is KmpTypeRef.ClassRef && simpleName in enumNames      -> if (wrapperMode) simpleName else "string"
            this is KmpTypeRef.ClassRef && simpleName in dataNames      -> simpleName
            this is KmpTypeRef.ClassRef && simpleName in sealedNames    -> simpleName
            this is KmpTypeRef.ClassRef && simpleName in interfaceNames -> simpleName
            this is KmpTypeRef.ClassRef && simpleName in abstractNames  -> simpleName
            this is KmpTypeRef.ClassRef -> "unknown"
            this is KmpTypeRef.CollectionType -> when (kind) {
                CollectionKind.LIST, CollectionKind.SET -> {
                    val elem = (typeArgs.firstOrNull() as? KmpTypeArg.Invariant)
                        ?.type?.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode) ?: "unknown"
                    "$elem[]"
                }
                CollectionKind.MAP -> {
                    val key = (typeArgs.getOrNull(0) as? KmpTypeArg.Invariant)
                        ?.type?.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode) ?: "unknown"
                    val value = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)
                        ?.type?.toTsType(enumNames, dataNames, sealedNames, interfaceNames, abstractNames, wrapperMode) ?: "unknown"
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

/** Prepended to every generated file as a warning against hand-editing generated output. */
private const val HEADER = "// AUTO-GENERATED by shared-artifacts bridge generator. DO NOT EDIT.\n" +
    "// Re-run `bash scripts/push-bridges.sh` from shared-artifacts to regenerate."
