# Task 0 Checkpoint — MCP token metrics wiring & compatibility inventory

Date: 2026-09-22 · Branch state: `graph-schema-proposal-grounding` @ `ec13e2c4`
Reviewed baseline in the prompt pack (`bb27493d`) is 2 commits behind HEAD; not reset (per contract).
Note: `kompile-mcp-token-metrics-design.md` is not present anywhere on disk (repo, docs, tool-results);
only the prompt pack references it. This pack + code inspection is the operative spec.

## 1. Current HEAD & tree

- HEAD: `ec13e2c4` ("update"); parent `be164cd82` (Z.AI structured output).
- Working tree already has user changes (model-context.service.ts, deploy-cli-jar.sh, several untracked
  scratch files incl. `kompile-cli-main/src/main/java/logback.xml`, `templates/`, test classes).
  None touched by this inventory. Do not clean or reset.

## 2. Verified execution boundaries (read, not inferred)

### CLI stdio (kompile-cli-main)
- `main/mcp/McpStdioCommand.java` (≈2000+ lines): JSON-RPC loop in `runInProcess` → `handleMessage`.
  `tools/call` path at ~846–1075 has EXACTLY the exits the pack lists:
  unknown tool (~869), gateway BLOCK (~887), enforcer BLOCK (~916), rewrite (~906, ~932),
  background ack (~957–1006), poll inline (~1008), cancelled-call suppression `return null` (~1022–1026),
  standard sync (~1020–1073). Result reference caching happens AFTER execution (~1063–1070,
  skipped for `fetch_result`/`fetch_result_batch`) → final serialized content is `buildCallResult(tr)`.
- `buildCallResult` (line ~1873) → `McpToolResultSerializer.toMcpCallResult(mapper, tr)`.
- `chat/tools/McpToolResultSerializer.java` (37 lines, kompile-owned): builds the wire `ObjectNode`
  by hand (`content[].text = title + output`, optional `structuredContent`, `isError`).
  **No SDK constraint on stdio — `_meta` is a direct `callResult.set("_meta", …)`.**
- Cancellation: `InFlightCall` + `CURRENT_CALL` ThreadLocal (~314–320); cancelled calls suppress
  response AND progress; background ack payload is the final response for that invocation.
- Session id: `resolveTranscriptId` (KOMPILE_TRANSCRIPT_UUID or generated, ~385); `toolContextSessionId`
  (~399). Inherited session correlation env: KOMPILE_PARENT_SESSION_ID / KOMPILE_AGENT_TASK /
  KOMPILE_AGENT_ROLE / KOMPILE_SUBAGENT_DEPTH (~347–364).
- Existing observability hooks on every exit already: `sessionTracker.recordToolCall`,
  `progressLogger.toolError/toolComplete`, `auditLogger.recordDecision`.

### CLI catalog writers
- `chat/ToolCallIndex.java`: singleton; per-session JSONL + combined `all-tool-calls.jsonl`;
  `synchronized` instance methods only (no cross-process lock). `record(ToolCallRecord)` overload exists.
- `chat/ToolCallRecord.java`: 12 fields, `@JsonIgnoreProperties(ignoreUnknown=true)` → additive
  usage fields are backward-compatible; `categorize()` static.
- `mcp/stdio/McpToolAuditLogger.java`: wraps ToolCallIndex.record; packs decision/reason/effective-args
  into the `toolInput` JSON envelope (structure the Task-2 typed overload must not be confused with).
- `chat/tools/ToolCallCatalogTool.java`: actions search/list/stats/index/filters; default limit via
  `index.search(..., limit)`; NO session-required usage action yet (Task 5 target).
- `chat/ChatSessionMetrics.java`: provider/model token ledger (`recordTokenUsage(in,out,cacheRead,cacheCreate)`,
  line 188) with 25+ call sites (AgenticChatLoop, Passthrough, EmulatedPassthrough, SubprocessAgentRunner,
  HeadlessAgentRunner, AuxChatRepl). This is the host conversation ledger — keep separate (contract).

### App (kompile-app-mcp)
- `mcp/McpToolRegistry.java`: production path IS `createToolSpec(ToolCallback)` (line 374) via
  `toolCallbackCatalog`; reflection path only as no-context test fallback (~346, ~494). Both build
  `SyncToolSpecification(tool, (exchange,args) -> …)` with logActionStart → permission → gateway →
  try/compress/log catches. Compression via `compressCallbackResult` (463) AFTER logging success with
  `truncate(rawResult)` — i.e. current log records PRE-compression text; metrics must count
  POST-compression representation.
- `mcp/McpToolWrapper.java`: wrap/wrapUndoable log-only; used for undo semantics; NOT the production
  registry path (registry delegates to callbacks, wrapper still needs the same recording seam if used).
- `services/mcp/McpActionLogService.java`: in-memory deque + JSONL history; `catalogSessionId =
  "mcp-app-" + UUID` per service instance (session-provenance issue flagged by pack, Task 4);
  `logActionStart` has no context-aware overload yet.
- `services/ToolCallWriterService.java`: same directory/schema as CLI ToolCallIndex, `synchronized(this)`
  only, `durationMs` always 0. TWO writers, ONE authoritative stream needed (Task 2).

## 3. Symbol search results
- `CompressingToolCallbackProvider`: exists only in app
  (`services/mcp/optimization/CompressingToolCallbackProvider.java`, wired in `McpSseServerConfiguration`).
  Not present in CLI — CLI has no compression layer; stdio reference caching is the analogue.
- `recordTokenUsage`: ChatSessionMetrics + 6 callers + tests (list above). No tool-attributed variant.
- `estimateTokens`: only `ToolSchemaOptimizer.estimateTokens` (schema sizing, called from
  McpStdioCommand ~805). No tokenizer lib anywhere: **no jtokkit/tiktoken dependency in any pom**
  (checked kompile-cli/**/pom.xml + all Java). → Task 1 must ship a deterministic, named, versioned
  Unicode-aware estimator (contract forbids unlabeled len/4).
- `bridgeStdio`: `DaemonClient.bridgeStdio` (serve/DaemonClient.java:327) is a pure byte pump
  (stdin→socket, socket→stdout). Daemon-side executes inside the SAME McpStdioCommand code path.
- `SyncToolSpecification`: zero occurrences in CLI (hand-built wire JSON only); app uses
  `McpServerFeatures.SyncToolSpecification`.
- Existing tests (do not assume; verified): `McpStdioCommandTest`, `McpToolAuditLoggerTest`,
  `ChatSessionMetricsTest` (rich), `ToolRegistryStdioParityTest`, `AllCliToolsRegisteredSweepTest`,
  `McpSocketSessionTest`, `ToolCallTailReaderTest`. App-mcp has only 2 tests
  (McpSseControllerScopedCapabilityTest, ScopedMcpCapabilityServiceTest) — no registry/wrapper tests yet.
- `kompile-cli-common`: has NO metrics package and no estimateTokens; deps are plain
  (jackson, picocli, commons, sqlite, logback, lombok, junit5). Reachable by BOTH app (already
  imported: ToolCallWriterService uses `ai.kompile.cli.common.util.JsonUtils`) and CLI-main →
  **confirmed lower shared module for COMMON/metrics**. App must NOT depend on CLI-main (verified none does).

## 4. MCP SDK metadata strategy (compile-tested)

Resolved versions (dependency:tree on kompile-app-mcp, actual reactor resolution):
- `org.springframework.ai:spring-ai-starter-mcp-client:1.0.0` → `io.modelcontextprotocol.sdk:mcp:0.10.0`
- `spring-ai-starter-mcp-server-webmvc:1.0.0` → `mcp-spring-webmvc:0.10.0`
- `~/.m2` also holds SDK 0.17.0 (split mcp-core/mcp-json-jackson2) — NOT on any app classpath.

javap-verified API shapes:
- 0.10.0 `McpSchema$CallToolResult`: record(content, isError) — **NO `_meta`, no structuredContent**.
  Builder has no meta either. `TextContent`: record(audience, priority, text) — no metadata.
  (0.17.0 CallToolResult implements Result with `meta()` + Map constructor — would be trivial, but
  swapping SDK versions breaks the Spring AI 1.0.0 pairing = forbidden blanket upgrade.)
- 0.10.0 `McpSchema$JSONRPCResponse`: record(jsonrpc, id, **result: Object**, error).
- `McpTransport.sendMessage(JSONRPCMessage)` receives the response BEFORE Jackson encoding.

Strategy (per transport):
1. **CLI stdio / daemon (wire is hand-built JSON):** extend `McpToolResultSerializer.toMcpCallResult`
   with an overload accepting usage; emits `callResult.set("_meta", {"ai.kompile/usage": …})`,
   merging any future unrelated `_meta`. Daemon path inherits automatically (same code).
2. **App SDK 0.10.0 (webmvc SSE/streamable):** a delegating `McpTransport` decorator installed around
   the auto-configured transport: on `JSONRPCResponse` whose `result()` is a `CallToolResult`, substitute
   an equivalent Jackson `ObjectNode` (content/isError) plus `_meta["ai.kompile/usage"]`.
   `result()` is typed `Object` so this compiles/serializes unchanged through the SDK encoder.
   **COMPILE+RUN SMOKE PASSED** against the exact jar set
   (mcp-0.10.0 + reactor-core 3.7.5 + reactive-streams 1.0.4 + jackson 2.18.3):
   `/tmp/kompile-meta-smoke/McpMetaCompileSmoke.java` → `SMOKE OK`.
   Seam wiring: the decorator must wrap the transport before `McpSyncServer` construction
   (`McpServerAutoConfiguration.mcpSyncServer(transportProvider, …)` javap-verified takes the
   provider — so the decorator wraps inside a custom `McpServerTransportProvider` whose
   `sessionFor`/session creation decorates the per-session `McpTransport`; exact wiring point
   verified during Task 4 against `McpSseServerConfiguration`/`McpStdioServerTransportProvider`).
   Fallback if provider-level decoration is blocked by auto-config ordering: `@Primary` bean
   override of the transport provider in kompile-app-mcp config (no SDK upgrade).
3. **Namespaced key** `ai.kompile/usage` preserved through Jackson because it is a plain map key.

## 5. Test commands / prerequisites

- mvn: `/home/agibsonccc/dev-apps/mvn/bin/mvn` (present).
- CLI module tests (from repo root): `mvn -pl kompile-cli/kompile-cli-main test -Dtest=<Class>`
  (+ `-Dsurefire.failIfNoSpecifiedTests=false` when passing -Dtest across a reactor).
  Stale ~/.m2 trap: `mvn install -pl kompile-cli/kompile-cli-common -DskipTests` first when
  COMMON changes, or run with `-pl :kompile-cli-main -am`.
- App module tests: `mvn -pl kompile-app/kompile-app-parent/kompile-app-mcp test` (or `-am`).
- Compile smoke for the SDK seam (already run): javac/java against mcp-0.10.0 jar set → SMOKE OK.
- Native-image: new types must avoid reflection-heavy patterns; app native config lives in
  web-shared META-INF/native-image (check reflect-config when adding Jackson-serialized types).

## 6. Daemon/bridge coverage plan
- Daemon mode runs McpStdioCommand in-process per session (socket session) → stdio instrumentation
  covers it. `DaemonClient.bridgeStdio` is byte-transparent: metrics produced daemon-side flow
  through unchanged; no double-count (bridge performs no tool execution). Task 6 asserts wire `_meta`
  survives the pump (plain `println(line)` of the daemon's JSON).

## 7. Uninstrumented model-usage seams (for Task 7, discovered not implemented)
- ChatSessionMetrics.recordTokenUsage call sites (AgenticChatLoop ×4, SubprocessAgentRunner ×2,
  Passthrough ×4, EmulatedPassthrough ×2, HeadlessAgentRunner override, AuxChatRepl) are conversation-
  scoped, not invocation-scoped: no request id, no correlation to a tool call.
- No tool-attributed usage exists anywhere; no provider `Usage` propagation into tool contexts.
- Model-backed guard/compressor phases (EnforcerToolCallGuard via mcpJudgeControl, LocalJudgeBackend;
  ToolResponseCompressorRegistry is local-only, no model) may incur usage — guard path flagged for
  Task 7; compressor needs verification at implementation time.

## 8. Honest limitations of this checkpoint
- Inventory is bounded to the pack's entry points + symbol searches; transport config classes
  (McpSseServerConfiguration) read only via grep of wiring references, full read deferred to Task 4.
- SDK smoke proves compile+construct+mono-pass, not end-to-end SSE serialization (Task 4 tests cover).
- No tests executed yet in this task beyond the javac smoke (none claimed).
