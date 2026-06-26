package expo.modules.kmpbridge

import com.example.shared.CounterCallback
import com.example.shared.CounterWatcher
import com.example.shared.Greeting
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KmpBridgeModule : Module() {
  private val greeting = Greeting()
  private val watcher = CounterWatcher(greeting)
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  override fun definition() = ModuleDefinition {
    Name("KmpBridge")

    Events("onCounterUpdate")

    OnDestroy {
      watcher.close()
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
      watcher.start(object : CounterCallback {
        override fun onValue(value: Int) {
          sendEvent("onCounterUpdate", mapOf("value" to value))
        }
      })
    }

    Function("stopCounter") {
      watcher.stop()
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
