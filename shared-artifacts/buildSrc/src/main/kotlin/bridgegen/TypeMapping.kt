package bridgegen

object TypeMapping {

    /** True when a Kotlin type refers to one of the parsed enums (carried as its case-name String). */
    fun isEnum(kotlinType: String, enumNames: Set<String>): Boolean = enumNames.contains(kotlinType.trim())

    /**
     * Swift parameter type for a Kotlin type received from JS.
     * JS numbers arrive as Double; Long must be accepted as Double then cast at the call site.
     * Enums arrive as their case-name String.
     */
    fun swiftBridgeParamType(kotlinType: String, enumNames: Set<String> = emptySet()): String = when {
        isEnum(kotlinType, enumNames) -> "String"
        else -> when (kotlinType.trim()) {
            "String" -> "String"
            "Int" -> "Int32"
            "Long" -> "Double"
            "Double" -> "Double"
            "Float" -> "Float"
            "Boolean" -> "Bool"
            else -> "Any"
        }
    }

    /** Cast expression used when forwarding a bridge parameter value to the KMP function. */
    fun swiftCallArg(paramName: String, kotlinType: String, enumNames: Set<String> = emptySet()): String = when {
        isEnum(kotlinType, enumNames) -> "decode${kotlinType.trim()}($paramName)"
        kotlinType.trim() == "Long" -> "Int64($paramName)"
        else -> paramName
    }

    /**
     * Unboxing suffix for a SKIE async-sequence element.
     * SKIE wraps primitive flow elements as KotlinInt/KotlinLong/… (NSNumber subclasses).
     */
    fun swiftFlowUnbox(kotlinType: String): String = when (kotlinType.trim()) {
        "Int" -> ".intValue"
        "Long" -> ".int64Value"
        "Double" -> ".doubleValue"
        "Float" -> ".floatValue"
        "Boolean" -> ".boolValue"
        else -> ""
    }

    fun swiftReturnType(kotlinType: String): String = when (kotlinType.trim()) {
        "String" -> "String"
        "Int" -> "Int32"
        "Long" -> "Int64"
        "Double" -> "Double"
        "Float" -> "Float"
        "Boolean" -> "Bool"
        "Unit" -> "Void"
        else -> "Any"
    }

    /**
     * Android bridge parameter type for a Kotlin type received from JS.
     * JS numbers arrive as Double; the call site casts back to Long when needed.
     * Enums arrive as their case-name String.
     */
    fun androidBridgeParamType(kotlinType: String, enumNames: Set<String> = emptySet()): String = when {
        isEnum(kotlinType, enumNames) -> "String"
        kotlinType.trim() == "Long" -> "Double"
        else -> kotlinType
    }

    /** Cast expression used when forwarding a bridge parameter value to the KMP function on Android. */
    fun androidCallArg(paramName: String, kotlinType: String, enumNames: Set<String> = emptySet()): String = when {
        isEnum(kotlinType, enumNames) -> "${kotlinType.trim()}.valueOf($paramName)"
        kotlinType.trim() == "Long" -> "$paramName.toLong()"
        else -> paramName
    }

    fun tsType(kotlinType: String, enumNames: Set<String> = emptySet()): String = when {
        isEnum(kotlinType, enumNames) -> kotlinType.trim()
        else -> when (kotlinType.trim()) {
            "String" -> "string"
            "Int", "Long", "Double", "Float" -> "number"
            "Boolean" -> "boolean"
            "Unit" -> "void"
            else -> "unknown"
        }
    }
}
