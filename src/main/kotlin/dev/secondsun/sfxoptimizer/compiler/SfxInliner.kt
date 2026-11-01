package dev.secondsun.sfxoptimizer.compiler

import dev.secondsun.sfxoptimizer.analysis.SfxLivenessAnalyzer
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
        val callsOthers: Boolean,
        val selfRecursive: Boolean,
        val size: Int,
    )

    private val headerRegex = Regex("""^(\s*)((?:[@\w.]+:\s*)?)(inline\s+)?function\s+(\w+)\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val callRegex = Regex("""^(\s*)((?:[@\w.]+:\s*)?)call\s+(\w+)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val endFunctionRegex = Regex("""^\s*(?:[@\w.]+:\s*)?endfunction\b""", RegexOption.IGNORE_CASE)
    private val hardwareRegex = Regex("""^r\d+$""", RegexOption.IGNORE_CASE)

    private var siteCounter = 0

    private fun code(line: String): String = line.substringBefore(';')

    private fun bodyOf(line: String): String = code(line).replace(Regex("""^\s*[@\w.]+:"""), "").trim()

    fun inline(source: String): String {
        var lines = source.lines()
        siteCounter = 0
        var changedAny = false
        repeat(8) {
            val (next, changed) = pass(lines)
            lines = next
            if (!changed) return@repeat
            changedAny = true
        }
        return finalize(lines, changedAny)
    }

    private fun parseFunctions(lines: List<String>): List<Fn> {
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
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            val retVar = if (rest.contains(':')) rest.substringAfter(':').trim().ifEmpty { null } else null
            val body = lines.subList(i + 1, end).toList()
            val calls = body.mapNotNull { callRegex.matchEntire(code(it).trimEnd())?.groupValues?.get(3) }
            val size =
                body.count {
                    val b = bodyOf(it)
                    b.isNotEmpty() && !b.lowercase(Locale.ROOT).startsWith("register")
                }
            fns.add(
                Fn(
                    name,
                    i,
                    end,
                    params,
                    retVar,
                    m.groupValues[3].isNotBlank(),
                    body,
                    calls.isNotEmpty(),
                    calls.any { it.equals(name, true) },
                    size,
                ),
            )
            i = end + 1
        }
        return fns
    }

    private fun loopRanges(lines: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        val forStack = ArrayDeque<Int>()
        val labels = mutableMapOf<String, Int>()
        val jumpRegex = Regex("""^(?:iwt\s+r15\s*,\s*#?|b\w+\s+|jmp\s+|loop\s+)(@?\w+)""", RegexOption.IGNORE_CASE)
        for ((idx, line) in lines.withIndex()) {
            val c = code(line)
            Regex("""^\s*(@?\w+):""").find(c)?.let { labels[it.groupValues[1].lowercase(Locale.ROOT)] = idx }
            val b = bodyOf(line)
            val mn = b.substringBefore(' ').lowercase(Locale.ROOT)
            when (mn) {
                "for", "forr" -> forStack.addLast(idx)
                "endfor" -> if (forStack.isNotEmpty()) ranges.add(forStack.removeLast()..idx)
                else ->
                    jumpRegex.find(b)?.let {
                        val target = labels[it.groupValues[1].lowercase(Locale.ROOT)]
                        if (target != null && target < idx) ranges.add(target..idx)
                    }
            }
        }
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

    private fun pass(lines: List<String>): Pair<List<String>, Boolean> {
        val fns = parseFunctions(lines)
        val byName = fns.associateBy { it.name.lowercase(Locale.ROOT) }
        val loops = loopRanges(lines)
        val macroLines = SfxLivenessAnalyzer.findMacroDefinitionLines(lines)
        val out = mutableListOf<String>()
        var changed = false

        for ((idx, line) in lines.withIndex()) {
            val m = if (idx in macroLines) null else callRegex.matchEntire(code(line).trimEnd())
            val fn = m?.let { byName[it.groupValues[3].lowercase(Locale.ROOT)] }
            if (m == null || fn == null || fn.selfRecursive || (idx in fn.start..fn.end)) {
                out.add(line)
                continue
            }
            val eligible =
                fn.explicit ||
                    (!fn.callsOthers && fn.size <= threshold && loops.any { idx in it })
            if (!eligible) {
                out.add(line)
                continue
            }
            val (argText, dest) = splitDest(m.groupValues[4])
            val args = splitTopLevel(argText)
            if (args.size != fn.params.size) {
                out.add(line)
                continue
            }
            out.addAll(expand(fn, m.groupValues[1], m.groupValues[2], args, dest, siteCounter++))
            changed = true
        }
        return out to changed
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
            val mn = b.substringBefore(' ').lowercase(Locale.ROOT)
            val operands = b.substringAfter(' ', "").trim()
            if (mn == "register") continue
            if (mn == "to" || mn == "with") {
                if (word.containsMatchIn(operands)) return true
                continue
            }
            if (mn.startsWith("if_") || mn == "return" || mn == "call") continue
            val first = operands.substringBefore(',').trim()
            if (word.matches(first) && mn !in readOnlyFirstOperand) return true
        }
        return false
    }

    private fun expand(
        fn: Fn,
        indent: String,
        labelPrefix: String,
        args: List<String>,
        dest: String?,
        k: Int,
    ): List<String> {
        val prefix = "__inline_${fn.name}_${k}_"
        val rename = LinkedHashMap<String, String>()
        val out = mutableListOf<String>()

        if (labelPrefix.isNotBlank()) out.add("$indent${labelPrefix.trim()}")
        out.add("$indent; [sfx-optimizer] inline ${fn.name} #$k")

        // Variables declared by `register` lines inside the body
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

        // Infer a return variable if the header omitted one
        var retVar = fn.retVar
        if (retVar == null) {
            retVar =
                fn.body
                    .mapNotNull { Regex("""^return\s+([A-Za-z_]\w*)\s*$""", RegexOption.IGNORE_CASE).find(bodyOf(it))?.groupValues?.get(1) }
                    .firstOrNull()
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

        if (needDeclare.isNotEmpty()) out.add("${indent}register ${needDeclare.joinToString(", ")}")
        out.addAll(bindings)

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
            return Regex("""@(\w+)""").replace(t) { "@${it.groupValues[1]}_inline_$k" }
        }

        val exit = "@inline_exit_$k"
        var exitUsed = false
        val lastStatement = fn.body.indexOfLast { bodyOf(it).isNotEmpty() }
        val retRegex = Regex("""^return\b\s*(.*)$""", RegexOption.IGNORE_CASE)
        val renamedRet = retVar?.let { rename[it] }

        for ((bi, line) in fn.body.withIndex()) {
            val comment = if (line.contains(';')) line.substring(line.indexOf(';')) else ""
            val codePart = code(line)
            val bodyText = bodyOf(line)
            val retMatch = retRegex.matchEntire(bodyText)
            if (retMatch != null) {
                val lbl = Regex("""^\s*(@\w+):""").find(codePart)?.let { "${renameCode(it.groupValues[1])}:" }
                if (lbl != null) out.add("$indent$lbl")
                val operand = retMatch.groupValues[1].trim()
                if (operand.isNotEmpty() && renamedRet != null) {
                    if (operand.startsWith("#")) {
                        out.add("${indent}iwt $renamedRet, ${renameCode(operand)}")
                    } else {
                        val src = renameCode(operand)
                        if (!src.equals(renamedRet, true)) out.add("${indent}move $renamedRet, $src")
                    }
                }
                if (bi != lastStatement) {
                    out.add("${indent}bra $exit")
                    out.add("${indent}nop")
                    exitUsed = true
                }
                continue
            }
            val newCode = renameCode(codePart)
            out.add(
                if (comment.isNotEmpty() &&
                    newCode.isBlank()
                ) {
                    "$indent$comment"
                } else {
                    (
                        newCode.trimEnd().let {
                            if (it.isBlank()) {
                                it
                            } else if (it.first().isWhitespace()) {
                                indent +
                                    it.trimStart()
                            } else {
                                it
                            }
                        }
                    ) +
                        if (comment.isNotEmpty()) " $comment" else ""
                },
            )
        }

        if (exitUsed) out.add("$indent$exit:")
        if (dest != null && renamedRet != null) out.add("${indent}move $dest, $renamedRet")
        return out
    }

    private fun finalize(
        lines: List<String>,
        changed: Boolean,
    ): String {
        val fns = parseFunctions(lines)
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
        if (!changed && remove.isEmpty() && strip.isEmpty()) return lines.joinToString("\n")
        return lines
            .withIndex()
            .filter { it.index !in remove }
            .joinToString("\n") { (idx, line) ->
                if (idx in strip) line.replaceFirst(Regex("""\binline\s+""", RegexOption.IGNORE_CASE), "") else line
            }
    }
}
