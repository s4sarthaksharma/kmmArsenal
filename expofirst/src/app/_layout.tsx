import { Stack } from "expo-router";
import { LogBox } from "react-native";

// expo-router's useLinking resolves the initial URL in a .then() callback
// before ContextNavigator has fully mounted — known framework-level timing issue.
LogBox.ignoreLogs([
  "Can't perform a React state update on a component that hasn't mounted yet.",
]);

export default function RootLayout() {
  return <Stack />;
}
