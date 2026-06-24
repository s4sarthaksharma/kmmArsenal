import { NativeModule, requireNativeModule } from 'expo';

declare class KmpBridgeModule extends NativeModule<{}> {
  /** Synchronous call into the KMP shared module. */
  greet(name: string): string;
  /** Async call into the KMP shared module, resolved via a Promise. */
  greetAsync(name: string): Promise<string>;
}

export default requireNativeModule<KmpBridgeModule>('KmpBridge');
