package dev.secondsun.sfxoptimizer.compiler

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.FunctionSyntaxHelper
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey
import dev.secondsun.sfxoptimizer.analysis.CallArg
import dev.secondsun.sfxoptimizer.analysis.DeclaredRegister
import dev.secondsun.sfxoptimizer.analysis.FunctionBoundary
import dev.secondsun.sfxoptimizer.analysis.NaturalLoop
import dev.secondsun.sfxoptimizer.analysis.SfxLivenessAnalyzer
import dev.secondsun.sfxoptimizer.analysis.SfxProgramAnalyzer
import dev.secondsun.sfxoptimizer.macro.SourceOrigin
import dev.secondsun.sfxoptimizer.solver.ParallelMoveResolver
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Compiler and lowering pass for SuperFX (.sgs / .i) assembly files.
 *
 * Performs:
 * 1. Recursive `.include` resolution and deduplication with 1-based [SourceOrigin] provenance
 * 2. Pre-allocation function inlining ([SfxInliner])
 * 3. Liveness, [dev.secondsun.sfxoptimizer.analysis.ScopeCfg] construction, and Chaitin-Briggs register allocation ([SfxProgramAnalyzer])
 * 4. Lowering of `register`, `function`, `return`, `call` (with parallel moves, caller-save push/pop, and ADR-0001 loop-invariant hoisting),
 *    and variable token replacement into standard ca65 SuperFX assembly, followed by [SfxPeepholeOptimizer].
 */
class SfxCompiler(
    private val enableInlining: Boolean = true,
    private val inlineThreshold: Int = 60,
    private val enableHoisting: Boolean = true,
    private val enablePeephole: Boolean = true,
) {
    data class AllocatedFunctionInfo(
        val name: String,
        val paramRegs: List<Constants.Register>,
        val returnReg: Constants.Register?,
        val clobberedRegisters: Set<Constants.Register>,
        val allocatedMap: Map<String, Constants.Register>,
    )

    data class CompilationResult(
        val loweredSource: String,
        val functionInfos: Map<String, AllocatedFunctionInfo>,
        val mainAllocations: Map<String, Constants.Register>,
        val warnings: List<String> = emptyList(),
    )

    fun compileFile(
        inputFile: File,
        includeDirs: List<File> = emptyList(),
    ): CompilationResult {
        val includedCanonicals = mutableSetOf<String>()
        includedCanonicals.add(inputFile.canonicalPath)
        val (lines, origins) =
            expandIncludes(
                source = inputFile.readText(),
                sourceUri = inputFile.toURI(),
                currentDir = inputFile.parentFile ?: File("."),
                includeDirs = includeDirs,
                includedCanonicals = includedCanonicals,
            )
        return compileExpanded(lines, origins)
    }

    fun compileSource(
        source: String,
        baseDir: File = File("."),
        includeDirs: List<File> = emptyList(),
    ): CompilationResult {
        val includedCanonicals = mutableSetOf<String>()
        val (lines, origins) =
            expandIncludes(
                source = source,
                sourceUri = null,
                currentDir = baseDir,
                includeDirs = includeDirs,
                includedCanonicals = includedCanonicals,
            )
        return compileExpanded(lines, origins)
    }

    private fun expandIncludes(
        source: String,
        sourceUri: URI?,
        currentDir: File,
        includeDirs: List<File>,
        includedCanonicals: MutableSet<String>,
    ): Pair<List<String>, List<SourceOrigin>> {
        val includeRegex = Regex("""^\s*\.include\s+"([^"]+)"\s*(?:;.*)?$""", RegexOption.IGNORE_CASE)
        val outLines = mutableListOf<String>()
        val outOrigins = mutableListOf<SourceOrigin>()

        for ((idx, line) in source.lines().withIndex()) {
            val lineOrigin = SourceOrigin(sourceUri, idx + 1)
            val match = includeRegex.matchEntire(line)
            if (match != null) {
                val incPath = match.groupValues[1]
                if (incPath.equals("libSFX.i", ignoreCase = true)) {
                    outLines.add(line)
                    outOrigins.add(lineOrigin)
                    continue
                }
                val resolved = resolveIncludeFile(incPath, currentDir, includeDirs)
                if (resolved != null && resolved.exists()) {
                    val canon = resolved.canonicalPath
                    if (!includedCanonicals.add(canon)) {
                        outLines.add("; [sfx-optimizer] skipped duplicate include: $incPath")
                        outOrigins.add(lineOrigin)
                    } else {
                        outLines.add("; --- begin include: $incPath ---")
                        outOrigins.add(lineOrigin)
                        val (subLines, subOrigins) =
                            expandIncludes(
                                source = resolved.readText(),
                                sourceUri = resolved.toURI(),
                                currentDir = resolved.parentFile ?: currentDir,
                                includeDirs = includeDirs,
                                includedCanonicals = includedCanonicals,
                            )
                        outLines.addAll(subLines)
                        outOrigins.addAll(subOrigins)
                        outLines.add("; --- end include: $incPath ---")
                        outOrigins.add(lineOrigin)
                    }
                } else {
                    outLines.add(line)
                    outOrigins.add(lineOrigin)
                }
            } else {
                outLines.add(line)
                outOrigins.add(lineOrigin)
            }
        }
        return outLines to outOrigins
    }

    private fun resolveIncludeFile(
        path: String,
        currentDir: File,
        includeDirs: List<File>,
    ): File? {
        val direct = File(currentDir, path)
        if (direct.exists()) return direct
        for (dir in includeDirs) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
        }
        return null
    }

    private fun compileExpanded(
        initialLines: List<String>,
        initialOrigins: List<SourceOrigin>,
    ): CompilationResult {
        val (rawLines, origins) =
            if (enableInlining) {
                SfxInliner(inlineThreshold).inlineWithProvenance(initialLines, initialOrigins)
            } else {
                initialLines to initialOrigins
            }
        val expandedSource = rawLines.joinToString("\n")
        val scanner = CA65Scanner()
        val tokenizedFile = scanner.tokenize(expandedSource)
        val macroLines = SfxLivenessAnalyzer.findMacroDefinitionLines(rawLines)

        val analysis = SfxProgramAnalyzer.analyze(tokenizedFile, rawLines, macroLines)
        val allocatedFunctions = mutableMapOf<String, AllocatedFunctionInfo>()
        val loweredLines = rawLines.toMutableList()

        for (fn in analysis.functions) {
            val fnLines = (fn.startLine..fn.endLine).filter { it !in macroLines }.toSet()
            val info =
                AllocatedFunctionInfo(
                    name = fn.name,
                    paramRegs = fn.paramRegs,
                    returnReg = fn.returnReg,
                    clobberedRegisters = fn.clobberedRegisters,
                    allocatedMap = fn.allocatedMap,
                )
            allocatedFunctions[fn.name.lowercase(Locale.ROOT)] = info

            lowerScopeLines(
                tokenizedFile = tokenizedFile,
                rawLines = rawLines,
                origins = origins,
                loweredLines = loweredLines,
                scopeLines = fnLines,
                declaredRegisters = fn.declaredRegisters,
                varToReg = fn.allocatedMap,
                intervals = fn.intervals,
                loops = fn.loops,
                naturalLoops = fn.cfg.loops,
                liveAfterByLine = fn.liveAfterByLine,
                allocatedFunctions = allocatedFunctions,
                enclosingFunction =
                    FunctionBoundary(
                        name = fn.name,
                        startLine = fn.startLine,
                        endLine = fn.endLine,
                        params = fn.params,
                        headerReturnVar = fn.effectiveReturnVar,
                        inferredReturnVar = null,
                        isInline = fn.isInline,
                    ),
                enclosingFunctionInfo = info,
            )
        }

        val allFnLines = analysis.functions.flatMap { it.startLine..it.endLine }.toSet()
        val mainLines =
            (0 until analysis.totalLines)
                .filter { it !in allFnLines && it !in macroLines }
                .toSet()

        val mainAllocations = analysis.main?.allocatedMap ?: emptyMap()
        if (analysis.main != null) {
            lowerScopeLines(
                tokenizedFile = tokenizedFile,
                rawLines = rawLines,
                origins = origins,
                loweredLines = loweredLines,
                scopeLines = mainLines,
                declaredRegisters = analysis.main.declaredRegisters,
                varToReg = mainAllocations,
                intervals = analysis.main.intervals,
                loops = analysis.main.loops,
                naturalLoops = analysis.main.cfg.loops,
                liveAfterByLine = analysis.main.liveAfterByLine,
                allocatedFunctions = allocatedFunctions,
                enclosingFunction = null,
                enclosingFunctionInfo = null,
            )
        }

        return CompilationResult(
            loweredSource = loweredLines.joinToString("\n").let { if (enablePeephole) SfxPeepholeOptimizer.optimize(it) else it },
            functionInfos = allocatedFunctions,
            mainAllocations = mainAllocations,
            warnings = analysis.warnings,
        )
    }

    private fun resolveNameToRegister(
        name: String,
        varToReg: Map<String, Constants.Register>,
    ): Constants.Register? {
        val lower = name.lowercase(Locale.ROOT)
        return Constants.register(lower) ?: varToReg[lower]
    }

    private fun formatOrigin(
        origins: List<SourceOrigin>,
        lineIdx: Int,
    ): String {
        val origin = origins.getOrNull(lineIdx) ?: SourceOrigin(null, lineIdx + 1)
        val filePart = origin.uri?.path?.substringAfterLast('/')
        return if (!filePart.isNullOrEmpty()) "$filePart:${origin.line1}" else "line ${origin.line1}"
    }

    private fun isLiveAfterCall(
        intv: Interval,
        callLine: Int,
        loops: List<IntRange>,
    ): Boolean {
        if (intv.start >= callLine || intv.end <= callLine) return false

        val nextRead = intv.reads.filter { it > callLine }.minOrNull()
        val nextWrite = intv.writes.filter { it > callLine }.minOrNull()
        if (nextWrite != null && (nextRead == null || nextWrite < nextRead)) {
            return false
        }
        if (nextRead != null) {
            return true
        }

        for (loop in loops) {
            if (callLine in loop && intv.start <= loop.first && intv.end >= loop.last) {
                val firstReadInLoop = intv.reads.filter { it in loop }.minOrNull()
                val firstWriteInLoop = intv.writes.filter { it in loop }.minOrNull()
                if (firstReadInLoop != null && (firstWriteInLoop == null || firstReadInLoop <= firstWriteInLoop)) {
                    return true
                }
            }
        }

        return false
    }

    private fun lowerScopeLines(
        tokenizedFile: TokenizedFile,
        rawLines: List<String>,
        origins: List<SourceOrigin>,
        loweredLines: MutableList<String>,
        scopeLines: Set<Int>,
        declaredRegisters: Map<String, DeclaredRegister>,
        varToReg: Map<String, Constants.Register>,
        intervals: Map<IntervalKey, Interval>,
        loops: List<IntRange>,
        naturalLoops: List<NaturalLoop>,
        liveAfterByLine: Map<Int, Set<IntervalKey>>,
        allocatedFunctions: Map<String, AllocatedFunctionInfo>,
        enclosingFunction: FunctionBoundary?,
        enclosingFunctionInfo: AllocatedFunctionInfo?,
    ) {
        val moveResolver = ParallelMoveResolver()
        // Hoisted caller-saves: loop line range -> registers to push before / pop after
        val hoistedByLoop = linkedMapOf<IntRange, MutableSet<Constants.Register>>()

        for (lineIdx in scopeLines.sorted()) {
            val rawLine = rawLines[lineIdx]
            val codeTokens = SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, lineIdx)
            if (codeTokens.isEmpty()) continue

            val (labelPrefix, bodyTokens) = SfxLivenessAnalyzer.splitLabelPrefix(codeTokens)
            val indent = rawLine.takeWhile { it == ' ' || it == '\t' }

            if (bodyTokens.isEmpty()) continue

            val insnTok = bodyTokens[0]
            val mnemonic = insnTok.text().lowercase(Locale.ROOT)

            if (mnemonic == "register" || insnTok.type == TokenType.TOK_REGISTER_KEYWORD) {
                val mappings =
                    bodyTokens
                        .drop(1)
                        .filter { it.text() != "," && it.text() != "=" && !Constants.isRegister(it.text().lowercase(Locale.ROOT)) }
                        .mapNotNull { tok ->
                            val reg = varToReg[tok.text().lowercase(Locale.ROOT)]
                            if (reg != null) "${tok.text()}=${reg.name.lowercase(Locale.ROOT)}" else null
                        }
                val comment = "$indent; [sfx-optimizer] register ${mappings.joinToString(", ")}"
                loweredLines[lineIdx] = if (labelPrefix != null) "$indent$labelPrefix\n$comment" else comment
                continue
            }

            if (FunctionSyntaxHelper.isFunction(bodyTokens) && enclosingFunction != null && enclosingFunctionInfo != null) {
                val paramDesc =
                    enclosingFunction.params
                        .zip(enclosingFunctionInfo.paramRegs)
                        .joinToString(", ") { (p, r) -> "$p=${r.name.lowercase(Locale.ROOT)}" }
                val retDesc =
                    if (enclosingFunction.effectiveReturnVar != null && enclosingFunctionInfo.returnReg != null) {
                        " : ${enclosingFunction.effectiveReturnVar}=${enclosingFunctionInfo.returnReg.name.lowercase(Locale.ROOT)}"
                    } else {
                        ""
                    }
                val out = mutableListOf<String>()
                if (labelPrefix != null) out.add("$indent$labelPrefix")
                out.add("$indent; [sfx-optimizer] function ${enclosingFunction.name} ($paramDesc)$retDesc")
                out.add("${indent}function ${enclosingFunction.name}")
                loweredLines[lineIdx] = out.joinToString("\n")
                continue
            }

            if (FunctionSyntaxHelper.isReturn(bodyTokens)) {
                val retStmt = FunctionSyntaxHelper.parseReturn(bodyTokens)
                val out = mutableListOf<String>()
                if (labelPrefix != null) out.add("$indent$labelPrefix")

                val targetRetReg = enclosingFunctionInfo?.returnReg
                if (retStmt.returnVariable().isPresent && targetRetReg != null) {
                    val retTok = retStmt.returnVariable().get()
                    val retText = retTok.text()
                    if (retText.startsWith("#")) {
                        out.add("${indent}iwt ${targetRetReg.name.lowercase(Locale.ROOT)}, $retText")
                    } else {
                        val srcReg = resolveNameToRegister(retText, varToReg)
                        if (srcReg != null && srcReg != targetRetReg) {
                            out.add("${indent}move ${targetRetReg.name.lowercase(Locale.ROOT)}, ${srcReg.name.lowercase(Locale.ROOT)}")
                        }
                    }
                }
                out.add("${indent}return")
                loweredLines[lineIdx] = out.joinToString("\n")
                continue
            }

            if (FunctionSyntaxHelper.isCall(bodyTokens)) {
                val call = SfxLivenessAnalyzer.parseCallStatement(bodyTokens)
                if (call != null) {
                    val calleeInfo = allocatedFunctions[call.target.lowercase(Locale.ROOT)]
                    val out = mutableListOf<String>()
                    if (labelPrefix != null) out.add("$indent$labelPrefix")

                    val destReg = call.dest?.let { resolveNameToRegister(it, varToReg) }
                    val destKey = call.dest?.let { SfxLivenessAnalyzer.resolveKeyByName(it, declaredRegisters) }

                    // 1. Determine caller-saved registers for variables live across this call
                    val callerSaveRegs = mutableListOf<Constants.Register>()
                    if (calleeInfo != null) {
                        for ((key, intv) in intervals) {
                            if (key !is IntervalKey.LabelKey) continue
                            if (key == destKey) continue
                            val liveAcrossCall =
                                liveAfterByLine[lineIdx]?.contains(key)
                                    ?: isLiveAfterCall(intv, lineIdx, loops)
                            if (liveAcrossCall) {
                                val reg = varToReg[key.label.lowercase(Locale.ROOT)] ?: continue
                                if (reg != destReg && reg in calleeInfo.clobberedRegisters && reg !in callerSaveRegs) {
                                    callerSaveRegs.add(reg)
                                }
                            }
                        }
                        callerSaveRegs.sortBy { it.ordinal }
                    }

                    if (enableHoisting) {
                        val it = callerSaveRegs.iterator()
                        while (it.hasNext()) {
                            val reg = it.next()
                            val loopRange = findHoistLoop(reg, lineIdx, naturalLoops, intervals, varToReg)
                            if (loopRange != null) {
                                hoistedByLoop.getOrPut(loopRange) { sortedSetOf(compareBy { r -> r.ordinal }) }.add(reg)
                                it.remove()
                            }
                        }
                    }

                    for (reg in callerSaveRegs) {
                        out.add("${indent}gsu_stack_push ${reg.name.lowercase(Locale.ROOT)}")
                    }

                    // 2. Parameter moves
                    if (calleeInfo != null && call.args.isNotEmpty()) {
                        val regMoves = mutableListOf<Pair<Constants.Register, Constants.Register>>()
                        val immMoves = mutableListOf<Pair<Constants.Register, String>>()

                        for (idx in call.args.indices) {
                            if (idx >= calleeInfo.paramRegs.size) break
                            val dstParamReg = calleeInfo.paramRegs[idx]
                            when (val arg = call.args[idx]) {
                                is CallArg.RegOrVar -> {
                                    val srcReg =
                                        resolveNameToRegister(arg.name, varToReg)
                                            ?: error(
                                                "Unknown register or variable '${arg.name}' in call to '${call.target}' at ${formatOrigin(
                                                    origins,
                                                    lineIdx,
                                                )}",
                                            )
                                    regMoves.add(Pair(srcReg, dstParamReg))
                                }
                                is CallArg.Immediate -> {
                                    immMoves.add(Pair(dstParamReg, arg.expr))
                                }
                            }
                        }

                        for (moveInsn in moveResolver.resolve(regMoves)) {
                            for (asmLine in moveInsn.toAssembly()) {
                                out.add("$indent$asmLine")
                            }
                        }
                        for ((dstParamReg, expr) in immMoves) {
                            out.add("${indent}iwt ${dstParamReg.name.lowercase(Locale.ROOT)}, $expr")
                        }
                    }

                    // 3. Emit call
                    out.add("${indent}call ${call.target}")

                    // 4. Move return value to destination if needed
                    if (destReg != null && calleeInfo?.returnReg != null && destReg != calleeInfo.returnReg) {
                        out.add("${indent}move ${destReg.name.lowercase(Locale.ROOT)}, ${calleeInfo.returnReg.name.lowercase(Locale.ROOT)}")
                    }

                    // 5. Pop caller-saved registers in reverse order
                    for (reg in callerSaveRegs.asReversed()) {
                        out.add("${indent}gsu_stack_pop ${reg.name.lowercase(Locale.ROOT)}")
                    }

                    loweredLines[lineIdx] = out.joinToString("\n")
                    continue
                }
            }

            // Standard instruction/macro line: replace declared register variable tokens with allocated registers
            val replacements = mutableListOf<Triple<Int, Int, String>>()
            for (idx in bodyTokens.indices) {
                if (idx == 0) continue // skip instruction mnemonic
                if (SfxLivenessAnalyzer.isInsideImmediateOrNamespace(bodyTokens, idx)) continue
                val tok = bodyTokens[idx]

                val lower = tok.text().lowercase(Locale.ROOT)
                if (lower in declaredRegisters && !Constants.isRegister(lower)) {
                    val reg =
                        varToReg[lower]
                            ?: error(
                                "Variable '${tok.text()}' at ${formatOrigin(
                                    origins,
                                    lineIdx,
                                )} was not allocated a register (possible spill)",
                            )
                    val actualStart = tok.endIndex - tok.text().length
                    replacements.add(Triple(actualStart, tok.endIndex, reg.name.lowercase(Locale.ROOT)))
                }
            }

            if (replacements.isNotEmpty()) {
                var modified = rawLine
                for ((startCol, endCol, rep) in replacements.sortedByDescending { it.first }) {
                    if (startCol >= 0 && endCol <= modified.length && startCol < endCol) {
                        modified = modified.substring(0, startCol) + rep + modified.substring(endCol)
                    }
                }
                loweredLines[lineIdx] = modified
            }
        }

        for ((loop, regs) in hoistedByLoop) {
            val indent = rawLines[loop.first].takeWhile { it == ' ' || it == '\t' }
            val pushes = regs.joinToString("\n") { "${indent}gsu_stack_push ${it.name.lowercase(Locale.ROOT)}" }
            val pops = regs.reversed().joinToString("\n") { "${indent}gsu_stack_pop ${it.name.lowercase(Locale.ROOT)}" }
            loweredLines[loop.first] = pushes + "\n" + loweredLines[loop.first]
            loweredLines[loop.last] = loweredLines[loop.last] + "\n" + pops
        }
    }

    /**
     * ADR-0001 Loop-Invariant Caller-Save Hoisting:
     * Returns the outermost hardware `for`/`forR` [NaturalLoop] around [callLine] across which [reg]
     * is neither read nor written inside the loop and the loop has no early exit.
     */
    private fun findHoistLoop(
        reg: Constants.Register,
        callLine: Int,
        naturalLoops: List<NaturalLoop>,
        intervals: Map<IntervalKey, Interval>,
        varToReg: Map<String, Constants.Register>,
    ): IntRange? {
        val candidates =
            naturalLoops
                .filter { it.isHardwareForLoop && !it.hasEarlyExit && callLine in it.lineRange }
                .sortedByDescending { it.endLine - it.startLine }

        for (loop in candidates) {
            val range = loop.lineRange
            val touched =
                intervals.any { (key, intv) ->
                    val r =
                        when (key) {
                            is IntervalKey.RegisterKey -> key.register
                            is IntervalKey.LabelKey -> varToReg[key.label.lowercase(Locale.ROOT)]
                        }
                    r == reg && (intv.reads.any { it in range } || intv.writes.any { it in range })
                }
            if (!touched) {
                return range
            }
        }
        return null
    }
}
