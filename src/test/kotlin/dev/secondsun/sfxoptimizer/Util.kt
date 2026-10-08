package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.SymbolService
import dev.secondsun.sfxoptimizer.graphbuilder.CA65Grapher
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander
import java.net.URI

fun graph(
    program: String,
    mainStartLine: Int = 0,
): CodeGraph {
    val file = (CA65Scanner().tokenize(program))

    val symbolService = SymbolService()
    symbolService.extractDefinitions(file)
    file.uri = URI.create("./test.sgs")
    val fileService = MockFileService(file)
    return CA65Grapher(symbolService = symbolService, fileService = fileService)
        .graph(file = file, line = mainStartLine)
}

fun graphExpanded(
    program: String,
    mainStartLine: Int = 0,
): CodeGraph {
    val rawFile = CA65Scanner().tokenize(program)
    val file = CA65MacroExpander().expand(rawFile)

    val symbolService = SymbolService()
    symbolService.extractDefinitions(file)
    file.uri = URI.create("./test.sgs")
    val fileService = MockFileService(file)
    return CA65Grapher(symbolService = symbolService, fileService = fileService)
        .graph(file = file, line = mainStartLine)
}
