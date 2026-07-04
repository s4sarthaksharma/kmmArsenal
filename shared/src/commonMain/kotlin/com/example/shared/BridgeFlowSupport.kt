package com.example.shared

import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bridge infrastructure — used only by the generated iOS bridge, never by app code.
 *
 * Kotlin/Native delivers an exception across the ObjC boundary only when the throwing function
 * declares it via @Throws; kotlinx's `Flow.collect` declares nothing, so a failing flow
 * otherwise terminates the process (`Kotlin_ObjCExport_createContinuationArgumentImpl` →
 * `ProcessUnhandledException` → abort). Collecting through this wrapper gives the exception a
 * declared contract, letting the generated Swift `catch` it and emit the flow's error event.
 *
 * Every KMP module bridged with bridgegen must contain this function (same signature, its own
 * package). The generator skips it from the bridged API surface (function-typed parameter).
 */
@Throws(CancellationException::class, Throwable::class)
suspend fun bridgeCollectFlow(flow: Any, onEach: (Any?) -> Unit) {
    // Typed Any (not Flow<*>) so SKIE does not transform the parameter into its Swift flow
    // wrapper — the generated Swift passes the ObjC-side SkieKotlinFlow object, which IS a
    // kotlinx Flow on this side of the boundary.
    @Suppress("UNCHECKED_CAST")
    (flow as Flow<Any?>).collect { onEach(it) }
}
