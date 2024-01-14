import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.TokenType
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.Constants.Register.*

fun allocate(program:TokenizedFile):String {

    val output = StringBuilder()
    val variables = mutableListOf<String>()
    val registerPool = mutableSetOf(R0, R1, R2, R3, R4, R5, R6,R7,R8,R9,R11,R12,R13,R14);//R10 is reserved for stack, R15 is PC
    var dirtyLine = false;

    for (idx in 0..<program.textLines()) {
        val line = program.getLine(idx)

        if (line.tokens.isEmpty()) {
            continue
        }

        val firstToken = line.tokens[0]
        if (firstToken.text().equals("var")) {
            for (tokenIndex in 1..<line.tokens.size) {
                val token = line.tokens[tokenIndex]
                if (token.type == TokenType.TOK_IDENT) {
                    variables.add(token.text())
                }
            }
        } else if (isInstruction(firstToken)) {
            output.append(firstToken.text())
            output.append(" ")
            for (tokenIndex in 1..<line.tokens.size) {
                val token = line.tokens[tokenIndex]
                if (isArgument(token)) {
                    output.append("r1")
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

/**
 * Is the Token a GSU Instruction?
 */
fun isInstruction(firstToken: Token): Boolean {
    return true
}
