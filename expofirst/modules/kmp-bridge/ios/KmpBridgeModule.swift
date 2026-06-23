import ExpoModulesCore
import Shared

public class KmpBridgeModule: Module {
  // Single instance of the shared KMP logic, reused across calls.
  private let greeting = Greeting()

  public func definition() -> ModuleDefinition {
    Name("KmpBridge")

    // Synchronous call straight into the KMP `commonMain` logic.
    Function("greet") { (name: String) in
      return self.greeting.greet(name: name)
    }

    // Async variant — same KMP call, returned via a Promise on the JS side.
    AsyncFunction("greetAsync") { (name: String) in
      return self.greeting.greet(name: name)
    }
  }
}
