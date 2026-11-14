package dev.secondsun.sfxoptimizer.analysis

import dev.secondsun.retro.util.FunctionSyntaxHelper
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.sfx.SuperFxIsa
import dev.secondsun.retro.util.vo.TokenizedFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Immutable basic block in a SuperFX Control Flow Graph.
 *
 * [startLine], [endLine], and [lineIndices] use 0-based line indices into the owning [TokenizedFile].
 * [entrances] and [exits] hold the exact [id] strings of predecessor and successor [CfgBlock]s.
 */
data class CfgBlock(
    val id: String,
    val scopeName: String,
    val startLine: Int,
    val endLine: Int,
    val lineIndices: List<Int>,
    val entrances: List<String>,
    val exits: List<String>,
    val terminatesWithReturnOrStop: Boolean = false,
    val unresolvedDynamicJump: Boolean = false,
)

/**
 * Natural or structured loop discovered in a [ScopeCfg].
 */
data class NaturalLoop(
    val headerBlockId: String,
    val latchBlockId: String,
    val startLine: Int,
    val endLine: Int,
    val isHardwareForLoop: Boolean,
    val hasEarlyExit: Boolean,
) {
    val lineRange: IntRange get() = startLine..endLine
}

/**
 * Immutable Control Flow Graph for a single isolated SuperFX scope (`(main)` or `[inline] function`).
 */
data class ScopeCfg(
    val scopeName: String,
    val blocks: List<CfgBlock>,
    val loops: List<NaturalLoop>,
) {
    private val byId: Map<String, CfgBlock> = blocks.associateBy { it.id }

    fun block(id: String): CfgBlock? = byId[id]
}

/**
 * Builds immutable per-scope Control Flow Graphs ([ScopeCfg]) with SuperFX 1-instruction delay-slot
 * handling, cheap local (`@label`) and anonymous (`:`, `:-`, `:+`) label resolution, `iwt r15` /
 * `iwt r8..r13` + `jmp` / `move r13, r15` + `loop` target tracking, and natural loop extraction.
 */
object SfxCfgBuilder {
    private val conditionalBranches =
        setOf("beq", "bne", "bcc", "bcs", "bpl", "bmi", "bvc", "bvs", "blt", "bge")

    private val colonlessDataDirectives =
        setOf(".byte", ".byt", ".dbyt", ".word", ".addr", ".faraddr", ".dword", ".res", ".tag", ".asciiz", ".literal")

    private enum class EdgeKind {
        NONE,
        UNCONDITIONAL,
        CONDITIONAL,
        CONDITIONAL_DYNAMIC,
        RETURN_OR_STOP,
        DYNAMIC_UNRESOLVED,
    }

    private data class TerminatorInfo(
        val stmtLine: Int,
        val blockEndPos: Int,
        val kind: EdgeKind,
        val targetLine: Int? = null,
    )

    fun buildScopeCfg(
        file: TokenizedFile,
        scopeName: String,
        scopeLines: Collection<Int>,
        blockIdCounter: AtomicInteger = AtomicInteger(0),
        blockIdPrefix: String = "block",
    ): ScopeCfg {
        val sortedLines = scopeLines.sorted()
        val execLines =
            sortedLines.filter { lineIdx ->
                val toks = getCodeTokens(file, lineIdx)
                if (toks.isEmpty()) {
                    false
                } else {
                    val (_, body) = splitLabelAndBody(toks)
                    if (body.isEmpty()) {
                        true // keep label-bearing lines so jump targets attach cleanly
                    } else {
                        val first = body[0].text().lowercase(Locale.ROOT)
                        val second = if (body.size >= 2) body[1].text().lowercase(Locale.ROOT) else ""
                        !FunctionSyntaxHelper.isFunction(body) &&
                            !FunctionSyntaxHelper.isEndFunction(body) &&
                            first != "register" &&
                            body[0].type != TokenType.TOK_REGISTER_KEYWORD &&
                            !first.startsWith(".") &&
                            second != "=" &&
                            second != ":=" &&
                            second !in colonlessDataDirectives
                    }
                }
            }

        if (execLines.isEmpty()) {
            return ScopeCfg(scopeName, emptyList(), emptyList())
        }

        val lineToPos = HashMap<Int, Int>(execLines.size)
        execLines.forEachIndexed { idx, line -> lineToPos[line] = idx }

        // 1. Index named, cheap-local (@), and anonymous (:) labels inside this scope
        val namedLabels = mutableMapOf<String, Int>()
        val cheapLabelsByParent = mutableMapOf<String, MutableMap<String, Int>>()
        val uniqueCheapLabels = mutableMapOf<String, Int?>()
        val anonLabelPositions = mutableListOf<Int>()
        var currentParentLabel = "__root__"

        for ((pos, lineIdx) in execLines.withIndex()) {
            val toks = getCodeTokens(file, lineIdx)
            val (labelText, _) = splitLabelAndBody(toks)
            if (labelText != null) {
                val clean = labelText.removeSuffix(":").trim()
                if (clean.isEmpty()) {
                    anonLabelPositions.add(pos)
                } else {
                    val lower = clean.lowercase(Locale.ROOT)
                    if (lower.startsWith("@")) {
                        cheapLabelsByParent.getOrPut(currentParentLabel) { mutableMapOf() }[lower] = lineIdx
                        uniqueCheapLabels[lower] = if (lower in uniqueCheapLabels) null else lineIdx
                    } else {
                        currentParentLabel = lower
                        namedLabels[lower] = lineIdx
                    }
                }
            }
        }

        fun resolveLabelTarget(
            rawTarget: String,
            fromPos: Int,
            activeParent: String,
        ): Int? {
            val clean =
                rawTarget
                    .trim()
                    .removePrefix("#")
                    .removePrefix("(")
                    .removeSuffix(")")
                    .trim()
            if (clean.isEmpty()) return null
            if (clean.startsWith(":-")) {
                val count = clean.drop(1).count { it == '-' }
                val candidates = anonLabelPositions.filter { it <= fromPos }
                val idx = candidates.size - count
                return if (idx in candidates.indices) execLines[candidates[idx]] else null
            }
            if (clean.startsWith(":+")) {
                val count = clean.drop(1).count { it == '+' }
                val candidates = anonLabelPositions.filter { it > fromPos }
                val idx = count - 1
                return if (idx in candidates.indices) execLines[candidates[idx]] else null
            }
            val lower = clean.lowercase(Locale.ROOT)
            if (lower.startsWith("@")) {
                return cheapLabelsByParent[activeParent]?.get(lower) ?: uniqueCheapLabels[lower]
            }
            return namedLabels[lower]
        }

        // Helper: find next non-label-only instruction position for delay slot
        fun delaySlotEndPos(branchPos: Int): Int {
            var p = branchPos + 1
            while (p < execLines.size) {
                val (_, body) = splitLabelAndBody(getCodeTokens(file, execLines[p]))
                if (body.isNotEmpty()) {
                    return p
                }
                p++
            }
            return branchPos
        }

        // 2. Scan control-flow terminators, tracking constant label loads into r8..r13 & move r13, r15
        val terminatorsByEndPos = mutableMapOf<Int, TerminatorInfo>()
        val leaders = sortedSetOf(0)
        val regTargetLabels = mutableMapOf<String, Int>()
        val r13TargetStack = ArrayDeque<Int?>()
        val forStack = ArrayDeque<Int>() // stores body start pos
        val structuredForLoops = mutableListOf<Triple<Int, Int, Int>>() // (forLine, bodyStartLine, endforLine)
        currentParentLabel = "__root__"

        var pos = 0
        while (pos < execLines.size) {
            val lineIdx = execLines[pos]
            val toks = getCodeTokens(file, lineIdx)
            val (lbl, body) = splitLabelAndBody(toks)
            if (lbl != null) {
                val clean = lbl.removeSuffix(":").trim().lowercase(Locale.ROOT)
                if (clean.isNotEmpty() && !clean.startsWith("@")) {
                    currentParentLabel = clean
                }
            }
            if (body.isEmpty()) {
                pos++
                continue
            }

            val mn = body[0].text().lowercase(Locale.ROOT)
            val args = body.subList(1, body.size)

            when {
                mn == "for" || mn == "forr" -> {
                    regTargetLabels.remove("r13")
                    val bodyStartPos = (pos + 1).coerceAtMost(execLines.size - 1)
                    if (pos + 1 < execLines.size) {
                        leaders.add(pos + 1)
                    }
                    forStack.addLast(bodyStartPos)
                }

                mn == "endfor" -> {
                    regTargetLabels.remove("r13")
                    val bodyPos = if (forStack.isNotEmpty()) forStack.removeLast() else null
                    val targetLine = bodyPos?.let { execLines[it] }
                    if (bodyPos != null && pos >= bodyPos) {
                        val forHeaderPos = (bodyPos - 1).coerceAtLeast(0)
                        structuredForLoops.add(Triple(execLines[forHeaderPos], execLines[bodyPos], lineIdx))
                    }
                    terminatorsByEndPos[pos] = TerminatorInfo(lineIdx, pos, EdgeKind.CONDITIONAL, targetLine)
                    if (targetLine != null) lineToPos[targetLine]?.let { leaders.add(it) }
                    if (pos + 1 < execLines.size) leaders.add(pos + 1)
                }

                FunctionSyntaxHelper.isReturn(body) || mn == "stop" -> {
                    terminatorsByEndPos[pos] = TerminatorInfo(lineIdx, pos, EdgeKind.RETURN_OR_STOP)
                    if (pos + 1 < execLines.size) leaders.add(pos + 1)
                }

                mn == "bra" -> {
                    val targetText = args.joinToString("") { it.text() }
                    val targetLine = resolveLabelTarget(targetText, pos, currentParentLabel)
                    val endPos = delaySlotEndPos(pos)
                    terminatorsByEndPos[endPos] = TerminatorInfo(lineIdx, endPos, EdgeKind.UNCONDITIONAL, targetLine)
                    if (targetLine != null) lineToPos[targetLine]?.let { leaders.add(it) }
                    if (endPos + 1 < execLines.size) leaders.add(endPos + 1)
                    pos = endPos + 1
                    continue
                }

                mn in conditionalBranches -> {
                    val targetText = args.joinToString("") { it.text() }
                    val targetLine = resolveLabelTarget(targetText, pos, currentParentLabel)
                    val endPos = delaySlotEndPos(pos)
                    terminatorsByEndPos[endPos] = TerminatorInfo(lineIdx, endPos, EdgeKind.CONDITIONAL, targetLine)
                    if (targetLine != null) lineToPos[targetLine]?.let { leaders.add(it) }
                    if (endPos + 1 < execLines.size) leaders.add(endPos + 1)
                    pos = endPos + 1
                    continue
                }

                mn == "loop" -> {
                    val targetLine = regTargetLabels["r13"]
                    val endPos = delaySlotEndPos(pos)
                    val kind = if (targetLine != null) EdgeKind.CONDITIONAL else EdgeKind.CONDITIONAL_DYNAMIC
                    terminatorsByEndPos[endPos] = TerminatorInfo(lineIdx, endPos, kind, targetLine)
                    if (targetLine != null) lineToPos[targetLine]?.let { leaders.add(it) }
                    if (endPos + 1 < execLines.size) leaders.add(endPos + 1)
                    pos = endPos + 1
                    continue
                }

                mn == "jmp" || mn == "ljmp" -> {
                    val regTok = args.firstOrNull { it.text() != "(" && it.text() != ")" }?.text()?.lowercase(Locale.ROOT)
                    val endPos = delaySlotEndPos(pos)
                    if (regTok == "r11") {
                        terminatorsByEndPos[endPos] = TerminatorInfo(lineIdx, endPos, EdgeKind.RETURN_OR_STOP)
                    } else {
                        val knownTarget = regTok?.let { regTargetLabels[it] }
                        if (knownTarget != null) {
                            terminatorsByEndPos[endPos] =
                                TerminatorInfo(lineIdx, endPos, EdgeKind.UNCONDITIONAL, knownTarget)
                            lineToPos[knownTarget]?.let { leaders.add(it) }
                        } else {
                            terminatorsByEndPos[endPos] = TerminatorInfo(lineIdx, endPos, EdgeKind.DYNAMIC_UNRESOLVED)
                        }
                    }
                    if (endPos + 1 < execLines.size) leaders.add(endPos + 1)
                    pos = endPos + 1
                    continue
                }

                mn == "iwt" && args.isNotEmpty() -> {
                    val dstReg = args[0].text().lowercase(Locale.ROOT)
                    val expr =
                        args
                            .drop(1)
                            .filter { it.text() != "," }
                            .joinToString("") { it.text() }
                    if (dstReg == "r15") {
                        val targetLine = resolveLabelTarget(expr, pos, currentParentLabel)
                        val endPos = delaySlotEndPos(pos)
                        terminatorsByEndPos[endPos] =
                            TerminatorInfo(lineIdx, endPos, EdgeKind.UNCONDITIONAL, targetLine)
                        if (targetLine != null) lineToPos[targetLine]?.let { leaders.add(it) }
                        if (endPos + 1 < execLines.size) leaders.add(endPos + 1)
                        pos = endPos + 1
                        continue
                    } else if (SuperFxIsa.parsePhysicalRegister(dstReg).orElse(-1) in 8..13) {
                        val resolved = resolveLabelTarget(expr, pos, currentParentLabel)
                        if (resolved != null) {
                            regTargetLabels[dstReg] = resolved
                        } else {
                            regTargetLabels.remove(dstReg)
                        }
                    }
                }

                mn == "move" &&
                    args.size >= 3 &&
                    args[0].text().equals("r13", ignoreCase = true) &&
                    args.last().text().equals("r15", ignoreCase = true) -> {
                    if (pos + 1 < execLines.size) {
                        regTargetLabels["r13"] = execLines[pos + 1]
                        leaders.add(pos + 1)
                    }
                }

                mn == "gsu_stack_push" && args.firstOrNull()?.text()?.equals("r13", ignoreCase = true) == true -> {
                    r13TargetStack.addLast(regTargetLabels["r13"])
                }

                mn == "gsu_stack_pop" && args.isNotEmpty() -> {
                    val dstReg = args[0].text().lowercase(Locale.ROOT)
                    if (dstReg == "r13") {
                        val restored = if (r13TargetStack.isNotEmpty()) r13TargetStack.removeLast() else null
                        if (restored != null) {
                            regTargetLabels["r13"] = restored
                        } else {
                            regTargetLabels.remove("r13")
                        }
                    } else {
                        regTargetLabels.remove(dstReg)
                    }
                }

                mn in
                    setOf(
                        "to",
                        "with",
                        "ibt",
                        "lm",
                        "lms",
                        "lea",
                        "move",
                        "moves",
                        "moveb",
                        "movew",
                        "inc",
                        "dec",
                        "shl",
                        "gsu_stack_peek",
                    ) &&
                    args.isNotEmpty() -> {
                    val dstReg = args[0].text().lowercase(Locale.ROOT)
                    if (SuperFxIsa.parsePhysicalRegister(dstReg).orElse(-1) in 8..13) {
                        regTargetLabels.remove(dstReg)
                    }
                }

                mn.startsWith("if_") -> {
                    for (tokIdx in 0 until args.size - 1) {
                        val subMn = args[tokIdx].text().lowercase(Locale.ROOT)
                        if (subMn == "bra" || subMn in conditionalBranches) {
                            val raw = args[tokIdx + 1].text().removeSuffix("}")
                            val targetLine = resolveLabelTarget(raw, pos, currentParentLabel)
                            terminatorsByEndPos[pos] =
                                TerminatorInfo(lineIdx, pos, EdgeKind.CONDITIONAL, targetLine)
                            if (targetLine != null) lineToPos[targetLine]?.let { leaders.add(it) }
                            if (pos + 1 < execLines.size) leaders.add(pos + 1)
                            break
                        }
                    }
                }
            }

            pos++
        }

        // Also mark any label that is referenced anywhere in the scope as a block leader if jumped to
        // (already added via resolveLabelTarget above; also split if a label follows code and is branched to).
        val leaderList = leaders.filter { it in execLines.indices }.sorted()

        // 3. Construct basic block slices
        data class RawBlock(
            val id: String,
            val startPos: Int,
            val endPos: Int,
            val lines: List<Int>,
            val terminator: TerminatorInfo?,
        )

        val posToBlockId = arrayOfNulls<String>(execLines.size)
        val rawBlocks = mutableListOf<RawBlock>()

        for (i in leaderList.indices) {
            val startPos = leaderList[i]
            val endPos = if (i + 1 < leaderList.size) leaderList[i + 1] - 1 else execLines.size - 1
            if (startPos > endPos) continue
            val id = "$blockIdPrefix-${blockIdCounter.incrementAndGet()}"
            val slice = execLines.subList(startPos, endPos + 1)
            val term = (startPos..endPos).firstNotNullOfOrNull { terminatorsByEndPos[it] }
            for (p in startPos..endPos) {
                posToBlockId[p] = id
            }
            rawBlocks.add(RawBlock(id, startPos, endPos, slice, term))
        }

        // 4. Wire entrances and exits
        val exitsMap = rawBlocks.associate { it.id to linkedSetOf<String>() }
        val entrancesMap = rawBlocks.associate { it.id to linkedSetOf<String>() }

        fun addEdge(
            fromId: String,
            toId: String?,
        ) {
            if (toId == null) return
            exitsMap[fromId]?.add(toId)
            entrancesMap[toId]?.add(fromId)
        }

        for ((idx, b) in rawBlocks.withIndex()) {
            val nextBlockId = if (idx + 1 < rawBlocks.size) rawBlocks[idx + 1].id else null
            val term = b.terminator
            when (term?.kind) {
                null, EdgeKind.NONE -> {
                    addEdge(b.id, nextBlockId)
                }
                EdgeKind.UNCONDITIONAL -> {
                    val targetBlockId = term.targetLine?.let { lineToPos[it] }?.let { posToBlockId[it] }
                    addEdge(b.id, targetBlockId)
                }
                EdgeKind.CONDITIONAL -> {
                    val targetBlockId = term.targetLine?.let { lineToPos[it] }?.let { posToBlockId[it] }
                    addEdge(b.id, targetBlockId)
                    addEdge(b.id, nextBlockId)
                }
                EdgeKind.CONDITIONAL_DYNAMIC -> {
                    addEdge(b.id, nextBlockId)
                }
                EdgeKind.RETURN_OR_STOP, EdgeKind.DYNAMIC_UNRESOLVED -> {
                    // No intra-scope successor blocks
                }
            }
        }

        val cfgBlocks =
            rawBlocks.map { b ->
                CfgBlock(
                    id = b.id,
                    scopeName = scopeName,
                    startLine = b.lines.first(),
                    endLine = b.lines.last(),
                    lineIndices = b.lines,
                    entrances = entrancesMap[b.id]?.toList() ?: emptyList(),
                    exits = exitsMap[b.id]?.toList() ?: emptyList(),
                    terminatesWithReturnOrStop = b.terminator?.kind == EdgeKind.RETURN_OR_STOP,
                    unresolvedDynamicJump =
                        b.terminator?.kind == EdgeKind.DYNAMIC_UNRESOLVED ||
                            b.terminator?.kind == EdgeKind.CONDITIONAL_DYNAMIC,
                )
            }

        // 5. Extract natural loops (structured `for`/`forR` + CFG back-edges)
        val blockById = cfgBlocks.associateBy { it.id }
        val loops = mutableListOf<NaturalLoop>()
        val seenRanges = mutableSetOf<Pair<Int, Int>>()

        fun computeEarlyExit(
            loopStart: Int,
            loopEnd: Int,
        ): Boolean {
            for (rb in rawBlocks) {
                if (rb.lines.last() < loopStart || rb.lines.first() > loopEnd) continue
                val term = rb.terminator ?: continue
                if (term.stmtLine !in loopStart until loopEnd) continue
                when (term.kind) {
                    EdgeKind.RETURN_OR_STOP, EdgeKind.DYNAMIC_UNRESOLVED, EdgeKind.CONDITIONAL_DYNAMIC -> return true
                    EdgeKind.UNCONDITIONAL, EdgeKind.CONDITIONAL -> {
                        val tgt = term.targetLine
                        if (tgt == null || tgt < loopStart || tgt > loopEnd) {
                            return true
                        }
                    }
                    EdgeKind.NONE -> {}
                }
            }
            return false
        }

        for ((forLine, bodyLine, endforLine) in structuredForLoops) {
            val headerId = lineToPos[bodyLine]?.let { posToBlockId[it] } ?: cfgBlocks.first().id
            val latchId = lineToPos[endforLine]?.let { posToBlockId[it] } ?: cfgBlocks.last().id
            if (seenRanges.add(forLine to endforLine)) {
                loops.add(
                    NaturalLoop(
                        headerBlockId = headerId,
                        latchBlockId = latchId,
                        startLine = forLine,
                        endLine = endforLine,
                        isHardwareForLoop = true,
                        hasEarlyExit = computeEarlyExit(forLine, endforLine),
                    ),
                )
            }
        }

        for (b in cfgBlocks) {
            for (succId in b.exits) {
                val target = blockById[succId] ?: continue
                if (target.startLine <= b.endLine) {
                    val rangeKey = target.startLine to b.endLine
                    val subsumedByFor =
                        structuredForLoops.any { (forLine, bodyLine, endforLine) ->
                            target.startLine == bodyLine && b.endLine == endforLine && (forLine to endforLine) in seenRanges
                        }
                    if (!subsumedByFor && seenRanges.add(rangeKey)) {
                        loops.add(
                            NaturalLoop(
                                headerBlockId = target.id,
                                latchBlockId = b.id,
                                startLine = target.startLine,
                                endLine = b.endLine,
                                isHardwareForLoop = false,
                                hasEarlyExit = computeEarlyExit(target.startLine, b.endLine),
                            ),
                        )
                    }
                }
            }
        }

        return ScopeCfg(scopeName, cfgBlocks, loops)
    }

    internal fun getCodeTokens(
        file: TokenizedFile,
        lineIdx: Int,
    ): List<Token> = file.getLineTokens(lineIdx).filter { !it.text().startsWith(";") }

    internal fun splitLabelAndBody(tokens: List<Token>): Pair<String?, List<Token>> {
        if (tokens.isEmpty()) return null to tokens
        val first = tokens[0]
        if (first.type == TokenType.TOK_COLON || first.text() == ":") {
            return ":" to tokens.subList(1, tokens.size)
        }
        if (first.text().endsWith(":") && first.text().length > 1) {
            return first.text() to tokens.subList(1, tokens.size)
        }
        if (tokens.size >= 2 && (tokens[1].type == TokenType.TOK_COLON || tokens[1].text() == ":")) {
            if (first.type == TokenType.TOK_IDENT || first.type == TokenType.TOK_LOCAL_IDENT) {
                return (first.text() + ":") to tokens.subList(2, tokens.size)
            }
        }
        return null to tokens
    }
}
