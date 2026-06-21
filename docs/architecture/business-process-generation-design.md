# Business-Process Generation Design
## Representation, Generation from the Hydrated KB, and Diagramming

**Date**: 2026-06-21
**Status**: DESIGN — no code written yet
**Scope**: How kompile represents, generates, and diagrams business processes as first-class domain objects
built directly from the hydrated knowledge-base — and whether Drools is the right rule engine for it.
**Layer context**: This is the L4 domain-object treatment for `BUSINESS_PROCESS` from
[`domain-object-generation-design.md`](domain-object-generation-design.md) (§2.1). Read that doc first
for the 5-layer stack. The hydration substrate is designed in
[`graph-hydration-inference-chain-design.md`](graph-hydration-inference-chain-design.md).

---

## 1. Inventory — What Exists Today

### 1.1 Drools Module

**Module**: `kompile-app/kompile-data/kompile-compute-graphs/kompile-compute-graph-drools/`

| Class | Path | Role |
|---|---|---|
| `DroolsComputeGraphAutoConfiguration` | `.../config/DroolsComputeGraphAutoConfiguration.java:20` | `@ConditionalOnClass(name = "org.kie.api.KieServices")` opt-in auto-config |
| `DroolsRuleCompiler` | `.../DroolsRuleCompiler.java` | Compiles inline DRL from `ComputeNode.script` into a `KieBase`; `ConcurrentHashMap` cache keyed by `nodeId:scriptHash` |
| `DroolsNodeExecutor` | `.../DroolsNodeExecutor.java:25` | Implements `NodeExecutor`; three modes: `DROOLS_RULE` (targeted), `DROOLS_INFERENCE` (full forward-chain), `DROOLS_DECISION_TABLE` (XLS/CSV via `DecisionTableProviderImpl`) |
| `DroolsInferenceEngine` | `.../DroolsInferenceEngine.java:37` | Implements `ComputeGraphEngine`; compiles ALL graph nodes into one `KieBase`, inserts all inputs as `NodeFacts`/`NamedFact`; fires rules up to `maxRuleFirings` (default 10 000) letting RETE determine order |
| `DroolsDecisionTableCompiler` | `.../DroolsDecisionTableCompiler.java` | XLS (base64) or CSV decision tables → DRL → `KieBase` via `KnowledgeBuilderFactory.newDecisionTableConfiguration()` |
| `BusinessRulesTool` | `kompile-middleware/kompile-tools/kompile-tool-camel/.../BusinessRulesTool.java:23` | MCP tool bridge with 3 `@Tool` methods (evaluate DRL, evaluate decision table, inspect); uses reflection so the module compiles without Drools on the classpath |
| `DroolsCamelProcessor` | (in same module) | Apache Camel `Processor` delegating to `DroolsNodeExecutor` via reflection |

**POM**: `kompile-compute-graph-drools/pom.xml:18` — `drools.version=9.44.0.Final`; deps: `drools-core`, `drools-compiler`, `drools-mvel`, `drools-decisiontables`, `kie-api`, `kie-internal`.

**Step types registered**: `StepType.java:46-50` — `DROOLS_RULE`, `DROOLS_INFERENCE`, `DROOLS_DECISION_TABLE` are valid `ProcessStep` execution modes in the process-engine model.

**No `.drl` files** exist in the repo — rules are stored inline in `ComputeNode.script` (database-driven). **No jBPM and no BPMN execution** references were found anywhere in the codebase.

**FP&A POMs**: `kompile-fpna-v3/project/pom.xml` and `kompile-fpna-v4/project/pom.xml` both reference drools, confirming the FP&A vertical is the primary consumer of Drools-backed compute graphs.

**Native-image status**: Drools 9.x uses runtime MVEL bytecode generation. The module is `@ConditionalOnClass` and is absent from the native CLI build path. There are no GraalVM reflect/proxy config files for Drools classes. It is **JVM-server-only** — the same native-image hostility that motivated PSL being hand-rolled (`kompile-graph-reasoning`) applies here.

---

### 1.2 Process-Mining Infra (the existing process generator)

**Module**: `kompile-app/kompile-data/kompile-process/kompile-process-discovery/`

#### Control-flow discovery

| Class | Path | What it produces |
|---|---|---|
| `EventLogExtractor` | `.../mining/extract/EventLogExtractor.java:46` | Turns `KnowledgeGraphService` nodes+edges for a fact sheet into an `EventLog`; excludes `NodeLevel.SOURCE` and `SNIPPET` scaffolding; pluggable `ActivityClassifier` (default: by entity_type) + `CaseCorrelation` (default: connected-component) |
| `InductiveMiner` | `.../mining/miner/InductiveMiner.java:60` | Full semi-naive Inductive Miner — XOR/SEQUENCE/PARALLEL/LOOP cut discovery; fallback flower model guarantees soundness; optional IMf noise threshold |
| `HeuristicsMiner` | `.../mining/miner/HeuristicsMiner.java` | Dependency measure `(|a→b|−|b→a|)/(|a→b|+|b→a|+1)`; arcs above threshold → `HeuristicsNet` |
| `ProcessTreeToSuggestion` | `.../mining/convert/ProcessTreeToSuggestion.java:49` | Flattens `ProcessTree` into `ProcessSuggestion` (phases → steps); each step carries provenance `graphNodeIds` and timestamp back to the KB node |

#### Statistical/causal overlay

| Class | Path | What it produces |
|---|---|---|
| `DeclareMiner` | `.../mining/declare/DeclareMiner.java` | Response/Precedence/ChainResponse/NotCoExistence/Init/End constraints with per-constraint support/confidence |
| `ProcessCausalAnalyzer` | `.../mining/causal/ProcessCausalAnalyzer.java` | χ²-tested causal dependencies typed via `CausalEdgeType` (CAUSES/ENABLES/TRIGGERS/CONTRIBUTES_TO/PREVENTS/CORRELATES_WITH/INFLUENCES/DERIVED_FROM); emits PSL rules from causal model |
| `ProcessPslInference` | `.../mining/causal/ProcessPslInference.java` | Runs HL-MRF over the discovered process: `Link` atoms carry directly-follows dependency strengths; optional evidence activities clamped |
| `ProcessBayesianInference` | `.../mining/causal/ProcessBayesianInference.java` | Noisy-OR CPTs + variable elimination over the process DAG |
| `PerformanceMiner` | `.../mining/perf/PerformanceMiner.java` | Per-arc count + mean + median duration in seconds (bottleneck analysis) |
| `ConformanceChecker` | `.../mining/conformance/ConformanceChecker.java` | Fitness / precision / simplicity metrics against the discovered model |

#### Diagram output

| Class | Path | What it outputs |
|---|---|---|
| `ProcessMermaidExporter` | `.../mining/export/ProcessMermaidExporter.java:32` | Two static methods: `dfgToMermaid(DirectlyFollowsGraph)` → `flowchart TD` with `start((start))` / `stop((end))` and frequency-labeled edges; `treeToMermaid(ProcessTree)` → hierarchical block-structure with operator symbols (→ × ∧ ↺) |

#### REST surface

`MiningDiscoveryController.java` at `/api/process/mining`:

| Endpoint | Returns |
|---|---|
| `GET /discover` | `ProcessSuggestion` (persisted) |
| `GET /preview` | `Map<String,Object>` — event log stats, DFG arcs, process tree |
| `GET /causal` | `ProcessCausalModel` (χ²-tested + PSL rules) |
| `GET /psl` | `ProcessPslInference.Result` |
| `GET /bayesian` | `ProcessBayesianInference.Result` |
| `GET /declare` | `List<DeclareConstraint>` |
| `GET /mermaid` | `Map<String,String>` with `"dfg"` and `"tree"` Mermaid strings |
| `GET /conformance` | `ConformanceResult` |
| `GET /heuristics` | `HeuristicsNet` |
| `GET /performance` | `PerformanceAnalysis` |

**Angular UI**: `process-mining.component.ts` (process-engine dashboard, Mining tab) renders six sub-tabs: Process Map (DFG Mermaid), Process Tree (tree Mermaid), Causal (χ² table + PSL rules), PSL activation bar chart, Bayesian posterior bar chart, Performance/Bottleneck table — all via `app-mermaid-renderer` which supports render/code/split view with dark-mode awareness.

---

### 1.3 Process-Engine Domain Model

**Module**: `kompile-app/kompile-data/kompile-process/kompile-process-engine/`

Key classes confirm the downstream shape that generated processes must conform to:

| Class | Path | Notes |
|---|---|---|
| `ProcessDefinition` | `.../workflow/ProcessDefinition.java:39` | Versioned; fields: `ontologySchemaId`, `phases` (list of `ProcessPhase`), `controls` (SOX gates), `agentSpecs`, `factSheetId`, `sourceSuggestionId`, `sourceGraphNodeIds`, `discoveryConfidence` — **KB provenance fields already wired** |
| `ProcessStep` | `.../workflow/ProcessStep.java:38` | `StepType` enum includes `DROOLS_RULE`/`DROOLS_INFERENCE`/`DROOLS_DECISION_TABLE` alongside `TOOL_CALL`, `HTTP_CALL`, `SCRIPT`, `EXCEL_COMPUTE`, `CAMEL_ROUTE`, `APPROVE`, `HUMAN` |
| `StepType` | `.../workflow/StepType.java:22` | 12 types; Drools occupies 3 of them |

The `ProcessDefinition → ProcessPhase → ProcessStep` hierarchy is the target shape. `ProcessSuggestion → SuggestedPhase → SuggestedStep` is the mined draft. `ProcessTreeToSuggestion` bridges them, but **there is no `SuggestionToDefinition` converter** — accept flow goes through the REST API to `ProcessEngineController`.

---

### 1.4 Our Reasoning Stack (potential Drools replacement)

**Module**: `kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/`

| Component | Class | Capability |
|---|---|---|
| **Datalog fixpoint** | `RecursiveQueryEngine.java` | Semi-naive bottom-up evaluation; stratified negation via Tarjan SCC; caps at 10 000 rounds / 500 000 derived facts |
| **FOL forward-chain** | `FolInferenceService.java` | Translates `FolRule` sets into grounded PSL programs; runs `HlMrfMapInference.solve()`; single O(N²) pass (10 000 entity-pair cap) |
| **Soft rules / MAP** | `HlMrfMapInference.java`, `ScalarHlMrfInference.java`, `TensorHlMrfInference.java` | PSL projected-gradient MAP; GPU-capable via ND4J; `HlMrfSolver` strategy selects scalar vs. tensor by problem size |
| **Weight learning** | `PslWeightLearningService.java`, `PseudolikelihoodLearner.java`, `StructuredPerceptronLearner.java` | Learns rule weights from observed data; `FileWeightStore` for persistence |
| **TMS** | `ContradictionDetector.java`, `JustificationIndex.java`, `BeliefReviser.java` | Hard-constraint violation detection; justification graph (atom → supporting rules → observed bodies); retract-and-re-solve |
| **MEBN** | `MebnInferenceService.java`, `SSBNGenerator.java` | Multi-entity Bayesian networks; parameterized random variables |
| **Bayesian** | `BayesianNetwork.java`, `VariableElimination.java`, `NoisyOrCpt.java` | Exact inference; noisy-OR CPTs |
| **Embeddings** | `RotatELearner.java`, `LinkPredictor.java`, `Node2VecLearner.java` | KGE for link prediction; feeds as PSL `SIM` evidence |
| **Ontology** | `OntologicalConstraintBuilder.java`, `OwlRlReasoner.java` | Hard PSL rules from OWL-RL restrictions; mutual exclusion / subsumption |
| **Explain** | `ExplanationService.java`, `DerivationTree.java` | Justification trees for derived facts |

---

## 2. Drools Assessment: Keep / Replace / Integrate

### 2.1 Capability Matrix

| Capability | Drools (current) | Our Stack |
|---|---|---|
| **Crisp rule execution** | RETE forward-chaining, DRL syntax, up to 10 000 firings per graph | `RecursiveQueryEngine` Datalog fixpoint; `FolInferenceService` single-pass forward-chain |
| **Decision tables** | Native XLS/CSV via `DroolsDecisionTableCompiler` | Not implemented; would require mapping rows to PSL rules or Datalog |
| **Soft/probabilistic rules** | No (RETE is crisp) | PSL HL-MRF — full soft-truth [0,1] + Łukasiewicz; GPU-capable |
| **Learned weights** | No | `PslWeightLearningService` (pseudolikelihood + structured perceptron) |
| **BPMN process execution** | No (explicitly not present) | Not applicable |
| **TMS (contradiction + retract)** | No | `ContradictionDetector` + `BeliefReviser` |
| **Graph entity integration** | Via `NodeFacts`/`NamedFact` (string/Object maps) | Native: operates on `GraphNode`/`GraphEdge` via `ReasoningGraph` |
| **Native-image** | Hostile (MVEL bytecode gen) | Native-safe (pure Java; PSL was hand-rolled for this reason) |
| **CLI deployment** | Excluded by `@ConditionalOnClass` | Fully deployed in any JVM |
| **Explainability** | `_totalRulesFired` count only | `DerivationTree`, `JustificationIndex`, `EntailmentRecord` full provenance |
| **FP&A decision tables** | Active consumers in kompile-fpna-v3/v4 | No equivalent; would need a new decision-table DSL or row→PSL compiler |

### 2.2 Verdict: Partial Replace — Keep Decision Tables, Replace Inference

**Drools forward-chaining inference** (`DROOLS_RULE` / `DROOLS_INFERENCE` step types) **should be replaced** by the reasoning stack for the business-process generation path. Rationale:

1. No `.drl` files exist in the repo — all rules are database-inline strings. The same strings can be expressed as PSL rules or Datalog rules with no user-facing format change.
2. Drools inference operates on `NodeFacts` (string/Object maps) isolated from the KB. Our stack operates natively on `GraphNode`/`GraphEdge`/`ReasoningGraph` — this is required for entity-linked process generation.
3. Drools is native-hostile; it is already excluded from the CLI. Unifying on the reasoning stack removes the split.
4. PSL adds soft-truth confidence propagation that Drools RETE cannot provide — a core requirement for confidence-scored process generation.
5. `RecursiveQueryEngine` already handles the recursive-rule patterns (e.g., transitive precedence, reachability) that the RETE network handles in Drools, and is graph-native.

**Drools decision tables** (`DROOLS_DECISION_TABLE` step type) **should be kept** for now. Rationale:

1. XLS/CSV decision tables from FP&A fact sheets (`kompile-fpna-v3/v4`) are a real, active use case. No row→PSL compiler exists.
2. Decision tables are a presentation format (business analysts maintain them in Excel), not an inference paradigm. Replacing them requires a new DSL surface, not just a backend swap.
3. They are isolated behind `@ConditionalOnClass` on the JVM server and do not block native-image builds.

**Migration cost for inference replacement**: `DROOLS_RULE` and `DROOLS_INFERENCE` `ProcessStep` types continue to exist in `StepType.java` but their executor is replaced by a `DatalogStepExecutor` / `PslStepExecutor` that calls `RecursiveQueryEngine` / `HlMrfMapInference` with the node's script parsed as rule text. The DRL syntax must be translated (a one-time migration of any existing inline DRL stored in `ComputeNode.script` rows); since no `.drl` files exist and this is DB-stored, the migration scope is limited to whatever rules exist in deployed instances.

---

## 3. Business-Process Element Design

### 3.1 Representation: The Process Model as a KB View

A business process in kompile is not a standalone artifact — it is a **view over the hydrated KB**, structured as `ProcessDefinition`. The design principle: every process element must link back to the KB.

```
ProcessDefinition
├── factSheetId                   → NamedGraph in KnowledgeGraph
├── ontologySchemaId              → OntologySchema (Phase-6 binding)
├── sourceGraphNodeIds[]          → GraphNode IDs that were evidence
├── discoveryConfidence           → PSL-calibrated float [0,1]
└── phases[]
    └── ProcessPhase
        └── steps[]
            └── ProcessStep
                ├── id, name, description
                ├── StepType          (AUTO / HUMAN / TOOL_CALL / APPROVE / CONTROL_GATE ...)
                ├── agentSpecId       → AgentSpec (who executes this step)
                ├── inputKeys[]       → KB entity attribute names
                ├── outputKeys[]      → KB entity attribute names
                └── graphNodeIds[]    → KB nodes that represent this activity's occurrences
```

**Object-centric extension** (extends the existing `anchorType` parameter in `MiningDiscoveryController.java:63`):

Each step must carry:
- `anchorEntityType` — the entity type that defines a process instance (ORDER, INVOICE, CUSTOMER)
- `anchorNodeIds[]` — the specific KB node IDs for anchor entities in this step's occurrences
- `roleBindings` — `Map<String, String>` mapping role labels (APPROVER, EXECUTOR) to entity_type values from the KB ontology

This transforms the process from a flat activity sequence into an object-centric model where swim lanes correspond to KB entity types.

**Roles from the KB**: Roles are derived by `GraphEntityBuilder` entity attributes — if a `GraphNode` of type `PERSON` or `ROLE` appears in edges adjacent to activity nodes, it becomes a role participant. The `OntologicalConstraintBuilder` type hierarchy identifies which entity types can be role holders.

### 3.2 Generation from the Hydrated KB

The generation pipeline extends the existing mining stack. Six stages run sequentially:

#### Stage 1: Event-Log Construction (exists)

`EventLogExtractor.extractForFactSheet(KnowledgeGraphService, factSheetId)` — already bridges KB to process mining via `CaseCorrelation`. The object-centric anchor notion (`AnchorTypeCorrelation`, `ConnectedComponentCorrelation`) already exists and is parameterized.

**Gap to close**: `ActivityClassifier` currently maps `entity_type` to activity name. It should be extended to also read derived PSL `Type(X,T)` atoms from the `InferredFactStore` so activities are typed against the hydration chain's inferred types, not only the raw crawl-extracted `entity_type` string.

#### Stage 2: Control-Flow Discovery (exists)

`InductiveMiner` → `ProcessTree` → `ProcessTreeToSuggestion` → `ProcessSuggestion`. Already produces phases/steps with `graphNodeIds` provenance.

**Gap to close**: `ProcessTreeToSuggestion.convert()` sets `confidence = Math.max(0.1, conformance.fitness() * conformance.precision())` (structural only). This must be extended to incorporate the PSL posterior from the causal model:

```
confidence = w_structural × (fitness × precision)
           + w_psl       × mean(psl_activation_score over process nodes)
           + w_bayesian  × max_posterior(bayesian_result)
```

where weights are learned via `PslWeightLearningService` over accepted/rejected suggestion history.

#### Stage 3: Causal/Temporal Enrichment (exists, needs wiring)

`ProcessCausalAnalyzer` already emits `CausalDependency` list + typed `CausalEdgeType` arcs. These feed:

- `CausalEdgeType.ENABLES` arcs → pre-conditions on `ProcessStep` (a step cannot start until its enabling step completes)
- `CausalEdgeType.TRIGGERS` arcs → `StepTrigger` event conditions in `ProcessStep`
- `CausalEdgeType.PREVENTS` arcs → mutual-exclusion constraints (CONTROL_GATE steps)

**Gap**: No converter from `ProcessCausalAnalyzer.ProcessCausalModel` to `ProcessStep.trigger` / `ApprovalPolicy` / `ControlDefinitionRef` exists yet.

#### Stage 4: Role/Decision-Logic Derivation via Reasoning Stack (new)

This is the stage Drools was meant to serve and where our stack replaces it:

- **Roles**: Query the `ReasoningGraph` via `ConjunctiveQueryEngine` for entities adjacent to activity nodes that carry PERSON/ROLE entity type. Run `FolInferenceService` with rules like `hasRole(?X, APPROVER) :- approves(?X, ?Y) & isActivity(?Y)`. These become `ProcessStep.agentSpecId` assignments.

- **Decision logic**: Run `RecursiveQueryEngine` with Datalog rules derived from the PSL causal model to compute which conditions (KB attribute values) must hold for each gateway. Express decisions as `ProcessStep.executionExpressions` (SpEL) — e.g., `#amount > threshold` where `threshold` is a value derived from `GraphNode` metadata.

- **Business rules as GROUNDED_RULE objects**: The `InferredFactStore.allLatest()` scan described in `domain-object-generation-design.md` §2.2 produces `GROUNDED_RULE` domain objects. These populate a `rules` field on `ProcessStep` (new field) recording which KB-derived rules govern the step's behavior — this is the formal specification of the process decision logic.

#### Stage 5: Confidence Scoring and TMS (new integration)

After generating the full `ProcessSuggestion`:

1. Run `ContradictionDetector.findFactContradictions(FactStore)` over the facts that support the process steps. Any contradictions demote `discoveryConfidence` and surface in `structuredEvidence` as a CONTRADICTION entry.

2. Run `BeliefReviser` if contradictions are found: retract the weakest supporting fact and re-derive. The resulting revised `ProcessSuggestion` is offered as an alternative (Process Variant B).

3. The TMS justification graph (`JustificationIndex`) provides the audit trail for every process step's derivation — maps back to the KB nodes, PSL rules, and causal arcs that produced it.

#### Stage 6: ProcessSuggestion → ProcessDefinition Promotion (gap to close)

Currently, accepting a `ProcessSuggestion` goes through `ProcessEngineController` but the conversion path `SuggestedPhase → ProcessPhase → ProcessStep` is done manually. A `SuggestionToDefinitionConverter` service should be added to `kompile-process-discovery`:

- Converts `SuggestedStep.stepType` string → `StepType` enum
- Propagates `sourceGraphNodeIds` to every step's `graphNodeIds`
- Populates `ontologySchemaId` from `GraphOntologyBindingService` for the fact sheet
- Preserves `discoveryConfidence` in `ProcessDefinition.discoveryConfidence`

---

### 3.3 Diagrams

#### Format Assessment

**Mermaid** (current):
- `ProcessMermaidExporter.dfgToMermaid()` (line 41) and `treeToMermaid()` (line 67) are already implemented and wired to `app-mermaid-renderer`.
- Renders inline in the browser via the `mermaid` npm package.
- Cannot express swim lanes, data objects, or gateway semantics — only activity boxes and edges.
- Best for: quick DFG visualization, process tree structure. Not a business standard.

**BPMN 2.0 XML**:
- Business-process industry standard — supported by Signavio, Camunda, Bizagi, Visio.
- Expresses swim lanes (by role/participant), gateways (XOR/AND/OR), events (start/end/intermediate), data objects, message flows.
- The process tree cut operators map directly: XOR cut → exclusive gateway, SEQUENCE cut → sequence flow, PARALLEL cut → parallel gateway, LOOP cut → looping back to a task.
- No BPMN library exists in the codebase today. The Java ecosystem has `bpmn-io/bpmn-js` (frontend rendering) and on the backend, `Camunda BPM` (too heavy) or a lightweight custom XML writer (preferred — same rationale as hand-rolling PSL).
- A custom `ProcessBpmnExporter` can produce valid BPMN 2.0 XML by writing the `<definitions>`, `<process>`, `<task>`, `<exclusiveGateway>`, `<parallelGateway>`, `<sequenceFlow>`, and `<laneSet>` elements from the `ProcessTree` structure and role bindings without any library dependency.

**Recommendation: produce both formats** (Fork B — see §4). Mermaid for the existing UI (no change required); BPMN 2.0 XML for export and downstream tooling. They are generated from the same `ProcessDefinition` model by two separate exporters.

#### Swim-Lane Design

Swim lanes map to **KB entity types in the role position**. For a process with activities `[SubmitInvoice, ApprovePayment, ReleasePayment]`:

- Lane `ACCOUNTS_PAYABLE_CLERK` (derived from adjacent PERSON entity with role attribute) → `SubmitInvoice`
- Lane `FINANCE_MANAGER` (adjacent PERSON with APPROVER attribute) → `ApprovePayment`
- Lane `SYSTEM` (AUTO step with no PERSON entity adjacent) → `ReleasePayment`

Lane assignment is derived by `ConjunctiveQueryEngine` over the KB adjacency graph — it is not hardcoded.

#### Data Object Annotations

Data objects that flow between activities are derived from `ProcessStep.inputKeys` / `outputKeys`. These keys are KB node `entity_type` values (INVOICE, PURCHASE_ORDER, PAYMENT_RECORD) linked via `graphNodeIds`. In BPMN, they become `<dataObject>` elements with `<dataInputAssociation>` / `<dataOutputAssociation>` attached to the relevant tasks.

---

### 3.4 End-to-End Flow

```
Hydrated KB (GraphNode/GraphEdge, InferredFactStore, ReasoningGraph)
    │
    ▼  Stage 1: EventLogExtractor
EventLog (Traces ordered by GraphNode.occurredAt, CaseCorrelation by anchorType)
    │
    ▼  Stage 2: InductiveMiner → ProcessTree → ProcessTreeToSuggestion
ProcessSuggestion (phases/steps with graphNodeIds provenance)
    │
    ▼  Stage 3: ProcessCausalAnalyzer → causal/temporal enrichment
ProcessSuggestion + CausalEdgeType arcs → step pre-conditions / triggers
    │
    ▼  Stage 4: ConjunctiveQueryEngine + RecursiveQueryEngine + FolInferenceService
ProcessSuggestion + role bindings + GROUNDED_RULE objects per step
    │
    ▼  Stage 5: ContradictionDetector + BeliefReviser
ProcessSuggestion with PSL-calibrated confidence + optional Variant B
    │
    ▼  Stage 6: SuggestionToDefinitionConverter (new)
ProcessDefinition (accepted, versioned, ontology-bound)
    │
    ├──▶ ProcessMermaidExporter → Mermaid DFG + Tree strings → app-mermaid-renderer
    └──▶ ProcessBpmnExporter (new) → BPMN 2.0 XML → download / external tool
```

---

### 3.5 Lib-vs-Client Module Split

The invariant from `domain-object-generation-design.md:7`: `kompile-graph-reasoning` stays infra-free.

| Component | Target module | Rationale |
|---|---|---|
| `ProcessBpmnExporter` | `kompile-process-discovery` | Pure Java, no Spring; depends on `ProcessTree` model |
| Enhanced `ProcessMermaidExporter` (swim lanes) | `kompile-process-discovery` | Same |
| `SuggestionToDefinitionConverter` | `kompile-process-discovery` | Depends on domain models in both discovery and engine |
| Role-derivation via `ConjunctiveQueryEngine` | `kompile-process-discovery` → calls `kompile-graph-reasoning` | discovery is already a client of reasoning |
| `DatalogStepExecutor` (replaces Drools inference) | `kompile-process-engine` | Needs Spring `@Service` for KB access |
| `ProcessDefinition.rules` field (GROUNDED_RULE list) | `kompile-process-engine` workflow models | model change only |
| Decision-table execution (`DROOLS_DECISION_TABLE`) | `kompile-compute-graph-drools` | Keep as-is; no changes |

---

## 4. Forks Requiring User Direction

### Fork A: Drools Inference Replace/Keep
**Recommendation above**: Replace `DROOLS_RULE` / `DROOLS_INFERENCE` step execution with `RecursiveQueryEngine` + PSL; keep `DROOLS_DECISION_TABLE`.

**If keep instead**: Drools inference stays but remains graph-blind (operates on string/Object `NodeFacts`), cannot consume `InferredFactStore`, and blocks native-image consolidation. Integration with the KB would require a manual ETL from `GraphNode` attributes to `NodeFacts` fields for every step.

**Migration cost to replace**: One `DatalogStepExecutor` class + a lightweight DRL-to-Datalog rule syntax migration for any DB-stored `ComputeNode.script` rows using DRL. No `.drl` files exist in the repo so the migration scope is limited to whatever DRL strings are in the production database.

### Fork B: Diagram Format — Mermaid-only vs BPMN 2.0 vs Both
**Recommendation above**: Both. Mermaid is already wired and costs nothing to keep. BPMN 2.0 is the business standard required for downstream tool integration (Camunda, Signavio, Visio).

**If Mermaid-only**: Faster to ship; no BPMN XML writer needed; sufficient for in-UI visualization. Blocks export to external process management tools.

**If BPMN-only**: Breaks the existing `app-mermaid-renderer` flow; requires replacing or wrapping with `bpmn-js` in the Angular frontend.

**If both**: `ProcessBpmnExporter` is a new ~200-line class producing BPMN 2.0 XML from `ProcessDefinition`; new endpoint `GET /api/process/mining/bpmn?factSheetId=N`. Frontend adds a download button. Cost: ~1 day backend, ~0.5 day frontend.

### Fork C: Process Confidence — Single Best vs Confidence-Ranked Variants
**Recommendation**: Ship single best-confidence process first (lower scope); add ranked variants in Phase 2.

**Single best**: `ProcessSuggestion` with one `confidence` score; `BeliefReviser` Variant B offered only when contradictions are detected. This is the current shape — no model changes needed.

**Ranked variants**: `ProcessDiscoveryResult` wrapper holding `List<ProcessSuggestion>` ranked by `confidence`; each variant is a different `CaseCorrelation` strategy (connected-component vs. anchor-type) or different `noiseThreshold`. This requires a new response DTO and UI panel to compare variants side-by-side. Cost: ~2 days. High value for noisy/multi-process graphs.

---

## 5. Phased Build Plan

### Phase 1 — First Buildable Slice: PSL-Confidence + Role Binding (1-2 days)

**Deliverable**: a mined `ProcessSuggestion` where `confidence` incorporates PSL posterior scores, and each step carries role bindings derived from the KB.

**Concrete changes**:
1. `ProcessTreeToSuggestion.convert()` (`.../convert/ProcessTreeToSuggestion.java:54`) — add PSL score parameter; replace `Math.max(0.1, fitness × precision)` with weighted formula.
2. `MiningProcessDiscoveryService.discoverForFactSheet()` — after mining, run `ProcessCausalAnalyzer` + `ProcessPslInference`, pass PSL activation scores to converter.
3. New `RoleBindingExtractor` in `kompile-process-discovery/mining/extract/` — takes `EventLog` + `KnowledgeGraphService`, queries adjacency graph for PERSON/ROLE typed entities near activity nodes, returns `Map<String, String>` (step name → role label). Uses `ConjunctiveQueryEngine` from `kompile-graph-reasoning`.
4. `ProcessSuggestion.SuggestedStep` — add `roleBinding: String` field.
5. No UI changes needed in Phase 1 (confidence and role appear in existing JSON response).

**Tests**: extend `ProcessPslInferenceTest` and `ProcessMiningConversionTest` in `kompile-process-discovery/test/`.

### Phase 2 — BPMN Export (1-2 days)

**Deliverable**: `GET /api/process/mining/bpmn` returns valid BPMN 2.0 XML; frontend download button.

**Concrete changes**:
1. `ProcessBpmnExporter` in `kompile-process-discovery/mining/export/` — maps `ProcessTree` operators to BPMN gateway elements; maps role bindings to `<laneSet>/<lane>`; maps `graphNodeIds` to `<dataObject>` annotations.
2. `MiningDiscoveryController` — new `@GetMapping("/bpmn")` calling `ProcessBpmnExporter.export(ProcessTree, roleBindings)` returning `ResponseEntity<String>` with `Content-Type: application/xml`.
3. Angular: add download button to `process-mining.component.ts` calling the new endpoint.

### Phase 3 — Causal Step Pre-Conditions + GROUNDED_RULE per Step (2-3 days)

**Deliverable**: `ProcessStep` carries pre-conditions derived from causal arcs + a `rules` list of derived KB business rules.

**Concrete changes**:
1. Converter from `CausalEdgeType.ENABLES` / `TRIGGERS` arcs → `ProcessStep.trigger` / precondition expressions.
2. `GROUNDED_RULE` extraction from `InferredFactStore` (per `domain-object-generation-design.md` §2.2) scoped to the fact sheet's entity set → assign relevant rules to each step.
3. `ProcessDefinition.ProcessPhase.steps[].rules: List<String>` (rule IDs from `InferredFactStore`).

### Phase 4 — Contradiction-Aware Variant B + Confidence-Ranked Variants (2-3 days)

**Deliverable**: TMS-detected contradictions demote confidence; Variant B offered automatically; optional ranked-variant endpoint.

**Concrete changes**:
1. `ContradictionDetector` integration after Stage 5 in generation pipeline.
2. `BeliefReviser` re-run producing Variant B `ProcessSuggestion`.
3. Optional: `ProcessDiscoveryResult` wrapper DTO with ranked list.

### Phase 5 — Drools Inference Replacement (2-3 days)

**Deliverable**: `StepType.DROOLS_RULE` and `DROOLS_INFERENCE` step execution backed by `RecursiveQueryEngine` + PSL.

**Concrete changes**:
1. `DatalogStepExecutor` in `kompile-process-engine` implementing `NodeExecutor`; parses `ComputeNode.script` as Datalog rule text (or PSL rule text); calls `RecursiveQueryEngine.evaluate()` or `HlMrfMapInference.solve()`.
2. Register in process-engine auto-config; route `DROOLS_RULE` / `DROOLS_INFERENCE` step types to it.
3. `DROOLS_DECISION_TABLE` continues to route to `DroolsNodeExecutor`.

---

## 6. Open Questions

1. **DRL→Datalog migration**: Are there `ComputeNode.script` rows in any deployed instance using DRL syntax that are load-bearing? If yes, a DRL parser/transpiler is needed before Phase 5 removal. If the DB is empty or uses pseudo-DRL, migration is trivial.

2. **BPMN rendering in Angular**: `bpmn-js` (the standard frontend BPMN renderer, ~600 KB) vs. inline SVG from a backend layout pass vs. Mermaid augmented with swim-lane syntax. The `mermaid` npm package already in `package.json` does not support BPMN; a separate renderer is needed if BPMN diagrams are to be rendered in-app rather than only exported.

3. **Process variants UX**: When `BeliefReviser` produces Variant B (contradiction-revised), how does the user choose? A side-by-side comparison panel is needed; this is a non-trivial UI investment.

4. **Anchor type auto-detection**: `anchorType` in `MiningDiscoveryController` is currently user-supplied. Should the system auto-detect the dominant entity type in the fact sheet (by node count or ontology binding) as the default anchor? This would remove the manual parameter for most use cases.

5. **Decision-table DSL long-term**: If `DROOLS_DECISION_TABLE` is kept for FP&A, is the intent to keep XLS/CSV as the authoring format indefinitely, or to eventually build a native decision-table UI? The latter would remove the last hard Drools dependency.

6. **`SuggestionToDefinitionConverter` acceptance flow**: Currently, accepting a suggestion calls `ProcessEngineController.POST /api/process/definition` with a manually assembled body. Automating this via the converter is Phase 1 prep but needs a decision on whether the converter runs server-side (on the accept API) or client-side (the Angular accept button assembles the payload).
