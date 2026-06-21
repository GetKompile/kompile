# Grounding API Contract Design
## L1/L2 Detailed API Contracts for Agent KB Grounding

**Date**: 2026-06-21
**Status**: DESIGN — no code written yet
**Prerequisite reading**: [`agent-grounding-infrastructure-design.md`](agent-grounding-infrastructure-design.md) — architecture overview, layering, and build plan
**Scope of this doc**: Exact request/response JSON schemas for the 5 MCP tools; the `KbGroundingController` REST envelope; the `KbGroundingService` contract; confidence/evidence/provenance propagation; UNKNOWN/error handling; assert→version flow; optimistic-concurrency surface; multi-turn grounding-session pattern.

---

## 0. Layering Recap

```
LLM agent
  └─ ask_graph_* MCP tool (kompile-cli-main, CliTool impl)
       └─ POST /api/kb-grounding/* (kompile-app-main, KbGroundingController)
            └─ KbGroundingService (kompile-knowledge-graph, @Service)
                 ├─ DefaultKbVerifier (kompile-graph-reasoning, infra-free)
                 ├─ ConjunctiveQueryEngine (kompile-graph-reasoning, infra-free)
                 ├─ DerivationTree.build() (kompile-graph-reasoning, infra-free)
                 └─ ConcurrentFactStore (kompile-graph-reasoning, infra-free)
```

**Invariant**: everything in `kompile-graph-reasoning` (`ai.kompile.graph.reasoning.fol.grounding.*`) is infra-free — no Spring, no HTTP.
The lib contracts are ALREADY BUILT. `KbGroundingService`, `KbGroundingController`, and the 5 MCP tools are the wiring work.

---

## 1. Common Envelope Fields

Every response from every tool and every REST endpoint carries a **common meta-block**. This section defines those fields once; individual schemas below reference it as `<CommonMeta>`.

### 1.1 Response meta-block (all endpoints)

```json
"meta": {
  "factSheetId":   <integer | null>,   // null = cross-factSheet query
  "asOf":          <ISO-8601 string>,  // the effective KB snapshot time used
  "stale":         <boolean>,          // true if InferredFactStore is pending a cascade cycle
  "stalenessBudgetMs": <integer>,      // ms until the next cascade is expected to complete (0 if not stale)
  "kbVersion":     <long>,             // ConcurrentFactStore.version() at read time
  "sessionId":     <string | null>     // echoed back from request if supplied
}
```

**Staleness semantics**: `stale=true` signals that a cascade (from a crawl, channel event, or prior assert) is in progress or recently queued. The `stalenessBudgetMs` field tells the agent how long to wait before re-querying. An agent that calls `ask_graph_assert` and immediately calls `ask_graph_verify` on a derivative fact should check `stale` and retry after `stalenessBudgetMs` if it receives `UNKNOWN` — this is the chosen answer to the Q2 open question (staleness flag, option (b); synchronous cascade on assert is deferred because it creates unbounded latency under large programs).

### 1.2 Error envelope (all endpoints, HTTP 4xx/5xx)

```json
{
  "error": "INVALID_ATOM | NOT_FOUND | CONFLICT | TIMEOUT | INTERNAL",
  "message": "<human-readable string>",
  "code": <integer>,     // HTTP status code repeated for client convenience
  "atomKey": "<string>", // the atom that caused the error, if applicable
  "meta": { ... }        // same meta-block as success, partial if error is early
}
```

`GlobalExceptionHandler` (`kompile-app-main/src/main/java/ai/kompile/app/web/GlobalExceptionHandler.java:42`) already produces `{error, message, type, ...}` for controllers in `ai.kompile.app.web.controllers.*`. The new package must be `ai.kompile.app.web.controllers.grounding` and added to `GlobalExceptionHandler.basePackages`.

---

## 2. Tool: `ask_graph_verify`

### 2.1 MCP tool schema

```
id:            "ask_graph_verify"
permissionKey: "ask_graph_verify"
annotations:   McpToolAnnotations.READ_ONLY   // readOnlyHint=true, idempotentHint=true
description:   "Verify a factual claim against the production knowledge base.
               Returns SUPPORTED, REFUTED, or UNKNOWN with a calibrated confidence
               score [0,1] and the evidence atom keys + activated rules that justify
               the verdict. Use this before accepting any LLM-generated claim as fact.
               Specify asOf for temporal point-in-time verification."
```

**parameterSchema** (built via `ObjectMapper.createObjectNode()` in `parameterSchema()`):

```json
{
  "type": "object",
  "required": ["atom"],
  "properties": {
    "atom": {
      "type": "string",
      "description": "Canonical atom key, e.g. 'isEmployedBy(Alice, Acme)'. Predicate name is case-sensitive. Arguments separated by ', ' (comma-space)."
    },
    "factSheetId": {
      "type": "integer",
      "description": "Scope verification to a specific fact sheet. Null or absent = search all active fact sheets."
    },
    "asOf": {
      "type": "string",
      "description": "ISO-8601 instant for temporal point-in-time verification, e.g. '2026-06-01T00:00:00Z'. Absent = current truth."
    },
    "minConfidence": {
      "type": "number",
      "description": "Override the default confidence threshold [0,1] below which UNKNOWN is returned. Default: 0.5 (KbVerifier.DEFAULT_THRESHOLD)."
    },
    "sessionId": {
      "type": "string",
      "description": "Optional agent session ID for grounding-session tracking. Echoed in meta."
    }
  }
}
```

### 2.2 REST endpoint

```
POST /api/kb-grounding/verify
Content-Type: application/json
```

**Request DTO** (matches parameterSchema 1:1):

```json
{
  "atom":          "isEmployedBy(Alice, Acme)",
  "factSheetId":   42,
  "asOf":          "2026-06-01T00:00:00Z",
  "minConfidence": 0.6,
  "sessionId":     "agent-session-abc123"
}
```

**Response DTO** (HTTP 200):

```json
{
  "verdict":          "SUPPORTED",
  "confidence":       0.87,
  "evidenceAtoms":    ["worksAt(Alice, Acme_NYC)", "subsidiary(Acme_NYC, Acme)"],
  "activatedRules":   ["0.9: worksAt(?X,?Z) :- worksAt(?X,?Y) & subsidiary(?Y,?Z)"],
  "derivationDepth":  2,
  "sourceProvenance": ["crawl:run-7712:doc-4417", "crawl:run-7712:doc-8801"],
  "meta": {
    "factSheetId":       42,
    "asOf":              "2026-06-01T00:00:00Z",
    "stale":             false,
    "stalenessBudgetMs": 0,
    "kbVersion":         1041,
    "sessionId":         "agent-session-abc123"
  }
}
```

**Field semantics**:

| Field | Type | Notes |
|---|---|---|
| `verdict` | `"SUPPORTED" \| "REFUTED" \| "UNKNOWN"` | Maps to `VerifyResult.Status` (`VerifyResult.java:50`) |
| `confidence` | `double [0,1]` | From `InferredFact.confidence()` for SUPPORTED/REFUTED; `0.0` for UNKNOWN |
| `evidenceAtoms` | `List<String>` | `InferredFact.supportingFactKeys()` — observed fact keys that underlie the conclusion |
| `activatedRules` | `List<String>` | `InferredFact.supportingRuleIds()` — PSL rule display strings |
| `derivationDepth` | `int` | 0 = directly observed; N = N hops of rule application |
| `sourceProvenance` | `List<String>` | Crawl-run IDs and document IDs extracted from `evidenceAtoms` by the controller |
| `meta.asOf` | `string` | Echoes `asOf` from request; set to actual KB snapshot time if request omitted it |
| `meta.stale` | `boolean` | `true` if the cascade executor queue for this factSheet is non-empty |

**UNKNOWN / error cases**:

| Situation | verdict | confidence | HTTP status |
|---|---|---|---|
| Atom not in KB, no inference | `"UNKNOWN"` | `0.0` | 200 |
| Atom below minConfidence threshold | `"UNKNOWN"` | 0.0 | 200 |
| factSheetId not found | — | — | 404, error envelope |
| Malformed atom key (no predicate) | — | — | 400, error envelope |
| KB store temporarily unavailable | — | — | 503, error envelope |

### 2.3 Service contract

```java
// KbGroundingService.java
public VerifyResponse verify(VerifyRequest req) {
    Long fsId = req.factSheetId();
    Instant asOf = req.asOf() != null ? req.asOf() : Instant.now();
    double threshold = req.minConfidence() > 0 ? req.minConfidence() : KbVerifier.DEFAULT_THRESHOLD;

    FactSheetKbState state = getState(fsId);               // ConcurrentHashMap lookup / lazy init
    DefaultKbVerifier verifier = new DefaultKbVerifier(
        asOf == null ? state.inferredFactStore()
                     : state.temporalView().at(asOf),      // TemporalView.at(Instant) for asOf queries
        state.concurrentFactStore(),
        threshold
    );
    VerifyResult result = verifier.verify(req.atom());

    // Cache-miss escalation: UNKNOWN → targeted inference
    if (result.status() == VerifyResult.Status.UNKNOWN && req.allowInference()) {
        result = verifyWithInference(req.atom(), fsId, extractSeedNodes(req.atom()));
    }

    return toVerifyResponse(result, state, asOf);
}
```

**`asOf` / current-truth semantics**: When `asOf` is absent, `state.inferredFactStore()` (the live materialized store) is used directly — this is "current truth as of the last cascade". When `asOf` is present, `TemporalView.at(asOf)` (`model/TemporalView.java:57`) creates a point-in-time slice. The `meta.asOf` field in the response always echoes the actual timestamp used.

**`allowInference` (implicit default: `false`)**: By default, the fast-path O(1) lookup is used. If the caller adds `"allowInference": true` to the request (optional, not exposed in the MCP tool description to avoid agent over-triggering), targeted inference runs on cache miss. This is a power-user escape hatch for the service-layer; the MCP tool does not expose it.

### 2.4 MCP tool execute() sketch

```java
// AskGraphVerifyTool.java (implements CliTool)
@Override
public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
    context.checkPermission(permissionKey(), "Verify KB claim");
    String atom = params.path("atom").asText("");
    if (atom.isEmpty()) return ToolResult.error("atom is required");

    ObjectNode body = objectMapper.createObjectNode();
    body.put("atom", atom);
    if (!params.path("factSheetId").isMissingNode()) body.set("factSheetId", params.get("factSheetId"));
    if (!params.path("asOf").isMissingNode())        body.set("asOf", params.get("asOf"));
    if (!params.path("minConfidence").isMissingNode()) body.set("minConfidence", params.get("minConfidence"));
    if (!params.path("sessionId").isMissingNode())   body.set("sessionId", params.get("sessionId"));

    HttpResponse<String> resp = post("/api/kb-grounding/verify", body);
    if (resp.statusCode() != 200) return ToolResult.error(extractError(resp.body()));

    JsonNode result = objectMapper.readTree(resp.body());
    String verdict    = result.path("verdict").asText();
    double conf       = result.path("confidence").asDouble();
    JsonNode evidence = result.path("evidenceAtoms");
    boolean stale     = result.path("meta").path("stale").asBoolean(false);

    String output = formatVerifyResult(atom, verdict, conf, evidence, stale);
    return ToolResult.success("ask_graph_verify: " + atom, output,
        Map.of("verdict", verdict, "confidence", conf, "stale", stale));
}

private String formatVerifyResult(String atom, String verdict, double conf,
                                   JsonNode evidence, boolean stale) {
    StringBuilder sb = new StringBuilder();
    sb.append("**").append(verdict).append("** — ").append(atom);
    sb.append("\nConfidence: ").append(String.format("%.3f", conf));
    if (evidence.isArray() && evidence.size() > 0) {
        sb.append("\nEvidence:");
        evidence.forEach(e -> sb.append("\n  - ").append(e.asText()));
    }
    if (stale) sb.append("\n⚠ KB is pending a cascade update — consider retrying.");
    return sb.toString();
}
```

---

## 3. Tool: `ask_graph_query`

### 3.1 MCP tool schema

```
id:            "ask_graph_query"
permissionKey: "ask_graph_query"
annotations:   McpToolAnnotations.READ_ONLY
description:   "Conjunctive pattern query against the knowledge base. Each conjunct
               is a predicate pattern with '?'-prefixed variables and ground constants.
               Returns all variable binding rows (up to maxResults) with per-row
               minimum confidence (Łukasiewicz T-norm). Variables MUST use '?' prefix
               to distinguish them from entity names which may start with uppercase."
```

**parameterSchema**:

```json
{
  "type": "object",
  "required": ["conjuncts"],
  "properties": {
    "conjuncts": {
      "type": "array",
      "minItems": 1,
      "description": "Ordered list of atom patterns forming the conjunctive query.",
      "items": {
        "type": "object",
        "required": ["predicate", "args"],
        "properties": {
          "predicate": {
            "type": "string",
            "description": "Predicate name, e.g. 'worksFor', 'hasSkill'. Case-sensitive."
          },
          "args": {
            "type": "array",
            "items": { "type": "string" },
            "description": "Arguments: use '?Name' for variables, bare string for constants. Example: ['?Person', 'Acme']."
          }
        }
      }
    },
    "factSheetId":   { "type": "integer", "description": "Scope to a fact sheet. Null = all." },
    "asOf":          { "type": "string",  "description": "ISO-8601 snapshot time. Absent = current truth." },
    "maxResults":    { "type": "integer", "description": "Maximum binding rows returned. Default 50 (ConjunctiveQueryEngine.DEFAULT_MAX_RESULTS)." },
    "minConfidence": { "type": "number",  "description": "Filter: only rows with confidence >= this value. Default 0.3." },
    "sessionId":     { "type": "string",  "description": "Agent session ID for tracking." }
  }
}
```

### 3.2 REST endpoint

```
POST /api/kb-grounding/query
Content-Type: application/json
```

**Request**:

```json
{
  "conjuncts": [
    { "predicate": "worksFor",  "args": ["?Person", "Acme"] },
    { "predicate": "hasSkill",  "args": ["?Person", "AI"] }
  ],
  "factSheetId":   42,
  "maxResults":    10,
  "minConfidence": 0.5
}
```

**Response** (HTTP 200):

```json
{
  "bindings": [
    {
      "variables": { "?Person": "Alice" },
      "confidence": 0.91,
      "matchedAtoms": ["worksFor(Alice, Acme)", "hasSkill(Alice, AI)"]
    },
    {
      "variables": { "?Person": "Bob" },
      "confidence": 0.74,
      "matchedAtoms": ["worksFor(Bob, Acme)", "hasSkill(Bob, AI)"]
    }
  ],
  "total":     2,
  "truncated": false,
  "meta": {
    "factSheetId":       42,
    "asOf":              "2026-06-21T14:23:01Z",
    "stale":             false,
    "stalenessBudgetMs": 0,
    "kbVersion":         1041,
    "sessionId":         null
  }
}
```

**Field semantics**:

| Field | Notes |
|---|---|
| `bindings[].variables` | Map of `?VarName → groundConstant`; uses `QueryBinding.bindings` from `QueryBinding.java:28` |
| `bindings[].confidence` | `QueryBinding.confidence()` — min over matched atoms (Łukasiewicz T-norm per `ConjunctiveQueryEngine.java:186`) |
| `bindings[].matchedAtoms` | Ground atom keys that satisfied the conjuncts for this row (from predicate index lookup) |
| `total` | Count of returned rows (≤ maxResults) |
| `truncated` | `true` if the result was cut at `maxResults` — the agent should add more conjuncts to narrow |

**Variable convention (critical)**: `?` prefix only. Entity names like `Alice`, `Acme`, `AI` are constants even though uppercase. This matches `ConjunctiveQueryEngine.isVariable(arg)` (`ConjunctiveQueryEngine.java:292`) which checks `arg.startsWith("?")` exclusively.

### 3.3 Service contract

```java
// KbGroundingService.java
public QueryResponse query(QueryRequest req) {
    FactSheetKbState state = getState(req.factSheetId());
    InferredFactStore store = req.asOf() != null
        ? state.temporalView().at(req.asOf())
        : state.inferredFactStore();

    List<ConjunctiveQueryEngine.AtomPattern> conjuncts = req.conjuncts().stream()
        .map(c -> new ConjunctiveQueryEngine.AtomPattern(c.predicate(), c.args()))
        .collect(toList());

    List<QueryBinding> raw = ConjunctiveQueryEngine.query(conjuncts, store, req.maxResults());
    double minConf = req.minConfidence() > 0 ? req.minConfidence() : 0.3;
    List<QueryBinding> filtered = raw.stream()
        .filter(b -> b.confidence() >= minConf)
        .collect(toList());

    boolean truncated = raw.size() == req.maxResults();
    return toQueryResponse(filtered, truncated, state);
}
```

**UNKNOWN / error cases**:

| Situation | Result |
|---|---|
| No predicate in InferredFactStore matches any conjunct | `bindings: [], total: 0, truncated: false` (not an error) |
| Empty conjuncts array | HTTP 400 |
| `maxResults` > 1000 | HTTP 400 (guard against scan abuse) |
| factSheetId not found | HTTP 404 |

---

## 4. Tool: `ask_graph_explain`

### 4.1 MCP tool schema

```
id:            "ask_graph_explain"
permissionKey: "ask_graph_explain"
annotations:   McpToolAnnotations.READ_ONLY
description:   "Produce a derivation trace explaining why the KB believes (or disbelieves)
               a specific fact. Returns a derivation tree with rule applications and
               supporting atoms at each hop, plus an NL summary. Use this to audit
               an LLM's reasoning or to present grounded explanations to end users."
```

**parameterSchema**:

```json
{
  "type": "object",
  "required": ["atom"],
  "properties": {
    "atom":        { "type": "string",  "description": "The atom key to explain, e.g. 'isEmployedBy(Alice, Acme)'." },
    "factSheetId": { "type": "integer", "description": "Fact sheet scope. Null = all." },
    "depth": {
      "type": "integer",
      "description": "Maximum derivation hops. Default: 3. Maximum: 5 (DerivationTree.DEFAULT_MAX_DEPTH). Deeper queries are slower.",
      "default": 3
    },
    "sessionId":   { "type": "string" }
  }
}
```

### 4.2 REST endpoint

```
POST /api/kb-grounding/explain
Content-Type: application/json
```

**Request**:

```json
{
  "atom":        "isEmployedBy(Alice, Acme)",
  "factSheetId": 42,
  "depth":       3
}
```

**Response** (HTTP 200):

```json
{
  "atom":       "isEmployedBy(Alice, Acme)",
  "verdict":    "SUPPORTED",
  "confidence": 0.87,
  "summary":    "Alice is employed by Acme because she works at Acme NYC (confidence 0.95), which is a subsidiary of Acme Corp (confidence 0.92), via rule: 0.9: worksAt(?X,?Z) :- worksAt(?X,?Y) & subsidiary(?Y,?Z).",
  "derivation": {
    "atom":       "isEmployedBy(Alice, Acme)",
    "confidence": 0.87,
    "rule":       "0.9: worksAt(?X,?Z) :- worksAt(?X,?Y) & subsidiary(?Y,?Z)",
    "source":     "crawl-run-7712",
    "children": [
      {
        "atom":       "worksAt(Alice, Acme_NYC)",
        "confidence": 0.95,
        "rule":       null,
        "source":     "crawl:run-7712:doc-4417",
        "children":   []
      },
      {
        "atom":       "subsidiary(Acme_NYC, Acme)",
        "confidence": 0.92,
        "rule":       null,
        "source":     "crawl:run-7712:doc-8801",
        "children":   []
      }
    ]
  },
  "meta": { ... }
}
```

**`derivation` is `DerivationTree.toJson()` output** (`DerivationTree.java:228`), wrapped in the response envelope. The `summary` field is produced by `ExplanationService` if wired, or by a deterministic template renderer in `KbGroundingService` if no `ExplanationService` bean is present (graceful degradation). The template renderer walks the top-level children and produces "X because Y (confidence C) and Z (confidence C'), via rule R."

**Provenance in explain**: Each node's `source` field carries `InferredFact.runId()` (`DerivationTree.java:136` uses `fact.runId()`). Leaf nodes (observed facts) carry the crawl document provenance string. This is the definitive provenance chain — the agent can trace any derived belief back to the crawl-run and document that asserted the base fact.

**UNKNOWN case**: If the atom is not in `InferredFactStore`, the response is:

```json
{
  "atom":     "unknownPred(X, Y)",
  "verdict":  "UNKNOWN",
  "confidence": 0.0,
  "summary":  "The atom 'unknownPred(X, Y)' is not derivable from the current KB.",
  "derivation": { "atom": "unknownPred(X, Y)", "confidence": 0.0, "rule": null, "source": null, "children": [] },
  "meta": { ... }
}
```

HTTP status is still 200; `UNKNOWN` is a valid verdict, not a server error.

### 4.3 Service contract

```java
// KbGroundingService.java
public ExplainResponse explain(ExplainRequest req) {
    FactSheetKbState state = getState(req.factSheetId());
    int depth = req.depth() > 0 ? Math.min(req.depth(), DerivationTree.DEFAULT_MAX_DEPTH) : 3;

    DerivationTree tree = DerivationTree.build(
        req.atom(),
        state.inferredFactStore(),
        state.justificationIndex(),
        depth
    );

    VerifyResult verdict = new DefaultKbVerifier(
        state.inferredFactStore(), state.concurrentFactStore()
    ).verify(req.atom());

    String summary = explanationService != null
        ? explanationService.summarize(tree)       // LLM-backed or rule-based impl
        : deterministicSummary(tree);              // no-LLM fallback

    return new ExplainResponse(req.atom(), verdict, tree, summary, buildMeta(state));
}
```

---

## 5. Tool: `ask_graph_assert`

### 5.1 MCP tool schema

```
id:            "ask_graph_assert"
permissionKey: "ask_graph_assert"
annotations:   McpToolAnnotations.WRITE   // readOnlyHint=false, idempotentHint=false
description:   "Assert a new fact into the knowledge base from agent output.
               The fact is attributed to the calling agent session (provenance).
               Contradiction-checking (TMS) runs synchronously before returning.
               Background re-reasoning cascades asynchronously — the 'stale' meta
               flag in subsequent verify/query calls will be true until the cascade
               completes. For optimistic-concurrency: supply expectedVersion from
               a prior verify or query response's meta.kbVersion."
```

**parameterSchema**:

```json
{
  "type": "object",
  "required": ["atom", "value"],
  "properties": {
    "atom": {
      "type": "string",
      "description": "Atom key to assert, e.g. 'isEmployedBy(Alice, Acme)'."
    },
    "value": {
      "type": "number",
      "description": "Soft-truth value [0,1]. Use 1.0 for hard facts, 0.0 to explicitly retract/refute. Values in (0,1) are probabilistic observations."
    },
    "factSheetId":       { "type": "integer" },
    "sessionId":         { "type": "string",  "description": "Agent session — stored as provenance." },
    "source":            { "type": "string",  "description": "Human-readable provenance label, e.g. 'agent-extraction:run-42'." },
    "expectedVersion":   {
      "type": "integer",
      "description": "Optional. If supplied, the assert is rejected with CONFLICT if the KB has been modified since this version (optimistic locking). Use meta.kbVersion from a prior query/verify response."
    }
  }
}
```

### 5.2 REST endpoint

```
POST /api/kb-grounding/assert
Content-Type: application/json
```

**Request**:

```json
{
  "atom":            "isEmployedBy(Alice, Acme)",
  "value":           1.0,
  "factSheetId":     42,
  "sessionId":       "agent-session-abc123",
  "source":          "agent-extraction:run-42",
  "expectedVersion": 1041
}
```

**Response** (HTTP 200):

```json
{
  "status":             "ASSERTED",
  "version":            1042,
  "contradictions":     [],
  "cascadeTriggered":   true,
  "meta": {
    "factSheetId":       42,
    "asOf":              "2026-06-21T14:30:00Z",
    "stale":             true,
    "stalenessBudgetMs": 2500,
    "kbVersion":         1042,
    "sessionId":         "agent-session-abc123"
  }
}
```

**Status values**:

| `status` | Meaning | HTTP |
|---|---|---|
| `"ASSERTED"` | Fact written, cascade triggered | 200 |
| `"CONTRADICTION_DETECTED"` | TMS found a contradiction; fact NOT written; `contradictions` non-empty | 200 |
| `"CONFLICT_QUEUED"` | `expectedVersion` mismatch (optimistic-concurrency conflict); fact NOT written | 200 |
| `"RETRACTED"` | `value=0.0` and fact existed — retracted, cascade triggered | 200 |

**Note**: All four are HTTP 200. The `status` field is the machine-readable outcome. Only infrastructure failures (factSheet not found, store unavailable) return 4xx/5xx.

### 5.3 Assert flow and version semantics

```
1. KbGroundingController.assertFact(req)
2.   → KbGroundingService.assertFact(req)
3.       → state.concurrentFactStore().version()          // read current version for CAS
4.       → if req.expectedVersion present:
              long result = state.concurrentFactStore().assertFact(fact, req.expectedVersion)
              if result == ConcurrentFactStore.CONFLICT → return CONFLICT_QUEUED (no cascade)
5.       → else: state.concurrentFactStore().assertFact(fact)  // unconditional
6.       → ContradictionDetector.detect(...)                   // synchronous, O(rules)
7.       → if contradiction: rollback via BeliefReviser, return CONTRADICTION_DETECTED
8.       → else: publish AgentFactAssertedEvent(atomKey, value, factSheetId, sessionId)
9.       → return ASSERTED with new version = ConcurrentFactStore.version()
10. GroundingCascadeHook.onAgentAssert() [ASYNC, @Async("groundingCascadeExecutor")]
11.   → IncrementalReasoningOrchestrator.runDelta(factSheetId, Set.of(atomKey))
12.   → subsequent verify/query calls see stale=true until step 11 completes
```

**ConcurrentFactStore.CONFLICT = -1L** (`ConcurrentFactStore.java:66`). The service checks `result == ConcurrentFactStore.CONFLICT` and returns `CONFLICT_QUEUED` without publishing the cascade event.

**Provenance persistence**: The `Fact` created in step 5 carries `sourceId = sessionId + ":" + source` (or just `source` if no sessionId). This travels into `InferredFact.runId()` via the cascade and surfaces in `DerivationTree.sourceProvenance` at explain time.

**Version number surface**: `meta.kbVersion` in the assert response is the new version after the write. The agent should save this and use it as `expectedVersion` in a follow-up assert if it wants to ensure no concurrent modification happened in between.

### 5.4 Retract semantics

`value = 0.0` + an existing fact in the store → `ConcurrentFactStore.retract(atomKey)`. The response status is `"RETRACTED"`. The cascade re-derives all atoms that depended on the retracted fact, potentially flipping downstream facts from SUPPORTED to UNKNOWN or REFUTED.

---

## 6. Tool: `ask_graph_subscribe` (Phase 2)

### 6.1 MCP tool schema

```
id:            "ask_graph_subscribe"
permissionKey: "ask_graph_subscribe"
annotations:   McpToolAnnotations.NETWORK   // openWorldHint=true (SSE is external state)
description:   "Subscribe to KB changes matching a predicate pattern. Returns a
               subscriptionId. Changes are delivered via SSE at
               GET /api/kb-grounding/subscribe/{subscriptionId}/events.
               Use this for cascade update notifications: after ask_graph_assert,
               subscribe to a dependent atom to know when re-reasoning completes.
               Phase 2 — not available in initial deployment."
```

**parameterSchema**:

```json
{
  "type": "object",
  "required": ["predicates"],
  "properties": {
    "predicates": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Predicate names to watch. Changes to any atom with these predicates trigger an event."
    },
    "factSheetId": { "type": "integer" },
    "sessionId":   { "type": "string"  }
  }
}
```

**Assert response** (POST `/api/kb-grounding/subscribe`):

```json
{
  "subscriptionId": "sub-f8e2a91c",
  "eventsUrl":      "/api/kb-grounding/subscribe/sub-f8e2a91c/events",
  "predicates":     ["isEmployedBy", "worksFor"],
  "factSheetId":    42,
  "meta": { ... }
}
```

**SSE event stream** (GET `/api/kb-grounding/subscribe/{subscriptionId}/events`):

Follows the same pattern as `CrawlProgressSseController` (`CrawlProgressSseController.java:64`): `SseEmitter` with 30-minute timeout, heartbeat every 15 seconds, `@EventListener` on a new `GroundingChangeEvent` (to be published by `IncrementalReasoningOrchestrator` when a predicate in the subscription set is updated).

```json
event: grounding_change
data: {
  "atomKey":     "isEmployedBy(Alice, Acme)",
  "predicate":   "isEmployedBy",
  "newVerdict":  "SUPPORTED",
  "confidence":  0.87,
  "factSheetId": 42,
  "kbVersion":   1043,
  "triggeredBy": "cascade:AgentFactAssertedEvent"
}
```

**Phase 2 note**: `ask_graph_subscribe` is listed in the tool family but returns HTTP 501 until Phase 2 is implemented. The MCP tool description says "not available in initial deployment" so the agent does not attempt to use it.

---

## 7. `KbGroundingController` — Full REST Envelope

**Location**: `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/web/controllers/grounding/KbGroundingController.java`

**Package placement**: `ai.kompile.app.web.controllers.grounding` — must be added to `GlobalExceptionHandler.basePackages` (`GlobalExceptionHandler.java:42`).

```java
@Slf4j
@RestController
@RequestMapping("/api/kb-grounding")
public class KbGroundingController {

    private final KbGroundingService groundingService;

    @Autowired
    public KbGroundingController(KbGroundingService groundingService) {
        this.groundingService = groundingService;
    }

    // ── Read-only endpoints ──────────────────────────────────────────────────────

    @PostMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(@RequestBody VerifyRequest req) {
        return ResponseEntity.ok(groundingService.verify(req));
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest req) {
        if (req.conjuncts() == null || req.conjuncts().isEmpty())
            throw new IllegalArgumentException("conjuncts must not be empty");
        if (req.maxResults() > 1000)
            throw new IllegalArgumentException("maxResults must not exceed 1000");
        return ResponseEntity.ok(groundingService.query(req));
    }

    @PostMapping("/explain")
    public ResponseEntity<ExplainResponse> explain(@RequestBody ExplainRequest req) {
        return ResponseEntity.ok(groundingService.explain(req));
    }

    // ── Write endpoint ───────────────────────────────────────────────────────────

    @PostMapping("/assert")
    public ResponseEntity<AssertResponse> assertFact(@RequestBody AssertRequest req) {
        if (req.value() < 0.0 || req.value() > 1.0)
            throw new IllegalArgumentException("value must be in [0,1]");
        return ResponseEntity.ok(groundingService.assertFact(req));
    }

    // ── Derived-rules endpoint (L4, Phase 3) ────────────────────────────────────

    @GetMapping("/derived-rules")
    public ResponseEntity<List<GroundedRuleDerivationService.GroundedRule>> derivedRules(
            @RequestParam(required = false) Long factSheetId,
            @RequestParam(defaultValue = "0.5") double minConfidence) {
        return ResponseEntity.ok(groundingService.derivedRules(factSheetId, minConfidence));
    }

    // ── SSE subscription endpoint (Phase 2, stub) ───────────────────────────────

    @PostMapping("/subscribe")
    public ResponseEntity<SubscribeResponse> subscribe(@RequestBody SubscribeRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body(new SubscribeResponse(null, null, "Phase 2 — not yet implemented"));
    }

    @GetMapping(value = "/subscribe/{subscriptionId}/events",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeEvents(@PathVariable String subscriptionId) {
        // Phase 2: delegate to KbSubscriptionRegistry
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 2");
    }
}
```

**Response DTO records** (all live in `ai.kompile.app.web.controllers.grounding`):

```java
// VerifyRequest.java
public record VerifyRequest(
    String atom, Long factSheetId, Instant asOf,
    Double minConfidence, String sessionId
) {}

// VerifyResponse.java
public record VerifyResponse(
    String verdict, double confidence,
    List<String> evidenceAtoms, List<String> activatedRules,
    int derivationDepth, List<String> sourceProvenance,
    GroundingMeta meta
) {}

// QueryRequest.java
public record QueryRequest(
    List<ConjunctEntry> conjuncts, Long factSheetId, Instant asOf,
    int maxResults, double minConfidence, String sessionId
) {
    public record ConjunctEntry(String predicate, List<String> args) {}
}

// QueryResponse.java
public record QueryResponse(
    List<BindingRow> bindings, int total, boolean truncated,
    GroundingMeta meta
) {
    public record BindingRow(
        Map<String, String> variables, double confidence,
        List<String> matchedAtoms
    ) {}
}

// ExplainRequest.java
public record ExplainRequest(String atom, Long factSheetId, int depth, String sessionId) {}

// ExplainResponse.java
public record ExplainResponse(
    String atom, String verdict, double confidence,
    String summary, JsonNode derivation,   // DerivationTree.toJson() parsed back
    GroundingMeta meta
) {}

// AssertRequest.java
public record AssertRequest(
    String atom, double value, Long factSheetId,
    String sessionId, String source, Long expectedVersion
) {}

// AssertResponse.java
public record AssertResponse(
    String status, long version,
    List<String> contradictions, boolean cascadeTriggered,
    GroundingMeta meta
) {}

// GroundingMeta.java
public record GroundingMeta(
    Long factSheetId, Instant asOf, boolean stale,
    long stalenessBudgetMs, long kbVersion, String sessionId
) {}
```

---

## 8. `KbGroundingService` — Full Contract

**Location**: `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/grounding/KbGroundingService.java`

```java
@Service
public class KbGroundingService {

    // ── Injected ────────────────────────────────────────────────────────────────
    private final KnowledgeGraphService graphService;           // @Primary matrix/vector store
    private final ApplicationEventPublisher eventPublisher;
    private final ExplanationService explanationService;        // @Autowired(required=false)
    private final GroundedRuleDerivationService ruleDerivationService; // Phase 3

    // ── Per-factSheet state ──────────────────────────────────────────────────────

    /**
     * Holds per-factSheet grounding state. Populated lazily on first access;
     * rebuilt from the live KG store on restart (Phase 2 persistence).
     */
    public record FactSheetKbState(
        Long factSheetId,
        InferredFactStore inferredFactStore,        // materialized MAP results
        ConcurrentFactStore concurrentFactStore,    // observed/asserted facts (MVCC)
        PslProgram pslProgram,                      // rule program for this factSheet
        IncrementalGrounder grounder,               // delta-grounding engine
        JustificationIndex justificationIndex,      // derivation lineage
        TemporalView temporalView,                  // asOf snapshots
        ReadWriteLock lock                          // read-many / write-serialized per factSheet
    ) {}

    private final ConcurrentHashMap<Long, FactSheetKbState> stateMap = new ConcurrentHashMap<>();

    // ── Public API ───────────────────────────────────────────────────────────────

    public VerifyResponse verify(VerifyRequest req);
    public QueryResponse  query(QueryRequest req);
    public ExplainResponse explain(ExplainRequest req);
    public AssertResponse assertFact(AssertRequest req);
    public List<GroundedRuleDerivationService.GroundedRule> derivedRules(Long factSheetId, double minConf);

    // ── Internal ─────────────────────────────────────────────────────────────────

    public FactSheetKbState getState(Long factSheetId);   // lazy init / rebuild
    public Set<String> atomKeysToNodeIds(Set<String> atomKeys, Long factSheetId);

    private VerifyResult verifyWithInference(String atomKey, Long factSheetId,
                                              Collection<String> seedNodeIds);
    private GroundingMeta buildMeta(FactSheetKbState state);
    private String deterministicSummary(DerivationTree tree);
}
```

**`getState(factSheetId)` init sequence**:

```
1. ConcurrentHashMap.computeIfAbsent(factSheetId, id → {
2.   Load PslProgram from FileWeightStore (data/graph/psl-weights.json for this factSheet)
3.   Seed ConcurrentFactStore from graphService.getNodesByType(factSheetId, ...)
       — projects GraphNode.metadataJson → Fact via atom-key convention
4.   Create InMemoryInferredFactStore (Phase 1) or FileBackedInferredFactStore (Phase 2)
       — load persisted InferredFacts from data/graph/inferred/*.json if they exist
5.   Create IncrementalGrounder from PslProgram
6.   Create JustificationIndex (populated on first MAP solve)
7.   Create TemporalView backed by InferredFactStore
8.   Return new FactSheetKbState(...)
})
```

**Read lock / write lock discipline**:

- `verify`, `query`, `explain` → `state.lock().readLock().lock()` — parallel reads allowed.
- `assertFact` (steps 5-8 in assert flow) → `state.lock().writeLock().lock()` for the `ContradictionDetector` + `ConcurrentFactStore.assertFact` call; the lock is released before publishing `AgentFactAssertedEvent` (the async cascade runs outside the lock).
- `IncrementalReasoningOrchestrator.runDelta` → `state.lock().writeLock().lock()` for the full MAP solve + `InferredFactStore` update.

**Null `factSheetId` (cross-factSheet queries)**: `verify` and `query` accept `factSheetId = null`. In this case, the service queries all active `FactSheetKbState` entries and returns the union (for verify: the highest-confidence verdict across fact sheets; for query: the union of bindings with a `factSheetId` annotation added to each row). This is intentionally not exposed for `assert` — an assert must be scoped to a specific fact sheet.

---

## 9. Multi-Turn Agent Grounding-Session Pattern

A grounding session is a sequence of tool calls within one agent reasoning turn or across a multi-turn conversation, all attributed to the same `sessionId`. The session carries no server state — it is a **label for provenance and an audit trail**, not a server-side context object.

### 9.1 Session lifecycle

```
Agent turn 1:
  ask_graph_verify("isEmployedBy(Alice, Acme)", sessionId="sess-001")
  → verdict: UNKNOWN
  → meta.kbVersion: 1041

  (agent decides to assert based on its own reasoning)
  ask_graph_assert("isEmployedBy(Alice, Acme)", value=0.9,
                   sessionId="sess-001", expectedVersion=1041)
  → status: ASSERTED, version: 1042, meta.stale: true

  (agent waits meta.stalenessBudgetMs=2500ms or retries with stale awareness)
  ask_graph_verify("isEmployedBy(Alice, Acme)", sessionId="sess-001")
  → verdict: SUPPORTED, confidence: 0.9, meta.stale: false

Agent turn 2 (later conversation):
  ask_graph_explain("isEmployedBy(Alice, Acme)", sessionId="sess-001")
  → derivation.source: "sess-001:agent-extraction:run-42"
  → NL summary explains the agent's own prior assertion as provenance
```

### 9.2 Session-scoped audit trail

Every `Fact` asserted with a `sessionId` carries `sourceId = sessionId + ":" + source`. The `DerivationTree` surfaces this in `sourceProvenance`. An admin can query `GET /api/kb-grounding/derived-rules?factSheetId=42` and trace which rules were derived from agent-asserted facts by filtering `supportingAtoms` that start with the session prefix.

### 9.3 Grounding-session invariants

1. `sessionId` is purely a client-provided label. The server does not validate or issue session IDs.
2. `expectedVersion` is the mechanism for optimistic concurrency, not the session. An agent that cares about concurrent correctness should always use `expectedVersion`.
3. The session does not gate any permission checks — `checkPermission("ask_graph_assert", ...)` is called regardless of session presence.
4. A session label survives restarts (it is stored in the `Fact`'s `sourceId`) and is visible in `DerivationTree` queries indefinitely.

---

## 10. Confidence, Evidence, and Provenance Propagation Summary

This table shows how each field flows from the lib primitive to the MCP tool result:

| Field | Lib source | Controller mapping | Tool output |
|---|---|---|---|
| `confidence` | `InferredFact.confidence()` (`fol/InferredFact.java`) | Copied 1:1 into `VerifyResponse.confidence` | Rendered as decimal |
| `evidenceAtoms` | `InferredFact.supportingFactKeys()` | Copied 1:1 | Listed as bullets |
| `activatedRules` | `InferredFact.supportingRuleIds()` | Copied 1:1 | Listed as bullets |
| `derivationDepth` | Computed by `DefaultKbVerifier` from hops to leaf | Set in `VerifyResponse.derivationDepth` | Integer |
| `sourceProvenance` | `DerivationTree.sourceProvenance` = `InferredFact.runId()` | Extracted from `evidenceAtoms` via crawl-run ID convention | Listed |
| `kbVersion` | `ConcurrentFactStore.version()` | Set in every `GroundingMeta.kbVersion` | In `meta` block |
| `stale` | Cascade executor queue size > 0 for factSheetId | Checked in `buildMeta()` | In `meta` block |
| Per-row confidence (query) | `QueryBinding.confidence()` = min T-norm (`QueryBinding.java:28`) | Copied 1:1 per `BindingRow` | Per row |
| Derivation tree (explain) | `DerivationTree.build(...)` (`DerivationTree.java:87`) | `toJson()` parsed to `JsonNode` | Nested object |
| Assert version | `ConcurrentFactStore.assertFact()` return value | Set in `AssertResponse.version` | In response |

---

## 11. `GlobalExceptionHandler` Registration

Add `"ai.kompile.app.web.controllers.grounding"` to `GlobalExceptionHandler.basePackages`:

```java
// GlobalExceptionHandler.java:42
@ControllerAdvice(basePackages = {
    "ai.kompile.app.web.controllers",
    "ai.kompile.event.attribution.controller",
    "ai.kompile.event.observation.controller",
    "ai.kompile.process.attribution.controller",
    "ai.kompile.app.web.controllers.grounding"   // ADD THIS
})
```

Without this, any exception thrown by `KbGroundingController` (e.g., `IllegalArgumentException` on bad atom key) returns an opaque HTTP 500 with no message body. The frontend and MCP tool both read `err.error.message`, not `err.message`.

---

## 12. Open Questions

**Q1 — InferredFactStore durability tier** (from L2 design doc, Q1):
File-backed (`data/graph/inferred/*.json`) vs SQLite-backed impl of the `InferredFactStore` SPI. Decision gate: expected fact count per fact sheet at production scale. If > 100k facts, file-backed random-access via per-atom files becomes expensive; SQLite is faster but not git-tracked. **Recommendation pending user answer.**

**Q2 — Staleness tolerance and the assert→verify gap** (from L2 design doc, Q2):
This doc chose option (b) — `stale=true` + `stalenessBudgetMs` — over synchronous delta-cascade on assert. This means an agent asserting a fact and immediately verifying a derivative will see UNKNOWN until the async cascade completes. The alternative (synchronous cascade at `syncDepth=1`) adds bounded latency (PSL solve over depth-1 subgraph, typically 50–200ms) but eliminates the agent retry loop. **Decision needed before Phase 2 is wired.**

**Q3 — `allowInference` exposure in MCP tool**:
The service supports a targeted-inference escalation on UNKNOWN (section 2.3). Currently hidden from the MCP tool description to avoid agents over-triggering expensive MAP solves. Should the tool expose an `allowInference: boolean` parameter? If agents routinely get UNKNOWN on valid claims (because the cascade has not yet materialized them), this becomes necessary. **Monitor in Phase 1 integration testing.**

**Q4 — Cross-factSheet null scope for assert**:
Currently assert requires a specific `factSheetId`. This is intentional (provenance and cascade scoping require it). But an agent might not know the factSheetId for a claim. The tool description says "Null = all" for verify/query but assert is factSheet-scoped. A future `resolveFactSheet(atomKey)` heuristic (find the factSheet whose graph contains the atom's entities) could auto-scope. **Deferred to Phase 2.**

**Q5 — `ask_graph_subscribe` event-loop integration**:
Phase 2 SSE subscriptions need the MCP client (the LLM agent's tool runtime) to support long-lived HTTP streams. Most MCP runtimes today are request/response only. The practical workaround is polling: after `ask_graph_assert` with `meta.stale=true`, the agent polls `ask_graph_verify` at `meta.stalenessBudgetMs` interval. Subscribe is therefore a Phase 2 nicety, not a Phase 1 blocker. **Confirm MCP runtime capabilities before Phase 2 design.**
