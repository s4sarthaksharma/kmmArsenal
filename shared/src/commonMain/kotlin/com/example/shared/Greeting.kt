package com.example.shared

/**
 * The shared business logic that both Android and iOS call into through the
 * native bridge. Anything written here runs identically on both platforms.
 */
class Greeting {
    private val platform = Platform()

    fun greet(name: String): String {
        return "Hello, $name! 👋 — from ${platform.name} (Kotlin Multiplatform)"
    }

    fun greeting2(): String {
        return "hello from greeting2"
    }
}
