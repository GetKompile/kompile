# Grounding + Reasoning Primitives: Angular Frontend Integration Design

**Date**: 2026-06-21  
**Status**: DESIGN — no Angular code written yet  
**Prerequisite reading**: [`grounding-api-contract-design.md`](grounding-api-contract-design.md), [`agent-grounding-infrastructure-design.md`](agent-grounding-infrastructure-design.md)

---

## 0. Context and Goal

The backend has new capability that has no UI surface at all:

| Primitive | Backend status | REST surface |
|---|---|---|
| KB Grounding (verify / query / explain / assert) | DONE | `KbGroundingController` at `/api/kb-grounding/*` (`grounding/KbGroundingController.java`) |
| KGE Embeddings + RotatE link prediction | DONE | `KGEmbeddingController` at `/api/knowledge-graph/embeddings` (`knowledgegraph/embedding/controller/KGEmbeddingController.java`) |
| Ontology derivation | DONE | `OntologyDerivationController` at `/api/process/ontology/derive` (`web/controllers/OntologyDerivationController.java`) |
| Temporal as-of queries | PARTIAL | `GET /api/knowledge-graph/temporal/bounds`, `GET /api/knowledge-graph/temporal/edges` exist; `TemporalView.at(Instant)` is library-only — no dedicated controller |
| OWL / DL type-system browser | NONE | `OwlDlReasoningBridge`, `TypeHierarchy`, `OwlOntologyMapper` are library-only; no REST controller |
| Live cascade / KB subscription | NONE | `POST /subscribe` returns HTTP 501 (`KbGroundingController.java:275`); `GET /subscribe/{id}/events` throws `UnsupportedOperationException` (`KbGroundingController.java:286`) |

**Decision**: add six new sub-panels to the existing GraphsHub component. No new top-level tab. The hub already has 18 sub-tabs (`GraphsTab` union, `graphs-hub.component.ts:45-48`); the nav scrolls horizontally in CSS so adding more is low-risk. Of the six panels, only one ships in Phase 1 (Grounding Console — backend already exists). The remaining five are gated on either new REST controllers or Phase 2 backend wiring.

---

## 1. Navigation Architecture

### 1.1 How tabs work (reference implementation)

The `GraphsHubComponent` (`graphs-hub.component.ts:91`) owns `activeSubTab: GraphsTab`. Tab buttons in the HTML template (`graphs-hub.component.html:13-85`) each bind `(click)="selectSubTab('value')"` and `[class.active]="activeSubTab === 'value'"`. Content panels are `*ngIf="activeSubTab === 'xxx'"` divs (`graphs-hub.component.html:88+`). There is no router-outlet and no `mat-tab-group`. `AppRoutingModule` is empty (dead).

### 1.2 Adding a new sub-tab: the four-touch pattern

Every new panel requires exactly four edits:

1. **`GraphsTab` type** (`graphs-hub.component.ts:45-48`): add the string literal to the union.
2. **Nav button** (`graphs-hub.component.html`): add a `<button class="sub-tab">` entry after the last `io` button (line 84).
3. **Content div** (`graphs-hub.component.html`): add `<div *ngIf="activeSubTab === 'xxx'"><app-xxx [factSheetId]="activeFactSheetId"></app-xxx></div>` in the `sub-tab-content` section.
4. **`imports[]` array** (`graphs-hub.component.ts:61-83`): add the new standalone component to the `imports` list. (No `app.module.ts` declaration needed — GraphsHub is standalone.)

### 1.3 Bundle budget

`angular.json:56-65`: `maximumError: "30MB"`. The budget was bumped from 25 MB during Phase 8. Add four lightweight panels (no D3, no force layout) to stay well under the ceiling. If the ceiling becomes an issue again, bump to 35 MB in `angular.json` under `"production" > "budgets" > "type": "initial"`. Never add D3 or charting libraries to new panels without a budget bump.

---

## 2. The Six New Panels

### 2.1 Grounding Console — Phase 1, ships immediately

**Tab key**: `'grounding'`  
**Component**: `KbGroundingPanelComponent` (`app/components/kb-grounding-panel/`)  
**Service**: `KbGroundingService` (`app/services/kb-grounding.service.ts`)  
**Backend**: `KbGroundingController` at `/api/kb-grounding/` — LIVE NOW (`web/controllers/grounding/KbGroundingController.java:59`)  
**Backend gate**: none — ships in Phase 1

**UX flow**:

1. Atom text field (e.g. `isEmployedBy(Alice, Acme)`) + optional fact-sheet ID selector (pre-filled from `activeFactSheetId`).
2. Three action buttons: **Verify**, **Explain**, **Query**.
   - **Verify** → `POST /api/kb-grounding/verify` → renders verdict chip (`SUPPORTED` green / `REFUTED` red / `UNKNOWN` grey) + confidence bar + evidence atom list.
   - **Explain** → `POST /api/kb-grounding/explain` → renders the `DerivationTree` as a recursive tree view (expand/collapse per node: atom key, confidence, rule applied). The `summary` string is shown at the top.
   - **Query** → opens a secondary sub-form for conjuncts (add/remove rows of predicate + args), `POST /api/kb-grounding/query` → renders a variable-binding table (`?Var → value`, confidence per row).
3. **Assert** mode (toggle, off by default): exposes value slider [0..1] + source label field → `POST /api/kb-grounding/assert`. On `CONTRADICTION_DETECTED` the response `contradictions` list is shown. On `ASSERTED` a staleness banner appears if `meta.stale = true`.
4. KB meta bar at the bottom: `kbVersion`, `asOf`, `stale` indicator.

**Component shape**:

```typescript
// kb-grounding-panel.component.ts
@Component({ selector: 'app-kb-grounding-panel', standalone: true, ... })
export class KbGroundingPanelComponent implements OnChanges {
  @Input() factSheetId: number | null = null;

  mode: 'verify' | 'explain' | 'query' | 'assert' = 'verify';
  atom = '';
  loading = false;
  verifyResult: VerifyResponse | null = null;
  explainResult: ExplainResponse | null = null;
  queryResult: QueryResponse | null = null;
  assertResult: AssertResponse | null = null;
  conjuncts: ConjunctEntry[] = [{ predicate: '', args: [] }];

  constructor(private grounding: KbGroundingService, private snack: MatSnackBar) {}

  verify(): void { /* POST /verify, set verifyResult */ }
  explain(): void { /* POST /explain, set explainResult */ }
  query(): void { /* POST /query, set queryResult */ }
  assert(): void { /* POST /assert, set assertResult */ }
}
```

**Service shape** (mirrors `GraphHealthService` pattern at `services/graph-health.service.ts:57-83`):

```typescript
// kb-grounding.service.ts
@Injectable({ providedIn: 'root' })
export class KbGroundingService extends BaseService {
  constructor(private http: HttpClient) { super(); }
  verify(req: VerifyRequest): Observable<VerifyResponse> {
    return this.http.post<VerifyResponse>(`${this.backendUrl}/api/kb-grounding/verify`, req);
  }
  explain(req: ExplainRequest): Observable<ExplainResponse> {
    return this.http.post<ExplainResponse>(`${this.backendUrl}/api/kb-grounding/explain`, req);
  }
  query(req: QueryRequest): Observable<QueryResponse> {
    return this.http.post<QueryResponse>(`${this.backendUrl}/api/kb-grounding/query`, req);
  }
  assert(req: AssertRequest): Observable<AssertResponse> {
    return this.http.post<AssertResponse>(`${this.backendUrl}/api/kb-grounding/assert`, req);
  }
}
```

**TypeScript model interfaces** (mirrors `grounding-api-contract-design.md` section 7):

```typescript
// kb-grounding-models.ts
export interface VerifyRequest { atom: string; factSheetId?: number; asOf?: string; minConfidence?: number; sessionId?: string; }
export interface VerifyResponse { verdict: 'SUPPORTED'|'REFUTED'|'UNKNOWN'; confidence: number; evidenceAtoms: string[]; activatedRules: string[]; derivationDepth: number; sourceProvenance: string[]; meta: GroundingMeta; }
export interface ConjunctEntry { predicate: string; args: string[]; }
export interface QueryRequest { conjuncts: ConjunctEntry[]; factSheetId?: number; asOf?: string; maxResults?: number; minConfidence?: number; sessionId?: string; }
export interface BindingRow { variables: Record<string,string>; confidence: number; matchedAtoms: string[]; }
export interface QueryResponse { bindings: BindingRow[]; total: number; truncated: boolean; meta: GroundingMeta; }
export interface ExplainRequest { atom: string; factSheetId?: number; depth?: number; sessionId?: string; }
export interface DerivationNode { atom: string; confidence: number; rule: string|null; source: string|null; children: DerivationNode[]; }
export interface ExplainResponse { atom: string; verdict: string; confidence: number; summary: string; derivation: DerivationNode; meta: GroundingMeta; }
export interface AssertRequest { atom: string; value: number; factSheetId?: number; sessionId?: string; source?: string; expectedVersion?: number; }
export interface AssertResponse { status: 'ASSERTED'|'CONTRADICTION_DETECTED'|'CONFLICT_QUEUED'|'RETRACTED'; version: number; contradictions: string[]; cascadeTriggered: boolean; meta: GroundingMeta; }
export interface GroundingMeta { factSheetId: number; asOf: string; stale: boolean; stalenessBudgetMs: number; kbVersion: number; sessionId: string|null; }
```

**DerivationTree renderer**: a recursive standalone `DerivationTreeComponent` that takes a `DerivationNode` input and renders with `mat-expansion-panel` (expand per node) — no D3 needed. Confidence shown as a colored inline chip. Children rendered recursively via `*ngFor` + `ng-template`.

**Cross-navigation hook**: from the Visualizer panel, a right-click / context-menu item "Verify in Grounding Console" should set `atom` to `entityType(nodeTitle)` and call `this.activeSubTab = 'grounding'` in GraphsHub. Wire this the same way `onAttributeNode()` seeds the causal attribution panel (`graphs-hub.component.ts:140-146`). Add `(verifyNode)="onVerifyNode($event)"` output on `<app-graph-visualizer>` and a corresponding `onVerifyNode(nodeId: string)` method in GraphsHub.

---

### 2.2 Temporal Slice View — Phase 2, gated on backend controller

**Tab key**: `'temporal'`  
**Component**: `GraphTemporalPanelComponent` (`app/components/graph-temporal-panel/`)  
**Service**: `GraphTemporalService` (`app/services/graph-temporal.service.ts`)  
**Backend gate**: a new `TemporalReasoningController` at `/api/kb-grounding/temporal` is needed. The existing `GET /api/knowledge-graph/temporal/bounds` and `GET /api/knowledge-graph/temporal/edges` in `KnowledgeGraphController` give edge-level time bounds, but do NOT expose `TemporalView.at(Instant)` (the full KB snapshot at a point-in-time). A new endpoint `POST /api/kb-grounding/temporal/snapshot` that accepts `{ factSheetId, asOf }` and returns a `VerifyResponse`-like payload for every asserted fact at that time is the minimum needed. Alternatively, `ask_graph_verify` with `asOf` populated already routes through `TemporalView.at()` — so the Grounding Console's verify mode with an `asOf` field filled in IS the temporal view. The separate panel is therefore a convenience timeline UI, not a hard requirement.

**Recommended design**: defer the standalone tab. Instead, add an **As-Of date picker** to the Grounding Console panel (Phase 1) that populates the `asOf` field of verify/query requests. This gives temporal point-in-time verification with zero new backend work and no new component. The dedicated Temporal panel (with a slider over the `temporal/bounds` range, visualizing which facts existed at each time) is Phase 2 once `TemporalReasoningController` is built.

**Backend work needed**:
- `POST /api/kb-grounding/temporal/snapshot` → returns `{ asOf, facts: [{atom, confidence, source}] }` for all live inferred facts at the given timestamp using `TemporalView.at(asOf).allFacts()`.
- Add to `GlobalExceptionHandler.basePackages` if placed in a new sub-package.

---

### 2.3 Type System / OWL Browser — Phase 2, gated on new controller

**Tab key**: `'typeBrowser'`  
**Component**: `GraphTypeBrowserComponent` (`app/components/graph-type-browser/`)  
**Service**: `GraphTypeSystemService` (`app/services/graph-type-system.service.ts`)  
**Backend gate**: NO REST controller exists. `OwlDlReasoningBridge`, `TypeHierarchy`, `OwlOntologyMapper`, `ReasoningGraphABoxLoader`, and `ExternalOwlImporter` are all in `kompile-reasoning-owl-bridge` as infra-free library classes. A new `TypeSystemController` at `/api/knowledge-graph/type-system` is required. Minimum surface:
  - `GET /api/knowledge-graph/type-system/{factSheetId}/hierarchy` → returns class hierarchy as tree JSON (`TypeHierarchy.toJson()` or equivalent)
  - `GET /api/knowledge-graph/type-system/{factSheetId}/inferred` → returns RL-inferred type memberships with confidence
  - `GET /api/knowledge-graph/type-system/{factSheetId}/inconsistencies` → returns DL inconsistency list

**UX**: a tree view of OWL classes (collapsible, same `mat-expansion-panel` approach as DerivationTree). Click a class to see its instances from the graph (cross-link to Entity Browser). A separate "Inconsistencies" section lists entities violating OWL restrictions with the violated axiom. An "Inferred Types" section shows probabilistic RL type memberships with confidence bars.

**Note**: the existing Ontology panel (`graph-ontology-panel`) shows the `OntologySchema` (the process-engine governance layer). The Type Browser is distinct — it surfaces the OWL ABox/TBox from `kompile-reasoning-owl-bridge`. Do not merge these two panels; they address different levels of the type hierarchy (governance schema vs. formal OWL DL).

---

### 2.4 Embeddings + Link Prediction — Phase 1 feasible, gated on embedding registration

**Tab key**: `'embeddings'`  
**Component**: `GraphEmbeddingsPanelComponent` (`app/components/graph-embeddings-panel/`)  
**Service**: `GraphEmbeddingsService` (`app/services/graph-embeddings.service.ts`)  
**Backend**: `KGEmbeddingController` at `/api/knowledge-graph/embeddings` — LIVE (`knowledgegraph/embedding/controller/KGEmbeddingController.java:53`). Endpoints: `POST /score`, `POST /predict/tails`, `POST /predict/heads`, `POST /predict/relations`, `GET /similar/entities/{factSheetId}/{entityName}`, plus training job management.  
**Backend gate**: the controller exists but is in `kompile-knowledge-graph` (a data module), not `kompile-app-main`. Confirm it is auto-scanned in the app-main Spring context. If not, it needs to be included in the `@SpringBootApplication` scan or the component scan in `kompile-app-main`. Check `KClawAutoConfiguration` or the main application class scan base packages before wiring the UI.

**UX flow**:

1. Entity name input + fact-sheet selector → `GET /similar/entities/{factSheetId}/{entityName}` → renders a ranked similarity list (entity name + cosine score).
2. Link prediction sub-form: head entity + relation → `POST /predict/tails` → renders top-N tail predictions with confidence scores. Head + tail + `?` → `POST /predict/relations`.
3. Training section: displays active training jobs, allows `POST /training` to kick off a new RotatE or Node2Vec run, polls job status.
4. The similarity list should have a "View in Graph" action that sets `focusNodeId` in GraphsHub and switches to the Visualizer tab (same pattern as Entity Browser's `(navigateToGraph)` output).

**Service shape** (follows `GraphHealthService` pattern):

```typescript
// graph-embeddings.service.ts
@Injectable({ providedIn: 'root' })
export class GraphEmbeddingsService extends BaseService {
  constructor(private http: HttpClient) { super(); }
  similarEntities(factSheetId: number, entityName: string): Observable<EmbeddingSimilarityResult[]> {
    return this.http.get<EmbeddingSimilarityResult[]>(`${this.backendUrl}/api/knowledge-graph/embeddings/similar/entities/${factSheetId}/${encodeURIComponent(entityName)}`);
  }
  predictTails(req: LinkPredictRequest): Observable<LinkPredictResult[]> {
    return this.http.post<LinkPredictResult[]>(`${this.backendUrl}/api/knowledge-graph/embeddings/predict/tails`, req);
  }
  predictRelations(req: LinkPredictRequest): Observable<LinkPredictResult[]> {
    return this.http.post<LinkPredictResult[]>(`${this.backendUrl}/api/knowledge-graph/embeddings/predict/relations`, req);
  }
}
```

---

### 2.5 Domain Objects — Phase 1.5, reuse existing controller

**Tab key**: `'domainObjects'`  
**Component**: `GraphDomainObjectsPanelComponent` (`app/components/graph-domain-objects-panel/`)  
**Service**: `GraphDomainObjectsService` (`app/services/graph-domain-objects.service.ts`)  
**Backend**: `OntologyDerivationController` at `/api/process/ontology/derive` — LIVE (`web/controllers/OntologyDerivationController.java:53`). Also `GET /api/kb-grounding/derived-rules?factSheetId={id}&minConfidence={c}` in `KbGroundingController` for grounded rules (PSL-derived rules with confidence).

**UX flow**:

1. "Derive Domain Objects" button → `POST /api/process/ontology/derive` (async, polls `GET /api/process/ontology/derive/jobs/{jobId}`) → on completion, shows derived `OntologySchema` entities as cards (entity type, description, example instances from graph).
2. "Derived Rules" section → `GET /api/kb-grounding/derived-rules?factSheetId=X&minConfidence=0.5` → shows PSL rules that were induced from the graph data (head predicate, body predicates, confidence weight).
3. Each derived entity card has a "View in Entity Browser" cross-link that sets `factSheetId` and switches to `entityBrowser` sub-tab. Each rule card has a "Verify in Grounding Console" cross-link.

**Relationship to existing Ontology panel**: the existing `graph-ontology-panel` manages saved `OntologySchema` binding (Phase 6 governance). This new panel shows the derivation workflow and grounded PSL rules — a different operation. Keep them separate.

---

### 2.6 Live Cascade / KB Status — Phase 2, gated on subscription backend

**Tab key**: `'cascadeStatus'`  
**Component**: `KbCascadeStatusComponent` (`app/components/kb-cascade-status/`)  
**Service**: `KbCascadeService` (`app/services/kb-cascade.service.ts`)  
**Backend gate**: `POST /api/kb-grounding/subscribe` returns HTTP 501 and `GET /subscribe/{id}/events` throws `UnsupportedOperationException` (`KbGroundingController.java:275,286`). Phase 2 SSE wiring is not complete. Additionally a `GroundingChangeEvent` publisher in `IncrementalReasoningOrchestrator` does not yet exist.

**Phase 2 backend work required**:
1. `KbSubscriptionRegistry` bean — stores active `SseEmitter` instances keyed by `subscriptionId`.
2. `IncrementalReasoningOrchestrator.runDelta()` publishes `GroundingChangeEvent` via `ApplicationEventPublisher` when a predicate in a registered subscription set is updated.
3. `@EventListener(GroundingChangeEvent.class)` in a `KbSseDispatcher` bean routes events to the matching `SseEmitter` instances.
4. Follow the existing `CrawlProgressSseController` pattern (`CrawlProgressSseController.java:64`): 30-minute timeout, heartbeat every 15 seconds.

**Phase 2 UI design** (do not implement until backend is live):
- Subscription form: predicate watch-list + fact-sheet selector → `POST /api/kb-grounding/subscribe` → connect `EventSource` to returned `eventsUrl`.
- Live event log: scrolling list of `GroundingChangeEvent` entries (atom, new verdict, confidence, timestamp). Color-coded by verdict change direction (UNKNOWN→SUPPORTED = green, SUPPORTED→REFUTED = red, etc.).
- KB version counter and stale indicator (poll `GET /api/kb-grounding/verify` with a known atom as a heartbeat, read `meta.kbVersion` from response).
- In Phase 1, show a disabled stub panel with "Cascade subscriptions require Phase 2 backend wiring" banner, so the tab exists but is not functional.

---

## 3. Phase Plan

### Phase 1 — ships with existing backend (no new controllers needed)

| Deliverable | Tab key | Backend dependency |
|---|---|---|
| `KbGroundingPanelComponent` + `KbGroundingService` | `'grounding'` | `/api/kb-grounding/*` LIVE |
| Add `asOf` date picker to Grounding Console | (within 'grounding') | already supported by `verify`+`query` |
| `GraphEmbeddingsPanelComponent` + `GraphEmbeddingsService` | `'embeddings'` | `/api/knowledge-graph/embeddings` LIVE — confirm scan scope |
| `GraphDomainObjectsPanelComponent` + `GraphDomainObjectsService` | `'domainObjects'` | `/api/process/ontology/derive` + `/api/kb-grounding/derived-rules` LIVE |
| GraphsHub wiring: 3 new tab entries + imports + type union additions | — | — |

### Phase 1.5 — requires minor backend addition (no new module)

| Deliverable | Backend work |
|---|---|
| `asOf` date picker in Grounding Console exposed as full Temporal panel | Add `POST /api/kb-grounding/temporal/snapshot` endpoint in `KbGroundingController` — 20-30 lines; delegates to existing `TemporalView.at()` |

### Phase 2 — gated on new backend controllers

| Deliverable | Backend gate |
|---|---|
| `GraphTypeBrowserComponent` (OWL/DL browser) | New `TypeSystemController` at `/api/knowledge-graph/type-system` — expose `TypeHierarchy`, RL inferences, inconsistency list from `OwlDlReasoningBridge` |
| `GraphTemporalPanelComponent` (full timeline slider) | `POST /api/kb-grounding/temporal/snapshot` returning all live facts at a point in time |
| `KbCascadeStatusComponent` (live SSE status) | `KbSubscriptionRegistry` + `GroundingChangeEvent` publisher + Phase 2 SSE in `KbGroundingController` |

---

## 4. GraphsHub Wiring Changes (summary)

**`graphs-hub.component.ts`**:

```typescript
// Line 45-48: extend GraphsTab
type GraphsTab = 'visualizer' | 'entityBrowser' | 'hierarchy' | 'builder'
  | 'eventObservation' | 'causalAttribution'
  | 'overview' | 'health' | 'ontology' | 'pipelines' | 'provenance' | 'diff'
  | 'maintenance' | 'rules' | 'extract' | 'patch' | 'eval' | 'io'
  // NEW — Phase 1
  | 'grounding' | 'embeddings' | 'domainObjects'
  // NEW — Phase 2
  | 'temporal' | 'typeBrowser' | 'cascadeStatus';

// New cross-navigation method (like onAttributeNode at line 140):
onVerifyNode(nodeId: string): void {
  if (!nodeId) return;
  this.groundingAtom = nodeId;   // new field, passed as @Input to KbGroundingPanelComponent
  this.activeSubTab = 'grounding';
}
```

**`graphs-hub.component.html`** — add after line 84 (`io` button):

```html
<!-- Phase 1 -->
<button class="sub-tab" [class.active]="activeSubTab === 'grounding'" (click)="selectSubTab('grounding')">
  <mat-icon class="tab-icon">verified</mat-icon>
  <span class="tab-label">Grounding</span>
</button>
<button class="sub-tab" [class.active]="activeSubTab === 'embeddings'" (click)="selectSubTab('embeddings')">
  <mat-icon class="tab-icon">hub</mat-icon>
  <span class="tab-label">Embeddings</span>
</button>
<button class="sub-tab" [class.active]="activeSubTab === 'domainObjects'" (click)="selectSubTab('domainObjects')">
  <mat-icon class="tab-icon">category</mat-icon>
  <span class="tab-label">Domain Objects</span>
</button>

<!-- Phase 2 (add when backend is live) -->
<button class="sub-tab" [class.active]="activeSubTab === 'temporal'" (click)="selectSubTab('temporal')">
  <mat-icon class="tab-icon">history</mat-icon>
  <span class="tab-label">Temporal</span>
</button>
<button class="sub-tab" [class.active]="activeSubTab === 'typeBrowser'" (click)="selectSubTab('typeBrowser')">
  <mat-icon class="tab-icon">account_tree</mat-icon>
  <span class="tab-label">Type System</span>
</button>
<button class="sub-tab" [class.active]="activeSubTab === 'cascadeStatus'" (click)="selectSubTab('cascadeStatus')">
  <mat-icon class="tab-icon">stream</mat-icon>
  <span class="tab-label">Cascade</span>
</button>
```

Content divs follow the same `*ngIf="activeSubTab === 'xxx'"` pattern as the existing 18 panels.

---

## 5. Key Implementation Notes

### 5.1 GlobalExceptionHandler — already patched

`GlobalExceptionHandler.java:42-48` already includes `"ai.kompile.app.web.controllers.grounding"` in `basePackages`. No change needed for the Grounding Console. Any future `TypeSystemController` in a new sub-package must also be added to `basePackages`.

### 5.2 Standalone component pattern

All new components MUST be declared `standalone: true` (same as every panel added since Phase 8). They import their own Angular Material modules and are added to `GraphsHubComponent.imports[]`, not to `app.module.ts` declarations. See `graph-health-panel.component.ts:35-52` for the canonical template.

### 5.3 DerivationTree recursive component

The `ExplainResponse.derivation` (`DerivationNode`) is a recursive tree structure. Build a separate `DerivationTreeComponent` that takes a single `@Input() node: DerivationNode` and renders its children recursively via `ng-content` or a self-referencing component. Use `mat-expansion-panel` (collapsible) with the atom as the header and confidence + rule as the detail. Mark leaves (no children) with a distinctive CSS class. This component is reused by the Grounding Console and potentially by future panels.

### 5.4 `KGEmbeddingController` scan scope

`KGEmbeddingController` is in `ai.kompile.knowledgegraph.embedding.controller` (a `kompile-knowledge-graph` module class). Verify it is reachable via `@SpringBootApplication(scanBasePackages=...)` or an explicit `@ComponentScan` in the app-main boot class before wiring the UI. If not scanned, expose it through an app-main facade controller in `ai.kompile.app.web.controllers` rather than adding a new scan root (adding scan roots is risky — it can sweep in beans not intended for production).

### 5.5 Error handling in Angular

All services must follow the pattern from `graph-rules-panel.component.ts` (check the `error` callback pattern). The `GlobalExceptionHandler` returns `{ error, message, type }` — Angular services should read `err.error.message` (not `err.message`) for display in `MatSnackBar`.

### 5.6 Bundle budget — current headroom

`angular.json:59-60`: `maximumError: "30MB"`. Three new lightweight panels (Grounding, Embeddings, Domain Objects) with Material components and no new chart libraries will each add roughly 50-100 KB to the bundle. Total new cost: ~300 KB — well under the ceiling. The `DerivationTreeComponent` adds ~30 KB. No budget bump needed for Phase 1. If Phase 2 adds a timeline slider with charting (`chart.js` or similar), bump to 35 MB.

---

## 6. Open Questions

**Q1 — `KGEmbeddingController` scan scope (Phase 1 blocker)**  
Is `ai.kompile.knowledgegraph.embedding.controller.KGEmbeddingController` already reachable in the Spring context of `kompile-app-main`? If not, the Embeddings panel needs an app-main facade. Verify via the existing integration test suite before wiring the UI.

**Q2 — GraphsHub nav overflow at 24 sub-tabs**  
At 18 sub-tabs the nav already scrolls horizontally. Adding 6 more (24 total) may make the tab bar unusable on narrow viewports. Consider grouping tabs into sections with section headers (e.g. "Explorer", "Reasoning", "Management") as non-clickable dividers in the HTML, without changing the routing logic. This is a CSS/UX decision, not an architecture change — defer to the first time a user reports the nav as overcrowded.

**Q3 — `asOf` in Grounding Console vs. dedicated Temporal panel**  
Should the `asOf` field be inline in the Grounding Console (simplest) or should we always intend to build the separate Temporal panel later? Decision: inline for Phase 1 (lower complexity, no new component), dedicated panel for Phase 2 only if user research shows temporal browsing is a common workflow distinct from single-atom verification.

**Q4 — Cross-panel "Verify this node" action in graph Visualizer**  
The `graph-visualizer.component.ts` is 3002 lines. Adding a new `(verifyNode)` output without breaking existing behavior requires care. Candidate insertion point: the right-click context menu or the "Details" side panel. The safe approach is to add a single `@Output() verifyNode = new EventEmitter<string>()` and emit on a new button in the node detail panel — one small addition to an already large file.

**Q5 — Domain Objects vs. Ontology panel overlap (user confusion)**  
Both `'ontology'` (existing `GraphOntologyPanelComponent`) and `'domainObjects'` (new) touch ontology concepts. The distinction: the Ontology panel manages the saved `OntologySchema` governance binding (Phase 6 — `PUT /api/process/ontology/binding`). The Domain Objects panel surfaces derivation (LLM-driven) and grounded rules (PSL-driven). Label the new tab "Knowledge Rules" instead of "Domain Objects" to reduce overlap confusion.

---

*End of design document.*
