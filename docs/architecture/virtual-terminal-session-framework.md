# Virtual Terminal & Agent Session Framework — Architecture Audit and Design

**Date:** 2026-07-02
**Status:** DESIGN ONLY — no implementation started (explicit user directive).
**Scope:** the CLI-side terminal stack under `kompile-cli/kompile-cli-main/src/main/java/ai/kompile/cli/main/chat/` (`tui/`, passthrough commands, `enforcer/`, `harness/` judges, `agent/` runners), with the app-side web passthrough noted for later convergence.

**Goal (user ask):** reproduce the UIs of underlying agent CLIs (Claude Code, Codex, OpenCode, Gemini) faithfully, **while** Kompile keeps its own input handling and subprocess management, **so that** the judge/enforcer can operate in every mode.

---

## 1. Current architecture — component map

### 1.1 Terminal state layer (`chat/tui/`) — the good bones

| Component | Role | Notes |
|---|---|---|
| `VirtualTerminal` (1407 ln) | VT100/xterm emulator: main+alt screens, per-cell fg style (bg deliberately untracked), deferred auto-wrap (DECAWM `pendingWrap`), scroll regions, DECSC/DECRC, OSC/DCS parsing, `resize()`, `getScreenHash()`, `screenDump()` | Solid emulator. Carries legacy product logic it shouldn't (see F6). |
| `AgentTuiDecoder` (SPI, 220 ln) | Per-agent capability surface: `renderRawTui()`, `supportsNativeBackgrounding()`, `isResponding()/isIdle()/turnIdleMillis()`, `buildResponses()` (query answering), `buildInputResponses()` (bootstrap prompts), `submitSequence()/submitDelayMillis()/startupSettleMillis()`, `contentRowRange()`, `observe()/history()` transcript reconstruction | The right abstraction; already encodes most per-agent knowledge the framework needs. |
| `AbstractTuiDecoder` (923 ln) | Shared classification (chrome/tool/progress), no-loss `alignAndMerge` transcript, query answering | Decoder-owned agents default `renderRawTui()=false`. |
| `ClaudeCodeDecoder`, `CodexDecoder`, `OpenCodeDecoder`, `GeminiCliDecoder`, `GenericDecoder` | Layout heuristics from PTY dump analysis | `GenericDecoder` re-implements query answering instead of sharing (F5d). |
| `MirrorRenderer` (33 ln) | Pure function: `buildMirrorBlit(agentVt, topRow, regionRows)` — verbatim styled blit of the agent screen into a scroll region | Headless-testable; the proven "faithful reproduction" primitive. |
| `KompileTui` + `TopBar` + `StatusBar` + `SidePanelManager` | Kompile chrome: TopBar (2 rows) + scroll region + `reservedMiddleRows` (input box/queue) + StatusBar (2 rows); WINCH; `setEnforcerActive()` on both bars | The settled screen-ownership model (§4). |

### 1.2 Session host layer — where the architecture breaks down

**`EmulatedPassthroughCommand` — 6,010 lines / 268.6 KB.** One class owns ~15 concerns:

| Concern | ~Lines | Anchors |
|---|---|---|
| REPL loop / session init | 280 | `call()` :328–614 |
| Scroll layout + scrollback | 260 | `initScrollLayout`, `redrawScrollViewportLocked` :638–880 |
| Fixed input chrome | 180 | `drawFixedInputBox` :886–1026 |
| Activity panel + status | 630 | :1023–1730 |
| Todo tracking | 350 | :1833–2095 |
| `TerminalQueryStripper` (inner class) | 285 | :2688–2973 |
| Transcript replay | 160 | :3058–3264 |
| Spawn + PTY output pump | 260 | `sendToTuiAgent` :3265–3640 |
| Decoded/mirror rendering | 330 | `mirrorVtToScrollRegion`, `processDecodedTuiScreen` :3653–3966 |
| Structured-stream processing | 320 | `processInteractiveOutputChunk` :4000–4330 |
| Interactive question/approval | 200 | :4322–4530 |
| Slash commands | 320 | :4613–4940 |
| Enforcer wiring + UI | 230 | fields :297–314, `dispatchEnforced` :5744, `/enforcer` :4936–5065 |
| MCP injection / key bindings | 250 | :5256–5500 |
| Dispatch + process mgmt | 225 | `dispatchToAgent` :5683, `killProcess` :5952 |

Every new feature (enforcer live-control, diff archive, mirror mode, activity panel) has landed inside this class because there is nowhere else to put it. Its tests (`EmulatedPassthroughCommandManagedInputBridgeTest`, 30+ cases) must reach private state **via reflection** — the system has no seams.

**`PassthroughCommand` (74.6 KB)** — the "perfect fidelity" host: `ProcessBuilder.inheritIO()` (:205–207), no PTY wrapper, no VT, no chrome, no live parsing. Enforcement = keyword rules **injected as text into CLAUDE.md/AGENTS.md** before launch and stripped after (:170–188, :1425–1491). Transcript recovered only **post-exit** by harvesting agent-native session files (~700 lines of per-agent harvesters, :412–1200).

**`SubprocessAgentRunner` (65.8 KB)** — headless per-message runner, *extracted from the god-class per its own Javadoc (:44)* — yet `EmulatedPassthroughCommand` still re-implements the spawn/pump inline (:3316–3640). Has the `RealtimeMonitor` seam (`onTextChunk`/`onToolUse`, :54) that enables mid-stream interrupts — the TUI path never attaches one.

**`PassthroughStreamParser` (1,015 ln)** — multi-agent structured-stream parser (Claude stream-json, Codex NDJSON incl. approval/question events, OpenCode/Gemini/Qwen/Pi) → typed events (`TextChunk`, `ToolUse`, `TurnComplete`, `InteractiveQuestion`, …). Live in `EmulatedPassthroughCommand` (structured agents only — the TUI path bypasses it entirely), `SubprocessAgentRunner`, `AgentSubprocessClient`. `PersistentAgentProcess` has its **own inline** stream-json parsing. A stale `PassthroughStreamParser.java.orig` (41.7 KB) is checked into the tree.

### 1.3 Enforcement layer (`enforcer/` + `harness/`)

**Engine (live, host-agnostic, keep):** `EnforcerService.enforce(prompt, policy, contextSupplier, executor)` retry/correction loop (`EnforcerService.java:59`); `EnforcerJudge` turn/partial/tool evals with `JudgementLog` JSONL (`~/.kompile/sessions/<id>/judgements.jsonl`); `JudgeBackend` family behind `JudgeBackendFactory` with `ResilientJudgeBackend` (deadline + model swap) and `EnforcerFallbackPolicy`; `EnforcerDiffArchive` per-turn git snapshots with rollback; `EnforcerToolCallGuard` MCP pre-execution gate (live in `McpStdioCommand:252,596`, `McpSocketSession:592`).

**Current host wirings (each hand-rolled):**
1. `EnforcerCommand.runEnforcedTurn()` (:714) — classic REPL + REST `Session`; attaches `EnforcerRealtimeMonitor`/`KeywordRealtimeMonitor` to `SubprocessAgentRunner` → **full realtime** enforcement.
2. `EnforcerCommand.runLivePtyEnforced()` (:413) → sets 9 package-visible fields on `EmulatedPassthroughCommand` → **turn-gate only** (no realtime monitor).
3. `ChatCommand.runEnforcedPassthroughMode()` (:565–594) — same pattern, third copy of judge construction.
4. MCP stdio/socket tool gate.
5. `EnforcerControlServer` REST (localhost, token auth).

**Built, complete, and 100 % unwired (the parts the new framework needs):**
- `EnforcerJsonlTailer` (18.7 KB) — tails **agent-native session JSONL** (`~/.claude/projects/<dir>/<session>.jsonl`, `~/.codex/sessions/**/*.jsonl`) at 750 ms, evaluates text + tool calls via `EnforcerJudge`. **Zero instantiations.**
- `ScoringRealtimeMonitor` + `RealtimeInterruptEvent`/`RealtimeInterruptListener` — a complete score/interrupt/reprompt event system (SCORE_UPDATE, TEXT_VIOLATION, TOOL_VIOLATION, REPROMPT, TURN_SCORED, BLOCKED × CONTINUE/INTERRUPT/REPROMPT/BLOCK). Its host `AgentSubprocessClient` is **never instantiated**; the listener list is always empty.
- `BackgroundEnforcerMonitor` (script-log tailer), `SubprocessJudgeEvaluator` (agent-as-judge) — dead.
- `TuiJudgeAndInterruptTest` contains a designed-and-proven `ResponseJudge` + `InterruptHandler` + `FallbackSupervisor` (auto agent-switch cascade on bad scores, context preserved) — **as test-local classes only**; never promoted to production.

### 1.4 Subprocess/PTY layer — the raw material

- **No pty4j in any runtime code** (referenced only in POM-generator comments). All PTY allocation is a `script(1)` wrapper: `script -q /dev/null -c "stty rows N cols M; <cmd>"`.
- `wrapWithPty()` exists **three times**: `EmulatedPassthroughCommand:5589` (sets dimensions), `SubprocessAgentRunner:1484` (no dims), `AgentCommandForwarder:352` (no dims, different quoting). Silent fallback to *no PTY* when `script` is missing.
- Kill/interrupt exists twice (`EmulatedPassthroughCommand.killProcess:5952` INT→300ms→TERM→3s→forcible; `SubprocessAgentRunner:1550` variant) plus the proven `escalatingUnixInterrupt` (INT→TERM→KILL, needed because **opencode ignores SIGINT**).
- WINCH propagation sends `kill -WINCH <pid>` to the direct child (:437–466) — misses the process group, i.e. the agent running *inside* `script`'s child shell; runtime resize is unreliable.
- The web app (`PassthroughSessionManager`, `EnforcerSessionManager` in app-main) implements the subprocess-ownership pattern **two more times** (plain `ProcessBuilder` + line reader + SSE; no PTY/VT; no reuse of CLI enforcer classes; CLI↔web bridge is `registerExternalSession`/`recordEvent`).

### 1.5 Test/verification infrastructure (keep and extend)

- **Framebuffer harness:** `MirrorFramebufferTest` composes agent-VT → `MirrorRenderer.buildMirrorBlit` → screen-VT → `screenDump()` assertions; dumps land in `target/framebuffer/*.txt`. This is the pattern for all render-policy tests.
- `PtyDumpRenderHarness` (`-Dpty.harness=true`) replays real captured PTY bytes from `/tmp/kompile-pty-capture/*.raw` through the VT+decoder.
- `VirtualTerminalAgentConsistencyTest` pins emulator invariants across 6 agent geometries.
- `EnforcerInterruptLatencyTest`, `SubprocessInterruptLatencyTest` — hermetic signal-escalation tests.

---

## 2. Findings

**F1 — God-class fusion (critical).** All session behavior lives in `EmulatedPassthroughCommand` (6,010 ln). Consequences: features are non-reusable across hosts, tests require reflection, every fix risks unrelated concerns, and the raw/enforcer/web hosts can't share the machinery.

**F2 — Fidelity and control are coupled (critical — the core of the user ask).** "Who paints" (raw bytes vs mirror blit vs decoded transcript) is decided inside the output pump (:3455–3502), and input handling, turn detection, and enforcement are entangled with that choice. Reproducing the agent UI currently means giving up Kompile's management (raw), and keeping management means re-rendering (decoded/mirror inside the god-class).

**F3 — Enforcement depth is inversely proportional to UI fidelity (critical).**

| Host | UI fidelity | Enforcement today |
|---|---|---|
| `PassthroughCommand` (inheritIO) | perfect | prompt injection only — no judge, no interrupt, no rollback |
| `EmulatedPassthroughCommand` TUI path | high (mirror/raw) | turn-gate only — **no realtime monitor attached**, no mid-stream interrupt |
| `EnforcerCommand` classic/REST (headless) | none | full (turn gate + realtime text/tool interrupts) |

The most faithful modes have the weakest enforcement. The unwired `EnforcerJsonlTailer` + `ScoringRealtimeMonitor` are precisely the missing links.

**F4 — The needed components exist but dangle.** JSONL tailer (semantic tap that works regardless of who owns the screen), scoring monitor + interrupt events (uniform enforcement action bus), fallback supervisor (test-local). The framework job is mostly *wiring*, not invention.

**F5 — Systemic duplication.**
(a) `wrapWithPty` ×3 (one with dims); (b) spawn/pump blocks ×3 (`EmulatedPassthroughCommand:3316`, `SubprocessAgentRunner:400,564`); (c) `killProcess` ×2 + `escalatingUnixInterrupt`; (d) terminal-query answering ×4 (`AbstractTuiDecoder.buildResponses:862`, `GenericDecoder:54`, `VirtualTerminal.terminalResponsesFor:546` + `safeTerminalResponsesFor:610` — the VT pair has **zero production callers**); (e) `parseAgentLineMulti`/`isStructuredAgent` ×2; (f) stream-json parsing re-implemented inline in `PersistentAgentProcess`; (g) MCP/prompt/skills injection ×3; (h) app-side subprocess stack ×2 more; (i) judge construction ×5 hosts; (j) three judge-prompt builders (`EnforcerJudge.buildJudgePrompt` is the live one).

**F6 — The emulator carries product logic.** `VirtualTerminal.getNewText()/getAllContentText()/isChromeRow()` (chrome heuristics inside the emulator; single production caller `EmulatedPassthroughCommand:4049`) and the dead query responders. The VT should be a pure screen model.

**F7 — PTY/lifecycle fragility.** `script(1)` silent no-PTY fallback; WINCH to pid not group; dims set only at spawn; no restart policy; `--skip-permissions` accepted but ignored by `buildCommand()` (:4607 returns `[binary]` only).

**F8 — Session-identity split.** The passthrough mints `sessionId="emulated-<uuid>"` (:368) while judgements are keyed by `EnforcerRuntimePolicy.getSessionId()` (`KOMPILE_ENFORCER_SESSION_ID`) — two ids for one session; `/enforcer judgements` needed a workaround (read via the judge's own log handle).

**F9 — Hygiene.** `PassthroughStreamParser.java.orig` checked in; dead constants (`EnforcerJudge.PARTIAL_SYSTEM_PROMPT:321`, `TOOL_CALL_SYSTEM_PROMPT:332`, `EnforcerToolCallGuard.ALWAYS_ALLOW:31`); `EnforcerAttachCommand` name promises attach, delivers read-only tail.

---

## 3. Design constraints (settled decisions — do not relitigate)

These were decided with the user during the 2026-06-20 mirror-mode work and remain binding:

1. **Kompile owns the bottom.** Managed mode keeps Kompile's input box + TopBar + StatusBar in *every* render mode. "The main problem in switching to mirror is the kompile tooling not showing up. I'd prefer to still own the bottom and the input box." Mirror is a **render-only variation**; the agent's input box appearing inside the mirror is accepted redundancy.
2. **Defer rendering to the agent where possible; never re-render full-screen TUIs from heuristics as the primary path.** Decoder-merge re-rendering is the fragile fallback, not the default direction.
3. **Backgrounding is delegated** to agents that support it natively (`supportsNativeBackgrounding()`, Ctrl+B forwarding for Claude); Kompile backgrounding applies only to agents lacking it. Queueing stays Kompile-managed.
4. **The VT is the shared root.** Mirror-mode fidelity was fixed at the source (deferred auto-wrap) rather than by filtering; keep pinning emulator subtleties with tests.
5. **Interrupts must escalate** (INT→TERM→KILL) because agents differ (claude honors SIGINT; opencode ignores it).

---

## 4. Target architecture

One framework, six layers, N thin hosts. New package: `ai.kompile.cli.main.chat.terminal` (working name — *Terminal Session Framework*, TSF).

```
┌─ Host (EmulatedPassthrough / Passthrough / EnforcerCommand / REST / headless / web-later) ─┐
│                                                                                            │
│   AgentSession  (orchestrator: one agent subprocess + one screen + one input + policies)   │
│      │                                                                                     │
│      ├── L0  AgentProcess        spawn/PTY/signals/lifecycle/resize        (one impl)      │
│      ├── L1  TerminalState       VirtualTerminal (pure) + QueryResponder + Decoder SPI     │
│      ├── L2  RenderPolicy        RAW | MIRROR | DECODED   (strategy, hot-swappable)        │
│      ├── L3  InputRouter         managed prompt | raw-forward + chords + interceptors      │
│      ├── L4  TurnEngine          semantic taps → TurnEvents (started/delta/tool/completed) │
│      └── L5  EnforcementBridge   pre-gate + turn gate + realtime + tool gate + actions     │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.1 L0 — `AgentProcess` (subprocess + PTY ownership)

Consolidates the three spawn blocks, three `wrapWithPty`, two kill paths.

```java
interface AgentProcess extends AutoCloseable {
    void start(AgentLaunchSpec spec);            // cmd, cwd, env, dims, ptyMode
    OutputStream stdin();
    void onOutput(OutputTap tap);                // ordered tap chain (see below)
    void resize(int rows, int cols);             // stty at spawn + WINCH to PROCESS GROUP
    void interrupt(InterruptEscalation esc);     // INT→grace→TERM→grace→KILL (configurable)
    void signalTree(String signal);
    boolean isAlive();  int pid();  CompletableFuture<Integer> exitFuture();
}
```

- `AgentLaunchSpec` carries: command (from a single shared `AgentCommandBuilder` that finally honors `--skip-permissions` etc.), env (incl. `enforcerExtraEnv`), PTY dims, `PtyMode { SCRIPT_WRAPPER, INHERIT_IO, NONE }`.
- **Pty provider behind an interface** (`PtyProvider`): default `ScriptPtyProvider` (today's behavior, but *loud* when `script` is missing), leaving room for a pty4j provider later without touching callers.
- **Resize fix:** send WINCH to the process *group* (negative pid / `kill -WINCH -- -<pgid>`), falling back to child-pid; keep `stty` only for initial dims.
- One `InterruptEscalation` policy object replaces `killProcess` ×2 + `escalatingUnixInterrupt` (defaults 300/200 ms per the tested values).

**`OutputTap` chain** (formalizes the pump at `EmulatedPassthroughCommand:3404–3517` in fixed order):

```
raw bytes ─▶ PtyDumpTap ─▶ VtFeedTap(vt) ─▶ BootstrapTap(decoder.buildInputResponses)
          ─▶ QueryStripTap(TerminalQueryStripper → displayBytes | queries → QueryResponder)
          ─▶ RenderTap(renderPolicy.onFrame/onBytes) ─▶ StreamParseTap(structured agents only)
```

The pump thread lives in L0; taps are pure consumers. `TerminalQueryStripper` moves out of the god-class unchanged (it is already self-contained, :2688–2973).

### 4.2 L1 — Terminal state

- **`VirtualTerminal` slims to a pure emulator.** `getNewText()/getAllContentText()/isChromeRow()/hasEnoughWords()` are deprecated and their one caller migrated to decoder extraction; `terminalResponsesFor/safeTerminalResponsesFor` (zero callers) delete outright, with their tests retargeted to the new responder.
- **`TerminalQueryResponder` — ONE implementation** of query answering (DSR/DA1/DA2/XTVERSION/window-reports/OSC 10-12/Kitty), parameterized by a per-agent `QueryPolicy { answersKittyKeyboard, … }` supplied by the decoder. `AbstractTuiDecoder.buildResponses` and `GenericDecoder` delegate to it (their `appendExtraResponses` hook survives). Kills duplication family F5d.
- **`AgentTuiDecoder` stays as-is** — it is the per-agent capability registry. One addition (design-level): `SemanticCapabilities capabilities()` describing which taps the agent supports (see L4).

### 4.3 L2 — `RenderPolicy` (strategy; the "reproduce UIs" axis)

```java
interface RenderPolicy {
    void onDisplayBytes(byte[] b);               // raw path (called from RenderTap)
    void onSettledFrame(VirtualTerminal vt);     // settle+hash gate fires this
    void attach(ScreenChrome chrome);            // scroll region geometry, drawLock
    String name();                               // "raw" | "mirror" | "decoded"
}
```

- **`RawRenderPolicy`** — forward `displayBytes` verbatim to `terminal.output()` under the draw lock (today's `renderRawTui()==true` branch). Chrome minimized; used for full-handoff.
- **`MirrorRenderPolicy`** — settle-gate (90/1200 ms) + `MirrorRenderer.buildMirrorBlit` into the scroll region; Kompile keeps TopBar/StatusBar/input box (today's `mirrorVtToScrollRegion`, :3500). **Recommended default for TUI agents** — this *is* "reproduce the agent UI while owning input": the agent paints itself into our shadow VT, we blit it verbatim, and the real cursor stays in Kompile's input box.
- **`DecodedRenderPolicy`** — settle-gate (60/400 ms) + decoder `observe()`/extract → transcript scroll (today's `processDecodedTuiScreen`). Robust fallback and the mode that gives Kompile-owned scrollback.
- The settle+hash gate becomes a small shared `FrameSettleGate` (per-policy windows), extracted from :3455–3502.
- `/render raw|mirror|decoded` swaps the strategy object at runtime; the choice **never** affects L3–L5. `AgentTuiDecoder.renderRawTui()` becomes the *default policy selector* per agent instead of an inline branch.

**Mode × capability matrix (the contract this design commits to):**

| Capability | RAW (inheritIO, `PassthroughCommand`) | RAW (in-session bytes-forward) | MIRROR | DECODED | Headless structured |
|---|---|---|---|---|---|
| UI fidelity | perfect | perfect | high (verbatim blit, settle-gated) | medium (transcript) | n/a |
| Kompile chrome (bars, input box) | none | escape-chord only | **yes** | yes | n/a |
| Pre-send prompt gate | no | no (post-hoc within ~1 s) | **yes** | yes | yes |
| Realtime judge + interrupt | via JSONL tail | via JSONL tail | JSONL tail + VT tap | stream/VT taps | full (stream) |
| Tool-call observation | JSONL tail | JSONL tail | JSONL/stream | stream | stream |
| Tool-call *blocking* | MCP-gateway tools only | same | same | same | full (`RealtimeMonitor`) |
| Diff archive / rollback | yes (turn from JSONL) | yes | yes | yes | yes |
| Kompile queueing/backgrounding | no | chord | yes | yes | yes |

(Blocking an agent-*internal* tool is impossible without owning its runtime; in PTY modes the honest contract is *observe → judge → interrupt/rollback fast*, plus hard blocking for Kompile-served MCP tools via the existing `EnforcerToolCallGuard`.)

### 4.4 L3 — `InputRouter` (the "our own input" axis)

Kompile always owns the real stdin. Two modes, one interceptor chain:

```java
interface InputRouter {
    void setMode(InputMode m);                   // MANAGED_PROMPT | RAW_FORWARD
    void addInterceptor(InputInterceptor i);     // ordered; may consume/rewrite/hold
    void submit(String message);                 // programmatic (queue, REST, enforcer reprompt)
}
interface InputInterceptor {
    Decision onSubmit(SubmitEvent e);            // ALLOW | REWRITE(prompt) | DENY(reason) | HOLD
    boolean onKey(KeyEvent e);                   // chords, busy-keys; true = consumed
}
```

- **MANAGED_PROMPT** = today's JLine readline + slash commands + completion + busy buffer + `MessageQueue` (extracted, not rewritten). Submissions flow through the interceptor chain → `AgentSession.dispatch()`.
- **RAW_FORWARD** = today's `/passthrough` loop (raw mode, byte forwarding, Ctrl+] exit) formalized, with a `KeyChordRegistry` (Kompile escape chord, scrollback keys, Ctrl+B routing per `supportsNativeBackgrounding()`, Ctrl+O forwarding) replacing scattered widget installs.
- **Busy-key policy** revives the dead `busyInputActive` path: while the agent is responding, designated keys (Esc, Ctrl+B) forward to the agent; typed text stays in the Kompile busy buffer/queue as today.
- The **enforcer pre-gate is just an interceptor** (`EnforcerSubmitInterceptor`): in managed mode it can rewrite (inject rules preamble), deny, or hold for the turn gate. In RAW_FORWARD there is no reliable pre-send hook — the design deliberately does *not* attempt heuristic Enter-detection; raw mode relies on the L4 tap + fast interrupt instead (matrix row "pre-send gate").

### 4.5 L4 — `TurnEngine` + semantic taps (the "what is the agent doing" axis)

Uniform turn/tool event stream regardless of render mode, fed by the **best available source**:

```java
enum SemanticSource { STRUCTURED_STREAM, NATIVE_SESSION_JSONL, VT_DECODER }
interface SemanticTap { Flow<AgentEvent> events(); SemanticSource source(); }
```

- **`StructuredStreamTap`** — wraps `PassthroughStreamParser` events (headless/structured agents; also carries `InteractiveQuestion/Approval`). One parser; `PersistentAgentProcess`'s inline copy migrates here.
- **`SessionJsonlTap`** — **wires the dead `EnforcerJsonlTailer`**, generalized: claude/codex native session files give user turns, assistant text, and tool calls with arguments — *independent of who owns the screen*. This is the semantic backbone for RAW and MIRROR modes.
- **`VtDecoderTap`** — decoder `observe()`/`isResponding()`/`isIdle()` + `turnIdleMillis()` quiet-window (today's `decodedTuiTurnComplete`, :3653) as the universal fallback (opencode/gemini/generic).
- `TapSelector` picks per `AgentTuiDecoder.capabilities()` (claude → JSONL+VT; codex → JSONL+VT; opencode/gemini → VT; structured headless → stream), and **fuses**: VT tap for latency/busy-state, JSONL tap for ground-truth content/tools.
- Output: `TurnStarted(userPrompt) → OutputDelta(text) → ToolObserved(name,args) → TurnCompleted(transcript, durations)`. `StatusBar`/activity panel and `ChatHistory` consume the same events (removing bespoke plumbing from the god-class).

### 4.6 L5 — `EnforcementBridge` (the "judge/enforcer everywhere" axis)

One bridge object per session, constructed from `EnforcerConfig` once (killing the 5 hand-rolled wirings), subscribing to L3/L4 and acting through L0/L3:

- **Pre-gate:** `EnforcerSubmitInterceptor` (managed mode only, per matrix).
- **Turn gate:** existing `EnforcerService.enforce(...)` wrapped around `AgentSession.dispatch` — unchanged semantics (correction loop, diff archive `beginTurn/completeTurn`, auto-rollback, `applyDiffArchive` port).
- **Realtime:** subscribe `ScoringRealtimeMonitor` (finally wired) to the fused tap; `onTextChunk` partial evals (existing 1200-char/900-char/2000-ms throttles) and `ToolObserved` evals; violations → `RealtimeInterruptEvent` on the (finally non-empty) listener bus.
- **Actions** (uniform, mode-independent): `interrupt()` → `AgentProcess.interrupt(escalation)`; `reprompt(correction)` → `InputRouter.submit()` after interrupt; `rollback(turnId)` → `EnforcerDiffArchive`; `block` → deny at pre-gate / drop dispatch; `annotate` → StatusBar/TopBar tags + judgement panel + `BackgroundProcessManager` watchers (`registerEnforcerWatchers` moves to the bridge).
- **Identity fix (F8):** `AgentSession` owns ONE `SessionIdentity{kompileSessionId, enforcerSessionId, agentNativeSessionId}` minted at construction; `JudgementLog`, JSONL tap, history, and REST all key off it.
- **`FallbackSupervisor` (promotion, optional phase):** lift the test-local design (catastrophic-turn / consecutive-failures / cumulative-floor triggers → agent-switch cascade with context preservation) into the bridge as an optional policy.

### 4.7 Hosts become thin

- **`EmulatedPassthroughCommand`** → composes `AgentSession` (managed prompt + mirror-or-decoded + full bridge) + its UI extras (activity panel, todos, replay). Target: < 1,500 lines of genuinely command-specific UI.
- **`PassthroughCommand`** → `AgentSession` with `PtyMode.INHERIT_IO`-equivalent raw policy + `SessionJsonlTap` + bridge → **gains real judge/interrupt/rollback enforcement** while remaining pixel-perfect; keeps its rule-injection as the pre-gate implementation for this mode; post-exit harvesters shrink to a thin adapter over the JSONL tap.
- **`EnforcerCommand` / REST / headless** → same `AgentSession` core with no render policy.
- **Web (later, out of CLI scope):** `PassthroughSessionManager`/`EnforcerSessionManager` adopt L0 + L4 taps; SSE becomes another event consumer.

### 4.8 Key sequences

**Managed dispatch with enforcement (mirror render):**
```
user Enter → InputRouter(MANAGED) → interceptors(pre-gate: rewrite/deny)
  → EnforcementBridge.turnGate: DiffArchive.beginTurn
  → EnforcerService.enforce(prompt, policy, ctx, p -> session.dispatch(p))
      dispatch → AgentProcess.stdin (text + decoder.submitSequence, submitDelay)
      pump: bytes → VT feed → QueryResponder → FrameSettleGate → MirrorRenderPolicy.blit
      TurnEngine: VT busy → JSONL/VT events → realtime partial evals (violation → interrupt+correction)
      TurnCompleted → judge turn eval → compliant? accept : correction-loop
  → applyDiffArchive (pattern eval, optional rollback) → StatusBar/judgement log update
```

**Raw passthrough with enforcement (the new capability):**
```
agent owns screen (bytes forwarded verbatim); user types into agent's own UI
SessionJsonlTap: user msg → TurnStarted; assistant deltas → partial evals; tool calls → tool evals
violation → RealtimeInterruptEvent(INTERRUPT, correction)
  → AgentProcess.interrupt(INT→TERM→KILL)  [claude ~1ms, opencode ~374ms]
  → optional reprompt via InputRouter.submit (managed injection w/ submitSequence)
  → DiffArchive rollback if diff-pattern violation
StatusBar unavailable in full-handoff; judgements still land in judgements.jsonl (+ REST/web tabs)
```

---

## 5. Migration plan (strangler — no big-bang; each WP independently shippable & green)

**Phase A — extract L0 (no behavior change)**
- WP1: `terminal/` package skeleton; move `TerminalQueryStripper` out (unchanged); create `AgentProcess`/`ScriptPtyProvider`/`InterruptEscalation` from the three `wrapWithPty` + two kill paths; `EmulatedPassthroughCommand.sendToTuiAgent` delegates spawn/pump to it. Acceptance: input-bridge + interrupt-latency tests green, PTY dump byte-identical on a replay.
- WP2: WINCH-to-process-group fix + loud no-`script` fallback + single `AgentCommandBuilder` honoring the ignored flags. Acceptance: live resize test w/ nested `script` child.
- WP3: hygiene — delete `PassthroughStreamParser.java.orig`, dead prompts/constants; rename-or-alias `enforcer attach` help text.

**Phase B — consolidate L1**
- WP4: `TerminalQueryResponder` + `QueryPolicy`; decoders delegate; delete VT `terminalResponsesFor/safeTerminalResponsesFor` (retarget their tests); `GenericDecoder` shares.
- WP5: migrate `EmulatedPassthroughCommand:4049` off `vt.getNewText()` to decoder extraction; deprecate VT extraction trio (delete after one release).

**Phase C — L2 render strategies**
- WP6: `FrameSettleGate` + `RenderPolicy` interface; extract Raw/Mirror/Decoded from :3455–3966; `/render` swaps objects; `renderRawTui()` becomes default-policy selector. Acceptance: framebuffer suite reproduces existing `target/framebuffer/*` dumps unchanged.

**Phase D — L3/L4 seams**
- WP7: `InputRouter` extraction (managed prompt path first; chord registry; busy-key policy incl. Ctrl+B/Esc delegation). Acceptance: input-bridge tests rewritten against the interface (reflection removed for migrated cases).
- WP8: `TurnEngine` + `VtDecoderTap` (wrap `decodedTuiTurnComplete`) + `StructuredStreamTap` (wrap parser; `PersistentAgentProcess` migrates); status/activity/history consume TurnEvents.
- WP9: `SessionJsonlTap` — wire `EnforcerJsonlTailer` (generalized, session-file discovery from `SessionIdentity`), fused with VT tap.

**Phase E — L5 enforcement equalization (the payoff)**
- WP10: `EnforcementBridge` — single construction from `EnforcerConfig`; `EmulatedPassthroughCommand`/`ChatCommand`/`EnforcerCommand` all consume it; `SessionIdentity` unification (F8); watchers move in.
- WP11: realtime-in-TUI — wire `ScoringRealtimeMonitor` to the fused tap in emulated passthrough (partial-eval interrupts mid-turn). Acceptance: hermetic violating-turn test → interrupt < 1 s (stream) / < ~2 s (JSONL poll).
- WP12: raw-mode enforcement — `PassthroughCommand` gains the bridge via JSONL tap (judge, interrupt, rollback, judgement log) while staying inheritIO-faithful.
- WP13 (optional): promote `FallbackSupervisor` from `TuiJudgeAndInterruptTest` into the bridge.

**Phase F — later / out of CLI scope**
- WP14: pty4j `PtyProvider` behind the L0 interface (evaluate; not required).
- WP15: web-side adoption of L0/L4 (+ retiring the two app-main subprocess stacks).

Dependencies: A→B→C can interleave; D requires A; E requires D (WP9 for WP12); nothing requires F.

---

## 6. Test strategy

- **Render policies:** framebuffer harness (`MirrorFramebufferTest` compose pattern) per policy — golden dumps for raw/mirror/decoded from the same PTY capture; `PtyDumpRenderHarness` replays as regression corpus.
- **InputRouter:** existing `LineDisciplineTerminal` fake-PTY tests rewritten against public seams — the reflection count in `EmulatedPassthroughCommandManagedInputBridgeTest` is the measurable debt metric (target: 0 for migrated behaviors).
- **Taps/TurnEngine:** fixture JSONL session files (claude/codex formats) driving `SessionJsonlTap`; synthetic stream-json for `StructuredStreamTap`; VT tap covered by existing consistency tests.
- **EnforcementBridge:** hermetic violating-agent stubs (the `trap '' INT` patterns from `SubprocessInterruptLatencyTest`) → interrupt-latency and rollback assertions per mode.
- **Emulator:** `VirtualTerminalAgentConsistencyTest` / style tests stay as the L1 safety net.

## 7. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Strangler churn destabilizes the working TUI | Every WP keeps god-class behavior as the delegating shell; framebuffer goldens gate each phase |
| JSONL tail latency (750 ms poll) too slow for realtime gate | Acceptable per matrix (interrupt-after ~1–2 s); tighten poll adaptively while `isResponding()` |
| Session-file formats drift with agent versions | Tap is per-agent + versioned fixtures; decoder dump-analysis workflow already exists |
| Raw-mode pre-send gating expectations | Explicitly out of contract (matrix); managed mode is the gated mode |
| `script(1)` absence | Loud failure + documented fallback; pty4j option preserved behind `PtyProvider` |

## 8. Open questions (for the user, non-blocking)

1. Default render policy for Claude: stay **decoded** (current default) or flip to **mirror** once WP6 lands? (Mirror was built for exactly this; the earlier blocker — VT wrap collisions — was fixed at the source in build 26.)
2. Should raw-mode enforcement (WP12) be on by default when an enforcer config exists, or opt-in per session (`--enforce`)?
3. Is web-side convergence (WP15) in scope for this framework effort at all, or a separate initiative?
