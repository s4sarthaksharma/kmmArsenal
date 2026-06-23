import { useEffect, useState } from "react";
import { Text, View, StyleSheet, Platform } from "react-native";
import { greet, greetAsync } from "../../modules/kmp-bridge";

export default function Index() {
  const [sync, setSync] = useState<string>("…");
  const [async, setAsync] = useState<string>("…");

  useEffect(() => {
    // Synchronous bridge call straight into the KMP shared module.
    setSync(greet("React Native"));
    // Async bridge call (Promise-based).
    greetAsync("React Native (async)").then(setAsync);
  }, []);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>KMP ↔ React Native</Text>
      <Text style={styles.label}>Running on: {Platform.OS}</Text>

      <View style={styles.card}>
        <Text style={styles.caption}>greet() — sync</Text>
        <Text style={styles.result}>{sync}</Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.caption}>greetAsync() — Promise</Text>
        <Text style={styles.result}>{async}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 24,
    gap: 16,
  },
  title: {
    fontSize: 22,
    fontWeight: "700",
  },
  label: {
    fontSize: 14,
    opacity: 0.6,
  },
  card: {
    width: "100%",
    padding: 16,
    borderRadius: 12,
    backgroundColor: "rgba(127,127,127,0.12)",
    gap: 6,
  },
  caption: {
    fontSize: 12,
    fontWeight: "600",
    opacity: 0.5,
    textTransform: "uppercase",
  },
  result: {
    fontSize: 16,
  },
});
