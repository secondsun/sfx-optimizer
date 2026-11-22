package dev.secondsun.sfxoptimizer.compiler

import dev.secondsun.retro.util.sfx.SuperFxIsa
import dev.secondsun.sfxoptimizer.analysis.SfxLivenessAnalyzer
import java.util.Locale

/**
 * Post-allocation peephole pass over a lowered SuperFX instruction stream.
 *
 * Transformations:
 * - `iwt Rn, #imm` -> `ibt Rn, #imm` (`0..127`) or `ibt Rn, #<(imm)` (`-128..-1`, satisfying `GSU_Assembler.i` byte bounds)
 * - removal of `move Rx, Rx` outside branch delay slots
 * - removal of redundant `from r0`, `to r0`, and `with r0` prefixes
 * - fusion of `ldw/ldb (rN); move rM, r0` -> `to rM; ldw/ldb (rN)` when `r0` is provably overwritten before read
 * - `with Rn; mult #2` / `mult #4` strength reduction to `add Rn` (when flags are not consumed)
 *
 * Lines inside `.macro` definitions are never modified.
 */
object SfxPeepholeOptimizer {
    data class PeepholeResult(
        val code: String,
        val rewriteCount: Int,
    )

    private val DELAY_SLOT_CARRIERS =
        setOf(
            "bra",
            "beq",
            "bne",
            "bcc",
            "bcs",
            "bpl",
            "bmi",
            "bvc",
            "bvs",
            "blt",
            "bge",
            "jmp",
            "ljmp",
            "loop",
            "call",
            "return",
            "endfor",
            "link",
        )
    private val CONDITIONAL_BRANCHES =
        setOf("beq", "bne", "bcc", "bcs", "bpl", "bmi", "bvc", "bvs", "blt", "bge")

    private val LINE_REGEX = Regex("""^(\s*)((?:[@\w.]+:\s*|\s*:\s*)?)([^;]*?)\s*(;.*)?$""")
    private val IWT_REGEX = Regex("""^iwt\s+(r\d+)\s*,\s*#?\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val MOVE_REGEX = Regex("""^move\s+(r\d+)\s*,\s*(r\d+)$""", RegexOption.IGNORE_CASE)
    private val WITH_REGEX = Regex("""^with\s+(r\d+)$""", RegexOption.IGNORE_CASE)
    private val MULT_REGEX = Regex("""^mult\s+#\s*([24])$""", RegexOption.IGNORE_CASE)
    private val LOAD_IND_REGEX = Regex("""^(ldw|ldb)\s+\((r\d+)\)$""", RegexOption.IGNORE_CASE)
    private val INC_DEC_REGEX = Regex("""^(inc|dec)\s+(r\d+)$""", RegexOption.IGNORE_CASE)
    private val R0_WRITE_IMM_REGEX = Regex("""^(?:ibt|iwt|lm|lms)\s+r0\s*,.*$""", RegexOption.IGNORE_CASE)

    private class Parsed(
        val indent: String,
        val label: String,
        val code: String,
        val comment: String,
    ) {
        val mnemonic: String get() = code.substringBefore(' ').substringBefore('\t').lowercase(Locale.ROOT)
    }

    private fun parse(line: String): Parsed? {
        val m = LINE_REGEX.matchEntire(line) ?: return null
        return Parsed(m.groupValues[1], m.groupValues[2], m.groupValues[3].trim(), m.groupValues[4])
    }

    private fun render(
        p: Parsed,
        code: String,
    ): String {
        val head = "${p.indent}${p.label}$code".trimEnd()
        return if (p.comment.isEmpty()) {
            head
        } else if (head.isBlank()) {
            "${p.indent}${p.comment}"
        } else {
            "$head ${p.comment}"
        }
    }

    /** Parses a literal immediate (decimal, `$hex`, `%binary`, optional unary minus). Returns null for symbols/expressions. */
    fun parseLiteral(text: String): Long? {
        var t = text.trim()
        var negative = false
        if (t.startsWith("-")) {
            negative = true
            t = t.substring(1).trim()
        }
        val value =
            when {
                t.startsWith("$") -> t.substring(1).takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }?.toLongOrNull(16)
                t.startsWith("%") -> t.substring(1).takeIf { it.isNotEmpty() }?.toLongOrNull(2)
                t.isNotEmpty() && t.all { it.isDigit() } -> t.toLongOrNull()
                else -> null
            } ?: return null
        return if (negative) -value else value
    }

    private fun readsSreg(mnemonic: String?): Boolean {
        if (mnemonic == null) return false
        return SuperFxIsa.lookup(mnemonic).map { it.readsSreg() }.orElse(false)
    }

    private fun writesDreg(mnemonic: String?): Boolean {
        if (mnemonic == null) return false
        return SuperFxIsa.lookup(mnemonic).map { it.writesDreg() }.orElse(false)
    }

    fun optimize(source: String): String = optimizeWithStats(source).code

    fun optimizeWithStats(source: String): PeepholeResult {
        val lines = source.lines()
        val macroLines = SfxLivenessAnalyzer.findMacroDefinitionLines(lines)
        val out = ArrayList<String?>(lines.size)
        out.addAll(lines)
        var rewrites = 0

        fun isDirective(code: String): Boolean = code.startsWith(".")

        fun previousInstruction(i: Int): Int {
            var j = i - 1
            while (j >= 0) {
                val p = out[j]?.let { parse(it) }
                if (out[j] != null && p != null && p.code.isNotEmpty() && !isDirective(p.code)) return j
                j--
            }
            return -1
        }

        fun nextInstruction(i: Int): Int {
            var j = i + 1
            while (j < out.size) {
                val p = out[j]?.let { parse(it) }
                if (out[j] != null && p != null && p.code.isNotEmpty() && !isDirective(p.code)) return j
                j++
            }
            return -1
        }

        fun isR0DeadAfter(afterIdx: Int): Boolean {
            var cursor = afterIdx
            var skipped = 0
            while (skipped <= 2) {
                val nextIdx = nextInstruction(cursor)
                if (nextIdx < 0 || nextIdx in macroLines) return false
                val np = parse(out[nextIdx]!!) ?: return false
                if (np.label.isNotEmpty()) return false
                // Skip simple non-r0 pointer bumps (inc/dec rK where rK != r0)
                val incDec = INC_DEC_REGEX.matchEntire(np.code)
                if (incDec != null && !incDec.groupValues[2].equals("r0", ignoreCase = true)) {
                    cursor = nextIdx
                    skipped++
                    continue
                }
                // Unprefixed ldw/ldb (rK) with rK != r0 unconditionally overwrites r0
                val ld = LOAD_IND_REGEX.matchEntire(np.code)
                if (ld != null && !ld.groupValues[2].equals("r0", ignoreCase = true)) return true
                // Direct immediate/memory load into r0
                if (R0_WRITE_IMM_REGEX.matches(np.code)) return true
                // `move r0, rK` (rK != r0) overwrites r0 without reading r0
                val mv = MOVE_REGEX.matchEntire(np.code)
                if (mv != null &&
                    mv.groupValues[1].equals("r0", ignoreCase = true) &&
                    !mv.groupValues[2].equals("r0", ignoreCase = true)
                ) {
                    return true
                }
                return false
            }
            return false
        }

        fun inDelaySlot(i: Int): Boolean {
            val prev = previousInstruction(i)
            if (prev < 0) return false
            val pp = parse(out[prev]!!) ?: return false
            val mn = pp.mnemonic
            if (mn in DELAY_SLOT_CARRIERS) return true
            return mn == "iwt" &&
                IWT_REGEX
                    .matchEntire(pp.code)
                    ?.groupValues
                    ?.get(1)
                    ?.equals("r15", true) == true
        }

        for (i in lines.indices) {
            if (i in macroLines) continue
            val raw = out[i] ?: continue
            val p = parse(raw) ?: continue
            if (p.code.isEmpty() || isDirective(p.code)) continue

            // 1. iwt -> ibt (-128..-1 masked as #<(imm) for GSU_Assembler.i; 0..127 direct #imm)
            IWT_REGEX.matchEntire(p.code)?.let { m ->
                val reg = m.groupValues[1].lowercase(Locale.ROOT)
                val rawImm = m.groupValues[2].trim()
                val value = parseLiteral(rawImm)
                if (reg != "r15" && value != null && value in -128..127) {
                    val formattedImm = if (value < 0) "#<($rawImm)" else "#$rawImm"
                    out[i] = render(p, "ibt ${m.groupValues[1]}, $formattedImm")
                    rewrites++
                    return@let
                }
            }
            if (out[i] != raw) continue

            // 2. self move elimination
            MOVE_REGEX.matchEntire(p.code)?.let { m ->
                if (m.groupValues[1].equals(m.groupValues[2], ignoreCase = true) && !inDelaySlot(i)) {
                    out[i] = if (p.label.isNotEmpty() || p.comment.isNotEmpty()) render(p, "") else null
                    rewrites++
                    return@let
                }
            }
            if (out[i] != raw) continue

            // 3. redundant `from r0` / `to r0` / `with r0`
            if (p.code.equals("from r0", ignoreCase = true) && p.label.isEmpty() && !inDelaySlot(i)) {
                val prev = previousInstruction(i)
                val prevMn = if (prev >= 0) parse(out[prev]!!)?.mnemonic else null
                val next = nextInstruction(i)
                val nextMn = if (next >= 0) parse(out[next]!!)?.mnemonic else null
                if (prevMn != "with" && prevMn != "from" && readsSreg(nextMn)) {
                    out[i] = if (p.comment.isNotEmpty()) render(p, "") else null
                    rewrites++
                    continue
                }
            }
            if (p.code.equals("to r0", ignoreCase = true) && p.label.isEmpty() && !inDelaySlot(i)) {
                val prev = previousInstruction(i)
                val prevMn = if (prev >= 0) parse(out[prev]!!)?.mnemonic else null
                val next = nextInstruction(i)
                val nextMn = if (next >= 0) parse(out[next]!!)?.mnemonic else null
                if (prevMn != "with" && prevMn != "to" && writesDreg(nextMn)) {
                    out[i] = if (p.comment.isNotEmpty()) render(p, "") else null
                    rewrites++
                    continue
                }
            }
            if (p.code.equals("with r0", ignoreCase = true) && p.label.isEmpty() && !inDelaySlot(i)) {
                val prev = previousInstruction(i)
                val prevMn = if (prev >= 0) parse(out[prev]!!)?.mnemonic else null
                val next = nextInstruction(i)
                val nextMn = if (next >= 0) parse(out[next]!!)?.mnemonic else null
                if (prevMn !in setOf("with", "from", "to") && (readsSreg(nextMn) || writesDreg(nextMn))) {
                    out[i] = if (p.comment.isNotEmpty()) render(p, "") else null
                    rewrites++
                    continue
                }
            }

            // 4. fuse `ldw/ldb (rN); move rM, r0` -> `to rM; ldw/ldb (rN)` when r0 is dead after move
            val ldMatch = LOAD_IND_REGEX.matchEntire(p.code)
            if (ldMatch != null && !inDelaySlot(i)) {
                val prev = previousInstruction(i)
                val prevMn = if (prev >= 0) parse(out[prev]!!)?.mnemonic else null
                val nextIdx = nextInstruction(i)
                val nextParsed = if (nextIdx >= 0 && nextIdx !in macroLines) parse(out[nextIdx]!!) else null
                val mvMatch = nextParsed?.let { MOVE_REGEX.matchEntire(it.code) }
                if (prevMn !in setOf("to", "with") &&
                    nextParsed != null &&
                    nextParsed.label.isEmpty() &&
                    mvMatch != null &&
                    mvMatch.groupValues[2].equals("r0", ignoreCase = true) &&
                    !mvMatch.groupValues[1].equals("r0", ignoreCase = true) &&
                    !inDelaySlot(nextIdx) &&
                    isR0DeadAfter(nextIdx)
                ) {
                    val dstReg = mvMatch.groupValues[1]
                    val loadInsn = "${ldMatch.groupValues[1]} (${ldMatch.groupValues[2]})"
                    val prefixLine =
                        if (p.label.isNotEmpty()) {
                            "${p.indent}${p.label.trimEnd()}\n${p.indent}to $dstReg"
                        } else {
                            "${p.indent}to $dstReg"
                        }
                    val loadLine =
                        if (p.comment.isNotEmpty()) "${p.indent}$loadInsn ${p.comment}" else "${p.indent}$loadInsn"
                    out[i] = "$prefixLine\n$loadLine"
                    out[nextIdx] = if (nextParsed.comment.isNotEmpty()) render(nextParsed, "") else null
                    rewrites++
                    continue
                }
            }

            // 5. power-of-two multiply reduction: `with rN` + `mult #2|#4`
            val multMatch = MULT_REGEX.matchEntire(p.code)
            if (multMatch != null && p.label.isEmpty()) {
                val prev = previousInstruction(i)
                val prevParsed = if (prev >= 0) parse(out[prev]!!) else null
                val withMatch = prevParsed?.let { WITH_REGEX.matchEntire(it.code) }
                if (prevParsed != null && withMatch != null && prevParsed.label.isEmpty() && !inDelaySlot(prev)) {
                    val next = nextInstruction(i)
                    val nextMn = if (next >= 0) parse(out[next]!!)?.mnemonic else null
                    if (nextMn == null || nextMn !in CONDITIONAL_BRANCHES) {
                        val reg = withMatch.groupValues[1]
                        val addLine = "${p.indent}add $reg"
                        out[i] =
                            if (multMatch.groupValues[1] == "2") {
                                render(p, "add $reg")
                            } else {
                                "${render(p, "add $reg")}\n${p.indent}with $reg\n$addLine"
                            }
                        rewrites++
                    }
                }
            }
        }

        return PeepholeResult(out.filterNotNull().joinToString("\n"), rewrites)
    }
}
