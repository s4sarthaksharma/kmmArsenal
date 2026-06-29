import ExpoModulesCore
import Shared

public class KmpBridgeModule: Module {
  private let greeting = Greeting()
  private var counterTask: Task<Void, Never>?

  public func definition() -> ModuleDefinition {
    Name("KmpBridge")

    Events("onCounterUpdate")

    OnDestroy {
      self.counterTask?.cancel()
    }

    Function("greet") { (name: String) in
      return self.greeting.greet(name: name)
    }

    // Async variant — same KMP call, returned via a Promise on the JS side.
    AsyncFunction("greetAsync") { (name: String) in
      return self.greeting.greet(name: name)
    }

    Function("startCounter") {
      self.counterTask?.cancel()
      self.counterTask = Task { [weak self] in
        guard let self else { return }
        for await value in self.greeting.counterFlow() {
          self.sendEvent("onCounterUpdate", ["value": value.intValue])
        }
      }
    }

    Function("stopCounter") {
      self.counterTask?.cancel()
      self.counterTask = nil
    }

    AsyncFunction("delayedEcho") { (text: String, delayMs: Double, promise: Promise) in
      Task { [weak self] in
        guard let self else { return }
        do {
          let result = try await self.greeting.delayedEcho(text: text, delayMs: Int64(delayMs))
          promise.resolve(result)
        } catch {
          promise.reject(error)
        }
      }
    }
  }
}
