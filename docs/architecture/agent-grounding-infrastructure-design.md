# Agent-Grounding Infrastructure Design
## Kompile Production Knowledge Base for LLM Agent Grounding

**Date**: 2026-06-21  
**Status**: DESIGN — no code written yet  
**Prerequisite reading**: [`production-fol-kb-agent-grounding-gaps.md`](production-fol-kb-agent-grounding-gaps.md) — gap matrix, roadmap, and citations are NOT repeated here  
**Core loop**: `LLM agent → MCP tool (ask_graph_*) → production KB → grounded result (confidence + evidence)`  
**Invariant**: `kompile-graph-reasoning` stays infra-free. All grounding infrastructure is a **client** of that lib, living in kompile-app / kompile-cli modules.

---

## Architecture Overview

```
┌───────────────────────────────────────────────────────────────────┐
│  L1  MCP Tool Surface  (kompile-cli / kompile-cli-main)           │
│  ask_graph_verify · ask_graph_query · ask_graph_explain           │
│  ask_graph_assert · ask_graph_subscribe                            │
└──────────────────────────────────┬────────────────────────────────┘
                                   │ HTTP/REST (same pattern as
                                   │ KnowledgeGraphTool, GraphRagSearchTool)
┌──────────────────────────────────▼────────────────────────────────┐
│  L2  Query / Grounding Engine  (kompile-knowledge-graph, NEW)     │
│  KbGroundingService · ConjunctiveQueryEngine · KbVerifier         │
│  ← KnowledgeGraphReasoningAdapter · ← EntailmentEngine (lib)     │
└──────────────────────────────────┬────────────────────────────────┘
                                   │ Spring events + direct call
┌──────────────────────────────────▼────────────────────────────────┐
│  L3  Live-Graph Cascade Pipeline  (kompile-graph-change-tracking) │
│  GroundingCascadeHook · IncrementalReasoningOrchestrator          │
│  ← GraphRuleHook · ← GraphUpdateChannelBridge · ← CrawlJob       │
└──────────────────────────────────┬────────────────────────────────┘
                                   │
┌──────────────────────────────────▼────────────────────────────────┐
│  L4  Domain-Object Generation  (kompile-process-discovery + NEW)  │
│  GroundedDomainObjectService · ProcessSuggestion + confidence     │
│  Rule derivation · Ontology binding · Business process extraction │
└──────────────────────────────────┬────────────────────────────────┘
                                   │
┌──────────────────────────────────▼────────────────────────────────┐
│  L5  Integration + Deployment  (kompile-knowledge-graph,          │
│       kompile-app-core, kompile-app-main)                         │
│  Persistence · Fact-sheet scoping · Module wiring                 │
└───────────────────────────────────────────────────────────────────┘
```

---

## L1 — Grounding Contract + MCP Surface

### Purpose
Expose the production KB to LLM agents as a set of named MCP tools with well-defined schemas, semantics, and response envelopes. This is the **only API surface the agent sees**. All tools return `confidence`, `evidence`, and `explanation` as first-class fields.

### Existing components reused

| Component | Path | Role |
|---|---|---|
| `CliTool` interface | `kompile-cli-main/.../chat/tools/CliTool.java:26` | All new tools implement this |
| `ToolRegistry` | `kompile-cli-main/.../chat/tools/ToolRegistry.java:31` | Registration point |
| `McpToolAnnotations` | `kompile-cli-main/.../chat/tools/McpToolAnnotations.java:32` | Hint metadata (read-only vs write) |
| `KnowledgeGraphTool` | `kompile-cli-main/.../chat/tools/KnowledgeGraphTool.java:40` | Pattern for HTTP dispatch to REST backend |
| `GraphRagSearchTool` | `kompile-cli-main/.../chat/tools/GraphRagSearchTool.java:37` | Pattern for search-style tools |
| `KompileBackendClient` | `kompile-cli-main/.../chat/tools/KompileBackendClient.java` | Auto-detect + reconnect HTTP client |

### New tools (all NEW, live in `kompile-cli-main/.../chat/tools/`)

All five tools are thin HTTP dispatchers — they call `POST /api/kb-grounding/<action>` on the kompile-app backend (L2) and return a `GroundingResult` JSON envelope. The tool implementations are <100 lines each.

#### Tool: `ask_graph_verify`

```
id:          "ask_graph_verify"
annotations: McpToolAnnotations.READ_ONLY   // read-only hint; no graph mutation
description: "Verify a factual claim against the production knowledge base.
              Returns SUPPORTED, REFUTED, or UNKNOWN with a calibrated confidence
              score [0,1] and the evidence atom keys + activated rules that justify
              the verdict. Use this to check LLM output before accepting it."
```

**Input schema (JSON)**:
```json
{
  "type": "object",
  "required": ["atom"],
  "properties": {
    "atom":          { "type": "string",  "description": "e.g. 'isEmployedBy(Alice,Acme)'" },
    "factSheetId":   { "type": "integer", "description": "Scope to a specific fact sheet; null = all" },
    "asOf":          { "type": "string",  "description": "ISO-8601 instant for temporal point-in-time" },
    "minConfidence": { "type": "number",  "description": "Threshold below which UNKNOWN is returned; default 0.5" }
  }
}
```

**Response envelope** (returned as JSON tool result):
```json
{
  "verdict":       "SUPPORTED | REFUTED | UNKNOWN",
  "confidence":    0.87,
  "evidenceAtoms": ["worksAt(Alice,Acme_NYC)", "subsidiary(Acme_NYC,Acme)"],
  "activatedRules": ["0.9: worksAt(?X,?Z) :- worksAt(?X,?Y) & subsidiary(?Y,?Z)"],
  "derivationDepth": 2,
  "factSheetId":   42,
  "asOf":          "2026-06-21T10:00:00Z"
}
```

**Semantics**: The tool does **not** trigger MAP re-inference on every call (see L2 fast-path). It first hits the materialized `InferredFactStore`; only if absent does it trigger targeted inference for that atom.

#### Tool: `ask_graph_query`

```
id:          "ask_graph_query"
annotations: McpToolAnnotations.READ_ONLY
description: "Pattern-match the knowledge base with conjunctive variable binding.
              E.g.: find all ?Person who worksFor ?Company AND hasSkill 'AI'.
              Returns a list of binding maps with per-row confidence."
```

**Input schema**:
```json
{
  "type": "object",
  "required": ["conjuncts"],
  "properties": {
    "conjuncts": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["predicate", "args"],
        "properties": {
          "predicate": { "type": "string" },
          "args":      { "type": "array", "items": { "type": "string" },
                         "description": "Use '?Name' for variables, bare string for constants" }
        }
      }
    },
    "factSheetId":  { "type": "integer" },
    "asOf":         { "type": "string"  },
    "maxResults":   { "type": "integer", "default": 50 },
    "minConfidence":{ "type": "number",  "default": 0.3 }
  }
}
```

**Response**:
```json
{
  "bindings": [
    { "?Person": "Alice", "?Company": "Acme", "confidence": 0.91 },
    { "?Person": "Bob",   "?Company": "Acme", "confidence": 0.74 }
  ],
  "total": 2,
  "truncated": false
}
```

#### Tool: `ask_graph_explain`

```
id:          "ask_graph_explain"
annotations: McpToolAnnotations.READ_ONLY
description: "Produce a derivation trace for why the KB believes (or disbelieves)
              a specific fact. Returns a derivation tree with supporting atoms,
              rule labels, and an NL summary."
```

**Input schema**:
```json
{
  "type": "object",
  "required": ["atom"],
  "properties": {
    "atom":        { "type": "string"  },
    "factSheetId": { "type": "integer" },
    "depth":       { "type": "integer", "default": 3, "description": "Max derivation hops" }
  }
}
```

**Response**:
```json
{
  "atom":       "isEmployedBy(Alice,Acme)",
  "confidence": 0.87,
  "summary":    "Alice is employed by Acme because she works at Acme NYC, which is a subsidiary of Acme Corp.",
  "derivation": {
    "node": "isEmployedBy(Alice,Acme)",
    "rule":  "0.9: worksAt(?X,?Z) :- worksAt(?X,?Y) & subsidiary(?Y,?Z)",
    "children": [
      { "node": "worksAt(Alice,Acme_NYC)", "source": "crawl:doc-4417", "confidence": 0.95 },
      { "node": "subsidiary(Acme_NYC,Acme)", "source": "crawl:doc-8801", "confidence": 0.92 }
    ]
  }
}
```

The `summary` field is produced by `ExplanationService` (`explain/ExplanationService.java:31`) wired to whatever impl the host app registers (LLM-backed impl lives in `kompile-event-attribution`; if absent, a deterministic verbalization of the derivation tree is used instead).

#### Tool: `ask_graph_assert`

```
id:          "ask_graph_assert"
annotations: McpToolAnnotations.WRITE
description: "Assert a new fact into the knowledge base from agent output.
              The fact is recorded with the agent session as provenance.
              TMS contradiction-checking runs synchronously. Background re-reasoning
              is triggered asynchronously via the cascade pipeline (L3)."
```

**Input schema**:
```json
{
  "type": "object",
  "required": ["atom", "value"],
  "properties": {
    "atom":        { "type": "string",  "description": "e.g. 'isEmployedBy(Alice,Acme)'" },
    "value":       { "type": "number",  "description": "[0,1] soft-truth; 1.0 = hard fact" },
    "factSheetId": { "type": "integer" },
    "sessionId":   { "type": "string",  "description": "Agent session for provenance tracking" },
    "source":      { "type": "string",  "description": "Human-readable provenance label" }
  }
}
```

**Response**:
```json
{
  "status":         "ASSERTED | CONTRADICTION_DETECTED | CONFLICT_QUEUED",
  "version":        7,
  "contradictions": [],
  "cascadeTriggered": true
}
```

**Semantics**: The assert path calls `ConcurrentFactStore.assertFact` (L2), then runs `ContradictionDetector` (`tms/ContradictionDetector.java:36`) synchronously before returning. If no contradiction, it publishes an `AgentFactAssertedEvent` (NEW Spring event) that triggers the L3 incremental cascade asynchronously. The agent gets a fast response; re-reasoning happens in the background.

#### Tool: `ask_graph_subscribe` _(Phase 2 — not P0)_

```
id:          "ask_graph_subscribe"
annotations: McpToolAnnotations.NETWORK
description: "Subscribe to KB changes matching a predicate pattern.
              Returns a subscription ID. Changes are delivered via SSE
              at /api/kb-grounding/subscribe/{subscriptionId}/events."
```

This follows the same SSE pattern as the existing crawl progress event stream (`CrawlProgressEvent`, SSE controller) already in the codebase. Deferred to Phase 2; the P0 tools above do not depend on it.

### Where L1 lives

New class `AskGraphToolFamily.java` in `kompile-cli-main/.../chat/tools/` registers all five tools via `ToolRegistry`. The REST backend calls go to `kompile-app-main` (`/api/kb-grounding/*`), handled by a new `KbGroundingController` in `kompile-app-main` (L2 wiring point). Same `GlobalExceptionHandler` scope issue applies — add `ai.kompile.app.web.controllers.grounding` to `basePackages` (see `reference_global_exception_handler_scope` memory entry).

---

## L2 — Query / Grounding Engine

### Purpose
Answer `verify`, `query`, and `explain` requests against the live materialized KB. Two execution paths: (a) **fast path** — O(1) lookup in the materialized `InferredFactStore` (sub-10ms, no inference); (b) **targeted inference path** — invokes a scoped `EntailmentEngine` run on the seed subgraph (100ms–2s, triggered only on cache miss). The engine is the **bridge between the infra-free lib and the Spring-managed graph stores**.

### Existing components reused

| Component | Path | Role |
|---|---|---|
| `InferredFactStore` SPI | `fol/InferredFactStore.java:29` | Primary cache for materialized facts |
| `InMemoryInferredFactStore` | lib | Default runtime impl |
| `EntailmentEngine` | `fol/EntailmentEngine.java:55` | Runs PSL or MEBN entailment |
| `InferredFact` | `fol/InferredFact.java:47` | Carries confidence + evidence atom keys + rule weights |
| `KnowledgeGraphReasoningAdapter` | `kompile-knowledge-graph/.../reasoning/KnowledgeGraphReasoningAdapter.java:54` | Projects live KG → `ReasoningGraph` from seed nodes |
| `ContradictionDetector` | `tms/ContradictionDetector.java:36` | Synchronous contradiction check on assert |
| `BeliefReviser` | `tms/BeliefReviser.java:34` | Retract + re-derive on agent correction |
| `JustificationIndex` | `tms/JustificationIndex.java:33` | Derivation lineage for explain |
| `TemporalView` | `model/TemporalView.java:57` | Point-in-time KB slice for `asOf` queries |
| `FactStore` | `fol/FactStore.java:38` | Observed (asserted) facts |

### New components (all in `kompile-knowledge-graph` module)

#### `KbVerifier` (NEW — lib-level, no Spring)

As specified in the gaps doc (`production-fol-kb-agent-grounding-gaps.md:338`), this is a **lib addition** — it is infra-free and lives in `kompile-graph-reasoning`:

```java
// kompile-graph-reasoning/.../fol/KbVerifier.java  (NEW, lib-level)
public interface KbVerifier {
    enum Verdict { SUPPORTED, REFUTED, UNKNOWN }
    record VerifyResult(
        Verdict verdict,
        double confidence,
        List<String> evidenceAtomKeys,
        List<String> activatedRules,
        int derivationDepth
    ) {}
    VerifyResult verify(String atomKey);
    VerifyResult verify(String predicate, String... args);
}
```

Implementation (`DefaultKbVerifier.java`, lib-level):
1. Check `InferredFactStore.latest(atomKey)` — if present and `value >= minConfidence` → `SUPPORTED` with confidence from `InferredFact.confidence`.
2. Check for negated atom (`"~" + atomKey`) in `InferredFactStore` — if present with high confidence → `REFUTED`.
3. Check `FactStore` for directly observed fact (hard assertion).
4. If all cache misses: return `UNKNOWN` (caller decides whether to escalate to targeted inference).
5. The targeted-inference escalation lives one layer up in `KbGroundingService` (Spring side) — the lib stays infra-free.

#### `ConjunctiveQueryEngine` (NEW — lib-level)

```java
// kompile-graph-reasoning/.../fol/ConjunctiveQueryEngine.java  (NEW, lib-level)
public final class ConjunctiveQueryEngine {
    public record AtomPattern(String predicate, List<Term> args) {}
    public record QueryResult(List<Map<String, String>> bindings, boolean truncated) {}
    
    public QueryResult query(List<AtomPattern> conjuncts, ReasoningGraph graph,
                             InferredFactStore store, int maxResults) { ... }
}
```

Implementation: extract and generalize the backtracking conjunctive join already implemented in `psl/PslProgram.groundInto` (which does exactly this for grounding). Instead of emitting `GroundRule` objects it returns variable-binding maps. Confidence per row = min confidence of all matched atoms (Łukasiewicz T-norm, consistent with PSL semantics). Estimated: ~200 lines, reuses the existing join kernel.

#### `KbGroundingService` (NEW — Spring `@Service`, in `kompile-knowledge-graph`)

This is the **Spring-side orchestrator** for L2. It holds `@Autowired` references to the live `KnowledgeGraphService` (the `@Primary` matrix/vector store) and wraps all lib calls.

```java
@Service
public class KbGroundingService {
    // Injected
    private final KnowledgeGraphService graphService;
    private final InferredFactStore inferredFactStore;   // per-factSheet, keyed by factSheetId
    private final FactStore observedFactStore;
    private final PslProgram pslProgram;                 // per-factSheet

    // Fast path: O(1) lookup
    public KbVerifier.VerifyResult verify(String atomKey, Long factSheetId, Instant asOf) { ... }

    // Targeted inference on cache miss (escalation path)
    public KbVerifier.VerifyResult verifyWithInference(String atomKey, Long factSheetId,
                                                        Collection<String> seedNodeIds) { ... }

    // Conjunctive pattern query
    public ConjunctiveQueryEngine.QueryResult query(List<ConjunctiveQueryEngine.AtomPattern> conjuncts,
                                                     Long factSheetId, Instant asOf, int maxResults) { ... }

    // Explain derivation
    public DerivationTree explain(String atomKey, Long factSheetId, int maxDepth) { ... }

    // Assert + contradiction check + cascade trigger
    public AssertResult assertFact(String atomKey, double value, Long factSheetId,
                                    String sessionId, String source) { ... }
}
```

**Multi-factSheet state management**: Each fact sheet has its own `InferredFactStore` and `PslProgram`. `KbGroundingService` maintains a `ConcurrentHashMap<Long, FactSheetKbState>` where `FactSheetKbState` holds the per-fact-sheet `InferredFactStore`, `FactStore`, `PslProgram`, and `IncrementalGrounder`. This map is populated lazily on first access and rebuilt from the materialized graph store on restart.

#### `ConcurrentFactStore` (NEW — lib-level)

Thin thread-safe wrapper around `FactStore` using `ConcurrentHashMap` + `AtomicLong` version counter. Provides optimistic-lock `assertFact(fact, expectedVersion) → boolean`. This is the P0-3 fix from the gaps doc (`production-fol-kb-agent-grounding-gaps.md:364`). Lives in `kompile-graph-reasoning` — no Spring.

#### `DerivationTree` (NEW — lib-level)

```java
// kompile-graph-reasoning/.../tms/DerivationTree.java  (NEW, lib-level)
public record DerivationTree(
    String atomKey,
    double confidence,
    String ruleApplied,        // null for observed facts
    String sourceProvenance,   // crawlRunId / sessionId
    List<DerivationTree> children
) {
    public static DerivationTree build(String atomKey, InferredFactStore store,
                                       JustificationIndex index, int maxDepth) { ... }
    public String verbalize() { ... }  // deterministic NL from tree structure
}
```

`JustificationIndex.supportingFacts(atomKey)` provides the support edge set; `InferredFactStore.latest(key)` retrieves child nodes. The recursive build terminates at `maxDepth` or at observed (non-derived) facts. `verbalize()` is a deterministic template renderer — no LLM required for basic explanation; `ExplanationService` (`explain/ExplanationService.java:31`) is used only when a richer NL summary is needed.

#### REST controller: `KbGroundingController` (NEW — `kompile-app-main`)

```java
@RestController
@RequestMapping("/api/kb-grounding")
public class KbGroundingController {
    @PostMapping("/verify")     public ResponseEntity<VerifyResponse> verify(...)   { ... }
    @PostMapping("/query")      public ResponseEntity<QueryResponse>  query(...)    { ... }
    @PostMapping("/explain")    public ResponseEntity<ExplainResponse> explain(...) { ... }
    @PostMapping("/assert")     public ResponseEntity<AssertResponse> assertFact(...) { ... }
    @GetMapping("/subscribe/{id}/events")  // SSE, Phase 2
    public SseEmitter subscribe(@PathVariable String id) { ... }
}
```

**Lib-vs-kompile-infra split at L2**: `KbVerifier`, `ConjunctiveQueryEngine`, `ConcurrentFactStore`, `DerivationTree` are **lib additions** (infra-free, no Spring). `KbGroundingService` and `KbGroundingController` are **kompile-infra clients** (Spring, app-main).

### Data flow (verify fast path)

```
ask_graph_verify(atom, factSheetId)
  → POST /api/kb-grounding/verify
  → KbGroundingController.verify()
  → KbGroundingService.verify(atomKey, factSheetId, asOf)
      → inferredFactStore[factSheetId].latest(atomKey)         // O(1)
      → if hit: return VerifyResult(SUPPORTED, confidence, evidenceAtomKeys, ...)
      → if miss: return VerifyResult(UNKNOWN)  [or escalate]
  ← VerifyResponse JSON
← tool result: { verdict, confidence, evidenceAtoms, activatedRules }
```

### Data flow (verify with inference — cache miss escalation)

```
KbGroundingService.verifyWithInference(atomKey, factSheetId, seedNodes)
  → KnowledgeGraphReasoningAdapter.subgraph(seedNodes)     // BFS from seeds
  → EntailmentEngine.entailFromPsl(program, factStore)     // scoped MAP solve
  → InferredFactMaterializer.materialize(facts, graph)     // write-back to InferredFactStore
  → DefaultKbVerifier.verify(atomKey)                      // now hits the cache
  ← VerifyResult
```

---

## L3 — Live-Graph Cascade Pipeline

### Purpose
When the graph changes (from crawl, channel message, or agent assert), re-reason **only the affected subgraph** and propagate the updated materialization back into the `InferredFactStore` so that the next `verify` call sees fresh results. Concurrency model: **read-many / write-serialized per factSheet**. A single email arriving triggers one cascade; concurrent email bursts are queued per factSheet.

### Existing components reused

| Component | Path | Role in cascade |
|---|---|---|
| `GraphChangesetCompletedEvent` | `kompile-graph-change-tracking/.../event/GraphChangesetCompletedEvent.java:7` | Fired after crawl/extraction writes nodes+edges; carries `factSheetId`, `nodesCreated`, `edgesCreated` |
| `GraphMutationEvent` | `kompile-graph-change-tracking/.../event/GraphMutationEvent.java` | Per-mutation (individual node/edge) event |
| `GraphRuleHook` | `kompile-graph-change-tracking/.../hook/GraphRuleHook.java:30` | Already listens to `GraphChangesetCompletedEvent`; fires `GraphActionService` (LOG/WEBHOOK) |
| `GraphUpdateChannelBridge` | `kompile-graph-change-tracking/.../channel/GraphUpdateChannelBridge.java` | Inbound channel message → graph update hook |
| `ChannelMessageReceivedEvent` | `kompile-agent-gateway-core/.../channel/ChannelMessageReceivedEvent.java:22` | Source event for email/Slack/etc |
| `KClawAutoConfiguration` | `kompile-kclaw/.../config/KClawAutoConfiguration.java:55` | Wires channel adapters with `eventPublisher` |
| `IncrementalGrounder` | `kompile-graph-reasoning/.../psl/IncrementalGrounder.java:45` | Adds/removes atoms, re-grounds only affected rules |
| `BeliefReviser` | `tms/BeliefReviser.java:34` | Retract-and-revise for agent corrections |
| `ContradictionDetector` | `tms/ContradictionDetector.java:36` | Synchronous contradiction check |
| `UnifiedCrawlJob` | `kompile-app-core/.../crawl/graph/UnifiedCrawlJob.java:41` | Crawl produces `GraphChangesetCompletedEvent` on completion |

### New components

#### `AgentFactAssertedEvent` (NEW Spring event)

```java
// kompile-graph-change-tracking/.../event/AgentFactAssertedEvent.java
public class AgentFactAssertedEvent extends ApplicationEvent {
    private final String atomKey;
    private final double value;
    private final Long factSheetId;
    private final String sessionId;
    private final String sourceProvenance;
}
```

This is the third event source (alongside crawl's `GraphChangesetCompletedEvent` and channel's `ChannelMessageReceivedEvent`) that triggers the cascade. Published by `KbGroundingService.assertFact` after the synchronous contradiction check passes.

#### `GroundingCascadeHook` (NEW `@Component` in `kompile-graph-change-tracking`)

```java
@Component
public class GroundingCascadeHook {

    @EventListener
    @Async("groundingCascadeExecutor")
    public void onChangeset(GraphChangesetCompletedEvent event) {
        // fired by crawl/extraction completion
        schedule(event.getFactSheetId(), CascadeScope.FULL_FACTSHEET);
    }

    @EventListener
    @Async("groundingCascadeExecutor")
    public void onAgentAssert(AgentFactAssertedEvent event) {
        // fired by ask_graph_assert
        schedule(event.getFactSheetId(), CascadeScope.DELTA_ATOMS);
    }

    @EventListener
    @Async("groundingCascadeExecutor")
    public void onChannelMessage(ChannelMessageReceivedEvent event) {
        // fired after GraphUpdateChannelBridge has applied the channel delta
        // The channel bridge already maps message → graph update;
        // GroundingCascadeHook sees the downstream GraphChangesetCompletedEvent
        // (no separate listener needed here — covered by onChangeset above)
    }
}
```

Note: for channel messages, `GraphUpdateChannelBridge` (which already runs as `@Async @EventListener`) produces graph mutations that emit `GraphChangesetCompletedEvent` via `GraphChangeTrackingAutoConfiguration`. `GroundingCascadeHook.onChangeset` therefore also catches the email/channel path without a separate listener. **No new wiring needed in KClawAutoConfiguration for the cascade.**

#### `IncrementalReasoningOrchestrator` (NEW `@Service` in `kompile-knowledge-graph`)

This is the engine for the cascade. It is called by `GroundingCascadeHook` via an application event; it is NOT a Spring event listener itself (separation of concerns).

```java
@Service
public class IncrementalReasoningOrchestrator {

    public void runDelta(Long factSheetId, Set<String> changedAtomKeys) {
        FactSheetKbState state = kbGroundingService.getState(factSheetId);
        // 1. Map changed atom keys to seed node IDs
        Set<String> seedNodes = atomKeysToNodeIds(changedAtomKeys, factSheetId);
        // 2. Build affected subgraph via KnowledgeGraphReasoningAdapter
        ReasoningGraph subgraph = new KnowledgeGraphReasoningAdapter(graphService)
                .maxDepth(2).subgraph(seedNodes);
        // 3. Delta-ground: for each changed atom, call IncrementalGrounder.addAtom / removeAtom
        changedAtomKeys.forEach(key -> state.grounder().addAtom(toPslAtom(key), lookupValue(key)));
        // 4. Re-solve the scoped MAP problem (only ground rules involving changedAtomKeys)
        HlMrfMapInference.Result result = new ScalarHlMrfInference().solve(state.pslProgram());
        // 5. Materialize new InferredFacts into InferredFactStore
        List<InferredFact> newFacts = EntailmentEngine.entailFromPslResult(
                state.pslProgram(), result, state.factStore(), UUID.randomUUID().toString());
        newFacts.forEach(f -> state.inferredFactStore().store(f));
        // 6. Contradiction scan on changed atoms
        List<Contradiction> contradictions = ContradictionDetector.detect(result, 1e-6);
        if (!contradictions.isEmpty()) publishContradictionEvent(factSheetId, contradictions);
    }

    public void runFull(Long factSheetId) {
        // Used after a crawl changeset: full MAP solve over the entire factSheet's program
        // Same flow as runDelta but seedNodes = all nodes in factSheet
        ...
    }
}
```

**Consistency model under concurrent agent writes**: `FactSheetKbState` holds a `ReentrantReadWriteLock`. Multiple concurrent `verify`/`query` calls acquire the read lock (nonblocking). `IncrementalReasoningOrchestrator.runDelta` acquires the write lock for the duration of steps 3–6. An `ask_graph_assert` from a second agent during a cascade write is queued in the `groundingCascadeExecutor` thread pool (bounded queue per factSheet; reject policy = caller gets `CONFLICT_QUEUED` response and the fact is staged for the next cascade cycle). This gives **per-factSheet serialized writes, parallel reads** — the same isolation model the live `KnowledgeGraphService` already uses for the matrix store.

### Email cascade walkthrough (the motivating example)

```
1. Email arrives at EmailChannelAdapter (KClawAutoConfiguration wires the publisher)
2. ChannelMessageReceivedEvent published → GraphUpdateChannelBridge.onChannelMessage()
3. GraphUpdateChannelBridge matches pipeline config, calls hookRegistry.executeChannelMessage()
   → MultiAgentExtractionService.runExtraction() → graph nodes/edges written via createNode(6-arg)
4. GraphChangesetCompletedEvent published (factSheetId, nodesCreated=N, edgesCreated=M)
5. GroundingCascadeHook.onChangeset() → schedules IncrementalReasoningOrchestrator.runFull(factSheetId)
6. Orchestrator: KnowledgeGraphReasoningAdapter.subgraph(newNodeIds) → subgraph
7. IncrementalGrounder: adds new atoms (Person→receivesEmail, Person→mentions→X)
8. ScalarHlMrfInference: solves MAP on updated program
9. EntailmentEngine: emits InferredFacts (e.g., "Person is connected to entity X with confidence 0.82")
10. InferredFactStore: stores new facts (version bump)
11. GraphRuleHook: fires any CHANGESET rules (e.g., WEBHOOK if nodesCreated > threshold)
12. Next ask_graph_verify("knows(Person, X)") hits InferredFactStore.latest() → SUPPORTED, 0.82
```

The total latency from email receipt to grounded fact available for agent verification is bounded by steps 3–10. Step 3 (extraction) is the bottleneck; steps 7–10 are typically <500ms for subgraph sizes reachable from one email (depth-2 BFS).

---

## L4 — Domain-Object Generation

### Purpose
Derive **first-class domain objects** (business process models, rules, ontological constraints) from the grounded graph, with confidence scores propagated from the underlying PSL/MEBN values. The key invariant: every derived object carries a `double confidence` computed from the inference chain that produced it, not from an LLM judgment.

### Existing components reused

| Component | Path | Role |
|---|---|---|
| `MiningProcessDiscoveryService` | `kompile-process-discovery/.../mining/MiningProcessDiscoveryService.java:65` | LLM-free process derivation: KG → EventLog → InductiveMiner → ProcessSuggestion |
| `ProcessSuggestion` | `kompile-process-discovery/...ProcessSuggestion.java:39` | Already has `double confidence` field |
| `MiningDiscoveryController` | at `/api/process/mining` | Exposes `/discover`, `/causal`, `/psl`, `/bayesian`, `/declare`, `/mermaid`, `/heuristics`, `/performance` |
| `EventLogExtractor` | `mining/extract/EventLogExtractor.java:46` | KG → EventLog; `anchorEntityType` configurable |
| `ProcessCausalAnalyzer` | `mining/causal/ProcessCausalAnalyzer.java` | Causal analysis using PSL + Bayesian VE |
| `DeclareMiner` | `mining/declare/DeclareMiner.java` | Declare constraint templates with support/confidence |
| `PerformanceMiner` | `mining/perf/PerformanceMiner.java` | Bottleneck and duration analysis |
| `OntologicalConstraintBuilder` | `psl/OntologicalConstraintBuilder.java` | Inverse/functional/subsumption constraints from type system |
| `TypeHierarchy` | `mebn/type/TypeHierarchy.java:68` | isA graph from which ontological rules derive |
| `GraphHealthSnapshot` | graph-health module | density/degree/orphans/conformanceScore |

### New components

#### Confidence propagation into `ProcessSuggestion`

**Current gap**: `ProcessSuggestion.confidence` is set by `ProcessTreeToSuggestion.java` from process-tree structural metrics only (frequency, depth). It does not reflect the PSL/MEBN soft-truth values of the underlying edges.

**Fix**: `MiningProcessDiscoveryService.discoverForFactSheet` (already runs `ProcessPslInference` and `ProcessBayesianInference`) should compute a **grounding-weighted confidence**:

```
processConfidence = α * structuralConfidence + (1-α) * pslMapScore
```

where `pslMapScore` is the mean MAP value of the PSL atoms corresponding to the directly-follows edges in the discovered model. α = 0.4 (structural), 1-α = 0.6 (PSL-grounded) is a reasonable starting default. This is a **new computation in `ProcessTreeToSuggestion`**, not a new service.

#### `GroundedRuleDerivationService` (NEW `@Service` in `kompile-process-discovery` or `kompile-knowledge-graph`)

Derives symbolic rules (Datalog-style) from the grounded `InferredFactStore`. Each derived rule carries a confidence = mean MAP value of the PSL atoms that consistently co-occur to activate the rule:

```java
@Service
public class GroundedRuleDerivationService {
    public List<GroundedRule> deriveRules(Long factSheetId, double minConfidence) { ... }
    
    public record GroundedRule(
        String head,              // "isEmployedBy(?X, ?Y)"
        List<String> body,        // ["worksAt(?X, ?Z)", "subsidiary(?Z, ?Y)"]
        double confidence,        // mean MAP value of activating atoms
        List<String> supportingAtoms,
        int observedActivations
    ) {}
}
```

Implementation: scan `InferredFactStore.allLatest()` for facts with `confidence >= minConfidence`; group by `supportingRuleIds`; the most-activated rules with consistent `ruleWeights()` above threshold become `GroundedRule` candidates. REST endpoint: `GET /api/kb-grounding/derived-rules?factSheetId=&minConfidence=`.

#### `DomainObjectRegistry` (NEW in `kompile-knowledge-graph`)

A lightweight in-memory registry (persisted as JSON in the fact-sheet's `data/graph/` directory, following the Phase-1 graph portability pattern) that stores all derived domain objects:

```java
public class DomainObjectRegistry {
    enum ObjectType { PROCESS, RULE, CONSTRAINT, ONTOLOGY_CLASS }
    
    public record DomainObject(
        String id,
        ObjectType type,
        String name,
        String definition,      // JSON or Mermaid or Datalog string
        double confidence,
        Long factSheetId,
        Instant derivedAt,
        String derivedBy        // service class that produced it
    ) {}
    
    void register(DomainObject obj);
    List<DomainObject> byType(ObjectType type, Long factSheetId);
    List<DomainObject> aboveConfidence(double threshold, Long factSheetId);
}
```

This registry is the **output** of L4; it is the **input** to L1 — an agent can call `ask_graph_query` and get domain objects from it, or `ask_graph_verify` can check claims against derived rules.

#### Lifecycle of a derived domain object

```
L3 cascade completes → InferredFactStore updated (new PSL MAP values)
  → GroundingCascadeHook publishes GroundingMaterializationCompleteEvent (NEW)
  → GroundedRuleDerivationService.deriveRules(factSheetId)
  → MiningProcessDiscoveryService.discoverForFactSheet(factSheetId)  [if enough new edges]
  → DomainObjectRegistry.register(...)
  → DomainObject has confidence derived from PSL MAP scores
  → Next ask_graph_query([("type","PROCESS")]) returns domain objects with confidence
```

Domain objects are **re-derived on each full cascade** (not incrementally — derivation is fast enough relative to MAP solving). They are stored durably as JSON alongside the graph portability data (Phase-1 exporter format), so they survive restarts and are included in git-tracked graph exports.

---

## L5 — Integration + Deployment Mapping

### Module hosting

| Layer | Module | Package |
|---|---|---|
| L1 (MCP tools) | `kompile-cli/kompile-cli-main` | `ai.kompile.cli.main.chat.tools` |
| L1 (REST controller) | `kompile-app/kompile-app-parent/kompile-app-main` | `ai.kompile.app.web.controllers.grounding` |
| L2 (lib additions: KbVerifier, ConjunctiveQueryEngine, ConcurrentFactStore, DerivationTree) | `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning` | `ai.kompile.graph.reasoning.fol`, `.tms` |
| L2 (Spring service: KbGroundingService) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph` | `ai.kompile.knowledgegraph.grounding` |
| L3 (GroundingCascadeHook) | `kompile-app/kompile-data/kompile-graphs/kompile-graph-change-tracking` | `ai.kompile.graphchangetracking.hook` |
| L3 (IncrementalReasoningOrchestrator) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph` | `ai.kompile.knowledgegraph.grounding` |
| L4 (GroundedRuleDerivationService, DomainObjectRegistry) | `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph` | `ai.kompile.knowledgegraph.domain` |
| L4 (confidence integration) | `kompile-app/kompile-data/kompile-process/kompile-process-discovery` | `ai.kompile.process.discovery.mining.convert` |
| L5 (AgentFactAssertedEvent) | `kompile-app/kompile-data/kompile-graphs/kompile-graph-change-tracking` | `ai.kompile.graphchangetracking.event` |

### Fact-sheet scoping

The entire grounding system is **scoped by `factSheetId`** — the existing per-fact-sheet model used throughout crawl, channel, provenance, and graph portability. Each fact sheet has its own:
- `InferredFactStore` instance (keyed in `FactSheetKbState`)
- `PslProgram` instance
- `IncrementalGrounder` instance
- `DomainObjectRegistry` entries

The `KnowledgeGraphReasoningAdapter.subgraph()` already accepts seed node IDs from the `@Primary` matrix/vector `KnowledgeGraphService`, which is already fact-sheet-aware (the 6-arg `createNode` added in Phase 2 channel wiring). No new fact-sheet scoping infrastructure is needed.

### Persistence

| Data | Persistence | Format |
|---|---|---|
| `InferredFact` (materialized facts) | `InferredFactStore` SPI; default = in-memory; Phase 2 = file-backed | Per-atom JSON via `InferredFact.toJson()` (`fol/InferredFact.java:159`) |
| `PslProgram` weights | `FileWeightStore` | Already exists in lib |
| `DomainObject` registry | File JSON in `data/graph/domain-objects.json` (follows Phase-1 portability path) | Git-tracked, travels on clone |
| `IncrementalGrounder` state | In-memory; rebuilt from `InferredFactStore` + `PslProgram` on restart | No separate persistence needed |
| `ConcurrentFactStore` | In-memory; seeded from `KnowledgeGraphService` on startup | Facts are sourced from the live graph store |

**Restart recovery**: On `KbGroundingService` initialization, for each known fact sheet, the system loads the persisted `InferredFact` JSON files (if the file-backed store impl is active) and the `FileWeightStore` weights, and rebuilds the `IncrementalGrounder` from the current `PslProgram`. This takes O(N·P) where N = materialized facts and P = program rules — expected <5s for typical fact sheets.

### New vs reused — summary

| Component | Status | Effort estimate |
|---|---|---|
| `KbVerifier` (lib interface + `DefaultKbVerifier`) | **NEW lib** | ~100 lines |
| `ConjunctiveQueryEngine` (lib) | **NEW lib** (extracts from `PslProgram.groundInto`) | ~200 lines |
| `ConcurrentFactStore` (lib) | **NEW lib** | ~80 lines |
| `DerivationTree` (lib) | **NEW lib** (uses existing `JustificationIndex`) | ~150 lines |
| `KbGroundingService` (Spring) | **NEW** | ~300 lines |
| `KbGroundingController` (Spring) | **NEW** | ~150 lines |
| `AskGraphToolFamily` (5 MCP tools) | **NEW** | ~400 lines total |
| `AgentFactAssertedEvent` | **NEW** | ~30 lines |
| `GroundingCascadeHook` | **NEW** (extends existing hook pattern) | ~100 lines |
| `IncrementalReasoningOrchestrator` | **NEW** | ~250 lines |
| `GroundedRuleDerivationService` | **NEW** | ~150 lines |
| `DomainObjectRegistry` | **NEW** | ~120 lines |
| `ProcessTreeToSuggestion` confidence | **MODIFY existing** | ~30 lines |
| `FactSheetKbState` (inner record) | **NEW** (part of `KbGroundingService`) | ~60 lines |

---

## Dependency-Ordered Build Plan

The following order is mandated by: (a) the lib staying infra-free (lib components come before their Spring wrappers), (b) L1 tools requiring L2 REST endpoints, (c) L3 cascade requiring L2 `IncrementalReasoningOrchestrator`.

### Phase 1 — The verify + query path (L2 lib primitives → L2 Spring → L1 tools)

This unblocks the primary agent-grounding loop with no cascade and no domain objects.

1. **`KbVerifier` interface + `DefaultKbVerifier`** — `kompile-graph-reasoning/fol/` (lib-only, no Spring, no new deps). Tests: unit tests with `InMemoryInferredFactStore`. Validates the fast-path lookup without inference.

2. **`ConjunctiveQueryEngine`** — `kompile-graph-reasoning/fol/` (lib-only). Extract join kernel from `PslProgram.groundInto`. Tests: conjunctive pattern queries on `MutableReasoningGraph` in-memory.

3. **`ConcurrentFactStore`** — `kompile-graph-reasoning/fol/` (lib-only). Thin ConcurrentHashMap wrapper; optimistic lock protocol. Tests: concurrent assert stress test.

4. **`DerivationTree`** — `kompile-graph-reasoning/tms/` (lib-only). Recursive build from `JustificationIndex` + `InferredFactStore`. Tests: multi-hop derivation on known PSL result.

5. **`KbGroundingService` + `FactSheetKbState`** — `kompile-knowledge-graph` (Spring). Wires the 4 lib primitives above to the live `KnowledgeGraphService` + `@Primary` store. Tests: `@SpringBootTest` scoped to `kompile-knowledge-graph` with a real `InMemoryInferredFactStore`.

6. **`KbGroundingController`** — `kompile-app-main` (Spring MVC). Thin delegate to `KbGroundingService`. Tests: MockMvc for all 4 POST endpoints.

7. **`AskGraphToolFamily`** (5 MCP tools: verify, query, explain, assert, subscribe-stub) — `kompile-cli-main`. HTTP dispatch to `/api/kb-grounding/*`. Tests: integration test against a live `kompile-app-main` instance.

**Milestone**: An LLM agent can call `ask_graph_verify("isEmployedBy(Alice,Acme)")` and get a grounded verdict with confidence and evidence. The graph is static (no cascade yet).

### Phase 2 — The live cascade (L3)

8. **`AgentFactAssertedEvent`** — `kompile-graph-change-tracking`. Adds the third event source.

9. **`GroundingCascadeHook`** — `kompile-graph-change-tracking`. Listens to `GraphChangesetCompletedEvent` + `AgentFactAssertedEvent`; delegates to `IncrementalReasoningOrchestrator` via async executor.

10. **`IncrementalReasoningOrchestrator`** — `kompile-knowledge-graph`. Full delta-reasoning pipeline; uses `IncrementalGrounder`, `KnowledgeGraphReasoningAdapter`, `EntailmentEngine`. Tests: integration test: crawl a small graph → verify a derived fact → assert a new fact → verify the cascaded derivative.

**Milestone**: The KB is live. A new email or crawl update cascades to updated grounded facts within seconds. `ask_graph_verify` returns post-cascade values.

### Phase 3 — Domain objects (L4)

11. **`ProcessTreeToSuggestion` confidence fix** — modify `kompile-process-discovery`. 2-line change: multiply structural confidence by PSL MAP score.

12. **`GroundedRuleDerivationService`** — `kompile-knowledge-graph`. Scans `InferredFactStore`; derives `GroundedRule` objects. Tests: known PSL program → verify derived rules match expected.

13. **`DomainObjectRegistry`** — `kompile-knowledge-graph`. In-memory + JSON persistence. Tests: register → reload → query by type + confidence.

14. **Wire L4 into L3 cascade** — `GroundingCascadeHook` publishes `GroundingMaterializationCompleteEvent`; `GroundedRuleDerivationService` + `MiningProcessDiscoveryService` listen and re-derive domain objects.

**Milestone**: After each cascade, derived domain objects (processes, rules) are available from `ask_graph_query([("type","PROCESS")])` with PSL-grounded confidence scores.

---

## Open Questions for the User

**Q1 — InferredFactStore durability tier**: The fast-path lookup (Phase 1) works with the existing `InMemoryInferredFactStore`. For production restarts without full re-inference (~30–300s per fact sheet depending on graph size and PSL program complexity), a durable store is needed. Two options: (a) **file-backed** `InferredFact.toJson()` per atom in `data/graph/inferred/` (follows Phase-1 portability, git-tracked, travels on clone, but not fast for >100k facts); (b) **SQLite-backed** impl of `InferredFactStore` SPI in `kompile-knowledge-graph` (fast random-access, no git-tracking, separate file). Which durability model is preferred, and what is the expected fact count per fact sheet at production scale?

**Q2 — Cascade concurrency and staleness tolerance**: The current design serializes writes per factSheet and propagates updates asynchronously. This means the `InferredFactStore` can be **stale by up to one cascade cycle** (typically seconds to tens of seconds) after a crawl or channel event. For the agent's `verify` loop during active reasoning, this is usually acceptable. However, for the `assert` → immediate-verify pattern (agent asserts a fact and immediately verifies a derivative), the current design returns `UNKNOWN` until the cascade completes. Two options: (a) **synchronous cascade** on `assert` for a configurable `syncDepth` (e.g., re-reason only atoms at depth ≤ 1 from the asserted atom before returning); (b) **staleness flag** in `VerifyResult` (`"stale": true, "stalenessBudgetMs": 5000`) so the agent knows to retry. Which consistency model is acceptable for the target use cases?
