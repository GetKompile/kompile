# Reasoning Trail Explainability Design

**Status:** DESIGN — 2026-06-21  
**Scope:** Cross-cutting WHY/HOW explainability for all facts, inferences, and ranked answers in kompile  
**Complements:** `grounding-api-contract-design.md` (per-primitive REST surface), `grounding-evaluation-design.md`

---

## 1. Problem Statement

Kompile produces facts, rankings, causal chains, and Bayesian posteriors from several reasoning engines (PSL/HL-MRF, MEBN/variable-elimination, hybrid structural+semantic, FOL grounding). Users currently see answers but not the derivation behind them. One buried path exists today — the "Explain Why?" button at `graph-visualizer.component.ts:275` calls `loadAttribution()` at line 2855 which hits `attributionService.explainQuick()` for causal chains — but this is inline, single-purpose, and does not draw on the grounding proof tree or the hybrid scorer's structural/semantic breakdown.

The goal is a **unified `ReasoningTrail`**: a single model that any reasoning mode can populate, a single generalized endpoint that any caller can invoke, and a single reusable Angular component that any panel can embed. Every fact, inference, node ranking, and chat answer becomes self-explaining without spawning a separate UI surface.

---

## 2. Existing Building Blocks (with locations)

| Primitive | File | Key fields |
|-----------|------|------------|
| `DerivationTree` | `kompile-graph-reasoning/src/…/fol/grounding/DerivationTree.java:57` | `atomKey`, `confidence`, `ruleApplied`, `sourceProvenance`, `children: List<DerivationTree>` |
| `KbVerifier` / `VerifyResult` | `…/fol/grounding/KbVerifier.java:37`, `VerifyResult.java:43` | `status: SUPPORTED/REFUTED/UNKNOWN`, `confidence`, `evidence: List<String>` |
| `EntailmentRecord` | `…/fol/EntailmentRecord.java:29` | `groundedRvOrAtomKey`, `posterior`, `supportingFindingKeys`, `activatedRules` |
| `EntailmentEngine` | `…/fol/EntailmentEngine.java:55` | `entailFromMebn(…)`, `entailFromPsl(…)` |
| `HybridReasoner` / `ScoredEntity` | `…/hybrid/HybridReasoner.java:54,78` | `score`, `structuralScore`, `semanticScore`; blending at line 108 |
| `ExplanationService` SPI | `…/explain/ExplanationService.java:31` | `explain(ReasoningGraph, targetEntityId, question): Explanation` |
| `Explanation` | `…/explain/Explanation.java:32` | `summary`, `confidence`, `supportingEntityIds` |
| `KbGroundingController /explain` | `…/web/controllers/grounding/KbGroundingController.java:170` | returns `DerivationTree` + `VerifyResult` verdict; `deterministicSummary()` at line 325 |
| Existing "Explain Why?" button | `graph-visualizer.component.ts:275` | calls `loadAttribution()` → causal chain only |
| Causal attribution panel | `causal-attribution-panel.component.ts:249` | 4-step wizard wrapping `<app-bayesian-panel>` |
| Attribution extras | `causal-attribution-extras.component.ts:403` | surfaces sensitivity/MEBN/predict/What-If/MPE |

---

## 3. Unified `ReasoningTrail` Model

### 3.1 Lib-level record (kompile-graph-reasoning)

Location decision: add to `kompile-graph-reasoning/src/…/explain/` alongside the existing SPI.

```
ai.kompile.graph.reasoning.explain.ReasoningTrail  (new record)
```

```java
public record ReasoningTrail(
    // WHAT was explained
    String targetId,            // atomKey, entityId, or answer token
    String question,            // optional — the query that triggered this

    // VERDICT
    double confidence,          // [0,1] unified across all modes
    ConfidenceBreakdown breakdown,  // see 3.2

    // PROOF
    DerivationTree derivationTree,  // nullable — present when grounding path ran
    List<EntailmentRecord> entailments, // nullable — present for PSL/MEBN paths
    List<String> evidence,          // human-readable evidence strings (from VerifyResult)
    List<String> activatedRules,    // rule names / PSL rule bodies that fired

    // PROVENANCE
    String inferenceMode,       // "GROUNDING" | "PSL" | "MEBN" | "HYBRID" | "CAUSAL" | "EMBEDDING"
    String runId,               // EntailmentRecord.inferenceRunId or grounding sessionId
    Instant computedAt,

    // NATURAL LANGUAGE
    String naturalLanguageSummary   // from deterministicSummary() or LLM fallback
) {}
```

### 3.2 ConfidenceBreakdown

```java
public record ConfidenceBreakdown(
    // Grounding / FOL
    double groundingConfidence,   // DerivationTree root confidence

    // PSL soft-truth
    double pslSoftTruth,          // HlMrfMapInference MAP value, or NaN

    // MEBN posterior
    double mebnPosterior,         // VariableElimination posterior, or NaN

    // Hybrid split (HybridReasoner.ScoredEntity)
    double structuralScore,       // ScoredEntity.structuralScore (PSL or Bayesian)
    double semanticScore,         // ScoredEntity.semanticScore (embedding cosine)
    double structuralWeight,      // HybridReasoner.structuralWeight
    double semanticWeight,        // HybridReasoner.semanticWeight

    // Distance-to-satisfaction (PSL only, for rules without full MAP solve)
    double distanceToSatisfaction // EntailmentEngine.ACTIVATION_THRESHOLD = 0.1 at line 58
) {}
```

All fields default to `Double.NaN` when the mode did not produce them; the frontend renders them only when non-NaN.

---

## 4. Backend: Generalized Explain Endpoint

### 4.1 Decision: extend `/api/kb-grounding/explain`, do not fork

`KbGroundingController.explain` at line 170 already returns a `DerivationTree` plus a `VerifyResult` verdict. The request adds two optional fields to cover the hybrid and attribution paths. This keeps one URL, one controller, one response shape.

**New endpoint:** `POST /api/explain` (new controller `ExplainController` in `ai.kompile.app.web.controllers.explain` — separate package so `GlobalExceptionHandler.basePackages` covers it without editing the existing grounding controller).

```
POST /api/explain
Request:
{
  "targetId": "isActive(alice)",      // atom key OR entity id OR "chat:<sessionId>:<msgIdx>"
  "question":  "Why is alice active?", // optional
  "mode":      "AUTO",                 // AUTO | GROUNDING | PSL | MEBN | HYBRID | CAUSAL | EMBEDDING
  "factSheetId": "...",
  "maxDepth":  5,
  "includeNl": true                   // whether to call NL summary (may be LLM-backed)
}

Response:
{
  "trail": { /* ReasoningTrail JSON */ },
  "meta": { "factSheetId": "...", "kbVersion": "...", "computedAt": "..." }
}
```

**AUTO mode routing logic** (implemented in new `ExplainOrchestrator` Spring service):

```
if targetId looks like an atom key (contains "(") → GROUNDING first; 
  if InferredFactStore has no record → PSL or MEBN fallback from EntailmentEngine
if targetId is a graph entity id → HYBRID (HybridReasoner.rank + single-entity lookup)
if targetId has prefix "causal:" → CAUSAL (existing attributionService.explainQuick)
if targetId has prefix "chat:" → embed question → HYBRID + NL summary
```

### 4.2 Mode → trail mapping (what to put in the trail when DerivationTree is absent)

| Mode | `derivationTree` | `entailments` | `evidence` | `activatedRules` | `breakdown` fields populated |
|------|-----------------|---------------|------------|------------------|------------------------------|
| **GROUNDING** | Full `DerivationTree` from `DerivationTree.build()` | — | `VerifyResult.evidence` | atoms with `:-` from `KbGroundingController:76` split | `groundingConfidence` |
| **PSL** | null | `EntailmentRecord` list from `entailFromPslResult` | `supportingFindingKeys` | `activatedRules` | `pslSoftTruth`, `distanceToSatisfaction` |
| **MEBN** | null | `EntailmentRecord` list from `entailFromMebn` | `supportingFindingKeys` | edge provenance strings from `mebnEdgeProvenance` at line 148 | `mebnPosterior` |
| **HYBRID** | null if no grounding; else reuse | — | nearest-neighbor entity ids | PSL/Bayesian rule names used in `pslActivations` / `bayesianPosteriors` | `structuralScore`, `semanticScore`, `structuralWeight`, `semanticWeight` |
| **CAUSAL** | null | — | chain hops as evidence strings | causal rule types | `groundingConfidence` ← causal chain strength |
| **EMBEDDING** | null | — | top-K nearest neighbor entity ids + cosine similarities | — | `semanticScore` |

PSL "distance-to-satisfaction" is the primary explanation when no full `DerivationTree` exists: for each activated ground rule, `1 - satisfactionValue` (a value of 0 means the rule is fully satisfied; higher means the atom is being pushed toward truth by unsatisfied rules). This is a direct WHY in the PSL sense.

MEBN explanation is the CPT/SSBN path: `mebnEdgeProvenance` at `EntailmentEngine.java:148` already extracts `"<strength>: parent->child"` strings from the MTheory edges. These strings become `activatedRules` in the trail.

Embedding explanation is the top-K nearest neighbors from the query embedding: `HybridReasoner.rank(graph, queryEmbedding)` produces `ScoredEntity.semanticScore` per entity. The top-K entity ids and scores become the embedding sub-trail.

### 4.3 Natural language summary

`deterministicSummary()` at `KbGroundingController.java:325` already produces a template-rendered NL string. The `ExplainOrchestrator` calls this first (no LLM cost). If `includeNl=true` AND an `ExplanationService` bean is present (the `AttributionLlmService` impl in `kompile-event-attribution` registers one), the orchestrator augments the deterministic string with the LLM `Explanation.summary`. The `ReasoningTrail.naturalLanguageSummary` field carries whichever path ran.

---

## 5. Frontend: Reusable `reasoning-trail` Component

### 5.1 Location and generation

```
kompile-app-main/src/main/frontend/src/app/components/reasoning-trail/
  reasoning-trail.component.ts
  reasoning-trail.component.html
  reasoning-trail.component.css
```

### 5.2 Interface

```typescript
// reasoning-trail.component.ts
@Component({ selector: 'app-reasoning-trail', standalone: true, ... })
export class ReasoningTrailComponent {
  @Input() trail: ReasoningTrail | null = null;
  @Input() loading = false;
  @Input() error: string | null = null;
  @Input() compact = false;   // condensed 1-line chip for embedding in tables/chat
  @Output() close = new EventEmitter<void>();
}

export interface ReasoningTrail {
  targetId: string;
  question?: string;
  confidence: number;
  breakdown: ConfidenceBreakdown;
  derivationTree?: DerivationTreeNode;
  entailments?: EntailmentRecord[];
  evidence: string[];
  activatedRules: string[];
  inferenceMode: string;
  runId?: string;
  computedAt: string;
  naturalLanguageSummary?: string;
}
```

### 5.3 Visual layout (expandable sections)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  [MODE badge]  "isActive(alice)"  ●●●●○  0.82  [collapse ▲]           │
├─────────────────────────────────────────────────────────────────────────┤
│  NL Summary: "alice is active because rule R3 (conf 0.9) ..."          │
├────────────────────┬────────────────────────────────────────────────────┤
│  Confidence split  │  Proof tree                                        │
│  ─────────────     │  ─────────                                         │
│  Structural 0.86   │  ▼ isActive(alice)  [0.82]  ← R3                 │
│  ████████░  60%    │    ▼ employedAt(alice,acme)  [0.95]  ← F12       │
│  Semantic   0.75   │      • acme.type = EMPLOYER  (hard fact)          │
│  ███████░░  40%    │    ▼ tenureYears(alice) > 2  [0.88]  ← F7        │
│  PSL truth  0.82   │      • hire_date = 2021-03  (provenance)         │
│  MEBN post  NaN    │                                                    │
├────────────────────┴────────────────────────────────────────────────────┤
│  Evidence (3)  [chips]:  "R3: employedAt(X,Y) ∧ ... → isActive(X)"   │
│                          "F12 (grounded 2025-09-01)"                   │
│                          "F7 (source: hr-feed)"                        │
└─────────────────────────────────────────────────────────────────────────┘
```

The proof tree is rendered recursively: each `DerivationTreeNode` is a mat-tree or a recursive Angular template (not a third-party tree library — D3 is already loaded but the tree is text-structure, not graph-structure). Leaf nodes show `sourceProvenance`. When `derivationTree` is null, the proof tree section renders the `entailments` list as a flat table (rule → posterior → supporting findings).

The confidence split bar is always shown (even when only one mode ran — the bar just shows one segment). This ensures the component is visually consistent regardless of mode.

`compact=true` collapses everything to: `[MODE] 0.82 ●●●●○ "alice is active because..." [▶ Why?]` — a single line suitable for table cells and chat bubbles.

### 5.4 ReasoningTrailService

```
components/reasoning-trail/reasoning-trail.service.ts
```

```typescript
@Injectable({ providedIn: 'root' })
export class ReasoningTrailService {
  explain(targetId: string, opts?: ExplainOptions): Observable<ReasoningTrail>
  // POST /api/explain — all modes, AUTO by default
}
```

---

## 6. Pervasive Embedding Points

### 6.1 Graph Visualizer / Graph Canvas — node-level "Why?"

**File:** `graph-visualizer.component.ts`

Current: "Explain Why?" button at line 275 is Causal-only (`loadAttribution()` at line 2855).

Change: replace `loadAttribution()` with `reasoningTrailService.explain(selectedNode.id)`. Store result in `trailResult: ReasoningTrail | null`. Replace the inline attribution chain rendering (template lines 297–400) with:

```html
<app-reasoning-trail [trail]="trailResult" [loading]="trailLoading" />
```

The causal chain result (`attributionResult`) becomes one mode-specific trail: the existing `attributionService.explainQuick()` call moves into `ExplainOrchestrator` under `mode=CAUSAL`. The `mergeAttributionOverlay()` behavior (line 2905) stays: when the trail's `inferenceMode` is `CAUSAL`, the visualizer reads `trail.evidence` (chain hop node ids) to drive the heat overlay. No D3 behavior changes.

**Graph canvas right-click context menu** (add): `nodeContextMenu` output at line 297 already fires on right-click. Add a "Why?" menu item that calls `reasoningTrailService.explain(nodeId)` and emits the trail up to the visualizer parent for display.

### 6.2 Bayesian Panel — per-posterior "Why?"

**File:** `bayesian-panel.component.ts`

The posterior entries table (`entries[]` populated at line 1162) currently shows `variable`, `posterior`, `prior`, `sensitivity`. Add a `[▶ Why?]` chip per row (compact trail). Clicking expands an inline `<app-reasoning-trail compact=false>` below the row.

The `whatIfResult` and `mpeResult` sections (templates lines 226–410) each get a trail: after `runWhatIf()` and `runMpe()` complete, call `reasoningTrailService.explain(nodeId, { mode: 'MEBN', question: 'What-If / MPE' })` to populate a trail panel beneath each result. This is additive — the existing What-If / MPE result tables stay.

### 6.3 Causal Attribution Panel + Extras — migration

**File:** `causal-attribution-panel.component.ts:249`, `causal-attribution-extras.component.ts:403`

The panel's `run()` at line 271 drives the attribution wizard. After the wizard reaches the results step, render `<app-reasoning-trail>` beneath the existing `<app-bayesian-panel>`. The trail here uses `mode=CAUSAL` and shows the causal chain hops as a proof tree (hop title = rule, strength = confidence, causal type = rule applied).

In `causal-attribution-extras`, each of the five action buttons (sensitivity, MEBN structure, predict, what-if, MPE at lines 438–547) gets a trail after its result. Sensitivity becomes `mode=MEBN` (the shift values are the breakdown). Prediction becomes `mode=HYBRID`. This is the **migration path**: these five panels become callers of the unified component rather than rendering inline tables.

### 6.4 Grounding verify / query results

**File:** new `KbGroundingPanelComponent` (already planned in `grounding-ui-integration-design.md` scope — this design hands off the trail rendering):

After `POST /api/kb-grounding/verify` or `/query` resolves, pass the `atomKey` and `factSheetId` to `reasoningTrailService.explain(atomKey, { mode: 'GROUNDING', factSheetId })`. Show the trail beneath the verify verdict chip. This is straightforward because `KbGroundingController.explain` at line 170 already produces the exact inputs `ExplainOrchestrator` needs.

### 6.5 Chat answers

The chat reply component (wherever chat answer bubbles are rendered) adds a `compact=true` trail chip beneath each answer: `[HYBRID] 0.79 "Because..." [▶ Why?]`. The chat endpoint passes the answer's grounded entity ids (from graph RAG retrieval) as `targetId=chat:<sessionId>:<msgIdx>`. `ExplainOrchestrator` resolves those to the top-ranked entity's trail via `HybridReasoner.rank`. This is best-effort — if the chat didn't go through the graph RAG path, the trail chip is omitted.

---

## 7. Reasoning Mode → Trail Completeness Table

| Mode | DerivationTree | ConfidenceBreakdown | NL Summary | Proof tree rendered as |
|------|---------------|--------------------|-----------|-----------------------|
| GROUNDING | Full recursive tree | `groundingConfidence` | `deterministicSummary()` (always; LLM optional) | Recursive tree widget |
| PSL | Null | `pslSoftTruth` + `distanceToSatisfaction` per rule | "Rule R was satisfied with soft-truth 0.82" template | Flat rule table (rule body → truth) |
| MEBN | Null | `mebnPosterior` | "Variable V has posterior 0.75 given parents P1, P2" template | SSBN path table (edge strength strings from `mebnEdgeProvenance`) |
| HYBRID | Optional (if GROUNDING ran first) | All four scores + weights | "Structural score 0.86 (PSL) + semantic score 0.75 (embedding cosine) → 0.82" | Split bar + nearest-neighbor chips |
| CAUSAL | Null | `groundingConfidence` ← chain strength | Existing `attributionResult.chains[0].narrative` | Chain hop tree (hop → strength → effect) |
| EMBEDDING | Null | `semanticScore` per neighbor | "Most similar to E1 (0.91), E2 (0.87)" template | K-nearest-neighbor list |

When mode is PSL and `distanceToSatisfaction` > 0 for a rule: the rule was not yet satisfied — the trail explicitly says this ("Rule R3 is pulling `isActive(alice)` toward TRUE; current distance: 0.18"). This is the direct PSL WHY.

---

## 8. Phased Plan

### Phase 1 — Unified model + generalized endpoint (backend only, no new UI)

**Scope:** lib-level `ReasoningTrail` record + `ConfidenceBreakdown`; `ExplainOrchestrator` Spring service; `POST /api/explain` in new `ExplainController`; AUTO-mode routing for GROUNDING and HYBRID.

**What ships:**
- `ReasoningTrail.java` and `ConfidenceBreakdown.java` in `kompile-graph-reasoning/src/…/explain/`
- `ExplainOrchestrator.java` in `kompile-app-core` (peers with `KbGroundingService`)
- `ExplainController.java` in `kompile-app-main/.../controllers/explain/` — add package to `GlobalExceptionHandler.basePackages`
- PSL and MEBN modes wire `EntailmentEngine.entailFromPsl` / `entailFromMebn` results into `ReasoningTrail.entailments`
- HYBRID mode calls `HybridReasoner.rank(graph, queryEmbedding)`, picks the top-matching entity, reads `ScoredEntity.structuralScore` / `semanticScore` into `ConfidenceBreakdown`
- `deterministicSummary()` from `KbGroundingController:325` moves to a shared `TrailNarrativeService` in app-core (so it is reusable across all modes without being locked in the controller)

**Does not require:** any Angular changes; any LLM calls; breaking changes to existing endpoints.

**Test:** unit tests for `ExplainOrchestrator` per mode; integration test: `POST /api/explain` with a seeded `FactStore` returns a `ReasoningTrail` with populated `derivationTree` (GROUNDING mode) and non-NaN `structuralScore`+`semanticScore` (HYBRID mode).

### Phase 2 — Reusable Angular component + graph-visualizer migration

**Scope:** `ReasoningTrailComponent` (standalone, imports MatExpansionModule, MatChipsModule, MatProgressBarModule, CdkTree or recursive template); `ReasoningTrailService`; replace inline attribution rendering in `graph-visualizer.component.ts:297–400` with `<app-reasoning-trail>`.

**What ships:**
- `reasoning-trail/` directory under `components/`
- `ReasoningTrailComponent` with full + compact modes; recursive proof tree template
- `graph-visualizer`: `loadAttribution()` at line 2855 → `loadTrail()` calling `reasoningTrailService.explain()`; `mergeAttributionOverlay` remains (reads `trail.evidence` for CAUSAL mode)
- `graph-canvas` right-click "Why?" menu item emitting `nodeContextMenu` → parent handles with `loadTrail()`

**Angular budget note:** component budget was raised to 25MB at `angular.json` in commit bumping from 23→25MB. This component is small (no new heavy deps); the D3 tree is not used — the proof tree is a recursive `*ngFor` template.

### Phase 3 — Pervasive embedding (bayesian-panel, causal-attribution, grounding UI, chat)

**Scope:** embed `<app-reasoning-trail>` in all remaining panels listed in section 6. Migrate `causal-attribution-extras` five action panels to trail callers. Add PSL and MEBN mode support to `ExplainOrchestrator`. Add chat answer trail chips.

**What ships:**
- `bayesian-panel`: per-row compact trail chip; What-If + MPE trail panels
- `causal-attribution-panel` + `causal-attribution-extras`: migration to unified component
- `KbGroundingPanel` (Phase 1 of `grounding-ui-integration-design.md`): trail rendered after verify/query
- Chat answer bubble: compact trail chip (best-effort, HYBRID mode)
- PSL mode: `distanceToSatisfaction` per rule in `ConfidenceBreakdown`; NL template for unsatisfied rules
- MEBN mode: `mebnEdgeProvenance` strings as proof tree leaves

---

## 9. What the Existing "Explain Why?" Becomes

`graph-visualizer.component.ts:275` — the "Explain Why?" button stays in place visually. Its `loadAttribution()` handler is replaced by `loadTrail()` which calls `POST /api/explain` with `mode=AUTO`. For a graph entity, AUTO routes to HYBRID first; if the entity has causal attribution data, the trail's `inferenceMode` will be `CAUSAL` and the chain hop tree renders exactly as the current inline template does (just inside `<app-reasoning-trail>`). The button label stays "Explain Why?" — only the data path changes.

`causal-attribution-panel.component.ts` wizard stays. The results step gains `<app-reasoning-trail>` beneath the existing `<app-bayesian-panel>`. The panel is not removed or restructured — trail rendering is additive.

`causal-attribution-extras.component.ts` five action methods stay. Each gets a `trail: ReasoningTrail | null` field populated after the action resolves, displayed via `<app-reasoning-trail compact=false>` below the existing result table.

---

## 10. Relationship to `grounding-api-contract-design.md`

`grounding-api-contract-design.md` owns the per-primitive REST contracts (`/api/kb-grounding/*`), the `FactSheetKbState` lifecycle, OCC versioning, and the subscribe SSE stub. This document does **not** replace those.

`POST /api/explain` is a **higher-level aggregation endpoint** that internally calls `KbGroundingService.explain()` (for GROUNDING mode), `EntailmentEngine` (for PSL/MEBN), and `HybridReasoner` (for HYBRID). It speaks `ReasoningTrail`, not the per-primitive DTOs. The per-primitive endpoints remain callable directly for MCP tool use and agent tasks. The UI only needs to call one endpoint to get a fully populated trail, regardless of which reasoning engine ran.

---

## 11. Open Questions

1. **InferredFactStore durability in `ReasoningTrail`:** `DerivationTree.build()` reads `InferredFactStore` which is currently in-process only (see `grounding-api-contract-design.md` Q1). For GROUNDING-mode trails to survive server restarts (e.g. for chat trail chips referencing old answers), `InferredFactStore` needs at minimum a session-scoped write-through to the JSONL durable store at `~/.kompile/sessions/<id>/`. Decision needed before Phase 3 chat integration.

2. **Embedding vector availability at explain time:** HYBRID mode needs the query embedding to populate `semanticScore`. For node-click "Why?" there is no query — only the node's own embedding vector. The natural default is to use the node's stored embedding as the "query" and find its nearest neighbors in the graph, producing a self-similarity score. This is semantically odd ("why is node X ranked high when querying for… itself?"). A better framing: the semantic sub-trail shows which OTHER nodes the selected node is most similar to and why. Decision needed before Phase 2 ships.

3. **PSL rule body exposure:** `HlMrfMapInference` returns soft-truth per grounded atom, not a per-rule satisfaction table. `distanceToSatisfaction` requires iterating the program's ground rules and computing `1 - satisfactionValue` per rule body. `GraphPslProgramBuilder` builds the program, but the ground rules are not currently stored as a separate index accessible after solve. Either `HlMrfMapInference.Result` needs a `groundRuleResults()` accessor, or the `ExplainOrchestrator` must re-derive them from the `PslProgram` post-solve. This is the main missing piece for PSL trail completeness.

4. **`ExplainOrchestrator` module placement:** placed in `kompile-app-core` here to avoid app-main coupling. But `HybridReasoner` is in `kompile-graph-reasoning`, and `EntailmentEngine` is also there. `ExplainOrchestrator` would need both as dependencies. App-core already depends on graph-reasoning (via `KnowledgeGraphReasoningAdapter`). Confirm the dependency edge does not create a cycle before Phase 1 implementation.

5. **NL summary LLM gate:** if `includeNl=true` and `ExplanationService` is present, the explain endpoint makes a synchronous LLM call. This could be slow for pervasive UI embedding (every node click, every posterior row). Consider making NL always async: return the deterministic summary immediately, add `GET /api/explain/{trailId}/nl` as a follow-up call the UI makes after rendering the structural trail. Avoids blocking the UI on LLM latency.
