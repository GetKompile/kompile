# KB UI Surfacing Plan — Deferred Items

**Date:** 2026-06-22
**Status:** Design reference; top 2 slices DELIVERED (see below)

---

## What Was Delivered (top-slice)

### Slice 1: Community Visualization (DONE)

**Backend**
- `GraphCommunityService` (`ai.kompile.knowledgegraph.reasoning`) — runs Louvain or LabelPropagation detector over a fact sheet's ReasoningGraph subgraph; returns `CommunityResult` (nodeToCommunit map, modularity, communityCount, communityMembers inverted index).
- `GraphCommunityController` — `GET /api/graph/{factSheetId}/communities?method=louvain&resolution=1.0&seed=42&maxNodes=500`

**Frontend**
- `graph-canvas.component.ts` — new `@Input() communityOverlayEnabled`, `@Input() communityMap: Map<string, number>` + `updateCommunityOverlay()` that colors node strokes and adds semi-transparent rings using a 20-color palette indexed by community id.
- `graph-visualizer.component.ts` — new `toggleCommunityOverlay()` + toolbar button (bubble_chart icon) that calls the communities endpoint and populates `communityMap`.
- `community-panel/community-panel.component.ts` — standalone tab component: method selector (Louvain/LP), resolution input, "Detect" button, stats row (count/modularity/nodes), color-coded community chips, members list.
- Wired into `GraphsHubComponent` as the "Communities" tab (`bubble_chart` icon).

### Slice 2: Fact / Opinion Browser (DONE)

**Backend**
- `KbOpinionBrowserController` (`ai.kompile.knowledgegraph.grounding.controller`):
  - `GET /api/kb-grounding/{factSheetId}/opinions?tier=&q=&maxUncertainty=&minExpectation=&limit=200` — returns `List<FactOpinionRow>` with full Subjective-Logic opinion (belief/disbelief/uncertainty/expectation/baseRate). Three-tier opinion derivation: Beta evidence → embedded `_opinion` JSON → `fromSoftTruth` scalar fallback.
  - `GET /api/kb-grounding/{factSheetId}/band-summary` — returns `Map<String, Long>` bandName → count.

**Frontend**
- `opinion-browser/opinion-browser.component.ts` — standalone component with tier chips, search input, maxUncertainty/minExpectation filters, mat-table with 7 columns including b/d/u simplex bar and `<app-strength-badge>`.
- Wired into `GraphsHubComponent` as the "Opinions" tab (`psychology_alt` icon).

---

## Deferred Items

### D1: FOL Materialized-Rule Browser

**What exists:** `EntailmentEngine`, `EntailmentRecord`, `PslRule`, `PslProgram` in `kompile-graph-reasoning`. `IncrementalReasoningOrchestrator` runs weight learning and stores fired rules.

**Missing:** No endpoint to list current PSL rules for a fact sheet, no endpoint to show which atoms a rule grounded over, no UI.

**Proposed design:**
- New endpoint: `GET /api/kb-grounding/{factSheetId}/rules` → `List<RuleSummaryRow>` (ruleText, weight, firingCount, supportedAtoms sample)
- New tab in GraphsHub: "FOL Rules" — rule list with weight bar, click to expand firing atoms

**Wiring point:** `IncrementalReasoningOrchestrator.getActiveProgramSnapshot(long factSheetId)` (needs to be added or exposed) → existing `PslProgram` / `PslRule`.

---

### D2: Subgraph / Focal-View Builder

**What exists:** `SubgraphMaterializer.materialize(graph, SubgraphSpec)` in `kompile-graph-reasoning/subgraph/`. `SubgraphSpec` includes seed nodes, radius, relation-type filter, confidence floor.

**Missing:** No REST endpoint to trigger materialization for a named subgraph; no UI to define a `SubgraphSpec`.

**Proposed design:**
- New endpoint: `POST /api/graph/{factSheetId}/subgraph` body: `{seedNodeIds, radius, edgeTypes, confidenceFloor, targetNamedGraphId?}` → returns a bounded `D3VisualizationData` (reuse the existing graph-visualizer serialization) or a `SubgraphHandle{id, nodeCount, edgeCount}`.
- UI: "Subgraph" panel in GraphsHub (or a "Focus" button in the graph-visualizer context menu) — seed selection from selected node, radius slider (1–5), relation-type filter chips, confidence floor slider.

---

### D3: Per-Atom Opinion Simplex Inspector (full-detail popup)

**What exists:** `StrengthBadgeComponent` shows a 5-dot badge with a tooltip. `KbContextPanelComponent` shows verify + badge inline. The opinion-browser table shows a summary bar.

**Missing:** No dedicated "opinion drill-down" panel showing the full Subjective Logic simplex triangle (b, d, u barycentric visualization), fusion history, and source breakdown.

**Proposed design:**
- Add an expandable row in the opinion-browser table that, on click, calls `GET /api/kb-grounding/{factSheetId}/explain` and renders: the derivation tree, the cumulative fusion chain, a barycentric simplex triangle (SVG, 3 vertices = certain-true / certain-false / vacuous).
- Alternatively, extend `KbContextPanelComponent` with a "Show Full Opinion" expansion that renders the SVG simplex triangle inline.

---

### D4: BasisType Column and Filter

**What exists:** `GraphProvenanceKeys.BASIS_TYPE = "_basisType"` stored on every node/edge. Values: `STRUCTURAL | LLM_EXTRACTION | PSL_INFERENCE | MEBN_INFERENCE | CORROBORATION | ASSERTED`.

**Missing:** The opinion-browser and facts-by-tier-panel have no basisType column or filter chip. The provenance panel shows raw metadata JSON but does not surface this dimension.

**Proposed design:**
- Extend `KbGroundingAuditController.FactTierRow` to add `basisType: String` (read from `InferredFactRow.provenanceJson` or the `_basisType` metadata key).
- Extend the opinion-browser to show basisType as a small badge (`STRUCTURAL`, `LLM`, `PSL`, `MEBN`, `CORR`, `ASSERTED`) and add a multi-select filter.
- Extend facts-by-tier-panel similarly.

---

### D5: Opinion-Set / Corpus-Level Search

**What exists:** `KbVerifier.verify(atom, factSheetId)` checks a single atom. There is no corpus-search path (e.g. "show all facts containing entity X with belief > 0.8").

**Missing:** No endpoint for free-text + epistemic-threshold search across the full inferred-fact corpus. The opinion-browser supports per-atom text search but only queries via `FactPromotionTracker.factsByTierDurable` which does not support arbitrary predicate/entity search.

**Proposed design:**
- New endpoint: `GET /api/kb-grounding/{factSheetId}/search?entity=Acme&predicate=worksAt&minBelief=0.8&maxUncertainty=0.3` — queries InferredFactRowRepository with JPQL containing the entity name (substring of atomKey) and opinion thresholds.
- Extend opinion-browser with an "Advanced Search" panel exposing entity name, predicate name, and epistemic threshold sliders.

---

### D6: MEBN Theory Inspector

**What exists:** `IncrementalReasoningOrchestrator.registerMTheory()` and `MebnWeightLearner`. MEBN edge strengths are learned per fact sheet.

**Missing:** No endpoint to inspect registered MTheory structures. No UI to show node-type-conditional probability tables (CPTs) learned during weight learning.

**Proposed design:**
- New endpoint: `GET /api/kb/weights/mebn/{factSheetId}` → `List<MebnWeightRow>{mFragName, conditionDescription, learnedStrength}`.
- Add a "MEBN" sub-section to the existing `KbWeightsPanelComponent`.

---

### D7: ValidFrom/ValidTo Temporal Filter in Facts Panel

**What exists:** `GraphProvenanceKeys.VALID_FROM` and `VALID_TO` (epoch-millis). The graph-visualizer already has a temporal filter for graph edges. The facts panels have no temporal dimension.

**Proposed design:**
- Extend `KbGroundingAuditController.FactTierRow` to include `validFrom: Long` and `validTo: Long`.
- Add a "Time Range" filter to `FactsByTierPanelComponent` and `OpinionBrowserComponent` (two date-picker inputs; filter applied client-side after load or passed as query params to the endpoint).

---

### D8: Band-Count Dashboard Widget

**What exists:** `FactPromotionTracker.bandCounts(long factSheetId)` returns `Map<StrengthBand, Integer>`. The `GET /api/kb-grounding/{factSheetId}/band-summary` endpoint now exists.

**Missing:** No graphical summary widget (pie chart or stacked bar) showing the epistemic health at a glance. The opinion-browser shows a text row of band counts but no chart.

**Proposed design:**
- Add a small SVG horizontal stacked bar (no D3 dependency needed — just `div.band-bar-segment` with percentage widths) to the top of both `FactsByTierPanelComponent` and the GraphsHub overview area.
- Consider adding a "KB Health" row to `GraphHealthPanelComponent` next to density/orphans.

---

### D9: Community Reports (LLM Summaries)

**What exists:** `CommunitySummaryService` (matrix store, Spring @Service) generates LLM-written community summaries stored in memory. The community-panel endpoint returns raw node-id lists only.

**Missing:** No endpoint to retrieve the LLM-generated community reports. The community-panel shows raw member ids, not human-readable summaries.

**Proposed design:**
- Add `GET /api/graph/{factSheetId}/community-reports?rebuild=false` that calls `CommunitySummaryService.getOrBuildReports(graph)` (need to bridge from the fact-sheet's `AdjacencyMatrixGraph`).
- Extend `CommunityPanelComponent` to show the LLM summary for each selected community id below the member list.

---

## Implementation Priority Order (suggested)

| Priority | Item | Effort | Value |
|----------|------|--------|-------|
| 1 | D4: BasisType column (1 field + 1 filter chip) | Low | High |
| 2 | D8: Band-count stacked bar in FactsByTier | Low | Medium |
| 3 | D9: Community reports endpoint + panel extension | Medium | High |
| 4 | D5: Corpus-level opinion search | Medium | High |
| 5 | D1: FOL rules browser | Medium | Medium |
| 6 | D7: ValidFrom/ValidTo temporal filter | Low | Medium |
| 7 | D2: Subgraph focal-view builder | High | High |
| 8 | D3: Per-atom simplex triangle inspector | High | Medium |
| 9 | D6: MEBN theory inspector | Medium | Low |
