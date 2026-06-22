# Sparse Graph Handling Design

## Problem Statement

Excel/CSV sources produce structurally sparse graphs. A typical tabular import creates:

- One **row-node** per spreadsheet row
- One **column-node** per column header
- One **value-node** per distinct cell value (or per cell, depending on extraction depth)
- Edges: `row → column` (has-column) and `column → value` (has-value) or `row → value` (cell-belongs-to)

The resulting graph is **bipartite** (or near-bipartite) with a very small number of edges relative to the
theoretical maximum. With N entities and E edges, density = E / (N*(N-1)) can easily fall below 0.01 for
a 1,000-row table.

This is structurally different from a dense social/citation graph where the absence of an edge between two
known entities is genuinely informative (i.e., disbelief). In a sparse tabular graph:

- Row R2 not being connected to Column C5 says nothing about whether R2 *could* have a value for C5 — we
  simply may not have extracted it, it may be an optional column, or the extraction may be partial.
- Absent edges reflect **extraction incompleteness or schema optionality**, not world-level falsity.

Treating absent edges as disbelief collapses the epistemic distinction between "not observed" and "observed
to be false", producing overconfident negative inferences and breaking downstream PSL/MEBN reasoning.

---

## Sparsity Metrics

### Density

For a directed graph with N nodes and E directed edges:

```
density = E / (N * (N - 1))    [directed]
density = E / (N * (N - 1) / 2)  [undirected]
```

A density below **0.10** is treated as "likely sparse" by default (`SparsityMetrics.SPARSE_THRESHOLD`).
This threshold is a tunable starting point; dense knowledge graphs can have density > 0.3, while bipartite
tabular graphs rarely exceed 0.05.

### Degree Distribution

- **Mean degree**: average number of edges per node (counting both in and out for directed graphs).
- **Median degree**: the 50th percentile degree. In bipartite tabular graphs, column-nodes tend to have
  very high degree (one per row), while value-nodes tend to have degree 1.

High variance (mean >> median) is a strong signal of a hub-and-spoke or bipartite structure.

### Bipartite Detection

A heuristic 2-coloring (BFS-based) is used to detect near-bipartite structure. The algorithm:

1. Pick an unvisited node, assign color 0.
2. BFS: assign alternating colors to neighbors.
3. Count "violations" — edges where source and target share the same color.
4. Declare "likely bipartite" when violations / total edges ≤ `BIPARTITE_VIOLATION_TOLERANCE` (default 0.05).

This tolerates minor schema irregularities (e.g., a row referencing another row) while correctly flagging
the dominant row↔column↔value structure.

---

## Absent Edges → Uncertainty, Not Disbelief

### The Core Epistemic Distinction

The Subjective Logic (SL) opinion triplet `(b, d, u)` — belief, disbelief, uncertainty — sums to 1.0.
In the confidence-evidence model used by Kompile:

- `d > 0` means we have **positive evidence against** the proposition.
- `u > 0` means we **have not accumulated enough evidence** to decide.
- A **vacuous opinion** `(0, 0, 1, baseRate)` means we have seen nothing at all — the proposition is
  completely open.

For a dense social graph: "Alice and Bob are not connected" after observing a full friend-graph is
mild disbelief (we looked and found nothing). For a tabular graph: "Row 42 has no explicit edge to
Column 'ShippingDate'" may mean:

- The column is optional and was left blank.
- The extraction did not emit an edge for that cell.
- The column simply does not apply to that row's type.

In all three cases, the correct epistemic state is **uncertainty** (vacuous or near-vacuous opinion),
not disbelief. Asserting disbelief would incorrectly tell downstream PSL rules that `HasColumn(row42,
ShippingDate)` is probably false, poisoning inferences like `if HasColumn(X, ShippingDate) then
ShipmentPresent(X)`.

### Mapping in `SparseEvidenceHelper`

| Situation | Opinion produced | Rationale |
|---|---|---|
| Observed cell value | `fromBetaEvidence(pos, 0, 0.5, 2.0)` | Beta-posterior with non-informative prior; pos = source trust |
| Unobserved pair (absent edge in sparse graph) | `Opinion.vacuous()` | No evidence at all; open world |
| Absent edge, caller knows graph is sparse | `opinionForAbsentInSparse()` same as vacuous | Explicit open-world declaration |

The `positiveEvidence / totalObservations` path produces intermediate belief appropriate to a Beta
posterior: at 10/10 observations, belief is high; at 1/20, belief is low but still meaningful.

---

## Degree-Aware / High-Cardinality Column Handling

Columns with very high degree (e.g., a "Category" column touching every row) require care:

- Their in-degree = number of rows; their value-nodes may have very low degree (just one type of value).
- PSL rules that propagate across `has_column → has_value` chains would fire on every row, causing
  combinatorial blowup.
- The correct mitigation is to detect high-degree column-nodes via degree distribution stats and either:
  - Apply coarser-grained reasoning (community-level, not node-level).
  - Gate rule propagation by an entropy/selectivity score derived from value-node degree distribution.

`SparsityMetrics` exposes `meanDegree` and `medianDegree` to help callers make this decision.
Future work: a per-column selectivity score (`1 / log(degree)` clipped to [0, 1]).

---

## Interaction with the Beta Evidence Model

`Opinion.fromBetaEvidence(pos, neg, baseRate, k)` — the Beta prior strength `k` controls how fast
belief accumulates:

- **Dense / well-observed graph** (`k = 2.0`, the default `evidencePriorStrength`): a single observation
  pushes belief modestly; the prior is meaningful.
- **Sparse / tabular graph**: a cell being present is typically a strong structural signal. Using the
  `structuralPriorStrength = 0.1` (from `KbConfig`) means a single trust-weighted observation yields
  belief ≈ 0.9 — appropriate for a cell that is definitively present.
- **Absent cell**: vacuous `(0, 0, 1, 0.5)` — the prior never gets a chance to accumulate, leaving the
  opinion fully uncertain.

The interplay: `SparseEvidenceHelper.opinionForObservedFact(pos, total)` calls
`Opinion.fromBetaEvidence(pos, total - pos, 0.5, 2.0)` — it uses the standard evidence prior so that
partial observations (some cells present, some absent in a multi-source join) still accumulate
incrementally. Callers that know the source is purely structural (a CSV header is definitively present
when the column exists) should prefer `Opinion.fromBetaEvidence(sourceTrust, 0, 0.5, 0.1)` directly.

---

## Interaction with Community / Subgraph Reasoning

Sparse bipartite graphs partition naturally into communities along the column dimension:

- All rows sharing a set of columns form one community.
- All rows of the same type (entity type attribute) form another.

Community detection algorithms (Louvain, label propagation) work on the adjacency structure; they are
unaffected by the open-world assumption. However, **PSL rules** that use community membership as a
prior should respect vacuous opinions for unobserved links. Concretely:

- `SameCluster(X, Y) & HasFeature(X, F) → HasFeature(Y, F)` — if `HasFeature(Y, F)` is absent, the
  correct seed is `Opinion.vacuous()` (open world), not `Opinion.fromObservedValue(0.0)` (false).
- The PSL engine's soft-truth propagation will then yield a soft posterior based on `SameCluster` weight
  and evidence from X, rather than being blocked by an artificial zero prior.

In practice, `SparseEvidenceHelper.opinionForAbsentInSparse()` should be the default seed for any
unobserved atom in a sparse-graph context before PSL/MEBN inference runs.

---

## Grounding in the Real API

All of the above is grounded in the live `ReasoningGraph` / `Opinion` API:

- `ReasoningGraph.entities()` and `.relations()` give the full node/edge collections.
- `ReasoningGraph.outgoing(id)` / `.incoming(id)` give adjacency in O(1) (indexed in `MutableReasoningGraph`).
- `ReasoningGraph.relationsOf(id)` is used to compute per-node degree (sum of outgoing + incoming).
- `Opinion.vacuous()` → `new Opinion(0, 0, 1, 0.5)` — the literal representation of "no evidence".
- `Opinion.fromBetaEvidence(pos, neg, baseRate, k)` — the Beta-posterior factory used for observed cells.

`SparsityMetrics.compute(graph)` reads these interfaces directly — it never touches a persistence store,
Spring context, or JPA entity.

---

## Tunables for KbConfig

The following parameters are candidates for addition to `KbConfig` (do not edit `KbConfig` without a
separate task — this is a reference list only):

| Config key (kb-prefix) | Field name | Default | Valid range | Description |
|---|---|---|---|---|
| `kbSparsityThreshold` | `sparsityThreshold` | `0.10` | `[0.001, 1.0]` | Density below which a graph is classified as sparse |
| `kbBipartiteViolationTolerance` | `bipartiteViolationTolerance` | `0.05` | `[0.0, 0.5]` | Fraction of edge violations allowed in bipartite heuristic |
| `kbSparseAbsenceBaseRate` | `sparseAbsenceBaseRate` | `0.5` | `[0.0, 1.0]` | Base rate for vacuous opinions in sparse absent-edge contexts |
| `kbSparseObservedPriorStrength` | `sparseObservedPriorStrength` | `2.0` | `[1e-6, 1000.0]` | Beta prior strength k for observed cells in sparse graphs |

All four are read-only at reasoning time — no writes needed during inference.
