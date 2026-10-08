package dev.secondsun.sfxoptimizer.macro

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.FileService
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.vo.TokenizedFile

/**
 * Definition of a CA65 macro.
 */
data class MacroDef(
    val name: String,
    val parameters: List<String>,
    val bodyLines: List<String>,
)

/**
 * Expands CA65 macros (.macro ... .endmacro, .define, and built-in stack/loop macros)
 * into a concrete tokenized instruction stream prior to CFG construction and register coloring.
 */
class CA65MacroExpander(
    val fileService: FileService = FileService(),
    val scanner: CA65Scanner = CA65Scanner(),
) {
    private val macros = mutableMapOf<String, MacroDef>()
    private val defines = mutableMapOf<String, String>()

    init {
        registerBuiltins()
    }

    private fun registerBuiltins() {
        defines["stackpointer"] = "r10"

        registerMacro(
            name = "gsu_stack_push",
            parameters = listOf("R"),
            rawLines =
                listOf(
                    ".if .not(.blank({R}))",
                    "from R",
                    ".endif",
                    "stw (r10)",
                    "inc r10",
                    "inc r10",
                ),
        )

        registerMacro(
            name = "gsu_stack_pop",
            parameters = listOf("R"),
            rawLines =
                listOf(
                    "dec r10",
                    "dec r10",
                    ".if .not(.blank({R}))",
                    "to R",
                    ".endif",
                    "ldw (r10)",
                ),
        )

        registerMacro(
            name = "gsu_stack_peek",
            parameters = listOf("R"),
            rawLines =
                listOf(
                    "dec r10",
                    "dec r10",
                    ".if .not(.blank({R}))",
                    "to R",
                    ".endif",
                    "ldw (r10)",
                    "inc r10",
                    "inc r10",
                ),
        )

        registerMacro(
            name = "gsu_stack_alloc_bytes",
            parameters = listOf("S"),
            rawLines =
                listOf(
                    "with r10",
                    "adds S",
                ),
        )

        registerMacro(
            name = "gsu_stack_free_bytes",
            parameters = listOf("S"),
            rawLines =
                listOf(
                    "with r10",
                    "sub S",
                ),
        )

        registerMacro(
            name = "backuploop",
            parameters = emptyList(),
            rawLines =
                listOf(
                    "gsu_stack_push r12",
                    "gsu_stack_push r13",
                ),
        )

        registerMacro(
            name = "restoreloop",
            parameters = emptyList(),
            rawLines =
                listOf(
                    "gsu_stack_pop r13",
                    "gsu_stack_pop r12",
                ),
        )

        registerMacro(
            name = "for",
            parameters = listOf("count"),
            rawLines =
                listOf(
                    "backuploop",
                    "iwt r12, #count",
                    "move r13, r15",
                ),
        )

        registerMacro(
            name = "forr",
            parameters = listOf("countRegister"),
            rawLines =
                listOf(
                    "backuploop",
                    "move r12, countRegister",
                    "move r13, r15",
                ),
        )

        registerMacro(
            name = "endfor",
            parameters = emptyList(),
            rawLines =
                listOf(
                    "loop",
                    "nop",
                    "restoreloop",
                ),
        )

        registerMacro(
            name = "init_stack",
            parameters = emptyList(),
            rawLines =
                listOf(
                    "iwt r10, #(gsu_stack_ram)",
                ),
        )
    }

    fun registerMacro(
        name: String,
        parameters: List<String>,
        rawLines: List<String>,
    ) {
        macros[name.lowercase()] = MacroDef(name, parameters, rawLines)
    }

    /**
     * Expands all macros and defines in [file], returning a new [TokenizedFile]
     * with expanded instructions tokenized and mapped sequentially.
     */
    fun expand(file: TokenizedFile): TokenizedFile {
        extractDefinitions(file)

        val outputLines = mutableListOf<String>()
        var lineIdx = 0
        while (lineIdx < file.textLines()) {
            val lineTokens = file.getLine(lineIdx)
            if (lineTokens == null || lineTokens.tokens.isEmpty()) {
                lineIdx++
                continue
            }

            val t0 = lineTokens.tokens[0]
            if (t0.type == TokenType.TOK_MACRO || t0.text().equals(".macro", ignoreCase = true)) {
                lineIdx = skipMacroDef(file, lineIdx)
                continue
            }

            if (t0.type == TokenType.TOK_DEFINE || t0.text().equals(".define", ignoreCase = true)) {
                lineIdx++
                continue
            }

            val lineText = file.getLineText(lineIdx)?.trim() ?: ""
            val expanded = expandLine(lineText, lineTokens.tokens.map { it.text() }, 0)
            outputLines.addAll(expanded)
            lineIdx++
        }

        val fullText = outputLines.joinToString("\n")
        val result = scanner.tokenize(fullText)
        result.uri = file.uri
        return result
    }

    private fun extractDefinitions(file: TokenizedFile) {
        var idx = 0
        while (idx < file.textLines()) {
            val lineTokens = file.getLine(idx)
            if (lineTokens != null && lineTokens.tokens.isNotEmpty()) {
                val t0 = lineTokens.tokens[0]
                if (t0.type == TokenType.TOK_DEFINE || t0.text().equals(".define", ignoreCase = true)) {
                    val tokens = lineTokens.tokens
                    if (tokens.size >= 3) {
                        defines[tokens[1].text().lowercase()] = tokens[2].text()
                    }
                } else if (t0.type == TokenType.TOK_MACRO || t0.text().equals(".macro", ignoreCase = true)) {
                    val tokens = lineTokens.tokens
                    if (tokens.size >= 2) {
                        val macroName = tokens[1].text()
                        val params = mutableListOf<String>()
                        for (i in 2 until tokens.size) {
                            if (tokens[i].type != TokenType.TOK_COMMA) {
                                params.add(tokens[i].text())
                            }
                        }
                        val body = mutableListOf<String>()
                        idx++
                        while (idx < file.textLines()) {
                            val bodyLineTokens = file.getLine(idx)
                            val b0 = bodyLineTokens?.tokens?.firstOrNull()
                            if (b0 != null &&
                                (
                                    b0.type == TokenType.TOK_ENDMACRO ||
                                        b0.text().equals(".endmacro", ignoreCase = true) ||
                                        b0.text().equals(".endmac", ignoreCase = true)
                                )
                            ) {
                                break
                            }
                            val rawText = file.getLineText(idx)?.trim()
                            if (rawText != null && rawText.isNotBlank()) {
                                body.add(rawText)
                            }
                            idx++
                        }
                        macros[macroName.lowercase()] = MacroDef(macroName, params, body)
                    }
                }
            }
            idx++
        }
    }

    private fun skipMacroDef(
        file: TokenizedFile,
        startIdx: Int,
    ): Int {
        var idx = startIdx + 1
        while (idx < file.textLines()) {
            val line = file.getLine(idx)
            val b0 = line?.tokens?.firstOrNull()
            if (b0 != null &&
                (
                    b0.type == TokenType.TOK_ENDMACRO ||
                        b0.text().equals(".endmacro", ignoreCase = true) ||
                        b0.text().equals(".endmac", ignoreCase = true)
                )
            ) {
                return idx + 1
            }
            idx++
        }
        return idx
    }

    private fun expandLine(
        rawLine: String,
        tokenTexts: List<String>,
        depth: Int,
    ): List<String> {
        if (depth > 32 || tokenTexts.isEmpty()) {
            return if (rawLine.isNotBlank()) listOf(rawLine) else emptyList()
        }

        val firstTokenLower = tokenTexts[0].lowercase()
        val macro = macros[firstTokenLower]
        if (macro != null) {
            val args = mutableListOf<String>()
            for (i in 1 until tokenTexts.size) {
                val t = tokenTexts[i]
                if (t != ",") {
                    args.add(t)
                }
            }
            val argMap = mutableMapOf<String, String>()
            macro.parameters.forEachIndexed { i, p ->
                if (i < args.size) {
                    argMap[p.lowercase()] = args[i]
                }
            }

            val result = mutableListOf<String>()
            var bodyIdx = 0
            while (bodyIdx < macro.bodyLines.size) {
                val bodyLine = macro.bodyLines[bodyIdx]

                if (isIfCondition(bodyLine)) {
                    val condMet = evaluateIfCondition(bodyLine, argMap)
                    bodyIdx++
                    val conditionalLines = mutableListOf<String>()
                    while (bodyIdx < macro.bodyLines.size && !isEndIf(macro.bodyLines[bodyIdx])) {
                        conditionalLines.add(macro.bodyLines[bodyIdx])
                        bodyIdx++
                    }
                    if (condMet) {
                        for (cLine in conditionalLines) {
                            val substituted = substitute(cLine, argMap)
                            val subTokens = splitTokens(substituted)
                            result.addAll(expandLine(substituted, subTokens, depth + 1))
                        }
                    }
                } else {
                    val substituted = substitute(bodyLine, argMap)
                    val subTokens = splitTokens(substituted)
                    result.addAll(expandLine(substituted, subTokens, depth + 1))
                }
                bodyIdx++
            }
            return result
        } else {
            val substituted = substituteDefines(rawLine)
            return if (substituted.isNotBlank()) listOf(substituted) else emptyList()
        }
    }

    private fun substitute(
        bodyLine: String,
        argMap: Map<String, String>,
    ): String {
        var s = bodyLine
        for ((param, arg) in argMap) {
            s = s.replace("{$param}", arg, ignoreCase = true)
            s = s.replace(Regex("(?i)\\b$param\\b"), arg)
        }
        return substituteDefines(s)
    }

    private fun substituteDefines(line: String): String {
        var res = line
        for ((defKey, defVal) in defines) {
            res = res.replace(Regex("(?i)\\b$defKey\\b"), defVal)
        }
        return res
    }

    private fun splitTokens(line: String): List<String> {
        val clean = line.substringBefore(";").trim()
        if (clean.isEmpty()) return emptyList()
        return clean.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
    }

    private fun isIfCondition(line: String): Boolean {
        val l = line.trim().lowercase()
        return l.startsWith(".if") || l.startsWith(".ifnblank") || l.startsWith(".ifblank")
    }

    private fun isEndIf(line: String): Boolean = line.trim().lowercase().startsWith(".endif")

    private fun evaluateIfCondition(
        line: String,
        argMap: Map<String, String>,
    ): Boolean {
        val l = line.trim().lowercase()
        if (l.contains(".not") && l.contains(".blank")) {
            for ((param, value) in argMap) {
                if (l.contains(param)) {
                    return value.isNotBlank()
                }
            }
            return false
        }
        if (l.contains(".ifnblank")) {
            for ((param, value) in argMap) {
                if (l.contains(param)) {
                    return value.isNotBlank()
                }
            }
            return false
        }
        if (l.contains(".ifblank")) {
            for ((param, value) in argMap) {
                if (l.contains(param)) {
                    return value.isBlank()
                }
            }
            return true
        }
        return true
    }
}
