package bridgegen

import org.jetbrains.kotlin.konan.file.File as KFile
import org.jetbrains.kotlin.library.resolveSingleFileKlib
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.serialization.deserialization.getClassId
import java.io.File

/**
 * Reads a compiled Kotlin Multiplatform `.klib` file and produces a [KmpModule] containing
 * the fully resolved public API surface of the given [targetPackage], grouped by source file.
 *
 * Each klib package fragment corresponds to one `.kt` source file. When [sourceDir] is provided,
 * the reader scans source files to resolve human-readable file names; otherwise the klib part
 * name is used as a fallback.
 *
 * ### Normalization applied before returning:
 * - `StateFlow`, `SharedFlow`, `MutableStateFlow`, `MutableSharedFlow` → [KmpTypeRef.FlowType]
 * - `MutableList`, `MutableMap`, `MutableSet` → their read-only [CollectionKind] equivalents
 * - `suspend fun` returning `Flow<T>` → [FunctionKind.FLOW], `suspend` modifier discarded
 * - Type aliases → resolved to the underlying expanded type
 * - Non-public declarations, annotation classes, and generated data-class methods are excluded
 */
object KlibApiReader {

    private val SKIP_FUNCTION_NAMES = setOf("hashCode", "equals", "toString", "copy")
    private val COMPONENT_REGEX = Regex("^component\\d+$")

    /** Regex that matches the simple name of any top-level class/interface/object declaration. */
    private val DECL_NAME_REGEX = Regex(
        """^\s*(?:public\s+)?(?:(?:data|sealed|abstract|open|inner|enum|annotation)\s+)*(?:class|interface|object)\s+(\w+)"""
    )

    /** Regex that matches the simple name of any top-level fun/val/var declaration. */
    private val TOP_LEVEL_DECL_REGEX = Regex(
        """^\s*(?:public\s+)?(?:suspend\s+)?(?:fun|val|var)\s+(\w+)"""
    )

    private data class ClassEntry(
        val cls: ProtoBuf.Class,
        val nr: NameResolverImpl,
    ) {
        // Type table is per-class in klib metadata, not per package fragment.
        val tt: TypeTable get() = TypeTable(
            if (cls.hasTypeTable()) cls.typeTable else ProtoBuf.TypeTable.getDefaultInstance()
        )
    }

    private data class TopLevelEntry(
        val functions: List<ProtoBuf.Function>,
        val properties: List<ProtoBuf.Property>,
        val nr: NameResolverImpl,
        val tt: TypeTable,
    )

    private data class PartData(
        val classes: MutableList<ClassEntry> = mutableListOf(),
        val topLevel: MutableList<TopLevelEntry> = mutableListOf(),
    )

    /**
     * Reads all public declarations in [targetPackage] from [klibFile], grouped by source file.
     *
     * @param klibFile      The commonMain metadata klib directory (or ZIP) to read.
     * @param targetPackage The Kotlin package to include (e.g. `"com.example.shared"`).
     * @param sourceDir     Optional commonMain source directory used to resolve source file names
     *                      from class names. When null, falls back to klib part names.
     */
    fun read(klibFile: File, targetPackage: String, sourceDir: File? = null): KmpModule {
        val library = resolveSingleFileKlib(KFile(klibFile.absolutePath))
        val header  = parseModuleHeader(library.moduleHeaderData)

        val classToFile: Map<String, String> =
            if (sourceDir != null) scanSourceFiles(sourceDir) else emptyMap()

        // Preserve insertion order so file order matches compilation order.
        val partEntries = LinkedHashMap<String, PartData>()

        for (pkg in header.packageFragmentNameList) {
            if (pkg != targetPackage && !pkg.startsWith("$targetPackage.")) continue
            for (part in library.packageMetadataParts(pkg)) {
                val fragment  = parsePackageFragment(library.packageMetadata(pkg, part))
                val nr        = NameResolverImpl(fragment.strings, fragment.qualifiedNames)
                // Top-level functions/properties live in the Package sub-message, not on
                // PackageFragment directly. `package` is a reserved word → accessed as package_.
                val fragmentPkg = fragment.`package`
                val fragmentTt = TypeTable(
                    if (fragmentPkg.hasTypeTable()) fragmentPkg.typeTable
                    else ProtoBuf.TypeTable.getDefaultInstance()
                )
                val data = partEntries.getOrPut(part) { PartData() }
                fragment.class_List.forEach { data.classes.add(ClassEntry(it, nr)) }
                if (fragmentPkg.functionList.isNotEmpty() || fragmentPkg.propertyList.isNotEmpty()) {
                    data.topLevel.add(TopLevelEntry(fragmentPkg.functionList, fragmentPkg.propertyList, nr, fragmentTt))
                }
            }
        }

        // Build FQN → entry map across all parts for nested-class resolution.
        val byFqName: Map<String, ClassEntry> = partEntries.values
            .flatMap { it.classes }
            .associateBy { entry ->
                entry.nr.getClassId(entry.cls.fqName).asSingleFqName().asString()
            }

        val moduleName = library.manifestProperties
            .getProperty("unique_name", klibFile.name)
            .substringBefore("_")

        val files = partEntries.entries.mapNotNull { (partName, data) ->
            val topLevel = data.classes.filter { entry ->
                !entry.nr.getClassId(entry.cls.fqName).isNestedClass
            }

            val fileScopeFunctions = mutableListOf<KmpFunction>()
            for (entry in data.topLevel) {
                entry.functions.mapNotNullTo(fileScopeFunctions) { fn ->
                    readFunction(fn, entry.nr, entry.tt, emptyList())
                }
                entry.properties.mapNotNullTo(fileScopeFunctions) { prop ->
                    readPropertyAsGetter(prop, entry.nr, entry.tt)
                }
            }

            if (topLevel.isEmpty() && fileScopeFunctions.isEmpty()) return@mapNotNull null

            // Resolve file name: try class names first, then fall back to top-level function names.
            val fileName = topLevel
                .firstNotNullOfOrNull { entry ->
                    val simpleName = entry.nr.getClassId(entry.cls.fqName).shortClassName.asString()
                    classToFile[simpleName]
                }
                ?: fileScopeFunctions.firstNotNullOfOrNull { fn -> classToFile[fn.name] }
                ?: partName

            val declarations = mutableListOf<KmpDeclaration>()
            topLevel.mapNotNullTo(declarations) { entry ->
                readDeclaration(entry.cls, entry.nr, entry.tt, byFqName)
            }
            if (fileScopeFunctions.isNotEmpty()) {
                declarations.add(KmpDeclaration.KmpFileScope(fileName, targetPackage, fileScopeFunctions))
            }

            if (declarations.isEmpty()) null
            else KmpSourceFile(fileName, declarations)
        }

        return KmpModule(
            moduleName  = moduleName,
            packageName = targetPackage,
            files       = files,
        )
    }

    /**
     * Scans all `.kt` files under [sourceDir] and returns a map from declaration simple name
     * to source file name (without `.kt` extension).
     *
     * Only the declaration name is extracted — no type information is parsed. This is used
     * solely to resolve human-readable file names for klib parts.
     */
    private fun scanSourceFiles(sourceDir: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        sourceDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val fileName = file.nameWithoutExtension
                file.forEachLine { line ->
                    val name = DECL_NAME_REGEX.find(line)?.groupValues?.get(1)
                        ?: TOP_LEVEL_DECL_REGEX.find(line)?.groupValues?.get(1)
                    if (!name.isNullOrBlank()) result.putIfAbsent(name, fileName)
                }
            }
        return result
    }

    // ── Declaration reading ───────────────────────────────────────────────────

    private fun readDeclaration(
        cls: ProtoBuf.Class,
        nr: NameResolverImpl,
        tt: TypeTable,
        all: Map<String, ClassEntry>,
    ): KmpDeclaration? {
        if (Flags.VISIBILITY.get(cls.flags) != ProtoBuf.Visibility.PUBLIC) return null
        if (Flags.IS_EXPECT_CLASS.get(cls.flags)) return null

        val classId  = nr.getClassId(cls.fqName)
        val name     = classId.shortClassName.asString()
        val pkg      = classId.packageFqName.asString()
        val kind     = Flags.CLASS_KIND.get(cls.flags)
        val modality = Flags.MODALITY.get(cls.flags)
        val isData   = Flags.IS_DATA.get(cls.flags)

        return when {
            kind == ProtoBuf.Class.Kind.ANNOTATION_CLASS -> null

            kind == ProtoBuf.Class.Kind.ENUM_CLASS -> KmpDeclaration.KmpEnum(
                name        = name,
                packageName = pkg,
                entries     = cls.enumEntryList.map { nr.getString(it.name) },
            )

            kind == ProtoBuf.Class.Kind.INTERFACE -> KmpDeclaration.KmpInterface(
                name        = name,
                packageName = pkg,
                functions   = readFunctions(cls, nr, tt),
            )

            kind == ProtoBuf.Class.Kind.OBJECT ||
            kind == ProtoBuf.Class.Kind.COMPANION_OBJECT -> KmpDeclaration.KmpObject(
                name        = name,
                packageName = pkg,
                functions   = readFunctions(cls, nr, tt),
            )

            modality == ProtoBuf.Modality.SEALED -> {
                val parentFqn = classId.asSingleFqName().asString()
                val variants = cls.nestedClassNameList.mapNotNull { idx ->
                    val nestedFqn = "$parentFqn.${nr.getString(idx)}"
                    all[nestedFqn]?.let { e -> readVariant(e.cls, e.nr, e.tt) }
                }
                KmpDeclaration.KmpSealedClass(
                    name        = name,
                    packageName = pkg,
                    variants    = variants,
                    functions   = readFunctions(cls, nr, tt),
                )
            }

            isData -> KmpDeclaration.KmpDataClass(
                name        = name,
                packageName = pkg,
                fields      = primaryConstructorFields(cls, nr, tt),
                functions   = readFunctions(cls, nr, tt),
            )

            else -> KmpDeclaration.KmpClass(
                name           = name,
                packageName    = pkg,
                isAbstract     = modality == ProtoBuf.Modality.ABSTRACT,
                functions      = readFunctions(cls, nr, tt),
                typeParameters = cls.typeParameterList.map { nr.getString(it.name) },
            )
        }
    }

    private fun readVariant(cls: ProtoBuf.Class, nr: NameResolverImpl, tt: TypeTable): KmpVariant {
        val name     = nr.getClassId(cls.fqName).shortClassName.asString()
        val kind     = Flags.CLASS_KIND.get(cls.flags)
        val modality = Flags.MODALITY.get(cls.flags)
        val isData   = Flags.IS_DATA.get(cls.flags)

        return when {
            kind == ProtoBuf.Class.Kind.OBJECT -> KmpVariant.ObjectVariant(name)
            isData -> KmpVariant.DataVariant(name = name, fields = primaryConstructorFields(cls, nr, tt))
            else   -> KmpVariant.ClassVariant(
                name       = name,
                fields     = primaryConstructorFields(cls, nr, tt),
                isAbstract = modality == ProtoBuf.Modality.ABSTRACT,
            )
        }
    }

    // ── Function reading ──────────────────────────────────────────────────────

    private fun readFunctions(cls: ProtoBuf.Class, nr: NameResolverImpl, tt: TypeTable): List<KmpFunction> =
        cls.functionList.mapNotNull { readFunction(it, nr, tt, cls.typeParameterList) }

    private fun readFunction(
        func: ProtoBuf.Function,
        nr: NameResolverImpl,
        tt: TypeTable,
        classTypeParams: List<ProtoBuf.TypeParameter>,
    ): KmpFunction? {
        if (Flags.VISIBILITY.get(func.flags) != ProtoBuf.Visibility.PUBLIC) return null

        val name = nr.getString(func.name)
        if (name in SKIP_FUNCTION_NAMES || name.startsWith("<") || COMPONENT_REGEX.matches(name)) return null

        val typeParams    = classTypeParams + func.typeParameterList
        val isSuspend     = Flags.IS_SUSPEND.get(func.flags)
        val returnTypeRef = resolveReturnType(func, nr, tt, typeParams)

        val (kind, effectiveReturn) = when {
            returnTypeRef is KmpTypeRef.FlowType -> {
                val element = when (val arg = returnTypeRef.typeArg) {
                    is KmpTypeArg.Invariant     -> arg.type
                    is KmpTypeArg.Covariant     -> arg.type
                    is KmpTypeArg.Contravariant -> arg.type
                    KmpTypeArg.Star             -> KmpTypeRef.Primitive(PrimitiveKind.STRING)
                }
                FunctionKind.FLOW to element
            }
            isSuspend -> FunctionKind.SUSPEND to returnTypeRef
            else      -> FunctionKind.SYNC    to returnTypeRef
        }

        val params = func.valueParameterList.map { param ->
            KmpParam(
                name = nr.getString(param.name),
                type = resolveParamType(param, nr, tt, typeParams),
            )
        }

        return KmpFunction(name = name, kind = kind, params = params, returnType = effectiveReturn)
    }

    private fun readPropertyAsGetter(
        prop: ProtoBuf.Property,
        nr: NameResolverImpl,
        tt: TypeTable,
    ): KmpFunction? {
        if (Flags.VISIBILITY.get(prop.flags) != ProtoBuf.Visibility.PUBLIC) return null
        val name = nr.getString(prop.name)
        val returnTypeProto = if (prop.hasReturnType()) prop.returnType else tt[prop.returnTypeId]
        val typeRef = readTypeRef(returnTypeProto, nr, tt, emptyList())
        return KmpFunction(
            name            = name,
            kind            = FunctionKind.SYNC,
            params          = emptyList(),
            returnType      = typeRef,
            isPropertyGetter = true,
        )
    }

    // ── Field reading ─────────────────────────────────────────────────────────

    private fun primaryConstructorFields(
        cls: ProtoBuf.Class,
        nr: NameResolverImpl,
        tt: TypeTable,
    ): List<KmpField> {
        val ctor = cls.constructorList.firstOrNull { !Flags.IS_SECONDARY.get(it.flags) }
            ?: return emptyList()
        return ctor.valueParameterList.map { param ->
            KmpField(
                name = nr.getString(param.name),
                type = resolveParamType(param, nr, tt, emptyList()),
            )
        }
    }

    // ── Type resolution ───────────────────────────────────────────────────────

    private fun resolveReturnType(
        func: ProtoBuf.Function,
        nr: NameResolverImpl,
        tt: TypeTable,
        typeParams: List<ProtoBuf.TypeParameter>,
    ): KmpTypeRef {
        val type = if (func.hasReturnType()) func.returnType else tt[func.returnTypeId]
        return readTypeRef(type, nr, tt, typeParams)
    }

    private fun resolveParamType(
        param: ProtoBuf.ValueParameter,
        nr: NameResolverImpl,
        tt: TypeTable,
        typeParams: List<ProtoBuf.TypeParameter>,
    ): KmpTypeRef {
        val type = if (param.hasType()) param.type else tt[param.typeId]
        return readTypeRef(type, nr, tt, typeParams)
    }

    private fun readTypeRef(
        type: ProtoBuf.Type,
        nr: NameResolverImpl,
        tt: TypeTable,
        typeParams: List<ProtoBuf.TypeParameter>,
    ): KmpTypeRef {
        val nullable = type.nullable

        // Type alias — use the expanded (underlying) type
        if (type.hasAbbreviatedType()) {
            return readTypeRef(
                if (type.hasAbbreviatedType()) type.abbreviatedType else type,
                nr, tt, typeParams,
            )
        }

        // Generic type parameter (e.g. T, K, V)
        if (type.hasTypeParameter()) {
            val paramName = typeParams.getOrNull(type.typeParameter)
                ?.let { nr.getString(it.name) } ?: "T"
            return KmpTypeRef.TypeParam(paramName, nullable)
        }

        if (!type.hasClassName()) return KmpTypeRef.ClassRef("kotlin.Any", nullable = nullable)

        val classId  = nr.getClassId(type.className)
        val fqn      = classId.asString()   // e.g. "kotlin/String", "com/example/shared/FixtureUser"
        val typeArgs = type.argumentList.map { arg -> readTypeArg(arg, nr, tt, typeParams) }

        return mapToTypeRef(fqn, typeArgs, nullable)
    }

    private fun readTypeArg(
        arg: ProtoBuf.Type.Argument,
        nr: NameResolverImpl,
        tt: TypeTable,
        typeParams: List<ProtoBuf.TypeParameter>,
    ): KmpTypeArg {
        if (arg.projection == ProtoBuf.Type.Argument.Projection.STAR) return KmpTypeArg.Star
        // Type arguments can be stored inline (arg.type) or by ID in the class type table (arg.typeId).
        val type = if (arg.hasType()) arg.type else tt[arg.typeId]
        return when (arg.projection) {
            ProtoBuf.Type.Argument.Projection.OUT -> KmpTypeArg.Covariant(readTypeRef(type, nr, tt, typeParams))
            ProtoBuf.Type.Argument.Projection.IN  -> KmpTypeArg.Contravariant(readTypeRef(type, nr, tt, typeParams))
            else                                  -> KmpTypeArg.Invariant(readTypeRef(type, nr, tt, typeParams))
        }
    }

    private fun mapToTypeRef(fqn: String, typeArgs: List<KmpTypeArg>, nullable: Boolean): KmpTypeRef = when (fqn) {
        "kotlin/String"  -> KmpTypeRef.Primitive(PrimitiveKind.STRING,  nullable)
        "kotlin/Int"     -> KmpTypeRef.Primitive(PrimitiveKind.INT,     nullable)
        "kotlin/Long"    -> KmpTypeRef.Primitive(PrimitiveKind.LONG,    nullable)
        "kotlin/Double"  -> KmpTypeRef.Primitive(PrimitiveKind.DOUBLE,  nullable)
        "kotlin/Float"   -> KmpTypeRef.Primitive(PrimitiveKind.FLOAT,   nullable)
        "kotlin/Boolean" -> KmpTypeRef.Primitive(PrimitiveKind.BOOLEAN, nullable)
        "kotlin/Byte"    -> KmpTypeRef.Primitive(PrimitiveKind.BYTE,    nullable)
        "kotlin/Short"   -> KmpTypeRef.Primitive(PrimitiveKind.SHORT,   nullable)
        "kotlin/Char"    -> KmpTypeRef.Primitive(PrimitiveKind.CHAR,    nullable)
        "kotlin/Unit"    -> KmpTypeRef.UnitType(nullable)

        "kotlin/collections/List",
        "kotlin/collections/MutableList",
        "kotlin/collections/Collection",
        "kotlin/collections/Iterable"    -> KmpTypeRef.CollectionType(CollectionKind.LIST, typeArgs, nullable)

        "kotlin/collections/Map",
        "kotlin/collections/MutableMap"  -> KmpTypeRef.CollectionType(CollectionKind.MAP, typeArgs, nullable)

        "kotlin/collections/Set",
        "kotlin/collections/MutableSet"  -> KmpTypeRef.CollectionType(CollectionKind.SET, typeArgs, nullable)

        "kotlinx/coroutines/flow/Flow",
        "kotlinx/coroutines/flow/StateFlow",
        "kotlinx/coroutines/flow/SharedFlow",
        "kotlinx/coroutines/flow/MutableStateFlow",
        "kotlinx/coroutines/flow/MutableSharedFlow" ->
            KmpTypeRef.FlowType(
                typeArg  = typeArgs.firstOrNull() ?: KmpTypeArg.Invariant(KmpTypeRef.UnitType()),
                nullable = nullable,
            )

        else -> KmpTypeRef.ClassRef(
            qualifiedName = fqn.replace('/', '.').replace('$', '.'),
            typeArgs      = typeArgs,
            nullable      = nullable,
        )
    }
}
