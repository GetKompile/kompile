# Ontology & Data Enrichment Roadmap

> Status: proposed (2026-06-19). Incremental — phases ship independently. Lead with **Workstream B**.

## Diagnosis

Kompile has **two type systems that never talk to each other**:

- **`OntologySchema`** (`kompile-process-engine`, `ai.kompile.process.ontology`) — rich and persisted:
  `EntityTypeDefinition` (classifications REFERENCE/TRANSACTIONAL/PATTERN/CONTROL/METRIC/ACTOR),
  `FieldDefinition` (type, regex, min/max, `fkReference`, `immutable`, `primaryKey`, `enumValues`),
  `RelationshipTypeDefinition` (cardinality, source/target type), `ValidationRule` (SpEL + severity),
  `ProvenanceCitation`, `ChangeRecord`. Versioned JSON files in `~/.kompile/processes/ontologies/`.
- **The knowledge-graph type system** (`kompile-knowledge-graph`, `domain/`) — two hardcoded enums:
  `NodeLevel` (8 structural tiers) + `EdgeType`. The *semantic* type (PERSON, VENDOR, SKU, ACCOUNT)
  is a **free-form string in `metadataJson.entity_type`**, straight from the LLM, validated by nothing.

The ontology is **derived *from* the graph but never flows *back* to govern it.** That one-way dead-end is
the root cause: no schema validation, no long-term graph benefit, downstream consumers stuck on structural
tiers, and enrichment passes that don't share a type vocabulary.

### Supporting facts (verified)

- `OntologyDerivationService.derive()` returns an **unsaved draft** (`metadata.draft=true`); only consumers
  are `ProcessDefinition.ontologySchemaId` / `AgentSpec.ontologySchemaId` — bare string FKs, no integrity.
- Validation runs **only against workflow `runData` maps** — `ProcessEngineServiceImpl.validateRunDataAgainstOntology`
  (~line 2300), non-blocking. Never touches a graph node/edge. `fkReference` / `cardinality` / `immutable`
  are modeled but unenforced even there.
- `NamedGraph.schemaJson` ("Optional JSON schema defining allowed node/edge types") is **inert** — never read.
- Extraction default is `SchemaEnforcementMode.LENIENT`/`NONE` (`application.properties:377`).
- **Latent bugs:** `MatrixKnowledgeGraphService.EDGE_TYPE_MAP` (line 71) covers 7 of 12 edge types; the other 5
  — including new `RESOLVES_TO` — silently alias to `"RELATED_TO"` (line 462) on the **@Primary** backend.
  `ContradictionDetector` reads `GraphEdgeRepository` (JPA) directly → dead on the active matrix backend.
  Stub maintenance tasks: `ENTITY_RE_RESOLUTION`, `STATS_REFRESH` (in the default scheduled list), `COMMUNITY_REBUILD`.
- Enrichment: hollow `ENRICHMENT` pipeline slot (fires async off `GraphBuildCompletedEvent` instead); two
  independent normalization passes; `BarcodeIdentityGraphService.materialize()` HTTP-only (never in the crawl);
  in-flight `EntityResolutionService` Neo4j-path-only; ~8 independent full ENTITY scans, each re-parsing metadata.

## Guiding principles (project constraints)

- **No hardcoded enums for domain types** — semantic types stay strings in `metadataJson`, governed by the JSON
  ontology. Do *not* add Java enums for entity types. (`feedback_no_hardcoded_enums`)
- **JSON config via UI/CLI only** — no Spring properties for behavior config. (`feedback_ui_config_only`)
- **Verify with self-contained unit tests** — compile + unit test each change. (`feedback_test_verification`)
- **Build only `kompile-app-main` + the specific dependency modules**, dependency modules first; never skip UI.
  Maven: `/home/agibsonccc/dev-apps/mvn/bin/mvn`, `install` (not `compile`), no `-am`.
- New controller packages → add to `GlobalExceptionHandler` `basePackages`; frontend reads `err.error.message`.
- If `kompile-app-main` references a new module, update the sample POM **and** `RagPomGenerator`.

## Dependency graph

```
B0  standalone fixes ............ no deps        ──► ship first
B1  maintenance infra ........... no deps
A   ontology governs graph ...... KEYSTONE       ──► needed by B2, C-normalizer, D
B2  ontology conformance ........ needs A        ──► completes B
C   enrichment coupling ......... orchestration parts independent; normalizer needs A
D   process triangle + consumers  needs A
```

Suggested order: **B0 → B1 → A → B2 → C → D** (C orchestration can interleave with A).

---

## Workstream B — Continuous ontology-driven maintenance  *(START HERE)*

Split into what ships now (no ontology dependency) and what completes after A.

### B0 — Standalone correctness fixes ✅ DONE (2026-06-19)

- [x] **Fix `EDGE_TYPE_MAP` data loss.** Cover all 12 `EdgeType` values in
  `MatrixKnowledgeGraphService` (line 71); remove the silent `"RELATED_TO"` fallback (line 462) — unmapped
  types become a logged error, not a silent alias. Protects the `RESOLVES_TO` barcode work on the @Primary store.
  *Verify:* unit test enumerating `EdgeType.values()` → distinct canonical strings; write→read round-trip for `RESOLVES_TO`.
- [x] **Fix `ContradictionDetector` backend.** Route through `KnowledgeGraphService` (store-agnostic seam) instead
  of `GraphEdgeRepository` so it works on the matrix backend.
  *Verify:* unit test — contradictory edges via `KnowledgeGraphService` on the matrix backend → detected.
- [x] **Broaden orphan detection** beyond `ENTITY` (`findOrphanNodeIds`) to SNIPPET/DOCUMENT/TABLE/ATTACHMENT/IDENTIFIER
  via a level-set parameter. *Verify:* unit test with one orphan per level.

### B1 — Maintenance infrastructure ✅ mostly DONE (2026-06-19)

- [x] **Implement `STATS_REFRESH`** — now a per-fact-sheet graph-health snapshot (active nodes, orphans,
  low-confidence nodes/edges) computed via the store-agnostic `KnowledgeGraphService`; read-only, returns the
  breakdown in the `TaskReport`.
- [x] **Implement `ENTITY_RE_RESOLUTION`** — re-runs `GraphCompactionService` for a fact sheet and reports deltas
  (entitiesMerged / edgesRedirected); honors `ReResolutionConfig` (threshold + mergeOnMatch) and never mutates on a dry run.
- [~] **Data-quality stats** — delivered *per fact sheet* via STATS_REFRESH (above). Adding the same counts to the
  *global* `getGraphStatistics` map is folded into **B2's drift report**, where it gains ontology/conformance context.
- [x] ~~**Cross-store consistency check**~~ **DEFERRED — won't build.** Investigation confirmed the matrix/vector
  store is the single source of truth ("no JPA write-through"); the JPA `GraphNode`/`GraphEdge` tables are
  intentionally empty in the live path, so a JPA-vs-vector check would compare against an empty store.
- [x] **All-fact-sheet maintenance** — `MaintenanceScheduler.enableAll()` enumerates fact sheets each run via the
  new store-agnostic `KnowledgeGraphService.findFactSheetIds()` and maintains each, isolating per-fact-sheet failures.
  Still disabled by default.

### B2 — Ontology conformance  *(depends on A)*

- [ ] **`ONTOLOGY_CONFORMANCE` maintenance task** — for each node/edge, resolve its semantic type via the A bridge,
  validate fields (type/regex/min/max/enum/required), relationship cardinality, FK references, immutability;
  emit a conformance report. Non-destructive (dry-run) by default; apply-mode flags/quarantines violators
  (set `stale` or a `conformance` metadata flag).
- [ ] **Drift detection** — compare graph type distribution against the ontology's declared types → "types in graph
  not in ontology" (add candidates) and "ontology types with no instances" (dead types). Feeds ontology evolution.
- [ ] **`COMMUNITY_REBUILD`** (clustering) — lowest priority, may defer.

---

## Workstream A — Ontology governs the graph  *(KEYSTONE)*

Where "automatic schema validation" actually lands. Types remain strings governed by JSON — no new enums.
Split into the reusable engine (A-1, `process-engine`) and the app-main bridge that wires it to live graph data (A-2).

> **Architectural facts (from investigation):** `kompile-process-engine` (OntologySchema) and `kompile-knowledge-graph`
> (graph + write paths) are *siblings* — neither depends on the other; only `kompile-app-main` sees both. `GraphSchema`
> (app-core) is label-names only (no field constraints). There is **no graph-level ontology binding** today — the only
> link is `ProcessDefinition.factSheetId → ontologySchemaId`. These shape A-2 (the write-time hook needs an SPI; a real
> graph→ontology binding field is the clean fix).

### A-1 — Conformance engine ✅ DONE (2026-06-19)

- [x] **`OntologyConformanceValidator`** (`kompile-process-engine`, `ai.kompile.process.ontology`) — pure/stateless:
  `validateEntity(schema, type, props)` (unknown-type + field constraints over a graph-node-shaped property map),
  `validateRelationship(schema, src, type, tgt)` (allowed? + cardinality), `withinSourceCardinality(...)`. The
  field-constraint logic is now the single source of truth — `ProcessEngineServiceImpl.validateFieldConstraint`
  delegates to it (`ProcessEngineServiceImplTest` 70/70 still green → behavior preserved). 9 unit tests.

### A-2 — App-main bridge (wires the engine to the graph) — IN PROGRESS

- [x] **Type-registry bridge + conformance check** ✅ (2026-06-19) — `GraphOntologyBindingService` (`kompile-app-main`)
  resolves the active ontology for a fact sheet (priority-1 explicit-binding hook → falls through to the
  `ProcessDefinition.factSheetId → ontologySchemaId` link, APPROVED/LIVE preferred), adapts each
  `GraphNode.getMetadata()` → property map, and runs `OntologyConformanceValidator` over the ENTITY nodes. Surfaced at
  `GET /api/process/ontology/conformance?factSheetId=` → `GraphConformanceReport`. 5 unit tests; app-main builds with UI.
  Scope = entity conformance; relationship/cardinality conformance deferred (graph edges carry structural `EdgeType`s,
  not the ontology's semantic relationship names — that mapping belongs with the typing work in D).
- [x] **Conformance SPI** ✅ (2026-06-19) — `GraphConformanceChecker` + `GraphConformanceSummary` (`kompile-app-core`);
  `GraphOntologyBindingService` implements it. The dependency-inversion seam so the knowledge-graph layer can trigger
  conformance without seeing `OntologySchema` — the foundation the write-hook and B2's maintenance task both build on.
- [ ] **Graph-level ontology binding field** — add `NamedGraph.ontologySchemaId`/`ontologyVersion` (+ a way to set it)
  so a fact sheet's graph can be bound explicitly; `GraphOntologyBindingService.resolveExplicitGraphBinding` already has
  the priority-1 hook waiting for it (currently returns empty → falls through to the process link).
- [ ] **Activate `NamedGraph.schemaJson`** — populate from the bound ontology; have `NamedGraphService` + write paths
  consult it. (NamedGraph has no `ontologySchemaId` today — adding a real graph-level binding field is the clean fix.)
- [ ] **Ontology-driven `SchemaEnforcementMode`** — STRICT/LENIENT pull allowed node/edge/entity types from the bound
  ontology (today `GraphSchema` is label-names only). LENIENT tags violations; STRICT drops/coerces. Default LENIENT.
- [ ] **Write-time validation hook** at the `KnowledgeGraphService` seam — call the now-existing
  `GraphConformanceChecker` SPI (optional bean) from `createNode`/`createEdge`; config-gated, default observe-only
  (tag, don't block) to protect ingest. (Deferred — overlaps active concurrent graph-write work.)
- [ ] **Persist + bind properly** — optional auto-persist of derived ontology as a draft version; FK existence check
  when binding `ProcessDefinition`/`NamedGraph`; fix unbounded in-memory derivation job map (eviction).

---

## Workstream C — Couple the enrichment passes

Orchestration/shared-context/barcode-wiring are independent; the unified normalizer needs A.

- [ ] **`EnrichmentContext`** — per-run object holding the loaded node set + a single parsed-metadata view; passes
  mutate the shared view, one flush. Replaces ~8 independent scans + multiple `ObjectMapper` parses.
- [ ] **Fill the real `ENRICHMENT` step** — invoke `DataEnrichmentService` from `UnifiedCrawlGraphServiceImpl` under
  `stepPlan.isRun("ENRICHMENT")` with proper `hardDependsOn` (ENTITY_RESOLUTION/EDGE_COMPUTATION); keep the event
  path as an ad-hoc fallback.
- [ ] **Wire barcode identity + in-flight resolution into the pipeline** — call `BarcodeIdentityGraphService.materialize()`
  after ENTITY_RESOLUTION in the crawl (not just HTTP); reconcile the Neo4j-only `EntityResolutionService` with the JPA path.
- [ ] **One ontology-aware normalizer** *(needs A)* — collapse `GraphCompactionService.normalize` +
  `EntityNormalizationService.toTitleCase`/`inferEntityType` into one normalizer that infers entity types from the
  A registry, replacing keyword heuristics and hardcoded `COMPATIBLE_TYPE_PAIRS`.
- [ ] **Propagate `ContextualChunkEnricher` output back to `GraphNode`** so graph and vector store stay consistent.

---

## Workstream D — Process↔ontology↔graph triangle + downstream consumers

Closes the open reverse arrows and converts A's semantic types into user-visible payoff. Needs A.

- [ ] **Process concepts as ontology types** — model activity/role/resource/event types as ontology classifications
  (have ACTOR/CONTROL/METRIC; add ACTIVITY/RESOURCE/EVENT). Typed role references on `ProcessStep`, not bare strings.
- [ ] **Real binding + snapshots** — validate `ontologySchemaId` on bind; make `WorkflowRun.ontologySnapshotId` a frozen
  content copy so mid-run ontology edits don't change the run.
- [ ] **Discovery feeds types back** — `ProcessDiscovery` contributes discovered entity/relationship/event types as
  ontology candidates (Graph→Ontology becomes bidirectional).
- [ ] **Light up consumers** (each unlocked by A's semantic types):
  - RAG: semantic-type scope/boost on `RagQuery`; replace hardcoded `ENTITY_MENTION_PATTERN` with ontology types.
  - Attribution (MEBN/PSL): type-conditioned CPT / rule templates keyed on ontology entity/edge types.
  - Query/traversal tools: semantic `entityType` param resolving via the bridge.
  - Agents: implement `AgentSpec.promptTemplate` placeholder substitution from the bound ontology (stored-not-consumed today).
  - Visualizer: color/group by semantic type; use `NamedGraph.ontologyType`.
  - Embeddings: type-constrained negative sampling.

---

## Verification & build notes

- Each task lands with self-contained unit tests (matrix-backend tests where the @Primary store is involved).
- Touched modules: `kompile-knowledge-graph`, `kompile-data-enrichment`, `kompile-crawl-graph`,
  `kompile-process-engine`, `kompile-app-main`. Build dependency modules before `app-main`; build only `app-main`
  (not the whole `kompile-app` parent); never `-Dskip.ui`.
