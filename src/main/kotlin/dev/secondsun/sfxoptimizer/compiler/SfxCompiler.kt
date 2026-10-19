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
import dev.secondsun.sfxoptimizer.analysis.SfxLivenessAnalyzer
import dev.secondsun.sfxoptimizer.analysis.SfxProgramAnalyzer
import dev.secondsun.sfxoptimizer.solver.ParallelMoveResolver
import java.io.File
import java.util.Locale

/**
 * Compiler and lowering pass for SuperFX (.sgs / .i) assembly files.
 *
 * Performs:
 * 1. Recursive `.include` resolution and deduplication
 * 2. Delegates liveness, call-graph ordering, and hardware-constrained register allocation to SfxProgramAnalyzer
 * 3. Lowers `register`, `function`, `return`, `call` (with parallel moves and caller-save push/pop),
 *    and variable token replacement into standard ca65 SuperFX assembly.
 */
class SfxCompiler {
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
    )

    fun compileFile(
        inputFile: File,
        includeDirs: List<File> = emptyList(),
    ): CompilationResult {
        val includedCanonicals = mutableSetOf<String>()
        includedCanonicals.add(inputFile.canonicalPath)
        val expanded = expandIncludes(inputFile.readText(), inputFile.parentFile ?: File("."), includeDirs, includedCanonicals)
        return compileExpanded(expanded)
    }

    fun compileSource(
        source: String,
        baseDir: File = File("."),
        includeDirs: List<File> = emptyList(),
    ): CompilationResult {
        val includedCanonicals = mutableSetOf<String>()
        val expanded = expandIncludes(source, baseDir, includeDirs, includedCanonicals)
        return compileExpanded(expanded)
    }

    private fun expandIncludes(
        source: String,
        currentDir: File,
        includeDirs: List<File>,
        includedCanonicals: MutableSet<String>,
    ): String {
        val includeRegex = Regex("""^\s*\.include\s+"([^"]+)"\s*(?:;.*)?$""", RegexOption.IGNORE_CASE)
        val outLines = mutableListOf<String>()

        for (line in source.lines()) {
            val match = includeRegex.matchEntire(line)
            if (match != null) {
                val incPath = match.groupValues[1]
                if (incPath.equals("libSFX.i", ignoreCase = true)) {
                    outLines.add(line)
                    continue
                }
                val resolved = resolveIncludeFile(incPath, currentDir, includeDirs)
                if (resolved != null && resolved.exists()) {
                    val canon = resolved.canonicalPath
                    if (!includedCanonicals.add(canon)) {
                        outLines.add("; [sfx-optimizer] skipped duplicate include: $incPath")
                    } else {
                        outLines.add("; --- begin include: $incPath ---")
                        val subExpanded =
                            expandIncludes(
                                resolved.readText(),
                                resolved.parentFile ?: currentDir,
                                includeDirs,
                                includedCanonicals,
                            )
                        outLines.add(subExpanded)
                        outLines.add("; --- end include: $incPath ---")
                    }
                } else {
                    outLines.add(line)
                }
            } else {
                outLines.add(line)
            }
        }
        return outLines.joinToString("\n")
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

    private fun compileExpanded(expandedSource: String): CompilationResult {
        val scanner = CA65Scanner()
        val tokenizedFile = scanner.tokenize(expandedSource)
        val rawLines = expandedSource.lines()
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
                loweredLines = loweredLines,
                scopeLines = fnLines,
                declaredRegisters = fn.declaredRegisters,
                varToReg = fn.allocatedMap,
                intervals = fn.intervals,
                loops = fn.loops,
                allocatedFunctions = allocatedFunctions,
                enclosingFunction =
                    FunctionBoundary(
                        name = fn.name,
                        startLine = fn.startLine,
                        endLine = fn.endLine,
                        params = fn.params,
                        headerReturnVar = fn.effectiveReturnVar,
                        inferredReturnVar = null,
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
                loweredLines = loweredLines,
                scopeLines = mainLines,
                declaredRegisters = analysis.main.declaredRegisters,
                varToReg = mainAllocations,
                intervals = analysis.main.intervals,
                loops = analysis.main.loops,
                allocatedFunctions = allocatedFunctions,
                enclosingFunction = null,
                enclosingFunctionInfo = null,
            )
        }

        return CompilationResult(
            loweredSource = loweredLines.joinToString("\n"),
            functionInfos = allocatedFunctions,
            mainAllocations = mainAllocations,
        )
    }

    private fun resolveNameToRegister(
        name: String,
        varToReg: Map<String, Constants.Register>,
    ): Constants.Register? {
        val lower = name.lowercase(Locale.ROOT)
        return Constants.register(lower) ?: varToReg[lower]
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
        loweredLines: MutableList<String>,
        scopeLines: Set<Int>,
        declaredRegisters: Map<String, DeclaredRegister>,
        varToReg: Map<String, Constants.Register>,
        intervals: Map<IntervalKey, Interval>,
        loops: List<IntRange>,
        allocatedFunctions: Map<String, AllocatedFunctionInfo>,
        enclosingFunction: FunctionBoundary?,
        enclosingFunctionInfo: AllocatedFunctionInfo?,
    ) {
        val moveResolver = ParallelMoveResolver()

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
                            if (isLiveAfterCall(intv, lineIdx, loops)) {
                                val reg = varToReg[key.label.lowercase(Locale.ROOT)] ?: continue
                                if (reg != destReg && reg in calleeInfo.clobberedRegisters && reg !in callerSaveRegs) {
                                    callerSaveRegs.add(reg)
                                }
                            }
                        }
                        callerSaveRegs.sortBy { it.ordinal }
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
                                                "Unknown register or variable '${arg.name}' in call to '${call.target}' at line ${lineIdx + 1}",
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
                            ?: error("Variable '${tok.text()}' on line ${lineIdx + 1} was not allocated a register (possible spill)")
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
    }
}
