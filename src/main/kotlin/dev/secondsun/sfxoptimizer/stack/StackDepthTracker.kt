package dev.secondsun.sfxoptimizer.stack

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.FunctionSyntaxHelper
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.WorkspaceSnapshot
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic
import dev.secondsun.retro.util.sfx.ModalStepEvaluator
import dev.secondsun.retro.util.sfx.ModalStepEvaluator.ModalState
import dev.secondsun.retro.util.sfx.ModalStepEvaluator.StepKind
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.analysis.ScopeCfg
import dev.secondsun.sfxoptimizer.analysis.SfxCfgBuilder
import dev.secondsun.sfxoptimizer.analysis.SfxLivenessAnalyzer
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander
import dev.secondsun.sfxoptimizer.macro.ExpandedSource
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Violation record for stack displacement invariants (`r10`), carrying exact 1-based [line] provenance.
 */
data class StackViolation(
    val uri: URI? = null,
    val line: Int,
    val rule: String,
    val message: String,
    val displacement: Int,
)

/**
 * Result of static stack depth analysis for a function or `(main)` scope.
 */
data class StackDepthResult(
    val scopeName: String,
    /** Map of 0-based expanded instruction line index to stack depth displacement (Δ_SP) at entry to that line */
    val lineDepths: Map<Int, Int>,
    /** Stack depth displacement (Δ_SP) at entrance of each [dev.secondsun.sfxoptimizer.analysis.CfgBlock] ID */
    val blockEntryDepths: Map<String, Int>,
    /** Stack depth displacement (Δ_SP) at exit of each [dev.secondsun.sfxoptimizer.analysis.CfgBlock] ID */
    val blockExitDepths: Map<String, Int>,
    /** Peak stack displacement (highest positive Δ_SP reached) */
    val maxDepth: Int,
    /** List of stack violations detected with 1-based source provenance */
    val violations: List<StackViolation> = emptyList(),
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
 * Static instruction-level stack depth analyzer tracking `r10` mutations (Δ_SP)
 * across immutable [ScopeCfg] basic blocks and mapping findings back to exact 1-based
 * original source lines via [ExpandedSource].
 */
class StackDepthTracker {
    /**
     * Expands macros in [source] and tracks stack depth across `(main)` and all functions.
     */
    @JvmOverloads
    fun trackSource(
        source: String,
        strict: Boolean = true,
        uri: URI = URI.create("snippet:///inline.sgs"),
    ): Map<String, StackDepthResult> {
        val raw = CA65Scanner().tokenize(source, uri)
        val expanded = CA65MacroExpander().expandWithProvenance(raw)
        return trackExpanded(expanded, strict = strict)
    }

    /**
     * Tracks stack depth across `(main)` and every `[inline] function` in [expanded], auditing
     * each scope exactly once.
     */
    @JvmOverloads
    fun trackExpanded(
        expanded: ExpandedSource,
        strict: Boolean = false,
    ): Map<String, StackDepthResult> {
        val file = expanded.tokenizedFile
        val rawLines = file.rawLines ?: emptyList()
        val macroLines = SfxLivenessAnalyzer.findMacroDefinitionLines(rawLines)
        val functions = SfxLivenessAnalyzer.discoverFunctions(file, rawLines, macroLines)
        val blockCounter = AtomicInteger(0)

        val results = linkedMapOf<String, StackDepthResult>()

        // 1. Track top-level (main) lines outside all function bodies
        val allFnLines = functions.flatMap { it.startLine..it.endLine }.toSet()
        val mainLines =
            (0 until file.textLines())
                .filter { it !in allFnLines && it !in macroLines }
        val mainCfg = SfxCfgBuilder.buildScopeCfg(file, "(main)", mainLines, blockCounter)
        results["(main)"] =
            trackScopeCfg(
                expanded = expanded,
                cfg = mainCfg,
                isFunction = false,
                strict = strict,
            )

        // 2. Track each discovered function exactly once
        for (fn in functions) {
            val fnLines = (fn.startLine..fn.endLine).filter { it !in macroLines }
            val fnCfg = SfxCfgBuilder.buildScopeCfg(file, fn.name, fnLines, blockCounter)
            results[fn.name] =
                trackScopeCfg(
                    expanded = expanded,
                    cfg = fnCfg,
                    isFunction = true,
                    strict = strict,
                )
        }

        return results
    }

    /**
     * Audits [tokenizedFile] and returns typed [StackViolation] records with exact 1-based line numbers.
     */
    @JvmOverloads
    fun auditFindings(
        tokenizedFile: TokenizedFile,
        snapshot: WorkspaceSnapshot? = null,
    ): List<StackViolation> {
        if (tokenizedFile.isEmpty) return emptyList()
        val expander = CA65MacroExpander(snapshot)
        val expanded = expander.expandWithProvenance(tokenizedFile)
        return trackExpanded(expanded, strict = false).values.flatMap { it.violations }
    }

    /**
     * Audits [tokenizedFile] and returns [SyntaxDiagnostic] records mapped to exact 1-based source lines.
     */
    @JvmOverloads
    fun auditFile(
        tokenizedFile: TokenizedFile,
        snapshot: WorkspaceSnapshot? = null,
    ): List<SyntaxDiagnostic> {
        val fallbackUri = tokenizedFile.uri() ?: URI.create("snippet:///inline.sgs")
        return auditFindings(tokenizedFile, snapshot).map { sv ->
            SyntaxDiagnostic.error(
                sv.uri ?: fallbackUri,
                maxOf(1, sv.line),
                0,
                1,
                sv.rule,
                sv.message,
            )
        }
    }

    private fun trackScopeCfg(
        expanded: ExpandedSource,
        cfg: ScopeCfg,
        isFunction: Boolean,
        strict: Boolean,
    ): StackDepthResult {
        if (cfg.blocks.isEmpty()) {
            return StackDepthResult(
                scopeName = cfg.scopeName,
                lineDepths = emptyMap(),
                blockEntryDepths = emptyMap(),
                blockExitDepths = emptyMap(),
                maxDepth = 0,
                violations = emptyList(),
            )
        }

        val file = expanded.tokenizedFile
        val blockById = cfg.blocks.associateBy { it.id }
        val blockEntryDepths = linkedMapOf<String, Int>()
        val blockExitDepths = linkedMapOf<String, Int>()
        val lineDepths = linkedMapOf<Int, Int>()
        val violations = mutableListOf<StackViolation>()
        var peakDepth = 0

        val entry = cfg.blocks.first()
        val worklist = ArrayDeque<String>()
        blockEntryDepths[entry.id] = 0
        worklist.addLast(entry.id)

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeFirst()
            val block = blockById[blockId] ?: continue
            var depth = blockEntryDepths[blockId] ?: 0
            var modal = ModalState.DEFAULT

            for (lineIdx in block.lineIndices) {
                val tokens = SfxLivenessAnalyzer.getCodeTokens(file, lineIdx)
                if (tokens.isEmpty()) continue

                lineDepths[lineIdx] = depth
                peakDepth = max(peakDepth, depth)

                val step = ModalStepEvaluator.step(modal, tokens, lineIdx, null)
                modal = step.nextState()

                if (step.kind() == StepKind.INSTRUCTION) {
                    val eff = step.effect()
                    if (eff != null) {
                        depth += computeStackMutation(eff, tokens)
                        peakDepth = max(peakDepth, depth)
                    }
                } else {
                    // Fallback for non-standard `adds`/`subs` or bare-immediate macro lines if ever present
                    val (_, body) = SfxLivenessAnalyzer.splitLabelPrefix(tokens)
                    if (body.isNotEmpty() && !FunctionSyntaxHelper.isCall(body)) {
                        val mn = body[0].text().lowercase(Locale.ROOT)
                        if ((mn == "adds" || mn == "subs") &&
                            step
                                .effect()
                                ?.consumedState()
                                ?.destReg()
                                .equals("r10", true)
                        ) {
                            val imm = getImmediateOperand(body)
                            if (imm != null) {
                                depth += if (mn == "adds") imm else -imm
                                peakDepth = max(peakDepth, depth)
                            }
                        }
                    }
                }
            }

            blockExitDepths[blockId] = depth

            val isReturnExit =
                isFunction &&
                    !block.unresolvedDynamicJump &&
                    (block.terminatesWithReturnOrStop || block.exits.isEmpty())

            if (isReturnExit && depth != 0) {
                val reportLine0 = findReportingLine(file, block.lineIndices, preferReturn = true)
                val origin = expanded.originOf(reportLine0)
                violations.add(
                    StackViolation(
                        uri = origin.uri,
                        line = origin.line1,
                        rule = "STACK_LEAK_ON_RETURN",
                        message = "Stack displacement at function return is non-zero (Δ_SP = $depth bytes)",
                        displacement = depth,
                    ),
                )
            }

            for (succId in block.exits) {
                val existing = blockEntryDepths[succId]
                if (existing != null) {
                    if (existing != depth) {
                        if (strict) {
                            throw IllegalStateException(
                                "Mismatched stack depth at branch target: " +
                                    "expected $existing bytes, but got $depth bytes entering block $succId",
                            )
                        } else {
                            val reportLine0 = findReportingLine(file, block.lineIndices, preferReturn = false)
                            val origin = expanded.originOf(reportLine0)
                            violations.add(
                                StackViolation(
                                    uri = origin.uri,
                                    line = origin.line1,
                                    rule = "DIVERGENT_STACK_DEPTH",
                                    message =
                                        "Mismatched stack depth at branch target: " +
                                            "expected $existing bytes, but got $depth bytes entering block $succId",
                                    displacement = depth - existing,
                                ),
                            )
                        }
                    }
                } else {
                    blockEntryDepths[succId] = depth
                    worklist.addLast(succId)
                }
            }
        }

        return StackDepthResult(
            scopeName = cfg.scopeName,
            lineDepths = lineDepths,
            blockEntryDepths = blockEntryDepths,
            blockExitDepths = blockExitDepths,
            maxDepth = peakDepth,
            violations = violations,
        )
    }

    private fun computeStackMutation(
        eff: ModalStepEvaluator.InstructionEffect,
        tokens: List<Token>,
    ): Int {
        val mn = eff.mnemonic()
        if (mn == "inc" && eff.writes().any { it.equals("r10", ignoreCase = true) }) {
            return 1
        }
        if (mn == "dec" && eff.writes().any { it.equals("r10", ignoreCase = true) }) {
            return -1
        }
        if ((mn == "add" || mn == "sub") && eff.consumedState().destReg().equals("r10", ignoreCase = true)) {
            val immOpt = eff.immediate()
            val immVal =
                if (immOpt.isPresent && immOpt.get().isConstant) {
                    immOpt.get().signedValue()
                } else {
                    val (_, body) = SfxLivenessAnalyzer.splitLabelPrefix(tokens)
                    getImmediateOperand(body)
                }
            if (immVal != null) {
                return if (mn == "add") immVal else -immVal
            }
        }
        return 0
    }

    private fun findReportingLine(
        file: TokenizedFile,
        blockLines: List<Int>,
        preferReturn: Boolean,
    ): Int {
        if (blockLines.isEmpty()) return 0
        if (preferReturn) {
            for (idx in blockLines.asReversed()) {
                val (_, body) = SfxLivenessAnalyzer.splitLabelPrefix(SfxLivenessAnalyzer.getCodeTokens(file, idx))
                if (body.isEmpty()) continue
                val mn = body[0].text().lowercase(Locale.ROOT)
                if (FunctionSyntaxHelper.isReturn(body) || mn == "jmp" || mn == "ljmp") {
                    return idx
                }
            }
        } else {
            for (idx in blockLines.asReversed()) {
                val (_, body) = SfxLivenessAnalyzer.splitLabelPrefix(SfxLivenessAnalyzer.getCodeTokens(file, idx))
                if (body.isEmpty()) continue
                val mn = body[0].text().lowercase(Locale.ROOT)
                if (mn != "nop") {
                    return idx
                }
            }
        }
        return blockLines.last()
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
