package dev.secondsun.sfxoptimizer.analysis

import dev.secondsun.retro.util.Token
import dev.secondsun.retro.util.vo.TokenizedFile
import dev.secondsun.sfxoptimizer.Constants
import dev.secondsun.sfxoptimizer.Interval
import dev.secondsun.sfxoptimizer.IntervalKey

/**
 * Declared register variable, optionally forced to a specific physical register.
 */
data class DeclaredRegister(
    val name: String,
    val forcedRegister: Constants.Register? = null,
    val line: Int,
)

/**
 * Representation of an argument to a `call` invocation.
 */
sealed interface CallArg {
    data class RegOrVar(
        val name: String,
        val token: Token,
    ) : CallArg

    data class Immediate(
        val expr: String,
    ) : CallArg
}

/**
 * Parsed `call` statement.
 */
data class CallStatement(
    val target: String,
    val args: List<CallArg>,
    val dest: String?,
)

/**
 * Discovered function boundary in source.
 */
data class FunctionBoundary(
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val params: List<String> = emptyList(),
    val headerReturnVar: String? = null,
    val inferredReturnVar: String? = null,
) {
    val effectiveReturnVar: String?
        get() = headerReturnVar ?: inferredReturnVar
}

/**
 * Per-line register and variable effect of an instruction or macro.
 */
data class LineEffect(
    val lineIdx: Int,
    val instructionToken: Token?,
    val explicitReads: Map<Token, IntervalKey> = emptyMap(),
    val explicitWrites: Map<Token, IntervalKey> = emptyMap(),
    val implicitReads: List<IntervalKey> = emptyList(),
    val implicitWrites: List<IntervalKey> = emptyList(),
    val memoryAddressKeys: Set<IntervalKey> = emptySet(),
    val nextPendingSource: IntervalKey? = null,
    val nextPendingDest: IntervalKey? = null,
)

/**
 * Complete analysis of a single function scope.
 */
data class FunctionAnalysis(
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val params: List<String>,
    val effectiveReturnVar: String?,
    val paramRegs: List<Constants.Register>,
    val returnReg: Constants.Register?,
    val clobberedRegisters: Set<Constants.Register>,
    val allocatedMap: Map<String, Constants.Register>,
    val declaredRegisters: Map<String, DeclaredRegister>,
    val intervals: Map<IntervalKey, Interval>,
    val loops: List<IntRange>,
    val effects: Map<Int, LineEffect>,
)

/**
 * Complete analysis of top-level non-function code.
 */
data class MainAnalysis(
    val startLine: Int,
    val endLine: Int,
    val allocatedMap: Map<String, Constants.Register>,
    val declaredRegisters: Map<String, DeclaredRegister>,
    val intervals: Map<IntervalKey, Interval>,
    val loops: List<IntRange>,
    val effects: Map<Int, LineEffect>,
)

/**
 * Complete result of whole-program or whole-file SuperFX analysis.
 */
data class ProgramAnalysisResult(
    val tokenizedFile: TokenizedFile,
    val totalLines: Int,
    val functions: List<FunctionAnalysis>,
    val main: MainAnalysis?,
    val overallAllocatedMap: Map<String, Constants.Register>,
    val allIntervals: List<Interval>,
    val maxRegisterPressure: Int,
    val pressureStatus: String,
)
