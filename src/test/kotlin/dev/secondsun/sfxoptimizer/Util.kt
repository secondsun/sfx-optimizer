package dev.secondsun.sfxoptimizer

import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.SymbolService
import dev.secondsun.sfxoptimizer.graphbuilder.CA65Grapher
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
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
