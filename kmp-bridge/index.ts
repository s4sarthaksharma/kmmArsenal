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

export default KmpBridgeModule;
