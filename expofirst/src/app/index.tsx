import { useEffect, useState } from "react";
import { Text, View, TextInput, TouchableOpacity, StyleSheet, Platform, KeyboardAvoidingView, ScrollView } from "react-native";
import { greet, greetAsync, startCounter, stopCounter, delayedEcho } from "kmp-bridge";
import KmpBridgeModule from "kmp-bridge";

export default function Index() {
  const [sync, setSync] = useState<string>("…");
  const [async, setAsync] = useState<string>("…");
  const [count, setCount] = useState<number>(0);
  const [echoInput, setEchoInput] = useState<string>("");
  const [echoResult, setEchoResult] = useState<string>("…");
  const [echoPending, setEchoPending] = useState<boolean>(false);

  useEffect(() => {
    setSync(greet("React Native"));
    greetAsync("React Native (async)").then(setAsync);
    startCounter();
    const sub = KmpBridgeModule.addListener("onCounterUpdate", ({ value }: { value: number }) => {
      setCount(value);
    });
    return () => {
      stopCounter();
      sub.remove();
    };
  }, []);

  const triggerEcho = async () => {
    setEchoPending(true);
    const result = await delayedEcho(echoInput, 2000);
    setEchoResult(result);
    setEchoPending(false);
  };

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === "ios" ? "padding" : "height"}
    >
      <ScrollView
        contentContainerStyle={styles.container}
        keyboardShouldPersistTaps="handled"
      >
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

        <View style={styles.card}>
          <Text style={styles.caption}>counterFlow() — Kotlin Flow</Text>
          <Text style={styles.result}>{count}</Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.caption}>delayedEcho() — suspend fun (2s)</Text>
          <TextInput
            style={styles.input}
            value={echoInput}
            onChangeText={setEchoInput}
            placeholder="Type something…"
            placeholderTextColor="rgba(127,127,127,0.5)"
          />
          <TouchableOpacity
            style={[styles.button, echoPending && styles.buttonDisabled]}
            onPress={triggerEcho}
            disabled={echoPending}
          >
            <Text style={styles.buttonText}>
              {echoPending ? "Waiting 2s…" : "Send"}
            </Text>
          </TouchableOpacity>
          <Text style={styles.result}>{echoResult}</Text>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: {
    flex: 1,
  },
  container: {
    flexGrow: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 24,
    paddingBottom: 200,
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
  input: {
    borderWidth: 1,
    borderColor: "rgba(127,127,127,0.3)",
    borderRadius: 8,
    padding: 10,
    fontSize: 15,
    color: "#000",
  },
  button: {
    backgroundColor: "#007AFF",
    borderRadius: 8,
    paddingVertical: 10,
    alignItems: "center",
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  buttonText: {
    color: "#fff",
    fontWeight: "600",
    fontSize: 15,
  },
});
