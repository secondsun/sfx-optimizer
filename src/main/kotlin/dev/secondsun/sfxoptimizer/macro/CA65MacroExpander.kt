package dev.secondsun.sfxoptimizer.macro

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.WorkspaceSnapshot
import dev.secondsun.retro.util.vo.TokenizedFile
import java.net.URI

/**
 * 1-based source file and line provenance for an expanded or lowered instruction line.
 */
data class SourceOrigin(
    val uri: URI?,
    val line1: Int,
)

/**
 * Macro- or include-expanded instruction stream paired with 1-based [SourceOrigin] provenance
 * for every 0-based line in [tokenizedFile].
 */
data class ExpandedSource(
    val tokenizedFile: TokenizedFile,
    val origins: List<SourceOrigin>,
) {
    fun originOf(expandedLine0: Int): SourceOrigin =
        if (expandedLine0 in origins.indices) {
            origins[expandedLine0]
        } else {
            SourceOrigin(tokenizedFile.uri(), maxOf(1, expandedLine0 + 1))
        }
}

/**
 * Definition of a CA65 macro.
 */
data class MacroDef(
    val name: String,
    val parameters: List<String>,
    val bodyLines: List<String>,
)

/**
 * Expands CA65 macros (`.macro ... .endmacro`, `.define`, and built-in stack/loop macros)
 * into a concrete tokenized instruction stream while preserving 1-based [SourceOrigin] provenance.
 */
class CA65MacroExpander
    @JvmOverloads
    constructor(
        val snapshot: WorkspaceSnapshot? = null,
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
                        "add S",
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
         * Expands all macros and defines in [file], returning an [ExpandedSource] with exact
         * 1-based [SourceOrigin] provenance per expanded line.
         */
        fun expandWithProvenance(file: TokenizedFile): ExpandedSource {
            loadSnapshotDefinitions(file)
            extractDefinitions(file)

            val outputLines = mutableListOf<String>()
            val origins = mutableListOf<SourceOrigin>()
            val fileUri = file.uri()

            var lineIdx = 0
            while (lineIdx < file.textLines()) {
                val lineTokens = file.getLine(lineIdx)
                val toks = lineTokens?.tokens() ?: emptyList()
                if (toks.isEmpty()) {
                    lineIdx++
                    continue
                }

                val t0 = toks[0]
                if (t0.type == TokenType.TOK_MACRO || t0.text().equals(".macro", ignoreCase = true)) {
                    lineIdx = skipMacroDef(file, lineIdx)
                    continue
                }

                if (t0.type == TokenType.TOK_DEFINE || t0.text().equals(".define", ignoreCase = true)) {
                    lineIdx++
                    continue
                }

                val origin = SourceOrigin(fileUri, lineIdx + 1)
                val lineText = file.getLineText(lineIdx)?.substringBefore(';')?.trim() ?: ""
                if (lineText.isEmpty()) {
                    lineIdx++
                    continue
                }

                // Preserve leading label on a macro invocation line (e.g. `lbl: gsu_stack_push r4`)
                val (leadingLabel, bodyTokens, bodyText) = splitLeadingLabel(lineText, toks.map { it.text() })
                if (leadingLabel != null) {
                    if (bodyTokens.isEmpty()) {
                        outputLines.add(leadingLabel)
                        origins.add(origin)
                        lineIdx++
                        continue
                    } else if (macros.containsKey(bodyTokens[0].lowercase())) {
                        outputLines.add(leadingLabel)
                        origins.add(origin)
                        val expanded = expandLine(bodyText, bodyTokens, 0)
                        for (expLine in expanded) {
                            outputLines.add(expLine)
                            origins.add(origin)
                        }
                        lineIdx++
                        continue
                    }
                }

                val expanded = expandLine(lineText, toks.map { it.text() }, 0)
                for (expLine in expanded) {
                    outputLines.add(expLine)
                    origins.add(origin)
                }
                lineIdx++
            }

            val fullText = outputLines.joinToString("\n")
            val tokenized =
                if (outputLines.isEmpty()) {
                    TokenizedFile.emptyWithUri(fileUri)
                } else {
                    scanner.tokenize(fullText, fileUri)
                }
            return ExpandedSource(tokenized, origins)
        }

        /**
         * Expands all macros and defines in [file], returning the expanded [TokenizedFile].
         */
        fun expand(file: TokenizedFile): TokenizedFile = expandWithProvenance(file).tokenizedFile

        private fun splitLeadingLabel(
            lineText: String,
            tokenTexts: List<String>,
        ): Triple<String?, List<String>, String> {
            if (tokenTexts.isEmpty()) return Triple(null, tokenTexts, lineText)
            if (tokenTexts[0] == ":") {
                val rest = lineText.removePrefix(":").trim()
                return Triple(":", tokenTexts.drop(1), rest)
            }
            if (tokenTexts.size >= 2 && tokenTexts[1] == ":") {
                val lbl = "${tokenTexts[0]}:"
                val rest = lineText.substringAfter(':').trim()
                return Triple(lbl, tokenTexts.drop(2), rest)
            }
            if (tokenTexts[0].endsWith(":") && tokenTexts[0].length > 1) {
                val lbl = tokenTexts[0]
                val rest = lineText.substringAfter(':').trim()
                return Triple(lbl, tokenTexts.drop(1), rest)
            }
            return Triple(null, tokenTexts, lineText)
        }

        private fun loadSnapshotDefinitions(rootFile: TokenizedFile) {
            val snap = snapshot ?: return
            val visited = mutableSetOf<URI>()

            fun walkIncludes(tf: TokenizedFile) {
                val u = tf.uri()
                if (u != null && !visited.add(u)) return
                for (inc in tf.scopeTree().includes()) {
                    val targets = snap.findIncludeUris(u, inc)
                    for (targetUri in targets) {
                        if (targetUri in visited) continue
                        val incFile = snap.file(targetUri) ?: continue
                        walkIncludes(incFile)
                        extractDefinitions(incFile, overrideBuiltins = false)
                    }
                }
            }

            walkIncludes(rootFile)
        }

        private fun extractDefinitions(
            file: TokenizedFile,
            overrideBuiltins: Boolean = true,
        ) {
            var idx = 0
            while (idx < file.textLines()) {
                val toks = file.getLine(idx)?.tokens() ?: emptyList()
                if (toks.isNotEmpty()) {
                    val t0 = toks[0]
                    if (t0.type == TokenType.TOK_DEFINE || t0.text().equals(".define", ignoreCase = true)) {
                        if (toks.size >= 3) {
                            val key = toks[1].text().lowercase()
                            if (overrideBuiltins || key !in defines) {
                                defines[key] = toks[2].text()
                            }
                        }
                    } else if (t0.type == TokenType.TOK_MACRO || t0.text().equals(".macro", ignoreCase = true)) {
                        if (toks.size >= 2) {
                            val macroName = toks[1].text()
                            val params = mutableListOf<String>()
                            for (i in 2 until toks.size) {
                                if (toks[i].type != TokenType.TOK_COMMA) {
                                    params.add(toks[i].text())
                                }
                            }
                            val body = mutableListOf<String>()
                            idx++
                            while (idx < file.textLines()) {
                                val bodyToks = file.getLine(idx)?.tokens() ?: emptyList()
                                val b0 = bodyToks.firstOrNull()
                                if (b0 != null &&
                                    (
                                        b0.type == TokenType.TOK_ENDMACRO ||
                                            b0.text().equals(".endmacro", ignoreCase = true) ||
                                            b0.text().equals(".endmac", ignoreCase = true) ||
                                            b0.text().equals(".endm", ignoreCase = true)
                                    )
                                ) {
                                    break
                                }
                                val rawText = file.getLineText(idx)?.substringBefore(';')?.trim()
                                if (!rawText.isNullOrBlank()) {
                                    body.add(rawText)
                                }
                                idx++
                            }
                            val key = macroName.lowercase()
                            if (overrideBuiltins || key !in macros) {
                                macros[key] = MacroDef(macroName, params, body)
                            }
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
                val b0 = file.getLine(idx)?.tokens()?.firstOrNull()
                if (b0 != null &&
                    (
                        b0.type == TokenType.TOK_ENDMACRO ||
                            b0.text().equals(".endmacro", ignoreCase = true) ||
                            b0.text().equals(".endmac", ignoreCase = true) ||
                            b0.text().equals(".endm", ignoreCase = true)
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
                    argMap[p.lowercase()] = if (i < args.size) args[i] else ""
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
                s = s.replace(Regex("(?i)\\b${Regex.escape(param)}\\b"), arg)
            }
            return substituteDefines(s).trim()
        }

        private fun substituteDefines(line: String): String {
            var res = line
            for ((defKey, defVal) in defines) {
                res = res.replace(Regex("(?i)\\b${Regex.escape(defKey)}\\b"), defVal)
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
