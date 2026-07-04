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
import { Greeting, GreetingKt } from "kmp-bridge/src/Greeting";
import { AsyncWorker } from "kmp-bridge/src/AsyncWorker";
import { TickerService } from "kmp-bridge/src/TickerService";
import { TrafficLight, LightColor } from "kmp-bridge/src/TrafficLight";
import {
  BridgeTypeFixture,
  FixtureAsyncApi,
  FixtureAnalytics,
  FixturePrimitivesApi,
  FixtureInterfaceApi,
  FixtureGenericApi,
  FixtureRepository,
  FixtureBaseProcessor,
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
  const gRef   = useRef<Greeting | null>(null);
  const subRef = useRef<ReturnType<Greeting["addCounterListener"]> | null>(null);

  useEffect(() => {
    gRef.current = Greeting.create();
    return () => {
      gRef.current?.stopCounter();
      subRef.current?.remove();
      gRef.current?.destroy();
      gRef.current = null;
    };
  }, []);

  const toggleCounter = () => {
    const g = gRef.current;
    if (!g) return;
    if (counting) {
      g.stopCounter();
      subRef.current?.remove();
      setCounting(false);
    } else {
      g.startCounter();
      subRef.current = g.addCounterListener(e => setCount(e.value));
      setCounting(true);
    }
  };

  const sendEcho = async () => {
    const g = gRef.current;
    if (!g) return;
    setEchoPending(true);
    try { setEchoResult(await g.delayedEcho(echoIn, 2000)); }
    finally { setEchoPending(false); }
  };

  return (
    <ScrollView contentContainerStyle={s.tab} keyboardShouldPersistTaps="handled">

      <FnCard name="greet(name): String">
        <StrIn value={name} onChange={setName} placeholder="name" />
        <Btn label="Call" onPress={() => setGreet(gRef.current?.greet(name) ?? "")} />
        {greetResult != null && <ReplyRow value={greetResult} />}
      </FnCard>

      <FnCard name="greeting2 / 3 / 4(): String">
        <Row2>
          <Btn label="greeting2()" onPress={() => setG2(gRef.current?.greeting2() ?? "")} />
          <Btn label="greeting3()" onPress={() => setG3(gRef.current?.greeting3() ?? "")} />
          <Btn label="greeting4()" onPress={() => setG4(gRef.current?.greeting4() ?? "")} />
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

      {/* ── GreetingKt (file-scope) ────────────────────────── */}
      <Text style={s.section}>GreetingKt (file-scope)</Text>

      <FnCard name="saidHello(): String">
        <Btn label="Call" onPress={() => setGreet(GreetingKt.saidHello())} />
        {greetResult != null && <ReplyRow value={greetResult} />}
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
  const [calc, setCalc] = useState<Calculator | null>(null);
  const na = Number(a) || 0;
  const nb = Number(b) || 0;

  useEffect(() => {
    const c = Calculator.create();
    setCalc(c);
    return () => { c.destroy(); setCalc(null); };
  }, []);

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
        <ReplyRow value={calc?.addInts(na, nb) ?? 0} />
      </FnCard>

      <FnCard name="addDoubles(a, b): Double">
        <ReplyRow value={calc?.addDoubles(na, nb) ?? 0} />
      </FnCard>

      <FnCard name="addLongs(a, b): Long">
        <ReplyRow value={calc?.addLongs(na, nb) ?? 0} />
      </FnCard>

      <FnCard name="multiplyFloats(a, b): Float">
        <ReplyRow value={calc?.multiplyFloats(na, nb) ?? 0} />
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
        <ReplyRow value={calc?.negate(neg) ?? false} />
      </FnCard>

      <FnCard name="describe(label, count): String">
        <StrIn value={lbl} onChange={setLbl} placeholder="label" />
        <ReplyRow value={calc?.describe(lbl, na) ?? ""} />
      </FnCard>

      <FnCard name="reset(): Unit">
        <Btn label="reset()" onPress={() => calc?.reset()} />
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
  const awRef = useRef<AsyncWorker | null>(null);

  useEffect(() => {
    awRef.current = AsyncWorker.create();
    return () => { awRef.current?.destroy(); awRef.current = null; };
  }, []);

  return (
    <ScrollView contentContainerStyle={s.tab} keyboardShouldPersistTaps="handled">

      <FnCard name="fetchMessage(): suspend String">
        <Btn label={msgPending ? "Fetching…" : "Call"} disabled={msgPending} onPress={async () => {
          setMsgP(true);
          try { setMsg(await awRef.current?.fetchMessage() ?? ""); } finally { setMsgP(false); }
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
          try { setSum(String(await awRef.current?.computeSum(Number(sa) || 0, Number(sb) || 0) ?? 0)); }
          finally { setSumP(false); }
        }} />
        {sum != null && <ReplyRow value={sum} />}
      </FnCard>

      <FnCard name="waitAndFlag(delayMs): suspend Boolean">
        <NumIn value={delayMs} onChange={setDelayMs} placeholder="delay ms" />
        <Btn label={flagPending ? `Waiting ${delayMs} ms…` : "Call"} disabled={flagPending} onPress={async () => {
          setFlagP(true);
          try { setFlag(String(await awRef.current?.waitAndFlag(Number(delayMs) || 1000) ?? false)); }
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
  const tsRef    = useRef<TickerService | null>(null);
  const secSub   = useRef<ReturnType<TickerService["addSecondsListener"]> | null>(null);
  const statSub  = useRef<ReturnType<TickerService["addStatusListener"]>  | null>(null);
  const pulseSub = useRef<ReturnType<TickerService["addPulseListener"]>   | null>(null);

  useEffect(() => {
    tsRef.current = TickerService.create();
    return () => {
      tsRef.current?.stopSeconds(); secSub.current?.remove();
      tsRef.current?.stopStatus();  statSub.current?.remove();
      tsRef.current?.stopPulse();   pulseSub.current?.remove();
      tsRef.current?.destroy();
      tsRef.current = null;
    };
  }, []);

  const toggleSec = () => {
    const ts = tsRef.current;
    if (!ts) return;
    if (secOn) { ts.stopSeconds(); secSub.current?.remove(); setSecOn(false); }
    else { ts.startSeconds(); secSub.current = ts.addSecondsListener(e => setSec(e.value)); setSecOn(true); }
  };
  const toggleStat = () => {
    const ts = tsRef.current;
    if (!ts) return;
    if (statOn) { ts.stopStatus(); statSub.current?.remove(); setStatOn(false); }
    else { ts.startStatus(); statSub.current = ts.addStatusListener(e => setStat(e.value)); setStatOn(true); }
  };
  const togglePulse = () => {
    const ts = tsRef.current;
    if (!ts) return;
    if (pulseOn) { ts.stopPulse(); pulseSub.current?.remove(); setPulseOn(false); }
    else { ts.startPulse(); pulseSub.current = ts.addPulseListener(e => setPulse(e.value)); setPulseOn(true); }
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
  const tlRef   = useRef<TrafficLight | null>(null);
  const flowSub = useRef<ReturnType<TrafficLight["addColorListener"]> | null>(null);

  useEffect(() => {
    tlRef.current = TrafficLight.create();
    return () => {
      tlRef.current?.stopColor();
      flowSub.current?.remove();
      tlRef.current?.destroy();
      tlRef.current = null;
    };
  }, []);

  const toggleFlow = () => {
    const tl = tlRef.current;
    if (!tl) return;
    if (flowOn) { tl.stopColor(); flowSub.current?.remove(); setFlowOn(false); }
    else {
      tl.startColor();
      flowSub.current = tl.addColorListener(e => setFlowColor(e.value));
      setFlowOn(true);
    }
  };

  return (
    <ScrollView contentContainerStyle={s.tab}>

      <FnCard name="currentColor(): LightColor">
        <Btn label="Call" onPress={() => setSyncColor(tlRef.current?.currentColor() ?? null)} />
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
              onPress={() => { setSelected(c); setIsStopRes(tlRef.current?.isStop(c) ?? null); }}
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
  const statusSub = useRef<ReturnType<FixtureAsyncApi["addObserveStatusListener"]> | null>(null);

  // Flow — observeUser
  const [liveUser,   setLiveUser]   = useState<FixtureUser | null>(null);
  const [liveUserOn, setLiveUserOn] = useState(false);
  const liveUserSub = useRef<ReturnType<FixtureAsyncApi["addObserveUserListener"]> | null>(null);

  // Flow — observeResult
  const [result,   setResult]   = useState<FixtureResult | null>(null);
  const [resultOn, setResultOn] = useState(false);
  const resultSub = useRef<ReturnType<FixtureAsyncApi["addObserveResultListener"]> | null>(null);

  // Object — FixtureAnalytics
  const [tracked,      setTracked]      = useState<string  | null>(null);
  const [flushed,      setFlushed]      = useState<boolean | null>(null);
  const [flushPending, setFlushPending] = useState(false);
  const [event,        setEvent]        = useState<string  | null>(null);
  const [eventsOn,     setEventsOn]     = useState(false);
  const eventSub = useRef<ReturnType<typeof FixtureAnalytics.addEventsListener> | null>(null);

  // File-scope — BridgeTypeFixture (no create/destroy, direct calls)
  const [fsGreet,       setFsGreet]       = useState<string  | null>(null);
  const [fsAdd,         setFsAdd]         = useState<number  | null>(null);
  const [fsEcho,        setFsEcho]        = useState<string | null | undefined>(undefined);
  const [fsEchoIn,      setFsEchoIn]      = useState("");
  const [fsUser,        setFsUser]        = useState<FixtureUser | null>(null);
  const [fsUserPending, setFsUserPending] = useState(false);
  const [fsVersion,     setFsVersion]     = useState<string  | null>(null);
  const [fsCounter,     setFsCounter]     = useState<number  | null>(null);
  const [fsCounterOn,   setFsCounterOn]   = useState(false);
  const [fsStatus,      setFsStatus]      = useState<string  | null>(null);
  const [fsStatusOn,    setFsStatusOn]    = useState(false);
  const [fsNullStr,     setFsNullStr]     = useState<string | null | undefined>(undefined);
  const [fsNullStrOn,   setFsNullStrOn]   = useState(false);
  const fsCounterSub = useRef<ReturnType<typeof BridgeTypeFixture.addFixtureObserveCounterListener> | null>(null);
  const fsStatusSub  = useRef<ReturnType<typeof BridgeTypeFixture.addFixtureObserveStatusListener>  | null>(null);
  const fsNullStrSub = useRef<ReturnType<typeof BridgeTypeFixture.addFixtureObserveNullableStringListener> | null>(null);

  // Generics — FixtureGenericApi<T> (runtime __toWire conversion + caller-asserted T)
  const [genUser,         setGenUser]         = useState<FixtureUser | null>(null);
  const [genUsers,        setGenUsers]        = useState<FixtureUser[] | null>(null);
  const [genGet,          setGenGet]          = useState<string | null>(null);
  const [genFetched,      setGenFetched]      = useState<string | null>(null);
  const [genFetchPending, setGenFetchPending] = useState(false);
  const [genObs,          setGenObs]          = useState<string | null>(null);
  const [genObsOn,        setGenObsOn]        = useState(false);
  const genObsSub  = useRef<ReturnType<FixtureGenericApi<string>["addObserveListener"]> | null>(null);
  const genStrRef  = useRef<FixtureGenericApi<string> | null>(null);
  const genUserRef = useRef<FixtureGenericApi<FixtureUser> | null>(null);

  const primRef  = useRef<FixturePrimitivesApi | null>(null);
  const asyncRef = useRef<FixtureAsyncApi | null>(null);

  useEffect(() => {
    primRef.current  = FixturePrimitivesApi.create();
    asyncRef.current = FixtureAsyncApi.create();
    genStrRef.current  = FixtureGenericApi.create<string>();
    genUserRef.current = FixtureGenericApi.create<FixtureUser>();
    return () => {
      asyncRef.current?.stopObserveStatus();  statusSub.current?.remove();
      asyncRef.current?.stopObserveUser();    liveUserSub.current?.remove();
      asyncRef.current?.stopObserveResult();  resultSub.current?.remove();
      FixtureAnalytics.stopEvents();          eventSub.current?.remove();
      BridgeTypeFixture.stopFixtureObserveCounter();       fsCounterSub.current?.remove();
      BridgeTypeFixture.stopFixtureObserveStatus();        fsStatusSub.current?.remove();
      BridgeTypeFixture.stopFixtureObserveNullableString(); fsNullStrSub.current?.remove();
      genStrRef.current?.stopObserve();       genObsSub.current?.remove();
      primRef.current?.destroy();             primRef.current  = null;
      asyncRef.current?.destroy();            asyncRef.current = null;
      genStrRef.current?.destroy();           genStrRef.current  = null;
      genUserRef.current?.destroy();          genUserRef.current = null;
    };
  }, []);

  const toggleStatus = () => {
    const api = asyncRef.current;
    if (!api) return;
    if (statusOn) {
      api.stopObserveStatus(); statusSub.current?.remove(); setStatusOn(false);
    } else {
      api.startObserveStatus();
      statusSub.current = api.addObserveStatusListener(e => setStatus(e.value));
      setStatusOn(true);
    }
  };

  const toggleLiveUser = () => {
    const api = asyncRef.current;
    if (!api) return;
    if (liveUserOn) {
      api.stopObserveUser(); liveUserSub.current?.remove(); setLiveUserOn(false);
    } else {
      api.startObserveUser();
      liveUserSub.current = api.addObserveUserListener(e => setLiveUser(e.value));
      setLiveUserOn(true);
    }
  };

  const toggleResult = () => {
    const api = asyncRef.current;
    if (!api) return;
    if (resultOn) {
      api.stopObserveResult(); resultSub.current?.remove(); setResultOn(false);
    } else {
      api.startObserveResult();
      resultSub.current = api.addObserveResultListener(e => setResult(e.value));
      setResultOn(true);
    }
  };

  const toggleFsCounter = () => {
    if (fsCounterOn) {
      BridgeTypeFixture.stopFixtureObserveCounter(); fsCounterSub.current?.remove(); setFsCounterOn(false);
    } else {
      BridgeTypeFixture.startFixtureObserveCounter();
      fsCounterSub.current = BridgeTypeFixture.addFixtureObserveCounterListener(e => setFsCounter(e.value));
      setFsCounterOn(true);
    }
  };
  const toggleFsStatus = () => {
    if (fsStatusOn) {
      BridgeTypeFixture.stopFixtureObserveStatus(); fsStatusSub.current?.remove(); setFsStatusOn(false);
    } else {
      BridgeTypeFixture.startFixtureObserveStatus();
      fsStatusSub.current = BridgeTypeFixture.addFixtureObserveStatusListener(e => setFsStatus(e.value));
      setFsStatusOn(true);
    }
  };
  const toggleFsNullStr = () => {
    if (fsNullStrOn) {
      BridgeTypeFixture.stopFixtureObserveNullableString(); fsNullStrSub.current?.remove(); setFsNullStrOn(false);
    } else {
      BridgeTypeFixture.startFixtureObserveNullableString();
      fsNullStrSub.current = BridgeTypeFixture.addFixtureObserveNullableStringListener(e => setFsNullStr(e.value));
      setFsNullStrOn(true);
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
          <Btn label="String" onPress={() => setPrimStr(primRef.current?.returnString() ?? "")} />
          <Btn label="Int"    onPress={() => setPrimInt(primRef.current?.returnInt() ?? 0)} />
          <Btn label="Long"   onPress={() => setPrimLong(primRef.current?.returnLong() ?? 0)} />
          <Btn label="Bool"   onPress={() => setPrimBool(primRef.current?.returnBoolean() ?? false)} />
        </Row2>
        {primStr  != null && <ReplyRow value={`String → ${primStr}`} />}
        {primInt  != null && <ReplyRow value={`Int    → ${primInt}`} />}
        {primLong != null && <ReplyRow value={`Long   → ${primLong}`} />}
        {primBool != null && <ReplyRow value={`Bool   → ${primBool}`} />}
      </FnCard>

      <FnCard name="returnNullableInt(): Int?">
        <Btn label="Call" onPress={() => setNullInt(primRef.current?.returnNullableInt() ?? null)} />
        {nullInt !== undefined && <ReplyRow value={`→ ${nullInt}`} />}
      </FnCard>

      {/* ── FixtureAsyncApi — suspend ──────────────────────── */}
      <Text style={s.section}>FixtureAsyncApi — suspend</Text>

      <FnCard name="fetchUser(id): FixtureUser  [Record return]">
        <StrIn value={userId} onChange={setUserId} placeholder="user id" />
        <Btn label={userPending ? "Fetching…" : "fetchUser"} disabled={userPending} onPress={async () => {
          setUserPending(true);
          try { setUser(await asyncRef.current?.fetchUser(userId) ?? null); }
          finally { setUserPending(false); }
        }} />
        {user != null && <JsonRow value={user} />}
      </FnCard>

      <FnCard name="fetchNullableUser(id): FixtureUser?">
        <Btn label={nullUserPending ? "Fetching…" : "fetchNullableUser"} disabled={nullUserPending}
          onPress={async () => {
            setNullUserPending(true);
            try { setNullUser(await asyncRef.current?.fetchNullableUser("nullable-test")); }
            finally { setNullUserPending(false); }
          }} />
        {nullUser !== undefined && <JsonRow value={nullUser} />}
      </FnCard>

      <FnCard name="deleteUser(id): Unit">
        <Btn label="deleteUser" onPress={async () => {
          await asyncRef.current?.deleteUser("del-123");
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

      {/* ── BridgeTypeFixture (file-scope) ─────────────────── */}
      <Text style={s.section}>BridgeTypeFixture (file-scope)</Text>

      <FnCard name="fixtureGreet(name): String">
        <Btn label="Call" onPress={() => setFsGreet(BridgeTypeFixture.fixtureGreet("World"))} />
        {fsGreet != null && <ReplyRow value={fsGreet} />}
      </FnCard>

      <FnCard name="fixtureAdd(a, b): Int">
        <Btn label="6 + 7" onPress={() => setFsAdd(BridgeTypeFixture.fixtureAdd(6, 7))} />
        {fsAdd != null && <ReplyRow value={fsAdd} />}
      </FnCard>

      <FnCard name="fixtureNullableEcho(value): String?">
        <Row2>
          <StrIn value={fsEchoIn} onChange={setFsEchoIn} placeholder="text or empty…" />
          <Btn label="Call" onPress={() => setFsEcho(BridgeTypeFixture.fixtureNullableEcho(fsEchoIn || null))} />
        </Row2>
        {fsEcho !== undefined && <ReplyRow value={`→ ${fsEcho}`} />}
      </FnCard>

      <FnCard name="fixtureVersion: String  [property]">
        <Btn label="Read" onPress={() => setFsVersion(BridgeTypeFixture.fixtureVersion())} />
        {fsVersion != null && <ReplyRow value={fsVersion} />}
      </FnCard>

      <FnCard name="fixtureFetchUser(id): suspend FixtureUser">
        <Btn label={fsUserPending ? "Fetching…" : "fetchUser"} disabled={fsUserPending} onPress={async () => {
          setFsUserPending(true);
          try { setFsUser(await BridgeTypeFixture.fixtureFetchUser("fs-user-1")); }
          finally { setFsUserPending(false); }
        }} />
        {fsUser != null && <JsonRow value={fsUser} />}
      </FnCard>

      <FnCard name="fixtureObserveCounter(): Flow<Int>">
        <Btn label={fsCounterOn ? "Stop" : "Start"} onPress={toggleFsCounter} muted={fsCounterOn} />
        {fsCounter != null && <ReplyRow value={fsCounter} live={fsCounterOn} />}
      </FnCard>

      <FnCard name="fixtureObserveStatus(): Flow<FixtureStatus>">
        <Btn label={fsStatusOn ? "Stop" : "Start"} onPress={toggleFsStatus} muted={fsStatusOn} />
        {fsStatus != null && <ReplyRow value={fsStatus} live={fsStatusOn} />}
      </FnCard>

      <FnCard name="fixtureObserveNullableString(): Flow<String?>">
        <Btn label={fsNullStrOn ? "Stop" : "Start"} onPress={toggleFsNullStr} muted={fsNullStrOn} />
        {fsNullStr !== undefined && <ReplyRow value={`→ ${fsNullStr}`} live={fsNullStrOn} />}
      </FnCard>

      {/* ── FixtureGenericApi<T> — generics ────────────────── */}
      <Text style={s.section}>FixtureGenericApi&lt;T&gt; — generics</Text>

      <FnCard name="create<FixtureUser>() → getUser(): T  [record via runtime __toWire]">
        <Btn label="Call" onPress={() => setGenUser(genUserRef.current?.getUser() ?? null)} />
        {genUser != null && <JsonRow value={genUser} />}
      </FnCard>

      <FnCard name="wrapUsers(): T[]  [List<T> converted element-wise]">
        <Btn label="Call" onPress={() => setGenUsers(genUserRef.current?.wrapUsers() ?? null)} />
        {genUsers != null && <JsonRow value={genUsers} />}
      </FnCard>

      <FnCard name="create<string>() → get(): T">
        <Btn label="Call" onPress={() => setGenGet(genStrRef.current?.get() ?? null)} />
        {genGet != null && <ReplyRow value={genGet} />}
      </FnCard>

      <FnCard name="fetch(): Promise<T>  [suspend]">
        <Btn
          label={genFetchPending ? "Fetching…" : "Call"}
          disabled={genFetchPending}
          onPress={async () => {
            setGenFetchPending(true);
            try { setGenFetched(await genStrRef.current?.fetch() ?? null); }
            finally { setGenFetchPending(false); }
          }}
        />
        {genFetched != null && <ReplyRow value={genFetched} />}
      </FnCard>

      <FnCard name="observe(): Flow<T>">
        <Btn
          label={genObsOn ? "Stop" : "Start"}
          muted={genObsOn}
          onPress={() => {
            if (genObsOn) {
              genStrRef.current?.stopObserve(); genObsSub.current?.remove(); setGenObsOn(false);
            } else {
              genStrRef.current?.startObserve();
              genObsSub.current = genStrRef.current?.addObserveListener(e => setGenObs(e.value)) ?? null;
              setGenObsOn(true);
            }
          }}
        />
        {genObs != null && <ReplyRow value={genObs} live={genObsOn} />}
      </FnCard>

    </ScrollView>
  );
}

// ─── Root screen ─────────────────────────────────────────────────────────────

// ─── Tab: Interface ───────────────────────────────────────────────────────────

function InterfaceTab() {
  const apiRef = useRef<FixtureInterfaceApi | null>(null);
  const [repo, setRepo] = useState<FixtureRepository | null>(null);
  const [nullRepo, setNullRepo] = useState<FixtureRepository | null | undefined>(undefined);
  const [findId, setFindId] = useState("test-user");
  const [findResult, setFindResult] = useState<unknown>(undefined);
  const [fetchedRepo, setFetchedRepo] = useState<FixtureRepository | null>(null);
  const [fetchPending, setFetchPending] = useState(false);
  const [processor, setProcessor] = useState<FixtureBaseProcessor | null>(null);
  const [processResult, setProcessResult] = useState<string | null>(null);
  const [processPending, setProcessPending] = useState(false);
  // Task 4 state
  const [processRepoResult, setProcessRepoResult] = useState<string | null>(null);
  // Task 5 state
  const [jsRepo, setJsRepo] = useState<FixtureRepository | null>(null);
  const jsRepoSubRef = useRef<any>(null);
  const [jsRoundTripResult, setJsRoundTripResult] = useState<unknown>(undefined);
  const [jsRoundTripPending, setJsRoundTripPending] = useState(false);
  const [jsRoundTripId, setJsRoundTripId] = useState("js-impl-user");
  const [nullableRepoResult, setNullableRepoResult] = useState<string | null>(null);
  const [fetchFromRepoResult, setFetchFromRepoResult] = useState<unknown>(undefined);
  const [fetchFromRepoPending, setFetchFromRepoPending] = useState(false);
  const [fetchFromRepoId, setFetchFromRepoId] = useState("from-repo-id");
  const [processProcessorResult, setProcessProcessorResult] = useState<string | null>(null);

  useEffect(() => {
    apiRef.current = FixtureInterfaceApi.create();
    return () => { apiRef.current?.destroy(); apiRef.current = null; jsRepoSubRef.current?.remove(); };
  }, []);

  return (
    <ScrollView contentContainerStyle={s.tab} keyboardShouldPersistTaps="handled">

      <Text style={s.section}>Task 1 — TS types</Text>

      <FnCard name="FixtureRepository">
        <ReplyRow value="class imported ✓" />
      </FnCard>
      <FnCard name="FixtureBaseProcessor">
        <ReplyRow value="class imported ✓" />
      </FnCard>

      <Text style={s.section}>Task 2 + 3 — interface return type</Text>

      <FnCard name="getRepository(): FixtureRepository">
        <Btn label="Call" onPress={() => setRepo(apiRef.current?.getRepository() ?? null)} />
        {repo != null && <ReplyRow value="handle ✓ (FixtureRepository instance)" />}
      </FnCard>

      <FnCard name="repo.findById(id)  [dispatch through registry]">
        <StrIn value={findId} onChange={setFindId} placeholder="user id" />
        <Btn
          label="Call"
          disabled={repo == null}
          onPress={() => { if (!repo) return; setFindResult(repo.findById(findId)); }}
        />
        {findResult !== undefined && <JsonRow value={findResult} />}
      </FnCard>

      <FnCard name="getNullableRepository(): FixtureRepository?">
        <Btn label="Call" onPress={() => setNullRepo(apiRef.current?.getNullableRepository())} />
        {nullRepo !== undefined && <ReplyRow value={nullRepo == null ? "→ null ✓" : "handle ✓"} />}
      </FnCard>

      <FnCard name="fetchRepository(id): suspend FixtureRepository">
        <Btn
          label={fetchPending ? "Fetching…" : "Call"}
          disabled={fetchPending}
          onPress={async () => {
            setFetchPending(true);
            try { setFetchedRepo(await apiRef.current?.fetchRepository("fetch-id") ?? null); }
            finally { setFetchPending(false); }
          }}
        />
        {fetchedRepo != null && <ReplyRow value="handle ✓ (suspend → FixtureRepository)" />}
      </FnCard>

      <FnCard name="getProcessor(): FixtureBaseProcessor">
        <Btn label="Call" onPress={() => setProcessor(apiRef.current?.getProcessor() ?? null)} />
        {processor != null && (
          <View style={{ gap: 8 }}>
            <ReplyRow value="handle ✓ (FixtureBaseProcessor instance)" />
            <Btn
              label={processPending ? "Processing…" : "processAsync('hello')"}
              disabled={processPending}
              onPress={async () => {
                setProcessPending(true);
                try { setProcessResult(await processor.processAsync("hello")); }
                finally { setProcessPending(false); }
              }}
            />
            {processResult != null && <ReplyRow value={processResult} />}
          </View>
        )}
      </FnCard>

      <Text style={s.section}>Task 4 — interface as parameter</Text>

      <FnCard name="processRepo(repo): String  [non-null interface param]">
        <Text style={s.hint}>{repo ? "repo ready ✓" : "call getRepository() first"}</Text>
        <Btn
          label="processRepo(repo)"
          disabled={repo == null}
          onPress={() => setProcessRepoResult(apiRef.current?.processRepo(repo!) ?? null)}
        />
        {processRepoResult != null && <ReplyRow value={processRepoResult} />}
      </FnCard>

      <FnCard name="processNullableRepo(null): String  [nullable interface param]">
        <Btn
          label="processNullableRepo(null)"
          onPress={() => setNullableRepoResult(apiRef.current?.processNullableRepo(null) ?? null)}
        />
        {nullableRepoResult != null && <ReplyRow value={nullableRepoResult} />}
      </FnCard>

      <FnCard name="fetchFromRepo(repo, id): suspend FixtureUser">
        <StrIn value={fetchFromRepoId} onChange={setFetchFromRepoId} placeholder="user id" />
        <Btn
          label={fetchFromRepoPending ? "Fetching…" : "Call"}
          disabled={repo == null || fetchFromRepoPending}
          onPress={async () => {
            if (!repo) return;
            setFetchFromRepoPending(true);
            try { setFetchFromRepoResult(await apiRef.current?.fetchFromRepo(repo, fetchFromRepoId)); }
            finally { setFetchFromRepoPending(false); }
          }}
        />
        {fetchFromRepoResult !== undefined && <JsonRow value={fetchFromRepoResult} />}
      </FnCard>

      <FnCard name="processProcessor(processor): String">
        <Text style={s.hint}>{processor ? "processor ready ✓" : "call getProcessor() first"}</Text>
        <Btn
          label="processProcessor(processor)"
          disabled={processor == null}
          onPress={() => setProcessProcessorResult(apiRef.current?.processProcessor(processor!) ?? null)}
        />
        {processProcessorResult != null && <ReplyRow value={processProcessorResult} />}
      </FnCard>

      <Text style={s.section}>Task 5 — JS implements the interface</Text>

      <FnCard name="FixtureRepository.create()  [JS-implemented]">
        <Btn
          label={jsRepo ? "JS impl created ✓" : "Create JS impl"}
          muted={jsRepo != null}
          onPress={() => {
            if (jsRepo) return;
            const r = FixtureRepository.create();
            jsRepoSubRef.current = r.addCallFetchByIdListener(({ callId, id }) => {
              const user: FixtureUser = {
                id,
                age: 42,
                score: 9.9,
                active: true,
                byteFlag: 1,
                longId: 999,
                initial: 'J',
                ratio: 1.0,
                status: FixtureStatus.ACTIVE,
                address: null,
                tags: ['js-impl'],
                metadata: {},
                aliases: [],
              };
              r.resolveFetchById(callId, user);
            });
            setJsRepo(r);
          }}
        />
        {jsRepo && <ReplyRow value="fetchById handler registered ✓" />}
      </FnCard>

      <FnCard name="fetchFromRepo(jsRepo, id)  [roundtrip: Kotlin calls JS]">
        <Text style={s.hint}>{jsRepo ? 'Kotlin calls repo.fetchById() → JS resolves' : 'Create JS impl first'}</Text>
        <StrIn value={jsRoundTripId} onChange={setJsRoundTripId} placeholder="user id" />
        <Btn
          label={jsRoundTripPending ? 'Waiting for JS resolve…' : 'Call fetchFromRepo(jsRepo, id)'}
          disabled={!jsRepo || jsRoundTripPending}
          onPress={async () => {
            if (!jsRepo) return;
            setJsRoundTripPending(true);
            try {
              const result = await apiRef.current?.fetchFromRepo(jsRepo, jsRoundTripId);
              setJsRoundTripResult(result);
            } finally {
              setJsRoundTripPending(false);
            }
          }}
        />
        {jsRoundTripResult !== undefined && <JsonRow value={jsRoundTripResult} />}
      </FnCard>

    </ScrollView>
  );
}

type Tab = "Greeting" | "Calculator" | "AsyncWorker" | "TickerService" | "TrafficLight" | "Fixture" | "Interface";
const TABS: Tab[] = ["Greeting", "Calculator", "AsyncWorker", "TickerService", "TrafficLight", "Fixture", "Interface"];

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
      {tab === "Interface"     && <InterfaceTab />}
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
