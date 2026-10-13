package dev.secondsun.sfxoptimizer.compiler

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.FunctionSyntaxHelper
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.AllocationResult
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey
import dev.secondsun.sfxoptimizer.solver.ParallelMoveResolver
import java.io.File
import java.util.Locale

/**
 * Compiler and lowering pass for SuperFX (.sgs / .i) assembly files.
 *
 * Performs:
 * 1. Recursive `.include` resolution and deduplication
 * 2. Function and main-scope boundary discovery
 * 3. Bottom-up call-graph ordering (callees allocated before callers)
 * 4. Instruction and macro register liveness & loop-carried interval extension
 * 5. Chaitin-Briggs graph-coloring register allocation with SuperFX hardware constraints
 * 6. Lowering of `register`, `function`, `return`, `call` (with parallel moves and caller-save push/pop),
 *    and variable token replacement into standard ca65 SuperFX assembly.
 */
class SfxCompiler {
    data class DeclaredRegister(
        val name: String,
        val forcedRegister: Constants.Register? = null,
        val line: Int,
    )

    data class ParsedCall(
        val target: String,
        val args: List<CallArg>,
        val dest: String?,
    )

    sealed interface CallArg {
        data class RegOrVar(
            val name: String,
            val token: Token,
        ) : CallArg

        data class Immediate(
            val expr: String,
        ) : CallArg
    }

    data class FunctionScope(
        val name: String,
        val startLine: Int,
        val endLine: Int,
        val params: List<String>,
        val headerReturnVar: String?,
        val inferredReturnVar: String?,
    ) {
        val effectiveReturnVar: String?
            get() = headerReturnVar ?: inferredReturnVar
    }

    data class AllocatedFunctionInfo(
        val name: String,
        val paramRegs: List<Constants.Register>,
        val returnReg: Constants.Register?,
        val clobberedRegisters: Set<Constants.Register>,
        val allocatedMap: Map<String, Constants.Register>,
    )

    data class LineEffect(
        val lineIdx: Int,
        val instructionToken: Token?,
        val explicitReads: Map<Token, IntervalKey> = emptyMap(),
        val explicitWrites: Map<Token, IntervalKey> = emptyMap(),
        val implicitReads: List<IntervalKey> = emptyList(),
        val implicitWrites: List<IntervalKey> = emptyList(),
        val memoryAddressKeys: Set<IntervalKey> = emptySet(),
    )

    data class CompilationResult(
        val loweredSource: String,
        val functionInfos: Map<String, AllocatedFunctionInfo>,
        val mainAllocations: Map<String, Constants.Register>,
    )

    // Primary register pool: R1..R9, R11 (all legal in ldw/stw/ldb/stb (Rm))
    private val primaryPool =
        listOf(
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

    // Extended pool for scalar variables never used as (Rm) in ldw/stw/ldb/stb
    private val extendedPool =
        primaryPool +
            listOf(
                Constants.Register.R12,
                Constants.Register.R13,
            )

    private val branchMnemonics =
        setOf(
            "bra",
            "beq",
            "bne",
            "blt",
            "bge",
            "bpl",
            "bmi",
            "bcc",
            "bcs",
            "bvc",
            "bvs",
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
        incPath: String,
        currentDir: File,
        includeDirs: List<File>,
    ): File? {
        val direct = File(currentDir, incPath)
        if (direct.exists() && direct.isFile) return direct
        for (dir in includeDirs) {
            val candidate = File(dir, incPath)
            if (candidate.exists() && candidate.isFile) return candidate
        }
        return null
    }

    private fun compileExpanded(expandedSource: String): CompilationResult {
        val scanner = CA65Scanner()
        val tokenizedFile = scanner.tokenize(expandedSource)
        val rawLines = expandedSource.lines()
        val totalLines = rawLines.size

        // 1. Identify lines inside .macro ... .endmacro so we don't treat macro bodies as functions/code
        val macroLines = findMacroDefinitionLines(rawLines)

        // 2. Discover all functions in the expanded unit
        val functions = discoverFunctions(tokenizedFile, rawLines, macroLines)
        val functionMap = functions.associateBy { it.name.lowercase(Locale.ROOT) }

        // 3. Build call graph and sort functions bottom-up (callees before callers)
        val sortedFunctions = sortFunctionsBottomUp(functions, tokenizedFile, macroLines)

        // 4. Allocate each function in bottom-up order
        val allocatedFunctions = mutableMapOf<String, AllocatedFunctionInfo>()
        val loweredLines = rawLines.toMutableList()

        for (fn in sortedFunctions) {
            val fnLines = (fn.startLine..fn.endLine).filter { it !in macroLines }.toSet()
            val declared = extractDeclaredRegisters(tokenizedFile, fnLines, fn.params, fn.effectiveReturnVar)
            val effects = analyzeScopeLines(tokenizedFile, fnLines, declared, fn.params, fn.effectiveReturnVar, fn.startLine)
            val loops = detectLoops(tokenizedFile, fnLines)
            val intervals = buildIntervals(fnLines, effects, declared, loops)

            val avoidMap = computeCallerSaveAvoidance(tokenizedFile, fnLines, declared, intervals, loops, allocatedFunctions)
            val memoryKeys = effects.values.flatMap { it.memoryAddressKeys }.toSet()
            val precolored = buildPrecoloredMap(declared)

            val allocations = allocateGraph(intervals, precolored, memoryKeys, avoidMap)
            val varToReg = mutableMapOf<String, Constants.Register>()
            for ((key, res) in allocations) {
                if (key is IntervalKey.LabelKey && res is AllocationResult.Register) {
                    varToReg[key.label.lowercase(Locale.ROOT)] = res.register
                }
            }

            val paramRegs =
                fn.params.map { p ->
                    val lower = p.lowercase(Locale.ROOT)
                    Constants.register(lower)
                        ?: varToReg[lower]
                        ?: error("Unallocated parameter '$p' in function '${fn.name}'")
                }

            val returnReg =
                fn.effectiveReturnVar?.let { ret ->
                    val lower = ret.lowercase(Locale.ROOT)
                    Constants.register(lower)
                        ?: varToReg[lower]
                }

            // Compute clobbered registers of this function
            val clobbered = mutableSetOf<Constants.Register>()
            clobbered.add(Constants.Register.R11) // link #4 clobbers r11
            clobbered.addAll(paramRegs)
            if (returnReg != null) clobbered.add(returnReg)

            for (lineIdx in fnLines) {
                val eff = effects[lineIdx] ?: continue
                for (wKey in eff.explicitWrites.values + eff.implicitWrites) {
                    resolveKeyToRegister(wKey, varToReg)?.let { clobbered.add(it) }
                }
                val codeTokens = getCodeTokens(tokenizedFile, lineIdx)
                val (_, bodyTokens) = splitLabelPrefix(codeTokens)
                if (FunctionSyntaxHelper.isCall(bodyTokens)) {
                    val call = parseCallStatement(bodyTokens)
                    if (call != null) {
                        val calleeInfo = allocatedFunctions[call.target.lowercase(Locale.ROOT)]
                        if (calleeInfo != null) {
                            clobbered.addAll(calleeInfo.clobberedRegisters)
                        }
                    }
                }
            }

            val info =
                AllocatedFunctionInfo(
                    name = fn.name,
                    paramRegs = paramRegs,
                    returnReg = returnReg,
                    clobberedRegisters = clobbered,
                    allocatedMap = varToReg,
                )
            allocatedFunctions[fn.name.lowercase(Locale.ROOT)] = info

            // Lower function lines
            lowerScopeLines(
                tokenizedFile = tokenizedFile,
                rawLines = rawLines,
                loweredLines = loweredLines,
                scopeLines = fnLines,
                declaredRegisters = declared,
                varToReg = varToReg,
                intervals = intervals,
                loops = loops,
                allocatedFunctions = allocatedFunctions,
                enclosingFunction = fn,
                enclosingFunctionInfo = info,
            )
        }

        // 5. Process and lower main (top-level non-function, non-macro lines)
        val allFnLines = functions.flatMap { it.startLine..it.endLine }.toSet()
        val mainLines =
            (0 until totalLines)
                .filter { it !in allFnLines && it !in macroLines }
                .toSet()

        val mainDeclared = extractDeclaredRegisters(tokenizedFile, mainLines, emptyList(), null)
        val mainEffects = analyzeScopeLines(tokenizedFile, mainLines, mainDeclared, emptyList(), null, 0)
        val mainLoops = detectLoops(tokenizedFile, mainLines)
        val mainIntervals = buildIntervals(mainLines, mainEffects, mainDeclared, mainLoops)
        val mainAvoid = computeCallerSaveAvoidance(tokenizedFile, mainLines, mainDeclared, mainIntervals, mainLoops, allocatedFunctions)
        val mainMemoryKeys = mainEffects.values.flatMap { it.memoryAddressKeys }.toSet()
        val mainPrecolored = buildPrecoloredMap(mainDeclared)
        val mainAllocations = allocateGraph(mainIntervals, mainPrecolored, mainMemoryKeys, mainAvoid)

        val mainVarToReg = mutableMapOf<String, Constants.Register>()
        for ((key, res) in mainAllocations) {
            if (key is IntervalKey.LabelKey && res is AllocationResult.Register) {
                mainVarToReg[key.label.lowercase(Locale.ROOT)] = res.register
            }
        }

        lowerScopeLines(
            tokenizedFile = tokenizedFile,
            rawLines = rawLines,
            loweredLines = loweredLines,
            scopeLines = mainLines,
            declaredRegisters = mainDeclared,
            varToReg = mainVarToReg,
            intervals = mainIntervals,
            loops = mainLoops,
            allocatedFunctions = allocatedFunctions,
            enclosingFunction = null,
            enclosingFunctionInfo = null,
        )

        return CompilationResult(
            loweredSource = loweredLines.joinToString("\n"),
            functionInfos = allocatedFunctions,
            mainAllocations = mainVarToReg,
        )
    }

    private fun findMacroDefinitionLines(rawLines: List<String>): Set<Int> {
        val result = mutableSetOf<Int>()
        var depth = 0
        for (i in rawLines.indices) {
            val trimmed = rawLines[i].substringBefore(";").trim().lowercase(Locale.ROOT)
            if (trimmed.startsWith(".macro ") || trimmed == ".macro") {
                depth++
                result.add(i)
            } else if (trimmed.startsWith(".endmacro") || trimmed.startsWith(".endm")) {
                result.add(i)
                if (depth > 0) depth--
            } else if (depth > 0) {
                result.add(i)
            }
        }
        return result
    }

    private fun getCodeTokens(
        file: TokenizedFile,
        lineIdx: Int,
    ): List<Token> {
        val tokens = file.getLineTokens(lineIdx) ?: return emptyList()
        return tokens.filter { !it.text().startsWith(";") }
    }

    private fun splitLabelPrefix(codeTokens: List<Token>): Pair<String?, List<Token>> {
        if (codeTokens.isEmpty()) return Pair(null, emptyList())
        val first = codeTokens[0].text()
        if (first.endsWith(":")) {
            return Pair(first, codeTokens.subList(1, codeTokens.size))
        }
        if (codeTokens.size >= 2 &&
            codeTokens[1].text() == ":" &&
            (codeTokens[1].endIndex - codeTokens[1].text().length) == codeTokens[0].endIndex &&
            first.lowercase(Locale.ROOT) !in branchMnemonics
        ) {
            return Pair(first + ":", codeTokens.subList(2, codeTokens.size))
        }
        return Pair(null, codeTokens)
    }

    private fun discoverFunctions(
        file: TokenizedFile,
        rawLines: List<String>,
        macroLines: Set<Int>,
    ): List<FunctionScope> {
        val result = mutableListOf<FunctionScope>()
        var currentName: String? = null
        var currentStart = 0
        var currentParams = emptyList<String>()
        var currentHeaderRet: String? = null
        var currentInferredRet: String? = null

        for (lineIdx in rawLines.indices) {
            if (lineIdx in macroLines) continue
            val codeTokens = getCodeTokens(file, lineIdx)
            val (_, bodyTokens) = splitLabelPrefix(codeTokens)
            if (bodyTokens.isEmpty()) continue

            if (FunctionSyntaxHelper.isFunction(bodyTokens)) {
                val decl = FunctionSyntaxHelper.parseFunction(bodyTokens)
                if (decl.nameToken() != null) {
                    currentName = decl.nameToken().text()
                    currentStart = lineIdx
                    currentParams = decl.parameters().map { it.text() }
                    currentHeaderRet = decl.returnVariable().map { it.text() }.orElse(null)
                    currentInferredRet = null
                }
            } else if (FunctionSyntaxHelper.isEndFunction(bodyTokens) && currentName != null) {
                result.add(
                    FunctionScope(
                        name = currentName,
                        startLine = currentStart,
                        endLine = lineIdx,
                        params = currentParams,
                        headerReturnVar = currentHeaderRet,
                        inferredReturnVar = currentInferredRet,
                    ),
                )
                currentName = null
            } else if (currentName != null && FunctionSyntaxHelper.isReturn(bodyTokens)) {
                val retStmt = FunctionSyntaxHelper.parseReturn(bodyTokens)
                if (retStmt.returnVariable().isPresent && currentInferredRet == null) {
                    val tok = retStmt.returnVariable().get()
                    if (!tok.text().startsWith("#")) {
                        currentInferredRet = tok.text()
                    }
                }
            }
        }
        return result
    }

    private fun sortFunctionsBottomUp(
        functions: List<FunctionScope>,
        file: TokenizedFile,
        macroLines: Set<Int>,
    ): List<FunctionScope> {
        val fnByName = functions.associateBy { it.name.lowercase(Locale.ROOT) }
        val callees = mutableMapOf<String, MutableSet<String>>()
        for (fn in functions) {
            val key = fn.name.lowercase(Locale.ROOT)
            val set = callees.getOrPut(key) { mutableSetOf() }
            for (lineIdx in fn.startLine..fn.endLine) {
                if (lineIdx in macroLines) continue
                val (_, bodyTokens) = splitLabelPrefix(getCodeTokens(file, lineIdx))
                if (FunctionSyntaxHelper.isCall(bodyTokens)) {
                    val call = parseCallStatement(bodyTokens)
                    if (call != null) {
                        val targetKey = call.target.lowercase(Locale.ROOT)
                        if (targetKey in fnByName && targetKey != key) {
                            set.add(targetKey)
                        }
                    }
                }
            }
        }

        val visited = mutableSetOf<String>()
        val ordered = mutableListOf<FunctionScope>()

        fun dfs(nameKey: String) {
            if (!visited.add(nameKey)) return
            for (calleeKey in callees[nameKey] ?: emptySet()) {
                dfs(calleeKey)
            }
            fnByName[nameKey]?.let { ordered.add(it) }
        }

        for (fn in functions) {
            dfs(fn.name.lowercase(Locale.ROOT))
        }
        return ordered
    }

    fun parseCallStatement(bodyTokens: List<Token>): ParsedCall? {
        if (bodyTokens.size < 2) return null
        val first = bodyTokens[0].text().lowercase(Locale.ROOT)
        if (first != "call") return null
        val target = bodyTokens[1].text()
        val args = mutableListOf<CallArg>()
        var dest: String? = null

        var i = 2
        while (i < bodyTokens.size) {
            val tok = bodyTokens[i]
            if (tok.text() == ":" || tok.type == TokenType.TOK_COLON) {
                i++
                if (i < bodyTokens.size) {
                    dest = bodyTokens[i].text()
                }
                break
            }
            if (tok.text() == "," || tok.type == TokenType.TOK_COMMA) {
                i++
                continue
            }
            if (tok.text().startsWith("#")) {
                if (tok.text().length > 1) {
                    args.add(CallArg.Immediate(tok.text()))
                    i++
                } else if (i + 1 < bodyTokens.size) {
                    val sb = StringBuilder("#")
                    i++
                    if (bodyTokens[i].text() == "(") {
                        var parenDepth = 0
                        while (i < bodyTokens.size) {
                            val t = bodyTokens[i].text()
                            sb.append(t)
                            if (t == "(") parenDepth++
                            if (t == ")") {
                                parenDepth--
                                if (parenDepth <= 0) {
                                    i++
                                    break
                                }
                            }
                            i++
                        }
                    } else {
                        sb.append(bodyTokens[i].text())
                        i++
                    }
                    args.add(CallArg.Immediate(sb.toString()))
                } else {
                    i++
                }
            } else {
                args.add(CallArg.RegOrVar(tok.text(), tok))
                i++
            }
        }

        return ParsedCall(target = target, args = args, dest = dest)
    }

    private fun extractDeclaredRegisters(
        file: TokenizedFile,
        scopeLines: Set<Int>,
        params: List<String>,
        returnVar: String?,
    ): Map<String, DeclaredRegister> {
        val result = mutableMapOf<String, DeclaredRegister>()
        val minLine = scopeLines.minOrNull() ?: 0

        for (param in params) {
            val lower = param.lowercase(Locale.ROOT)
            if (!Constants.isRegister(lower)) {
                result[lower] = DeclaredRegister(name = param, forcedRegister = null, line = minLine)
            }
        }
        if (returnVar != null) {
            val lower = returnVar.lowercase(Locale.ROOT)
            if (!Constants.isRegister(lower) && lower !in result) {
                result[lower] = DeclaredRegister(name = returnVar, forcedRegister = null, line = minLine)
            }
        }

        for (lineIdx in scopeLines.sorted()) {
            val (_, bodyTokens) = splitLabelPrefix(getCodeTokens(file, lineIdx))
            if (bodyTokens.isEmpty()) continue
            val firstText = bodyTokens[0].text().lowercase(Locale.ROOT)
            if (firstText == "register" || bodyTokens[0].type == TokenType.TOK_REGISTER_KEYWORD) {
                var i = 1
                while (i < bodyTokens.size) {
                    val tok = bodyTokens[i]
                    if (tok.type == TokenType.TOK_COMMA || tok.text() == ",") {
                        i++
                        continue
                    }
                    val varName = tok.text()
                    val varLower = varName.lowercase(Locale.ROOT)
                    if (varName == "=" || Constants.isRegister(varLower)) {
                        i++
                        continue
                    }
                    i++
                    var forcedReg: Constants.Register? = null
                    if (i < bodyTokens.size && (bodyTokens[i].text() == "=" || bodyTokens[i].type == TokenType.TOK_EQ)) {
                        i++
                        if (i < bodyTokens.size) {
                            val regTok = bodyTokens[i]
                            forcedReg =
                                Constants.register(regTok.text())
                                    ?: Constants.Register.entries.find { it.name.equals(regTok.text(), ignoreCase = true) }
                            i++
                        }
                    }
                    val existing = result[varLower]
                    result[varLower] =
                        DeclaredRegister(
                            name = existing?.name ?: varName,
                            forcedRegister = forcedReg ?: existing?.forcedRegister,
                            line = existing?.line ?: lineIdx,
                        )
                }
            }
        }
        return result
    }

    private fun resolveKey(
        token: Token,
        declaredRegisters: Map<String, DeclaredRegister>,
    ): IntervalKey? {
        val text = token.text().lowercase(Locale.ROOT)
        val reg = Constants.register(text)
        if (reg != null) {
            return IntervalKey.RegisterKey(reg)
        }
        val declared = declaredRegisters[text]
        if (declared != null) {
            return IntervalKey.LabelKey(declared.name)
        }
        return null
    }

    private fun resolveKeyByName(
        name: String,
        declaredRegisters: Map<String, DeclaredRegister>,
    ): IntervalKey? {
        val lower = name.lowercase(Locale.ROOT)
        val reg = Constants.register(lower)
        if (reg != null) return IntervalKey.RegisterKey(reg)
        val declared = declaredRegisters[lower]
        if (declared != null) return IntervalKey.LabelKey(declared.name)
        return null
    }

    private fun isInsideImmediateOrNamespace(
        tokens: List<Token>,
        idx: Int,
    ): Boolean {
        if (idx < 0 || idx >= tokens.size) return false
        val tok = tokens[idx]
        if (tok.text().startsWith("#")) return true
        if (idx > 0 && tokens[idx - 1].text() == "::") return true
        if (idx + 1 < tokens.size && tokens[idx + 1].text() == "::") return true

        // Walk backward to start of current comma/colon-separated operand to check for '#'
        var i = idx - 1
        var parenDepth = 0
        while (i >= 0) {
            val t = tokens[i].text()
            if (t == ")") parenDepth++
            if (t == "(") {
                if (parenDepth > 0) parenDepth--
            }
            if (parenDepth <= 0 && (t == "," || t == ":")) {
                break
            }
            if (t == "#" || t.startsWith("#")) {
                return true
            }
            i--
        }
        return false
    }

    private fun findRegisterInArgs(
        args: List<Token>,
        declaredRegisters: Map<String, DeclaredRegister>,
    ): Pair<Token, IntervalKey>? {
        for (i in args.indices) {
            if (isInsideImmediateOrNamespace(args, i)) continue
            val tok = args[i]
            val key = resolveKey(tok, declaredRegisters)
            if (key != null) {
                return Pair(tok, key)
            }
        }
        return null
    }

    private fun analyzeScopeLines(
        file: TokenizedFile,
        scopeLines: Set<Int>,
        declaredRegisters: Map<String, DeclaredRegister>,
        params: List<String>,
        effectiveReturnVar: String?,
        fnStartLine: Int,
    ): Map<Int, LineEffect> {
        val result = mutableMapOf<Int, LineEffect>()
        var pendingSource: IntervalKey? = null
        var pendingDest: IntervalKey? = null

        val r0Key = IntervalKey.RegisterKey(Constants.Register.R0)
        val r1Key = IntervalKey.RegisterKey(Constants.Register.R1)
        val r2Key = IntervalKey.RegisterKey(Constants.Register.R2)
        val r4Key = IntervalKey.RegisterKey(Constants.Register.R4)
        val r5Key = IntervalKey.RegisterKey(Constants.Register.R5)
        val r6Key = IntervalKey.RegisterKey(Constants.Register.R6)
        val r7Key = IntervalKey.RegisterKey(Constants.Register.R7)
        val r8Key = IntervalKey.RegisterKey(Constants.Register.R8)
        val r11Key = IntervalKey.RegisterKey(Constants.Register.R11)
        val r12Key = IntervalKey.RegisterKey(Constants.Register.R12)
        val r13Key = IntervalKey.RegisterKey(Constants.Register.R13)
        val r14Key = IntervalKey.RegisterKey(Constants.Register.R14)

        for (lineIdx in scopeLines.sorted()) {
            val codeTokens = getCodeTokens(file, lineIdx)
            if (codeTokens.isEmpty()) continue

            val (labelPrefix, bodyTokens) = splitLabelPrefix(codeTokens)
            if (labelPrefix != null) {
                pendingSource = null
                pendingDest = null
            }
            if (bodyTokens.isEmpty()) continue

            val insnTok = bodyTokens[0]
            val mnemonic = insnTok.text().lowercase(Locale.ROOT)
            if (mnemonic.startsWith(".")) continue
            // Skip ca65 constant assignment `FOO = BAR`
            if (bodyTokens.size >= 2 && bodyTokens[1].text() == "=") continue
            if (mnemonic == "register" || insnTok.type == TokenType.TOK_REGISTER_KEYWORD) continue

            val args = bodyTokens.subList(1, bodyTokens.size)
            val explicitReads = mutableMapOf<Token, IntervalKey>()
            val explicitWrites = mutableMapOf<Token, IntervalKey>()
            val implicitReads = mutableListOf<IntervalKey>()
            val implicitWrites = mutableListOf<IntervalKey>()
            val memoryAddressKeys = mutableSetOf<IntervalKey>()

            fun useSource() {
                implicitReads.add(pendingSource ?: r0Key)
            }

            fun useDest() {
                implicitWrites.add(pendingDest ?: r0Key)
            }

            when (mnemonic) {
                "function" -> {
                    for (param in params) {
                        resolveKeyByName(param, declaredRegisters)?.let { implicitWrites.add(it) }
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "endfunction" -> {
                    if (effectiveReturnVar != null) {
                        resolveKeyByName(effectiveReturnVar, declaredRegisters)?.let { implicitReads.add(it) }
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "return" -> {
                    val retStmt = FunctionSyntaxHelper.parseReturn(bodyTokens)
                    if (retStmt.returnVariable().isPresent) {
                        val retTok = retStmt.returnVariable().get()
                        resolveKey(retTok, declaredRegisters)?.let { explicitReads[retTok] = it }
                    }
                    if (effectiveReturnVar != null) {
                        resolveKeyByName(effectiveReturnVar, declaredRegisters)?.let { implicitReads.add(it) }
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "call" -> {
                    val call = parseCallStatement(bodyTokens)
                    if (call != null) {
                        for (arg in call.args) {
                            if (arg is CallArg.RegOrVar) {
                                resolveKey(arg.token, declaredRegisters)?.let { explicitReads[arg.token] = it }
                            }
                        }
                        if (call.dest != null) {
                            val destTok = bodyTokens.last()
                            resolveKeyByName(call.dest, declaredRegisters)?.let { explicitWrites[destTok] = it }
                        }
                    }
                    implicitWrites.add(r11Key)
                    pendingSource = null
                    pendingDest = null
                }
                "to" -> {
                    if (args.isNotEmpty()) {
                        val target = resolveKey(args[0], declaredRegisters)
                        pendingDest = target
                        if (target != null) explicitWrites[args[0]] = target
                    }
                }
                "from" -> {
                    if (args.isNotEmpty()) {
                        val source = resolveKey(args[0], declaredRegisters)
                        pendingSource = source
                        if (source != null) explicitReads[args[0]] = source
                    }
                }
                "with" -> {
                    if (args.isNotEmpty()) {
                        val target = resolveKey(args[0], declaredRegisters)
                        pendingDest = target
                        pendingSource = target
                        if (target != null) {
                            explicitWrites[args[0]] = target
                            explicitReads[args[0]] = target
                        }
                    }
                }
                "alt1", "alt2", "alt3" -> {
                    // Preserves pendingSource and pendingDest
                }
                "ldw", "ldb" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) ->
                        explicitReads[tok] = key
                        memoryAddressKeys.add(key)
                    }
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "stw", "stb" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) ->
                        explicitReads[tok] = key
                        memoryAddressKeys.add(key)
                    }
                    useSource()
                    pendingSource = null
                    pendingDest = null
                }
                "lmult" -> {
                    useSource()
                    useDest()
                    implicitReads.add(r6Key)
                    implicitWrites.add(r4Key)
                    pendingSource = null
                    pendingDest = null
                }
                "fmult" -> {
                    useSource()
                    useDest()
                    implicitReads.add(r6Key)
                    pendingSource = null
                    pendingDest = null
                }
                "mult", "umult", "add", "adc", "sub", "sbc", "and", "bic", "or", "xor" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                    useSource()
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "cmp" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                    useSource()
                    pendingSource = null
                    pendingDest = null
                }
                "not", "div2", "lsr", "asr", "rol", "ror", "sex", "swap", "hib", "lob" -> {
                    useSource()
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "merge" -> {
                    implicitReads.add(r7Key)
                    implicitReads.add(r8Key)
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "plot" -> {
                    implicitReads.add(r1Key)
                    implicitReads.add(r2Key)
                    pendingSource = null
                    pendingDest = null
                }
                "rpix" -> {
                    implicitReads.add(r1Key)
                    implicitReads.add(r2Key)
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "getb", "getbs" -> {
                    implicitReads.add(r14Key)
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "getbh", "getbl" -> {
                    implicitReads.add(r14Key)
                    useSource()
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "getc" -> {
                    implicitReads.add(r14Key)
                    pendingSource = null
                    pendingDest = null
                }
                "ramb", "romb", "color", "cmode" -> {
                    useSource()
                    pendingSource = null
                    pendingDest = null
                }
                "loop" -> {
                    implicitReads.add(r13Key)
                    implicitReads.add(r12Key)
                    implicitWrites.add(r12Key)
                    pendingSource = null
                    pendingDest = null
                }
                "link" -> {
                    implicitWrites.add(r11Key)
                    pendingSource = null
                    pendingDest = null
                }
                "move", "moves", "movew", "moveb" -> {
                    val regArgs = mutableListOf<Pair<Token, IntervalKey>>()
                    for (idx in args.indices) {
                        if (isInsideImmediateOrNamespace(args, idx)) continue
                        val k = resolveKey(args[idx], declaredRegisters)
                        if (k != null) {
                            regArgs.add(Pair(args[idx], k))
                            if ((mnemonic == "moveb" || mnemonic == "movew") && idx > 0 && args[idx - 1].text() == "(") {
                                memoryAddressKeys.add(k)
                            }
                        }
                    }
                    if (regArgs.size >= 2) {
                        explicitWrites[regArgs[0].first] = regArgs[0].second
                        explicitReads[regArgs[1].first] = regArgs[1].second
                    } else if (regArgs.size == 1) {
                        explicitWrites[regArgs[0].first] = regArgs[0].second
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "iwt", "ibt", "lm", "lms", "lea" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitWrites[tok] = key }
                    pendingSource = null
                    pendingDest = null
                }
                "inc", "dec", "shl", "fixed88_negate" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) ->
                        explicitReads[tok] = key
                        explicitWrites[tok] = key
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "sm", "sms" -> {
                    // sm (addr), reg -> reg is typically the last register token
                    val regArgs =
                        args.mapIndexedNotNull { idx, tok ->
                            if (isInsideImmediateOrNamespace(args, idx)) {
                                null
                            } else {
                                resolveKey(tok, declaredRegisters)?.let { Pair(tok, it) }
                            }
                        }
                    regArgs.lastOrNull()?.let { (tok, key) -> explicitReads[tok] = key }
                    pendingSource = null
                    pendingDest = null
                }
                "condensed_lmult" -> {
                    val regArgs =
                        args.mapIndexedNotNull { idx, tok ->
                            if (isInsideImmediateOrNamespace(args, idx)) null else resolveKey(tok, declaredRegisters)?.let { Pair(tok, it) }
                        }
                    if (regArgs.size >= 2) {
                        explicitReads[regArgs[0].first] = regArgs[0].second
                        explicitWrites[regArgs[1].first] = regArgs[1].second
                    } else if (regArgs.size == 1) {
                        explicitReads[regArgs[0].first] = regArgs[0].second
                        implicitWrites.add(r0Key)
                    }
                    implicitReads.add(r6Key)
                    implicitWrites.add(r4Key)
                    pendingSource = null
                    pendingDest = null
                }
                "sin_lookup_rom", "cos_lookup_rom", "reciprocal_lookup_rom" -> {
                    val regArgs =
                        args.mapIndexedNotNull { idx, tok ->
                            if (isInsideImmediateOrNamespace(args, idx)) null else resolveKey(tok, declaredRegisters)?.let { Pair(tok, it) }
                        }
                    if (regArgs.size >= 2) {
                        explicitReads[regArgs[0].first] = regArgs[0].second
                        explicitWrites[regArgs[1].first] = regArgs[1].second
                    }
                    implicitReads.add(r14Key)
                    implicitWrites.add(r14Key)
                    if (mnemonic == "cos_lookup_rom") {
                        implicitWrites.add(r0Key)
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "_romreadbyte", "_romreadword", "_romreadwordle" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitWrites[tok] = key }
                    implicitReads.add(r14Key)
                    implicitWrites.add(r14Key)
                    pendingSource = null
                    pendingDest = null
                }
                "gsu_stack_push" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                    pendingSource = null
                    pendingDest = null
                }
                "gsu_stack_pop" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitWrites[tok] = key }
                    pendingSource = null
                    pendingDest = null
                }
                "gsu_stack_peek" -> {
                    val regArgs =
                        args.mapIndexedNotNull { idx, tok ->
                            if (isInsideImmediateOrNamespace(args, idx)) null else resolveKey(tok, declaredRegisters)?.let { Pair(tok, it) }
                        }
                    if (regArgs.isNotEmpty()) {
                        explicitWrites[regArgs[0].first] = regArgs[0].second
                    }
                    if (args.any { it.text() == "," }) {
                        implicitWrites.add(r0Key)
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "for" -> {
                    implicitWrites.add(r12Key)
                    implicitWrites.add(r13Key)
                    pendingSource = null
                    pendingDest = null
                }
                "forr" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                    implicitWrites.add(r12Key)
                    implicitWrites.add(r13Key)
                    pendingSource = null
                    pendingDest = null
                }
                "endfor", "restoreloop", "break" -> {
                    implicitReads.add(r12Key)
                    implicitReads.add(r13Key)
                    implicitWrites.add(r12Key)
                    implicitWrites.add(r13Key)
                    pendingSource = null
                    pendingDest = null
                }
                "if_lt", "if_gt", "if_gte", "if_eq", "if_ne", "if_lte" -> {
                    implicitWrites.add(r0Key)
                    var afterMove = false
                    var moveWriteDone = false
                    for (idx in args.indices) {
                        if (isInsideImmediateOrNamespace(args, idx)) continue
                        val tokText = args[idx].text().lowercase(Locale.ROOT)
                        if (tokText == "move" || tokText == "moves") {
                            afterMove = true
                            continue
                        }
                        val key = resolveKey(args[idx], declaredRegisters)
                        if (key != null) {
                            if (afterMove && !moveWriteDone) {
                                explicitWrites[args[idx]] = key
                                moveWriteDone = true
                            } else {
                                explicitReads[args[idx]] = key
                            }
                        }
                    }
                    pendingSource = null
                    pendingDest = null
                }
                else -> {
                    for (idx in args.indices) {
                        if (isInsideImmediateOrNamespace(args, idx)) continue
                        val key = resolveKey(args[idx], declaredRegisters)
                        if (key != null) {
                            explicitReads[args[idx]] = key
                        }
                    }
                    pendingSource = null
                    pendingDest = null
                }
            }

            result[lineIdx] =
                LineEffect(
                    lineIdx = lineIdx,
                    instructionToken = insnTok,
                    explicitReads = explicitReads,
                    explicitWrites = explicitWrites,
                    implicitReads = implicitReads,
                    implicitWrites = implicitWrites,
                    memoryAddressKeys = memoryAddressKeys,
                )
        }

        // Ensure function parameters have an initial write at fnStartLine even if fnStartLine wasn't in effects
        if (params.isNotEmpty() && fnStartLine in scopeLines && fnStartLine !in result) {
            val impWrites = params.mapNotNull { resolveKeyByName(it, declaredRegisters) }
            result[fnStartLine] =
                LineEffect(
                    lineIdx = fnStartLine,
                    instructionToken = null,
                    implicitWrites = impWrites,
                )
        }

        return result
    }

    private fun detectLoops(
        file: TokenizedFile,
        scopeLines: Set<Int>,
    ): List<IntRange> {
        val loops = mutableListOf<IntRange>()
        val forStack = ArrayDeque<Int>()
        val namedLabels = mutableMapOf<String, Int>()
        val anonLabels = mutableListOf<Int>()
        var lastR13Write: Int? = null
        var lastR13Label: String? = null

        for (lineIdx in scopeLines.sorted()) {
            val codeTokens = getCodeTokens(file, lineIdx)
            if (codeTokens.isEmpty()) continue

            val (labelPrefix, bodyTokens) = splitLabelPrefix(codeTokens)
            if (labelPrefix != null) {
                val lbl = labelPrefix.removeSuffix(":").trim()
                if (lbl.isEmpty()) {
                    anonLabels.add(lineIdx)
                } else {
                    namedLabels[lbl.lowercase(Locale.ROOT)] = lineIdx
                }
            }
            if (bodyTokens.isEmpty()) continue

            val mnemonic = bodyTokens[0].text().lowercase(Locale.ROOT)
            val args = bodyTokens.subList(1, bodyTokens.size)

            if (mnemonic == "for" || mnemonic == "forr") {
                forStack.addLast(lineIdx)
            } else if (mnemonic == "endfor") {
                if (forStack.isNotEmpty()) {
                    val start = forStack.removeLast()
                    loops.add(start..lineIdx)
                }
            } else if ((mnemonic == "iwt" || mnemonic == "move") && args.isNotEmpty() && args[0].text().equals("r13", ignoreCase = true)) {
                lastR13Write = lineIdx
                lastR13Label =
                    if (mnemonic == "iwt" && args.size >= 2) {
                        args
                            .last()
                            .text()
                            .removePrefix("#")
                            .trim()
                            .lowercase(Locale.ROOT)
                    } else {
                        null
                    }
            } else if (mnemonic == "loop") {
                val loopStart = (lastR13Label?.let { namedLabels[it] }) ?: lastR13Write
                if (loopStart != null && loopStart < lineIdx) {
                    loops.add(loopStart..lineIdx)
                }
            } else if (mnemonic in branchMnemonics && args.isNotEmpty()) {
                val targetText = args.joinToString("") { it.text() }.trim()
                if (targetText.startsWith(":-")) {
                    val minusCount = targetText.drop(1).count { it == '-' }
                    val targetIdx = anonLabels.size - minusCount
                    if (targetIdx >= 0 && targetIdx < anonLabels.size) {
                        val targetLine = anonLabels[targetIdx]
                        if (targetLine < lineIdx) {
                            loops.add(targetLine..lineIdx)
                        }
                    }
                } else {
                    val targetLine = namedLabels[targetText.lowercase(Locale.ROOT)]
                    if (targetLine != null && targetLine < lineIdx) {
                        loops.add(targetLine..lineIdx)
                    }
                }
            } else {
                // Check for embedded branch inside conditional macros like `if_lt a, b, { bra target }`
                for (tokIdx in 1 until bodyTokens.size - 1) {
                    if (bodyTokens[tokIdx].text().lowercase(Locale.ROOT) in branchMnemonics) {
                        val targetName =
                            bodyTokens[tokIdx + 1]
                                .text()
                                .removeSuffix("}")
                                .trim()
                                .lowercase(Locale.ROOT)
                        val targetLine = namedLabels[targetName]
                        if (targetLine != null && targetLine < lineIdx) {
                            loops.add(targetLine..lineIdx)
                        }
                    }
                }
            }
        }
        return loops
    }

    private fun buildIntervals(
        scopeLines: Set<Int>,
        effects: Map<Int, LineEffect>,
        declaredRegisters: Map<String, DeclaredRegister>,
        loops: List<IntRange>,
    ): Map<IntervalKey, Interval> {
        val intervalsMap = mutableMapOf<IntervalKey, Interval>()

        fun addRead(
            key: IntervalKey,
            lineIdx: Int,
        ) {
            intervalsMap.getOrPut(key) { Interval(key) }.addRead(lineIdx)
            if (key is IntervalKey.LabelKey) {
                val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                if (forced != null) {
                    val regKey = IntervalKey.RegisterKey(forced)
                    intervalsMap.getOrPut(regKey) { Interval(regKey) }.addRead(lineIdx)
                }
            }
        }

        fun addWrite(
            key: IntervalKey,
            lineIdx: Int,
        ) {
            intervalsMap.getOrPut(key) { Interval(key) }.addWrite(lineIdx)
            if (key is IntervalKey.LabelKey) {
                val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                if (forced != null) {
                    val regKey = IntervalKey.RegisterKey(forced)
                    intervalsMap.getOrPut(regKey) { Interval(regKey) }.addWrite(lineIdx)
                }
            }
        }

        for ((lineIdx, effect) in effects) {
            effect.explicitReads.values.forEach { addRead(it, lineIdx) }
            effect.explicitWrites.values.forEach { addWrite(it, lineIdx) }
            effect.implicitReads.forEach { addRead(it, lineIdx) }
            effect.implicitWrites.forEach { addWrite(it, lineIdx) }
        }

        // Ensure every declared register has an interval entry (even if only declared)
        for ((_, decl) in declaredRegisters) {
            val key = IntervalKey.LabelKey(decl.name)
            val intv = intervalsMap.getOrPut(key) { Interval(key) }
            if (!intv.used() && decl.line in scopeLines) {
                addWrite(key, decl.line)
            }
        }

        // Extend intervals across loops (fixed-point iteration for nested loops)
        var changed = true
        while (changed) {
            changed = false
            for (loop in loops) {
                val loopStart = loop.first
                val loopEnd = loop.last
                for ((key, intv) in intervalsMap) {
                    if (!intv.used()) continue
                    if (key == IntervalKey.RegisterKey(Constants.Register.R0)) continue

                    val firstReadInLoop = intv.reads.filter { it in loopStart..loopEnd }.minOrNull()
                    val firstWriteInLoop = intv.writes.filter { it in loopStart..loopEnd }.minOrNull()

                    val loopCarried =
                        firstReadInLoop != null && (firstWriteInLoop == null || firstReadInLoop <= firstWriteInLoop)
                    val liveEntering =
                        intv.start < loopStart &&
                            intv.end >= loopStart &&
                            (loopCarried || intv.reads.any { it > loopEnd })
                    val liveExiting = intv.start <= loopEnd && intv.end > loopEnd && intv.reads.any { it > loopEnd }

                    if (liveEntering || liveExiting || loopCarried) {
                        val newStart = minOf(intv.start, loopStart)
                        val newEnd = maxOf(intv.end, loopEnd)
                        if (newStart < intv.start || newEnd > intv.end) {
                            intv.start = newStart
                            intv.end = newEnd
                            changed = true
                        }
                    }
                }
            }
        }

        // Propagate extended LabelKey intervals to precolored RegisterKey intervals
        for ((_, decl) in declaredRegisters) {
            if (decl.forcedRegister != null) {
                val labelIntv = intervalsMap[IntervalKey.LabelKey(decl.name)]
                if (labelIntv != null && labelIntv.used()) {
                    val regKey = IntervalKey.RegisterKey(decl.forcedRegister)
                    val regIntv = intervalsMap.getOrPut(regKey) { Interval(regKey) }
                    regIntv.start = minOf(regIntv.start, labelIntv.start)
                    regIntv.end = maxOf(regIntv.end, labelIntv.end)
                }
            }
        }

        return intervalsMap
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
            // Overwritten before next read after callLine
            return false
        }
        if (nextRead != null) {
            return true
        }

        // No textual read or write after callLine: check loop back-edges enclosing callLine
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

    private fun computeCallerSaveAvoidance(
        file: TokenizedFile,
        scopeLines: Set<Int>,
        declaredRegisters: Map<String, DeclaredRegister>,
        intervals: Map<IntervalKey, Interval>,
        loops: List<IntRange>,
        allocatedFunctions: Map<String, AllocatedFunctionInfo>,
    ): Map<IntervalKey, Set<Constants.Register>> {
        val avoid = mutableMapOf<IntervalKey, MutableSet<Constants.Register>>()
        for (lineIdx in scopeLines.sorted()) {
            val (_, bodyTokens) = splitLabelPrefix(getCodeTokens(file, lineIdx))
            if (!FunctionSyntaxHelper.isCall(bodyTokens)) continue
            val call = parseCallStatement(bodyTokens) ?: continue
            val callee = allocatedFunctions[call.target.lowercase(Locale.ROOT)] ?: continue
            val destKey = call.dest?.let { resolveKeyByName(it, declaredRegisters) }

            for ((key, intv) in intervals) {
                if (key !is IntervalKey.LabelKey) continue
                if (key == destKey) continue
                if (isLiveAfterCall(intv, lineIdx, loops)) {
                    avoid.getOrPut(key) { mutableSetOf() }.addAll(callee.clobberedRegisters)
                }
            }
        }
        return avoid
    }

    private fun buildPrecoloredMap(declaredRegisters: Map<String, DeclaredRegister>): Map<IntervalKey, Constants.Register> {
        val precolored = mutableMapOf<IntervalKey, Constants.Register>()
        for ((_, decl) in declaredRegisters) {
            if (decl.forcedRegister != null) {
                precolored[IntervalKey.LabelKey(decl.name)] = decl.forcedRegister
            }
        }
        return precolored
    }

    private fun allocateGraph(
        intervals: Map<IntervalKey, Interval>,
        precolored: Map<IntervalKey, Constants.Register>,
        memoryKeys: Set<IntervalKey>,
        avoidMap: Map<IntervalKey, Set<Constants.Register>>,
    ): Map<IntervalKey, AllocationResult> {
        val activeIntervals = intervals.filterValues { it.used() }
        val edges = mutableMapOf<IntervalKey, MutableSet<IntervalKey>>()
        for (node in activeIntervals.keys) {
            edges[node] = mutableSetOf()
        }

        val keys = activeIntervals.keys.toList()
        for (i in keys.indices) {
            val u = keys[i]
            val intU = activeIntervals[u] ?: continue
            for (j in i + 1 until keys.size) {
                val v = keys[j]
                val intV = activeIntervals[v] ?: continue
                if (intU.start <= intV.end && intV.start <= intU.end) {
                    edges[u]?.add(v)
                    edges[v]?.add(u)
                }
            }
        }

        val colors = mutableMapOf<IntervalKey, Constants.Register>()
        for ((node, reg) in precolored) {
            if (node in activeIntervals) {
                colors[node] = reg
            }
        }
        for (node in activeIntervals.keys) {
            if (node is IntervalKey.RegisterKey) {
                colors[node] = node.register
            }
        }

        val uncoloredNodes = activeIntervals.keys.filter { it !in colors }.toMutableSet()
        val currentEdges = mutableMapOf<IntervalKey, MutableSet<IntervalKey>>()
        for (node in activeIntervals.keys) {
            currentEdges[node] = (edges[node] ?: emptySet()).toMutableSet()
        }

        val stack = ArrayDeque<IntervalKey>()

        fun effectiveDegree(node: IntervalKey): Pair<Int, Int> {
            val pool = if (node in memoryKeys) primaryPool else extendedPool
            val neighbors = currentEdges[node] ?: emptySet()
            val precoloredCount =
                neighbors
                    .mapNotNull { colors[it] }
                    .filter { it in pool }
                    .toSet()
                    .size
            val uncoloredCount = neighbors.count { it in uncoloredNodes }
            return Pair(precoloredCount + uncoloredCount, pool.size)
        }

        while (uncoloredNodes.isNotEmpty()) {
            val candidate =
                uncoloredNodes.firstOrNull { node ->
                    val (deg, k) = effectiveDegree(node)
                    deg < k
                }
            val chosen =
                candidate
                    ?: uncoloredNodes.maxByOrNull { node ->
                        val (deg, k) = effectiveDegree(node)
                        deg - k
                    }!!
            uncoloredNodes.remove(chosen)
            stack.addLast(chosen)
            for (neighbor in currentEdges[chosen] ?: emptySet()) {
                currentEdges[neighbor]?.remove(chosen)
            }
        }

        val result = mutableMapOf<IntervalKey, AllocationResult>()
        for ((node, reg) in colors) {
            result[node] = AllocationResult.Register(reg)
        }

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val usedColors = mutableSetOf<Constants.Register>()
            for (neighbor in edges[node] ?: emptySet()) {
                colors[neighbor]?.let { usedColors.add(it) }
            }

            val pool = if (node in memoryKeys) primaryPool else extendedPool
            val avoid = avoidMap[node] ?: emptySet()

            // First prefer registers not clobbered by calls across which `node` is live
            val preferred = pool.firstOrNull { it !in usedColors && it !in avoid }
            val chosen = preferred ?: pool.firstOrNull { it !in usedColors }

            if (chosen != null) {
                colors[node] = chosen
                result[node] = AllocationResult.Register(chosen)
            } else {
                result[node] = AllocationResult.Spill
            }
        }

        return result
    }

    private fun resolveKeyToRegister(
        key: IntervalKey,
        varToReg: Map<String, Constants.Register>,
    ): Constants.Register? =
        when (key) {
            is IntervalKey.RegisterKey -> key.register
            is IntervalKey.LabelKey -> varToReg[key.label.lowercase(Locale.ROOT)]
        }

    private fun resolveNameToRegister(
        name: String,
        varToReg: Map<String, Constants.Register>,
    ): Constants.Register? {
        val lower = name.lowercase(Locale.ROOT)
        return Constants.register(lower) ?: varToReg[lower]
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
        enclosingFunction: FunctionScope?,
        enclosingFunctionInfo: AllocatedFunctionInfo?,
    ) {
        val moveResolver = ParallelMoveResolver()

        for (lineIdx in scopeLines.sorted()) {
            val rawLine = rawLines[lineIdx]
            val codeTokens = getCodeTokens(tokenizedFile, lineIdx)
            if (codeTokens.isEmpty()) continue

            val (labelPrefix, bodyTokens) = splitLabelPrefix(codeTokens)
            val indent = rawLine.takeWhile { it == ' ' || it == '\t' }
            val labelEmitPrefix = if (labelPrefix != null) "$labelPrefix " else ""

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
                val call = parseCallStatement(bodyTokens)
                if (call != null) {
                    val calleeInfo = allocatedFunctions[call.target.lowercase(Locale.ROOT)]
                    val out = mutableListOf<String>()
                    if (labelPrefix != null) out.add("$indent$labelPrefix")

                    val destReg = call.dest?.let { resolveNameToRegister(it, varToReg) }
                    val destKey = call.dest?.let { resolveKeyByName(it, declaredRegisters) }

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
                if (isInsideImmediateOrNamespace(bodyTokens, idx)) continue
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
