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
import dev.secondsun.sfxoptimizer.analysis.LineEffect
import dev.secondsun.sfxoptimizer.analysis.NaturalLoop
import dev.secondsun.sfxoptimizer.analysis.ScopeCfg
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
 * 2. Pre-allocation cache-aware function inlining ([SfxInliner])
 * 3. Liveness, [ScopeCfg] construction, and Chaitin-Briggs register allocation ([SfxProgramAnalyzer])
 * 4. Lowering of `register`, `function`, `return`, `call` (with parallel moves, caller-save push/pop,
 *    and ADR-0001 loop-invariant hoisting), `R10` stack spill slots, and variable token replacement
 *    into standard ca65 SuperFX assembly, followed by [SfxPeepholeOptimizer] and optional `.dbg` mapping.
 */
class SfxCompiler(
    private val enableInlining: Boolean = true,
    private val inlineThreshold: Int = 60,
    private val enableHoisting: Boolean = true,
    private val enablePeephole: Boolean = true,
    private val emitDebugLines: Boolean = true,
) {
    data class CompilationStats(
        val includedFileCount: Int = 0,
        val functionCount: Int = 0,
        val inlinedSiteCount: Int = 0,
        val hoistedSaveCount: Int = 0,
        val peepholeRewriteCount: Int = 0,
        val spilledVariableCount: Int = 0,
    )

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
        val includedFiles: List<File> = emptyList(),
        val stats: CompilationStats = CompilationStats(),
    )

    fun compileFile(
        inputFile: File,
        includeDirs: List<File> = emptyList(),
    ): CompilationResult {
        val includedCanonicals = linkedSetOf<String>()
        includedCanonicals.add(inputFile.canonicalPath)
        val (lines, origins) =
            expandIncludes(
                source = inputFile.readText(),
                sourceUri = inputFile.toURI(),
                currentDir = inputFile.parentFile ?: File("."),
                includeDirs = includeDirs,
                includedCanonicals = includedCanonicals,
            )
        val includedFiles = includedCanonicals.map { File(it) }
        return compileExpanded(
            initialLines = lines,
            initialOrigins = origins,
            baseDir = inputFile.parentFile ?: File("."),
            includedFiles = includedFiles,
        )
    }

    fun compileSource(
        source: String,
        baseDir: File = File("."),
        includeDirs: List<File> = emptyList(),
    ): CompilationResult {
        val includedCanonicals = linkedSetOf<String>()
        val (lines, origins) =
            expandIncludes(
                source = source,
                sourceUri = null,
                currentDir = baseDir,
                includeDirs = includeDirs,
                includedCanonicals = includedCanonicals,
            )
        val includedFiles = includedCanonicals.map { File(it) }
        return compileExpanded(
            initialLines = lines,
            initialOrigins = origins,
            baseDir = baseDir,
            includedFiles = includedFiles,
        )
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
        baseDir: File,
        includedFiles: List<File>,
    ): CompilationResult {
        val inlineWarnings = mutableListOf<String>()
        var inlinedSites = 0
        val (rawLines, origins) =
            if (enableInlining) {
                val res = SfxInliner(inlineThreshold).inlineWithDetails(initialLines, initialOrigins)
                inlinedSites = res.inlinedCount
                inlineWarnings.addAll(res.warnings)
                res.lines to res.origins
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
        var hoistedSaves = 0
        var spilledVarsTotal = 0

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
            spilledVarsTotal += fn.spilledVariables.size

            hoistedSaves +=
                lowerScopeLines(
                    tokenizedFile = tokenizedFile,
                    rawLines = rawLines,
                    origins = origins,
                    loweredLines = loweredLines,
                    scopeLines = fnLines,
                    declaredRegisters = fn.declaredRegisters,
                    varToReg = fn.allocatedMap,
                    spilledVariables = fn.spilledVariables,
                    scratchRegisters = fn.scratchRegisters,
                    effects = fn.effects,
                    cfg = fn.cfg,
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
            spilledVarsTotal += analysis.main.spilledVariables.size
            hoistedSaves +=
                lowerScopeLines(
                    tokenizedFile = tokenizedFile,
                    rawLines = rawLines,
                    origins = origins,
                    loweredLines = loweredLines,
                    scopeLines = mainLines,
                    declaredRegisters = analysis.main.declaredRegisters,
                    varToReg = mainAllocations,
                    spilledVariables = analysis.main.spilledVariables,
                    scratchRegisters = analysis.main.scratchRegisters,
                    effects = analysis.main.effects,
                    cfg = analysis.main.cfg,
                    intervals = analysis.main.intervals,
                    loops = analysis.main.loops,
                    naturalLoops = analysis.main.cfg.loops,
                    liveAfterByLine = analysis.main.liveAfterByLine,
                    allocatedFunctions = allocatedFunctions,
                    enclosingFunction = null,
                    enclosingFunctionInfo = null,
                )
        }

        val withDebugLines =
            if (emitDebugLines) {
                injectCa65DebugLines(loweredLines, rawLines, origins, macroLines, baseDir)
            } else {
                loweredLines.joinToString("\n")
            }

        val (finalCode, peepholeRewrites) =
            if (enablePeephole) {
                val res = SfxPeepholeOptimizer.optimizeWithStats(withDebugLines)
                res.code to res.rewriteCount
            } else {
                withDebugLines to 0
            }

        return CompilationResult(
            loweredSource = finalCode,
            functionInfos = allocatedFunctions,
            mainAllocations = mainAllocations,
            warnings = inlineWarnings + analysis.warnings,
            includedFiles = includedFiles,
            stats =
                CompilationStats(
                    includedFileCount = includedFiles.size,
                    functionCount = analysis.functions.size,
                    inlinedSiteCount = inlinedSites,
                    hoistedSaveCount = hoistedSaves,
                    peepholeRewriteCount = peepholeRewrites,
                    spilledVariableCount = spilledVarsTotal,
                ),
        )
    }

    private fun injectCa65DebugLines(
        loweredLines: List<String>,
        rawLines: List<String>,
        origins: List<SourceOrigin>,
        macroLines: Set<Int>,
        baseDir: File,
    ): String {
        val filePaths = linkedMapOf<URI, Pair<String, File>>()
        val basePath = baseDir.canonicalFile.toPath()
        for (origin in origins) {
            val uri = origin.uri ?: continue
            if (uri !in filePaths && uri.scheme == "file") {
                val f = File(uri).canonicalFile
                val display =
                    try {
                        basePath.relativize(f.toPath()).toString()
                    } catch (_: IllegalArgumentException) {
                        f.path
                    }
                filePaths[uri] = display to f
            }
        }
        if (filePaths.isEmpty()) return loweredLines.joinToString("\n")

        // Identify lines inside .struct/.enum/.union blocks where .dbg line should not be injected
        val blockedLines = HashSet<Int>(macroLines)
        var blockDepth = 0
        for ((idx, line) in rawLines.withIndex()) {
            val trimmed = line.substringBefore(';').trim().lowercase(Locale.ROOT)
            if (trimmed.startsWith(".struct") || trimmed.startsWith(".enum") || trimmed.startsWith(".union")) {
                blockDepth++
                blockedLines.add(idx)
            } else if (trimmed.startsWith(".endstruct") || trimmed.startsWith(".endenum") || trimmed.startsWith(".endunion")) {
                blockedLines.add(idx)
                if (blockDepth > 0) blockDepth--
            } else if (blockDepth > 0) {
                blockedLines.add(idx)
            }
        }

        val out = ArrayList<String>(loweredLines.size + filePaths.size + 64)
        for ((_, pair) in filePaths) {
            val (display, f) = pair
            val size = if (f.exists()) f.length() else 0L
            val mtime = if (f.exists()) f.lastModified() / 1000L else 0L
            out.add(".dbg file, \"${display.replace("\"", "\\\"")}\", $size, $mtime")
        }

        var lastFile: String? = null
        var lastLine = -1
        for (idx in loweredLines.indices) {
            val text = loweredLines[idx]
            val origin = origins.getOrNull(idx)
            val fileEntry = origin?.uri?.let { filePaths[it] }
            val hasExecutableLine =
                text.lines().any { l ->
                    val t = l.substringBefore(';').trim()
                    t.isNotEmpty() && !t.startsWith(".")
                }
            val canEmit =
                fileEntry != null &&
                    idx !in blockedLines &&
                    hasExecutableLine
            if (canEmit) {
                val (display, _) = fileEntry
                if (display != lastFile || origin.line1 != lastLine) {
                    out.add(".dbg line, \"${display.replace("\"", "\\\"")}\", ${origin.line1}")
                    lastFile = display
                    lastLine = origin.line1
                }
            }
            out.add(text)
        }
        return out.joinToString("\n")
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
        spilledVariables: Set<String>,
        scratchRegisters: List<Constants.Register>,
        effects: Map<Int, LineEffect>,
        cfg: ScopeCfg,
        intervals: Map<IntervalKey, Interval>,
        loops: List<IntRange>,
        naturalLoops: List<NaturalLoop>,
        liveAfterByLine: Map<Int, Set<IntervalKey>>,
        allocatedFunctions: Map<String, AllocatedFunctionInfo>,
        enclosingFunction: FunctionBoundary?,
        enclosingFunctionInfo: AllocatedFunctionInfo?,
    ): Int {
        val moveResolver = ParallelMoveResolver()
        // Hoisted caller-saves: loop line range -> registers to push before / pop after
        val hoistedByLoop = linkedMapOf<IntRange, MutableSet<Constants.Register>>()

        val spillSlots = linkedMapOf<String, Int>()
        for ((slotIdx, v) in spilledVariables.withIndex()) {
            spillSlots[v.lowercase(Locale.ROOT)] = slotIdx
        }
        val frameBytes = spillSlots.size * 2
        val scratch1 = scratchRegisters.getOrElse(0) { Constants.Register.R9 }
        val scratch2 = scratchRegisters.getOrElse(1) { Constants.Register.R8 }
        val deltaSpByLine =
            if (frameBytes > 0) {
                computeDynamicStackDeltas(tokenizedFile, cfg)
            } else {
                emptyMap()
            }
        val allowHoisting = enableHoisting && frameBytes == 0

        fun spillOffset(
            varName: String,
            lineIdx: Int,
            extraSp: Int = 0,
        ): Int {
            val slot = spillSlots[varName.lowercase(Locale.ROOT)] ?: error("No spill slot for '$varName'")
            return (frameBytes - slot * 2) + (deltaSpByLine[lineIdx] ?: 0) + extraSp
        }

        val sortedScopeLines = scopeLines.sorted()
        // For (main) scope with spills, track where to allocate the spill frame (after init_stack if present)
        var mainFrameAllocated = enclosingFunction != null || frameBytes == 0
        val initStackLine =
            if (!mainFrameAllocated) {
                sortedScopeLines.firstOrNull { l ->
                    val body = SfxLivenessAnalyzer.splitLabelPrefix(SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, l)).second
                    body.isNotEmpty() && body[0].text().equals("init_stack", ignoreCase = true)
                }
            } else {
                null
            }

        var pos = 0
        while (pos < sortedScopeLines.size) {
            val lineIdx = sortedScopeLines[pos]
            pos++
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
                            val lower = tok.text().lowercase(Locale.ROOT)
                            val reg = varToReg[lower]
                            when {
                                reg != null -> "${tok.text()}=${reg.name.lowercase(Locale.ROOT)}"
                                lower in spillSlots -> "${tok.text()}=[r10-spill#${spillSlots[lower]}]"
                                else -> null
                            }
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
                if (frameBytes > 0) {
                    out.add("$indent; [sfx-optimizer] allocate $frameBytes-byte R10 spill frame")
                    emitStackAdjust(indent, frameBytes, out)
                }
                loweredLines[lineIdx] = out.joinToString("\n")
                continue
            }

            if (!mainFrameAllocated && initStackLine == lineIdx) {
                val out = mutableListOf(rawLine)
                out.add("$indent; [sfx-optimizer] allocate $frameBytes-byte R10 spill frame")
                emitStackAdjust(indent, frameBytes, out)
                loweredLines[lineIdx] = out.joinToString("\n")
                mainFrameAllocated = true
                continue
            }

            if (enclosingFunction == null && frameBytes > 0 && mnemonic == "stop") {
                val out = mutableListOf<String>()
                if (labelPrefix != null) out.add("$indent$labelPrefix")
                emitStackAdjust(indent, -frameBytes, out)
                out.add("${indent}stop")
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
                    val retLower = retText.lowercase(Locale.ROOT)
                    if (retText.startsWith("#")) {
                        out.add("${indent}iwt ${targetRetReg.name.lowercase(Locale.ROOT)}, $retText")
                    } else if (retLower in spillSlots) {
                        val off = spillOffset(retLower, lineIdx)
                        if (targetRetReg in ADDRESS_REGISTERS) {
                            emitReloadSpill(indent, targetRetReg, off, out)
                        } else {
                            emitReloadSpill(indent, scratch1, off, out)
                            out.add("${indent}move ${targetRetReg.name.lowercase(Locale.ROOT)}, ${scratch1.name.lowercase(Locale.ROOT)}")
                        }
                    } else {
                        val srcReg = resolveNameToRegister(retText, varToReg)
                        if (srcReg != null && srcReg != targetRetReg) {
                            out.add("${indent}move ${targetRetReg.name.lowercase(Locale.ROOT)}, ${srcReg.name.lowercase(Locale.ROOT)}")
                        }
                    }
                }
                if (frameBytes > 0) {
                    emitStackAdjust(indent, -frameBytes, out)
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

                    val destLower = call.dest?.lowercase(Locale.ROOT)
                    val destIsSpilled = destLower != null && destLower in spillSlots
                    val destReg = if (destIsSpilled) null else call.dest?.let { resolveNameToRegister(it, varToReg) }
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

                    if (allowHoisting) {
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
                    val callExtraSp = callerSaveRegs.size * 2

                    // 2. Parameter moves (including spilled argument reloads)
                    if (calleeInfo != null && call.args.isNotEmpty()) {
                        val regMoves = mutableListOf<Pair<Constants.Register, Constants.Register>>()
                        val immMoves = mutableListOf<Pair<Constants.Register, String>>()
                        val spilledArgLoads = mutableListOf<Pair<Constants.Register, String>>()

                        for (idx in call.args.indices) {
                            if (idx >= calleeInfo.paramRegs.size) break
                            val dstParamReg = calleeInfo.paramRegs[idx]
                            when (val arg = call.args[idx]) {
                                is CallArg.RegOrVar -> {
                                    val argLower = arg.name.lowercase(Locale.ROOT)
                                    if (argLower in spillSlots) {
                                        spilledArgLoads.add(Pair(dstParamReg, argLower))
                                    } else {
                                        val srcReg =
                                            resolveNameToRegister(arg.name, varToReg)
                                                ?: error(
                                                    "Unknown register or variable '${arg.name}' in call to " +
                                                        "'${call.target}' at ${formatOrigin(origins, lineIdx)}",
                                                )
                                        regMoves.add(Pair(srcReg, dstParamReg))
                                    }
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
                        for ((dstParamReg, spVar) in spilledArgLoads) {
                            val off = spillOffset(spVar, lineIdx, callExtraSp)
                            if (dstParamReg in ADDRESS_REGISTERS) {
                                emitReloadSpill(indent, dstParamReg, off, out)
                            } else {
                                emitReloadSpill(indent, scratch1, off, out)
                                out.add("${indent}move ${dstParamReg.name.lowercase(Locale.ROOT)}, ${scratch1.name.lowercase(Locale.ROOT)}")
                            }
                        }
                        for ((dstParamReg, expr) in immMoves) {
                            out.add("${indent}iwt ${dstParamReg.name.lowercase(Locale.ROOT)}, $expr")
                        }
                    }

                    // 3. Emit call
                    out.add("${indent}call ${call.target}")

                    // 4. Move/store return value to destination if needed
                    if (destIsSpilled && calleeInfo?.returnReg != null) {
                        val retReg = calleeInfo.returnReg
                        val addrReg = if (retReg == scratch2) scratch1 else scratch2
                        emitLoadSpillAddr(indent, addrReg, spillOffset(destLower, lineIdx, callExtraSp), out)
                        emitStoreSpillViaAddr(indent, retReg, addrReg, out)
                    } else if (destReg != null && calleeInfo?.returnReg != null && destReg != calleeInfo.returnReg) {
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

            // If this is a (main) scope with spills and no `init_stack`, allocate frame before first real instruction
            val mainAllocPrefix = mutableListOf<String>()
            if (!mainFrameAllocated && initStackLine == null && !mnemonic.startsWith(".")) {
                mainAllocPrefix.add("$indent; [sfx-optimizer] allocate $frameBytes-byte R10 spill frame")
                emitStackAdjust(indent, frameBytes, mainAllocPrefix)
                mainFrameAllocated = true
            }

            // Group modal prefixes (`from`/`to`/`with`, 0..2 lines) + target instruction when spills exist
            if (spillSlots.isNotEmpty()) {
                val unitLines = mutableListOf(lineIdx)
                var curMn = mnemonic
                var lookPos = pos
                while (curMn in MODAL_PREFIXES && unitLines.size < 3 && lookPos < sortedScopeLines.size) {
                    val nextLineIdx = sortedScopeLines[lookPos]
                    val nextTokens = SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, nextLineIdx)
                    lookPos++
                    if (nextTokens.isEmpty()) continue
                    val (_, nextBody) = SfxLivenessAnalyzer.splitLabelPrefix(nextTokens)
                    if (nextBody.isEmpty()) continue
                    unitLines.add(nextLineIdx)
                    curMn = nextBody[0].text().lowercase(Locale.ROOT)
                }

                val hasSpillRef =
                    unitLines.any { uLine ->
                        val (_, uBody) = SfxLivenessAnalyzer.splitLabelPrefix(SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, uLine))
                        uBody.drop(1).withIndex().any { (i, tok) ->
                            !SfxLivenessAnalyzer.isInsideImmediateOrNamespace(uBody, i + 1) &&
                                tok.text().lowercase(Locale.ROOT) in spillSlots
                        }
                    }

                if (hasSpillRef) {
                    pos = lookPos
                    val loweredUnit =
                        lowerSpillUnit(
                            unitLines = unitLines,
                            tokenizedFile = tokenizedFile,
                            rawLines = rawLines,
                            declaredRegisters = declaredRegisters,
                            varToReg = varToReg,
                            spillSlots = spillSlots,
                            effects = effects,
                            scratch1 = scratch1,
                            scratch2 = scratch2,
                            spillOffsetFn = { v, l -> spillOffset(v, l) },
                        )
                    val firstLine = unitLines.first()
                    loweredLines[firstLine] =
                        if (mainAllocPrefix.isNotEmpty()) {
                            (mainAllocPrefix + loweredUnit).joinToString("\n")
                        } else {
                            loweredUnit.joinToString("\n")
                        }
                    for (otherLine in unitLines.drop(1)) {
                        loweredLines[otherLine] = ""
                    }
                    continue
                }
            }

            // Standard instruction/macro line: replace declared register variable tokens with allocated registers
            val modifiedLine =
                substituteLineRegisters(
                    rawLine = rawLine,
                    bodyTokens = bodyTokens,
                    declaredRegisters = declaredRegisters,
                    varToReg = varToReg,
                    origins = origins,
                    lineIdx = lineIdx,
                )
            loweredLines[lineIdx] =
                if (mainAllocPrefix.isNotEmpty()) {
                    (mainAllocPrefix + modifiedLine).joinToString("\n")
                } else {
                    modifiedLine
                }
        }

        var hoistedCount = 0
        for ((loop, regs) in hoistedByLoop) {
            hoistedCount += regs.size
            val indent = rawLines[loop.first].takeWhile { it == ' ' || it == '\t' }
            val pushes = regs.joinToString("\n") { "${indent}gsu_stack_push ${it.name.lowercase(Locale.ROOT)}" }
            val pops = regs.reversed().joinToString("\n") { "${indent}gsu_stack_pop ${it.name.lowercase(Locale.ROOT)}" }
            loweredLines[loop.first] = pushes + "\n" + loweredLines[loop.first]
            loweredLines[loop.last] = loweredLines[loop.last] + "\n" + pops
        }
        return hoistedCount
    }

    private fun substituteLineRegisters(
        rawLine: String,
        bodyTokens: List<dev.secondsun.retro.util.Token>,
        declaredRegisters: Map<String, DeclaredRegister>,
        varToReg: Map<String, Constants.Register>,
        origins: List<SourceOrigin>,
        lineIdx: Int,
    ): String {
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        for (idx in bodyTokens.indices) {
            if (idx == 0) continue
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
        if (replacements.isEmpty()) return rawLine
        var modified = rawLine
        for ((startCol, endCol, rep) in replacements.sortedByDescending { it.first }) {
            if (startCol >= 0 && endCol <= modified.length && startCol < endCol) {
                modified = modified.substring(0, startCol) + rep + modified.substring(endCol)
            }
        }
        return modified
    }

    private fun lowerSpillUnit(
        unitLines: List<Int>,
        tokenizedFile: TokenizedFile,
        rawLines: List<String>,
        declaredRegisters: Map<String, DeclaredRegister>,
        varToReg: Map<String, Constants.Register>,
        spillSlots: Map<String, Int>,
        effects: Map<Int, LineEffect>,
        scratch1: Constants.Register,
        scratch2: Constants.Register,
        spillOffsetFn: (String, Int) -> Int,
    ): List<String> {
        val firstLineIdx = unitLines.first()
        val indent = rawLines[firstLineIdx].takeWhile { it == ' ' || it == '\t' }
        val out = mutableListOf<String>()

        // Preserve any leading label on the first line before reloads
        val (leadingLabel, _) = SfxLivenessAnalyzer.splitLabelPrefix(SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, firstLineIdx))
        if (leadingLabel != null) {
            out.add("$indent$leadingLabel")
        }

        // Classify spilled variables referenced across this atomic modal unit
        val readSpills = linkedSetOf<String>()
        val writeSpills = linkedSetOf<String>()
        val allSpills = linkedSetOf<String>()

        for (uLine in unitLines) {
            val (_, uBody) = SfxLivenessAnalyzer.splitLabelPrefix(SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, uLine))
            val uMn = uBody.firstOrNull()?.text()?.lowercase(Locale.ROOT) ?: continue
            val eff = effects[uLine]
            val lineSpills =
                uBody
                    .drop(1)
                    .withIndex()
                    .filter { (i, tok) ->
                        !SfxLivenessAnalyzer.isInsideImmediateOrNamespace(uBody, i + 1) &&
                            tok.text().lowercase(Locale.ROOT) in spillSlots
                    }.map { it.value.text().lowercase(Locale.ROOT) }
            allSpills.addAll(lineSpills)

            when (uMn) {
                "from" -> readSpills.addAll(lineSpills)
                "to" -> writeSpills.addAll(lineSpills)
                "with" -> {
                    readSpills.addAll(lineSpills)
                    writeSpills.addAll(lineSpills)
                }
                else -> {
                    if (eff != null) {
                        for (k in eff.explicitReads.values) {
                            if (k is IntervalKey.LabelKey && k.label.lowercase(Locale.ROOT) in spillSlots) {
                                readSpills.add(k.label.lowercase(Locale.ROOT))
                            }
                        }
                        for (k in eff.explicitWrites.values) {
                            if (k is IntervalKey.LabelKey && k.label.lowercase(Locale.ROOT) in spillSlots) {
                                writeSpills.add(k.label.lowercase(Locale.ROOT))
                            }
                        }
                    } else {
                        readSpills.addAll(lineSpills)
                    }
                }
            }
        }

        // Special-case pure `move spDst, spSrc` (2 distinct spilled variables): direct load+store without extra move
        if (unitLines.size == 1) {
            val (_, uBody) = SfxLivenessAnalyzer.splitLabelPrefix(SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, firstLineIdx))
            if (uBody.size >= 4 && uBody[0].text().equals("move", ignoreCase = true)) {
                val dstName = uBody[1].text().lowercase(Locale.ROOT)
                val srcName = uBody[3].text().lowercase(Locale.ROOT)
                if (dstName in spillSlots && srcName in spillSlots && dstName != srcName) {
                    emitReloadSpill(indent, scratch1, spillOffsetFn(srcName, firstLineIdx), out)
                    emitLoadSpillAddr(indent, scratch2, spillOffsetFn(dstName, firstLineIdx), out)
                    emitStoreSpillViaAddr(indent, scratch1, scratch2, out)
                    return out
                }
            }
        }

        // Map spilled variables to scratch registers
        val localRegMap = HashMap<String, Constants.Register>(varToReg)
        val precomputedWriteAddr: Pair<String, Constants.Register>?

        if (allSpills.size == 1) {
            val sp = allSpills.first()
            localRegMap[sp] = scratch1
            if (sp in readSpills) {
                emitReloadSpill(indent, scratch1, spillOffsetFn(sp, firstLineIdx), out)
            }
            precomputedWriteAddr =
                if (sp in writeSpills) {
                    // Precompute store address in scratch2 BEFORE instruction so Z/S/CY/OV flags survive store-back!
                    emitLoadSpillAddr(indent, scratch2, spillOffsetFn(sp, firstLineIdx), out)
                    sp to scratch2
                } else {
                    null
                }
        } else {
            val writtenVar = writeSpills.firstOrNull()
            val readOnlyVars = allSpills.filter { it != writtenVar }
            if (writtenVar != null) {
                localRegMap[writtenVar] = scratch1
                if (writtenVar in readSpills) {
                    emitReloadSpill(indent, scratch1, spillOffsetFn(writtenVar, firstLineIdx), out)
                }
                for ((idx, rv) in readOnlyVars.withIndex()) {
                    val targetScratch = if (idx == 0) scratch2 else scratch1
                    localRegMap[rv] = targetScratch
                    if (rv in readSpills) {
                        emitReloadSpill(indent, targetScratch, spillOffsetFn(rv, firstLineIdx), out)
                    }
                }
            } else {
                val list = allSpills.toList()
                localRegMap[list[0]] = scratch1
                if (list[0] in readSpills) emitReloadSpill(indent, scratch1, spillOffsetFn(list[0], firstLineIdx), out)
                if (list.size > 1) {
                    localRegMap[list[1]] = scratch2
                    if (list[1] in readSpills) emitReloadSpill(indent, scratch2, spillOffsetFn(list[1], firstLineIdx), out)
                }
            }
            precomputedWriteAddr = null
        }

        // Emit substituted unit lines (stripping leading label from first line since we already emitted it)
        for ((idxInUnit, uLine) in unitLines.withIndex()) {
            val origLine = rawLines[uLine]
            val codeToks = SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, uLine)
            val (uLbl, uBody) = SfxLivenessAnalyzer.splitLabelPrefix(codeToks)
            var subLine =
                substituteLineRegisters(
                    rawLine = origLine,
                    bodyTokens = uBody,
                    declaredRegisters = declaredRegisters,
                    varToReg = localRegMap,
                    origins = emptyList(),
                    lineIdx = uLine,
                )
            if (idxInUnit == 0 && uLbl != null) {
                val lblIdx = subLine.indexOf(uLbl)
                if (lblIdx >= 0) {
                    subLine = indent + subLine.substring(lblIdx + uLbl.length).trimStart()
                }
            }
            out.add(subLine)
        }

        // Store back any written spilled variable(s)
        if (precomputedWriteAddr != null) {
            val (sp, addrReg) = precomputedWriteAddr
            emitStoreSpillViaAddr(indent, localRegMap[sp]!!, addrReg, out)
        } else {
            for (sp in writeSpills) {
                val valReg = localRegMap[sp] ?: scratch1
                val addrReg = if (valReg == scratch1) scratch2 else scratch1
                emitLoadSpillAddr(indent, addrReg, spillOffsetFn(sp, firstLineIdx), out)
                emitStoreSpillViaAddr(indent, valReg, addrReg, out)
            }
        }

        return out
    }

    private fun computeDynamicStackDeltas(
        tokenizedFile: TokenizedFile,
        cfg: ScopeCfg,
    ): Map<Int, Int> {
        if (cfg.blocks.isEmpty()) return emptyMap()
        val blockById = cfg.blocks.associateBy { it.id }
        val entryDepths = linkedMapOf<String, Int>()
        val lineDepths = linkedMapOf<Int, Int>()
        val entry = cfg.blocks.first()
        val worklist = ArrayDeque<String>()
        entryDepths[entry.id] = 0
        worklist.addLast(entry.id)

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeFirst()
            val block = blockById[blockId] ?: continue
            var depth = entryDepths[blockId] ?: 0
            var pendingWithR10 = false

            for (lineIdx in block.lineIndices) {
                lineDepths[lineIdx] = depth
                val (_, body) = SfxLivenessAnalyzer.splitLabelPrefix(SfxLivenessAnalyzer.getCodeTokens(tokenizedFile, lineIdx))
                if (body.isEmpty()) continue
                val mn = body[0].text().lowercase(Locale.ROOT)
                when (mn) {
                    "gsu_stack_push" -> {
                        depth += 2
                        pendingWithR10 = false
                    }
                    "gsu_stack_pop" -> {
                        depth -= 2
                        pendingWithR10 = false
                    }
                    "for", "forr" -> {
                        depth += 4
                        pendingWithR10 = false
                    }
                    "endfor" -> {
                        depth -= 4
                        pendingWithR10 = false
                    }
                    "gsu_stack_alloc_bytes" -> {
                        val n =
                            body
                                .getOrNull(1)
                                ?.text()
                                ?.removePrefix("#")
                                ?.let { SfxPeepholeOptimizer.parseLiteral(it)?.toInt() } ?: 0
                        depth += n
                        pendingWithR10 = false
                    }
                    "gsu_stack_free_bytes" -> {
                        val n =
                            body
                                .getOrNull(1)
                                ?.text()
                                ?.removePrefix("#")
                                ?.let { SfxPeepholeOptimizer.parseLiteral(it)?.toInt() } ?: 0
                        depth -= n
                        pendingWithR10 = false
                    }
                    "inc" -> {
                        if (body.getOrNull(1)?.text().equals("r10", ignoreCase = true)) depth += 1
                        pendingWithR10 = false
                    }
                    "dec" -> {
                        if (body.getOrNull(1)?.text().equals("r10", ignoreCase = true)) depth -= 1
                        pendingWithR10 = false
                    }
                    "with", "to" -> {
                        pendingWithR10 = body.getOrNull(1)?.text().equals("r10", ignoreCase = true)
                    }
                    "add", "sub" -> {
                        if (pendingWithR10) {
                            val immTok = body.drop(1).joinToString("") { it.text() }
                            if (immTok.startsWith("#")) {
                                val n = SfxPeepholeOptimizer.parseLiteral(immTok.substring(1))?.toInt() ?: 0
                                depth += if (mn == "add") n else -n
                            }
                        }
                        pendingWithR10 = false
                    }
                    else -> pendingWithR10 = false
                }
            }

            for (succ in block.exits) {
                if (succ !in entryDepths) {
                    entryDepths[succ] = depth
                    worklist.addLast(succ)
                }
            }
        }
        return lineDepths
    }

    private fun emitStackAdjust(
        indent: String,
        deltaBytes: Int,
        out: MutableList<String>,
    ) {
        var rem = kotlin.math.abs(deltaBytes)
        val op = if (deltaBytes >= 0) "add" else "sub"
        while (rem > 0) {
            val chunk = minOf(rem, 14)
            out.add("${indent}with r10")
            out.add("$indent$op #$chunk")
            rem -= chunk
        }
    }

    private fun emitLoadSpillAddr(
        indent: String,
        dst: Constants.Register,
        offsetBelowR10: Int,
        out: MutableList<String>,
    ) {
        val d = dst.name.lowercase(Locale.ROOT)
        if (offsetBelowR10 in 1..15) {
            out.add("${indent}from r10")
            out.add("${indent}to $d")
            out.add("${indent}sub #$offsetBelowR10")
        } else {
            out.add("${indent}iwt $d, #$offsetBelowR10")
            out.add("${indent}from r10")
            out.add("${indent}to $d")
            out.add("${indent}sub $d")
        }
    }

    private fun emitReloadSpill(
        indent: String,
        dst: Constants.Register,
        offsetBelowR10: Int,
        out: MutableList<String>,
    ) {
        val d = dst.name.lowercase(Locale.ROOT)
        emitLoadSpillAddr(indent, dst, offsetBelowR10, out)
        out.add("${indent}to $d")
        out.add("${indent}ldw ($d)")
    }

    private fun emitStoreSpillViaAddr(
        indent: String,
        src: Constants.Register,
        addrReg: Constants.Register,
        out: MutableList<String>,
    ) {
        val s = src.name.lowercase(Locale.ROOT)
        val a = addrReg.name.lowercase(Locale.ROOT)
        out.add("${indent}from $s")
        out.add("${indent}stw ($a)")
    }

    companion object {
        private val MODAL_PREFIXES = setOf("from", "to", "with")
        private val ADDRESS_REGISTERS =
            setOf(
                Constants.Register.R1,
                Constants.Register.R2,
                Constants.Register.R3,
                Constants.Register.R4,
                Constants.Register.R5,
                Constants.Register.R6,
                Constants.Register.R7,
                Constants.Register.R8,
                Constants.Register.R9,
                Constants.Register.R11,
            )
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
