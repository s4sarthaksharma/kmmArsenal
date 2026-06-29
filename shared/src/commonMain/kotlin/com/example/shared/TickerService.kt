package com.example.shared

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Exercises the Flow bridge path (startX/stopX + event emission) across the three
 * primitive element types that unbox cleanly on iOS. Each flow name ends in "Flow",
 * and each reduces to a distinct base name (seconds/status/pulse) to avoid collisions.
 */
class TickerService {

    /** Emits an incrementing second counter once per second. */
    fun secondsFlow(): Flow<Int> = flow {
        var seconds = 0
        while (true) {
            emit(seconds++)
            delay(1000)
        }
    }

    /** Emits a rotating status label every two seconds. */
    fun statusFlow(): Flow<String> = flow {
        val labels = listOf("idle", "working", "done")
        var index = 0
        while (true) {
            emit(labels[index % labels.size])
            index++
            delay(2000)
        }
    }

    /** Emits an alternating boolean pulse every 500ms. */
    fun pulseFlow(): Flow<Boolean> = flow {
        var on = false
        while (true) {
            emit(on)
            on = !on
            delay(500)
        }
    }
}
