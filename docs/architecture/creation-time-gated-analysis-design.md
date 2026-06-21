# Creation-Time Gated Analysis — Design

**Status:** Design only — no code changes, no Maven runs  
**Date:** 2026-06-21  
**Scope:** `kompile-graph-reasoning` (infra-free lib) + `kompile-knowledge-graph` client layer + `kompile-app-main` UI  
**Related:**
- `temporal-reasoning-attribution-design.md` — valid-time gating pattern being mirrored here
- `production-kb-integration-design.md` — crawl-commit signal and KB push lifecycle
- `incremental-cascade-reasoning-design.md` — cascade epoch and §7.4 `validFrom`/`validUntil` on `InferredFact`
- Memory: `project_graph_provenance.md`

---

## 0. What This Design Is (and Is Not)

The existing temporal model is **valid-time only** — `TemporalInterval`
(`kompile-graph-reasoning/.../model/TemporalInterval.java:1`) encodes *when a fact held in the
world*, and `TemporalView.asOf/between`
(`kompile-graph-reasoning/.../model/TemporalView.java:110`) gates a `ReasoningGraph` by that
event-occurrence axis. This design **does not reopen that decision and does not make the fact model
bitemporal**.

What it adds is a **second, orthogonal analysis-scoping axis**: *transaction time* — when a node or
edge was physically **created in the graph** (entered via a crawl, channel message, or agent
assert). The use case is not querying "what was true in the world at T?" but rather "what did the
graph learn from crawl X / during time window T, and what does the KB conclude from only that
material?". This is analysis scoping, not fact modelling. It is built on provenance that already
exists; no new production-path write is required for the common crawl case.

---

## 1. Where Creation Time Lives Today

### 1.1 Node creation time — three timestamps, one guaranteed

`GraphNode` (`kompile-knowledge-graph/.../domain/GraphNode.java:257`) carries:

| Field | Column | Set by | Meaning |
|---|---|---|---|
| `createdAt` | `created_at` | `@PrePersist` line 262 | DB insertion time — when the row was first written to JPA. This is the **transaction time** for the JPA store. |
| `observedAt` | `observed_at` | Crawl extraction code | When the node was first noticed during a specific crawl run. |
| `occurredAt` | `occurred_at` | Source document metadata | Real-world event time (email send, commit timestamp). Indexed. This is the **valid-time** axis. |

`GraphEdge` mirrors this exactly:
- `createdAt` at `GraphEdge.java:135`, set by `@PrePersist`
- `observedAt` at `GraphEdge.java:237`
- `occurredAt` at `GraphEdge.java:246`

**Decision:** `createdAt` (JPA `@PrePersist`) is the reliable transaction-time ground truth for the
JPA (secondary) store. The primary vector/matrix store does not persist JPA rows, so for that store
the creation timestamp must travel in `metadataJson` (see §1.2).

### 1.2 Provenance metadata — the store-agnostic seam

`GraphProvenanceKeys` (`kompile-knowledge-graph/.../domain/GraphProvenanceKeys.java:34–46`)
defines provenance keys stored in `GraphNode.metadataJson`. The `_extractedAt` key (line 42) is
the provenance-layer equivalent of creation time — it is set by the crawl extraction path when a
node is first written. `_crawlRunId` (line 38) ties every node to the specific extraction run that
created it.

**Current gap:** `_extractedAt` is defined as a key constant but is not uniformly set by all
producers. The crawl path populates it via `GraphProvenanceKeys.crawl(...)` (line 113). The
channel path (`GraphUpdateChannelBridge`) and agent-assert path do not. This must be closed.

### 1.3 The changeset as a creation-time boundary

`GraphChangesetCompletedEvent`
(`kompile-graph-change-tracking/.../event/GraphChangesetCompletedEvent.java:7`) carries:
- `changesetId` — a string identifier for the commit event
- `nodesCreated`, `nodesUpdated`, `nodesDeleted`, `edgesCreated`, `edgesDeleted`
- `factSheetId`

This event fires at the moment a crawl or channel extraction commits its graph writes. All nodes
and edges created during that extraction share the same `_crawlRunId`. **The changeset is the
natural unit of creation-time scoping** — "what was created in changeset X" is equivalent to
"what was created in the time window that changeset covered" because changeset IDs are already
persisted in provenance metadata.

---

## 2. The `CreationTimeView` — Gating Pattern

### 2.1 Decision: a separate view class, not a second axis on `TemporalView`

`TemporalView` (`model/TemporalView.java:1`) is already implemented, tested, and used by
`TemporalAttributionService`. Adding a second filter dimension to it would change its constructor
signatures and break call sites. Instead, a new `CreationTimeView` class mirrors the same pattern:
it wraps a `ReasoningGraph` and filters by creation-time metadata without copying data.

`TemporalView` and `CreationTimeView` are composable: wrapping one inside the other produces a
view scoped by both axes. The order of composition does not matter (both are lazy filters over the
same underlying graph).

### 2.2 `CreationTimeView` — specification

**Location:** `kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/model/CreationTimeView.java`

```
CreationTimeView implements ReasoningGraph {

    // Factories
    static CreationTimeView asOf(ReasoningGraph graph, Instant createdBefore)
    static CreationTimeView between(ReasoningGraph graph, Instant from, Instant to)
    static CreationTimeView byCrawlRun(ReasoningGraph graph, String crawlRunId)
    static CreationTimeView byChangeset(ReasoningGraph graph, String changesetId)

    // Internal
    private final ReasoningGraph delegate
    private final Instant createdFrom    // null = −∞
    private final Instant createdBefore  // null = +∞
    private final String  crawlRunId     // null = any
    private final String  changesetId    // null = any

    // Filter contract
    private boolean entityMatches(GraphEntity e)
    private boolean relationMatches(GraphRelation r)
    // relation included iff both endpoints are included (mirrors TemporalView line 158)
}
```

### 2.3 Reading creation time from a `GraphEntity`/`GraphRelation`

`GraphEntity` attributes (`GraphEntity.java:93`) carry free-form metadata. The creation timestamp
must be read from the `_extractedAt` key (a `GraphProvenanceKeys` constant). The crawl-run identity
is read from `_crawlRunId`. A changeset ID, if needed, is read from a new key
`_changesetId` (to be added to `GraphProvenanceKeys`).

**Resolution order for creation instant:**
1. `attributes().get("_extractedAt")` parsed as ISO-8601 `Instant` — populated by crawl producer
2. `attributes().get("_observedAt")` — channel and agent path fallback
3. `null` — element predates provenance tracking; treated as "always included" (open-left default,
   matches `TemporalView`'s timeless-element rule at line 265)

This keeps the lib infra-free: no JPA dependency; it reads only the attribute map already on
`GraphEntity`.

### 2.4 `CreationTimeInterval` — value object

A thin parallel to `TemporalInterval`:

```
record CreationTimeInterval(Instant from, Instant before) {
    boolean contains(Instant createdAt)
    // from <= createdAt < before  (half-open, same convention as TemporalInterval)
    static CreationTimeInterval since(Instant from)
    static CreationTimeInterval until(Instant before)
    static CreationTimeInterval of(Instant from, Instant before)
}
```

Placed in `model/CreationTimeInterval.java`, same package as `TemporalInterval`.

---

## 3. Producer Responsibilities — Closing the Gap

All three producers that write nodes/edges must populate `_extractedAt` and `_crawlRunId` (and,
once defined, `_changesetId`) in the node's metadata map.

### 3.1 Crawl producer (already mostly done)

`GraphProvenanceKeys.crawl(crawlRunId, sourceDocumentId, sourceChunkId)` (line 113) builds the
map. `_extractedAt` must be added to this factory method: set to `Instant.now()` at call time
(inside `GraphExtractionOrchestrator`). This is an additive change to one factory call.

### 3.2 Channel producer (gap)

`GraphUpdateChannelBridge` (wired in Phase 2 channel work, `project_channel_graph.md`) calls
`MultiAgentExtractionService.runExtraction`. Provenance is not set here. The bridge must inject
a channel-scoped provenance map into node creation calls:
- `_source` = `"channel:" + channelId`
- `_extractedAt` = `Instant.now()`
- `_crawlRunId` = a channel-message-scoped ID (e.g. `"channel-" + messageId`)
- `_changesetId` = matched to `GraphChangesetCompletedEvent.changesetId` when that event fires

### 3.3 Agent-assert producer (future)

When `AgentFactAssertedEvent` is wired, provenance map must include:
- `_source` = `"agent"`
- `_extractedAt` = assertion timestamp
- `_crawlRunId` = `"agent-assert-" + agentSessionId`

### 3.4 `_changesetId` — new key

Add `CHANGESET_ID = "_changesetId"` to `GraphProvenanceKeys`. The `GraphBuildCompletedEventIntegration`
fires `GraphChangesetCompletedEvent` with a `changesetId`. After the event fires, nodes created
during that crawl can be tagged with this changeset ID. **Practical approach:** store `changesetId`
in the `GraphExtractionOrchestrator`'s run context and inject it into each node's metadata at
write time (same pattern as `crawlRunId`). This makes `changesetId` a first-class filter key.

---

## 4. Gated Analysis — Running Reasoning on the Creation-Scoped View

### 4.1 The pattern

Every reasoning entry point already accepts a `ReasoningGraph`:
- `FolInferenceService.infer(ReasoningGraph graph, FolRuleSet ruleSet)`
  (`kompile-graph-reasoning/.../fol/FolInferenceService.java:92`)
- `KbGroundingService` does not accept a `ReasoningGraph` directly — it operates on the
  `FactSheetKbState` (materialized fact store). Scoping here requires a different pattern (§4.3).
- `TemporalAttributionService.attribute(ReasoningGraph graph, TemporalAttributionQuery query)`
  (`kompile-graph-reasoning/.../attribution/TemporalAttributionService.java:99`)

A creation-scoped analysis is:
```java
ReasoningGraph scopedGraph = CreationTimeView.byCrawlRun(fullGraph, crawlRunId);
FolInferenceResult result  = folInferenceService.infer(scopedGraph, ruleSet);
```

This answers: "what does the inference engine conclude from only the nodes and edges introduced by
this crawl run?". No copy, no new store, no JPA dependency.

### 4.2 Gated attribution — "what did this update introduce?"

The canonical analysis use case:

```java
// What did crawl X add, and how does it shift attribution for a target node?
ReasoningGraph crawlDelta = CreationTimeView.byCrawlRun(fullGraph, crawlRunId);
TemporalAttributionQuery query = TemporalAttributionQuery.builder()
    .targetNodeId(targetId)
    .maxDepth(5)
    .build();
TemporalAttributionResult result = TemporalAttributionService.INSTANCE.attribute(crawlDelta, query);
```

Result: attribution chains anchored only to the material introduced by that crawl. Compare against
`TemporalAttributionService.attribute(fullGraph, query)` to see the **delta contribution** of the
crawl.

Composition with valid-time:
```java
// Only nodes created in the last crawl AND valid as of yesterday
ReasoningGraph step1 = CreationTimeView.byCrawlRun(fullGraph, crawlRunId);
ReasoningGraph step2 = TemporalView.asOf(step1, Instant.now().minus(1, DAYS));
TemporalAttributionResult result = TemporalAttributionService.INSTANCE.attribute(step2, query);
```

Both filters are lazy; composition is cheap (two delegate wrappers).

### 4.3 Gated KB grounding — scoped `InferredFact` generation

`KbGroundingService` holds a `FactSheetKbState` keyed by `factSheetId`. It does not accept a
`ReasoningGraph` slice because the `FactStore` is pre-projected (atoms, not graph primitives).
Gated grounding therefore requires a **projection step**:

1. Build a `CreationTimeView` of the full graph.
2. Project the scoped view to atoms via `GraphToFactStoreProjector` (the missing link identified
   in `production-kb-integration-design.md §3.1`).
3. Pass the resulting `FactStore` to `FolInferenceService.buildProgram` + `inferFacts`.
4. Store the result in a **ephemeral, scoped `InferredFactStore`** (not the live per-fact-sheet
   store) — this keeps the gated analysis isolated from the production KB state.

A convenience entry point:
```
CreationTimeGroundingService.groundScoped(
    ReasoningGraph fullGraph,
    CreationTimeInterval window,   // or crawlRunId
    FolRuleSet ruleSet
) → List<InferredFact>
```

This is a thin infra-free coordinator; it does not extend `KbGroundingService` (which is
Spring-managed). It belongs in the `kompile-graph-reasoning` lib, using the same
`FolInferenceService` and an in-memory `InMemoryInferredFactStore`.

### 4.4 Cascade integration — epoch delta analysis

`KbGroundingService.markEpoch(factSheetId, runId, justificationIndex)` is called at cascade STEP 8
(`KbGroundingService.java:361`). At this point `runId` identifies the cascade that just completed,
and `runId` maps to a `crawlRunId` or `changesetId` that triggered the cascade.

**Addition:** Record a `CreationTimeInterval` alongside each epoch — specifically, the wall-clock
window `[cascadeStart, cascadeEnd)` during which this cascade ran. This is stored in the
`FactSheetKbState` epoch map (in-memory). A new `CreationTimeGroundingService.groundAtEpoch(
factSheetId, epochRunId)` can then reconstruct the scoped `InferredFact` set that existed at a
given epoch by filtering the `InferredFactStore`'s version history by `cascade.runId`.

This integrates with the planned `InferredFact.validFrom`/`validUntil` (§7.4 of
`incremental-cascade-reasoning-design.md`): the cascade's creation-time window becomes the
`validFrom` of all inferred facts it produces, enabling cross-axis queries ("what was inferred
from material created in crawl X, and when did those inferences first become valid?").

---

## 5. Composition of Both Axes

The two axes are orthogonal and composable:

| Axis | View class | Filter key | Meaning |
|---|---|---|---|
| Valid-time (event-time) | `TemporalView` | `validFrom`/`validUntil` in attributes, or `timestamp()` | When the real-world fact held |
| Creation-time (transaction-time) | `CreationTimeView` | `_extractedAt` in metadata, or `_crawlRunId`/`_changesetId` | When the node/edge entered the graph |

Compose by nesting — order is irrelevant because both are lazy filters on the same delegate graph:
```java
// Nodes created THIS WEEK that were valid LAST YEAR
ReasoningGraph g = CreationTimeView.between(
    TemporalView.between(fullGraph, lastYearStart, lastYearEnd),
    thisWeekStart, thisWeekEnd);
```

This is **lightweight bitemporality for analysis** without making the fact model itself bitemporal.
No new JPA schema, no new store column — only metadata-map reads and lazy view wrappers.

---

## 6. API and UI Surface

### 6.1 REST endpoints

Add to `KbGroundingController` (or a new `CreationTimeAnalysisController`):

```
POST /api/kb/grounding/creation-scoped
Body: {
  "factSheetId": 42,
  "crawlRunId": "run-abc123",         // option A: by run
  "createdFrom": "2026-06-01T00:00Z", // option B: by window
  "createdBefore": "2026-06-21T00:00Z",
  "changesetId": "cs-xyz789",         // option C: by changeset
  "ruleSetId": "default",
  "targetNodeId": "node-999"          // optional: attribution target
}
Response: {
  "inferredFacts": [...],
  "attributionChains": [...],
  "scopedNodeCount": 142,
  "scopedEdgeCount": 318,
  "crawlRunId": "run-abc123",
  "creationWindow": { "from": "...", "before": "..." }
}
```

```
GET /api/kb/grounding/epochs/{factSheetId}
Response: [{ "runId": "...", "createdFrom": "...", "createdBefore": "...", "inferredFactCount": N }, ...]
```

`GlobalExceptionHandler` note (`reference_global_exception_handler_scope.md`): the new controller
must be in or added to the `basePackages` list so errors surface properly to the frontend.

### 6.2 MCP tool

```
ask_graph_creation_scoped(factSheetId, crawlRunId?, createdFrom?, createdBefore?, targetNodeId?)
→ { inferredFacts, attributionChains, scopedNodeCount }
```

Follows the same pattern as the existing `ask_graph_*` tools.

### 6.3 UI surface

**Grounding Console** (existing) — add a "Creation Scope" filter row:
- "All time" (default) / "By crawl run" (dropdown of recent runs) / "Custom window" (date range)
- "By changeset" (maps to changeset ID from crawl history)

When a scope is selected, the console sends the creation-scoped POST and renders the result in the
existing `InferredFact` table, annotated with a "Scoped" badge showing the creation window.

**Attribution Tab** (existing, `project_event_attribution_surfacing.md`) — add a "Creation Filter"
input alongside the existing temporal-window input. A user can now ask:
"What caused [event X], considering only information introduced by [crawl Y]?"

**Process Mining / Bottleneck panel** — the same creation-scoped graph can be passed to the
`ProcessMiningService` to compute a process model from only the activity nodes created in a given
crawl or window. This surfaces the "contribution" of a crawl to the process model.

**Crawl History panel** — after each crawl completes, show a "Scoped Analysis" button that
pre-fills the creation scope with that crawl's `crawlRunId` and opens the Grounding Console. This
makes the delta-contribution pattern one click away.

---

## 7. Phased Plan

### Phase C1 — Data layer (creation timestamp reliability)
1. Add `CHANGESET_ID = "_changesetId"` to `GraphProvenanceKeys.java`
2. In `GraphProvenanceKeys.crawl(...)`: add `_extractedAt = Instant.now().toString()`
3. In `GraphBuildCompletedEventIntegration`: inject `changesetId` into crawl provenance before firing
4. In `GraphUpdateChannelBridge`: inject channel provenance map (`_source`, `_extractedAt`, `_crawlRunId`)
5. **Tests:** assert `_extractedAt` and `_crawlRunId` present in metadata after a crawl and after a channel message

### Phase C2 — View layer (infra-free, in `kompile-graph-reasoning`)
1. `model/CreationTimeInterval.java` — value object, mirrors `TemporalInterval`
2. `model/CreationTimeView.java` — lazy filter implementing `ReasoningGraph`; factories `asOf`, `between`, `byCrawlRun`, `byChangeset`
3. Unit tests: `CreationTimeViewTest` — same test structure as `TemporalViewTest`; verify relation endpoint rule (both endpoints must match), timeless-element passthrough, null-bound open semantics

### Phase C3 — Gated inference (infra-free)
1. `creation/CreationTimeGroundingService.java` (in `kompile-graph-reasoning`) — `groundScoped(ReasoningGraph, CreationTimeInterval, FolRuleSet)` coordinator using `FolInferenceService` + `InMemoryInferredFactStore`
2. Tests: scoped inference over a graph where half the nodes have `_extractedAt` before cutoff and half after; verify only the pre-cutoff half contributes

### Phase C4 — Cascade epoch annotation
1. Add `createdFrom`/`createdBefore` fields to the epoch map in `FactSheetKbState`
2. `KbGroundingService.markEpoch(...)` records wall-clock start/end of the cascade
3. New `GET /api/kb/grounding/epochs/{factSheetId}` endpoint

### Phase C5 — REST + MCP surface
1. `CreationTimeAnalysisController` with POST `/api/kb/grounding/creation-scoped`
2. Add controller package to `GlobalExceptionHandler.basePackages`
3. `ask_graph_creation_scoped` MCP tool (follows existing `ask_graph_*` pattern)

### Phase C6 — UI
1. "Creation Scope" filter row in Grounding Console
2. "Creation Filter" input in Attribution Tab
3. "Scoped Analysis" button in Crawl History panel

---

## 8. Open Questions

### OQ-1 (CRITICAL): Primary store creation-time reliability

The `@Primary` vector/matrix store does not use JPA `@PrePersist`. Creation time therefore depends
entirely on the `_extractedAt` metadata key. If a producer omits it (gap identified in §3.2–3.3),
the node is treated as "always included" by `CreationTimeView` — silently widening the scope.
**Decision needed:** should nodes with missing `_extractedAt` be excluded (strict mode) or included
(permissive mode, current proposal)? Strict mode prevents silent scope widening but breaks queries
over legacy graph data that predates this feature.

### OQ-2: `_extractedAt` vs `createdAt` divergence on re-crawl

If a node is first created in crawl A (`_extractedAt` = T1) and then upserted in crawl B
(`_crawlRunId` updated to B's run), `_extractedAt` now reflects B's time, losing A's creation
record. The JPA store has `GraphNode.createdAt` which does not change on upsert (`@PrePersist`
fires only on insert). **Decision needed:** should `_extractedAt` be write-once (set only if absent,
preserving first-creation semantics) or update-on-upsert (reflecting last-modification semantics)?
Recommendation: write-once, add a separate `_lastModifiedAt` key for the update case.

### OQ-3: Changeset ID propagation timing

`GraphChangesetCompletedEvent` fires after writes complete, so the `changesetId` is only known
after extraction finishes. Injecting it into per-node metadata retroactively would require a
bulk-update pass over all nodes in the changeset. The alternative is to pre-generate the
`changesetId` at crawl start (before writes) and thread it through `GraphExtractionOrchestrator`
as part of the run context alongside `crawlRunId`. **Recommendation:** pre-generate `changesetId`
at crawl start; it equals the job ID or a UUID assigned in `UnifiedCrawlJob`.

### OQ-4: `CreationTimeGroundingService` isolation from production KB

Scoped inference writes to an ephemeral `InMemoryInferredFactStore`, not the live
`FileBackedInferredFactStore`. If the user wants to persist or compare scoped results against the
live KB, the API must expose a diff. The cascade epoch design in §4.4 is the long-term answer, but
the Phase C3 deliverable is ephemeral-only. Is ephemeral-only sufficient for the initial use case?

### OQ-5: `TemporalAttributionService` and creation-time composition

`TemporalAttributionService.applyWindowScope()` (line 182) applies `TemporalView.between()` if
the query specifies a temporal window. A creation-scoped attribution query (§4.2) wraps the graph
before passing it to `TemporalAttributionService` — the service is unaware it is operating on a
creation-scoped view. This is clean but means the `TemporalAttributionQuery` has no way to express
"creation scope" as a first-class parameter. Should `TemporalAttributionQuery` grow an optional
`CreationTimeInterval creationScope` field that the service resolves internally (wrapping the graph
before BFS), or should callers always pre-wrap? Pre-wrapping keeps the service simpler and the lib
composable; internal resolution is more convenient for the REST API.

### OQ-6: Process mining over creation-scoped graphs

`ProcessMiningService` (in `kompile-process-discovery`) accepts a `ReasoningGraph`. Passing a
`CreationTimeView` is zero-cost at the call site. The open question is whether the activity-log
reconstruction logic in `ProcessMiningService` reads only `occurredAt` (valid-time) or also
`_extractedAt`. If it reads only `occurredAt`, creation-scoped process mining works correctly out
of the box. Verify before Phase C6.
