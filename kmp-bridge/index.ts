// Public entry point for the KMP bridge module.
import KmpBridgeModule from './src/KmpBridgeModule';

/** Synchronous greeting from the KMP shared module. */
export function greet(name: string): string {
  return KmpBridgeModule.greet(name);
}

/** Async greeting from the KMP shared module. */
export function greetAsync(name: string): Promise<string> {
  return KmpBridgeModule.greetAsync(name);
}

/** Start emitting counter ticks via the onCounterUpdate event. */
export function startCounter(): void {
  KmpBridgeModule.startCounter();
}

/** Stop the counter flow. */
export function stopCounter(): void {
  KmpBridgeModule.stopCounter();
}

/** Waits delayMs milliseconds then returns text. */
export function delayedEcho(text: string, delayMs: number): Promise<string> {
  return KmpBridgeModule.delayedEcho(text, delayMs);
}

export default KmpBridgeModule;
