import { registerWebModule, NativeModule } from 'expo';

// Web fallback — there's no KMP native code on web, so we reimplement the
// shared greeting in JS to keep the same API surface.
class KmpBridgeModule extends NativeModule<{}> {
  greet(name: string): string {
    return `Hello, ${name}! 👋 — from Web (JS fallback, no KMP)`;
  }
  async greetAsync(name: string): Promise<string> {
    return this.greet(name);
  }
}

export default registerWebModule(KmpBridgeModule, 'KmpBridgeModule');
