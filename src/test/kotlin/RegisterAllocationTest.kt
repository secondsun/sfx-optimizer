import kotlin.test.Test

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
        TODO()
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