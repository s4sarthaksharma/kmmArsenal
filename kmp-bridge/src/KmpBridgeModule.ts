import { NativeModule, requireNativeModule } from 'expo';

type KmpBridgeEvents = {
  onCounterUpdate: (event: { value: number }) => void;
};

declare class KmpBridgeModule extends NativeModule<KmpBridgeEvents> {
  /** Synchronous call into the KMP shared module. */
  greet(name: string): string;
  /** Async call into the KMP shared module, resolved via a Promise. */
  greetAsync(name: string): Promise<string>;
  /** Start emitting counter updates via the onCounterUpdate event. */
  startCounter(): void;
  /** Stop the counter flow. */
  stopCounter(): void;
  /** Waits delayMs milliseconds then returns text — suspend function demo. */
  delayedEcho(text: string, delayMs: number): Promise<string>;
}

export default requireNativeModule<KmpBridgeModule>('KmpBridge');
