package dev.secondsun.sfxoptimizer.graphbuilder

import dev.secondsun.sfxoptimizer.graphnode.CodeNode


fun interface  CodeNodeVisitor {
    fun visit(node: CodeNode)
}
