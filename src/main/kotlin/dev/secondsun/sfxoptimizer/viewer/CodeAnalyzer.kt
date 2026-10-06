package dev.secondsun.sfxoptimizer.viewer

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.FileService
import dev.secondsun.retro.util.SymbolService
import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenAttribute
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.AllocationResult
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.GraphColoringAllocator
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey
import dev.secondsun.sfxoptimizer.collectFunctionIntervals
import dev.secondsun.sfxoptimizer.collectMainIntervals
import dev.secondsun.sfxoptimizer.graphbuilder.CA65Grapher
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
import dev.secondsun.sfxoptimizer.graphnode.CodeNode
import java.net.URI
import java.util.Locale

class CodeAnalyzer {
    private val superFxMnemonics =
        setOf(
            "add",
            "adc",
            "alt1",
            "alt2",
            "alt3",
            "and",
            "asr",
            "bcc",
            "bcs",
            "beq",
            "bge",
            "bic",
            "blt",
            "bmi",
            "bne",
            "bpl",
            "bra",
            "bvc",
            "bvs",
            "cache",
            "cmode",
            "cmp",
            "color",
            "dec",
            "div2",
            "fmult",
            "from",
            "getb",
            "getbh",
            "getbl",
            "getbs",
            "getc",
            "hib",
            "ibt",
            "inc",
            "iwt",
            "jal",
            "jmp",
            "ldb",
            "ldw",
            "lea",
            "link",
            "ljmp",
            "lm",
            "lms",
            "lmult",
            "lob",
            "loop",
            "lsr",
            "merge",
            "move",
            "moveb",
            "moves",
            "movew",
            "mult",
            "nop",
            "not",
            "or",
            "plot",
            "pop",
            "push",
            "ramb",
            "ret",
            "rol",
            "romb",
            "ror",
            "rpix",
            "sbc",
            "sbk",
            "sex",
            "sm",
            "sms",
            "stb",
            "stop",
            "stw",
            "sub",
            "swap",
            "to",
            "umult",
            "with",
            "xor",
        )

    private val pseudoKeywords =
        setOf(
            "function",
            "endfunction",
            "call",
            "register",
            "for",
            "forr",
            "endfor",
            "return",
            "stop",
            "to",
            "from",
            "with",
            "export",
            "import",
            "global",
            "byte",
            "word",
            "res",
            "org",
            "segment",
            "scope",
            "proc",
            "endproc",
        )

    data class FunctionBoundary(
        val name: String,
        val startLine: Int,
        val endLine: Int,
        val params: List<String> = emptyList(),
    )

    data class DeclaredRegister(
        val name: String,
        val forcedRegister: Constants.Register? = null,
        val line: Int,
    )

    private fun extractDeclaredRegisters(
        file: TokenizedFile,
        startLine: Int,
        endLine: Int,
        functionParams: List<String> = emptyList(),
    ): Map<String, DeclaredRegister> {
        val result = mutableMapOf<String, DeclaredRegister>()

        for (param in functionParams) {
            val lower = param.lowercase(Locale.ROOT)
            result[lower] = DeclaredRegister(name = param, forcedRegister = null, line = startLine)
        }

        for (lineIdx in startLine..endLine) {
            val tokens = file.getLineTokens(lineIdx) ?: continue
            if (tokens.isEmpty()) continue

            val firstText = tokens[0].text().lowercase(Locale.ROOT)
            if (firstText == "register" || tokens[0].type == TokenType.TOK_REGISTER_KEYWORD) {
                var i = 1
                while (i < tokens.size) {
                    val tok = tokens[i]
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
                    if (i < tokens.size && (tokens[i].text() == "=" || tokens[i].type == TokenType.TOK_EQ)) {
                        i++
                        if (i < tokens.size) {
                            val regTok = tokens[i]
                            forcedReg =
                                Constants.register(regTok.text())
                                    ?: Constants.Register.entries.find { it.name.equals(regTok.text(), ignoreCase = true) }
                            i++
                        }
                    }
                    result[varLower] = DeclaredRegister(name = varName, forcedRegister = forcedReg, line = lineIdx)
                }
            }
        }
        return result
    }

    fun analyze(
        source: String,
        fileName: String = "source.sgs",
    ): FileAnalysisResultDto {
        val scanner = CA65Scanner()
        val tokenizedFile = scanner.tokenize(source)
        tokenizedFile.uri = URI.create("./$fileName")

        val symbolService = SymbolService()
        symbolService.extractDefinitions(tokenizedFile)

        val fileService =
            object : FileService() {
                override fun readLines(fileUri: URI?): TokenizedFile = tokenizedFile

                override fun find(
                    file: URI?,
                    vararg optionalSearchPaths: URI?,
                ): MutableList<URI> = mutableListOf(tokenizedFile.uri)
            }

        val rawLines = source.lines()
        val totalLines = rawLines.size

        // 1. Discover all function boundaries in the file
        val functionBoundaries = findFunctionBoundaries(tokenizedFile, rawLines)

        val allocator = GraphColoringAllocator()
        val functionScopeDtos = mutableListOf<FunctionScopeDto>()
        val allBlockDtos = mutableListOf<CodeBlockDto>()
        val functionDeclaredMaps = mutableMapOf<String, Map<String, DeclaredRegister>>()
        var blockIdx = 0

        // 2. Process each function independently (functions create strict allocation boundaries)
        for (boundary in functionBoundaries) {
            val fnGrapher = CA65Grapher(symbolService = symbolService, fileService = fileService)
            val fnNode: CodeNode.FunctionStart? =
                try {
                    fnGrapher.graphFunction(boundary.name)
                } catch (e: Exception) {
                    null
                }

            val fnIntervalsMap = mutableMapOf<IntervalKey, Interval>()
            val graphLines = mutableSetOf<Int>()
            if (fnNode != null) {
                val intervals = collectFunctionIntervals(fnNode)
                for ((k, v) in intervals) {
                    fnIntervalsMap[k] = v
                }
                fnNode.functionBody.traverse { node ->
                    if (node is CodeNode.CodeBlock) {
                        node.lines.forEach { lineTokens ->
                            lineTokens.tokens.forEach { tok ->
                                graphLines.add(tok.lineNumber)
                            }
                        }
                    }
                }
            }

            val fnStart = fnNode?.location?.line ?: boundary.startLine
            val fnEnd = maxOf(if (graphLines.isNotEmpty()) graphLines.max() else boundary.endLine, boundary.endLine)

            val fnDeclaredRegisters = extractDeclaredRegisters(tokenizedFile, fnStart, fnEnd, boundary.params)
            functionDeclaredMaps[boundary.name] = fnDeclaredRegisters

            // Supplement with token scans strictly within function boundaries
            scanFunctionLinesForRegisters(
                tokenizedFile,
                fnStart,
                fnEnd,
                fnIntervalsMap,
                fnDeclaredRegisters,
            )

            // Retain ONLY physical registers and declared register labels
            fnIntervalsMap.keys.retainAll { key ->
                when (key) {
                    is IntervalKey.RegisterKey -> true
                    is IntervalKey.LabelKey -> fnDeclaredRegisters.containsKey(key.label.lowercase(Locale.ROOT))
                }
            }

            val fnPrecolored = mutableMapOf<IntervalKey, Constants.Register>()
            for ((_, declared) in fnDeclaredRegisters) {
                if (declared.forcedRegister != null) {
                    fnPrecolored[IntervalKey.LabelKey(declared.name)] = declared.forcedRegister
                }
            }

            // Run graph coloring register allocator for this function
            val fnInterference = allocator.buildInterferenceGraph(fnIntervalsMap, fnPrecolored)
            val fnAllocations = allocator.allocate(fnInterference)

            val fnAllocatedMap = mutableMapOf<String, String>()
            for ((key, res) in fnAllocations) {
                val name = keyToString(key)
                when (res) {
                    is AllocationResult.Register -> fnAllocatedMap[name] = res.register.name
                    is AllocationResult.Spill -> fnAllocatedMap[name] = "SPILL"
                }
            }

            // Build DTOs for this function
            val fnIntervalDtos =
                fnIntervalsMap.values
                    .filter { it.used() }
                    .map { interval ->
                        createIntervalDto(
                            interval = interval,
                            functionScope = boundary.name,
                            tokenizedFile = tokenizedFile,
                            allocatedMap = fnAllocatedMap,
                            interference = fnInterference.neighbors(interval.key).map { keyToString(it) }.sorted(),
                        )
                    }.sortedWith(compareBy({ !it.isRegister }, { it.startLine }, { it.keyName }))

            // Collect basic blocks for this function
            if (fnNode != null) {
                fnNode.functionBody.traverse { node ->
                    if (node is CodeNode.CodeBlock) {
                        blockIdx++
                        allBlockDtos.add(
                            createBlockDto(node, "block-$blockIdx", boundary.name, tokenizedFile, fnAllocatedMap, fnInterference),
                        )
                    }
                }
            }

            functionScopeDtos.add(
                FunctionScopeDto(
                    name = boundary.name,
                    startLine = fnStart,
                    endLine = fnEnd,
                    intervals = fnIntervalDtos,
                    allocatedMap = fnAllocatedMap,
                ),
            )
        }

        // 3. Process main / top-level code outside of functions ONLY if real code outside functions exists
        val linesInFunctions = functionScopeDtos.flatMap { it.startLine..it.endLine }.toSet()
        val nonFunctionCodeLines =
            (0 until totalLines).filter { lineIdx ->
                lineIdx !in linesInFunctions &&
                    rawLines.getOrNull(lineIdx)?.trim()?.let { it.isNotEmpty() && !it.startsWith(";") } == true
            }

        val nonFunctionMin = nonFunctionCodeLines.minOrNull() ?: 0
        val nonFunctionMax = nonFunctionCodeLines.maxOrNull() ?: (totalLines - 1)
        val mainDeclaredRegisters = extractDeclaredRegisters(tokenizedFile, nonFunctionMin, nonFunctionMax)

        // Check if there are actual register uses or declarations in lines outside functions
        val nonFunctionRegisterLines = mutableSetOf<Int>()
        for (lineIdx in nonFunctionCodeLines) {
            val tokens = tokenizedFile.getLineTokens(lineIdx) ?: continue
            for (tok in tokens) {
                val lower = tok.text().lowercase(Locale.ROOT)
                if (Constants.isRegister(lower) || tok.hasAttribute(TokenAttribute.REGISTER_LABEL) || lower == "register" ||
                    mainDeclaredRegisters.containsKey(lower)
                ) {
                    nonFunctionRegisterLines.add(lineIdx)
                }
            }
        }

        if (nonFunctionRegisterLines.isNotEmpty()) {
            val mainStartLine = nonFunctionRegisterLines.min()
            val mainEndLine = nonFunctionRegisterLines.max()
            val mainGrapher = CA65Grapher(symbolService = symbolService, fileService = fileService)
            val mainGraph: CodeGraph? =
                try {
                    mainGrapher.graph(tokenizedFile, mainStartLine)
                } catch (e: Exception) {
                    null
                }

            val mainIntervalsMap = mutableMapOf<IntervalKey, Interval>()
            if (mainGraph != null) {
                val intervals = collectMainIntervals(mainGraph)
                for ((k, v) in intervals) {
                    mainIntervalsMap[k] = v
                }
            }

            // Only scan lines that are strictly outside function scopes
            scanNonFunctionLinesForRegisters(
                tokenizedFile,
                nonFunctionCodeLines.toSet(),
                mainIntervalsMap,
                mainDeclaredRegisters,
            )

            mainIntervalsMap.keys.retainAll { key ->
                when (key) {
                    is IntervalKey.RegisterKey -> true
                    is IntervalKey.LabelKey -> mainDeclaredRegisters.containsKey(key.label.lowercase(Locale.ROOT))
                }
            }

            val mainPrecolored = mutableMapOf<IntervalKey, Constants.Register>()
            for ((_, declared) in mainDeclaredRegisters) {
                if (declared.forcedRegister != null) {
                    mainPrecolored[IntervalKey.LabelKey(declared.name)] = declared.forcedRegister
                }
            }

            if (mainIntervalsMap.values.any { it.used() }) {
                val mainInterference = allocator.buildInterferenceGraph(mainIntervalsMap, mainPrecolored)
                val mainAllocations = allocator.allocate(mainInterference)
                val mainAllocatedMap = mutableMapOf<String, String>()
                for ((key, res) in mainAllocations) {
                    val name = keyToString(key)
                    when (res) {
                        is AllocationResult.Register -> mainAllocatedMap[name] = res.register.name
                        is AllocationResult.Spill -> mainAllocatedMap[name] = "SPILL"
                    }
                }

                val mainIntervalDtos =
                    mainIntervalsMap.values
                        .filter { it.used() }
                        .map { interval ->
                            createIntervalDto(
                                interval = interval,
                                functionScope = "(main)",
                                tokenizedFile = tokenizedFile,
                                allocatedMap = mainAllocatedMap,
                                interference = mainInterference.neighbors(interval.key).map { keyToString(it) }.sorted(),
                            )
                        }.sortedWith(compareBy({ !it.isRegister }, { it.startLine }, { it.keyName }))

                if (mainGraph != null) {
                    mainGraph.traverse { node ->
                        if (node is CodeNode.CodeBlock) {
                            blockIdx++
                            allBlockDtos.add(
                                createBlockDto(node, "block-$blockIdx", "(main)", tokenizedFile, mainAllocatedMap, mainInterference),
                            )
                        }
                    }
                }

                val mainScopeDto =
                    FunctionScopeDto(
                        name = "(main)",
                        startLine = mainStartLine,
                        endLine = mainEndLine,
                        intervals = mainIntervalDtos,
                        allocatedMap = mainAllocatedMap,
                    )
                functionScopeDtos.add(0, mainScopeDto)
            }
        }

        // 4. Combined interval list across all scopes
        val allIntervalDtos = functionScopeDtos.flatMap { it.intervals }
        val overallAllocatedMap = mutableMapOf<String, String>()
        for (scope in functionScopeDtos) {
            for ((k, v) in scope.allocatedMap) {
                overallAllocatedMap[k] = v
            }
        }

        // 5. Line and token DTOs with scope awareness
        val allDeclaredRegisters = mutableMapOf<String, DeclaredRegister>()
        for (m in functionDeclaredMaps.values) {
            allDeclaredRegisters.putAll(m)
        }
        allDeclaredRegisters.putAll(mainDeclaredRegisters)

        val fileLineEffects = analyzeLines(tokenizedFile, 0, totalLines - 1, allDeclaredRegisters)
        val lineDtos =
            (0 until totalLines).map { lineIdx ->
                val lineText = if (lineIdx < rawLines.size) rawLines[lineIdx] else ""
                val tokens = tokenizedFile.getLineTokens(lineIdx) ?: emptyList()

                // Find active scope for this line
                val activeScope =
                    functionScopeDtos.firstOrNull { lineIdx in it.startLine..it.endLine }
                        ?: functionScopeDtos.firstOrNull { it.name == "(main)" }

                val effect = fileLineEffects[lineIdx]
                val tokenDtos =
                    tokens.map { token ->
                        classifyToken(token, lineIdx, activeScope, effect)
                    }

                SourceLineDto(
                    lineNumber = lineIdx,
                    text = lineText,
                    tokens = tokenDtos,
                )
            }

        return FileAnalysisResultDto(
            fileName = fileName,
            totalLines = totalLines,
            lines = lineDtos,
            blocks = allBlockDtos,
            intervals = allIntervalDtos,
            allocatedMap = overallAllocatedMap,
            functions = functionScopeDtos,
        )
    }

    private fun findFunctionBoundaries(
        file: TokenizedFile,
        rawLines: List<String>,
    ): List<FunctionBoundary> {
        val list = mutableListOf<FunctionBoundary>()
        var currentName: String? = null
        var currentStart = 0
        var currentParams = emptyList<String>()

        for (i in 0 until file.textLines()) {
            val tokens = file.getLineTokens(i) ?: emptyList()
            if (tokens.isNotEmpty()) {
                val first = tokens[0].text().trim().lowercase(Locale.ROOT)
                if (first == "function" && tokens.size >= 2) {
                    currentName = tokens[1].text().trim()
                    currentStart = i
                    currentParams =
                        if (tokens.size > 2) {
                            tokens
                                .subList(2, tokens.size)
                                .filter { it.type == TokenType.TOK_IDENT && !Constants.isRegister(it.text()) && it.text() != "," }
                                .map { it.text() }
                        } else {
                            emptyList()
                        }
                } else if (first == "endfunction" && currentName != null) {
                    list.add(FunctionBoundary(currentName, currentStart, i, currentParams))
                    currentName = null
                    currentParams = emptyList()
                }
            } else if (i < rawLines.size) {
                val trimmed = rawLines[i].trim().lowercase(Locale.ROOT)
                if (trimmed.startsWith("function ") && currentName == null) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        currentName = parts[1].trim()
                        currentStart = i
                        currentParams = if (parts.size > 2) parts.subList(2, parts.size) else emptyList()
                    }
                } else if (trimmed.startsWith("endfunction") && currentName != null) {
                    list.add(FunctionBoundary(currentName, currentStart, i, currentParams))
                    currentName = null
                    currentParams = emptyList()
                }
            }
        }
        if (currentName != null) {
            list.add(FunctionBoundary(currentName, currentStart, file.textLines() - 1, currentParams))
        }
        return list
    }

    data class LineEffect(
        val lineIdx: Int,
        val instructionToken: Token?,
        val explicitReads: Map<Token, IntervalKey> = emptyMap(),
        val explicitWrites: Map<Token, IntervalKey> = emptyMap(),
        val implicitReads: List<IntervalKey> = emptyList(),
        val implicitWrites: List<IntervalKey> = emptyList(),
    )

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

    private fun findRegisterInArgs(
        args: List<Token>,
        declaredRegisters: Map<String, DeclaredRegister>,
    ): Pair<Token, IntervalKey>? {
        for (tok in args) {
            val key = resolveKey(tok, declaredRegisters)
            if (key != null) {
                return Pair(tok, key)
            }
        }
        return null
    }

    private fun analyzeLines(
        file: TokenizedFile,
        startLine: Int,
        endLine: Int,
        declaredRegisters: Map<String, DeclaredRegister> = emptyMap(),
    ): Map<Int, LineEffect> {
        val result = mutableMapOf<Int, LineEffect>()
        var pendingSource: IntervalKey? = null
        var pendingDest: IntervalKey? = null

        val r0Key = IntervalKey.RegisterKey(Constants.Register.R0)
        val r1Key = IntervalKey.RegisterKey(Constants.Register.R1)
        val r2Key = IntervalKey.RegisterKey(Constants.Register.R2)
        val r4Key = IntervalKey.RegisterKey(Constants.Register.R4)
        val r6Key = IntervalKey.RegisterKey(Constants.Register.R6)
        val r7Key = IntervalKey.RegisterKey(Constants.Register.R7)
        val r8Key = IntervalKey.RegisterKey(Constants.Register.R8)
        val r11Key = IntervalKey.RegisterKey(Constants.Register.R11)
        val r12Key = IntervalKey.RegisterKey(Constants.Register.R12)
        val r13Key = IntervalKey.RegisterKey(Constants.Register.R13)
        val r14Key = IntervalKey.RegisterKey(Constants.Register.R14)

        for (lineIdx in startLine..endLine) {
            val tokens = file.getLineTokens(lineIdx) ?: continue
            val codeTokens = tokens.filter { !it.text().startsWith(";") }
            if (codeTokens.isEmpty()) continue

            val firstTokText = codeTokens[0].text().lowercase(Locale.ROOT)
            if (firstTokText.endsWith(":") || (codeTokens.size > 1 && codeTokens[1].text() == ":")) {
                pendingSource = null
                pendingDest = null
            }

            if (firstTokText.startsWith(".")) continue

            val mnemonicIdx =
                if (codeTokens.size > 1 && codeTokens[1].text() == ":") {
                    2
                } else if (firstTokText.endsWith(":")) {
                    1
                } else {
                    0
                }
            if (mnemonicIdx >= codeTokens.size) continue

            val insnTok = codeTokens[mnemonicIdx]
            val mnemonic = insnTok.text().lowercase(Locale.ROOT)
            val args = codeTokens.subList(mnemonicIdx + 1, codeTokens.size)

            val explicitReads = mutableMapOf<Token, IntervalKey>()
            val explicitWrites = mutableMapOf<Token, IntervalKey>()
            val implicitReads = mutableListOf<IntervalKey>()
            val implicitWrites = mutableListOf<IntervalKey>()

            fun useSource() {
                if (pendingSource == null) {
                    implicitReads.add(r0Key)
                } else {
                    implicitReads.add(pendingSource!!)
                }
            }

            fun useDest() {
                if (pendingDest == null) {
                    implicitWrites.add(r0Key)
                } else {
                    implicitWrites.add(pendingDest!!)
                }
            }

            when (mnemonic) {
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
                "ldw", "ldb" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "stw", "stb" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
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
                "mult", "umult" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                    useSource()
                    useDest()
                    pendingSource = null
                    pendingDest = null
                }
                "add", "adc", "sub", "sbc", "and", "bic", "or", "xor" -> {
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
                "not", "div2", "lsr", "rol", "ror", "sex", "swap", "hib", "lob" -> {
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
                "move", "moves" -> {
                    val regArgs =
                        args.filter {
                            Constants.isRegister(it.text().lowercase(Locale.ROOT)) ||
                                declaredRegisters.containsKey(it.text().lowercase(Locale.ROOT))
                        }
                    if (regArgs.size >= 2) {
                        resolveKey(regArgs[0], declaredRegisters)?.let { explicitWrites[regArgs[0]] = it }
                        resolveKey(regArgs[1], declaredRegisters)?.let { explicitReads[regArgs[1]] = it }
                    } else if (regArgs.size == 1) {
                        resolveKey(regArgs[0], declaredRegisters)?.let { explicitWrites[regArgs[0]] = it }
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "iwt", "ibt", "lm", "lms" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitWrites[tok] = key }
                    pendingSource = null
                    pendingDest = null
                }
                "inc", "dec" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) ->
                        explicitReads[tok] = key
                        explicitWrites[tok] = key
                    }
                    pendingSource = null
                    pendingDest = null
                }
                "sm", "sms" -> {
                    findRegisterInArgs(args, declaredRegisters)?.let { (tok, key) -> explicitReads[tok] = key }
                    pendingSource = null
                    pendingDest = null
                }
                "condensed_lmult" -> {
                    val regArgs =
                        args.filter {
                            Constants.isRegister(it.text().lowercase(Locale.ROOT)) ||
                                declaredRegisters.containsKey(it.text().lowercase(Locale.ROOT))
                        }
                    if (regArgs.size >= 2) {
                        resolveKey(regArgs[0], declaredRegisters)?.let { explicitReads[regArgs[0]] = it }
                        resolveKey(regArgs[1], declaredRegisters)?.let { explicitWrites[regArgs[1]] = it }
                    } else if (regArgs.size == 1) {
                        resolveKey(regArgs[0], declaredRegisters)?.let { explicitReads[regArgs[0]] = it }
                        implicitWrites.add(r0Key)
                    }
                    implicitReads.add(r6Key)
                    implicitWrites.add(r4Key)
                    pendingSource = null
                    pendingDest = null
                }
                else -> {
                    for (arg in args) {
                        val key = resolveKey(arg, declaredRegisters)
                        if (key != null) {
                            explicitReads[arg] = key
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
                )
        }
        return result
    }

    private fun scanFunctionLinesForRegisters(
        file: TokenizedFile,
        startLine: Int,
        endLine: Int,
        intervalsMap: MutableMap<IntervalKey, Interval>,
        declaredRegisters: Map<String, DeclaredRegister>,
    ) {
        val effects = analyzeLines(file, startLine, endLine, declaredRegisters)
        for ((lineIdx, effect) in effects) {
            effect.explicitReads.values.forEach { key ->
                intervalsMap.getOrPut(key) { Interval(key) }.addRead(lineIdx)
                if (key is IntervalKey.LabelKey) {
                    val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                    if (forced != null) {
                        val regKey = IntervalKey.RegisterKey(forced)
                        intervalsMap.getOrPut(regKey) { Interval(regKey) }.addRead(lineIdx)
                    }
                }
            }
            effect.explicitWrites.values.forEach { key ->
                intervalsMap.getOrPut(key) { Interval(key) }.addWrite(lineIdx)
                if (key is IntervalKey.LabelKey) {
                    val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                    if (forced != null) {
                        val regKey = IntervalKey.RegisterKey(forced)
                        intervalsMap.getOrPut(regKey) { Interval(regKey) }.addWrite(lineIdx)
                    }
                }
            }
            effect.implicitReads.forEach { key ->
                intervalsMap.getOrPut(key) { Interval(key) }.addRead(lineIdx)
            }
            effect.implicitWrites.forEach { key ->
                intervalsMap.getOrPut(key) { Interval(key) }.addWrite(lineIdx)
            }
        }
    }

    private fun scanNonFunctionLinesForRegisters(
        file: TokenizedFile,
        nonFunctionLines: Set<Int>,
        intervalsMap: MutableMap<IntervalKey, Interval>,
        declaredRegisters: Map<String, DeclaredRegister>,
    ) {
        if (nonFunctionLines.isEmpty()) return
        val minLine = nonFunctionLines.min()
        val maxLine = nonFunctionLines.max()
        val effects = analyzeLines(file, minLine, maxLine, declaredRegisters)
        for (lineIdx in nonFunctionLines) {
            val effect = effects[lineIdx] ?: continue
            effect.explicitReads.values.forEach { key ->
                intervalsMap.getOrPut(key) { Interval(key) }.addRead(lineIdx)
                if (key is IntervalKey.LabelKey) {
                    val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                    if (forced != null) {
                        val regKey = IntervalKey.RegisterKey(forced)
                        intervalsMap.getOrPut(regKey) { Interval(regKey) }.addRead(lineIdx)
                    }
                }
            }
            effect.explicitWrites.values.forEach { key ->
                intervalsMap.getOrPut(key) { Interval(key) }.addWrite(lineIdx)
                if (key is IntervalKey.LabelKey) {
                    val forced = declaredRegisters[key.label.lowercase(Locale.ROOT)]?.forcedRegister
                    if (forced != null) {
                        val regKey = IntervalKey.RegisterKey(forced)
                        intervalsMap.getOrPut(regKey) { Interval(regKey) }.addWrite(lineIdx)
                    }
                }
            }
            effect.implicitReads.forEach { key ->
                intervalsMap.getOrPut(key) { Interval(key) }.addRead(lineIdx)
            }
            effect.implicitWrites.forEach { key ->
                intervalsMap.getOrPut(key) { Interval(key) }.addWrite(lineIdx)
            }
        }
    }

    private fun createIntervalDto(
        interval: Interval,
        functionScope: String,
        tokenizedFile: TokenizedFile,
        allocatedMap: Map<String, String>,
        interference: List<String>,
    ): IntervalSpanDto {
        val keyName = keyToString(interval.key)
        val isReg = interval.key is IntervalKey.RegisterKey
        val regName = (interval.key as? IntervalKey.RegisterKey)?.register?.name
        val allocReg = if (isReg) regName else allocatedMap[keyName]

        val readsLoc =
            interval.reads.mapNotNull { lineNum ->
                findTokenLocationOnLine(tokenizedFile, lineNum, keyName, isReg)
            }
        val writesLoc =
            interval.writes.mapNotNull { lineNum ->
                findTokenLocationOnLine(tokenizedFile, lineNum, keyName, isReg)
            }

        return IntervalSpanDto(
            keyName = keyName,
            functionScope = functionScope,
            isRegister = isReg,
            registerName = regName,
            startLine = interval.start,
            endLine = interval.end,
            reads = readsLoc,
            writes = writesLoc,
            interferences = interference,
            allocatedRegister = allocReg,
            isSpill = allocReg == "SPILL",
        )
    }

    private fun createBlockDto(
        node: CodeNode.CodeBlock,
        id: String,
        functionScope: String,
        tokenizedFile: TokenizedFile,
        allocatedMap: Map<String, String>,
        interference: dev.secondsun.sfxoptimizer.InterferenceGraph,
    ): CodeBlockDto {
        val bStart =
            node.lines
                .firstOrNull()
                ?.tokens
                ?.firstOrNull()
                ?.lineNumber ?: 0
        val bEnd =
            node.lines
                .lastOrNull()
                ?.tokens
                ?.lastOrNull()
                ?.lineNumber ?: bStart
        val bIntervals =
            node.intervals.values.filter { it.used() }.map { bInt ->
                val keyName = keyToString(bInt.key)
                val isReg = bInt.key is IntervalKey.RegisterKey
                val regName = (bInt.key as? IntervalKey.RegisterKey)?.register?.name
                val allocReg = if (isReg) regName else allocatedMap[keyName]
                IntervalSpanDto(
                    keyName = keyName,
                    functionScope = functionScope,
                    isRegister = isReg,
                    registerName = regName,
                    startLine = bInt.start,
                    endLine = bInt.end,
                    reads = bInt.reads.mapNotNull { findTokenLocationOnLine(tokenizedFile, it, keyName, isReg) },
                    writes = bInt.writes.mapNotNull { findTokenLocationOnLine(tokenizedFile, it, keyName, isReg) },
                    interferences = interference.neighbors(bInt.key).map { keyToString(it) }.sorted(),
                    allocatedRegister = allocReg,
                    isSpill = allocReg == "SPILL",
                )
            }
        return CodeBlockDto(
            id = id,
            startLine = bStart,
            endLine = bEnd,
            entrances = node.entrances.map { it.hashCode().toString() },
            exits = node.exits.map { it.hashCode().toString() },
            intervals = bIntervals,
        )
    }

    private fun classifyToken(
        token: Token,
        lineIdx: Int,
        activeScope: FunctionScopeDto?,
        lineEffect: LineEffect?,
    ): SourceTokenDto {
        val text = token.text()
        val lower = text.lowercase(Locale.ROOT)
        var cssClass = "tok-ident"
        var varKey: String? = null
        var isWrite = false
        var isRead = false
        var impReads = emptyList<String>()
        var impWrites = emptyList<String>()

        if (text.startsWith(";")) {
            cssClass = "tok-comment"
        } else if (text.startsWith("\"") || text.startsWith("'")) {
            cssClass = "tok-string"
        } else if (text.startsWith("#") || text.startsWith("$") || text.matches(Regex("^[0-9].*"))) {
            cssClass = "tok-number"
        } else if (superFxMnemonics.contains(lower)) {
            cssClass = "tok-insn"
        } else if (pseudoKeywords.contains(lower)) {
            cssClass = "tok-keyword"
        } else if (Constants.isRegister(lower)) {
            cssClass = "tok-register"
            varKey = lower
        } else if (text.matches(Regex("^[,\\[\\]():=+\\-]+$"))) {
            cssClass = "tok-punct"
        } else if (activeScope != null) {
            val matchedInterval = activeScope.intervals.firstOrNull { it.keyName.equals(lower, ignoreCase = true) }
            if (matchedInterval != null) {
                varKey = matchedInterval.keyName
                cssClass = if (matchedInterval.isRegister) "tok-register" else "tok-var-label"
            }
        }

        if (lineEffect != null) {
            val writeKey = lineEffect.explicitWrites[token]
            val readKey = lineEffect.explicitReads[token]
            if (writeKey != null) {
                varKey = keyToString(writeKey)
                isWrite = true
                isRead = false
            } else if (readKey != null) {
                varKey = keyToString(readKey)
                isWrite = false
                isRead = true
            }

            if (token == lineEffect.instructionToken) {
                impReads = lineEffect.implicitReads.map { keyToString(it) }
                impWrites = lineEffect.implicitWrites.map { keyToString(it) }
                if (varKey == null) {
                    if (impWrites.size == 1 && impReads.isEmpty()) {
                        varKey = impWrites[0]
                        isWrite = true
                    } else if (impReads.size == 1 && impWrites.isEmpty()) {
                        varKey = impReads[0]
                        isRead = true
                    }
                }
            }
        }

        return SourceTokenDto(
            text = text,
            type = token.type?.name ?: "UNKNOWN",
            cssClass = cssClass,
            line = lineIdx,
            colStart = token.startIndex,
            colEnd = token.endIndex,
            variableKey = varKey,
            functionScope = activeScope?.name,
            isWrite = isWrite,
            isRead = isRead,
            implicitReads = impReads,
            implicitWrites = impWrites,
        )
    }

    private fun findTokenLocationOnLine(
        file: TokenizedFile,
        lineNum: Int,
        keyName: String,
        isRegister: Boolean,
    ): LocationDto? {
        val tokens = file.getLineTokens(lineNum) ?: return null
        val matchedToken =
            tokens.firstOrNull { t ->
                val txt = t.text().lowercase(Locale.ROOT)
                txt == keyName.lowercase(Locale.ROOT) || (isRegister && Constants.isRegister(txt) && txt == keyName)
            }
        if (matchedToken != null) {
            return LocationDto(line = lineNum, colStart = matchedToken.startIndex, colEnd = matchedToken.endIndex)
        }
        val insnToken =
            tokens.firstOrNull { t ->
                val txt = t.text().lowercase(Locale.ROOT)
                superFxMnemonics.contains(txt) || txt == "condensed_lmult"
            }
        return if (insnToken != null) {
            LocationDto(line = lineNum, colStart = insnToken.startIndex, colEnd = insnToken.endIndex)
        } else {
            LocationDto(line = lineNum, colStart = 0, colEnd = 0)
        }
    }

    private fun keyToString(key: IntervalKey): String =
        when (key) {
            is IntervalKey.RegisterKey -> key.register.label.lowercase(Locale.ROOT)
            is IntervalKey.LabelKey -> key.label
        }
}
