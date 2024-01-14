import dev.secondsun.retro.util.CA65Scanner
import kotlin.test.Test
import kotlin.test.assertEquals

class RegisterAllocationTest {

    /**
     * This test tests a trivial register allocation.
     *
     * For example,
     *   var example
     *   iwt example, #5
     *   stw (example)
     * should create the code
     *   iwt r1, #5
     *   stw (r1)
     */
    @Test
    fun `trivial register selection with one variable`() {
        val code = """
            var example
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

    /**
     * ```
     *
     * function myFunc param
     *    sub param
     *    return
     *  endfunction
     *
     *  var newParam
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