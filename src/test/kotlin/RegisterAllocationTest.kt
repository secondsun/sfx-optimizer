import dev.secondsun.retro.util.CA65Scanner
import dev.secondsun.sfxoptimizer.allocate
import kotlin.test.Test
import kotlin.test.assertEquals

class RegisterAllocationTest {

    /**
     * This test tests a trivial register allocation.
     *
     * For example,
     *   register example
     *   iwt example, #5
     *   stw (example)
     * should create the code
     *   iwt r1, #5
     *   stw (r1)
     */
    @Test
    fun `trivial register selection with one variable`() {
        val code = """
            register example
            iwt example, #5
            stw(example)
        """.trimIndent()

        val expected = """
            iwt r1 , #5 
            stw ( r1 ) 

        """.trimIndent()

        val result = allocate(CA65Scanner().tokenize(code))
        assertEquals(expected, result)


    }

    @Test
    fun `trivial register selection with two variables`() {
        val code = """
            register example, example2
            iwt example, #5
            iwt example2, #15
            stw(example)
            stw(example2)
        """.trimIndent()

        val expected = """
            iwt r1 , #5 
            iwt r2 , #15 
            stw ( r1 ) 
            stw ( r2 ) 

        """.trimIndent()

        val result = allocate(CA65Scanner().tokenize(code))
        assertEquals(expected, result)


    }

    /**
     * ```
     *
     * function myFunc param
     *    sub param
     *    return
     *  endfunction
     *
     *  register newParam
     *  iwt myParam, #42
     *  call myFunc newParam
     * ```
     *
     * should translate to
     *
     * function myFunc
     *   sub r1
     *
     */
    @Test
    fun `trivial function call`() {
        TODO()
    }

    /**
     * The follow should throw an error
     *
     * ```
     *   call myFunction example
     * ```
     */
    @Test
    fun `undeclared variables in function call are an error`() {
        TODO()
    }

}