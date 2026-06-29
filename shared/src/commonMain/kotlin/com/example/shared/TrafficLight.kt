package com.example.shared

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The colors a traffic light can show. Bridged across the wire as its case name (a String). */
enum class LightColor {
    RED,
    YELLOW,
    GREEN,
}

/**
 * Exercises the enum bridge path end-to-end: an enum as a return value, as a parameter,
 * and as a Flow element type.
 */
class TrafficLight {

    private var index = 0

    /** Returns the current light color (enum return value). */
    fun currentColor(): LightColor = LightColor.entries[index % LightColor.entries.size]

    /** Returns true when the given color means "stop" (enum parameter). */
    fun isStop(color: LightColor): Boolean = color == LightColor.RED

    /** Emits the light color as it cycles RED → YELLOW → GREEN (enum Flow element). */
    fun colorFlow(): Flow<LightColor> = flow {
        var i = 0
        while (true) {
            emit(LightColor.entries[i % LightColor.entries.size])
            i++
            delay(1000)
        }
    }
}
