package com.example.shared

/**
 * Fixture: a file whose name collides with its interface. The interface registry module claims
 * the plain name, so the file-scope module must get the `Kt` suffix on every platform.
 */
interface FixtureNamedIface {
    suspend fun ping(): String
}

fun fixtureNamedIfaceHello(): String = "hello-from-file-scope"
