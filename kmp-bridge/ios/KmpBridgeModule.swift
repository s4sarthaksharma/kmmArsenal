import ExpoModulesCore
import Shared

// Bridges Swift closure into the Kotlin CounterCallback protocol so the
// Kotlin coroutine can call back into Swift without crossing the async boundary.
private class SwiftCounterCallback: NSObject, CounterCallback {
  let handler: (Int32) -> Void
  init(_ handler: @escaping (Int32) -> Void) { self.handler = handler }
  func onValue(value: Int32) { handler(value) }
}

public class KmpBridgeModule: Module {
  private let greeting = Greeting()
  private lazy var watcher = CounterWatcher(greeting: greeting)

  public func definition() -> ModuleDefinition {
    Name("KmpBridge")

    Events("onCounterUpdate")

    OnDestroy {
      self.watcher.close()
    }

    Function("greet") { (name: String) in
      return self.greeting.greet(name: name)
    }

    // Async variant — same KMP call, returned via a Promise on the JS side.
    AsyncFunction("greetAsync") { (name: String) in
      return self.greeting.greet(name: name)
    }

    Function("startCounter") {
      self.watcher.start(callback: SwiftCounterCallback { [weak self] value in
        self?.sendEvent("onCounterUpdate", ["value": Int(value)])
      })
    }

    Function("stopCounter") {
      self.watcher.stop()
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
