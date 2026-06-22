# Ontology / Crawl / Enrichment Tie-In Audit

> Status: audit completed 2026-06-22. Read-only investigation — no code was changed.

---

## Executive summary

The ontology infrastructure (OWL-RL reasoner, `OntologySchema`, `GraphOntologyBindingService`,
`OntologyDerivationService`) and the crawl/extraction/enrichment pipeline
(`LlmKnowledgeGraphBuilder`, `GraphPersistenceHelper`, `TikaGenericGraphExtractor`,
`GraphHydrationOrchestrator`, `IncrementalReasoningOrchestrator`) are **almost entirely
disconnected**. Four verdicts:

| Question | Verdict |
|---|---|
| (a) Ontology guides extraction? | **NO** — extractor and LLM builder are ontology-blind |
| (b) Ontology validates/governs the graph during enrichment? | **PARTIAL** — conformance is reachable via REST + maintenance, never auto-called in the crawl pipeline |
| (c) OWL axioms fed into PSL/FOL reasoning? | **NO** — OwlRlReasoner exists but has zero callers in production |
| (d) Two schema worlds reconciled? | **NO** — OntologySchema and free-form `entity_type` never meet |

---

## Step 1 — OWL/Ontology infrastructure

### OntologySchema
File: `kompile-app/kompile-data/kompile-process/kompile-process-engine/src/main/java/ai/kompile/process/ontology/OntologySchema.java`

A pure POJO with `entityTypes` (`List<EntityTypeDefinition>`), `relationshipTypes`
(`List<RelationshipTypeDefinition>`), and `globalRules` (`List<ValidationRule>`). Persisted to
`~/.kompile/processes/ontologies/` as versioned JSON. **No dependency on the knowledge-graph
module** — it is a `kompile-process-engine` artifact.

### OwlRlReasoner / OwlDlReasoningBridge
Files:
- `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/mebn/type/owl/OwlRlReasoner.java`
- `kompile-app/kompile-data/kompile-graphs/kompile-reasoning-owl-bridge/src/main/java/ai/kompile/graph/reasoning/owl/bridge/reasoner/OwlDlReasoningBridge.java`

`OwlRlReasoner` is a complete OWL 2 RL forward-chaining reasoner (transitive closure via BFS +
FOL rule compilation). `OwlDlReasoningBridge` wraps Openllet for full DL reasoning. Both implement
`OwlReasoner` SPI and take an `OwlOntology` TBox + `ReasoningGraph` ABox. They are **tested in
isolation** (`OwlRlReasonerTest`, `ExternalOwlImporterTest`, `OwlDlSubsumptionTest`) but have
**zero callers in production code** outside their own modules:

```
$ grep -rn "OwlRlReasoner|OwlDlReasoningBridge|ExternalOwlImporter" kompile-app/ \
    --exclude-dir=test --exclude-dir=kompile-graph-reasoning \
    --exclude-dir=kompile-reasoning-owl-bridge
# → 0 matches
```

The `kompile-reasoning-owl-bridge` module is a sub-module of `kompile-graphs` and is built, but
it is not declared as a dependency by `kompile-knowledge-graph`, `kompile-crawl-graph`,
`kompile-app-main`, or any other consumer.

### OntologyDerivationService / OntologyDerivationController
Files:
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/ontology/OntologyDerivationService.java`
- `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/web/controllers/OntologyDerivationController.java`

`OntologyDerivationService.derive()` reads the **post-crawl graph** (top concepts via
`FactSheetGraphService.getTopConcepts`, entity labels, edge type counts) and uses an LLM or
structural fallback to produce an `OntologySchema`. The returned schema is always an **unsaved
draft** (`metadata.draft=true`). The controller exposes `POST /api/process/ontology/derive` (sync)
and `POST /api/process/ontology/derive/async`. Direction is strictly **graph → ontology**; the
reverse does not exist.

### GraphOntologyBindingService
File: `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/ontology/GraphOntologyBindingService.java`

Implements `GraphConformanceChecker` SPI. Exposes:
- `resolveActiveOntology(factSheetId)` — priority 1: `NamedGraph.ontologySchemaId`; priority 2:
  `ProcessDefinition.ontologySchemaId` for the fact sheet.
- `checkConformance(factSheetId)` — validates all `NodeLevel.ENTITY` nodes and semantic edges
  against the bound ontology. Returns a `GraphConformanceReport` with a `conformanceScore` (0..1).
- `bindOntology(factSheetId, id, version)` — stamps `ontologySchemaId`/`ontologyVersion` on the
  fact sheet's `NamedGraph`.

REST surface: `GET /api/process/ontology/conformance`, `PUT /api/process/ontology/binding`,
`DELETE /api/process/ontology/binding` (via `OntologyConformanceController`).

### NamedGraph
File: `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/domain/NamedGraph.java` (line 114)

Has `String ontologySchemaId` and `Integer ontologyVersion` fields (added as part of Phase 6,
commit `7972de598`). Also has `String schemaJson` (line 105) described as "Optional JSON schema
defining allowed node/edge types" which is **inert — never read** during write or enrichment
paths.

### Frontend ontology components
- `kompile-app-main/src/main/frontend/src/app/components/graph-ontology-panel/graph-ontology-panel.component.ts`
- `kompile-app-main/src/main/frontend/src/app/services/graph-ontology.service.ts`
- `kompile-app-main/src/main/frontend/src/app/components/process-engine/process-ontology.component.ts`

The `GraphsHub` (Graphs tab, Phase 8) wires an Ontology tab backed by `graph-ontology-panel`
which calls `PUT/DELETE /api/process/ontology/binding` and `GET /api/process/ontology/conformance`.
These are operator-invoked, not pipeline-auto-invoked.

---

## Step 2 — Crawl/extraction/enrichment pipeline classes

### LlmKnowledgeGraphBuilder
File: `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/builder/impl/LlmKnowledgeGraphBuilder.java`

The LLM extraction prompt (line 341) lists entity types from `BuilderConfig.entityTypes()`:

```java
String entityTypes = config.entityTypes() != null
    ? String.join(", ", config.entityTypes())
    : "PERSON, ORGANIZATION, LOCATION, CONCEPT";
```

`BuilderConfig` is populated from crawl config, not from `OntologySchema`. There is **no
injection of the bound ontology's `entityTypes` list into the prompt**. The free-form entity
types returned by the LLM are stored as-is in `GraphNode.metadataJson` under `entity_type`.

### GraphPersistenceHelper
File: `kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/GraphPersistenceHelper.java`

`persistConstructedGraphBatch()` (line 87) maps entity type strings to `NodeLevel` via
`nodeLevelForEntityType()` (line 605) — a hardcoded switch over a few structural types
(`TABLE`, `SHEET`, etc.). The entity's semantic type (from the LLM) goes directly into
`metadataJson.entity_type` with no ontology lookup (lines 139–160). **No conformance gate,
no allowed-type filter.** Evidence provenance metadata (`GraphProvenanceKeys.*`) is written
(lines 431–441) but carries no ontology reference.

### TikaGenericGraphExtractor
File: `kompile-app/kompile-data/kompile-loaders/kompile-loader-tika/src/main/java/ai/kompile/loader/tika/TikaGenericGraphExtractor.java`

Assigns hardcoded structural entity types (`ENTITY_EPUB_DOCUMENT`, `ENTITY_AUDIO_DOCUMENT`, etc.)
based on document media type. No reference to `OntologySchema` anywhere in the file.
Zero grep matches for `ontology`, `OntologySchema`, `allowedTypes`, or `constrainedBy`.

### UnifiedCrawlGraphServiceImpl
File: `kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/UnifiedCrawlGraphServiceImpl.java`

Zero grep matches for `ontology`, `OntologySchema`, `conformance`, or `GraphOntologyBinding`.
The crawl pipeline is fully ontology-blind.

### GraphHydrationOrchestrator
File: `kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/GraphHydrationOrchestrator.java`

Sequences three enrichment stages (lines 153–265):
1. `DERIVATION` — calls `IncrementalReasoningOrchestrator.runFullReground(factSheetId)`
2. `PRUNE_COMPACT` — calls `PruneCompactOrchestrator.run(...)` which internally invokes
   `GraphHealthService.persistSnapshot(factSheetId)`.
3. `HEALTH` — sentinel (no-op; covered by PRUNE_COMPACT's PH(post) step).

**No direct call to `GraphOntologyBindingService` or `GraphConformanceChecker` inside the
hydration pipeline.** Conformance surfaces only as a field in the `GraphHealthSnapshot`
computed by `GraphHealthService` during PRUNE_COMPACT (see below).

### GraphHealthService (called during enrichment)
File: `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/GraphHealthService.java` (lines 147–158)

`computeSnapshot()` does call `GraphConformanceChecker.checkFactSheet(factSheetId)` if the
SPI bean is wired. This is the **only automatic conformance check** in the enrichment path, and
it fires indirectly (inside `PruneCompactOrchestrator` → `GraphHealthService.persistSnapshot`).
The result is stored in the health time series as a scalar `conformanceScore` and `ontologyBound`
flag, but **it does not gate or modify any nodes or edges** — it is observational only.

### IncrementalReasoningOrchestrator
File: `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/IncrementalReasoningOrchestrator.java`

`doReground()` (line 425) builds a `PslProgram` from the `FactStore` (PSL soft-truth facts
extracted from the graph by `GraphToFactStoreProjector`), runs `HlMrfMapInference.solve`, and
materializes `InferredFact`s. **No reference to `OntologySchema`, `OwlOntology`,
`OwlRlReasoner`, or any TBox axioms.** The PSL rules are either auto-generated soft-propagation
rules (`buildProgramFromFactStore`) or loaded from `<dataDir>/rules/*.psl` files — neither
source receives ontology-derived axioms.

---

## Step 3 — Pipeline classes: ontology reference audit

| Class | OntologySchema ref? | OWL axiom injection? | Conformance gate? |
|---|---|---|---|
| `LlmKnowledgeGraphBuilder` | No | No | No |
| `GraphPersistenceHelper` | No | No | No |
| `TikaGenericGraphExtractor` | No | No | No |
| `UnifiedCrawlGraphServiceImpl` | No | No | No |
| `GraphHydrationOrchestrator` | No | No | No |
| `IncrementalReasoningOrchestrator` | No | No | No |
| `GraphHealthService` | Via `GraphConformanceChecker` SPI (optional) | No | Observational only |
| `PruneCompactOrchestrator` | Indirectly via `GraphHealthService` | No | Observational only |

---

## Step 4 — OntologySchema content

`OntologySchema` (process-engine, line 39–53) stores:
- `entityTypes: List<EntityTypeDefinition>` — each has `name` (PascalCase string), `description`,
  `classification` (enum: REFERENCE/TRANSACTIONAL/PATTERN/CONTROL/METRIC/ACTOR), `confidence`,
  `fields` (`List<FieldDefinition>` with type, regex, min/max, `primaryKey`, `required`,
  `enumValues`, `fkReference`, `immutable`), `rules` (`List<ValidationRule>` with SpEL + severity).
- `relationshipTypes: List<RelationshipTypeDefinition>` — source/target entity type names +
  cardinality enum.
- `globalRules: List<ValidationRule>` — cross-entity SpEL rules.

Persistence: `~/.kompile/processes/ontologies/` JSON files. Loaded in memory by
`ProcessEngineServiceImpl` (`Map<String, OntologySchema> ontologies`). Validation only runs in
`validateRunDataAgainstOntology()` (line 2445) against `WorkflowRun.runData` map entries — never
against `GraphNode.metadataJson`.

---

## Step 5 — GraphOntologyBindingService / resolveExplicitGraphBinding: call sites

`resolveExplicitGraphBinding` is called only from `resolveActiveOntology` (line 87). All callers
of `resolveActiveOntology` / `checkConformance` / `bindOntology` live in:

1. `OntologyConformanceController` — REST-only, operator-invoked.
2. `GraphHealthService.computeSnapshot()` — called from `PruneCompactOrchestrator` during
   enrichment PRUNE_COMPACT stage. Result is observational (stored in health snapshot).

There is **no write-time hook** at `KnowledgeGraphService.createNode` / `createEdge` that calls
the SPI — that hook is explicitly listed as deferred in `ontology-enrichment-roadmap.md`:
> "Write-time validation hook at the `KnowledgeGraphService` seam — call the now-existing
> `GraphConformanceChecker` SPI (optional bean) from `createNode`/`createEdge`; config-gated,
> default observe-only (tag, don't block) to protect ingest. (Deferred…)"

---

## Step 6 — IncrementalReasoningOrchestrator: OWL axiom injection

`doReground()` builds the PSL program from `FactStore` atoms (lines 838–895) via
`buildProgramFromFactStore`. The only external rule source is `loadProjectPslRules()` (line 782)
which reads `*.psl` files from `<dataDir>/rules/`. Neither path has any mechanism to:
- Load an `OwlOntology` from the bound `OntologySchema`.
- Compile OWL axioms to `FolRule`s via `OwlRlRuleCompiler`.
- Call `OwlRlReasoner.reason()` or `OwlDlReasoningBridge.reason()`.

`OwlRlReasoner` exists and is complete, but has **zero callers in production**. The bridge module
(`kompile-reasoning-owl-bridge`) is a declared sub-module of `kompile-graphs` but is not a
compile-time dependency of any production module that participates in the enrichment pipeline.

---

## Key question verdicts

### (a) Is ontology used to GUIDE extraction?

**VERDICT: NO.**

`LlmKnowledgeGraphBuilder.createExtractionPrompt()` (line 328) uses
`BuilderConfig.entityTypes()` — a list from crawl configuration — not from `OntologySchema.entityTypes`.
`TikaGenericGraphExtractor` uses hardcoded structural type strings. Neither extractor has any
mechanism to receive the bound ontology's allowed entity/relationship types and inject them into
the prompt or type filter.

Evidence:
- `LlmKnowledgeGraphBuilder.java:337`: `"PERSON, ORGANIZATION, LOCATION, CONCEPT"` hardcoded default.
- `GraphPersistenceHelper.java:139`: `entityMeta.put("entity_type", entityType)` — raw LLM string, no validation.
- Zero grep matches for `OntologySchema` or `ontologySchemaId` in `kompile-crawl-graph` or `kompile-loader-tika`.

### (b) Is ontology used to VALIDATE/GOVERN the resulting graph during enrichment?

**VERDICT: PARTIAL — observational only, not gating.**

`GraphHealthService.computeSnapshot()` calls `GraphConformanceChecker.checkFactSheet()` which
triggers `GraphOntologyBindingService.checkConformance()`. This fires automatically during the
PRUNE_COMPACT enrichment stage (via `PruneCompactOrchestrator → GraphHealthService`). The
`conformanceScore` (0..1) is persisted in the health time series and surfaced in the
`GraphHealthSnapshot`. However:

- Non-conforming nodes are **not tagged, quarantined, or rejected** — the result is observational.
- `PruneCompactBudget` reads `conformanceScore` (line 58) to budget pruning aggressiveness, so
  conformance does weakly influence the pruning pass — this is the only downstream effect.
- The write-time hook is **explicitly deferred** in the roadmap.

### (c) Is ontology injected into REASONING (OWL-RL entailment fed into PSL/FOL)?

**VERDICT: NO.**

`IncrementalReasoningOrchestrator.doReground()` builds the PSL program purely from FactStore
atoms + optional `.psl` rule files. `OwlRlReasoner`, `OwlDlReasoningBridge`, and
`ExternalOwlImporter` have no production callers. The `kompile-reasoning-owl-bridge` module is
built but not injected into any enrichment-pipeline Spring context.

The mebn-type-system-ontology-bridge.md document and `owl-dl-client-bridge-design.md` in
`docs/architecture/` describe the planned integration but it has not been wired into the
orchestrator.

### (d) Are the two schema worlds reconciled?

**VERDICT: NO.**

`OntologySchema.entityTypes` (rich, versioned, in `process-engine`) and the free-form
`GraphNode.metadataJson.entity_type` strings (from the LLM, stored in the matrix/vector store)
live in completely separate worlds:

- No mapping table or registry translates LLM entity type strings to `OntologySchema` entity type names.
- `GraphNodeTypes.resolveEntityType()` (app-core) unifies `entity_category → entity_type → entity_subtype`
  precedence, but this is a metadataJson-internal resolver, not a bridge to `OntologySchema`.
- `GraphConformanceChecker.checkConformance()` calls `OntologyConformanceValidator.validateEntity()`
  which looks up the entity type name in `OntologySchema.entityTypes` by string equality. This is
  the one place the two worlds touch — but only for already-bound fact sheets invoked via REST or
  the health snapshot path.

---

## IS vs IS NOT connected table

| Capability | IS connected | IS NOT connected |
|---|---|---|
| OntologySchema persisted in process-engine | YES (JSON files) | |
| NamedGraph.ontologySchemaId binding field | YES (since Phase 6) | |
| REST conformance check (operator-invoked) | YES (`/api/process/ontology/conformance`) | |
| Health snapshot includes conformanceScore | YES (observational, PRUNE_COMPACT path) | |
| OntologySchema entity types guide LLM extraction prompt | | YES — completely absent |
| OntologySchema allowed types filter extractor output | | YES — no filter gate |
| Write-time createNode/createEdge conformance hook | | YES — explicitly deferred |
| OWL-RL axioms compiled to PSL rules in re-ground | | YES — OwlRlReasoner has 0 production callers |
| OwlDlReasoningBridge wired into Spring context | | YES — module built but not imported |
| entity_type ↔ OntologySchema type name mapping | | YES — string equality only if binding exists |
| `NamedGraph.schemaJson` used during writes | | YES — inert field, never read |
| ProcessEngineService ontology validation touches graph nodes | | YES — validates WorkflowRun.runData only |

---

## Root cause of the disconnect

The two worlds were built by independent workstreams with incompatible seams:

1. **Module boundary mismatch.** `OntologySchema` lives in `kompile-process-engine`;
   `KnowledgeGraphService` lives in `kompile-knowledge-graph`. They are siblings — neither
   depends on the other. Only `kompile-app-main` can see both, so any binding/validation must
   live there. The extraction pipeline (`kompile-crawl-graph`, `kompile-loader-tika`) sees
   neither module — it has no path to the ontology.

2. **Derivation is one-way.** `OntologyDerivationService` reads the graph to produce an ontology
   draft. There is no return path: the draft ontology is not automatically bound to the fact sheet,
   and even if it were, no mechanism would re-read it during the next crawl to constrain extraction.

3. **OWL-RL infrastructure exists but is disconnected.** `OwlRlReasoner` and `OwlDlReasoningBridge`
   are complete and tested. `IncrementalReasoningOrchestrator` runs PSL MAP inference with
   auto-generated soft-propagation rules. The bridge from `OntologySchema → OwlOntology →
   OwlRlRuleCompiler → FolRuleSet → PslProgram` exists as documented design (see
   `owl-dl-client-bridge-design.md`) but the wiring step is absent.

4. **`NamedGraph.schemaJson` was a placeholder.** The field was modeled early as a schema anchor
   but never populated or consulted. `ontologySchemaId` (added Phase 6) is the correct FK but
   the write/enrichment paths do not read it.

---

## Phased plan

### Phase 1 — Ontology-guided extraction

**Goal:** When a fact sheet has a bound ontology, inject its entity types and relationship types
into the LLM extraction prompt so the LLM is constrained to the schema.

**Changes:**
1. Add `Optional<OntologySchema> boundOntology` to `BuilderConfig` (or pass it as a parameter
   to `LlmKnowledgeGraphBuilder.buildFromChunks`).
2. In `LlmKnowledgeGraphBuilder.createExtractionPrompt()`, when `boundOntology` is present,
   replace the hardcoded entity-type list with `boundOntology.getEntityTypes().stream().map(EntityTypeDefinition::getName)`.
3. In `UnifiedCrawlGraphServiceImpl` (or the crawl coordinator), before starting extraction,
   call `GraphOntologyBindingService.resolveActiveOntology(factSheetId)` and pass the result to
   `BuilderConfig`.
4. For `TikaGenericGraphExtractor`, add an optional `allowedEntityTypes` set; when non-empty,
   only emit entity nodes whose `entityType` is in the allowed set (lenient by default — log
   but do not drop unknown types unless enforcement mode is STRICT).

**Files:** `LlmKnowledgeGraphBuilder.java`, `BuilderConfig.java` (app-core), `UnifiedCrawlGraphServiceImpl.java`, `TikaGenericGraphExtractor.java`

**Verification:** Unit test — `BuilderConfig` with a mock `OntologySchema` → prompt contains
the ontology entity type names. Integration test — crawl a fact sheet with a bound ontology →
graph nodes' `entity_type` values are a subset of the ontology's entity type names.

---

### Phase 2 — Conformance validation during enrichment

**Goal:** After every crawl's ENRICHMENT step, automatically flag non-conforming nodes (tag
`conformance=VIOLATION` in `metadataJson`) so operators and downstream consumers see schema
violations without manual REST invocation.

**Changes:**
1. Add a `ONTOLOGY_CONFORMANCE` stage to `HydrationConfig`/`GraphHydrationOrchestrator`
   between PRUNE_COMPACT and HEALTH.
2. In that stage call `GraphConformanceChecker.checkFactSheet(factSheetId)` and for each
   `NodeViolation` in the report, call `KnowledgeGraphService.updateNodeMetadata(nodeId,
   Map.of("conformance", "VIOLATION", "conformanceViolations", ...))`. Use LENIENT mode by default
   (tag, do not block or delete).
3. Expose `SchemaEnforcementMode` (LENIENT/STRICT) in crawl config; in STRICT mode, drop
   non-conforming nodes from the graph during this stage instead of tagging.
4. The write-time hook (`createNode`/`createEdge`) should also call the optional
   `GraphConformanceChecker` SPI — this is the already-designed but deferred item in the roadmap.

**Files:** `GraphHydrationOrchestrator.java`, `HydrationConfig.java`, `KnowledgeGraphService.java`
(seam call), `UnifiedCrawlGraphServiceImpl.java` (config)

**Verification:** Unit test — `GraphHydrationOrchestrator` with a mock `GraphConformanceChecker`
that returns 2 violations → both nodes get `conformance=VIOLATION` metadata. Assert non-blocking
in LENIENT mode; assert node count decreases in STRICT mode.

---

### Phase 3 — OWL-RL injection into the reasoning program

**Goal:** When a fact sheet has a bound `OntologySchema`, compile its entity type hierarchy,
disjointness constraints, and relationship cardinality rules into `FolRule`s / `PslRule`s and
feed them into `IncrementalReasoningOrchestrator.doReground()` before the MAP solve.

**Changes:**
1. Create an `OntologyToPslRuleCompiler` (new class, `kompile-knowledge-graph` or `kompile-crawl-graph`)
   that takes an `OntologySchema` and produces a list of `PslRule` strings:
   - For each `RelationshipTypeDefinition` with `ONE_TO_ONE`/`ONE_TO_MANY` cardinality: emit a
     soft cardinality rule constraining the count of matching edges.
   - For each `EntityTypeDefinition` with `ValidationRule`s: translate SpEL assertions to FOL
     predicates (where possible).
   - For each pair of entity types that are semantically disjoint (by business domain convention):
     emit an `owl:disjointWith`-style inconsistency rule.
2. In `IncrementalReasoningOrchestrator.doReground()`, after `loadProjectPslRules(program)`,
   check whether the fact sheet has a bound ontology (via the `GraphConformanceChecker` SPI or
   a new `OntologyRuleProvider` SPI) and if so call `OntologyToPslRuleCompiler.compile(schema)`
   and add the resulting rules to the program.
3. Wire `OwlRlReasoner` for pure OWL-RL entailment: create a `GraphToReasoningGraphProjector`
   that projects the `FactStore` into a `ReasoningGraph`, then call
   `OwlRlReasoner.reason(graph, owlOntology)`, and inject the `OwlRlResult.inferredTypes()` and
   `inferredRelations()` back into the `FactStore` before the MAP solve (so PSL can propagate
   from OWL-entailed facts).

**Files:** `IncrementalReasoningOrchestrator.java`, new `OntologyToPslRuleCompiler.java`,
new `OntologyRuleProvider` SPI in `kompile-app-core`

**Verification:** Unit test — `IncrementalReasoningOrchestrator` with a `FactStore` containing
3 entities + a mock `OntologyRuleProvider` returning a transitivity rule → MAP solve produces
transitive closure edges in `InferredFactStore`. Test that the OWL-RL path is skipped when no
ontology is bound.

---

### Phase 4 — Reconcile entity_type with OntologySchema

**Goal:** Establish a canonical mapping between the free-form `entity_type` strings that the LLM
and Tika produce and the `OntologySchema.entityTypes[].name` values that conformance checking
uses, so that type-based queries, visualizer coloring, RAG boosts, and PSL rule templates all
use a consistent vocabulary.

**Changes:**
1. Add an `EntityTypeMappingService` in `kompile-app-main` (or as an SPI in `kompile-app-core`):
   given a raw `entity_type` string and a fact sheet id, return the canonical
   `OntologySchema.EntityTypeDefinition` (by case-insensitive name match, then fuzzy match,
   then `UNKNOWN`).
2. Wire `EntityTypeMappingService` into `GraphOntologyBindingService.extractEntityType()` so
   conformance checking uses the mapped name rather than the raw LLM string — this directly
   improves the conformance signal quality without requiring re-crawl.
3. Have `OntologyDerivationService.derive()` optionally auto-bind the derived draft ontology
   to the fact sheet's `NamedGraph` (behind a `autoBind=true` request flag) so that the cycle
   `crawl → derive → bind → conformance` can be triggered programmatically.
4. Populate `NamedGraph.schemaJson` from the bound ontology's entity type names/classifications
   so that tools that read `schemaJson` (currently none, but the field was designed for this) can
   use it without a process-engine dependency.

**Files:** `GraphOntologyBindingService.java`, `OntologyDerivationService.java`,
`NamedGraphServiceImpl.java`, new `EntityTypeMappingService.java`

**Verification:** Unit test — `EntityTypeMappingService` with a schema containing `"Invoice"` →
maps `"invoice"`, `"INVOICE"`, `"invoiceEntity"` to the `Invoice` `EntityTypeDefinition`.
Integration test — derive + auto-bind → conformance check shows lower `unknownTypeCount`.

---

## File reference index

| File path | Role |
|---|---|
| `kompile-app/kompile-data/kompile-process/kompile-process-engine/src/main/java/ai/kompile/process/ontology/OntologySchema.java` | Schema POJO — entity/rel types, validation rules |
| `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/src/main/java/ai/kompile/graph/reasoning/mebn/type/owl/OwlRlReasoner.java` | OWL 2 RL reasoner — complete, 0 production callers |
| `kompile-app/kompile-data/kompile-graphs/kompile-reasoning-owl-bridge/src/main/java/ai/kompile/graph/reasoning/owl/bridge/reasoner/OwlDlReasoningBridge.java` | OWL DL bridge (Openllet) — complete, 0 production callers |
| `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/ontology/OntologyDerivationService.java` | Graph → ontology derivation (one-way) |
| `kompile-app/kompile-app-parent/kompile-app-main/src/main/java/ai/kompile/app/ontology/GraphOntologyBindingService.java` | Conformance checker + binding manager |
| `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/domain/NamedGraph.java` | Graph registry row — `ontologySchemaId`, `schemaJson` (inert) |
| `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/builder/impl/LlmKnowledgeGraphBuilder.java` | LLM extraction — ontology-blind |
| `kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/GraphPersistenceHelper.java` | Entity/edge persistence — ontology-blind |
| `kompile-app/kompile-data/kompile-loaders/kompile-loader-tika/src/main/java/ai/kompile/loader/tika/TikaGenericGraphExtractor.java` | Structural extraction — ontology-blind |
| `kompile-app/kompile-data/kompile-crawlers/kompile-crawl-graph/src/main/java/ai/kompile/crawl/graph/GraphHydrationOrchestrator.java` | Enrichment orchestrator — no ontology calls |
| `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/reasoning/IncrementalReasoningOrchestrator.java` | PSL/MEBN re-ground — no OWL axiom injection |
| `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/ai/kompile/knowledgegraph/maintenance/GraphHealthService.java` | Health snapshot — ONLY place conformance fires automatically |
| `kompile-app/kompile-data/kompile-process/kompile-process-engine/src/main/java/ai/kompile/process/service/ProcessEngineServiceImpl.java` | Ontology CRUD + WorkflowRun validation (not graph nodes) |
| `docs/architecture/ontology-enrichment-roadmap.md` | Pre-existing roadmap this audit extends |
