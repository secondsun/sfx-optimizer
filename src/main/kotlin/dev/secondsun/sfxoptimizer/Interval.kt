package dev.secondsun.sfxoptimizer


class Interval(val key:IntervalKey) {
    var start = Int.MAX_VALUE
    var end = Int.MIN_VALUE

    private val _reads = mutableSetOf<Int>()
    private val _writes = mutableSetOf<Int>()

    val reads get() = _reads.toSortedSet()
    val writes get() = _writes.toSortedSet()

    /**
     * Was the interval key used in code.
     * This catches unused declared values.
     */
    fun used() :Boolean  {
        return start != Int.MAX_VALUE
    }

    fun addRead(line:Int) {
        if (start > line) {
            start = line
        }
        if (end < line) {
            end = line
        }
        _reads.add(line)
    }
    fun addWrite(line:Int) {
        if (start > line) {
            start = line
        }
        if (end < line) {
            end = line
        }
        _writes.add(line)
    }

}

/**
 * Interval keys present a way to represent liveliness.
 * Each key represents a kind of reference that the register allocator must reason about.
 */
sealed interface IntervalKey {
    /**
     * A registerkey is created when a register is explicitly used.
     * The allocator uses these keys to remove registers from pools when they're
     * required by certain instructions such as loop, mult, merge, etc.
     *
     * In general code written should use register labels and let the allocator handle them.
     */
    data class RegisterKey(val register:Constants.Register):IntervalKey

    /**
     * A label key represents a label in code that is a stand in for a register.
     * In this project this is often called a RegisterLabel.
     */
    data class LabelKey(val label:String):IntervalKey

}
