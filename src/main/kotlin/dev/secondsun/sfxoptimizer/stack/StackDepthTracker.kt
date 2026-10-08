package dev.secondsun.sfxoptimizer.stack

import dev.secondsun.retro.util.Token
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
import dev.secondsun.sfxoptimizer.graphnode.CodeNode
import kotlin.math.max

/**
 * Result of static stack depth analysis for a function or code block.
 */
data class StackDepthResult(
    /** Map of instruction line number to stack depth displacement (Δ_SP) at entry to that line */
    val lineDepths: Map<Int, Int>,
    /** Stack depth displacement (Δ_SP) at entrance of each block */
    val blockEntryDepths: Map<CodeNode, Int>,
    /** Stack depth displacement (Δ_SP) at exit of each block */
    val blockExitDepths: Map<CodeNode, Int>,
    /** Peak stack displacement (highest positive Δ_SP reached) */
    val maxDepth: Int,
) {
    /**
     * Computes the effective displacement below the current r10 pointer at instruction [line]
     * for a stack slot located at [slotBaseOffset] below the function frame base.
     *
     * EffectiveDisplacement = slotBaseOffset + Δ_SP(line)
     */
    fun effectiveDisplacement(
        slotBaseOffset: Int,
        line: Int,
    ): Int {
        val deltaSp = lineDepths[line] ?: 0
        return slotBaseOffset + deltaSp
    }
}

/**
 * Static instruction-level stack depth analyzer tracking r10 mutations (Δ_SP)
 * and verifying control-flow invariants across basic block joins.
 */
class StackDepthTracker {
    /**
     * Analyzes all functions and the top-level entry point in [graph].
     * Returns a map of root nodes (functions and main entry) to their respective [StackDepthResult].
     */
    fun trackGraph(graph: CodeGraph): Map<CodeNode, StackDepthResult> {
        val results = mutableMapOf<CodeNode, StackDepthResult>()
        results[graph.startNode] = track(graph.startNode)
        for ((_, func) in graph.allFunctions) {
            results[func] = track(func)
        }
        return results
    }

    /**
     * Analyzes stack depth starting at [rootNode] (e.g. a FunctionStart or Start block).
     *
     * @throws IllegalStateException if a control-flow join has divergent stack depths.
     */
    fun track(rootNode: CodeNode): StackDepthResult {
        val entry =
            when (rootNode) {
                is CodeNode.FunctionStart -> rootNode.functionBody.startNode.main
                is CodeNode.Start -> rootNode.main
                else -> rootNode
            }

        val blockEntryDepths = mutableMapOf<CodeNode, Int>()
        val blockExitDepths = mutableMapOf<CodeNode, Int>()
        val lineDepths = mutableMapOf<Int, Int>()
        var peakDepth = 0

        val worklist = ArrayDeque<CodeNode>()
        blockEntryDepths[entry] = 0
        worklist.add(entry)

        while (worklist.isNotEmpty()) {
            val node = worklist.removeFirst()
            val incomingDepth = blockEntryDepths[node] ?: 0

            val exitDepth =
                when (node) {
                    is CodeNode.CodeBlock -> {
                        var depth = incomingDepth
                        var sReg: String? = null
                        var dReg: String? = null

                        for (tokens in node.lines) {
                            if (tokens.tokens.isEmpty()) continue
                            val firstToken = tokens.tokens[0]
                            val lineNum = firstToken.lineNumber

                            lineDepths[lineNum] = depth
                            peakDepth = max(peakDepth, depth)

                            val opcode = firstToken.text().lowercase()

                            when (opcode) {
                                "with" -> {
                                    if (tokens.tokens.size >= 2) {
                                        val reg = tokens.tokens[1].text().lowercase()
                                        sReg = reg
                                        dReg = reg
                                    }
                                }
                                "to" -> {
                                    if (tokens.tokens.size >= 2) {
                                        dReg = tokens.tokens[1].text().lowercase()
                                    }
                                }
                                "from" -> {
                                    if (tokens.tokens.size >= 2) {
                                        sReg = tokens.tokens[1].text().lowercase()
                                    }
                                }
                                "inc" -> {
                                    if (tokens.tokens.size >= 2 && tokens.tokens[1].text().equals("r10", ignoreCase = true)) {
                                        depth += 1
                                    }
                                    sReg = null
                                    dReg = null
                                }
                                "dec" -> {
                                    if (tokens.tokens.size >= 2 && tokens.tokens[1].text().equals("r10", ignoreCase = true)) {
                                        depth -= 1
                                    }
                                    sReg = null
                                    dReg = null
                                }
                                "add", "adds" -> {
                                    if (dReg == "r10") {
                                        val imm = getImmediateOperand(tokens.tokens)
                                        if (imm != null) {
                                            depth += imm
                                        }
                                    }
                                    sReg = null
                                    dReg = null
                                }
                                "sub", "subs" -> {
                                    if (dReg == "r10") {
                                        val imm = getImmediateOperand(tokens.tokens)
                                        if (imm != null) {
                                            depth -= imm
                                        }
                                    }
                                    sReg = null
                                    dReg = null
                                }
                                else -> {
                                    sReg = null
                                    dReg = null
                                }
                            }
                        }
                        peakDepth = max(peakDepth, depth)
                        depth
                    }
                    is CodeNode.CallBlock -> {
                        lineDepths[node.line] = incomingDepth
                        incomingDepth
                    }
                    else -> incomingDepth
                }

            blockExitDepths[node] = exitDepth

            for (succ in node.exits) {
                if (succ is CodeNode.End) continue

                val existing = blockEntryDepths[succ]
                if (existing != null) {
                    if (existing != exitDepth) {
                        throw IllegalStateException(
                            "Mismatched stack depth at branch target: expected $existing bytes, but got $exitDepth bytes entering block $succ",
                        )
                    }
                } else {
                    blockEntryDepths[succ] = exitDepth
                    worklist.add(succ)
                }
            }
        }

        return StackDepthResult(
            lineDepths = lineDepths,
            blockEntryDepths = blockEntryDepths,
            blockExitDepths = blockExitDepths,
            maxDepth = peakDepth,
        )
    }

    private fun getImmediateOperand(tokens: List<Token>): Int? {
        if (tokens.size < 2) return null
        val operandText = tokens.subList(1, tokens.size).joinToString("") { it.text() }
        return parseImmediateOrNull(operandText)
    }

    companion object {
        fun parseImmediateOrNull(text: String): Int? {
            val clean =
                text
                    .trim()
                    .removePrefix("#")
                    .removePrefix("(")
                    .removeSuffix(")")
                    .trim()
            if (clean.isEmpty()) return null
            return try {
                when {
                    clean.startsWith("$") -> clean.removePrefix("$").toInt(16)
                    clean.startsWith("0x", ignoreCase = true) -> clean.substring(2).toInt(16)
                    clean.startsWith("%") -> clean.removePrefix("%").toInt(2)
                    else -> clean.toInt()
                }
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
