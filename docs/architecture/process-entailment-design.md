# Process Entailment — Business-Process Creation on the Reasoning Stack

**Status:** SHIPPED (2026-07-02) — module 280 tests green incl. 11 new; consumers (data-enrichment, app-main) compile
**Scope:** Make business-process creation *entail* structure using the reasoning library
(`kompile-graph-reasoning`), instead of merely reporting mining statistics. Successor to the
"Remaining" items of `process-mining-design.md` Phase 2, plus the confidence/KB unification the
reasoning-math workstreams delivered the tools for.

---

## 1. Problem — what "a lot's been built" still doesn't do

The LLM-free mining pipeline (Phase 1+2 of `process-mining-design.md`) discovers a sound process
tree, computes causal dependencies, runs live PSL and Bayesian inference, verifies step atoms
against the KB, and persists mined causal rules. But process **creation** still doesn't *entail*
anything. Concretely (verified against the code, refs as of this commit):

| # | Gap | Where |
|---|---|---|
| G1 | Causal / Bayesian / PSL signals computed during `discoverForFactSheet` are **display-only** — attached as `StructuredEvidence` but never shape `suggestion.confidence` | `MiningProcessDiscoveryService.java:206-293`; confidence set only in `ProcessTreeToSuggestion.java:189-198` |
| G2 | **Declare constraints are never used**: mined only on demand (`GET /api/process/mining/declare`), not in the creation path; never compiled into PSL/FOL; never persisted; never entail anything | `MiningProcessDiscoveryService.declareConstraints` (endpoint-only), `DeclareMiner.java` |
| G3 | **No entailment of unobserved structure**: nothing derives implied precedence (transitivity), suppresses cycles (antisymmetry), or reconciles constraint-implied order with observed timestamps | absent |
| G4 | Created processes are **not KB citizens**: `activity("X")` atoms are *verified* during conversion, but the discovered relational structure — `precedes(A,B)` — is never asserted into `KbGroundingService`, so `ask_graph_verify` cannot answer questions about the process | `ProcessTreeToSuggestion.java:135-162` verifies only unary `activity(...)` atoms |
| G5 | **No `PRECEDES` edges in the graph** — the explicit "Remaining" Phase-2 item ("materialize `PRECEDES` edges via `GraphEdge.relationType`") was never done, so attribution/retrieval cannot traverse discovered control flow | `process-mining-design.md:208-211` |
| G6 | **Control-flow semantics are lost at conversion**: `SuggestedStep` has no `dependsOn` / `conditionExpression`, so the accept path produces a `ProcessDefinition` with no step dependencies — the mined ordering evaporates | `ProcessSuggestion.java:149-181` |
| G7 | Perf: `ConformanceChecker.check(tree, log)` is re-run **per step** inside the conversion loop (same tree+log), O(steps × replay) | `ProcessTreeToSuggestion.java:141` |

Meanwhile the reasoning library has (and the process modules already depend on it):
`EntailmentEngine` (PSL/MEBN → `EntailmentRecord` with provenance), `FactStore`/`Fact` (with
carried `Opinion`), subjective-logic `Opinion` fusion (`averageFuse`/`consensus`/`conjoin`/
`discount`), `KbGroundingService` (`verify`/`verifyOpinion`/`assertFact`→contradictions/
`seedInferredFacts`, Lucene-durable), calibrators, and temporal primitives. None of it is used to
*create* better processes.

## 2. Design — the entailment layer

New package `ai.kompile.process.discovery.mining.entail`, pure/static like the rest of the mining
code (unit-testable without Spring), wired into `MiningProcessDiscoveryService.discoverForFactSheet`
and exposed for inspection via `GET /api/process/mining/entailment`.

```
mining/entail/
  ProcessEntailment.java          the engine: Declare+DFG → PSL program → EntailmentEngine → result
  ProcessEntailmentResult.java    entailed precedence pairs + temporal verdicts + fused opinions
  PrecedenceMaterializer.java     entailed/observed control flow → graph edges + KB facts
```

### 2.1 Declare → PSL compilation (the entailment core)

`ProcessEntailment.entail(EventLog, DirectlyFollowsGraph, List<DeclareConstraint>)`:

- Constants: one lowercase constant per activity (same trick as `ProcessPslInference`).
- Observed atoms (via `FactStore.applyToProgram`):
  - `Df(a,b)` — directly-follows dependency strength (Heuristics measure, clamped to [0,1]);
  - `Resp(a,b)` / `Prec(a,b)` / `Chain(a,b)` / `Nce(a,b)` — mined Declare constraint confidences;
  - facts carry `Opinion.fromBetaEvidence(satisfied, activated−satisfied)` so downstream fusion
    keeps the evidence mass, not just the point estimate.
- Targets: `Precedes(a,b)` for every ordered activity pair that is *reachable* in the DFG or
  mentioned by a constraint (not the full n² blow-up).
- Rules (weights from constraint confidence where applicable):
  - `w: Df(A,B) -> Precedes(A,B) ^2` — observed adjacency is evidence of precedence;
  - `w: Resp(A,B) -> Precedes(A,B) ^2`, `w: Prec(A,B) -> Precedes(A,B) ^2`,
    `w(higher): Chain(A,B) -> Precedes(A,B) ^2` — declarative constraints entail order;
  - `0.8: Precedes(A,B) & Precedes(B,C) -> Precedes(A,C) ^2` — **transitive entailment**: derives
    orderings never directly observed;
  - `1.5: Precedes(A,B) -> ~Precedes(B,A) ^2` — antisymmetry: suppresses cycles instead of
    letting loops masquerade as mutual precedence (loops stay visible via the process tree ↺);
  - `w: Nce(A,B) -> ~Precedes(A,B) ^2` and the symmetric rule — activities that never co-occur
    must not be ordered.
- Solve once with `EntailmentEngine.entailFromPsl(program, factStore)` → `EntailmentRecord`s with
  posterior, supporting facts, and activated rules (real provenance, reused for explanation).
- Classify each entailed `Precedes(a,b)`: **OBSERVED** (a DFG arc exists) vs **ENTAILED**
  (derived only via rules — the new information).

### 2.2 Temporal consistency (valid-time check)

For each entailed pair, compare against the event log's timestamps: over all traces containing
both activities, the fraction where a's event occurs before b's (stable `Trace.ordered()`
semantics; traces without timestamps abstain rather than vote). Result per pair:
`Opinion.fromBetaEvidence(ordered, reversed)`. A strong reversal (expectation < 0.5 with low
uncertainty) marks the pair **TEMPORALLY_REFUTED** — it is excluded from KB assertion and edge
materialization and surfaces as a warning on the suggestion. This is the guard that keeps
constraint-entailed order honest against valid time.

### 2.3 Opinion fusion — confidence is earned from *all* modalities (G1)

`ProcessTreeToSuggestion` keeps its grounded per-step calibration; the discover path then fuses
one `Opinion` per modality with `Opinion.averageFuse` (sources are dependent — same log — so
cumulative fusion would double-count):

- conformance: `fromSoftTruth(fitness × precision, #traces)`;
- grounding: the existing aggregate calibrated confidence, evidence-weighted by #steps;
- causal: `fromBetaEvidence(#significant arcs, #insignificant arcs)`;
- Bayesian: `fromBayesianPosterior(mean posterior, prior)`;
- entailment: consensus of entailed-pair posteriors, discounted by the fraction TEMPORALLY_REFUTED.

`suggestion.confidence = fused.expectation()`; a `StructuredEvidence` entry of type `ENTAILED`
records the per-modality breakdown (belief/disbelief/uncertainty each), so the UI shows *why*
the confidence is what it is.

### 2.4 Process facts become KB citizens (G4)

For every non-refuted pair above the assert threshold, seed
`precedes("<A>", "<B>")` into `KbGroundingService.seedInferredFacts(factSheetId, ...)` as
`InferredFact`s carrying the pair's posterior, its supporting observed atoms, and the ground rules
that fired (plus a `process-mining:<suggestionId>` provenance id) — so `verify()` answers
SUPPORTED and `explain()` can walk the derivation.

Two deliberate choices, discovered the hard way:
- **Not `assertFact`.** `DefaultKbVerifier` treats soft `FactStore` entries as priors, not
  verdicts (only *hard* facts count on that path), so soft asserts are invisible to `verify()`.
  Worse, `assertFact` publishes one `AgentFactAssertedEvent` per fact — one re-ground cascade per
  pair is wrong for a mined batch. The inferred-fact store is the verifier's first-class channel,
  is Lucene-durable in production, and versions per run so re-mining supersedes.
- Atom keys use activity display labels only — never node ids or paths (no-raw-ids mandate).
  Temporal contradictions surface as `CONTRADICTION` evidence on the suggestion and demote the
  pair from both seeding and materialization.

### 2.5 Materialize control flow into the graph (G5)

`PrecedenceMaterializer` writes edges through the batch API on the @Primary matrix store
(free-form `relationType` — **no EdgeType enum surgery**, exactly as `process-mining-design.md`
§4.5 prescribed):

- Instance level: for each trace, consecutive events with distinct graph nodes →
  `DIRECTLY_FOLLOWS` edge between those nodes (bounded by total events), metadata
  `{provenance: MINED, minedBy: PROCESS_MINING, suggestionId, dfCount}`.
- Activity level: ENTAILED pairs (not directly observed) → `PRECEDES` edges between the
  *representative* nodes of each activity (earliest-occurring node per activity), metadata
  `{provenance: INFERRED, entailedBy: PSL, posterior, suggestionId}` — mirroring the OWL has-a
  closure materialization pattern.
- Idempotent per suggestion re-run: existing edges with the same
  `(relationType, from, to, suggestion-scoped metadata)` are upserted, not duplicated.

### 2.6 Ordering survives acceptance (G6)

`SuggestedStep` gains `dependsOn` (list of step names), populated from (a) the process tree's
operator semantics (SEQUENCE/LOOP children chain via entry/exit boundaries; XOR/AND branches stay
independent — that is what the operators assert) and (b) high-confidence non-refuted ENTAILED
pairs within the suggestion's step set, cycle-guarded. The accept path
(`ProcessDiscoveryServiceImpl.acceptSuggestion`) translates the names to engine step ids, which
`ProcessEngineServiceImpl` already enforces at execution. `conditionExpression` for XOR gates
stays future work (needs an expression language decision).

### 2.7 Declare constraints persist with the causal rules

The creation path now mines Declare constraints inline (same log, no re-extraction) and hands
them to `MinedRulePersistenceService` alongside the causal PSL rules, so the staging/cascade
consumers of `<dataDir>/rules/` see the declarative process constraints too
(`ModelTrainedEvent(psl-mined)` unchanged).

## 3. Config

Everything ships **enabled** (project mandate: no default-off feature flags). Knobs are value
thresholds only:

| Property | Default | Meaning |
|---|---|---|
| `kompile.process.mining.entail.assert-threshold` | `0.7` | min posterior to assert `precedes` into the KB |
| `kompile.process.mining.entail.materialize-threshold` | `0.7` | min posterior for `PRECEDES` edge write-back |
| `kompile.process.mining.entail.declare-min-support` | `0.2` | Declare mining support floor (creation path) |
| `kompile.process.mining.entail.declare-min-confidence` | `0.66` | Declare mining confidence floor (creation path) |

## 4. Out of scope (deliberately)

- MEBN process theories (`entailFromMebn`): the noisy-OR Bayesian view already covers the
  probabilistic-activation question; an MTheory adds machinery without new answers here.
- PSL weight learning from conformance-labeled traces (`PslWeightLearningService`): v2 — needs a
  labeled corpus that doesn't exist yet.
- Dedicated entailment UI panels (DFG viewer, conformance dashboard — Phase 3 of the mining
  design). The suggestion-card surfacing below is done instead.

## 4b. Surfacing — the entailment outputs reach the suggestions UI (2026-07-03)

Mined+entailed suggestions were previously invisible in practice: the dashboard's Run Discovery
only invoked the legacy heuristic engine, and the auto-listener defaulted OFF. Now:

- **They get created**: `MiningAutoDiscoveryListener` defaults ON (`matchIfMissing = true`;
  disable with `kompile.process.mining.auto-discover=false`) — every graph build mines+entails.
  `POST /api/process/mining/discover-all` mines every fact sheet with a graph, enumerated through
  the new `KnowledgeGraphService.getFactSheetIdsWithGraphs()` seam (matrix override parses
  `factsheet_<id>` from the loaded-graph-id cache; falls back to `listGraphs()` under the
  subprocess store, so both deployment modes work). The UI's **Run Discovery** button runs BOTH
  engines (`forkJoin`, one failing never sinks the other).
- **They don't pile up**: a fresh mined suggestion supersedes earlier PENDING mined ones for the
  same fact sheet (accepted ones are history and stay).
- **They render** (`ProcessDiscoverySuggestionsComponent`): `ENTAILED` (violet — matches the
  graph's INFERRED accent), `FUSION` (teal), and `CONTRADICTION` (red) evidence chips; per-step
  `after X, Y` control-flow annotations from `dependsOn` (tooltip: mined/entailed) plus the
  `roleBinding` chip; `PROCESS_MINING` gets its own source icon. The fused confidence rides the
  existing badge; Bayesian posteriors ride the existing panel.

## 4c. Fact promotion + ontology creation (2026-07-03)

The audit found business processes were second-class citizens in both subsystems:

**Fact promotion.** The grounding cascade builds its PSL program from the *fact store* — but the
entailment layer wrote only to the *inferred* store, so process facts were verify-visible yet
reasoning-inert. Worse: the persisted mined rules (`w: Occurs("A") -> Occurs("B")`) had been
grounding against **zero atoms in every cascade since they shipped** — nothing anywhere asserts
`Occurs` facts, and `Term.parse` strips quoted rule constants so the ground-rule atom keys are
UNQUOTED (`Occurs(INVOICE)`), which a quoted fact key could never unify with anyway. Fixes:

- `KbGroundingService.assertFactsBatch(factSheetId, facts)` — one write-lock, one contradiction
  scan, ONE `AgentFactAssertedEvent` for the whole batch (one cascade re-ground, not N).
- `MiningProcessDiscoveryService.promoteProcessFacts` runs BEFORE conversion and batch-asserts:
  hard `activity("X")` existence facts (verify-facing → per-step grounding returns SUPPORTED
  instead of the all-UNKNOWN 0.1 floor — the "KB authoritative over the miner's own output"
  resolution from the domain-object-grounding design), soft **unquoted** `Occurs(X)` facts at
  trace support with Beta-evidence opinions (cascade-facing — mined rules finally fire), and soft
  `precedes("A", "B")` facts with fused opinions for assertable entailed pairs. Promotion
  contradictions surface as `CONTRADICTION` evidence on the suggestion.
- `ProcessAtoms` is the single definition of these keys — the assert side and the verify side
  (`ProcessTreeToSuggestion`) can no longer drift. Quoted = verify convention; unquoted = the
  post-`Term.parse` cascade convention.
- Cascade-derived facts stay in the inferred store; graph materialization of *cascade* output
  remains behind the deliberate `kbCascadeMaterializeInferredEnabled` operator flag (the
  feedback-guard decision from the reasoning-stack-usage workstream stands — process edges are
  already written directly by `PrecedenceMaterializer`).

**Ontology creation.** The structural derivation promotes only has-a-style edge types to
transitive object properties, runs before mining in the crawl flow, and short-circuits once an
ontology exists — so `PRECEDES`/`DIRECTLY_FOLLOWS` never reached the ontology.
`ProcessOntologyContributionService` (app-main) listens for the miner's
`ModelTrainedEvent(psl-mined)` and, via the same update-and-rebind path as post-OWL type
induction, ensures the governing ontology declares `PRECEDES` as a **transitive** object property
(OWL-RL then computes the precedence closure over the materialized instance edges — the OWL-side
complement of the PSL transitivity rule) and `DIRECTLY_FOLLOWS` as non-transitive (adjacency is
not order). Auto-provisions the structural ontology first when none is bound; idempotent
thereafter. Activity classes need no contribution — they project from `entity_type` values the
structural derivation and type induction already promote. `ProcessDefinition.ontologySchemaId`
remains the priority-2 binding seam it always was.

## 4d. Coherence gaps closed (2026-07-03, second pass)

- **Correlation feedback loop (bug).** Case correlation unioned over ALL edges — including the
  miner's own materialized `DIRECTLY_FOLLOWS`/`PRECEDES` and INFERRED-provenance edges — so each
  run reshaped the next run's cases. `EventLogExtractor` now filters derived edges (INFERRED
  provenance or mining relation types) before any correlation strategy sees them: cases are
  observed structure only.
- **Mega-process suggestions.** `TraceClusterer` (single-link union-find over activity-set
  Jaccard, variant-deduplicated) splits a fact sheet's log into per-process sub-logs;
  `discoverForFactSheet` mines each cluster into its OWN suggestion (rule persistence and the
  staging event stay once-per-fact-sheet; the store holds all suggestions; callers get the
  strongest). Knobs: `kompile.process.mining.cluster.jaccard-threshold` (0.2),
  `.max-processes` (6, largest-first, dropped clusters logged at WARN — never silent).
- **Learning loop.** `ProcessCalibrationService`: accept/dismiss decisions on mined suggestions
  (already captured in the store, previously feeding nothing) append to a durable outcome ledger
  and refit the Platt calibrator (≥5 labels; identity below that), persisted under
  `<dataDir>/rules/process-calibration.json`; the miner loads the fitted calibrator instead of
  recreating an identity sigmoid every run. `ProcessSuggestion.rawConformanceScore` carries the
  signal the labels pair with. Controller hooks: accept → label 1.0; dismissing a still-pending
  mined suggestion → label 0.0 (deleting accepted ones is cleanup, not feedback).
- **occurredAt reality check (no code change needed).** The 2026-07-02 "stamped but never
  persisted" P0 no longer holds: `OccurredAtParser` is lenient and shared; the email extractor
  stamps `occurredAt` on nodes AND relations; `RuleBasedDocumentGraphExtractor` lifts the earliest
  incident-relation time onto entity nodes (`deriveEntityOccurredAt`); the matrix store persists,
  reloads, and surfaces it (verified by its own tests). Residue: sources whose relations carry no
  dates yield undated events — entailment's temporal cross-exam abstains there by design.

Still open (unchanged): XOR `conditionExpression`, PSL weight learning from conformance labels
(note the cascade's structured-perceptron learner DOES now train over promoted process atoms),
Allen-interval concurrency evidence + trace-recency decay, activity alias unification (WP14b/KGE),
learned suggestion re-ranking via `synthesis/`, Phase-3 DFG/conformance panels.

## 4e. Real-crawl-shape validation (2026-07-03, `CorporateEmailCrawlIntegrationTest`)

Integration fixtures now mirror ACTUAL crawl output for a corporate mailbox — the email lane's
EMAIL_MESSAGE/PERSON/ORGANIZATION/ATTACHMENT entities with SENT_BY/SENT_TO/REPLIED_TO/
HAS_ATTACHMENT/BELONGS_TO labels (EXTRACTED provenance, occurredAt from `email.date`, SOURCE
crawl-root + CONTAINS), plus the extraction lane's body-derived business entities (MENTIONS,
occurredAt lifted). Building that shape exposed two reality gaps, both fixed:

- **Actors were activities and case-merging hubs.** `ActivityClassifier` excluded only
  spreadsheet scaffolding — on a real mailbox PERSON/ORGANIZATION became "steps", and shared
  people/org hubs unioned every thread into ONE mega-case (one trace → flower garbage). Fix,
  per OCPM doctrine (actors are resources): `ACTOR_RESOURCE_TYPES` are excluded from the
  activity projection, and `EventLogExtractor` drops actor-incident edges from case correlation.
  Role binding is unaffected — it reads the KB, not the log.
- **Carriers-only variants chained unrelated workflows.** A newsletter's `{Email Message}`
  activity set is a Jaccard subset of every cluster; single-link merged procurement and
  recruiting through it. `TraceClusterer` signatures now exclude
  `COMMUNICATION_SCAFFOLD_TYPES` (Email Message/Attachment/Document/…); carriers-only traces are
  excluded from mining (logged) when business-bearing variants exist, with graceful raw-set
  fallback for scaffold-only crawls (email lane without extraction).

Three scenarios, all green: (1) the mailbox yields exactly two coherent suggestions
(procurement 4 cases: Purchase Request→APPROVAL→Purchase Order→INVOICE; recruiting 3), zero
actor steps, no vocabulary cross-contamination, `precedes("Purchase Request", "INVOICE")`
SUPPORTED in the KB despite never being a directly-follows arc (Email Message interleaves —
genuinely entailed), the pair materialized as a PRECEDES edge, rules file written in the Occurs
vocabulary, `ModelTrainedEvent(psl-mined)` published; (2) degraded artifacts — undated threads,
phantom REPLIED_TO placeholder messages, snake_case junk entity types (prettified, never leaked
raw), duplicate person nodes — change nothing structurally; (3) re-mining with the first run's
materialized edges present in the graph is stable: same two workflows, identical vocabularies,
store supersedes.

## 4f. Label robustness + rendering (2026-07-03, third pass)

- **One activity per type, one visual style.** `displayLabel` now normalizes every machine-shaped
  label (all-caps, all-lower, snake_case) to Title Case with 1–2-char tokens kept as acronyms
  (`INVOICE`/`invoice`/`Invoice` → one "Invoice" activity; `PURCHASE_PO` → "Purchase PO";
  `JOB_APPLICATION` → "Job Application" — 3-char tokens are words, not acronyms). Case variants
  from different extractors merge instead of fragmenting the mined process (the cheap half of the
  alias-unification gap); human-authored mixed-case labels (sheet names like "Group P&L") still
  pass through untouched. Deterministic, so DFG keys and KB atom keys stay stable per input.
- **One sanitizer.** `DeclareConstraint`, `ProcessCausalAnalyzer`, and
  `MinedRulePersistenceService` all delegate to `ProcessAtoms.sanitize` (quotes AND commas →
  space): a label like {@code "Acme, Inc. Invoice"} can no longer produce unparseable rule text or
  atom keys that fail to unify (both the PSL rule parser and the atom-key parser split on commas).
- **Rendering** (`ProcessDiscoverySuggestionsComponent`): workflow steps show the activity NAME
  prominently (the redundant 'Discovered activity "X"' description moved to a tooltip), and
  structured evidence sorts by decision relevance — FUSION breakdown first, CONTRADICTIONs second,
  ENTAILED orderings third, the rest in produced order.
- Tests: `ActivityClassifierTest` is a superset of the pre-existing coverage (precedence chain
  entity_type → sheetName → NodeLevel, junk-slug prettification, sheet-name preservation) plus
  case-variant merging, acronym stability across case variants, actor/scaffold projection rules.
  Module suite 301 green; ng build green.

## 4g. Business-sounding processes + the LLM final step (2026-07-03, fourth pass)

Every mined suggestion is now a readable business process, and an LLM can finish it — without
ever becoming load-bearing:

- **Names** come from the flow's business endpoints via `ProcessNarrator.businessName`
  ("Purchase Request → Invoice process", "Claim Intake → Claim Payout process") — communication
  carriers can never be endpoints; multi-cluster runs suffix `(i/k, fact sheet N)`. No more
  "Mined process (fact sheet 77)" placeholders.
- **Every suggestion carries a narrative.** `ProcessNarrator.narrate` deterministically writes a
  coherent multi-sentence description from the mined structure only: what it is (cases,
  confidence), the flow in order, non-trivial dependency clauses ("Claim Payout does not start
  until Coverage Review and Claim Intake are complete."), roles, entailed orderings, and caveats
  from contradictions. `narrative`/`narrativeSource` live on `ProcessSuggestion`.
- **The LLM final step** (`LlmProcessNarrationService`, app-main): after each mining run
  (`ModelTrainedEvent(psl-mined)`), pending mined suggestions are re-narrated by the configured
  `LLMChat` from a structured prompt (steps in order, dependencies, roles, entailed orderings,
  contradictions, confidence — nothing else). A **grounding gate** requires every step name to
  appear in the prose; un-grounded output is rejected and the deterministic template stands, so
  the narrative field is always coherent and never hallucinated. `narrativeSource` says which
  ("TEMPLATE" vs "LLM"). On-demand re-narration via
  `POST /api/process/narration/fact-sheet/{id}` and `/suggestions/{id}`.
- **Custom categories proven end-to-end** (`CustomCategoryWorkflowIntegrationTest`): two entirely
  custom vocabularies (insurance claims CLAIM_INTAKE→…→CLAIM_PAYOUT, security incidents
  SECURITY_ALERT→…→POSTMORTEM_REPORT) with deliberately inconsistent casing across instances mine
  into two coherent, correctly-named, fully-narrated suggestions; case variants merge; the
  intake→payout ordering — never a directly-follows arc — is entailed and KB-SUPPORTED; payout
  waits for the offer via `dependsOn`. Nothing in the stack is hardcoded to known types.
- **UI**: the narrative renders as the first block of an expanded suggestion (accent-bordered,
  with a TEMPLATE/LLM source tag explaining the provenance on hover); the raw discovery
  description drops to secondary styling below it.

## 4h. Learned suggestion re-ranking (2026-07-03, fifth pass)

The last unclosed learning loop: accept/dismiss outcomes now train a logistic re-ranker over the
suggestion's full signal shape, reusing the reasoning lib's `synthesis/` scorer exactly as it was
designed for ("fixed fold vs learned fusion").

- **`SuggestionFeatures`** — one definition of the 10-dim vector (raw conformance, fused
  confidence, entailed mean posterior + count, contradiction share, causal dependency, Bayesian
  posterior, step/case counts, role coverage), all engineered into [0,1]; feature NAMES persist
  with the model and a mismatch on load means "feature space evolved — stale model ignored".
- **Training** — `ProcessCalibrationService.recordOutcome` now writes the feature vector into the
  outcome ledger (backward compatible: old Platt-only lines are skipped) and refits via
  `LogisticRegressionTrainer.fit` — gated on ≥5 outcomes AND ≥2 of EACH class (a one-class fit
  ranks nothing). Model persists to `<dataDir>/rules/process-ranker.json` with weights, feature
  names, and training log-loss for inspection.
- **Scoring** — fresh mining stamps `ProcessSuggestion.learnedScore` (+ a `LEARNED` evidence
  entry). It RANKS, never replaces: `discoverForFactSheet`'s best-pick and the
  `/suggestions` listing order by `learnedScore ?? confidence`; the fused confidence stays the
  calibrated belief. UI shows a ★ badge beside the confidence with the provenance on hover.
- End-to-end proven in `MiningEntailmentWiringTest`: mine → accept the real profile ×3 / dismiss
  weak profiles ×3 → re-mine → the fresh suggestion carries `learnedScore > 0.5` and the LEARNED
  evidence chip.

## 4i. Relation-schema resolution — labeled relations become OWL/MEBN metadata (2026-07-03, sixth pass)

Structural ontology derivation promoted entity types to classes but left relationships untyped
(placeholder `RELATES_TO` + name-hinted transitives, no domain/range) — so the richest consumers
ran blind: `OwlOntologyBridge` maps `sourceEntityType`/`targetEntityType` to `rdfs:domain`/
`rdfs:range` on OWL object properties, `ontologyAxioms()` emits DOMAIN/RANGE axioms to the
PSL/MEBN side, and conformance validation checks edge endpoints — all fed nothing.

`RelationSchemaResolutionService` (app-main) closes it: for every relation label observed on the
graph's edges (SENT_BY, BELONGS_TO, custom domain labels, the miner's PRECEDES/DIRECTLY_FOLLOWS)
it induces, at ≥3-edge support:

- **domain/range** from the dominant (source-type → target-type) pair at ≥60% share,
  alias-resolved onto the schema's canonical class names (PERSON → Person) with graceful
  fall-through for classes type induction hasn't promoted yet;
- **cardinality** from observed mean out/in degree (ONE_TO_ONE … MANY_TO_MANY);
- **richer metadata** under `relationResolution` on the definition: support, dominant-pair share,
  symmetry share (a fully-reversed relation is recorded as an `owl:SymmetricProperty` candidate
  until the bridge consumes the flag), degree averages, resolvedAt.

Merge semantics mirror type induction (update-and-rebind, version bump); human-authored
domain/range/cardinality are never overwritten — only gaps fill. Deterministic and idempotent.

Triggers: (a) `OntologySchemaEnrichmentService.generateSchemaAndTypes` runs it after type
induction — so canonical names/aliases exist for matching — and re-runs OWL classification when
either pass changed the schema (new domain/range must reach the TBox); (b) the process-mined
listener resolves after contributing PRECEDES/DIRECTLY_FOLLOWS, sequentially in the same handler
(never a second listener racing the rebind), so freshly materialized control-flow labels get
typed in the same breath.

## 4j. Suggestion provenance audit — HybridReasoner + relation metadata (2026-07-03, seventh pass)

Audit question: does every suggestion field come from the reasoning library's orchestration and
from relations carrying their full metadata? Source-of-truth map after this pass:

| Suggestion content | Source | Reasoner-orchestrated? | Relation metadata used? |
|---|---|---|---|
| steps/phases/order | Inductive Miner over the event log | deterministic core (by design not a reasoner concern) | via correlation |
| event times | node occurredAt, **falling back to earliest incident OBSERVED edge occurredAt** | — | ✅ NEW: relation `occurredAt` (the email lane stamps every relation) finally consumed directly; derived edges never date |
| case correlation | observed structure only | — | ✅ relationType + provenanceType + actor-endpoint filters |
| entailed precedence | `EntailmentEngine` (PSL) + temporal cross-exam | ✅ lib | atoms carry Beta-evidence Opinions from arc counts |
| **structural activation** | **`ProcessHybridActivation` → lib `HybridReasoner`** (NEW) | ✅ the ONE orchestrator: the mined structure becomes a `ReasoningGraph` — activity priors from start-share+support, **relation weights from mined metadata** (dependency strengths; entailed posteriors × temporal-opinion discount) — ranked under BOTH engines (PSL, Bayesian VE), hybrid = engine consensus | ✅ |
| confidence fusion | Opinion.averageFuse of grounded + causal + **hybrid** + entailment (hybrid subsumes the old separate Bayesian-average modality; noisy-OR fallback only when hybrid fails) | ✅ lib Opinion algebra | indirectly via hybrid |
| grounded verification / roles | DefaultKbVerifier / ConjunctiveQueryEngine | ✅ lib | — |
| learnedScore / narrative | synthesis scorer / narrator+LLM gate | ✅ lib / grounded | — |
| ontology metadata | relation-schema resolution (§4i) feeds OWL/MEBN | ✅ | ✅ per-label domain/range/cardinality/symmetry |

Remaining deliberate non-reasoner parts: the Inductive Miner/DFG statistics (the deterministic,
inspectable core the design mandates), and DFG arc strengths stay count-based (weighting arcs by
edge confidence would change miner semantics — noted, not done). Embeddings for activities would
turn the HybridReasoner's `semanticWeight` knob on; currently 0.

## 4k. Agentic synthesis — the MCP-tooled final step (2026-07-03, eighth pass)

Narration (§4g) was a single-shot `LLMChat` call: the model never saw the graph, the hybrid
activations, or the tools. `AgentProcessSynthesisService` adds the step the reasoning output
deserved: **select an agent, feed it the relevant graph, bring back a fully described process.**

- Rides `AgentChatService.executeChatSync` with `injectMcpTools=true` and the suggestion's
  `factSheetId` — the selected CLI agent (claude/opencode/… from the registry; API agents are
  excluded by the sync-delegation contract) runs with the kompile MCP toolset and is explicitly
  instructed to explore before writing: look up the steps' graph node ids for titles/metadata,
  `ask_graph_query`/`ask_graph_verify`/`ask_graph_explain` the `precedes(...)`/`activity(...)`
  facts and their derivations, and read the entity types / relation schema around each step.
- The task carries the AUTHORITATIVE mined skeleton (steps in order with dependsOn, roles, graph
  node ids, HYBRID/ENTAILED/CONTRADICTION evidence, confidence) and a fixed output structure:
  Purpose / Trigger / Steps (per-step actor, inputs, outputs, waits-for) / Exceptions and
  contradictions / Evidence and confidence.
- The same grounding discipline as narration, stricter: every mined step name verbatim plus the
  `## Steps` section, or the document is rejected and nothing lands. The agent enriches; it never
  redefines.
- Persisted as `processDocument` + `processDocumentSource` on the suggestion.
  `POST /api/process/synthesis/suggestions/{id}?agent=…` runs it;
  `GET /api/process/synthesis/agents` lists eligible agents. Deliberately ON-DEMAND (agent runs
  cost minutes) — the cheap auto-narration pass stays automatic; the UI offers an agent picker +
  "Synthesize full description" per suggestion and renders the document with its agent tag.

The LLM ladder is now: deterministic template (always) → grounded one-shot LLM narration (auto,
cheap) → grounded agentic synthesis with graph exploration (on-demand, full document).

## 4l. Observed-actor role resolution — the OCPM resource perspective (2026-07-03, ninth pass)

The role resolver was a four-tier KB-first design whose KB tiers were dead: nothing anywhere
asserted `hasRole`/`performedBy`/`worksOn`, so every production binding came from name keywords
("…approv…" → APPROVER). Meanwhile the evidence that could bind roles for real — the actor-incident
relations (person —SENT_BY→ email) — was being *discarded* by `EventLogExtractor`, which excludes
them from case correlation (correctly: shared approvers union threads into a mega-case) but kept
nothing.

- **`ActorResourceObservations`** (extract/): pure tally over the fact sheet's raw nodes+edges.
  Every observed edge joining an actor (`isActorResource`) to a non-actor attributes the actor to
  that node's activity; attribution propagates ONE hop through communication carriers
  (actor→EMAIL_MESSAGE→MENTIONS→business entity — carrier and extracted entity are facets of the
  same real-world event, same rationale as the occurredAt lift). Carrier activities themselves are
  never bound. Performer labels (the extractors' `*_BY` authorship convention: SENT_BY,
  APPROVED_BY…) outrank mere involvement (SENT_TO/CC/MENTIONS); involvement-only actors must cover
  ≥ 0.5 of the activity's instances. A ROLE/JOB_TITLE/POSITION/DEPARTMENT/TEAM neighbor names the
  binding better than the actor ("Procurement Approver" beats "bob"); ORGANIZATION deliberately
  doesn't (everyone shares the org hub).
- **`RoleBindingExtractor` tiers become**: 0 = observed tally (wins always, works with no KB),
  1–3 = KB queries (unchanged), 4 = keywords. Steps now carry `roleSource`
  (OBSERVED | KB | HEURISTIC | null) so the UI can distinguish "we saw who did this" from "the
  name sounded like approving". KB bindings are unquoted for display.
- **Fact promotion closes the audit gap**: `promoteProcessFacts` now also asserts
  `performedBy("Approval", "bob")` (+ `hasRole(...)` when a role node named it) as soft Facts with
  Beta opinions from the per-instance counts AND seeds them as InferredFacts — which is what makes
  the KB tier (and `ask_graph_query`) actually able to answer these predicates: on a re-mine with
  degraded actor edges, tier-1/2 now answers from promoted knowledge. This resolves the
  "hasRole/performedBy emission unwired" finding from the crawl-extractor-gaps audit from the
  consumer side.
- **UI**: observed role chips render lime with a person icon and a provenance tooltip; a new
  RESOURCE evidence type ("Approval performed by bob — observed on 4 of 4 instances", score =
  share) sorts after ENTAILED. BPMN lanes and the narrator pick up the real names for free.
- **Latent production bug found and fixed**: `GraphEdge.getSourceNode()/getTargetNode()` never
  return null — they synthesize a hollow id-only `GraphNode` when the store didn't embed one, and
  matrix-store edges never embed nodes (`createEdgeObject` computed them via two per-edge
  `findNodeAnyGraph` lookups and then DROPPED them — dead locals, now deleted).
  `EventLogExtractor.touchesActorResource` preferred the embedded node, so **the actor-edge
  case-correlation filter was dead against the real store** — mega-case collapse would have
  returned on production crawls while fixture-built edges (which embed real nodes) kept tests
  green. Both consumers now resolve endpoints against the fact sheet's own nodes FIRST and treat
  edge-embedded nodes as fallback.
- **Follow-up tree-wide sweep (same day)**: every consumer reading title/entity_type/nodeType off
  edge-embedded nodes was found and fixed the same way — GraphOntologyBindingService edge
  conformance (validated UNKNOWN→UNKNOWN), MebnTheoryRegistrationService (fragments collapsed to
  NODE→NODE), GraphSearchTool/GraphTraversalTool endpoint titles (null in agent output),
  GraphLocalizationService entity-type filters (structural matches with requiredEntityTypes always
  empty), LlmProcessDiscoveryService prompt refs (raw ids), ProcessDiscoveryServiceImpl
  otherNode/buildEmailFlowSteps (email-attachment flows never fired; null actors),
  StepExecutionDispatcherImpl TABLE→parent-DOCUMENT walk. Id-only readers are unaffected by design;
  GraphToFactStoreProjector and GraphIOService were already defensive. The kompile-tool-graph test
  suite — uncompilable since the JPA repository purge — was also restored (101 tests).

Tests: `ActorResourceObservationsTest` (7 — direct attribution, carrier propagation + scaffold
skip, performer-beats-involvement, majority-share gate, actor-actor/derived exclusion,
role-neighbor naming, `*_BY` convention), `RoleBindingExtractorTest` +3 (observed beats KB,
observed without KB + HEURISTIC tagging, KB source + unquoting + null source for UNASSIGNED),
`CorporateEmailCrawlIntegrationTest` §8 (alice/bob/carol bind their lanes, the supplier "sales"
binds Invoice — previously UNASSIGNED; `roleSource=OBSERVED`; `performedBy("Approval", ?Who)`
conjunctive-queryable post-mine; RESOURCE evidence with 4-of-4 counts). Module: 323 green;
kompile-knowledge-graph: 2161 green; ng build green.

## 4m. Gap-closing pass (2026-07-03, tenth pass)

Four declared-open gaps closed:

- **PERFORMED_BY materialization** — the resource perspective now reaches the graph.
  `PrecedenceMaterializer` gained a third section: representative activity node
  —{@code PERFORMED_BY}→ observed actor node (`ObservedRole` now carries `actorNodeId`),
  USER_DEFINED carrier (mirrors the crawl's own `*_BY` edges), INFERRED provenance +
  `basis:observed-actor` + confidence = instance share. INFERRED provenance (plus a
  PERFORMED_BY entry in both derived-edge label filters, belt-and-braces) keeps materialized
  performers out of the next mine's casing AND actor tally — no feedback loop. Domain/range/
  cardinality for the new label then follow automatically via §4i relation-schema resolution.
- **OWL bridge consumes induced relation semantics** (§4i loose end): `relationResolution.symmetric`
  (reverse-edge share ≥ 0.8) → `owl:SymmetricProperty` (OWL-RL prp-symp materializes reverse
  edges); mean fan-out/fan-in of EXACTLY 1 → `owl:Functional/InverseFunctionalProperty`. The
  thresholded Cardinality field is deliberately NOT used for functional axioms — prp-fp/ifp are
  sameAs hints, and a lax axiom would merge distinct individuals once full sameAs lands.
- **Trace-recency decay in the temporal cross-exam**: each trace's valid-time vote now carries an
  exponential recency weight (half weight per `kompile.process.mining.entail.recency-half-life-days`,
  default 180; ≤0 disables). Age is measured against the LOG'S newest dated event — deterministic,
  never wall-clock. The Opinion and the refutation verdict use weighted sums, so recent reversals
  outvote stale confirmations when a process drifts; raw counts stay on the record for display.
  DFG arc strengths remain count-based (miner semantics — deliberate, unchanged).
- **Process map in the suggestions UI** (Phase-3 lite): the existing `GET /api/process/mining/mermaid`
  (dfg + tree sources) and `MermaidRendererComponent` are now wired into
  `process-discovery-suggestions` — a lazy "Show process map" per suggestion renders the
  directly-follows map and the Inductive Miner tree.

Deliberately still open: XOR conditionExpression (needs an expression language), Allen-interval
concurrency (needs interval — not point — timestamps), conformance-labeled PSL rule-weight
learning, activity embeddings → HybridReasoner semanticWeight, embedding-based alias unification.

## 4n. Final gap-close + kompile-managed config (2026-07-03, eleventh pass)

The remaining §4m "deliberately open" items, each taken in the form the data honestly supports,
plus the config mandate:

- **XOR decision-mining lite** — `ProcessTreeToSuggestion.annotateOperatorSemantics` grounds each
  XOR in the log: per-branch observed case shares, a default-TRUE SpEL routing stub on branch
  steps (`#take_<branch> != false` — a missing runData variable is null, so accepted processes
  behave exactly as before until an operator flips the flag; the engine's
  `ProcessStep.conditionExpression`/`conditionLabel` existed all along and the accept path now
  copies both), and one CHOICE evidence chip per XOR ("Choice between Approve (3/5), Reject
  (2/5)"). Guard PREDICATES stay unmined — events carry no data attributes; shares + editable
  stubs are what the log supports.
- **Concurrency corroboration (Allen-lite)** — AND blocks emit PARALLEL evidence with cross-trace
  order statistics (a-first/b-first/simultaneous by first occurrence); order instability across
  cases is the point-timestamp signal for real concurrency. Steps in AND branches stay mutually
  independent (dependsOn) as before.
- **Temporal-labeled rule-weight learning** — the valid-time cross-examination is per-pair ground
  truth: pairs with dated votes get a label (recency-weighted ordered share) and
  `PseudolikelihoodLearner` fits the structural rule weights to it (`PslProgram.withRules`
  re-runs inference with learned weights; result rule texts show them). Deterministic; needs ≥
  `miningWeightLearningMinLabels` labeled pairs (0 disables); never fails the mine.
- **Activity embeddings → semantic blending** — new infra-free `ActivityEmbedder` SPI (batch-
  shaped); app-main's `MiningActivityEmbedderConfig` wraps the `EmbeddingModel` bean
  (`embedBatch`); `ProcessHybridActivation` puts vectors on entities, queries with the process's
  normalized semantic centroid, and each engine's score becomes the reasoner's structural⊕cosine
  blend — off-theme strays score below topology alone. NoOp embedding model / failed labels
  degrade honestly to pure structural.
- **Kompile-managed config (NO @Value)** — ALL mining tunables moved to `ProcessMiningConfig` +
  `ProcessMiningConfigManager` (`process-mining-config.json` under the kompile config dir,
  mtime-hot-reloaded ≤5s, exactly the KbConfig pattern): anchor type, cluster Jaccard/max,
  Declare floors, assert/materialize thresholds, recency half-life, weight-learning
  min-labels/epochs, hybrid semantic weight, actor involvement-share gate. REST at
  `/api/process-mining-config` (GET, /defaults, POST merge — foreign keys never clobbered),
  edited in the web UI: Settings → **Process Mining** tab (grouped fields with hints, save/reset).
  Plain-Java tests fall back to `ProcessMiningConfig.defaults()`; the only remaining `@Value` is
  the infra `kompile.data.dir` path.

Still genuinely open (needs data we don't have): mined guard predicates (event attributes),
interval-based Allen relations (start+end times), embedding-based alias unification (WP14b).

## 4o. The "needs data we don't have" items — the data made real (2026-07-03, twelfth pass)

Each §4n leftover closed by first making its data exist:

- **Event attributes → mined guard predicates.** `EventLogExtractor` now lifts each node's scalar
  metadata (amounts, statuses, flags — the business facets the crawl extractors already store)
  onto `Event.attributes`, excluding identifier-ish keys and non-scalars (decision data, not a
  metadata dump; capped at 24). `annotateChoice` then runs decision-mining proper: per XOR
  branch, a one-vs-rest decision stump over the decision cases' attributes (numeric = best
  midpoint threshold, categorical = best equality; a key must be a valid SpEL identifier, present
  in ≥half the cases, ≥2 per side; winner needs ≥ `miningGuardMinAccuracy`). The mined guard
  composes with the manual flag NULL- and TYPE-safely:
  `#take_approve != false && (#amount == null || !(#amount instanceof T(java.lang.Number)) || #amount <= 2740.0)`
  — missing or non-numeric runData always runs; the label and CHOICE evidence carry the guard +
  separation stats ("mined guard: amount ≤ 2740 (separates 8 of 8 decision cases)").
- **Activity intervals → real Allen relations.** `ActivityIntervals`: per trace, an activity's
  interval = [earliest, latest] of its dated events — multi-event activities (email threads,
  loops) get real extent — further extended by crawl-lifted end-time attributes
  (`completedAt`/`closedAt`/… via `OccurredAtParser`). The temporal cross-exam now classifies per
  trace over intervals: BEFORE/MEETS → ordered, inverses → reversed, any intersection
  (OVERLAPS/STARTS/DURING/FINISHES/EQUAL) → an OVERLAP vote that abstains from direction. When
  recency-weighted overlap outweighs both directions combined the pair is **concurrent**:
  `assertable()` withholds `precedes()` (both directions), hybrid activation skips the relation,
  and a PARALLEL evidence entry explains ("intervals overlap in 4 dated cases; precedes()
  withheld"). Single-event activities degenerate to points where the algebra reduces exactly to
  the prior first-occurrence semantics — richer data upgrades the reasoning without changing the
  degenerate case. Equal points are Allen EQUAL (overlap), never both-ordered.
  `annotateParallel` uses the same classification (overlap count + order instability, take the
  stronger).
- **Embedding-based alias unification (WP14b at the mining seam).** `ActivityAliasUnifier`:
  union-find over activity-label pairs at embedding cosine ≥ `miningAliasSimilarityThreshold`
  (ActivityEmbedder vectors; default 0.95), canonical = most frequent label (ties
  lexicographic — deterministic re-mines), log rewritten BEFORE clustering so the miner never
  sees two half-support ghosts of one activity ("Bill" → "Invoice"). The observed-actor tally
  remaps through the same merges (or merged labels would lose their role bindings), and every
  merge surfaces as an ALIAS evidence chip — a visible decision, never a silent rewrite.
  Unembedded labels never merge; the projector-side WP14b (KB atom endpoints) stays with the
  reasoning-math workstream.

Both new knobs live in the managed config + Settings → Process Mining ("Decision & alias
mining" card). Tests: ActivityIntervalsTest (2), ActivityAliasUnifierTest (3), guard mining (2),
concurrent-pair entailment (1), extractor attribute lift (1) — module 339 green, ng build green.

## 4p. Process identity across time — lineage + drift (2026-07-03, thirteenth pass)

"The same process at different times" is now a first-class notion instead of disconnected
snapshots that deleted their own history:

- **Identity** — `ProcessIdentityResolver`: each fresh suggestion matches predecessor HEADS
  (pending heads + accepted suggestions) by activity-set Jaccard with BOTH sides remapped through
  the current alias merges (a rename the embedder unified this mine must not break identity with
  a pre-merge predecessor). Greedy one-to-one, best overlap first — a split cluster keeps the key
  on its closest half. Threshold `miningIdentityJaccardThreshold` (0.5) is deliberately stricter
  than clustering: identity is a stronger claim than co-clustering. Matched suggestions inherit
  the predecessor's `processKey` (minted from the first sighting's id) and link
  `previousSuggestionId`.
- **Mark, never delete** — `supersedePendingMined`'s delete loop is GONE (it destroyed the very
  history drift needs, and contradicted the standing non-destructive-prune mandate). Pending
  predecessors get `supersededAt` + `supersededBySuggestionId`; a vanished process (no successor
  claimed it) gets `supersededAt` with a null successor — the disappearance itself stays visible.
  Accepted suggestions are never superseded; they anchor identity.
  `GET /suggestions` lists heads only by default (`includeSuperseded=true` walks history).
- **Drift** — `ProcessDriftAnalyzer` diffs linked generations: steps added/removed, `dependsOn`
  rewires ("'Invoice' now waits for 'Manager Review' (was 'Approval')"), performer changes
  ("performer of 'Approval': bob → carol"; UNASSIGNED≡null never flaps), routing changes (the
  conditionLabel carries branch shares AND mined guards, so share shifts and guard changes ride
  one line), confidence delta (±0.1 floor). Surfaced as DRIFT evidence (capped 8) + a narrative
  clause ("Changes since 2026-06-12: …"); an UNCHANGED process states its stability explicitly
  ("Stable since …") rather than staying silent. Descriptive by design — shares/counts ride along
  so the reader judges drift vs noise.
- **Accept-as-revision** — new engine API `ProcessEngineService.reviseProcess(id, definition)`
  (the versioned-store analogue of `updateOntology`: version+1 under the SAME id, DRAFT, previous
  versions immutable). When the matched predecessor was ACCEPTED, the fresh suggestion carries
  `revisesProcessDefinitionId` and the accept path bumps the live definition instead of spawning
  an unrelated one (falls back to create if the target vanished). UI shows an "Updated"/"Revision"
  chip and ranks DRIFT evidence right after FUSION.

## 4q. Within-log change points + drift durability (2026-07-03, fourteenth pass)

The two loose ends of §4p closed:

- **Change-POINT detection** — `ChangePointDetector`: recency decay makes verdicts reflect the
  process as it is NOW; this explains WHEN it stopped being what it was, detectable on the very
  FIRST mine of a long log (no predecessor generation needed). Dated traces order by start time;
  every split with ≥ `miningChangePointMinWindowCases` (3) on each side is scored by how many
  WELL-SUPPORTED structural differences it separates — activities and directly-follows arcs
  present ≥2× on one side and completely ABSENT on the other (frequency wobbles are noise, not
  drift; arc differences only count when both endpoints exist on both sides, else they duplicate
  the activity-level line). The strongest split (ties: most balanced, then earliest) surfaces as
  one DRIFT evidence entry: "Change point ~2025-02-10 within this log (4 cases before, 4 after):
  activity 'Scan' appears (0 → 4 cases); activity 'Review' disappears (4 → 0 cases)". DFG-level —
  O(splits × events), no per-split Inductive Miner runs. Deterministic.
- **Drift-clause durability** — `ProcessNarrator.driftClause(suggestion)` rebuilds the "Changes
  since <date>: …" clause from the durable DRIFT evidence, and `LlmProcessNarrationService`
  appends it after BOTH the grounded LLM prose and the template fallback — the §4p caveat
  (the LLM rewrite dropped the clause) is gone. Within-log change-point entries stay
  evidence-only (they describe the log, not the previous generation).

The one remaining alias-unification item — the PROJECTOR-side WP14b (KB atom endpoints via node
embeddings) — was subsequently shipped IN the reasoning-math workstream (2026-07-03): see the
WP14b-EMBEDDING note in docs/architecture/reasoning-math-composition-implementation-plan.md
(`EMBEDDING_SIMILARITY` edges join the alias union-find at a 0.95 identity floor with a
same-entity_type guard).

## 4r. The evolving-procurement end-to-end fixture (2026-07-03, fifteenth pass)

`EvolvingProcurementIntegrationTest` — one crawl-shaped graph, one process, two eras, every drift
mechanism exercised together with no hints beyond the graph:

- **Era 1** (Jan, 6 orders): $80 widgets — 4 small orders take AUTO APPROVAL, 2 big ones MANAGER
  APPROVAL; bob approves everything; Acme Supply invoices. The mined guard reads the price data:
  `#amount <= 625.0` (the 450|800 midpoint), separating 6 of 6 cases.
- **Era 2** (Jul, 7 orders, accumulated onto the same graph as a real crawl would): the price
  TRIPLED ($240/unit) so every order exceeds the auto band, a new PRICE REVIEW step guards spend,
  carol took over approvals, and the vendor changed to NuParts.

Three tests prove the machinery recovers the whole story:

1. **Two-generation mine**: same `processKey`; predecessor marked superseded; ONE narrative carries
   it all — "performer of 'Manager Approval': Bob Approver → Carol Approver", "performer of
   'Invoice': Acme Supply Sales → NuParts Billing", "step added: 'Price Review'", routing share
   4-of-6 → 4-of-13 (the price increase collapsing the auto branch), PLUS the within-log change
   point dated to the era boundary ("~2025-07-07: activity 'Price Review' appears (0 → 7 cases);
   activity 'Auto Approval' disappears (4 → 0 cases)"). The Auto branch keeps bob's binding — no
   false ownership drift where era 2 has no instances.
2. **First mine over both eras**: exactly one DRIFT evidence — the change point — proving WHEN is
   answerable without any predecessor generation.
3. **Accept → change → re-mine → accept**: a real `ProcessEngineServiceImpl` (isolated home);
   v1 accepted from era 1, the re-mine proposes `revisesProcessDefinitionId`, the second accept
   yields the SAME definition id at version 2 (DRAFT, carrying Price Review) while v1 stays
   immutable and traceable to the era-1 graph nodes.

One semantic worth stating: drift's "Since \<date\>" is the predecessor's MINE date — drift is
relative to the last time we looked, which is the operator's actual question.

## 4s. Conflicting source descriptions + TMS reconciliation (2026-07-03, sixteenth pass)

Two sources describing the SAME process and disagreeing — finance threads run Purchase Request →
Manager Approval (bob) → Invoice; ops threads run Purchase Request → Invoice → Manager Approval
(carol), interleaved in time. Distinct from everything before it: temporal refutation silently
sides with a majority over noise; drift is a change over time; this is two LIVE, well-supported,
disagreeing accounts, and the verdict never picks a winner silently.

- **Detection** (`ProcessConflictAnalyzer`): per activity pair, interval-order votes per trace;
  a conflict needs ≥2 votes per direction AND the minority ≥ `miningConflictMinorityShare` (0.25).
  Each trace's source = the dominant `source` metadata of its events' graph nodes, so both sides
  surface WITH attribution: "Conflicting order: Manager Approval ↔ Invoice — 'Manager Approval →
  Invoice' in 4 case(s), 'Invoice → Manager Approval' in 3 case(s) [finance-crawl: Manager
  Approval first ×4; ops-crawl: Invoice first ×3]".
- **The reconciliation is a labeled GUESS with a basis**, discriminated three ways: temporal
  separation (one order's traces all precede the other's — drift wearing a conflict's clothes),
  source-split (each source internally ≥0.8 consistent — departmental variants; majority canonical,
  minority a named variant), interleaved (order-independence suspected; weak majority guess).
- **The numbers come from the reasoning library, not hand-rolled ratios**:
  `ProbabilisticContradictionDetector` scores the mutually-exclusive `Precedes` posteriors
  (explicit `MutualExclusion` on the pair's ORDER group → joint conflict + entropy on the CONFLICT
  evidence); per-source Beta opinions `cumulativeFuse` (⊕ — independent sources) into the
  reconciliation's strength ("fused opinion across sources: E=0.51, belief=0.44, disbelief=0.33,
  u=0.22" — an honestly contested residue, not false confidence).
- **The KB abstains, then the TMS resolves**: a 4v3 contested ordering clears neither the assert
  threshold nor refutation — NEITHER direction is promoted (the store abstains while sources
  disagree). When the KB nonetheless holds the guessed loser (a stale/foreign assertion, or a tied
  pair), the new `KbGroundingService.retractFact` — `BeliefReviser.retract` behind the KB's write
  lock — removes it and the RECONCILIATION evidence reports the fallout ("N atom(s) lost their
  only support, M weakened"). A consistent KB with no loser fact is never rewritten on a guess.
- **Ownership conflicts** ride the same pass: `ActorResourceObservations.tallyDetailed` keeps the
  runner-up, and a rival with ≥2 performer instances at ≥half the winner's support surfaces as
  "Conflicting owner for 'Manager Approval': Bob Approver (finance-crawl ×4) vs Carol Approver
  (ops-crawl ×3)" with its own guess (delegate/backup/disagreeing-source owner — confirm).
- **Surfaced everywhere**: CONFLICT (deep orange) + RECONCILIATION (light green) chips ranked
  right after FUSION; the narrative gains "The sources DISAGREE: … GUESS: …" via
  `ProcessNarrator.conflictClause`, re-appended after LLM narration like the drift clause.

Tests: `ConflictingSourcesIntegrationTest` (2 — the full two-source scenario incl. KB abstention,
and the stale-assertion case where BeliefReviser actually retracts), `retractFact` TMS round-trip
in `KbGroundingServiceTest`. Modules: process-discovery 354, knowledge-graph 2166, app.process 13,
ng build — all green.

## 4t. OWL is-a/has-a consumption — a process ABOUT a concept (2026-07-03, seventeenth pass)

The wine scenario: orders over CHIANTI/MALBEC/RIESLING entities where the taxonomy lives in the
ontology. Design principle (user-corrected mid-pass): NO new access path — the resolution already
exists three ways (`owlInferredTypes` closure metadata on nodes from
`OwlReasoningService.materializeInferences`; has-a closure as HIERARCHICAL/INFERRED edges;
entity-level `isa` facts + cax-sco in the KB), and retrieval already consumes it
(`matchesEntityType`, `HierarchicalAggregationService`). What was missing was exactly one thing:
the process tools were closure-blind. This pass makes mining CONSUME what is already materialized:

- **`TaxonomyRollup`** reads the SAME closure keys `matchesEntityType` honors, straight off the
  fact sheet's nodes. Roll-up to fixpoint: an ancestor qualifies with ≥ `miningTaxonomyMinSiblings`
  (2) member activities (an activity already labeled as the ancestor counts — it IS the concept);
  each activity rolls to its most specific qualifying ancestor, and repeated passes converge
  multi-level hierarchies ({Chianti, Malbec} → RedWine → Wine ⊔ {Riesling} → Wine ⇒ ONE Wine
  activity). Every rewritten event keeps its leaf label as the `category` attribute — so the
  EXISTING decision-stump guard mining rediscovers subtype routing (the wine test mines
  `#color == 'red'` for Cellar Aging and a category/color guard for Cold Storage) with zero new
  guard machinery. Inert without closure metadata (no bound ontology → no-op). Stump ties between
  equally-separating attributes now break lexicographically (TreeMap) — `Map.copyOf` in Event had
  made them arbitrary.
- **Facts/opinions accumulate up the hierarchy**: promotion runs on the rolled-up log, so
  `Occurs(Wine)` carries ONE Beta opinion fused across every subtype's traces (7/7, belief >0.7)
  instead of three weak per-subtype facts — corroboration transfers up the is-a axis, the same
  principle as WP14b alias unification. Activity-level `isa("Chianti","Wine")` facts are promoted
  hard AND seeded as InferredFacts (the dual-channel lesson performedBy taught: verify() reads the
  fact store, conjunctive query() reads the inferred store) — `isa(?Category, "Wine")` answers
  with all three categories.
- **has-a**: `detectHasAPairs` maps the already-materialized HIERARCHICAL/INFERRED closure edges
  onto activity-concept pairs (through the roll-up: "Wine is part of Order Placed"), surfaced as
  TAXONOMY evidence and promoted as `partOf(...)` facts. Never re-derived.
- The actor tally remaps through the roll-up (roles land on "Wine"); TAXONOMY chips (purple) name
  every applied group — abstraction is a visible decision.

Tests: `TaxonomyRollupTest` (4 — sibling roll-up + category attribute, multi-level fixpoint,
lone-subtype/no-closure/disabled no-ops, typeClosure key variant + existing-parent membership),
`WineTaxonomyIntegrationTest` (1, five-part: one process about Wine; mined color/category guards;
fused Occurs(Wine) opinion; isa queryability; has-a surfacing + promotion). Module 359 green,
app-main compiles, ng build green.

## 4u. De-duplication pass — process concepts that were really graph concepts (2026-07-03)

Audit-driven consolidation of everything this workstream accidentally duplicated:

1. **Taxonomy access** — the OWL closure key list now lives ONCE
   (`GraphNodeTypes.TYPE_CLOSURE_KEYS` + `resolveTypeClosure`), consumed by retrieval's
   `matchesEntityType` AND `TaxonomyRollup`. The roll-up additionally consumes the crawl-native
   hierarchy (`resolveTypeHierarchy`: entity_subtype → entity_type → entity_category) — a
   BEHAVIOR gain, not just hygiene: an ungoverned crawl stamping `entity_category=Wine` now rolls
   up without OWL ever running (new test).
2. **`remapKeys`** — one generic implementation (`TaxonomyRollup.remapKeys`); the alias unifier's
   overload adapts its merge list to it.
3. **`cosine`** — the lib's `Embeddings.cosine` everywhere (identical zero/mismatch semantics);
   the alias unifier's private copy deleted.
4. **Source attribution** — `GraphProvenanceKeys.sourceLabel(metadata)`: plain `source` tag →
   reserved `_source`/`_crawlRunId`/`_sourceDocumentId` (+ legacy variants) → url. Conflict
   attribution now sees the reserved-key fallbacks it previously missed.
5. **Hollow-endpoint resolution** — canonical domain helpers: `GraphEdge.resolvedSourceNode/
   resolvedTargetNode(byId)` (byId-first) and `GraphNode.isHollow()` (id-only stub predicate).
   All seven per-consumer copies rewired (extractor, actor tally, ontology binding, discovery
   impl, step dispatcher, both graph tools, localization). CamelNodeExecutor was a false positive
   (Camel routing endpoints, unrelated).
6. **Union-find** — shared `ai.kompile.utils.UnionFind<T>` (comparator-elected deterministic
   roots) behind the projector's WP14b canon and the activity alias unifier. TraceClusterer's
   index-based single-link variant deliberately stays (different shape, no root-election
   semantics to share).
7. **Managed-config manager** — `ManagedJsonConfigManager<T>` base in cli-common (file path,
   5s-mtime hot reload, owned-key merge); `KbConfigManager` and `ProcessMiningConfigManager` are
   now thin subclasses. `CrawlRuntimeConfigManager` deliberately stays bespoke (package-private,
   quota-ledger entanglement) with a pointer in the base's javadoc.
8. **Declared-type reads** — `GraphNodeTypes.resolveDeclaredType` (entity_type + camelCase
   variant, deliberately NOT the category-first `resolveEntityType`) replaces the inline
   `metadata.get("entity_type")` snippets in ActivityClassifier, ActorResourceObservations, and
   RelationSchemaResolutionService.

Verified: kg 2166, process-discovery 360 (+1 crawl-native roll-up test), tool-graph 101,
tool-graph-localization 86, app-main ontology+process 66 — all green.

## 5. Verification (all green)

- `ProcessEntailmentTest` (7): transitive entailment of A→C with and *without* Declare
  constraints (pure Df-chaining); antisymmetry suppresses the reverse; NCE suppression between
  never-co-occurring activities; temporal refutation (+1/−3 dated traces) excludes the pair from
  `assertable()` regardless of posterior; fused opinion stays on the simplex; `kbAtomKey` quoting;
  empty/degenerate logs; compiled rule texts in the result.
- `PrecedenceMaterializerTest` (3): DF specs per distinct consecutive node pair; entailed-only
  pairs become one `PRECEDES` edge between representative nodes; observed pairs are never
  duplicated as `PRECEDES`; every spec directional (`bidirectional:false`), `EdgeType.TEMPORAL`,
  `provenanceType:INFERRED`, suggestion-stamped; spec-level dedup; empty-log no-op.
- `MiningEntailmentWiringTest` (1, end-to-end): after `discoverForFactSheet` on a mocked graph —
  ENTAILED + FUSION evidence present, fused confidence above the all-UNKNOWN grounding floor,
  tree-wired dependsOn on steps, **`kb.verify("precedes(\"Approve\", \"Close\")") == SUPPORTED`**,
  `createEdgesBatch` received DIRECTLY_FOLLOWS specs, and acceptance translates dependsOn names
  to executor-enforced step ids.
- Full module suite: 280 tests, 0 failures (no regressions in conversion/grounding/lineage/
  controller tests). `kompile-data-enrichment` and `kompile-app-main` compile against the change.
- The suggestions UI renders the new evidence types with no frontend change (generic
  `{{ ev.type }}` chips), and `dependsOn` rides the existing accept POST round-trip.
