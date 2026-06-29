package bridgegen

import java.io.File

class KmpApiParser {

    fun parseFiles(files: List<File>): ParsedApi {
        val sources = files.map { it.readText() }
        val classes = sources.flatMap { parseContent(it) }
        val enums = sources.flatMap { parseEnums(it) }
        return ParsedApi(classes, enums)
    }

    fun parseContent(source: String): List<ClassInfo> {
        val comments = extractFunctionComments(source)
        val lines = stripComments(source).lines()
        val classes = mutableListOf<ClassInfo>()
        var packageName = ""
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            PACKAGE_RE.find(line)?.let { packageName = it.groupValues[1] }

            if (isConcreteClass(line)) {
                val className = CLASS_NAME_RE.find(line)!!.groupValues[1]

                // Collect the class body by tracking brace depth
                val bodyLines = mutableListOf<String>()
                var depth = 0
                var started = false
                var j = i

                while (j < lines.size) {
                    val l = lines[j]
                    for (ch in l) {
                        when (ch) {
                            '{' -> { depth++; started = true }
                            '}' -> depth--
                        }
                    }
                    if (started) bodyLines.add(l)
                    if (started && depth == 0) { i = j; break }
                    j++
                }

                val functions = parseFunctions(bodyLines.joinToString("\n"), comments)
                if (functions.isNotEmpty()) {
                    classes.add(ClassInfo(className, packageName, functions))
                }
            }

            i++
        }

        return classes
    }

    private fun isConcreteClass(line: String): Boolean {
        if (!line.contains(Regex("""\bclass\b"""))) return false
        if (line.contains(Regex("""\bexpect\b"""))) return false
        if (line.contains(Regex("""\babstract\b"""))) return false
        if (line.contains(Regex("""\binterface\b"""))) return false
        if (line.contains(Regex("""\bobject\b"""))) return false
        // enum/sealed classes cannot be instantiated with a no-arg constructor — never treat them as bridgeable classes.
        if (line.contains(Regex("""\benum\b"""))) return false
        if (line.contains(Regex("""\bsealed\b"""))) return false
        return CLASS_NAME_RE.containsMatchIn(line)
    }

    /** Collects `enum class` declarations and their case names. Bodies/constructor args on entries are ignored. */
    private fun parseEnums(source: String): List<EnumInfo> {
        val lines = stripComments(source).lines()
        val enums = mutableListOf<EnumInfo>()
        var i = 0

        while (i < lines.size) {
            val match = ENUM_NAME_RE.find(lines[i])
            if (match != null) {
                // Collect the enum body by tracking brace depth.
                val body = StringBuilder()
                var depth = 0
                var started = false
                var j = i
                while (j < lines.size) {
                    for (ch in lines[j]) {
                        when (ch) {
                            '{' -> { depth++; started = true }
                            '}' -> depth--
                        }
                    }
                    if (started) body.appendLine(lines[j])
                    if (started && depth == 0) { i = j; break }
                    j++
                }
                enums.add(EnumInfo(match.groupValues[1], parseEnumCases(body.toString())))
            }
            i++
        }

        return enums
    }

    private fun parseEnumCases(body: String): List<String> {
        val open = body.indexOf('{')
        if (open < 0) return emptyList()
        val close = body.lastIndexOf('}').takeIf { it > open } ?: body.length
        // Entries precede the first ';' that separates them from member declarations.
        val entriesPart = body.substring(open + 1, close).substringBefore(';')
        return splitTopLevelCommas(entriesPart).mapNotNull { raw ->
            ENUM_CASE_RE.find(raw.trim())?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parseFunctions(body: String, comments: Map<String, String> = emptyMap()): List<FunctionInfo> {
        val functions = mutableListOf<FunctionInfo>()
        val lines = body.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            // Skip non-public members
            if (SKIP_VISIBILITY_RE.containsMatchIn(line)) { i++; continue }

            if (!line.contains(Regex("""\bfun\b"""))) { i++; continue }

            // Accumulate multi-line declarations until parentheses balance
            var sig = line
            var parenDepth = sig.count { it == '(' } - sig.count { it == ')' }
            var j = i + 1
            while (parenDepth > 0 && j < lines.size) {
                val next = lines[j].trim()
                sig += " $next"
                parenDepth += next.count { it == '(' } - next.count { it == ')' }
                j++
            }
            i = j

            parseFunctionSignature(sig)?.let { functions.add(it.copy(docComment = comments[it.name])) }
        }

        return functions
    }

    private fun extractFunctionComments(source: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = source.lines()
        var pendingComment: String? = null
        var i = 0

        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.startsWith("/**") || trimmed.startsWith("/*") -> {
                    val buf = mutableListOf<String>()
                    while (i < lines.size) {
                        buf.add(lines[i].trim())
                        val ended = lines[i].contains("*/")
                        i++
                        if (ended) break
                    }
                    pendingComment = buf.joinToString("\n")
                }
                trimmed.startsWith("//") -> {
                    val buf = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith("//")) {
                        buf.add(lines[i].trim())
                        i++
                    }
                    pendingComment = buf.joinToString("\n")
                }
                trimmed.isBlank() -> {
                    pendingComment = null
                    i++
                }
                FUN_EXTRACT_RE.containsMatchIn(trimmed) -> {
                    FUN_EXTRACT_RE.find(trimmed)?.let { m ->
                        val name = m.groupValues[1]
                        if (pendingComment != null && name.isNotEmpty()) {
                            result.putIfAbsent(name, pendingComment!!)
                        }
                    }
                    pendingComment = null
                    i++
                }
                else -> {
                    pendingComment = null
                    i++
                }
            }
        }

        return result
    }

    private fun parseFunctionSignature(sig: String): FunctionInfo? {
        val isSuspend = sig.contains(Regex("""\bsuspend\b"""))

        val funIdx = sig.indexOf("fun ")
        if (funIdx < 0) return null

        val afterFun = sig.substring(funIdx + 4).trim()
        val parenStart = afterFun.indexOf('(')
        if (parenStart < 0) return null

        val name = afterFun.substring(0, parenStart).trim()
        // Skip operators, overrides of Object methods, and invalid names
        if (name.isEmpty() || !name[0].isLetter() || name.contains(' ')) return null

        // Find matching closing parenthesis
        var depth = 0
        var paramEnd = -1
        for ((idx, ch) in afterFun.substring(parenStart).withIndex()) {
            when (ch) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) { paramEnd = parenStart + idx; break } }
            }
        }
        if (paramEnd < 0) return null

        val paramsStr = afterFun.substring(parenStart + 1, paramEnd)
        val afterParen = afterFun.substring(paramEnd + 1).trim()

        val returnType = if (afterParen.startsWith(":")) {
            afterParen.removePrefix(":").trim()
                .split(Regex("""\s*[={]"""))[0]
                .trim()
        } else "Unit"

        val params = parseParams(paramsStr)
        val kind = when {
            returnType.startsWith("Flow<") -> FunctionKind.FLOW
            isSuspend -> FunctionKind.SUSPEND
            else -> FunctionKind.SYNC
        }
        val effectiveReturn = if (kind == FunctionKind.FLOW) {
            returnType.removePrefix("Flow<").removeSuffix(">").trim()
        } else returnType

        return FunctionInfo(name, params, effectiveReturn, kind)
    }

    private fun parseParams(raw: String): List<ParamInfo> {
        if (raw.isBlank()) return emptyList()
        return splitTopLevelCommas(raw).mapNotNull { part ->
            val colon = part.indexOf(':')
            if (colon < 0) return@mapNotNull null
            val name = part.substring(0, colon).trim()
                .removePrefix("vararg").trim()
                .removePrefix("crossinline").trim()
                .removePrefix("noinline").trim()
            val type = part.substring(colon + 1).split("=")[0].trim()
            if (name.isEmpty() || type.isEmpty()) null
            else ParamInfo(name, type)
        }
    }

    /** Splits a parameter list string by commas, ignoring commas inside angle brackets. */
    private fun splitTopLevelCommas(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val buf = StringBuilder()
        for (ch in s) {
            when (ch) {
                '<', '(' -> { depth++; buf.append(ch) }
                '>', ')' -> { depth--; buf.append(ch) }
                ',' -> if (depth == 0) { parts += buf.toString().trim(); buf.clear() }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotBlank()) parts += buf.toString().trim()
        return parts
    }

    private fun stripComments(source: String): String {
        var s = source.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        s = s.lines().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
        return s
    }

    companion object {
        private val PACKAGE_RE = Regex("""^\s*package\s+([\w.]+)""")
        private val CLASS_NAME_RE = Regex("""\bclass\s+(\w+)""")
        private val SKIP_VISIBILITY_RE = Regex("""^\s*(?:private|internal|protected)\s""")
        private val FUN_EXTRACT_RE = Regex("""\bfun\s+(\w+)\s*\(""")
        private val ENUM_NAME_RE = Regex("""\benum\s+class\s+(\w+)""")
        private val ENUM_CASE_RE = Regex("""^([A-Za-z_]\w*)""")
    }
}
