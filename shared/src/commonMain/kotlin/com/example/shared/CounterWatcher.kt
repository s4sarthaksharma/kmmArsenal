package com.example.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

interface CounterCallback {
    fun onValue(value: Int)
}

class CounterWatcher(private val greeting: Greeting) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    fun start(callback: CounterCallback) {
        job?.cancel()
        job = scope.launch {
            greeting.counterFlow().collect { callback.onValue(it) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun close() {
        scope.cancel()
    }
}
