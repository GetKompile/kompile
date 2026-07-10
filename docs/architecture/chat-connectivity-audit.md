# Chat Interface Connectivity Audit — CLI Agents, Local Models, Graph Tools & Graph RAG

**Date:** 2026-07-02. **Scope:** the main app chat (`#/chat` → `POST /api/agents/chat/stream`), its backend in the
new `kompile-app-agent` module, connectivity for CLI agents and kompile-model-staging local models, and the
availability of graph reasoning / graph tools / graph RAG in chat. All P0 fixes found by this audit were applied
the same day (see "Fixes applied" below); builds + agent-module tests green, `ng build` green.

---

## 1. Architecture as audited

### Chat lanes

| Lane | Entry | Executor | Transport |
|---|---|---|---|
| One-shot CLI agent | `POST /api/agents/chat/stream` → `AgentChatService` | `AgentSubprocessExecutor` (ProcessBuilder, `-p` prompt / codex `exec` / gemini prompt-file) | SSE |
| API / local model | same entry, `agent.isApiAgent()` | `ApiAgentChatExecutor` → OpenAI-compatible `/chat/completions` | SSE |
| Passthrough (multi-turn interactive) | `GET /api/agents/passthrough/connect` | `PassthroughSessionManager` (piped stdin/stdout) | SSE |

- Agent inventory: `AgentRegistryService` ← `cli-agents.json` (claude default, codex, gemini, opencode, qwen, pi)
  + dynamic API agents (`~/.kompile/config/api-agents.json`) + runtime registrations (`kompile-local`).
- The `kompile-app-agent` module extraction is sound: parent pom lists it, app-main depends on it, package
  preserved, no duplicate sources left in app-main (two stale `.orig` files were deleted by this audit).
- SSE vocabulary (CLI lane): `query_info, corrective_retrieval, sources, evidence_insufficient, chunk, rag_metrics,
  start, cancelled, stats, reasoning_trace, claim_verification, citations, complete, error` + now `tool_use`.

### Local models (kompile-model-staging, :8090)

Two independent bridges — both HTTP-only; the staging isolation mandate holds (no maven dependency on
`kompile-model-staging` anywhere in app-main/app-agent, guarded by pom comments):

1. **Chat lane**: `KompileLocalModelService` registers agent `kompile-local` (AgentType.API,
   endpoint `{staging}/v1`) from staging's OpenAI-compatible `/v1/models` + `/v1/chat/completions`.
2. **Crawl/serving lane** (not chat): `StagingServingBridge` (app-main) polls `/api/staging/active`, downloads the
   active `llm_ggml` into `~/.kompile/llm-cache`, and hot-loads the serving subprocess (:8091) via
   `ServingSubprocessLauncher`; consumed by `CrawlLlmDispatcher` through the `LocalServingBackend` SPI
   (agentName="serving"). `LocalStagingLlmService` (`local-staging` pseudo-agent, `/api/llm/generate`) is the
   crawl-fallback path — intentionally not a chat agent.

### Graph reasoning infra reachable from chat

- **Graph RAG**: request flags `enableGraphRag`, `graphRagSearchType` (LOCAL | GLOBAL | HYBRID via
  `GraphRagService`; CAUSAL | PROBABILISTIC via `GraphReasoningRetriever.retrieveWithTrail` → EventAttribution /
  MEBN), `graphRagMaxResults`. UI has the toggle + type selector. CRAG corrective escalation ladder
  LOCAL→HYBRID→GLOBAL; sufficiency-gate abstention; citation contract; grounded answer verification — all live in
  the chat path.
- **Reasoning trails**: engines push `ReasoningTrail` → `ReasoningTraceStore` → drained per-turn → `reasoning_trace`
  SSE → rendered by `ReasoningTrailComponent` (compact mode) in chat messages.
- **REST surface** (backing the CLI `ask_graph_*` tools and the UI): `/api/kb-grounding/*` (verify, query, explain,
  assert, synthesize), `/api/explain` (+`/fused`), `/api/attribution/{psl,bayesian}/*` (incl. MEBN query/theory,
  sensitivity, what-if), `/api/graph-rag/search`, `/api/graph-ontology/*`, `/api/graph-sim/*`, `/api/kb/weights/*`.

### MCP servers in the app

- **`/mcp/sse`** (same HTTP port): real MCP protocol server (SSE 2024-11-05 + Streamable HTTP 2025-03-26),
  `McpServerConfig` + `SpringMvcSseServerTransport`; tools from `McpToolRegistry` (curated bean list).
- **`/api/mcp/*`**: REST management/diagnostic surface (`BuiltInToolDiscoveryService` scans all Spring `@Tool`
  beans; invoke-direct endpoint). Not an MCP protocol endpoint.

---

## 2. Connectivity verdicts (before → after fixes)

### CLI agents ↔ chat
**Worked**: spawn, streaming parse, model selection, RAG/graph-RAG augmentation.
**Broken**: MCP tool injection. `cli-agents.json` preset `mcpServerFlag: "--mcp-server"` for claude — a flag the
real CLI does not have (verified: `claude --help` has `--mcp-config`, no `--mcp-server`). With tool discovery
non-empty the spawn died on "unknown option"; the config-file path was an explicit TODO no-op. The help-parse also
never cleared bogus presets and its regex fallback could latch `--mcp-debug`. **Fixed** (see below): chat/passthrough/
enforcer-spawned claude now gets `--mcp-config ~/.kompile/config/agent-mcp-config.json` pointing at `{base}/mcp/sse`.

### Local models (staging) ↔ chat
**Worked**: the full request path UI → `kompile-local` → staging `/v1/chat/completions` → GGUF inference.
**Broken**: registration was a **one-shot** `@PostConstruct` +2s attempt. If staging had no model loaded at app
startup, `kompile-local` never appeared, and later loads/promotes never registered it (only manual
`POST /api/agents/kompile-local/discover`). **Fixed**: continuous change-driven poll (30s default,
`kompile.chat.local-model.poll-seconds`, ≤0 disables) — registers on appearance, re-registers on model swap,
unregisters on unload/unreachable; silent in steady state.

### Graph tools ↔ chat
**Broken twice over**:
1. Injection never delivered a working MCP connection (above).
2. Even with a connection, the `/mcp/sse` tool roster had **zero graph tools**: both `McpToolRegistry` and
   `McpSseServerConfiguration` are curated lists that never included them, and the entire `kompile-tool-graph`
   module (GraphSearch/Mutation/Traversal/Community/Algorithms/Label/NamedGraph tools) was **not a dependency of
   any module** — dead code since creation. `KbVerifyExplainTool` and `KnowledgeGraphToolImpl` were beans but
   unlisted.
**Fixed**: `kompile-tool-graph` added to app-main; 11 graph/KB tool beans registered on both MCP paths; new
`KbGroundingTool` adds `kb_query` (conjunctive query) + `kb_assert` (TMS-checked assert) as in-process
counterparts of the CLI's `ask_graph_query`/`ask_graph_assert`, against the same live `KbGroundingService`.
Chat agents now get: `kb_verify_explain`, `kb_query`, `kb_assert`, `knowledge_search`, 8× knowledge-graph search
tools, and the graph mutation/traversal/community/algorithms/label/named-graph tool families — alongside RAG tools.

### Graph RAG ↔ chat
Was already wired end-to-end (flags → retrievers → prompt context → sources/citations/trails → UI). Two drops fixed:
- **API/local-model lane dropped `reasoning_trace`**: retrieval pushed trails but only the CLI path drained them
  (they also leaked into the next turn's drain window). Now drained + emitted before the API executor runs.
- **Citation → graph navigation dead**: `viewSourceInGraph()` (chat) and process-diagram both navigated to
  `/knowledge-graph`, a route that didn't exist (silently redirected to chat). Added `#/knowledge-graph` →
  `KnowledgeGraphPageComponent` (thin routed host for `GraphsHubComponent`, `?nodeId=` → `focusNodeId` → visualizer).

---

## 3. Fixes applied (2026-07-02)

| # | Change | Files |
|---|---|---|
| F1 | MCP injection: prefer `--mcp-config <file>` (generated at `~/.kompile/config/agent-mcp-config.json`, claude-compatible shape, target `{base}/mcp/sse`); help-parse now clears unadvertised preset flags and skips the loose regex fallback when a config flag is known; removed bogus `--mcp-server` preset for claude; new `ServerPortService.getMcpSseUrl()` | `AgentSubprocessExecutor`, `AgentRegistryService`, `cli-agents.json`, `ServerPortService` |
| F2 | Graph tools on both MCP server paths + new `kb_query`/`kb_assert` tool; `kompile-tool-graph` finally wired into the app | `McpToolRegistry`, `McpSseServerConfiguration`, new `KbGroundingTool`, app-main `pom.xml` |
| F3 | `kompile-local` continuous re-discovery (30s poll, change-driven, quiet) | `KompileLocalModelService` |
| F4 | API lane emits `reasoning_trace`; one-shot CLI lane emits structured `tool_use` SSE (same shape as passthrough; text-pattern rendering unchanged) | `AgentChatService` |
| F5 | `#/knowledge-graph` route + routed Graphs-hub host page (fixes chat + process-diagram deep links) | `knowledge-graph-page.component.ts`, `app-routing.module.ts` |
| F7 | Deleted `ClaudeStreamParser.java.orig`, `CliAgentModelService.java.orig` | kompile-app-agent |

**Verification**: `kompile-app-platform`/`-core`/`-agent` install green; app-main compile green (UI skipped in that
pass); `ng build --configuration production` green; kompile-app-agent tests 26/26 green; claude CLI flag reality
confirmed against the new parse logic. Not yet done: live-app boot e2e (start app → confirm `[MCP] Registered N tools`
grows by ~40 graph tools → chat turn with claude calling `kb_verify_explain` → verify `@ConditionalOnBean` ordering
actually instantiates the kompile-tool-graph beans at boot).

---

## 4. Remaining gaps (known, not blocking, in rough priority order)

1. **API/local-model lane has no tool-calling loop** — `ApiAgentChatExecutor` sends no `tools` field and has no
   dispatch loop, so API-backed and staging-served models cannot call graph tools; they get graph context only via
   graph-RAG prompt augmentation. A tool loop would also need staging's `/v1/chat/completions` to emit tool calls
   (GGUF grammar support — currently absent). Design decision, not a wiring bug.
2. **gemini/qwen/opencode MCP wiring** — these CLIs configure MCP via settings files (`.gemini/settings.json`,
   `opencode.json`), not CLI flags; injection currently reaches only claude (the default agent). Port the CLI-side
   `McpToolInjection` writer if chat-spawned gemini/opencode should get tools too.
3. **Referencing layer (`ReferenceResolver`/`DisplayRef`/`displayRef` pipe) is spec-only** (WP48/49 in
   `reasoning-math-composition-implementation-plan.md`); chat still shows truncated raw `documentId` prefixes in
   sources.
4. **PSL / PathRAG / PPR / OWL not reachable as chat retrieval modes** — only via tools (`kb_verify_explain
   mode=PSL`, `ask_graph_fused`) or REST; `graphRagSearchType` accepts LOCAL/GLOBAL/HYBRID/CAUSAL/PROBABILISTIC.
5. **Chat history**: `AgentChatRequest.chatHistory` is honored only by the API lane; the one-shot CLI lane neither
   replays it nor persists server-side (CLI process owns its own session state).
6. **`ReasoningTraceStore` drain windows are timestamp-based** — concurrent chat turns can interleave traces
   (acknowledged in-code; narrow window).
7. **Frontend polish**: `UnifiedChatComponent` still renders tool calls from text patterns only (the new `tool_use`
   events are parsed by `LocalAgentChatService` but unsubscribed); `files_modified`/`result` events likewise; the
   reasoning trail is compact-mode only in chat; no fact-sheet picker inside the chat sidebar (global picker exists).
8. **Serving subprocess (:8091) is crawl-only** — by design it has no OpenAI endpoint; chat reaches local models
   through staging (:8090). If chat should ever use the serving lane, it needs a `/v1/chat/completions` there plus
   registry exposure.
