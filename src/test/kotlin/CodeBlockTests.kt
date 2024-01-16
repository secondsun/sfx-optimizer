import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.sfxoptimizer.CA65Grapher
import dev.secondsun.sfxoptimizer.CodeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These tests test that code blocks are generated correctly.
 * The tests use the X-GSU lib in the homebrew library
 *
 * This tests also make use of my function mnemonics provided
 * by CA65 macros in the X-GSU library.
 *
 * Each tests will be given a start point in a TokenizedFile
 * and then the code will examine the code block graph genertaed.
 *
 * The first block is the "Start" Block and has a single exit : the
 * "main" method. An exit is a pointer to another code block.
 * Each block (except start) has an entrance and at least one exit (except the End block)
 */
class CodeBlockTests {
    @Test
    fun `does a trivial code block generate a start, end, and code`() {
        val program = """
            iwt r1 , #5 
            stw ( r1 ) 
        """.trimIndent()

        val file = (CA65Scanner().tokenize(program))
        val codeGraph = CA65Grapher().graph(file = file, line = 0)
        assertEquals(3, codeGraph.nodeCount);
        assertTrue(codeGraph.start() is CodeNode.Start);

        val codeNode :CodeNode.CodeBlock = codeGraph.start().mainMethod();

        assertEquals(2, codeNode.lines.size);
        assertTrue(codeGraph.end() is CodeNode.End);
    }

    @Test
    fun `does a trivial jump generate 4 code nodes`() {
        val program = """
            iwt r1 , #5 
            stw ( r1 ) 
            jmp next
            nop()
            iwt r2 , #5 
            stw ( r2 )
            next:
            iwt r3 , #5 
            stw ( r3 ) 
        """.trimIndent()

        val file = (CA65Scanner().tokenize(program))
        val codeGraph = CA65Grapher().graph(file = file, line = 0)
        assertEquals(4, codeGraph.nodeCount);

        val codeNode :CodeNode.CodeBlock = codeGraph.start().mainMethod();

        assertEquals(4, codeNode.lines.size);
        assertEquals(1, codeNode.exits.size);

        val afterJumpNode :CodeNode.CodeBlock = codeNode.exits[0] as CodeNode.CodeBlock;

        assertEquals(2, afterJumpNode.lines.size);
        assertEquals(2, afterJumpNode.lines.size);
        assertEquals(1, afterJumpNode.exits.size);

        assertEquals("r3", afterJumpNode.lines[0].tokens[1].text())



    }

}