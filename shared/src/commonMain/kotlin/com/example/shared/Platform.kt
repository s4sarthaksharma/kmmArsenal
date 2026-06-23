package com.example.shared

/**
 * A value that differs per platform. The [Platform] class is declared here in
 * commonMain via `expect`, and each platform supplies the `actual` implementation.
 */
expect class Platform() {
    val name: String
}
