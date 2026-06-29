package bridgegen

enum class FunctionKind { SYNC, SUSPEND, FLOW }

data class ParamInfo(val name: String, val type: String)

data class FunctionInfo(
    val name: String,
    val params: List<ParamInfo>,
    val returnType: String,
    val kind: FunctionKind,
    val docComment: String? = null,
) {
    /** For FLOW functions: strips a trailing "Flow" suffix, e.g. "counterFlow" → "counter". */
    val flowBaseName: String
        get() = if (name.endsWith("Flow", ignoreCase = true)) name.dropLast(4) else name

    /** True when [typeName] appears as this function's return type or any parameter type. */
    fun referencesType(typeName: String): Boolean =
        returnType.trim() == typeName || params.any { it.type.trim() == typeName }
}

data class ClassInfo(
    val name: String,
    val packageName: String,
    val functions: List<FunctionInfo>,
)

/** A Kotlin enum exposed across the bridge. Carried as its case name (a String) over the wire. */
data class EnumInfo(
    val name: String,
    val cases: List<String>,
)

/** The full parsed surface of the KMP module: concrete classes plus the enums they reference. */
data class ParsedApi(
    val classes: List<ClassInfo>,
    val enums: List<EnumInfo>,
)
