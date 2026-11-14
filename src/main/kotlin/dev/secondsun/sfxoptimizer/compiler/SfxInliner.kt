package dev.secondsun.sfxoptimizer.compiler

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.sfxoptimizer.analysis.SfxCfgBuilder
import dev.secondsun.sfxoptimizer.analysis.SfxLivenessAnalyzer
import dev.secondsun.sfxoptimizer.macro.SourceOrigin
import java.util.Locale

/**
 * Pre-allocation function inlining pass.
 *
 * Runs on expanded source text before liveness analysis so that inlined bodies, with their
 * `register` variables alpha-renamed (`__inline_<name>_<k>_<var>`), share the caller's
 * Interference Graph. Eligible functions are:
 * - declared `inline function <name> [params]` (and not self-recursive), or
 * - non-recursive leaf functions (no `call`) under [threshold] instructions called inside a loop.
 *
 * `return` statements become `bra @inline_exit_<k>; nop` (omitted for the final statement).
 */
class SfxInliner(
    private val threshold: Int = 60,
) {
    private class Fn(
        val name: String,
        val start: Int,
        val end: Int,
        val params: List<String>,
        val retVar: String?,
        val explicit: Boolean,
        val body: List<String>,
        val bodyOrigins: List<SourceOrigin>,
        val callsOthers: Boolean,
        val selfRecursive: Boolean,
        val size: Int,
    )

    private val headerRegex = Regex("""^(\s*)((?:[@\w.]+:\s*)?)(inline\s+)?function\s+(\w+)\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val callRegex = Regex("""^(\s*)((?:[@\w.]+:\s*)?)call\s+(\w+)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val endFunctionRegex = Regex("""^\s*(?:[@\w.]+:\s*)?endfunction\b""", RegexOption.IGNORE_CASE)
    private val hardwareRegex = Regex("""^r\d+$""", RegexOption.IGNORE_CASE)
    private val ifWriteMnemonics =
        setOf(
            "move",
            "moves",
            "moveb",
            "movew",
            "iwt",
            "ibt",
            "lm",
            "lms",
            "lea",
            "to",
            "with",
            "inc",
            "dec",
            "shl",
            "gsu_stack_pop",
            "gsu_stack_peek",
        )

    private var siteCounter = 0

    private fun code(line: String): String = line.substringBefore(';')

    private fun bodyOf(line: String): String =
        code(line)
            .replace(Regex("""^\s*(?:[@\w.]+:|:)"""), "")
            .trim()

    fun inline(source: String): String {
        val rawLines = source.lines()
        val defaultOrigins = rawLines.indices.map { SourceOrigin(null, it + 1) }
        return inlineWithProvenance(rawLines, defaultOrigins).first.joinToString("\n")
    }

    fun inlineWithProvenance(
        initialLines: List<String>,
        initialOrigins: List<SourceOrigin>,
    ): Pair<List<String>, List<SourceOrigin>> {
        var lines = initialLines
        var origins =
            if (initialOrigins.size == initialLines.size) {
                initialOrigins
            } else {
                initialLines.indices.map { SourceOrigin(null, it + 1) }
            }
        siteCounter = 0
        var changedAny = false
        repeat(8) {
            val (nextLines, nextOrigins, changed) = pass(lines, origins)
            lines = nextLines
            origins = nextOrigins
            if (!changed) return@repeat
            changedAny = true
        }
        return finalize(lines, origins, changedAny)
    }

    private fun parseFunctions(
        lines: List<String>,
        origins: List<SourceOrigin>,
    ): List<Fn> {
        val macroLines = SfxLivenessAnalyzer.findMacroDefinitionLines(lines)
        val fns = mutableListOf<Fn>()
        var i = 0
        while (i < lines.size) {
            if (i in macroLines) {
                i++
                continue
            }
            val m = headerRegex.matchEntire(code(lines[i]).trimEnd())
            if (m == null) {
                i++
                continue
            }
            var end = i + 1
            while (end < lines.size && !endFunctionRegex.containsMatchIn(code(lines[end]))) end++
            if (end >= lines.size) {
                i++
                continue
            }
            val name = m.groupValues[4]
            val rest = m.groupValues[5]
            val params =
                rest
                    .substringBefore(':')
                    .split(',', ' ', '\t')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val retVar = if (rest.contains(':')) rest.substringAfter(':').trim().ifEmpty { null } else null
            val body = lines.subList(i + 1, end).toList()
            val bodyOrig = origins.subList(i + 1, end).toList()
            val calls = body.mapNotNull { callRegex.matchEntire(code(it).trimEnd())?.groupValues?.get(3) }
            val size =
                body.count {
                    val b = bodyOf(it)
                    b.isNotEmpty() && !b.lowercase(Locale.ROOT).startsWith("register")
                }
            fns.add(
                Fn(
                    name = name,
                    start = i,
                    end = end,
                    params = params,
                    retVar = retVar,
                    explicit = m.groupValues[3].isNotBlank(),
                    body = body,
                    bodyOrigins = bodyOrig,
                    callsOthers = calls.isNotEmpty(),
                    selfRecursive = calls.any { it.equals(name, true) },
                    size = size,
                ),
            )
            i = end + 1
        }
        return fns
    }

    private fun loopRanges(
        lines: List<String>,
        fns: List<Fn>,
        macroLines: Set<Int>,
    ): List<IntRange> {
        val tokenized = CA65Scanner().tokenize(lines.joinToString("\n"))
        val ranges = mutableListOf<IntRange>()
        for (fn in fns) {
            val fnLines = (fn.start..fn.end).filter { it !in macroLines }
            ranges.addAll(SfxCfgBuilder.buildScopeCfg(tokenized, fn.name, fnLines).loops.map { it.lineRange })
        }
        val allFnLines = fns.flatMap { it.start..it.end }.toSet()
        val mainLines = lines.indices.filter { it !in allFnLines && it !in macroLines }
        ranges.addAll(SfxCfgBuilder.buildScopeCfg(tokenized, "(main)", mainLines).loops.map { it.lineRange })
        return ranges
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (ch in text) {
            when {
                ch == '(' -> depth++
                ch == ')' -> depth--
            }
            if (ch == ',' && depth == 0) {
                parts.add(cur.toString().trim())
                cur.clear()
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotBlank()) parts.add(cur.toString().trim())
        return parts
    }

    private fun splitDest(rest: String): Pair<String, String?> {
        var depth = 0
        for ((i, ch) in rest.withIndex()) {
            if (ch == '(') depth++
            if (ch == ')') depth--
            if (ch == ':' && depth == 0) {
                val prevColon = i > 0 && rest[i - 1] == ':'
                val nextColon = i + 1 < rest.length && rest[i + 1] == ':'
                if (!prevColon && !nextColon) {
                    return rest.substring(0, i).trim() to rest.substring(i + 1).trim().ifEmpty { null }
                }
            }
        }
        return rest.trim() to null
    }

    private fun computeMacroDefEndLines(lines: List<String>): Map<String, Int> {
        val headerRe = Regex("""^\s*\.macro\s+(\w+)\b""", RegexOption.IGNORE_CASE)
        val endRe = Regex("""^\s*\.(?:endmacro|endmac|endm)\b""", RegexOption.IGNORE_CASE)
        val directEnd = LinkedHashMap<String, Int>()
        val macroBodyLines = LinkedHashMap<String, MutableList<String>>()
        var depth = 0
        var activeName: String? = null

        for ((idx, line) in lines.withIndex()) {
            val trimmed = code(line).trim()
            val hm = headerRe.find(trimmed)
            if (hm != null) {
                if (depth == 0) {
                    activeName = hm.groupValues[1].lowercase(Locale.ROOT)
                    macroBodyLines.putIfAbsent(activeName, mutableListOf())
                }
                depth++
            } else if (endRe.containsMatchIn(trimmed)) {
                depth = maxOf(0, depth - 1)
                if (depth == 0 && activeName != null) {
                    directEnd.putIfAbsent(activeName, idx)
                    activeName = null
                }
            } else if (depth > 0 && activeName != null) {
                macroBodyLines[activeName]?.add(line)
            }
        }

        val memo = HashMap<String, Int>()
        val visiting = HashSet<String>()

        fun effectiveEnd(name: String): Int {
            memo[name]?.let { return it }
            val base = directEnd[name] ?: return -1
            if (!visiting.add(name)) return base
            var best = base
            for (bLine in macroBodyLines[name].orEmpty()) {
                for (sub in extractLineMnemonics(bLine)) {
                    if (sub in directEnd) {
                        best = maxOf(best, effectiveEnd(sub))
                    }
                }
            }
            visiting.remove(name)
            memo[name] = best
            return best
        }

        for (name in directEnd.keys) {
            effectiveEnd(name)
        }
        return memo
    }

    private fun extractLineMnemonics(line: String): List<String> {
        val b = bodyOf(line)
        if (b.isEmpty()) return emptyList()
        val mn = b.substringBefore(' ').substringBefore('\t').lowercase(Locale.ROOT)
        if (!b.contains('{')) return listOf(mn)
        val braceMn =
            b
                .substringAfter('{')
                .substringBefore('}')
                .trim()
                .substringBefore(' ')
                .substringBefore('\t')
                .lowercase(Locale.ROOT)
        return if (braceMn.isNotEmpty()) listOf(mn, braceMn) else listOf(mn)
    }

    private fun pass(
        lines: List<String>,
        origins: List<SourceOrigin>,
    ): Triple<List<String>, List<SourceOrigin>, Boolean> {
        val fns = parseFunctions(lines, origins)
        val byName = fns.associateBy { it.name.lowercase(Locale.ROOT) }
        val macroLines = SfxLivenessAnalyzer.findMacroDefinitionLines(lines)
        val macroDefEnd = computeMacroDefEndLines(lines)
        val fnMnemonics =
            fns.associate { fn ->
                fn.name.lowercase(Locale.ROOT) to fn.body.flatMap { extractLineMnemonics(it) }.toSet()
            }
        val loops = loopRanges(lines, fns, macroLines)
        val outLines = mutableListOf<String>()
        val outOrigins = mutableListOf<SourceOrigin>()
        var changed = false

        for ((idx, line) in lines.withIndex()) {
            val callOrigin = origins[idx]
            val m = if (idx in macroLines) null else callRegex.matchEntire(code(line).trimEnd())
            val fn = m?.let { byName[it.groupValues[3].lowercase(Locale.ROOT)] }
            if (m == null || fn == null || fn.selfRecursive || (idx in fn.start..fn.end)) {
                outLines.add(line)
                outOrigins.add(callOrigin)
                continue
            }
            val usesForwardMacro =
                fnMnemonics[fn.name.lowercase(Locale.ROOT)].orEmpty().any { usedMn ->
                    val defEnd = macroDefEnd[usedMn]
                    defEnd != null && defEnd > idx
                }
            val eligible =
                !usesForwardMacro &&
                    (
                        fn.explicit ||
                            (!fn.callsOthers && fn.size <= threshold && loops.any { idx in it })
                    )
            if (!eligible) {
                outLines.add(line)
                outOrigins.add(callOrigin)
                continue
            }
            val (argText, dest) = splitDest(m.groupValues[4])
            val args =
                if (argText.contains(',')) {
                    splitTopLevel(argText)
                } else {
                    argText.split(Regex("""\s+""")).filter { it.isNotEmpty() }
                }
            if (args.size != fn.params.size) {
                outLines.add(line)
                outOrigins.add(callOrigin)
                continue
            }
            val (expLines, expOrigins) =
                expand(fn, m.groupValues[1], m.groupValues[2], args, dest, callOrigin, siteCounter++)
            outLines.addAll(expLines)
            outOrigins.addAll(expOrigins)
            changed = true
        }
        return Triple(outLines, outOrigins, changed)
    }

    private val readOnlyFirstOperand =
        setOf(
            "from",
            "add",
            "adc",
            "sub",
            "sbc",
            "and",
            "or",
            "xor",
            "bic",
            "stw",
            "stb",
            "ldw",
            "ldb",
            "cmp",
            "mult",
            "umult",
            "fmult",
            "lmult",
            "jmp",
            "ljmp",
            "gsu_stack_push",
            "forr",
            "plot",
        )

    private fun paramWritten(
        param: String,
        body: List<String>,
    ): Boolean {
        val word = Regex("""(?<![\w@.$#])${Regex.escape(param)}(?![\w:])""", RegexOption.IGNORE_CASE)
        for (line in body) {
            val b = bodyOf(line)
            if (b.isEmpty()) continue
            val mn = b.substringBefore(' ').substringBefore('\t').lowercase(Locale.ROOT)
            val operands = b.substring(mn.length).trim()
            if (mn == "register" || mn == "return") continue
            if (mn == "to" || mn == "with") {
                if (word.containsMatchIn(operands)) return true
                continue
            }
            if (mn == "call") {
                val afterCallee = operands.replaceFirst(Regex("""^\w+\s*"""), "")
                val (_, dest) = splitDest(afterCallee)
                if (dest != null && word.matches(dest)) return true
                continue
            }
            if (mn.startsWith("if_")) {
                if (ifStatementWritesParam(operands, word)) return true
                continue
            }
            val first = operands.substringBefore(',').substringBefore(' ').trim()
            if (word.matches(first) && mn !in readOnlyFirstOperand) return true
        }
        return false
    }

    private fun ifStatementWritesParam(
        operands: String,
        paramWord: Regex,
    ): Boolean {
        // Extract action tokens (either inside `{ ... }` or after two condition arguments)
        val braceBody =
            if (operands.contains('{')) {
                operands.substringAfter('{').substringBeforeLast('}')
            } else {
                val parts = splitTopLevel(operands)
                if (parts.size >= 3) parts.drop(2).joinToString(" ") else operands
            }
        val tokens =
            braceBody
                .replace(',', ' ')
                .split(Regex("""\s+"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            val op = tokens[i].lowercase(Locale.ROOT)
            if (op in ifWriteMnemonics && i + 1 < tokens.size) {
                if (paramWord.matches(tokens[i + 1])) return true
            }
            if (op == "call") {
                val rest = tokens.drop(i + 2).joinToString(" ")
                val (_, dest) = splitDest(rest)
                if (dest != null && paramWord.matches(dest)) return true
            }
            i++
        }
        return false
    }

    private fun expand(
        fn: Fn,
        indent: String,
        labelPrefix: String,
        args: List<String>,
        dest: String?,
        callOrigin: SourceOrigin,
        k: Int,
    ): Pair<List<String>, List<SourceOrigin>> {
        val prefix = "__inline_${fn.name}_${k}_"
        val rename = LinkedHashMap<String, String>()
        val out = mutableListOf<String>()
        val outOrigins = mutableListOf<SourceOrigin>()

        fun emit(
            text: String,
            origin: SourceOrigin = callOrigin,
        ) {
            out.add(text)
            outOrigins.add(origin)
        }

        if (labelPrefix.isNotBlank()) emit("$indent${labelPrefix.trim()}")
        emit("$indent; [sfx-optimizer] inline ${fn.name} #$k")

        val bodyDeclared = linkedSetOf<String>()
        for (line in fn.body) {
            val b = bodyOf(line)
            if (b.lowercase(Locale.ROOT).startsWith("register")) {
                val decl = b.substring("register".length)
                for (part in decl.split(',')) {
                    val name = part.substringBefore('=').trim()
                    if (name.isNotEmpty()) bodyDeclared.add(name)
                }
            }
        }

        var retVar = fn.retVar
        if (retVar == null) {
            retVar =
                fn.body
                    .mapNotNull {
                        Regex("""^return\s+([A-Za-z_]\w*)\s*$""", RegexOption.IGNORE_CASE)
                            .find(bodyOf(it))
                            ?.groupValues
                            ?.get(1)
                    }.firstOrNull()
        }

        val needDeclare = mutableListOf<String>()
        val bindings = mutableListOf<String>()
        for ((i, param) in fn.params.withIndex()) {
            val arg = args[i]
            val aliasable =
                !arg.startsWith("#") &&
                    arg.matches(Regex("""[A-Za-z_]\w*""")) &&
                    !hardwareRegex.matches(arg) &&
                    !paramWritten(param, fn.body) &&
                    param.lowercase(Locale.ROOT) != retVar?.lowercase(Locale.ROOT)
            if (aliasable) {
                rename[param] = arg
            } else {
                rename[param] = prefix + param
                if (param !in bodyDeclared) needDeclare.add(prefix + param)
                bindings.add(
                    if (arg.startsWith("#")) "${indent}iwt ${prefix + param}, $arg" else "${indent}move ${prefix + param}, $arg",
                )
            }
        }
        if (retVar != null && retVar !in rename) {
            rename[retVar] = prefix + retVar
            if (retVar !in bodyDeclared) needDeclare.add(prefix + retVar)
        }
        for (v in bodyDeclared) if (v !in rename) rename[v] = prefix + v

        if (needDeclare.isNotEmpty()) emit("${indent}register ${needDeclare.joinToString(", ")}")
        for (b in bindings) emit(b)

        val nonCheapLabelDefRegex = Regex("""^\s*([A-Za-z_]\w*):(?!:)""")
        val bodyLabels = LinkedHashMap<String, String>()
        for (line in fn.body) {
            val lblMatch = nonCheapLabelDefRegex.find(code(line))
            if (lblMatch != null) {
                val lblName = lblMatch.groupValues[1]
                bodyLabels[lblName.lowercase(Locale.ROOT)] = "@${lblName}_inline_$k"
            }
        }
        val bodyLabelRegex =
            if (bodyLabels.isEmpty()) {
                null
            } else {
                Regex(
                    """(?<![\w@.$:])(${bodyLabels.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})(?!\w)""",
                    RegexOption.IGNORE_CASE,
                )
            }

        val renameRegex =
            if (rename.isEmpty()) {
                null
            } else {
                Regex(
                    """(?<![\w@.$#])(${rename.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})(?![\w:])""",
                    RegexOption.IGNORE_CASE,
                )
            }
        val renameMap = rename.mapKeys { it.key.lowercase(Locale.ROOT) }

        fun renameCode(text: String): String {
            var t = text
            if (renameRegex != null) t = renameRegex.replace(t) { renameMap[it.value.lowercase(Locale.ROOT)] ?: it.value }
            t = Regex("""@(\w+)""").replace(t) { "@${it.groupValues[1]}_inline_$k" }
            if (bodyLabelRegex != null) {
                t = bodyLabelRegex.replace(t) { bodyLabels[it.value.lowercase(Locale.ROOT)] ?: it.value }
            }
            return t
        }

        val exit = "@inline_exit_$k"
        var exitUsed = false
        val lastStatement = fn.body.indexOfLast { bodyOf(it).isNotEmpty() }
        val retRegex = Regex("""^return\b\s*(.*)$""", RegexOption.IGNORE_CASE)
        val renamedRet = retVar?.let { rename[it] }

        for ((bi, line) in fn.body.withIndex()) {
            val lineOrigin = fn.bodyOrigins.getOrElse(bi) { callOrigin }
            val comment = if (line.contains(';')) line.substring(line.indexOf(';')) else ""
            val codePart = code(line)
            val bodyText = bodyOf(line)
            val retMatch = retRegex.matchEntire(bodyText)
            if (retMatch != null) {
                val lbl = Regex("""^\s*(@?[A-Za-z_]\w*):(?!:)""").find(codePart)?.let { "${renameCode(it.groupValues[1])}:" }
                if (lbl != null) emit("$indent$lbl", lineOrigin)
                val operand = retMatch.groupValues[1].trim()
                if (operand.isNotEmpty() && renamedRet != null) {
                    if (operand.startsWith("#")) {
                        emit("${indent}iwt $renamedRet, ${renameCode(operand)}", lineOrigin)
                    } else {
                        val src = renameCode(operand)
                        if (!src.equals(renamedRet, true)) emit("${indent}move $renamedRet, $src", lineOrigin)
                    }
                }
                if (bi != lastStatement) {
                    emit("${indent}bra $exit", lineOrigin)
                    emit("${indent}nop", lineOrigin)
                    exitUsed = true
                }
                continue
            }
            val newCode = renameCode(codePart)
            emit(
                if (comment.isNotEmpty() && newCode.isBlank()) {
                    "$indent$comment"
                } else {
                    newCode.trimEnd().let {
                        if (it.isBlank()) {
                            it
                        } else if (it.first().isWhitespace()) {
                            indent + it.trimStart()
                        } else {
                            it
                        }
                    } + if (comment.isNotEmpty()) " $comment" else ""
                },
                lineOrigin,
            )
        }

        if (exitUsed) emit("$indent$exit:")
        if (dest != null && renamedRet != null) emit("${indent}move $dest, $renamedRet")
        return out to outOrigins
    }

    private fun finalize(
        lines: List<String>,
        origins: List<SourceOrigin>,
        changed: Boolean,
    ): Pair<List<String>, List<SourceOrigin>> {
        val fns = parseFunctions(lines, origins)
        val remove = mutableSetOf<Int>()
        val strip = mutableSetOf<Int>()
        for (fn in fns) {
            if (!fn.explicit) continue
            val stillCalled =
                lines.indices.any { idx ->
                    idx !in fn.start..fn.end &&
                        callRegex
                            .matchEntire(code(lines[idx]).trimEnd())
                            ?.groupValues
                            ?.get(3)
                            ?.equals(fn.name, true) == true
                }
            if (!stillCalled && !fn.selfRecursive) {
                for (i in fn.start..fn.end) remove.add(i)
            } else {
                strip.add(fn.start)
            }
        }
        if (!changed && remove.isEmpty() && strip.isEmpty()) return lines to origins
        val finalLines = mutableListOf<String>()
        val finalOrigins = mutableListOf<SourceOrigin>()
        for (idx in lines.indices) {
            if (idx in remove) continue
            finalLines.add(
                if (idx in strip) lines[idx].replaceFirst(Regex("""\binline\s+""", RegexOption.IGNORE_CASE), "") else lines[idx],
            )
            finalOrigins.add(origins[idx])
        }
        return finalLines to finalOrigins
    }
}
