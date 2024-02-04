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

sealed interface IntervalKey {
    data class RegisterKey(val register:Constants.Register):IntervalKey
    data class LabelKey(val label:String):IntervalKey
}
