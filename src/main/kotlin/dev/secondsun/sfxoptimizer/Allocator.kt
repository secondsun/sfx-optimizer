package dev.secondsun.sfxoptimizer


import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.instruction.GSUInstruction
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.Constants.Register.*
import java.util.*
import kotlin.collections.HashMap

sealed interface AllocationResult {
    data class Register(val register:Constants.Register):AllocationResult;
    object Spill:AllocationResult{};

}
data class AllocationContext(val registerPool:MutableList<Constants.Register> = mutableListOf(R1, R2, R3, R4, R5, R6,R7,R8,R9,R11), val parings:MutableMap<String, Constants.Register> = mutableMapOf()) {


    fun allocate(label : String) : AllocationResult {
        if (registerPool.isEmpty()) {
            return AllocationResult.Spill
        } else {
            val register = registerPool.removeFirst()
            parings[label] = register
            return AllocationResult.Register(register)
        }
    }

}



fun allocate(program:TokenizedFile):String {
    val context = AllocationContext()
    val output = StringBuilder()
    var dirtyLine = false;

    for (idx in 0..<program.textLines()) {
        val line = program.getLine(idx)

        if (line.tokens.isEmpty()) {
            continue
        }

        val firstToken = line.tokens[0]
        if (firstToken.text().equals("register")) {
            for (tokenIndex in 1..<line.tokens.size) {
                val token = line.tokens[tokenIndex]
                if (token.type == TokenType.TOK_IDENT) {
                    if (context.allocate(token.text()) is AllocationResult.Spill) {
                        throw RuntimeException("Spill is not handled")
                    }
                }
            }
        } else if (GSUInstruction.isInstruction(firstToken)) {
            output.append(firstToken.text())
            output.append(" ")
            for (tokenIndex in 1..<line.tokens.size) {
                val token = line.tokens[tokenIndex]
                if (isArgument(token)) {
                    output.append(context.parings[token.text()]?.label)
                    output.append(" ")
                    dirtyLine = true
                } else {
                    output.append(token.text())
                    if (peekToken(line.tokens, tokenIndex + 1)?.type != TokenType.TOK_INTCON) {
                        output.append(" ")
                    }
                    dirtyLine = true
                }
            }
        }
        if (dirtyLine) {
            dirtyLine = false;
            output.append("\n")
        }
    }

    return output.toString()
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

