# Graph UI — KB Reasoning Cockpit Design

**Date:** 2026-06-21  
**Status:** Design doc — supersedes `grounding-ui-integration-design.md`, `reasoning-trail-explainability-design.md` UI sections, and domain-object-grounding UI sections where they overlap  
**Scope:** Consolidated Tier-2 graph UI: `GraphVisualizerComponent` + `GraphsHub` as a fully-interactive knowledge-base-reasoning surface

---

## Part 1 — JOB 1: Existing UI Inventory

### 1.1 GraphVisualizerComponent
**File:** `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/src/app/components/graph-visualizer/graph-visualizer.component.ts`  
**Size:** 3002 lines (inline template + styles, no separate HTML file)

#### Inputs / Outputs
- `@Input factSheetId: number | null` (line 1859)
- `@Input factSheetName: string` (line 1860)
- No `@Output`s — communicates up only through `MatSnackBar` and the shared canvas child

#### Active side-panel tabs (template)
`Details` | `Relations` | `Filter` | `Weights` | `Layout` | `Attribution`

The **Attribution** tab (lines 2855–2980) contains the existing "Explain Why?" flow:
- `loadAttribution()` at line 2855 calls `GET /attribution/explain-quick?nodeId=…`
- `loadPrediction()` at line 2943 calls `GET /attribution/predict-quick?nodeId=…`
- These are the ONLY grounding-adjacent interactions in the visualizer today

#### Node/edge coloring (line 2698–2712)
`getNodeColor()` reads from a static `nodeColors` record keyed by `allNodeTypes` (8 types: CONCEPT, ENTITY, DOCUMENT, EVENT, RELATIONSHIP, TABLE, ATTACHMENT, IDENTIFIER). No strength-band or provenance coloring exists today.

#### Filters (lines 2427–2468)
- Node-type filter toggle: `toggleNodeTypeFilter()`
- Edge-type filter toggle: `toggleEdgeTypeFilter()`
- Depth / maxNodes sliders
- Temporal filter (valid-time `timeFrom`/`timeTo`, lines 2474–2647)
- No creation-time, provenance, or strength-band filters exist today

#### HTTP endpoints called by the visualizer
```
GET  /knowledge-graph/visualization
GET  /knowledge-graph/temporal/bounds
GET  /fact-sheets/{id}/graph/visualization
POST /fact-sheets/{id}/graph/build
GET  /fact-sheets/{id}/graph/build/status/{jobId}
POST /fact-sheets/{id}/graph/build/cancel/{jobId}
GET  /fact-sheets/{id}/graph/statistics
DELETE /fact-sheets/{id}/graph
POST /fact-sheets/{id}/graph/sources/link
POST /fact-sheets/{id}/graph/concepts/rebuild-edges
GET  /knowledge-graph/nodes/{id}/connected
DELETE /knowledge-graph/nodes/{id}
GET  /knowledge-graph/edges
POST /knowledge-graph/edges
DELETE /knowledge-graph/edges/{id}
GET  /attribution/explain-quick            ← only existing "reasoning" call
GET  /attribution/predict-quick
```

#### Orphaned / stub code in the visualizer
| Symbol | Location | Status |
|--------|----------|--------|
| `onNodeSelectedForRelation()` | line 2990 | Dead duplicate of `onNodeSelected()`; not bound in template |
| `onNodeContextMenu()` | line 2350 | Logs only — no handler |
| Spec assertion `allNodeTypes.length === 7` | spec line 311 | Stale — component has 8 types |
| `// HELPER METHODS` block | `graph.service.ts` line 361 | Empty placeholder |

---

### 1.2 GraphCanvasComponent
**File:** `…/graph-visualizer/graph-canvas.component.ts` (889 lines, inline template)

D3 SVG renderer. Emits:
- `@Output nodeSelected`, `nodeDoubleClicked`, `edgeCreated`, `nodeContextMenu`, `linkSourceChanged`

Accepts overlays: `posteriorOverlay`, `priorOverlay`, `mebnMfragMap`, `influenceOverlayActive`. These are the existing hook points for strength/provenance coloring overlays.

**Extension seam:** The `posteriorOverlay` input (a `Record<string, number>`) can be repurposed or paralleled with a `strengthOverlay: Record<string, StrengthBand>` input to drive new node coloring without touching D3 internals.

---

### 1.3 BayesianPanelComponent
**File:** `…/graph-visualizer/bayesian-panel.component.ts` (1219 lines)

Standalone expansion-panel component with sections: MEBN Structure, Posteriors, Inference Trace, What-If Analysis, MPE.

Calls:
```
GET  /attribution/bayesian/query-from-node
GET  /attribution/bayesian/mebn-query-from-node
GET  /attribution/bayesian/quick-sensitivity
GET  /attribution/bayesian/network-stats
GET  /attribution/bayesian/mebn-network-stats
POST /attribution/bayesian/what-if
POST /attribution/bayesian/mpe
```

This panel is **already wired** into the visualizer's Attribution tab. All Bayesian What-If and MPE interactions live here. The new cockpit preserves this and extends with a side-by-side `<app-reasoning-trail>` block.

---

### 1.4 SourceLinkingPanelComponent
**File:** `…/graph-visualizer/source-linking-panel.component.ts` (510 lines)

**Status: fully orphaned.** Selector `app-source-linking-panel` is absent from both `graph-visualizer.component.ts` and `graphs-hub.component.html`. Has its own 757-line spec. Should be wired into a new "Source Links" sub-tab of the visualizer side panel.

---

### 1.5 GraphsHubComponent
**File:** `…/graphs-hub/graphs-hub.component.ts` (147 lines)  
**Template:** `…/graphs-hub/graphs-hub.component.html` (154 lines)

#### 18 existing tabs
| Tab key | Component | Notes |
|---------|-----------|-------|
| `visualizer` | `<app-graph-visualizer>` | Main cockpit — extended by this design |
| `entityBrowser` | `<app-entity-browser>` | Active |
| `hierarchy` | `<app-graph-hierarchy>` | Active |
| `builder` | `<app-knowledge-graph-builder>` | Active |
| `eventObservation` | `<app-event-observation-dashboard>` | Active |
| `causalAttribution` | `<app-causal-attribution-panel>` | Active — seeded by `attributionSeedNodeId` |
| `overview` | `<app-graph-overview>` | Phase 8 |
| `health` | `<app-graph-health-panel>` | Phase 7 — `GraphHealthSnapshot` served at `/api/graph-health` |
| `ontology` | `<app-graph-ontology-panel>` | Phase 6 |
| `pipelines` | `<app-graph-pipelines-panel>` | Phase 2 |
| `provenance` | `<app-graph-provenance-panel>` | Phase 3 |
| `diff` | `<app-graph-diff-panel>` | Phase 5 |
| `maintenance` | `<app-graph-maintenance-hub>` | Phase 5 |
| `rules` | `<app-graph-rules-panel>` | Phase 4 |
| `extract` | `<app-multi-agent-graph-extraction>` | Phase 8 |
| `patch` | `<app-graph-data-patch>` | Phase 8 |
| `eval` | `<app-graph-eval-debugger>` | Phase 8 |
| `io` | `<app-graph-io-panel>` | Phase 9 |

#### Gaps identified
1. `focusNodeId` received by `GraphsHubComponent` (line 89) correctly switches to the `visualizer` tab in `ngOnChanges` (line 114) but is **never passed down** to `<app-graph-visualizer>` in the HTML — the inner component cannot focus the specific node.
2. `onEntitySelected()` (line 129) is a `console.log` stub — no real navigation.
3. No tab for: Grounding Console, Reasoning Trail browser, Processes/BPMN, Audit/Correction, Hydration Progress, Creation-Time Scoping, Tables.

---

### 1.6 GraphService
**File:** `…/services/graph.service.ts` (664 lines)

Covers all node/edge CRUD, visualization, fact-sheet graph ops, named-graph ops, hierarchy, and metadata patch. Does **not** cover:
- `/api/kb-grounding/*` — needs a new `KbGroundingService`
- `/api/process/mining/*` — needs a new `ProcessMiningService` (or extend existing)
- `/api/explain` (unified reasoning trail) — needs `ExplainService`
- `/api/graph-health` — needs `GraphHealthService` (may already exist per Phase 7)

---

### 1.7 New Backend Capabilities — Build Status

| Capability | Key class/endpoint | Build state |
|------------|-------------------|-------------|
| KB verify | `POST /api/kb-grounding/verify` → `VerifyResponse` | BUILT |
| KB query | `POST /api/kb-grounding/query` → `QueryResponse` | BUILT |
| KB explain | `POST /api/kb-grounding/explain` → `ExplainResponse` + `DerivationTree` | BUILT |
| KB assert | `POST /api/kb-grounding/assert` → `AssertResponse` | BUILT |
| Opinion/StrengthBand | `Opinion.java`, `StrengthBand.java` in lib | BUILT (lib only; not yet on REST) |
| ReasoningTrail record | `ReasoningTrail.java` in lib | BUILT (lib only; not yet on REST) |
| Process discovery | `GET /api/process/mining/discover` | BUILT |
| BPMN export | `GET /api/process/mining/bpmn` (→ `application/xml`) | BUILT |
| Mermaid export | `GET /api/process/mining/mermaid` | BUILT |
| Heuristics Miner | `GET /api/process/mining/heuristics` | BUILT |
| Performance/bottleneck | `GET /api/process/mining/performance` | BUILT |
| Processes + grounded steps | `GET /api/process/mining/causal` + `/psl` + `/bayesian` | BUILT |
| GraphHealthSnapshot | `GET /api/graph-health` (Phase 7) | BUILT |
| CreationTimeView | `CreationTimeView.java` in lib | BUILT (lib only; no REST yet) |
| Table primitive | `Table.java` in lib + `TableRendererComponent` | BUILT (lib + UI component) |
| Pruning/health homeostasis | `PruneCompactOrchestrator` design | QUEUED (backend in design) |
| FactAuditEvent / timeline | append-only JSONL design | QUEUED (backend in design) |
| PIN mechanism | `PinRecord` / `PinGuard` design | QUEUED (backend in design) |
| Hydration per-stage progress | `GraphHydrationPipeline` design | IN-PROGRESS |
| Creation-time REST scoping | `POST /api/kb/grounding/creation-scoped` | QUEUED (lib built, no controller yet) |
| `POST /api/explain` unified trail | `ExplainOrchestrator` design | QUEUED (lib model built, controller not yet) |

---

## Part 2 — JOB 2: Design

### 2.1 Principle: The Visualizer as Cockpit

The graph canvas is not a viewer — it is the **primary interaction surface** for the knowledge base. Every node and edge is a handle to invoke reasoning:
- Left-click a node → context panel (verdict, strength, evidence, trail, provenance, actions)
- Right-click a node → context menu (verify, explain, assert correction, pin, scope analysis)
- Double-click a node → expand neighbors (existing) + trigger verify cascade for new edges
- Click an edge → edge context panel (edge strength, provenance, derivation rule)
- Toolbar overlays: strength-band heat map, provenance (observed vs derived), creation-time fade

This replaces the current passive attribution tab with an always-available, click-driven KB reasoning surface.

---

### 2.2 Node/Edge Context Panel

When a node is selected, the right-side panel gains a new **KB Context** tab (first tab, before the existing Details tab). The existing tabs are preserved.

#### KB Context tab layout (top to bottom)

```
┌─────────────────────────────────────────────────┐
│  [node label]                   [strength badge] │
│  SUPPORTED / REFUTED / UNKNOWN   ●●●●○ HIGH     │
│  confidence: 0.83                               │
├─────────────────────────────────────────────────┤
│  Evidence atoms (collapsed list, max 5 shown)   │
│  • isEmployedBy(Alice, Acme) [0.91]             │
│  • worksAt(Alice, Acme) [0.84]                  │
├─────────────────────────────────────────────────┤
│  Opinion   b=0.76  d=0.12  u=0.12  E=0.79      │
│  Source signals: PSL 0.81 | Embed 0.77           │
├─────────────────────────────────────────────────┤
│  Provenance                                      │
│  Type: DERIVED (PSL rule #14)                   │
│  Crawl run: cr-20260618-abc  Changed: 2026-06-18│
├─────────────────────────────────────────────────┤
│  [Why?]  [Verify]  [Correct]  [Pin]  [Ground]  │
├─────────────────────────────────────────────────┤
│  <app-reasoning-trail> (embedded, compact mode) │
│  (loads on "Why?" click, lazy)                  │
└─────────────────────────────────────────────────┘
```

**API calls for the KB Context tab (on node selection):**
1. Verify: `POST /api/kb-grounding/verify` `{atom: nodeId, factSheetId}` — populates verdict + confidence
2. On "Why?" click (lazy): `POST /api/kb-grounding/explain` `{atom: nodeId, factSheetId, depth: 3}` — populates `<app-reasoning-trail>`
3. Opinion/StrengthBand: derived client-side from `VerifyResponse.confidence` until `Opinion` is exposed on REST (see fork F-iii)
4. Provenance: read from `GraphNode.metadata._crawlRunId`, `_extractedAt`, `_changesetId` already present in the node data

**Actions wired in the context panel:**
- **[Why?]** — lazy-loads `ExplainResponse.derivation` into `<app-reasoning-trail>` compact mode
- **[Verify]** — re-runs verify (force-refresh, shows loading state)
- **[Correct]** — opens inline correction form (value 0..1, source label) → `POST /api/kb-grounding/assert` (GATED: backend built)
- **[Pin]** — sends assert with `value=1.0, source="USER_PIN"` (GATED: `PinRecord` controller queued)
- **[Ground]** — switches to Grounding Console tab scoped to this node's atom

**Edge context panel** (simpler):
- Edge type + `derivationRule` from metadata
- Strength badge from edge confidence metadata
- [Why this edge?] → explain with edge atom `edgeType(source, target)`

---

### 2.3 Node/Edge Styling

#### Strength-band overlay (new input to GraphCanvasComponent)
Add `@Input strengthOverlay: Record<string, StrengthBand> | null` alongside the existing `posteriorOverlay`. The canvas uses this to override the base `nodeColors` with a band-specific tint:

| StrengthBand | Fill tint | Border width |
|-------------|-----------|-------------|
| ESTABLISHED | base color, solid | 3px |
| HIGH | base color, 80% opacity | 2px |
| PROBABLE | base color, 60% opacity | 1px |
| SUPPRESSED | gray #9E9E9E | 1px dashed |
| SPECULATIVE | base color, 40% opacity | 1px dotted |

#### Provenance overlay (new input: `provenanceOverlay: Record<string, 'OBSERVED' | 'DERIVED' | 'INFERRED'>`)
- OBSERVED: circle node shape (existing default)
- DERIVED: diamond shape (SVG `<polygon>`)
- INFERRED: diamond with dashed border

#### Creation-time fade overlay
Nodes with `_extractedAt` older than the active `CreationTimeView` window are rendered at 20% opacity. Controlled by a new `creationTimeFade: boolean` toggle in the Filter tab.

---

### 2.4 Visualizer Toolbar Additions

Add to the existing toolbar row (above the canvas) a toggle-button group:

```
[Strength ●] [Provenance ◆] [Time ⏱] [Filter ⚙] [Fit ⊞] [Zoom+ -]
```

- **Strength ●** — toggles `strengthOverlay` (lazy-fetches via batch verify on visible nodes, max 50)
- **Provenance ◆** — toggles `provenanceOverlay` (reads from existing node metadata)
- **Time ⏱** — opens the existing temporal filter + adds a creation-time selector
- **Filter ⚙** — existing filter tab toggle

---

### 2.5 Visualizer Filter Tab Additions

Add to the existing Filter tab (no new tab needed):

1. **Strength band filter** — checkbox group (ESTABLISHED / HIGH / PROBABLE / SPECULATIVE / SUPPRESSED) → `applyFilters()` already has the hook; add `filter.strengthBands: Set<StrengthBand>`
2. **Creation-time window** — date-range picker for `_extractedAt` interval → maps to `CreationTimeView.between(from, before)` parameters sent as query params
3. **Provenance type** — toggle (OBSERVED / DERIVED / INFERRED / ALL)

The existing `applyFilters()` at line 2200 is extended to check these new filter fields before returning a node/edge.

---

### 2.6 GraphsHub Tab Map — Consolidated

#### Existing 18 tabs: no renames, no removals

Three new tabs are added. Total becomes 21. The `GraphsTab` union type gains:
```typescript
type GraphsTab = /* existing 18 */ | 'grounding' | 'processes' | 'audit';
```

#### New Tab 19: Grounding Console (`grounding`)
**Component:** `<app-grounding-console-panel>`  
**Ship state:** SHIP-NOW (backend live)

Sub-sections:
- **Verify atom** — free-text atom input + `POST /api/kb-grounding/verify` → verdict card
- **Query** — conjunctive query builder (predicate + args rows) + `POST /api/kb-grounding/query` → binding results table with confidence column
- **Assert/Retract** — atom + value slider + `POST /api/kb-grounding/assert` → status card
- **Explain** — atom input + `POST /api/kb-grounding/explain` → `<app-reasoning-trail>` full mode + `DerivationTree` collapse tree
- **Session history** — last 20 interactions in this session (local state, no persistence)

#### New Tab 20: Processes (`processes`)
**Component:** `<app-process-mining-panel>`  
**Ship state:** SHIP-NOW (all mining endpoints built)

Sub-sections (Angular `mat-tab-group` inside the panel):
- **Discover** — `GET /api/process/mining/discover?factSheetId=&anchorType=` → process suggestion card
- **Causal** — `GET /api/process/mining/causal` → table of activities + dependencies + χ² values
- **PSL rules** — `GET /api/process/mining/psl` → rule list with soft-truth weights
- **Bayesian** — `GET /api/process/mining/bayesian` → posterior probability table
- **Declare** — `GET /api/process/mining/declare` → MINERful constraint list
- **Heuristics** — `GET /api/process/mining/heuristics` → dependency net table
- **Performance** — `GET /api/process/mining/performance` → bottleneck table (activity → avg duration)
- **BPMN** — download button for `GET /api/process/mining/bpmn` → browser download as `.bpmn`
- **Mermaid** — inline `<pre>` with copy button + `GET /api/process/mining/mermaid` → code display
- **Grounded steps** — list of `ProcessSuggestion.steps` each with `VerifyResult` fetched from `/api/kb-grounding/verify` for each step atom

#### New Tab 21: Audit / Correction (`audit`)
**Component:** `<app-fact-audit-panel>`  
**Ship state:** GATED (backend design in `fact-store-audit-tuning-correction-design.md`, controller not built)

When backend ships:
- Timeline of `FactAuditEvent` rows (7 types: OBSERVED/INFERRED/CONTRADICTED/CORRECTED/PINNED/RETRACTED/STRENGTHENED)
- Filter by event type, crawl run, actor
- Inline [Correct] and [Pin] actions per fact row
- `StrengthLayer` badge per row (PINNED/CONFIRMED/TENTATIVE/SPECULATIVE)

---

### 2.7 Cross-Cutting Reusable Components

These three components are new and used pervasively across the cockpit.

#### `<app-reasoning-trail>` (`reasoning-trail.component.ts`)
**Modes:** `compact` (inline in context panel, max 8 rows) | `full` (Grounding Console, full tree)

Inputs:
```typescript
@Input trail: ReasoningTrailDto | null
@Input loading: boolean
@Input mode: 'compact' | 'full'
```

`ReasoningTrailDto` (Angular-side TS interface mirroring `ReasoningTrail.java`):
```typescript
interface ReasoningTrailDto {
  targetId: string;
  question: string;
  confidence: number;
  breakdown: ConfidenceBreakdownDto;   // pslScore, mebnScore, embeddingScore, groundingScore, fusedScore
  derivationTree: DerivationTreeNodeDto; // {atom, confidence, rule, source, children}
  entailments: EntailmentRecordDto[];
  evidence: string[];
  activatedRules: string[];
  inferenceMode: string;
  naturalLanguageSummary: string;
  computedAt: string;
}
```

Displays:
- NL summary as a blockquote
- Confidence breakdown as a horizontal bar (5 segments colored by band)
- `DerivationTree` as a collapsible nested list
- Evidence atoms as chips
- Activated rules as a code block

#### `<app-strength-badge>` (`strength-badge.component.ts`)
Inline component, ~30 lines. Input: `@Input band: StrengthBand`, `@Input opinion?: OpinionDto`. Output: five-dot display `●●●●○` with tooltip showing `b/d/u/E` values.

```typescript
const DOTS: Record<StrengthBand, number> = {
  ESTABLISHED: 5, HIGH: 4, PROBABLE: 3, SPECULATIVE: 2, SUPPRESSED: 1
};
```

Used in: KB Context panel, Grounding Console verdict card, BayesianPanel rows, process step list, Audit timeline rows.

#### `<app-table-view>` (`table-view.component.ts`)
Wraps the existing `TableRendererComponent` (which already exists at `…/table-renderer/`) with a standardized `@Input table: TableDto` interface and a download-as-CSV action. Adds pagination for large tables (>100 rows). Used in the KB Context panel when the selected node is `nodeType=TABLE`, in the new Processes tab for result tables, and in the Grounding Console query results.

---

### 2.8 Fixes to Existing Wiring

These are not new features — they are broken wiring that blocks the cockpit from working.

1. **`focusNodeId` pass-through** — In `graphs-hub.component.html`, the `<app-graph-visualizer>` element must gain `[focusNodeId]="focusNodeId"` and the visualizer must accept a new `@Input focusNodeId: string | null` that calls `expandNodeById(focusNodeId)` in `ngOnChanges`.

2. **`source-linking-panel` re-wire** — Add a "Source Links" sub-tab to the visualizer side panel using the existing (orphaned) `<app-source-linking-panel [factSheetId]="factSheetId">` component. Import `SourceLinkingPanelComponent` into the visualizer's standalone imports.

3. **Dead `onNodeSelectedForRelation()`** — Delete line 2990 (dead code).

---

### 2.9 New Angular Services

| Service | File | Endpoints covered |
|---------|------|------------------|
| `KbGroundingService` | `services/kb-grounding.service.ts` | `/api/kb-grounding/*` |
| `ProcessMiningService` | `services/process-mining.service.ts` | `/api/process/mining/*` |
| `ExplainService` | `services/explain.service.ts` | `POST /api/explain` (when built) |

`KbGroundingService` methods:
```typescript
verify(req: VerifyRequest): Observable<VerifyResponse>
query(req: QueryRequest): Observable<QueryResponse>
explain(req: ExplainRequest): Observable<ExplainResponse>
assert(req: AssertRequest): Observable<AssertResponse>
```

---

### 2.10 Phased Build Plan

#### Phase 1 — SHIP-NOW (all backend endpoints already built)
**Goal:** Visualizer as reasoning cockpit + Grounding Console + Processes tab

| Work item | New files | Existing files touched |
|-----------|-----------|----------------------|
| `KbGroundingService` | `kb-grounding.service.ts` | — |
| `<app-strength-badge>` | `strength-badge.component.ts/.html/.css` | — |
| `<app-reasoning-trail>` (compact) | `reasoning-trail.component.ts/.html/.css` | — |
| KB Context tab in visualizer side panel | — | `graph-visualizer.component.ts` (add tab, add `KbGroundingService` injection) |
| Strength-band overlay wiring | — | `graph-canvas.component.ts` (add `@Input strengthOverlay`) |
| Filter tab: strength-band + provenance | — | `graph-visualizer.component.ts` (extend `applyFilters`) |
| Fix `focusNodeId` pass-through | — | `graphs-hub.component.html`, `graph-visualizer.component.ts` |
| Wire orphaned `SourceLinkingPanel` | — | `graph-visualizer.component.ts` (import + add sub-tab) |
| `ProcessMiningService` | `process-mining.service.ts` | — |
| `<app-process-mining-panel>` | 3 component files | — |
| Add `processes` tab to `GraphsHub` | — | `graphs-hub.component.ts` (union type), `graphs-hub.component.html` (ngIf block) |
| `<app-grounding-console-panel>` | 3 component files | — |
| Add `grounding` tab to `GraphsHub` | — | `graphs-hub.component.ts`, `graphs-hub.component.html` |
| `<app-table-view>` wrapper | `table-view.component.ts/.html` | — |
| Register all new standalone components | — | `graphs-hub.component.ts` (imports array) |

**Bundle budget:** 21 tabs × average 15–25 kB = ~400 kB additional. Angular.json error ceiling is 30 MB; warning at 500 kB. Since the project is currently under warning (per Phase 8 notes the ceiling was bumped to 25 MB then later 30 MB), Phase 1 additions are well within budget. Set new `maximumWarning` to `2MB` to catch future overruns early.

**BPMN note (Fork F-i):** The `GET /api/process/mining/bpmn` endpoint returns raw BPMN 2.0 XML. For Phase 1, offer download-only (no in-browser render). A bpmn-js render (~600 kB gzipped) would push the warning ceiling — defer to Phase 3.

#### Phase 2 — After `POST /api/explain` controller ships
- `<app-reasoning-trail>` full mode (Grounding Console Explain sub-section)
- `ExplainService` wired to the real `/api/explain` endpoint
- Replace existing `GET /attribution/explain-quick` in the Attribution tab with the unified trail
- Migrate the "Explain Why?" button (visualizer line ~2855) from `explain-quick` to the new orchestrated trail

#### Phase 3 — After `PruneCompactOrchestrator` + `FactAuditEvent` controller ships
- `<app-fact-audit-panel>` for the `audit` tab
- Health tab enhancements: add prune/compact trigger buttons to existing `<app-graph-health-panel>`
- PruneCompactBudget dial (slider for `pruneFraction`, confirmation dialog)

#### Phase 4 — After creation-time REST endpoint + hydration pipeline progress endpoint
- Creation-time window picker in visualizer Filter tab (wired to `POST /api/kb/grounding/creation-scoped`)
- Hydration Progress tab: per-stage progress bars for the 11-stage `GraphHydrationPipeline`

---

### 2.11 Ship-Now vs Gated — Capability Table

| Capability | Backend endpoint | UI component | Ship state |
|------------|-----------------|-------------|-----------|
| KB verify | `POST /api/kb-grounding/verify` | KB Context panel verdict | SHIP-NOW |
| KB query | `POST /api/kb-grounding/query` | Grounding Console | SHIP-NOW |
| KB explain | `POST /api/kb-grounding/explain` | KB Context "Why?" + ReasoningTrail compact | SHIP-NOW |
| KB assert | `POST /api/kb-grounding/assert` | KB Context [Correct] | SHIP-NOW |
| Strength badge | derived from `VerifyResponse.confidence` | `<app-strength-badge>` | SHIP-NOW |
| Process discovery | `GET /api/process/mining/discover` | Processes tab | SHIP-NOW |
| BPMN download | `GET /api/process/mining/bpmn` | Processes tab download button | SHIP-NOW |
| Mermaid display | `GET /api/process/mining/mermaid` | Processes tab pre block | SHIP-NOW |
| Heuristics Miner | `GET /api/process/mining/heuristics` | Processes tab table | SHIP-NOW |
| Performance mining | `GET /api/process/mining/performance` | Processes tab bottleneck | SHIP-NOW |
| Grounded process steps | `/discover` + `/verify` per step | Processes grounded steps sub-tab | SHIP-NOW |
| GraphHealthSnapshot trend | `GET /api/graph-health` | Health tab (already exists) | SHIP-NOW (extend) |
| Provenance overlay | node metadata `_crawlRunId` etc. | Canvas overlay toggle | SHIP-NOW |
| focusNodeId fix | existing `/connected` | graphs-hub → visualizer | SHIP-NOW (fix) |
| SourceLinkingPanel re-wire | existing `/sources/*` | visualizer sub-tab | SHIP-NOW (fix) |
| Table node view | `GET /knowledge-graph/nodes/{id}` | `<app-table-view>` in context panel | SHIP-NOW |
| Full ReasoningTrail | `POST /api/explain` (not yet built) | Grounding Console full mode | GATED (Phase 2) |
| Unified trail migration | same | Replace explain-quick | GATED (Phase 2) |
| Opinion b/d/u on REST | lib built; no controller yet | KB Context opinion row | GATED (Phase 2) |
| StrengthBand on REST | lib built; no controller yet | Strength-band filter | GATED (Phase 2) |
| Creation-time scoping | `POST /api/kb/grounding/creation-scoped` not built | Filter tab date picker | GATED (Phase 4) |
| FactAudit timeline | controller not built | Audit tab | GATED (Phase 3) |
| PIN mechanism | `PinRecord` controller not built | Context panel [Pin] | GATED (Phase 3) |
| Correction [Correct] | `/assert` is built → SHIP-NOW | Context panel [Correct] | SHIP-NOW |
| PruneCompact trigger | `PruneCompactOrchestrator` not built | Health tab buttons | GATED (Phase 3) |
| Hydration progress | pipeline progress endpoint not built | Hydration tab | GATED (Phase 4) |
| BPMN in-browser render | bpmn-js not integrated | Processes tab | GATED (Phase 3, Fork F-i) |

---

### 2.12 Open Forks — Decide Before Phase 1 Coding

#### Fork F-i: BPMN in-browser render vs export-only
**Option A (recommended):** Phase 1 = download button only (zero bundle cost). Phase 3 = lazy-load bpmn-js via dynamic `import()` in the Processes tab only — Angular lazy chunk, ~600 kB isolated to that tab. Bundle warning not hit because it is a separate chunk.  
**Option B:** Embed bpmn-js eagerly — pushes initial bundle above the 500 kB warning immediately. Not recommended.  
**Decision: Option A.**

#### Fork F-ii: Visualizer context panel as side-drawer vs inline tab
**Option A (recommended):** Extend the existing side panel's tab strip with "KB Context" as the first tab. Zero new DOM structure, same `showSidePanel` toggle, works with current layout. The context panel slides in using the existing `<mat-card>` side panel (lines ~2000–2050 in the template).  
**Option B:** A full `<mat-drawer>` that overlays the canvas from the right. Requires restructuring the visualizer template significantly and risks breaking D3 canvas sizing.  
**Decision: Option A.** Smaller diff, zero layout risk.

#### Fork F-iii: Eager vs lazy grounding fetch on node click
**Option A:** Fire `POST /api/kb-grounding/verify` on every node click. Simple. Risk: clicking quickly through nodes generates N outstanding HTTP requests; `switchMap` on the click stream debounces this.  
**Option B (recommended):** Debounce with `debounceTime(300)` + `switchMap` in `onNodeSelected()`. The verify call only fires if the user pauses on a node for 300ms. Evidence lazy-loads on click of the expand arrow.  
**Decision: Option B.** Use `Subject<string>` for node selection piped through `debounceTime(300) + switchMap(() => this.kbGroundingService.verify({...}))`.

#### Fork F-iv: Opinion/StrengthBand from REST vs client-derived
The `Opinion` and `StrengthBand` types are built in the lib (`Opinion.java`, `StrengthBand.java`) but are not yet exposed on any REST endpoint. Two options:  
**Option A (recommended for Phase 1):** Derive `StrengthBand` client-side from `VerifyResponse.confidence` using the same band thresholds (ESTABLISHED ≥0.85, HIGH ≥0.70, PROBABLE ≥0.40, SPECULATIVE ≥0.10, else SUPPRESSED). No REST change needed.  
**Option B:** Add `opinion: {belief, disbelief, uncertainty, baseRate}` and `band: StrengthBand` to `VerifyResponse` on the backend. Cleaner long-term; requires touching `KbGroundingController` and `VerifyResponse.java`.  
**Decision: Option A for Phase 1; plan Option B for Phase 2 when the explain endpoint is added.**

---

## Part 3 — Reconciliation with Earlier UI Designs

| Earlier design doc | Status after this doc |
|-------------------|----------------------|
| `grounding-ui-integration-design.md` | SUPERSEDED. Its 6 sub-panels (Grounding Console, Embeddings, Domain Objects, Temporal Slice, OWL Type Browser, Live Cascade) are consolidated: Grounding Console → Tab 19; Embeddings → part of Grounding Console; Domain Objects → KB Context panel; Temporal/OWL/Cascade → deferred to Phase 4. The "4-touch tab pattern" is preserved. |
| `reasoning-trail-explainability-design.md` UI sections | SUPERSEDED. `<app-reasoning-trail>` is specified here (compact + full). Migration of `explain-quick` → unified trail is Phase 2. Component SPI is unchanged. |
| Domain-object-grounding UI (from `domain-object-grounding-design.md`) | SUPERSEDED. `GroundedElement<T>` display is the KB Context panel in the visualizer. `DerivationTreeComponent` → embedded in `<app-reasoning-trail>` full mode. |

All three earlier designs are now subordinate to this document. When they conflict, this document governs.

---

## Part 4 — Key File References (Absolute Paths)

### Backend files that drive the UI contract
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/web/controllers/grounding/KbGroundingController.java`
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/web/controllers/grounding/VerifyRequest.java`
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/web/controllers/grounding/VerifyResponse.java`
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/web/controllers/grounding/ExplainRequest.java`
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/web/controllers/grounding/ExplainResponse.java`
- `kompile-app/kompile-data/kompile-process/kompile-process-discovery/src/main/java/ai/kompile/process/discovery/MiningDiscoveryController.java`
- `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/explain/ReasoningTrail.java`
- `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/Opinion.java`
- `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/confidence/StrengthBand.java`
- `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/fol/grounding/KbVerifier.java`
- `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/model/CreationTimeView.java`
- `kompile-app/kompile-app-parent/kompile-app-core/src/main/java/ai/kompile/core/graphrag/maintenance/model/GraphHealthSnapshot.java`

### Frontend files to extend
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/src/app/components/graph-visualizer/graph-visualizer.component.ts` (3002 lines — add KB Context tab + service injection)
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/src/app/components/graph-visualizer/graph-canvas.component.ts` (889 lines — add `strengthOverlay` + `provenanceOverlay` inputs)
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/src/app/components/graphs-hub/graphs-hub.component.ts` (147 lines — extend union type)
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/src/app/components/graphs-hub/graphs-hub.component.html` (154 lines — add 3 ngIf blocks)
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/src/app/services/graph.service.ts` (664 lines)
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/src/app/components/graph-visualizer/source-linking-panel.component.ts` (510 lines — currently orphaned, re-wire)
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/angular.json` (set `maximumWarning` to `2MB`)

### Frontend new files (Phase 1)
All under `…/src/main/frontend/src/app/`:
```
services/kb-grounding.service.ts
services/process-mining.service.ts
components/strength-badge/strength-badge.component.ts/.html/.css
components/reasoning-trail/reasoning-trail.component.ts/.html/.css
components/grounding-console-panel/grounding-console-panel.component.ts/.html/.css
components/process-mining-panel/process-mining-panel.component.ts/.html/.css
components/table-view/table-view.component.ts/.html
```

### Existing orphaned component to re-wire
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/frontend/src/app/components/graph-visualizer/source-linking-panel.component.ts`
- Existing `TableRendererComponent` at `…/components/table-renderer/` (wrap, do not replace)
