# Production KB Integration Design
## How Kompile as a Whole Integrates with the Agent-Grounding Knowledge Base

**Date**: 2026-06-21
**Status**: DESIGN COMPLETE — read before building the first production wiring piece
**Depends on**:
- `agent-grounding-infrastructure-design.md` (L1–L5 layer spec + MCP tools)
- `incremental-cascade-reasoning-design.md` (L3 algorithm detail)
- `production-fol-kb-agent-grounding-gaps.md` (gap matrix that motivated both)
**Context**: The reasoning lib (594 green tests), `KbGroundingService`, `KbGroundingController`,
`GroundingCascadeHook`, `IncrementalReasoningOrchestrator`, and all five `ask_graph_*` MCP tools
are built and exist in the main tree. The gap this document closes is the
**production/whole-system integration**: how a crawl commits its per-fact-sheet graph into the live
KB, how the dual store handles it, what "production" deployment looks like, and the integration map
across all kompile subsystems.

---

## 1. The Crawl → Production-KB Push Lifecycle

### 1.1 What the crawl produces

A `UnifiedCrawlJob`
(`kompile-app-core/.../crawl/graph/UnifiedCrawlJob.java:46`) carries `factSheetId` from its
`UnifiedCrawlRequest` (`UnifiedCrawlRequest.java:45`). During extraction,
`GraphExtractionOrchestrator`
(`kompile-crawl-graph/.../GraphExtractionOrchestrator.java:960`) writes nodes and edges into the
`@Primary` vector/matrix `KnowledgeGraphService` store with the fact-sheet scope via the 6-arg
`createNode`. On extraction completion it publishes `GraphBuildCompletedEvent`
(`kompile-app-core/.../graphbuilder/GraphBuildCompletedEvent.java:27`) carrying `factSheetId`,
`entitiesExtracted`, and `edgesCreated`.

`GraphBuildCompletedEventIntegration`
(`kompile-graph-change-tracking/.../hook/GraphBuildCompletedEventIntegration.java:23–38`) converts
this to `GraphChangesetCompletedEvent`
(`kompile-graph-change-tracking/.../event/GraphChangesetCompletedEvent.java:7`) and publishes it
synchronously plus fires the `GraphUpdateHookRegistry`. This single event is the **crawl commit
signal** — it is the moment the graph transitions from draft-in-progress to committed/live.

### 1.2 Decided: no explicit "draft vs committed" state

There is no graph-level draft/committed toggle. The word "committed" means:
`GraphBuildCompletedEvent` has fired and listeners have received it. All writes to the
`@Primary` store happen atomically per-node/edge (no staging buffer). The distinction that matters
is **KB currency**: the `InferredFactStore` is stale until `GroundingCascadeHook.onChangesetCompleted`
runs `IncrementalReasoningOrchestrator.runFullReground(factSheetId)` and the epoch is bumped via
`KbGroundingService.markEpoch`. Between the end of extraction and the end of the first cascade, the
graph nodes and edges are live in the store but derived facts are stale. Agents calling
`ask_graph_verify` during this window see the pre-crawl `InferredFactStore` snapshot (correct;
they do not see inconsistent intermediate states).

**Decision**: Do not add a draft layer. Add a `graphGroundingStale: boolean` field to the job
snapshot (already exposed via `ProgressSnapshot`) that is set true at job start and cleared when
the first post-crawl cascade epoch is recorded. This lets the UI inform users that the KB is
being updated without introducing a new consistency state.

### 1.3 Idempotent re-crawls and supersession

A re-crawl of the same fact sheet produces new or updated nodes and edges via the vector/matrix
store's upsert semantics (the `@Primary` store). The subsequent
`GraphChangesetCompletedEvent` fires `GroundingCascadeHook.onChangesetCompleted`, which schedules a
full re-ground. The MAP solver operates over all current facts in the `FactStore` for that fact
sheet — it does not diff. Supersession of prior inferred facts happens via `InferredFactStore`'s
version-bump contract: atoms whose value changed by more than ε=0.001 get a new version;
stable atoms do not. A re-crawl that adds no new information produces zero version writes (the
fixed-point condition).

**Temporal supersession**: When `InferredFact` gains `validFrom`/`validUntil` (design spec
`incremental-cascade-reasoning-design.md §7.4`), each cascade run implicitly closes the prior
version's `validUntil` and opens the new version's `validFrom` at `cascade.runId` timestamp. This
gives a temporal audit trail of KB evolution without any additional wiring.

**Re-crawl idempotence checklist**:
- Graph nodes: upserted by `externalId` (store-level dedup already in place)
- Edges: duplicate-suppressed by source+target+type within the extraction run
- `InferredFact` versions: monotonically bumped only on meaningful change
- `data/graph/factsheet-<id>.json`: re-exported by `ProjectGraphPortabilityService.onChangesetCompleted`
  (`ProjectGraphPortabilityService.java:289`) on each changeset — last-write-wins, git-tracked

### 1.4 The complete push sequence

```
[CRAWL]
  UnifiedCrawlService.startJob(request{factSheetId=42})
    └─ GraphExtractionOrchestrator runs per-chunk extraction
         └─ graphService.createNode(6-arg, factSheetId=42) × N
         └─ graphService.createEdge(..., factSheetId=42) × M
    └─ publishes GraphBuildCompletedEvent(factSheetId=42, entities=N, edges=M)

[COMMIT SIGNAL]
  GraphBuildCompletedEventIntegration.onGraphBuildCompleted()
    └─ publishes GraphChangesetCompletedEvent(changesetId, factSheetId=42, ...)
    └─ hookRegistry.executeChangesetComplete(event)
         ├─ GraphRuleHook: fires LOG/WEBHOOK rules if threshold met
         ├─ ProjectGraphPortabilityService: re-exports data/graph/factsheet-42.json
         └─ GroundingCascadeHook: schedules cascade on per-factSheet executor

[GROUNDING CASCADE — async, per-factSheet serialized]
  IncrementalReasoningOrchestrator.runFullReground(42)
    └─ Acquires write lock on FactSheetKbState[42]
    └─ Builds PslProgram from FactStore[42] (all current asserted facts)
    └─ ScalarHlMrfInference.solve(program)
    └─ EntailmentEngine.entailFromPslResult(...) → List<InferredFact>
    └─ InferredFactStore[42].store(fact) for each fact with changed value
    └─ JustificationIndex rebuild
    └─ ContradictionDetector.detect(...) → publish if non-empty
    └─ KbGroundingService.markEpoch(42, runId, newIndex)
         └─ sets epochMap[42] = runId
         └─ publishes GroundingMaterializationCompleteEvent (NEW — see §3.2 below)
    └─ Releases write lock

[KB IS NOW CURRENT — agents see fresh InferredFacts]
  ask_graph_verify("knows(alice,AcmeCorp)", factSheetId=42)
    → KbVerifier.verify() hits InferredFactStore[42].latest("knows(alice,AcmeCorp)")
    → SUPPORTED, confidence=0.80
```

---

## 2. Production Durability of Grounding State

### 2.1 Dual-store topology (decided)

The live runtime uses exactly two stores, as already decided and in the MEMORY:

| Store | What it holds | Persistence | Module |
|---|---|---|---|
| `@Primary` vector/matrix store | `GraphNode`, `GraphEdge`, raw extracted facts, inferred INFERRED-provenance edges | In-process memory + serialized to `data/graph/*.json` on changeset + embeddings via xet sidecar | `kompile-knowledge-graph` + matrix backend |
| HSQLDB (JPA) via `@Secondary` | `FactSheet`, `IndexingJobHistory`, crawl metadata, ontology schemas, process rules, graph health snapshots, agent judgements | File-backed HSQLDB at `~/.kompile/<project>/db/` (or configurable path) | `kompile-app-main` JPA entities |

The `InferredFactStore` is currently in-memory (`InMemoryInferredFactStore`) — which means the
KB grounding state is **lost on restart**. This is the primary production durability gap.

### 2.2 Decided durability model for InferredFactStore

**Decision: file-backed JSON store, co-located with the graph portability files.**

Rationale: matches the existing Phase-1 portability design, travels on `git clone`, is git-tracked,
and requires no new infrastructure. The `InferredFact.toJson()` method (`InferredFact.java:159`)
already exists.

Concrete path layout (NEW — to be implemented):

```
data/
  graph/
    factsheet-42.json           # graph nodes + edges (Phase-1, existing)
    embeddings/
      factsheet-42.bin          # KGE embeddings (existing)
    inferred/
      factsheet-42-latest.jsonl # one line per atomKey: the latest InferredFact for that key
      factsheet-42-history.jsonl# full version history (optional; can be omitted for clone-size)
    domain-objects.json         # DomainObjectRegistry entries (L4, see §3)
```

New class: `FileBackedInferredFactStore implements InferredFactStore` in
`kompile-knowledge-graph/.../grounding/`. On `store(fact)`:
1. Serialize to JSON via `InferredFact.toJson()`.
2. Append to `factsheet-<id>-history.jsonl`.
3. Overwrite the atom key's entry in `factsheet-<id>-latest.jsonl` (one-line-per-key format for O(1) random-access by atom key on startup load, using a side-index `Map<atomKey, fileOffset>`).

On startup, `KbGroundingService.getState(factSheetId)` (the lazy factory) loads
`factsheet-<id>-latest.jsonl` into the `InMemoryInferredFactStore` held in `FactSheetKbState`.
Estimated load time: <2s for 50k facts (50k JSON lines, simple deserialization).

`ProjectGraphPortabilityService.onChangesetCompleted` should be extended to also flush the
`InferredFactStore` to the JSONL files after each cascade completes (the
`GroundingMaterializationCompleteEvent` is the right hook point). This ensures the on-disk
state is always a valid snapshot of the last completed cascade.

**What does NOT travel on clone**: the `FactStore` (observed/asserted facts from agent sessions).
These are session-ephemeral by design. The `InferredFact` records are the durable output; the
FactStore is rebuilt on the next crawl or from a re-ground triggered on startup.

**HSQLDB for InferredFact: decided NOT to use.** The JPA store is HSQLDB today, which has file
limits (~2GB) and is not the right structure for per-atom version querying. The JSONL approach
avoids a new table, scales to millions of facts as flat files, and fits the git-tracked portability
model.

### 2.3 What rides in which store on clone

| Artifact | Travels on clone? | Store | How |
|---|---|---|---|
| Graph nodes + edges | YES | data/graph/factsheet-N.json | Phase-1 exporter, existing |
| Named graph registry | YES | same JSON | PortableNamedGraph, existing |
| KGE embeddings | YES (via git-xet sidecar) | data/graph/embeddings/factsheet-N.bin | GraphEmbeddingSidecar, existing |
| Latest InferredFacts | YES (NEW) | data/graph/inferred/factsheet-N-latest.jsonl | FileBackedInferredFactStore |
| InferredFact history | YES (NEW, optional) | data/graph/inferred/factsheet-N-history.jsonl | FileBackedInferredFactStore |
| DomainObjectRegistry | YES (NEW) | data/graph/domain-objects.json | L4 service |
| FactSheet metadata | YES (via HSQLDB export on commit, planned) | HSQLDB → CSV → git | deferred |
| FactStore (observed facts from agent sessions) | NO | in-memory only | ephemeral by design |
| PslProgram weights | YES if FileWeightStore is configured | data/graph/psl-weights.json | FileWeightStore (already in lib) |

---

## 3. Deployment Model

### 3.1 Embedded vs service: decided embedded

The grounding KB is **embedded in the kompile-app Spring Boot process**. There is no separate
grounding microservice. Reasoning:
- The `@Primary` vector/matrix store is already in-process.
- The `KbGroundingService`, `IncrementalReasoningOrchestrator`, and `GroundingCascadeHook` are
  Spring `@Service`/`@Component` beans wired inside the same process.
- MCP tools call `POST /api/kb-grounding/*` over HTTP to `localhost:8090` (the same process the
  CLI already connects to for `KnowledgeGraphTool` and `GraphRagSearchTool`).
- Adding an out-of-process grounding service would require serializing the full `ReasoningGraph`
  and `PslProgram` across a network boundary — premature until scale demands it.

**Future out-of-process path** (if needed): introduce a `GrpcGroundingService` alongside the REST
controller; the MCP tools switch to gRPC. This is a thin shim on top of existing contracts.

### 3.2 Per-project scoping

All KB state is scoped by `factSheetId` (a JPA-backed Long from the `fact_sheets` HSQLDB table,
`FactSheet.java:49`). A project (identified by `KompileProjectStore` / `kompile.project.json`)
contains one or more fact sheets. The KB does not cross project boundaries.

At `project open`, `ProjectGraphPortabilityService` rehydrates the graph from
`data/graph/factsheet-N.json` (existing, guarded by empty-graph check). The `KbGroundingService`
lazy-loads `FactSheetKbState` on first access, which in turn loads the JSONL inferred facts. No
explicit "bootstrap KB" step is needed.

At `project init` (`KompileProjectStore.init`), the standard directories are created including
`data/graph/inferred/`. The JSONL files do not yet exist; first access produces an empty
`FactSheetKbState`. The first crawl populates everything.

### 3.3 When reasoning runs (decided)

| Trigger | Scope | Execution model | Timing |
|---|---|---|---|
| Crawl completes (`GraphBuildCompletedEvent`) | Full fact-sheet re-ground | Async, per-factSheet single-threaded executor via `GroundingCascadeHook` | Seconds to minutes after crawl, depending on graph size |
| Channel message extraction completes (`GraphChangesetCompletedEvent`) | Full fact-sheet re-ground | Same executor | Seconds after extraction |
| Agent `ask_graph_assert` (`AgentFactAssertedEvent`) | Full fact-sheet re-ground (upgrade to DELTA_ATOMS is the pending TODO in `IncrementalReasoningOrchestrator.java:66–78`) | Same executor | Seconds |
| Manual `POST /api/kb-grounding/reground?factSheetId=N` | Full re-ground on demand | Synchronous REST response (for small fact sheets) or async + SSE | On-demand |
| Startup with existing JSONL | Load from file, no re-ground | Synchronous during `getState` lazy init | <2s |

On-demand re-ground endpoint is the **first thing to build** after the file-backed store, because
it provides an escape hatch for operators who want to force-refresh the KB without a re-crawl.

### 3.4 Scale thresholds

| Fact-sheet size | MAP solve time (est.) | Re-ground frequency | Action |
|---|---|---|---|
| < 5,000 atoms | < 1s | Any frequency | No change needed |
| 5,000 – 50,000 atoms | 1–30s | Per crawl (low freq) | Async cascade, current design |
| > 50,000 atoms | 30s+ | Per crawl only | Add chunked MAP solve by connected component (design spec §9.4); also consider DELTA_ATOMS for agent asserts (spec §6.2 upgrade) |

For the initial production deployment, the 5k–50k range covers most kompile use cases (the FP&A
demo has ~200k edges but most atoms in the PSL program are structural, not semantic predicate
atoms). The full re-ground is acceptable.

---

## 4. Whole-Kompile Integration Map

The grounding KB is a **passive accumulator** touched by all subsystems but never blocking them.
Every subsystem writes to the graph store; the cascade hook automatically propagates changes to the
KB. No subsystem except the MCP tools and the REST grounding controller needs to know the KB
exists.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  PRODUCTION KNOWLEDGE BASE (KbGroundingService, InferredFactStore)      │
│  per factSheetId — embedded in kompile-app process                      │
└──────┬───────────────────────────────────────────────────────┬──────────┘
       │  READ (ask_graph_verify/query/explain)                │  WRITE (cascade)
       │                                                       │
       ▼                                                       │
┌──────────────┐  ┌────────────────────────────┐              │
│ L1 MCP Tools │  │ REST /api/kb-grounding/*   │              │
│ AskGraph*    │  │ KbGroundingController      │              │
│ (CLI)        │  │ (kompile-app-main)         │              │
└──────────────┘  └────────────────────────────┘              │
                                                               │
                  ┌────────────────────────────────────────────┘
                  │  WRITE via GraphChangesetCompletedEvent + cascade
                  │
       ┌──────────┼────────────────────────────┐
       │          │                            │
       ▼          ▼                            ▼
┌──────────┐ ┌──────────────┐ ┌────────────────────────────┐
│ CRAWL    │ │ CHANNELS     │ │ AGENT ASSERTS              │
│ engine   │ │ email/Slack  │ │ ask_graph_assert            │
│ (app-    │ │ channel      │ │ → AgentFactAssertedEvent   │
│ core/    │ │ adapters     │ │ → same cascade hook        │
│ crawl-   │ │ → extraction │ │                            │
│ graph)   │ │ → changeset  │ │                            │
└──────────┘ └──────────────┘ └────────────────────────────┘
```

### 4.1 Crawl engine integration (existing, wired)

`GraphExtractionOrchestrator` writes graph nodes/edges → `GraphBuildCompletedEvent` →
`GraphBuildCompletedEventIntegration` → `GraphChangesetCompletedEvent` →
`GroundingCascadeHook.onChangesetCompleted` → cascade.

**What is NOT wired yet**: The `FactStore` (the PSL observed-fact layer) is populated only by
`KbGroundingService.assertFact` (agent-side). The cascade currently builds its PSL program from
facts asserted by agents, not from the raw graph nodes/edges. The graph-to-FactStore bridge is the
**missing production link**: after each changeset, the crawled entities and relations must be
projected into PSL atom form and asserted into the `FactStore` so the MAP solve has data to work
with. This is `GraphToFactStoreProjector` (see §5 below).

### 4.2 Channel integration (existing, wired)

`GraphUpdateChannelBridge` produces `GraphChangesetCompletedEvent` after extraction. The cascade
hook catches it. No additional wiring needed for channels — they go through the same changeset path
as crawl.

### 4.3 Agent/MCP integration (existing, wired)

All five `ask_graph_*` tools exist in `kompile-cli-main/.../chat/tools/grounding/`. They call
`/api/kb-grounding/*` on the kompile-app backend. `ask_graph_assert` publishes
`AgentFactAssertedEvent` via `KbGroundingService.assertFact` → `GroundingCascadeHook.onAgentFactAsserted`.

The ReAct agent (`ReActAgentAutoConfiguration.java`) and the KClaw task runner already have access to
the tool registry — adding `AskGraph*Tool` beans via the existing `ToolRegistry` registration pattern
is the CLI-side wiring.

### 4.4 Process engine integration (connected via L4)

`MiningProcessDiscoveryService` (in `kompile-process-discovery`) already runs process discovery from
the graph. The L4 `GroundedRuleDerivationService` and confidence propagation into
`ProcessSuggestion` are the integration points. After a cascade completes, the L4 service fires
and updates the `DomainObjectRegistry`. Process mining results then carry PSL-grounded confidence
scores, not just structural metrics.

**Wiring needed**: `GroundingCascadeHook` must publish `GroundingMaterializationCompleteEvent`
after `markEpoch`. `MiningProcessDiscoveryService` listens to this event and re-runs discovery
for the affected fact sheet. This is ~30 lines of new code.

### 4.5 Project store integration (existing Phase-1 portability + NEW inferred JSONL)

`KompileProjectStore.init` creates `data/graph/inferred/` (NEW — add to
`ensureStandardDirectories`). `ProjectGraphPortabilityService.onChangesetCompleted` already
re-exports the graph JSON; it should also flush the `FileBackedInferredFactStore` to the JSONL
files after the cascade completes (hook point: `GroundingMaterializationCompleteEvent`).

On `git clone + project open`: the graph rehydrates from `data/graph/factsheet-N.json` (existing);
the `FileBackedInferredFactStore` loads from `data/graph/inferred/factsheet-N-latest.jsonl` (NEW).
Agents can query the KB immediately without a re-crawl. This is the **travel-on-clone guarantee**
for grounding state.

### 4.6 Graph Snapshots (Phase-5) and SnapshotManager integration

`SnapshotManager` writes full graph dumps to `<id>.graph.json`. The inferred facts for the
snapshot epoch should be included: when `SnapshotManager.createSnapshot(factSheetId)` is called,
it should also snapshot `data/graph/inferred/factsheet-N-latest.jsonl` to
`data/graph/snapshots/<id>/inferred-facts.jsonl`. `restoreSnapshot` restores both. This is
~20 lines of new code in `SnapshotManager`.

---

## 5. The Missing Production Link: GraphToFactStoreProjector

This is the most important gap the cascade wiring does not yet close. The `IncrementalReasoningOrchestrator`
runs MAP inference over the `FactStore` — but the `FactStore` for a freshly crawled fact sheet
contains no facts unless an agent has called `ask_graph_assert`. The crawl writes into the
`KnowledgeGraphService` (graph nodes/edges), not the `FactStore`.

**Decision**: Introduce `GraphToFactStoreProjector` as a new `@Service` in `kompile-knowledge-graph`.

```java
@Service
public class GraphToFactStoreProjector {

    /**
     * Project all entities and relations in the fact sheet's graph into PSL atom form
     * and assert them into the FactStore via KbGroundingService.
     *
     * Called by GroundingCascadeHook (before the MAP solve) for FULL_FACTSHEET cascades.
     * For DELTA_ATOMS cascades (agent asserts), only the changed atoms are projected.
     */
    public int projectFactSheet(long factSheetId) {
        // 1. Collect all GraphNode records for the fact sheet via
        //    KnowledgeGraphService.getNodesByFactSheet(factSheetId)
        // 2. For each unary predicate (entity type → Atom):
        //    "EntityType(entityId)" → FactStore.assertFact(atom, value=1.0)
        // 3. For each GraphEdge (edge type → binary predicate):
        //    "EdgeType(sourceId, targetId)" → FactStore.assertFact(atom,
        //         value = edge.weight or 1.0 if no weight)
        // 4. Return count of atoms asserted
    }
}
```

The projection call must happen **before** the MAP solve inside
`IncrementalReasoningOrchestrator.runFullReground`. The orchestrator currently builds the PslProgram
from the existing `FactStore` contents (`IncrementalReasoningOrchestrator.java:133`). Insert
`graphToFactStoreProjector.projectFactSheet(factSheetId)` at the top of `doReground` before the
program build.

The PSL predicate name for a node entity type: `lowercase(entityType)`. For an edge:
`lowercase(edgeType)`. These must match the predicate names in the user-configured PSL rules.
A configuration bridge (`GraphPredicateNamingConfig`) with a default snake_case mapping covers
most cases; it can be overridden per-project.

---

## 6. End-to-End Worked Example: Crawl a Fact Sheet → Agent Verifies Against It

**Setup**: project `demo-project`, one fact sheet (id=42, name="AcmeCorp docs"), crawl target =
local docs folder with 10 PDFs. PSL rule:
`0.8: mentions(Doc, Org) ∧ isReport(Doc) → references(Org)`

**Step 1 — User triggers crawl**

```
POST /api/crawl/unified
{ "factSheetId": 42, "sources": [{"type":"FILE", "path":"/data/docs"}] }
```

**Step 2 — Crawl runs**

`UnifiedCrawlJob` (status RUNNING) is created.
`GraphExtractionOrchestrator` processes 10 documents:
- Extracts 150 entity nodes (COMPANY, DOCUMENT, PERSON) scoped to `factSheetId=42`
- Extracts 320 relation edges (MENTIONS, IS_REPORT_OF, WORKS_AT)
- Calls `graphService.createNode(6-arg, factSheetId=42)` × 150
- Calls `graphService.createEdge(..., factSheetId=42)` × 320

**Step 3 — Graph commit**

Job transitions to `COMPLETED`.
`GraphBuildCompletedEvent(factSheetId=42, entities=150, edges=320)` published.
`GraphBuildCompletedEventIntegration` converts to `GraphChangesetCompletedEvent` and fires hooks:
- `ProjectGraphPortabilityService.onChangesetCompleted` → writes `data/graph/factsheet-42.json` (357 nodes+edges JSON)
- `GroundingCascadeHook.onChangesetCompleted` → submits task to `grounding-cascade-42` executor

**Step 4 — Grounding cascade (async)**

`IncrementalReasoningOrchestrator.runFullReground(42)`:
1. `GraphToFactStoreProjector.projectFactSheet(42)` → 470 PSL atoms asserted (150 unary + 320 binary)
2. PslProgram built from FactStore: 470 observed atoms + user rule `0.8: mentions∧isReport→references`
3. `ScalarHlMrfInference.solve` runs in ~200ms (470 atoms, 1 rule, ~230 ground instances)
4. MAP result: `references(AcmeCorp) = 0.78`, `references(XyzLtd) = 0.61`, etc.
5. `EntailmentEngine.entailFromPslResult` → 40 `InferredFact` records (value > ACTIVATION_THRESHOLD=0.1)
6. `InferredFactStore[42].store(...)` × 40 (all new atoms, version=1)
7. `JustificationIndex.build(...)` → indexes `references(AcmeCorp)` as depending on
   `mentions(doc-7,AcmeCorp)` and `isReport(doc-7)` via the rule
8. `KbGroundingService.markEpoch(42, "run-abc", newIndex)` → epochMap[42]="run-abc"
9. `FileBackedInferredFactStore` flushes 40 facts to `data/graph/inferred/factsheet-42-latest.jsonl`
10. `GroundingMaterializationCompleteEvent(42, "run-abc")` published
    → `MiningProcessDiscoveryService` schedules re-discovery for factSheet 42 (L4)

**Step 5 — Agent queries the KB**

Agent calls MCP tool `ask_graph_verify`:

```json
{
  "tool": "ask_graph_verify",
  "input": {
    "atom": "references(AcmeCorp)",
    "factSheetId": 42,
    "minConfidence": 0.5
  }
}
```

Tool dispatches `POST http://localhost:8090/api/kb-grounding/verify`:

```
KbGroundingController.verify(factSheetId=42, atom="references(AcmeCorp)")
  → KbGroundingService.verify(42, "references(AcmeCorp)")
      → KbVerifier.verify("references(AcmeCorp)")
          → inferredFactStore[42].latest("references(AcmeCorp)")
          → InferredFact(value=0.78, confidence=0.78, version=1, runId="run-abc",
                         supportingFactKeys=["mentions(doc-7,AcmeCorp)","isReport(doc-7)"],
                         supportingRuleIds=["0.8: mentions(D,O)∧isReport(D)→references(O)"])
          → SUPPORTED (0.78 >= minConfidence=0.5)
  ← VerifyResult(SUPPORTED, 0.78, evidenceAtomKeys=[...], activatedRules=[...])

KbGroundingController returns:
{
  "verdict": "SUPPORTED",
  "confidence": 0.78,
  "evidenceAtoms": ["mentions(doc-7,AcmeCorp)", "isReport(doc-7)"],
  "activatedRules": ["0.8: mentions(D,O) ∧ isReport(D) → references(O)"],
  "derivationDepth": 1,
  "factSheetId": 42
}
```

**Step 6 — Agent asks for explanation**

```json
{ "tool": "ask_graph_explain",
  "input": { "atom": "references(AcmeCorp)", "factSheetId": 42, "depth": 2 } }
```

`DerivationTree.build("references(AcmeCorp)", inferredFactStore[42], justificationIndex[42])`:

```
references(AcmeCorp) [confidence=0.78, version=1]
  via rule: "0.8: mentions(D,O) ∧ isReport(D) → references(O)"
  ├── mentions(doc-7, AcmeCorp) [value=1.0, observed, crawl-run-X]
  └── isReport(doc-7) [value=1.0, observed, crawl-run-X]
```

Returned verbalized summary: "AcmeCorp is referenced (confidence 0.78) because document doc-7 mentions
AcmeCorp and is classified as a report (both facts observed from the crawl)."

Total latency from tool invocation to response: <10ms (O(1) lookup in InferredFactStore).

---

## 7. Phased Build Plan

### Phase P0 — File-backed durability + graph→FactStore projection (first thing to build)

**Why first**: Without these two pieces, every restart loses all derived facts and re-crawls are
needed. The cascade produces no output for new crawls because the FactStore is empty.

1. `FileBackedInferredFactStore` implementing `InferredFactStore` SPI
   (`kompile-knowledge-graph/.../grounding/`).
   - `store(fact)` → append to JSONL
   - `latest(atomKey)` → read from in-memory index (loaded on startup from JSONL)
   - Tests: store/reload round-trip, concurrent writes, version monotonicity
2. `GraphToFactStoreProjector` (`kompile-knowledge-graph/.../grounding/`).
   - `projectFactSheet(factSheetId)` → enumerate nodes+edges → assert atoms into `KbGroundingService`
   - Wire into `IncrementalReasoningOrchestrator.doReground` before program build
   - Tests: project a small graph, verify FactStore contains expected atoms
3. `KompileProjectStore.ensureStandardDirectories` — add `data/graph/inferred/` creation
4. `ProjectGraphPortabilityService` — flush JSONL on `GroundingMaterializationCompleteEvent`
   (requires publishing this new event from `KbGroundingService.markEpoch`)
5. On-demand re-ground endpoint: `POST /api/kb-grounding/reground?factSheetId=N`

**Milestone**: Crawl fact sheet → cascade → `InferredFact`s written to JSONL → restart → load JSONL
→ agent `ask_graph_verify` succeeds without re-crawl.

### Phase P1 — GroundingMaterializationCompleteEvent + L4 process-mining hook

6. `GroundingMaterializationCompleteEvent` Spring event (in `kompile-graph-change-tracking/event/`)
7. Wire `MiningProcessDiscoveryService` as `@EventListener` for this event
8. `SnapshotManager` snapshot+restore extended to include inferred JSONL

**Milestone**: After each cascade, process-mining `ProcessSuggestion` objects carry PSL-grounded
confidence scores. `ask_graph_query([("type","PROCESS")])` returns domain objects.

### Phase P2 — Delta-atoms cascade (incremental MAP solve)

9. Upgrade `IncrementalReasoningOrchestrator.runFullReground` to
   `runDelta(factSheetId, Set<String> changedAtomKeys)` for agent-assert events
   (the `DELTA_ATOMS` scope from the design spec §3.3)
10. `JustificationIndex.update(prev, changedAtomKeys, newResult)` — incremental form

**Milestone**: Agent-assert cascades complete in <100ms for typical fact sheets (vs. 200ms–2s for
full re-ground), enabling low-latency agent reasoning loops.

### Phase P3 — `graphGroundingStale` job field + UI indicator

11. Add `graphGroundingStale` boolean to `UnifiedCrawlJob.ProgressSnapshot`
    (`UnifiedCrawlJob.java:1089`)
12. Set true at job start, false when `epochMap` for the job's factSheetId is updated
    after the cascade
13. Surface in the crawl progress UI panel

**Milestone**: Users see "KB updating..." indicator after crawl completes, then "KB current" when
cascade finishes.

---

## 8. Open Questions for the User

**Q1 — PSL rule authoring surface**: The `GraphToFactStoreProjector` maps entity types to PSL
predicate names (e.g., `DOCUMENT` → `document`, `MENTIONS` → `mentions`). The PSL rules themselves
(e.g., `0.8: mentions(D,O) ∧ isReport(D) → references(O)`) must be authored somewhere. Where
does the user author and manage PSL rules in the production workflow: (a) via the existing
`GraphRuleConfig` CRUD UI (which currently supports LOG/WEBHOOK actions — extending to PSL rules
would mean a new rule type), (b) via a new `.psl` text file per project in `data/rules/`, or
(c) auto-derived from the ontology schema (the ontology binding already exists at Phase 6)?
This decision determines whether the production KB has any meaningful inference or remains trivial
(all atoms at value=1.0, no rules firing).

**Q2 — InferredFactStore flush timing**: The design specifies JSONL flush after each cascade
(on `GroundingMaterializationCompleteEvent`). For a crawl with 10 re-crawls per day on a 50k-atom
fact sheet, that is 10 full JSONL rewrites per day (~50MB each). Is this acceptable, or should the
flush be deferred to `project commit` only (reducing IO, but losing durability between commits)?

**Q3 — factSheetId in PSL program scope**: The PSL program for a fact sheet currently uses bare
entity IDs as atom arguments (e.g., `mentions(doc-7, AcmeCorp)`). If two fact sheets contain a
node with the same external ID (e.g., two crawls of the same document produce the same `doc-7`
external ID in different fact sheets), the PSL atom namespace is per-factSheetId. Is it acceptable
for atom keys to use scoped form `mentions(doc-7@42, AcmeCorp@42)` (where `@42` is the
factSheetId suffix), or should entity IDs be globally unique by construction (enforced at crawl
time via UUID node IDs, which the current `createNode` already uses)?

**Q4 — Contradiction resolution UX**: STEP 7 of the cascade publishes `ContradictionDetectedEvent`
but does not halt the cascade. Contradictions accumulate silently. For production use, the user
needs a surface to review and resolve contradictions (e.g., an agent asserts `references(AcmeCorp)=0.0`
while the crawl has `references(AcmeCorp)=0.78`). Is the Enforcer tab (existing, judges-based)
the right place for contradiction review, or should the Graphs Hub get a dedicated Contradictions
panel?

**Q5 — Multi-project grounding isolation**: Does the grounding KB need to be
queryable cross-project (e.g., a "global KB" that aggregates across all projects)? The current
design is strictly per-project (scoped by `factSheetId` within one running kompile-app instance).
Cross-project aggregation would require a federated query layer on top of the per-project
`KbGroundingService` instances.
