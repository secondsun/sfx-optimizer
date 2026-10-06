package dev.secondsun.sfxoptimizer.viewer

object SimpleJson {
    fun serialize(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> escapeString(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> {
                val entries =
                    value.entries.joinToString(",") { (k, v) ->
                        "${escapeString(k.toString())}:${serialize(v)}"
                    }
                "{$entries}"
            }
            is Iterable<*> -> {
                val items = value.joinToString(",") { serialize(it) }
                "[$items]"
            }
            is LocationDto -> {
                """{"line":${value.line},"colStart":${value.colStart},"colEnd":${value.colEnd}}"""
            }
            is SourceTokenDto -> {
                val varKey = if (value.variableKey != null) escapeString(value.variableKey) else "null"
                val fnScope = if (value.functionScope != null) escapeString(value.functionScope) else "null"
                val impReadsJson = serialize(value.implicitReads)
                val impWritesJson = serialize(value.implicitWrites)
                """{"text":${escapeString(
                    value.text,
                )},"type":${escapeString(
                    value.type,
                )},"cssClass":${escapeString(
                    value.cssClass,
                )},"line":${value.line},"colStart":${value.colStart},"colEnd":${value.colEnd},"variableKey":$varKey,"functionScope":$fnScope,"isWrite":${value.isWrite},"isRead":${value.isRead},"implicitReads":$impReadsJson,"implicitWrites":$impWritesJson}"""
            }
            is SourceLineDto -> {
                val tokensJson = serialize(value.tokens)
                """{"lineNumber":${value.lineNumber},"text":${escapeString(value.text)},"tokens":$tokensJson}"""
            }
            is IntervalSpanDto -> {
                val fnScope = if (value.functionScope != null) escapeString(value.functionScope) else "null"
                val regName = if (value.registerName != null) escapeString(value.registerName) else "null"
                val allocReg = if (value.allocatedRegister != null) escapeString(value.allocatedRegister) else "null"
                val readsJson = serialize(value.reads)
                val writesJson = serialize(value.writes)
                val interferencesJson = serialize(value.interferences)
                """{"keyName":${escapeString(
                    value.keyName,
                )},"functionScope":$fnScope,"isRegister":${value.isRegister},"registerName":$regName,"startLine":${value.startLine},"endLine":${value.endLine},"reads":$readsJson,"writes":$writesJson,"interferences":$interferencesJson,"allocatedRegister":$allocReg,"isSpill":${value.isSpill}}"""
            }
            is CodeBlockDto -> {
                val entrancesJson = serialize(value.entrances)
                val exitsJson = serialize(value.exits)
                val intervalsJson = serialize(value.intervals)
                """{"id":${escapeString(
                    value.id,
                )},"startLine":${value.startLine},"endLine":${value.endLine},"entrances":$entrancesJson,"exits":$exitsJson,"intervals":$intervalsJson}"""
            }
            is FunctionScopeDto -> {
                val intervalsJson = serialize(value.intervals)
                val allocMapJson = serialize(value.allocatedMap)
                """{"name":${escapeString(
                    value.name,
                )},"startLine":${value.startLine},"endLine":${value.endLine},"intervals":$intervalsJson,"allocatedMap":$allocMapJson}"""
            }
            is FileAnalysisResultDto -> {
                val linesJson = serialize(value.lines)
                val blocksJson = serialize(value.blocks)
                val intervalsJson = serialize(value.intervals)
                val allocMapJson = serialize(value.allocatedMap)
                val functionsJson = serialize(value.functions)
                """{"fileName":${escapeString(
                    value.fileName,
                )},"totalLines":${value.totalLines},"lines":$linesJson,"blocks":$blocksJson,"intervals":$intervalsJson,"allocatedMap":$allocMapJson,"functions":$functionsJson}"""
            }
            else -> escapeString(value.toString())
        }

    fun escapeString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch < ' ') {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
