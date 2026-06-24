package expo.modules.kmpbridge

import com.example.shared.Greeting
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class KmpBridgeModule : Module() {
  // Single instance of the shared KMP logic, reused across calls.
  private val greeting = Greeting()

  override fun definition() = ModuleDefinition {
    Name("KmpBridge")

    // Synchronous call straight into the KMP `commonMain` logic.
    Function("greet") { name: String ->
      greeting.greet(name)
    }

    // Async variant — same KMP call, returned via a Promise on the JS side.
    AsyncFunction("greetAsync") { name: String ->
      greeting.greet(name)
    }
  }
}
