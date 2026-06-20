# Interpretable, LLM‑free Business‑Process Derivation from the Knowledge Graph

**Status:** Proposed (2026‑06‑20)
**Scope:** Replace the bespoke process‑discovery heuristics with a principled, domain‑agnostic
process‑mining pipeline; couple the discovered structure into the existing causal/PSL/Bayesian
attribution layer; surface both in a unified UI.

---

## 1. Problem

We want to **derive business processes from the knowledge graph**, with three hard requirements:

1. **No LLM required.** Discovery must be a deterministic, inspectable algorithm. An LLM may
   *narrate* results but must never be load‑bearing.
2. **Interpretable.** Every intermediate artifact (the event log, the directly‑follows graph, the
   process tree, the conformance score) must be human‑readable and explain *why* the model looks
   the way it does.
3. **General.** A new domain (ledgers, tickets, manufacturing, clinical pathways) must work without
   anyone writing new code.

## 2. What exists today, and why it is not enough

`kompile-process-discovery/ProcessDiscoveryServiceImpl` (1,747 lines) is **~12 hand‑coded matchers**
— `analyzeEmailFlows`, `analyzeExcelFlows`, `analyzeAuthorPipelines`, `analyzeVersionChainWorkflows`,
`analyzeTopicClusterWorkflows`, `analyzeFormCollectionWorkflows`, `analyzeEmailAttachmentFlows`,
`analyzeDocumentReferenceFlows`, … — each special‑cased to one artifact type (email / Excel / doc).

Confirmed gaps (repo‑wide grep: no `EventLog`, `DirectlyFollows`, `ProcessTree`, or `InductiveMiner`
exist anywhere in the codebase):

| Gap | Consequence |
|---|---|
| No **case notion** | Cannot say "which events belong to the same process instance." |
| No **directly‑follows** abstraction | No interpretable control‑flow backbone; every matcher reinvents ordering. |
| No formal **process model** | No soundness guarantee; discovered "flows" can be unexecutable. |
| No **conformance / quality** metric | Cannot tell the user how well a model explains the data. |
| **Domain‑locked** | Only email/Excel/doc; a new domain yields nothing until code is written. |
| No `PRECEDES`/`FOLLOWS` edges | Control flow is never recorded back onto the graph for attribution to traverse. |

The causal layer (`kompile-event-attribution`) is, by contrast, strong: hand‑rolled PSL/HL‑MRF,
noisy‑OR Bayesian networks with exact variable elimination, MEBN/SSBN, 20 REST endpoints. But it
estimates edge strengths **structurally** (keyword classification in `CausalTraversal.classifyEdge`)
because it has no event statistics to learn from. The two subsystems are disconnected.

## 3. The standard pipeline (process mining)

Decades of process‑mining research (van der Aalst et al.; implemented in ProM / PM4Py / Celonis)
converge on one interpretable pipeline:

```
KG ──▶ Event Log ──▶ Directly‑Follows Graph ──▶ Process Discovery ──▶ Process Model ──▶ Conformance
       (case notion)   (DFG, frequencies)        (Inductive Miner)      (process tree)   (fitness/precision)
```

Each arrow is a transparent artifact you can render and inspect. We adopt it wholesale.

### 3.1 The case‑notion problem and the object‑centric answer

A knowledge graph has **no inherent case id**. Forcing a single case id is the classic mistake that
produces "spaghetti." The modern answer is **Object‑Centric Process Mining (OCPM / OCEL standard)**:
an event relates to *several* objects, and you compute a directly‑follows relation *per object type*.
Forcing one case id instead causes two named, well‑studied distortions — **convergence** (an event touching
several objects is duplicated, inflating frequencies and inventing false parallelism) and **divergence**
(same‑activity events on different objects collapse together, erasing causal independence). The canonical
recipe for building a per‑object directly‑follows relation straight from a graph is the **Event Knowledge
Graph (EKG)** construction of Esser & Fahland (2021): `:Event`–`:CORR`→`:Entity`, then one `:DF` chain per
entity ordered by timestamp. Our `EventLogExtractor` is exactly this construction over `GraphNode`/`GraphEdge`.

For kompile this maps naturally:

- **Object** = a graph entity (or a chosen *anchor* entity type, e.g. an invoice, a document, a
  ticket). The case notion becomes the **only** domain‑specific configuration — a small
  `CaseCorrelation` strategy — and everything downstream is domain‑agnostic.
- **Activity** = a typed projection of a node/edge (default: `entity_type` from
  `GraphNode.getMetadata()`, falling back to `NodeLevel`, or an edge `relationType`).
- **Timestamp** = `GraphNode.occurredAt` / `GraphEdge.occurredAt` (already present), with
  `metadataJson` time keys as fallback.

Correlation strategies (pluggable, no LLM):
- **Shared‑entity** — events that touch the same anchor entity form a case.
- **Connected‑component** — a weakly‑connected subgraph under selected edge types is a case.
- **Root‑anchored** — BFS from each node of an anchor type defines that case.

### 3.2 Discovery algorithms — what we use and why

| Algorithm | Output | Soundness | Noise | Interpretability | Use in kompile |
|---|---|---|---|---|---|
| **Directly‑Follows Graph** | frequency graph | n/a | filter by freq | ★★★★★ (just counts) | **Always on** — the baseline view |
| **Inductive Miner (IMf)** | process **tree** → Petri/BPMN | **guaranteed sound** | infrequent‑filter | ★★★★ (block cuts) | **Default discovery** |
| **Heuristics Miner** | dependency net | not guaranteed | dependency threshold | ★★★ | alternative for loose/"spaghetti" data |
| Alpha (α / α+ / α++) | Petri net | no | fragile | ★★★ | reference baseline only — *not* production |
| Declare / MINERful | constraints | n/a | support/confidence | ★★★ (rules) | Phase 4 — for loosely‑structured work |

**Why Inductive Miner is the default.** It is the only widely‑used discovery algorithm that
**guarantees a sound, block‑structured model** (no deadlocks, no dead activities) while staying
interpretable and polynomial‑time. It works by recursively finding a **cut** on the DFG and splitting
the log:

- **→ sequence**, **× exclusive‑choice**, **∧ parallel (concurrency)**, **↺ loop**.

Each cut is itself an explanation ("these activities are mutually exclusive"; "these run
concurrently"). The IMf ("infrequent") variant filters rare directly‑follows edges before cutting, so
it is robust to noise. Crucially, the process tree maps **exactly** onto kompile's executable model:

| Process‑tree node | kompile `ProcessDefinition` |
|---|---|
| block (→ / × / ∧ / ↺) | `ProcessPhase` |
| leaf activity | `ProcessStep` (+ `graphNodeIds`, `occurredAt`) |
| sequence `→` | `ProcessStep.dependsOn` |
| exclusive `×` | `conditionExpression` gate |
| parallel `∧` | independent steps (no mutual `dependsOn`) |
| loop `↺` | repeat / back‑edge |

So discovery produces a model that is simultaneously (a) human‑interpretable, (b) formally sound, and
(c) directly executable by the existing process engine.

## 4. The unification: process structure ⇄ causal attribution

The discovered control‑flow structure and the probabilistic causal model are **two views of the same
transition structure**. We couple them — this is the core architectural idea.

1. **DFG frequencies → causal priors.** The directly‑follows count `f(a→b)` and the Heuristics‑Miner
   dependency measure `(a⇒b − b⇒a)/(a⇒b + b⇒a + 1)` are exactly the data‑driven edge strengths that
   `NoisyOrCpt` and the PSL `Link(a,b)` atoms currently *guess* structurally. Feed real frequencies in.
   Source them from `kompile-event-observation`'s Beta‑Binomial counts (`ObservedEvent` already tracks
   `PROCESS_STEP_OCCURRENCE` / `CONNECTION_OCCURRENCE`). Result: **fully data‑driven, LLM‑free
   attribution priors.**
2. **Process‑tree operators → conditional‑independence skeleton.** `∧ parallel ⇒ independent`,
   `× exclusive ⇒ competing causes`, `→ sequence ⇒ directed dependency`, `↺ loop ⇒ feedback`. This is
   precisely the structure a Bayesian network needs — so the process tree gives a *principled* DAG
   skeleton, replacing the keyword heuristic in `CausalTraversal.classifyEdge`.
3. **Statistical independence test upgrades edge typing.** A constraint‑based test (a G‑test / χ² on
   activity co‑occurrence in the log, scaling up to **consensus causal discovery** — run PC + GES +
   LiNGAM and keep an edge only when ≥2 of 3 agree, the recipe from *Causal Process Mining* 2025)
   distinguishes genuine dependency from mere temporal adjacency — i.e. `CAUSES` vs `CORRELATES_WITH`
   **with evidence**, not keywords. This also enables `do(·)` interventions ("if step X were faster,
   how much would cycle time drop?").
4. **PSL encodes process constraints as soft logic.** The discovered control‑flow becomes weighted
   Declare→PSL rules — `Response(A,B)` ⇒ `w: Occurs(T1,A) & Before(T1,T2) >> Occurs(T2,B)`,
   `Precedence`, `ChainResponse`, `NotCoExistence` — with weights from each constraint's
   support/confidence. The existing hand‑rolled HL‑MRF engine already supports exactly this
   (Łukasiewicz t‑norm, convex MAP), so process semantics become declarative and interpretable, and
   the most‑violated rules surface as root‑cause candidates.
5. **Materialize `PRECEDES` / `FOLLOWS` edges.** Write the discovered directly‑follows relation back
   onto the graph via `GraphEdge.relationType` (the existing free‑form seam — no schema change), so
   attribution can traverse discovered control‑flow directly.

> One structure — the event log + DFG + process tree — feeds **both** the executable process model
> **and** the probabilistic causal model. No separate worlds.

## 5. Module / package layout

No new Maven module: `kompile-process-discovery` already depends on `kompile-knowledge-graph`,
`kompile-process-engine`, and `kompile-event-attribution`. New code lives under
`ai.kompile.process.discovery.mining`:

```
mining/
  log/         Event, Trace, EventLog, Activity, ObjectCentricLog
  extract/     CaseCorrelation (strategy), ActivityClassifier, EventLogExtractor (KG → log)
  dfg/         DirectlyFollowsGraph, DfgBuilder, DfgMetrics
  tree/        ProcessTree, ProcessTreeNode (SEQ/XOR/AND/LOOP/ACTIVITY/TAU), Cut
  miner/       ProcessMiner (interface), InductiveMiner (IMf), HeuristicsMiner, DfgMiner
  conformance/ ConformanceChecker (token replay), ConformanceResult (fitness/precision/simplicity)
  convert/     ProcessTreeToSuggestion  (→ existing ProcessSuggestion, zero downstream change)
  causal/      DfgCausalPriors (→ Bayesian/PSL), IndependenceTest (PC‑lite)
```

Output is the existing `ProcessSuggestion`, so the accept→`ProcessDefinition` path, the suggestion
store, and the `ProcessDiscoverySuggestionsComponent` UI need **no changes** to consume mined results.
A new `MiningProcessDiscoveryService` is registered alongside the bespoke matchers behind a config
flag (`kompile.process.discovery.engine = mining | heuristic | both`), so nothing regresses.

## 6. UI integration

- Promote a unified **Processes** workspace (today the Bayesian panel is 6 levels deep; PSL has *no*
  UI at all; ~10 attribution endpoints are orphaned).
- New panels:
  - **DFG viewer** — the interpretable frequency graph, with frequency / dependency **threshold
    sliders** (the interpretable knobs that replace hidden heuristics).
  - **Process‑model viewer** — the discovered block structure, rendered to BPMN/Mermaid through the
    existing `ProcessDiagram` SSE path.
  - **Conformance dashboard** — fitness / precision / simplicity, so users can trust the model.
  - **Causal coupling view** — the discovered process beside its attribution: a new **PSL panel**,
    plus the orphaned `sensitivity`, `mebn/structure`, `mebn/query/byType` endpoints. "Why" sits next
    to "what."

## 7. Phased roadmap

- **Phase 1 — LLM‑free mining core. ✅ DONE (12 tests green).** `log` + `extract` (EKG construction) +
  `dfg` + `tree` + `InductiveMiner` (4 cuts + flower) + `conformance` (footprint fitness/precision/
  simplicity — alignment‑based is a future upgrade) + `convert` → `ProcessSuggestion`;
  `MiningProcessDiscoveryService` + `MiningDiscoveryController` (`GET /api/process/mining/discover`,
  `/preview`); optional auto‑run via `MiningAutoDiscoveryListener` behind
  `kompile.process.mining.auto-discover=true`. Confidence is now earned from conformance, not guessed.
  **Delivers principled, general, LLM‑free process derivation.**
- **Phase 2 — Causal coupling. ◐ CORE DONE (3 tests).** `mining/causal/`: `DependencyMeasures`
  (Heuristics dependency + χ² independence test), `ProcessCausalAnalyzer` classifies each
  directly‑follows relation into the attribution `CausalEdgeType` (CAUSES/TRIGGERS vs CORRELATES_WITH,
  with evidence not keywords) and emits weighted Declare→PSL rules **validated against the real
  `PslRule` engine**; exposed at `GET /api/process/mining/causal`. **Live PSL inference wired** —
  `ProcessPslInference` builds a real `PslProgram` (directly-follows dependency strengths become `Link`
  truths) and runs the project's `HlMrfMapInference`, so the discovered process drives HL-MRF inference;
  `GET /api/process/mining/psl`. Remaining (cross‑module /
  matrix‑store, riskier): feed the dependency strengths into the live noisy‑OR CPTs +
  `KgPslProgramBuilder`; process‑tree → BN independence skeleton; materialize `PRECEDES` edges via
  `GraphEdge.relationType`.
- **Phase 3 — UI.** DFG viewer, model viewer, conformance dashboard, PSL panel; surface orphaned
  endpoints; promote the Processes workspace.
- **Phase 4 — Object‑centric generalization.** OCEL‑style per‑object‑type DFGs; Heuristics & Declare
  miners; cross‑object convergence/divergence.

## 8. References

**Discovery algorithms**
- van der Aalst, *Process Mining: Data Science in Action* (2nd ed., Springer 2016) — field reference.
- Leemans, Fahland & van der Aalst, "Discovering Block‑Structured Process Models from Event Logs — A
  Constructive Approach" (Petri Nets 2013); the **IMf** infrequent and **IMd** directly‑follows variants
  (BPM 2013 / IS 2018). Overview: <https://en.wikipedia.org/wiki/Inductive_miner>, manual
  <https://www.leemans.ch/publications/ivm.pdf>. **← the algorithm implemented here.**
- van der Aalst, Weijters & Maruster, "Workflow Mining: Discovering Process Models from Event Logs"
  (the Alpha algorithm, IEEE TKDE 2004).
- Weijters & van der Aalst, *Heuristics Miner* (TU/e BETA WP 166, <https://www.vdaalst.com/publications/p314.pdf>)
  — Phase‑2 dependency measure `dep(a,b)=(|a>b|−|b>a|)/(|a>b|+|b>a|+1)`.
- Augusto et al., **Split Miner** (KAIS 2018, <https://link.springer.com/article/10.1007/s10115-018-1214-x>);
  Günther & van der Aalst, **Fuzzy Miner** (BPM 2007).

**Knowledge graph → event log**
- Esser & Fahland, "Multi‑Dimensional Event Data in Graph Databases" — the **Event Knowledge Graph (EKG)**
  construction (<https://arxiv.org/abs/2005.14552>); Fahland, *Process Mining over Multiple Behavioral
  Dimensions with EKGs* (Process Mining Handbook 2022).
- **OCEL 2.0** object‑centric standard: <https://www.ocel-standard.org/>, <https://arxiv.org/pdf/2403.01975>;
  OC‑DFG and convergence/divergence: van der Aalst, "Object‑Centric Process Mining" (SEFM 2019),
  <https://arxiv.org/pdf/2209.09725>.

**Causal / probabilistic coupling**
- Spirtes, Glymour & Scheines (**PC**); Chickering (**GES**); Shimizu et al. (**LiNGAM/DirectLiNGAM**);
  Zheng et al. (**NOTEARS**). "Causal Process Mining" consensus pipeline (ACM 2025,
  <https://dl.acm.org/doi/10.1145/3771678.3771681>); "The WHY in Business Processes"
  (<https://arxiv.org/pdf/2310.14975>); van der Aalst, SEM root‑cause analysis (Springer 2021).
- Bach et al., **PSL / Hinge‑Loss MRFs** (JMLR 2017, <https://arxiv.org/abs/1505.04406>); Declare→PSL/MLN
  constraint encodings; Di Ciccio & Mecella, **MINERful** declarative miner
  (<https://github.com/Process-in-Chains/MINERful>).

**Tooling**: PM4Py (<https://pm4py.fit.fraunhofer.de>), ProM (<https://promtools.org>),
processmining.org (<https://www.processmining.org/process-discovery.html>).
