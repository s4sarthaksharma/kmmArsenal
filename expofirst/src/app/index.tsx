import { useEffect, useRef, useState } from "react";
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from "react-native";
import { Calculator } from "kmp-bridge/src/Calculator";
import { Greeting } from "kmp-bridge/src/Greeting";
import { AsyncWorker } from "kmp-bridge/src/AsyncWorker";
import { TickerService } from "kmp-bridge/src/TickerService";
import { TrafficLight, LightColor } from "kmp-bridge/src/TrafficLight";
import {
  FixtureAsyncApi,
  FixtureAnalytics,
  FixturePrimitivesApi,
  FixtureStatus,
  type FixtureUser,
  type FixtureResult,
} from "kmp-bridge/src/BridgeTypeFixture";

// ─── Design tokens ────────────────────────────────────────────────────────────
const C = {
  bg:        "#0F0F14",
  surface:   "#1A1A24",
  surface2:  "#242432",
  border:    "rgba(255,255,255,0.07)",
  accent:    "#A855F7",
  accentDim: "rgba(168,85,247,0.15)",
  text:      "#F1F5F9",
  muted:     "#64748B",
  green:     "#22C55E",
  yellow:    "#EAB308",
  red:       "#EF4444",
  repl:      "#0A0A10",
} as const;

const MONO = Platform.select({ ios: "Menlo", android: "monospace", default: "monospace" });

// ─── Primitive UI ─────────────────────────────────────────────────────────────

function FnCard({ name, children }: { name: string; children: React.ReactNode }) {
  return (
    <View style={s.fnCard}>
      <Text style={s.fnName}>{name}</Text>
      {children}
    </View>
  );
}

function ReplyRow({ value, live }: { value: string | number | boolean; live?: boolean }) {
  return (
    <View style={s.repl}>
      <Text style={s.replArrow}>→</Text>
      <Text style={[s.replValue, live && { color: C.green }]} numberOfLines={2}>
        {String(value)}
      </Text>
      {live && <View style={s.liveDot} />}
    </View>
  );
}

function Btn({ label, onPress, disabled, muted }: { label: string; onPress: () => void; disabled?: boolean; muted?: boolean }) {
  return (
    <TouchableOpacity
      style={[s.btn, muted && s.btnMuted, disabled && s.btnDisabled]}
      onPress={onPress}
      disabled={disabled}
      activeOpacity={0.7}
    >
      <Text style={[s.btnLabel, muted && s.btnLabelMuted]}>{label}</Text>
    </TouchableOpacity>
  );
}

function NumIn({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder?: string }) {
  return (
    <TextInput
      style={s.input}
      value={value}
      onChangeText={onChange}
      keyboardType="numeric"
      placeholder={placeholder ?? "0"}
      placeholderTextColor={C.muted}
      selectionColor={C.accent}
    />
  );
}

function StrIn({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder?: string }) {
  return (
    <TextInput
      style={s.input}
      value={value}
      onChangeText={onChange}
      placeholder={placeholder}
      placeholderTextColor={C.muted}
      selectionColor={C.accent}
    />
  );
}

function Row2({ children }: { children: React.ReactNode }) {
  return <View style={s.row2}>{children}</View>;
}

// ─── Tab: Greeting ────────────────────────────────────────────────────────────

function GreetingTab() {
  const [name, setName]             = useState("World");
  const [greetResult, setGreet]     = useState<string | null>(null);
  const [g2, setG2]                 = useState<string | null>(null);
  const [g3, setG3]                 = useState<string | null>(null);
  const [g4, setG4]                 = useState<string | null>(null);
  const [count, setCount]           = useState(0);
  const [counting, setCounting]     = useState(false);
  const [echoIn, setEchoIn]         = useState("");
  const [echoResult, setEchoResult] = useState<string | null>(null);
  const [echoPending, setEchoPending] = useState(false);
  const subRef = useRef<ReturnType<typeof Greeting.addCounterListener> | null>(null);

  useEffect(() => () => {
    Greeting.stopCounter();
    subRef.current?.remove();
  }, []);

  const toggleCounter = () => {
    if (counting) {
      Greeting.stopCounter();
      subRef.current?.remove();
      setCounting(false);
    } else {
      Greeting.startCounter();
      subRef.current = Greeting.addCounterListener(e => setCount(e.value));
      setCounting(true);
    }
  };

  const sendEcho = async () => {
    setEchoPending(true);
    try { setEchoResult(await Greeting.delayedEcho(echoIn, 2000)); }
    finally { setEchoPending(false); }
  };

  return (
    <ScrollView contentContainerStyle={s.tab} keyboardShouldPersistTaps="handled">

      <FnCard name="greet(name): String">
        <StrIn value={name} onChange={setName} placeholder="name" />
        <Btn label="Call" onPress={() => setGreet(Greeting.greet(name))} />
        {greetResult != null && <ReplyRow value={greetResult} />}
      </FnCard>

      <FnCard name="greeting2 / 3 / 4(): String">
        <Row2>
          <Btn label="greeting2()" onPress={() => setG2(Greeting.greeting2())} />
          <Btn label="greeting3()" onPress={() => setG3(Greeting.greeting3())} />
          <Btn label="greeting4()" onPress={() => setG4(Greeting.greeting4())} />
        </Row2>
        {g2 != null && <ReplyRow value={`g2 → ${g2}`} />}
        {g3 != null && <ReplyRow value={`g3 → ${g3}`} />}
        {g4 != null && <ReplyRow value={`g4 → ${g4}`} />}
      </FnCard>

      <FnCard name="counterFlow(): Flow<Int>">
        <Row2>
          <Btn label={counting ? "Stop" : "Start"} onPress={toggleCounter} muted={counting} />
        </Row2>
        <ReplyRow value={count} live={counting} />
      </FnCard>

      <FnCard name="delayedEcho(text, 2000): suspend">
        <StrIn value={echoIn} onChange={setEchoIn} placeholder="text to echo…" />
        <Btn label={echoPending ? "Waiting 2 s…" : "Send"} onPress={sendEcho} disabled={echoPending} />
        {echoResult != null && <ReplyRow value={echoResult} />}
      </FnCard>

    </ScrollView>
  );
}

// ─── Tab: Calculator ──────────────────────────────────────────────────────────

function CalculatorTab() {
  const [a, setA]       = useState("6");
  const [b, setB]       = useState("7");
  const [lbl, setLbl]   = useState("apples");
  const [neg, setNeg]   = useState(true);
  const na = Number(a) || 0;
  const nb = Number(b) || 0;

  return (
    <ScrollView contentContainerStyle={s.tab} keyboardShouldPersistTaps="handled">

      <View style={s.fnCard}>
        <Text style={s.fnName}>Inputs  a, b</Text>
        <Row2>
          <NumIn value={a} onChange={setA} placeholder="a" />
          <NumIn value={b} onChange={setB} placeholder="b" />
        </Row2>
      </View>

      <FnCard name="addInts(a, b): Int">
        <ReplyRow value={Calculator.addInts(na, nb)} />
      </FnCard>

      <FnCard name="addDoubles(a, b): Double">
        <ReplyRow value={Calculator.addDoubles(na, nb)} />
      </FnCard>

      <FnCard name="addLongs(a, b): Long">
        <ReplyRow value={Calculator.addLongs(na, nb)} />
      </FnCard>

      <FnCard name="multiplyFloats(a, b): Float">
        <ReplyRow value={Calculator.multiplyFloats(na, nb)} />
      </FnCard>

      <FnCard name="negate(value): Boolean">
        <View style={s.switchRow}>
          <Text style={s.switchLabel}>Input: {String(neg)}</Text>
          <Switch
            value={neg}
            onValueChange={setNeg}
            thumbColor={C.accent}
            trackColor={{ true: C.accentDim, false: C.surface2 }}
          />
        </View>
        <ReplyRow value={Calculator.negate(neg)} />
      </FnCard>

      <FnCard name="describe(label, count): String">
        <StrIn value={lbl} onChange={setLbl} placeholder="label" />
        <ReplyRow value={Calculator.describe(lbl, na)} />
      </FnCard>

      <FnCard name="reset(): Unit">
        <Btn label="reset()" onPress={() => Calculator.reset()} />
        <Text style={s.hint}>Returns Unit — bridge exercises the void path</Text>
      </FnCard>

    </ScrollView>
  );
}

// ─── Tab: AsyncWorker ─────────────────────────────────────────────────────────

function AsyncWorkerTab() {
  const [msg, setMsg]         = useState<string | null>(null);
  const [msgPending, setMsgP] = useState(false);
  const [sa, setSa]           = useState("12");
  const [sb, setSb]           = useState("30");
  const [sum, setSum]         = useState<string | null>(null);
  const [sumPending, setSumP] = useState(false);
  const [delayMs, setDelayMs] = useState("1500");
  const [flag, setFlag]       = useState<string | null>(null);
  const [flagPending, setFlagP] = useState(false);

  return (
    <ScrollView contentContainerStyle={s.tab} keyboardShouldPersistTaps="handled">

      <FnCard name="fetchMessage(): suspend String">
        <Btn label={msgPending ? "Fetching…" : "Call"} disabled={msgPending} onPress={async () => {
          setMsgP(true);
          try { setMsg(await AsyncWorker.fetchMessage()); } finally { setMsgP(false); }
        }} />
        {msg != null && <ReplyRow value={msg} />}
      </FnCard>

      <FnCard name="computeSum(a, b): suspend Int">
        <Row2>
          <NumIn value={sa} onChange={setSa} placeholder="a" />
          <NumIn value={sb} onChange={setSb} placeholder="b" />
        </Row2>
        <Btn label={sumPending ? "Computing…" : "Call"} disabled={sumPending} onPress={async () => {
          setSumP(true);
          try { setSum(String(await AsyncWorker.computeSum(Number(sa) || 0, Number(sb) || 0))); }
          finally { setSumP(false); }
        }} />
        {sum != null && <ReplyRow value={sum} />}
      </FnCard>

      <FnCard name="waitAndFlag(delayMs): suspend Boolean">
        <NumIn value={delayMs} onChange={setDelayMs} placeholder="delay ms" />
        <Btn label={flagPending ? `Waiting ${delayMs} ms…` : "Call"} disabled={flagPending} onPress={async () => {
          setFlagP(true);
          try { setFlag(String(await AsyncWorker.waitAndFlag(Number(delayMs) || 1000))); }
          finally { setFlagP(false); }
        }} />
        {flag != null && <ReplyRow value={flag} />}
      </FnCard>

    </ScrollView>
  );
}

// ─── Tab: TickerService ───────────────────────────────────────────────────────

function TickerServiceTab() {
  const [sec, setSec]       = useState(0);
  const [secOn, setSecOn]   = useState(false);
  const [stat, setStat]     = useState("—");
  const [statOn, setStatOn] = useState(false);
  const [pulse, setPulse]   = useState(false);
  const [pulseOn, setPulseOn] = useState(false);
  const secSub   = useRef<ReturnType<typeof TickerService.addSecondsListener> | null>(null);
  const statSub  = useRef<ReturnType<typeof TickerService.addStatusListener>  | null>(null);
  const pulseSub = useRef<ReturnType<typeof TickerService.addPulseListener>   | null>(null);

  useEffect(() => () => {
    TickerService.stopSeconds(); secSub.current?.remove();
    TickerService.stopStatus();  statSub.current?.remove();
    TickerService.stopPulse();   pulseSub.current?.remove();
  }, []);

  const toggleSec = () => {
    if (secOn) { TickerService.stopSeconds(); secSub.current?.remove(); setSecOn(false); }
    else { TickerService.startSeconds(); secSub.current = TickerService.addSecondsListener(e => setSec(e.value)); setSecOn(true); }
  };
  const toggleStat = () => {
    if (statOn) { TickerService.stopStatus(); statSub.current?.remove(); setStatOn(false); }
    else { TickerService.startStatus(); statSub.current = TickerService.addStatusListener(e => setStat(e.value)); setStatOn(true); }
  };
  const togglePulse = () => {
    if (pulseOn) { TickerService.stopPulse(); pulseSub.current?.remove(); setPulseOn(false); }
    else { TickerService.startPulse(); pulseSub.current = TickerService.addPulseListener(e => setPulse(e.value)); setPulseOn(true); }
  };

  return (
    <ScrollView contentContainerStyle={s.tab}>

      <FnCard name="secondsFlow(): Flow<Int>">
        <Btn label={secOn ? "Stop" : "Start"} onPress={toggleSec} muted={secOn} />
        <ReplyRow value={sec} live={secOn} />
      </FnCard>

      <FnCard name="statusFlow(): Flow<String>">
        <Btn label={statOn ? "Stop" : "Start"} onPress={toggleStat} muted={statOn} />
        <ReplyRow value={stat} live={statOn} />
      </FnCard>

      <FnCard name="pulseFlow(): Flow<Boolean>">
        <Btn label={pulseOn ? "Stop" : "Start"} onPress={togglePulse} muted={pulseOn} />
        <View style={[s.pulseBlob, { backgroundColor: pulse ? C.green : C.red, opacity: pulseOn ? 1 : 0.3 }]} />
        <ReplyRow value={String(pulse)} live={pulseOn} />
      </FnCard>

    </ScrollView>
  );
}

// ─── Tab: TrafficLight ────────────────────────────────────────────────────────

const LIGHT_HEX: Record<string, string> = {
  [LightColor.RED]:    "#EF4444",
  [LightColor.YELLOW]: "#EAB308",
  [LightColor.GREEN]:  "#22C55E",
};

function TrafficLightTab() {
  const [syncColor, setSyncColor]   = useState<LightColor | null>(null);
  const [selected, setSelected]     = useState<LightColor>(LightColor.RED);
  const [isStopRes, setIsStopRes]   = useState<boolean | null>(null);
  const [flowColor, setFlowColor]   = useState<LightColor | null>(null);
  const [flowOn, setFlowOn]         = useState(false);
  const flowSub = useRef<ReturnType<typeof TrafficLight.addColorListener> | null>(null);

  useEffect(() => () => {
    TrafficLight.stopColor();
    flowSub.current?.remove();
  }, []);

  const toggleFlow = () => {
    if (flowOn) { TrafficLight.stopColor(); flowSub.current?.remove(); setFlowOn(false); }
    else {
      TrafficLight.startColor();
      flowSub.current = TrafficLight.addColorListener(e => setFlowColor(e.value));
      setFlowOn(true);
    }
  };

  return (
    <ScrollView contentContainerStyle={s.tab}>

      <FnCard name="currentColor(): LightColor">
        <Btn label="Call" onPress={() => setSyncColor(TrafficLight.currentColor())} />
        {syncColor != null && (
          <View style={s.enumRow}>
            <View style={[s.colorOrb, { backgroundColor: LIGHT_HEX[syncColor] ?? C.muted }]} />
            <ReplyRow value={syncColor} />
          </View>
        )}
      </FnCard>

      <FnCard name="isStop(color): Boolean">
        <Row2>
          {([LightColor.RED, LightColor.YELLOW, LightColor.GREEN] as LightColor[]).map(c => (
            <TouchableOpacity
              key={c}
              style={[s.colorChip, selected === c && { borderColor: LIGHT_HEX[c], backgroundColor: LIGHT_HEX[c] + "22" }]}
              onPress={() => { setSelected(c); setIsStopRes(TrafficLight.isStop(c)); }}
              activeOpacity={0.7}
            >
              <View style={[s.chipDot, { backgroundColor: LIGHT_HEX[c] }]} />
              <Text style={s.chipLabel}>{c}</Text>
            </TouchableOpacity>
          ))}
        </Row2>
        {isStopRes != null && <ReplyRow value={isStopRes} />}
      </FnCard>

      <FnCard name="colorFlow(): Flow<LightColor>">
        <Btn label={flowOn ? "Stop" : "Start"} onPress={toggleFlow} muted={flowOn} />
        {flowColor != null && (
          <View style={s.enumRow}>
            <View style={[s.colorOrb, { backgroundColor: LIGHT_HEX[flowColor] ?? C.muted }]} />
            <ReplyRow value={flowColor} live={flowOn} />
          </View>
        )}
      </FnCard>

    </ScrollView>
  );
}

// ─── Tab: Fixture ─────────────────────────────────────────────────────────────

function JsonRow({ value }: { value: unknown }) {
  return (
    <View style={s.repl}>
      <Text style={s.replArrow}>→</Text>
      <Text style={[s.replValue, { fontSize: 11 }]} numberOfLines={6}>
        {JSON.stringify(value, null, 2)}
      </Text>
    </View>
  );
}

function FixtureTab() {
  // Primitives
  const [primStr,  setPrimStr]  = useState<string  | null>(null);
  const [primInt,  setPrimInt]  = useState<number  | null>(null);
  const [primLong, setPrimLong] = useState<number  | null>(null);
  const [primBool, setPrimBool] = useState<boolean | null>(null);
  const [nullInt,  setNullInt]  = useState<number  | null | undefined>(undefined);

  // Async — fetchUser
  const [userId,      setUserId]      = useState("test-id");
  const [user,        setUser]        = useState<FixtureUser | null>(null);
  const [userPending, setUserPending] = useState(false);

  // Async — fetchNullableUser
  const [nullUser,        setNullUser]        = useState<FixtureUser | null | undefined>(undefined);
  const [nullUserPending, setNullUserPending] = useState(false);

  // Async — deleteUser
  const [deleted, setDeleted] = useState<string | null>(null);

  // Flow — observeStatus
  const [status,   setStatus]   = useState<string | null>(null);
  const [statusOn, setStatusOn] = useState(false);
  const statusSub = useRef<ReturnType<typeof FixtureAsyncApi.addObserveStatusListener> | null>(null);

  // Flow — observeUser
  const [liveUser,   setLiveUser]   = useState<FixtureUser | null>(null);
  const [liveUserOn, setLiveUserOn] = useState(false);
  const liveUserSub = useRef<ReturnType<typeof FixtureAsyncApi.addObserveUserListener> | null>(null);

  // Flow — observeResult
  const [result,   setResult]   = useState<FixtureResult | null>(null);
  const [resultOn, setResultOn] = useState(false);
  const resultSub = useRef<ReturnType<typeof FixtureAsyncApi.addObserveResultListener> | null>(null);

  // Object — FixtureAnalytics
  const [tracked,      setTracked]      = useState<string  | null>(null);
  const [flushed,      setFlushed]      = useState<boolean | null>(null);
  const [flushPending, setFlushPending] = useState(false);
  const [event,        setEvent]        = useState<string  | null>(null);
  const [eventsOn,     setEventsOn]     = useState(false);
  const eventSub = useRef<ReturnType<typeof FixtureAnalytics.addEventsListener> | null>(null);

  useEffect(() => () => {
    FixtureAsyncApi.stopObserveStatus();  statusSub.current?.remove();
    FixtureAsyncApi.stopObserveUser();    liveUserSub.current?.remove();
    FixtureAsyncApi.stopObserveResult();  resultSub.current?.remove();
    FixtureAnalytics.stopEvents();        eventSub.current?.remove();
  }, []);

  const toggleStatus = () => {
    if (statusOn) {
      FixtureAsyncApi.stopObserveStatus(); statusSub.current?.remove(); setStatusOn(false);
    } else {
      FixtureAsyncApi.startObserveStatus();
      statusSub.current = FixtureAsyncApi.addObserveStatusListener(e => setStatus(e.value));
      setStatusOn(true);
    }
  };

  const toggleLiveUser = () => {
    if (liveUserOn) {
      FixtureAsyncApi.stopObserveUser(); liveUserSub.current?.remove(); setLiveUserOn(false);
    } else {
      FixtureAsyncApi.startObserveUser();
      liveUserSub.current = FixtureAsyncApi.addObserveUserListener(e => setLiveUser(e.value));
      setLiveUserOn(true);
    }
  };

  const toggleResult = () => {
    if (resultOn) {
      FixtureAsyncApi.stopObserveResult(); resultSub.current?.remove(); setResultOn(false);
    } else {
      FixtureAsyncApi.startObserveResult();
      resultSub.current = FixtureAsyncApi.addObserveResultListener(e => setResult(e.value));
      setResultOn(true);
    }
  };

  const toggleEvents = () => {
    if (eventsOn) {
      FixtureAnalytics.stopEvents(); eventSub.current?.remove(); setEventsOn(false);
    } else {
      FixtureAnalytics.startEvents();
      eventSub.current = FixtureAnalytics.addEventsListener(e => setEvent(e.value));
      setEventsOn(true);
    }
  };

  return (
    <ScrollView contentContainerStyle={s.tab} keyboardShouldPersistTaps="handled">

      {/* ── Primitives ─────────────────────────────────────── */}
      <Text style={s.section}>FixturePrimitivesApi</Text>

      <FnCard name="returnString / Int / Long / Boolean">
        <Row2>
          <Btn label="String" onPress={() => setPrimStr(FixturePrimitivesApi.returnString())} />
          <Btn label="Int"    onPress={() => setPrimInt(FixturePrimitivesApi.returnInt())} />
          <Btn label="Long"   onPress={() => setPrimLong(FixturePrimitivesApi.returnLong())} />
          <Btn label="Bool"   onPress={() => setPrimBool(FixturePrimitivesApi.returnBoolean())} />
        </Row2>
        {primStr  != null && <ReplyRow value={`String → ${primStr}`} />}
        {primInt  != null && <ReplyRow value={`Int    → ${primInt}`} />}
        {primLong != null && <ReplyRow value={`Long   → ${primLong}`} />}
        {primBool != null && <ReplyRow value={`Bool   → ${primBool}`} />}
      </FnCard>

      <FnCard name="returnNullableInt(): Int?">
        <Btn label="Call" onPress={() => setNullInt(FixturePrimitivesApi.returnNullableInt())} />
        {nullInt !== undefined && <ReplyRow value={`→ ${nullInt}`} />}
      </FnCard>

      {/* ── FixtureAsyncApi — suspend ──────────────────────── */}
      <Text style={s.section}>FixtureAsyncApi — suspend</Text>

      <FnCard name="fetchUser(id): FixtureUser  [Record return]">
        <StrIn value={userId} onChange={setUserId} placeholder="user id" />
        <Btn label={userPending ? "Fetching…" : "fetchUser"} disabled={userPending} onPress={async () => {
          setUserPending(true);
          try { setUser(await FixtureAsyncApi.fetchUser(userId)); }
          finally { setUserPending(false); }
        }} />
        {user != null && <JsonRow value={user} />}
      </FnCard>

      <FnCard name="fetchNullableUser(id): FixtureUser?">
        <Btn label={nullUserPending ? "Fetching…" : "fetchNullableUser"} disabled={nullUserPending}
          onPress={async () => {
            setNullUserPending(true);
            try { setNullUser(await FixtureAsyncApi.fetchNullableUser("nullable-test")); }
            finally { setNullUserPending(false); }
          }} />
        {nullUser !== undefined && <JsonRow value={nullUser} />}
      </FnCard>

      <FnCard name="deleteUser(id): Unit">
        <Btn label="deleteUser" onPress={async () => {
          await FixtureAsyncApi.deleteUser("del-123");
          setDeleted("done (Unit returned)");
        }} />
        {deleted != null && <ReplyRow value={deleted} />}
      </FnCard>

      {/* ── FixtureAsyncApi — Flows ────────────────────────── */}
      <Text style={s.section}>FixtureAsyncApi — Flows</Text>

      <FnCard name="observeStatus(): Flow<FixtureStatus>">
        <Btn label={statusOn ? "Stop" : "Start"} onPress={toggleStatus} muted={statusOn} />
        {status != null && <ReplyRow value={status} live={statusOn} />}
      </FnCard>

      <FnCard name="observeUser(): Flow<FixtureUser>  [Record flow]">
        <Btn label={liveUserOn ? "Stop" : "Start"} onPress={toggleLiveUser} muted={liveUserOn} />
        {liveUser != null && <JsonRow value={liveUser} />}
      </FnCard>

      <FnCard name="observeResult(): Flow<FixtureResult>  [Sealed flow]">
        <Btn label={resultOn ? "Stop" : "Start"} onPress={toggleResult} muted={resultOn} />
        {result != null && <JsonRow value={result} />}
      </FnCard>

      {/* ── FixtureAnalytics (object singleton) ───────────── */}
      <Text style={s.section}>FixtureAnalytics (object singleton)</Text>

      <FnCard name="track(event): Unit">
        <Btn label="track('test_event')" onPress={() => {
          FixtureAnalytics.track("test_event");
          setTracked("called (Unit)");
        }} />
        {tracked != null && <ReplyRow value={tracked} />}
      </FnCard>

      <FnCard name="flush(): suspend Boolean">
        <Btn label={flushPending ? "Flushing…" : "flush()"} disabled={flushPending} onPress={async () => {
          setFlushPending(true);
          try { setFlushed(await FixtureAnalytics.flush()); }
          finally { setFlushPending(false); }
        }} />
        {flushed != null && <ReplyRow value={flushed} />}
      </FnCard>

      <FnCard name="events(): Flow<String>">
        <Btn label={eventsOn ? "Stop" : "Start"} onPress={toggleEvents} muted={eventsOn} />
        {event != null && <ReplyRow value={event} live={eventsOn} />}
      </FnCard>

    </ScrollView>
  );
}

// ─── Root screen ─────────────────────────────────────────────────────────────

type Tab = "Greeting" | "Calculator" | "AsyncWorker" | "TickerService" | "TrafficLight" | "Fixture";
const TABS: Tab[] = ["Greeting", "Calculator", "AsyncWorker", "TickerService", "TrafficLight", "Fixture"];

export default function Index() {
  const [tab, setTab] = useState<Tab>("Greeting");

  return (
    <KeyboardAvoidingView
      style={s.root}
      behavior={Platform.OS === "ios" ? "padding" : "height"}
    >
      {/* Tab bar */}
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={s.tabBar}
        contentContainerStyle={s.tabBarInner}
      >
        {TABS.map(t => (
          <TouchableOpacity
            key={t}
            style={[s.tabChip, tab === t && s.tabChipActive]}
            onPress={() => setTab(t)}
            activeOpacity={0.75}
          >
            <Text style={[s.tabLabel, tab === t && s.tabLabelActive]}>{t}</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>

      {/* Content — each tab mounts/unmounts so useEffect cleanup fires on switch */}
      {tab === "Greeting"      && <GreetingTab />}
      {tab === "Calculator"    && <CalculatorTab />}
      {tab === "AsyncWorker"   && <AsyncWorkerTab />}
      {tab === "TickerService" && <TickerServiceTab />}
      {tab === "TrafficLight"  && <TrafficLightTab />}
      {tab === "Fixture"       && <FixtureTab />}
    </KeyboardAvoidingView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: C.bg,
  },

  // Tab bar
  tabBar: {
    flexGrow: 0,
    borderBottomWidth: 1,
    borderBottomColor: C.border,
    backgroundColor: C.bg,
  },
  tabBarInner: {
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 8,
  },
  tabChip: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: C.border,
    backgroundColor: "transparent",
  },
  tabChipActive: {
    backgroundColor: C.accentDim,
    borderColor: C.accent,
  },
  tabLabel: {
    fontSize: 13,
    fontWeight: "500",
    color: C.muted,
    fontFamily: MONO,
  },
  tabLabelActive: {
    color: C.accent,
  },

  // Tab scroll content
  tab: {
    padding: 16,
    gap: 12,
    paddingBottom: 60,
  },

  // Function card
  fnCard: {
    backgroundColor: C.surface,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: C.border,
    padding: 14,
    gap: 10,
  },
  fnName: {
    fontSize: 13,
    fontFamily: MONO,
    color: C.accent,
    letterSpacing: 0.2,
  },

  // REPL output row
  repl: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: C.repl,
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
    gap: 8,
  },
  replArrow: {
    fontSize: 13,
    color: C.muted,
    fontFamily: MONO,
  },
  replValue: {
    flex: 1,
    fontSize: 15,
    fontFamily: MONO,
    color: C.text,
  },
  liveDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
    backgroundColor: C.green,
  },

  // Button
  btn: {
    backgroundColor: C.accent,
    borderRadius: 8,
    paddingVertical: 9,
    paddingHorizontal: 16,
    alignItems: "center",
    alignSelf: "flex-start",
  },
  btnMuted: {
    backgroundColor: C.surface2,
  },
  btnDisabled: {
    opacity: 0.45,
  },
  btnLabel: {
    fontSize: 13,
    fontWeight: "600",
    color: "#fff",
    fontFamily: MONO,
  },
  btnLabelMuted: {
    color: C.muted,
  },

  // Input
  input: {
    backgroundColor: C.surface2,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 9,
    fontSize: 14,
    fontFamily: MONO,
    color: C.text,
  },

  // Layout helpers
  row2: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 8,
  },
  switchRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  switchLabel: {
    fontSize: 13,
    fontFamily: MONO,
    color: C.muted,
  },

  // Ticker pulse blob
  pulseBlob: {
    width: 32,
    height: 32,
    borderRadius: 16,
  },

  // TrafficLight
  enumRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
  },
  colorOrb: {
    width: 20,
    height: 20,
    borderRadius: 10,
  },
  colorChip: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: C.border,
    backgroundColor: C.surface2,
  },
  chipDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  chipLabel: {
    fontSize: 12,
    fontFamily: MONO,
    color: C.text,
    fontWeight: "600",
  },

  // Section header (used in FixtureTab)
  section: {
    fontSize: 11,
    fontFamily: MONO,
    color: C.muted,
    letterSpacing: 0.8,
    textTransform: "uppercase",
    marginTop: 4,
  },

  // Misc
  hint: {
    fontSize: 11,
    color: C.muted,
    fontStyle: "italic",
  },
});
