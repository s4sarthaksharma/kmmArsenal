package com.example.shared

import kotlinx.coroutines.delay

/**
 * Exercises the suspend-function bridge path (AsyncFunction + Promise on both natives).
 * Covers suspend functions with and without parameters, plus a private suspend helper
 * that must not be bridged.
 */
class AsyncWorker {

    /** Suspends briefly, then returns a fixed message. */
    suspend fun fetchMessage(): String {
        delay(100)
        return "message from AsyncWorker"
    }

    /** Suspends, then returns the sum of two integers. */
    suspend fun computeSum(a: Int, b: Int): Int {
        delay(50)
        return a + b
    }

    /** Waits for the given duration, then returns true. Exercises a Long parameter on a suspend function. */
    suspend fun waitAndFlag(delayMs: Long): Boolean {
        delay(delayMs)
        return true
    }

    // Private suspend helper — must be excluded from the generated bridge.
    private suspend fun internalWait() {
        delay(10)
    }
}
