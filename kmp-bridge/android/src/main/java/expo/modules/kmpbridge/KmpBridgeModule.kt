package expo.modules.kmpbridge

import com.example.shared.Greeting
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KmpBridgeModule : Module() {
  private val greeting = Greeting()
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var counterJob: Job? = null

  override fun definition() = ModuleDefinition {
    Name("KmpBridge")

    Events("onCounterUpdate")

    OnDestroy {
      scope.cancel()
    }

    Function("greet") { name: String ->
      greeting.greet(name)
    }

    // Async variant — same KMP call, returned via a Promise on the JS side.
    AsyncFunction("greetAsync") { name: String ->
      greeting.greet(name)
    }

    Function("startCounter") {
      counterJob?.cancel()
      counterJob = scope.launch {
        greeting.counterFlow().collect { value ->
          sendEvent("onCounterUpdate", mapOf("value" to value))
        }
      }
    }

    Function("stopCounter") {
      counterJob?.cancel()
      counterJob = null
    }

    AsyncFunction("delayedEcho") { text: String, delayMs: Double, promise: Promise ->
      scope.launch {
        try {
          promise.resolve(greeting.delayedEcho(text, delayMs.toLong()))
        } catch (e: Exception) {
          promise.reject("DELAYED_ECHO_ERROR", e.message, e)
        }
      }
    }
  }
}
