package com.example.shared

/**
 * Primitive-type stress test for the bridge generator.
 * Exercises every mapped Kotlin primitive as both parameter and return type,
 * plus a Unit return, non-public functions (which must be skipped), and the
 * three supported comment styles.
 */
class Calculator {

    /** Adds two 32-bit integers. */
    fun addInts(a: Int, b: Int): Int = a + b

    /** Adds two doubles. */
    fun addDoubles(a: Double, b: Double): Double = a + b

    // Adds two 64-bit longs. JS sends these as Double; the bridge casts back to Long.
    fun addLongs(a: Long, b: Long): Long = a + b

    /* Multiplies two floats. */
    fun multiplyFloats(a: Float, b: Float): Float = a * b

    // Inverts a boolean.
    fun negate(value: Boolean): Boolean = !value

    /**
     * Builds a human-readable description from a label and a count.
     * Declared across multiple lines on purpose to exercise the parser's
     * multi-line signature accumulation.
     */
    fun describe(
        label: String,
        count: Int,
    ): String = "$label: ${roundedCount(count)}"

    /** Returns nothing — exercises the Unit/void bridge path. */
    fun reset(): Unit {
        // no-op; present only to test a Unit-returning bridge function
    }

    // Private helper — must be excluded from the generated bridge.
    private fun roundedCount(count: Int): Int = if (count < 0) 0 else count

    // Internal helper — must also be excluded (only public members are bridged).
    internal fun internalSecret(): String = "should not be bridged"
}
