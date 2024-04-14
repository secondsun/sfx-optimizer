package dev.secondsun.sfxoptimizer


import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenType
import dev.secondsun.sfxoptimizer.Constants.Register.*
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph
import dev.secondsun.sfxoptimizer.graphnode.CodeNode

sealed interface AllocationResult {
    data class Register(val register:Constants.Register):AllocationResult;
    object Spill:AllocationResult{};

}
data class AllocationContext(val registerPool:MutableList<Constants.Register> = mutableListOf(R0, R1, R2, R3, R4, R5, R6,R7,R8,R9,R11,R12,R13, R14)) {


    /**
     * Removes a register or spills
     */
    fun allocate() : AllocationResult {
        if (registerPool.isEmpty()) {
            return AllocationResult.Spill
        } else {
            val register = registerPool.removeFirst()
            return AllocationResult.Register(register)
        }
    }

    /**
     * Removes a register to the pool
     */
    fun reserve( register: Constants.Register) {
        registerPool.remove(register)
    }

    /**
     * Returns a register to the pool
     */
    fun free( register: Constants.Register) {
        registerPool.add(register)
    }

}


/**
 * Attach registers to register labels.
 */
fun allocate(program:CodeGraph) {

    //build function stack
    //for each stack entry allocate registers
    val graphStack =mutableListOf<CodeNode.FunctionStart>()
    fillGraphStack(program, graphStack);

    //First pass intervals, allocate function locals
    graphStack.asReversed().forEach { allocateFunction(it) }

    //Second pass intervals, allocate around function calls
    // this needs to handle circular function references
    // and parameters

    TODO(":Allocate calls")

    TODO(":Allocate main")


}

fun allocateFunction(function: CodeNode.FunctionStart) {
    val context = AllocationContext()
    val intervals : MutableList<Interval> = mutableListOf()
    var startline = Int.MAX_VALUE;
    var endline = Int.MIN_VALUE;

    /**
     * Find function start and end
     */
    function.functionBody.start().traverse { node ->
        when (node) {
            is CodeNode.CallBlock -> {if (node.line <startline) {startline = node.line};if (node.line > endline) endline = node.line}
            is CodeNode.CodeBlock -> {
                with(node) {
                    lines.forEach { line ->
                        if (line.tokens[0].lineNumber <startline) {startline = line.tokens[0].lineNumber};if (line.tokens[0].lineNumber > endline) endline = line.tokens[0].lineNumber
                    }
                }
            }
            CodeNode.End -> {}
            is CodeNode.FunctionStart -> {}
            is CodeNode.Start -> {}
        }
    }

    //Find all intervals not in calls


    TODO("Not yet implemented")
}

/**
 * Fills the @param graphStack with data from @param program
 */
private fun fillGraphStack(
    program: CodeGraph,
    graphStack: MutableList<CodeNode.FunctionStart>
){

    program.traverse() { node ->
        if (node is CodeNode.CallBlock) {
            if (!graphStack.contains(node.function)) {
                graphStack.add(node.function)
                fillGraphStack(node.function.functionBody, graphStack)
            }
        }
    }

}

fun peekToken(tokens: List<Token>, i: Int): Token? {
    if (i<tokens.size) {
        return tokens[i]
    } else {
        return null;
    }
}

/**
Return true if token is an argument literal for a instruction
 This is a signal that the token should be replaced with a register
 */
fun isArgument(token: Token): Boolean {
    return token.type == TokenType.TOK_IDENT
}

