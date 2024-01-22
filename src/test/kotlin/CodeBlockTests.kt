import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.retro.util.SymbolService
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

        val codeNode: CodeNode.CodeBlock = codeGraph.start().mainMethod();

        assertEquals(2, codeNode.lines.size);
        assertTrue(codeGraph.end() is CodeNode.End);
    }

    @Test
    fun `split code node when it is jumped into midway`() {
        val program = """
               iwt r0 , #5 ;-
               
               label:
               beq label
               nop
               
               stop
               """.trimMargin()
        val file = (CA65Scanner().tokenize(program))
        val symbolService = SymbolService()
        symbolService.extractDefinitions(file)
        val codeGraph = CA65Grapher(symbolService).graph(file = file, line = 0)

        println(codeGraph.print())

        assertEquals(5, codeGraph.nodeCount);
        assertEquals(1, codeGraph.startNode.mainMethod().exits.size)
        //the second node links to itself
        assertEquals(2, codeGraph.startNode.mainMethod().exits.get(0).exits.size)
        assertEquals(2, codeGraph.startNode.mainMethod().exits.get(0).entrances.size)
   }


    @Test
    fun `do conditional blocks make everything correctly`() {
        val program ="""
               iwt r0 , #5 ;-
               add #0      ;-
               
               label:
               beq next
               nop()
               
               iwt r2 , #5 ;-
               stw ( r2 )  ;-
               
               next:                   ;-
               iwt r3 , #5        ;-
               stw ( r3 )         ;-
               beq label          ;-
               nop              ;-
               
               stop ; comment -
        """.trimIndent()

        val file = (CA65Scanner().tokenize(program))
        val symbolService = SymbolService()
        symbolService.extractDefinitions(file)
        val codeGraph = CA65Grapher(symbolService).graph(file = file, line = 0)

        println(codeGraph.print())

        assertEquals(7, codeGraph.nodeCount);

        val block1 = codeGraph.startNode.mainMethod() as CodeNode.CodeBlock
        val block2 = (block1).exits[0] as CodeNode.CodeBlock
        val block3 = (block2 ).exits[1] as CodeNode.CodeBlock
        val block4 = (block2 ).exits[0] as CodeNode.CodeBlock
        val block5 = block4.exits[0] as CodeNode.CodeBlock
        val block6 = block5.exits[1] as CodeNode.CodeBlock

        assertEquals(1, block1.exits.size)
        assertEquals(1, block3.exits.size)
        assertEquals(2, block2.exits.size)
        assertEquals(2, block2.entrances.size)
        assertEquals(2, block5.exits.size)
        assertEquals(3, (block2).lines.size)
        assertEquals(5, (block4).lines.size)

    }

    @Test
    fun `code nodes must include empty lines`() {
        val program = """iwt r4, #$5
            
            iwt r6,#$9
            """
        val file = CA65Scanner().tokenize(program)
        val codeGraph = CA65Grapher().graph(file, line = 0)
        assertEquals(3, codeGraph.nodeCount);
        assertEquals(3, codeGraph.startNode.mainMethod().lines.size)

    }
    @Test
    fun `does a trivial jump generate 4 code nodes`() {
        val program = """
            iwt r1 , #5 
            stw ( r1 ) 
            bra next
            nop

            iwt r2 , #5 
            stw ( r2 )
            
            next:
            iwt r3 , #5 
            stw ( r3 ) 
        """.trimMargin()

        val file = (CA65Scanner().tokenize(program))
        val symbolService = SymbolService()
        symbolService.extractDefinitions(file)
        val codeGraph = CA65Grapher(symbolService).graph(file = file, line = 0)
        println(codeGraph.print())
        assertEquals(4, codeGraph.nodeCount);

        val codeNode :CodeNode.CodeBlock = codeGraph.start().mainMethod();

        assertEquals(4, codeNode.lines.size);
        assertEquals(1, codeNode.exits.size);

        val afterJumpNode :CodeNode.CodeBlock = codeNode.exits[0] as CodeNode.CodeBlock;

        assertEquals(3, afterJumpNode.lines.size);
        assertEquals(1, afterJumpNode.exits.size);

        assertEquals("r3", afterJumpNode.lines[1].tokens[1].text())



    }

}