package com.example.shared

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class Greeting {
    private val platform = Platform()

    fun greet(name: String): String {
        return "Hello, $name! 👋 — from ${platform.name} (Kotlin Multiplatform)"
    }

    fun greeting2(): String {
        return "hello from greeting2"
    }

    fun greeting3(): String {
        return "new function "
    }

    //hello this o=is greeting4
    fun greeting4(): String {
        return "hello from greeting4"
    }

    fun counterFlow(): Flow<Int> = flow {
        var count = 0
        while (true) {
            emit(count++)
            delay(1000)
        }
    }

    suspend fun delayedEcho(text: String, delayMs: Long): String {
        delay(delayMs)
        return text
    }
}
