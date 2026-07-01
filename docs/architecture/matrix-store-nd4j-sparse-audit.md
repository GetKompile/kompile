# Matrix-Store × nd4j SparseNDArray Audit

Status: **Phase A shipped** — 2026-07-01
Scope: audit the kompile matrix-store adjacency representation against nd4j's
`SparseNDArray` API; identify blockers to full replacement; define a 3-phase hybrid
migration plan; implement Phase A (per-edge-type CSR cache at the read/training boundary).

Companion docs: `dl4j-graph-features-integration-design.md`,
`sparse-graph-handling-design.md`, `graph-serialization-storage-audit.md`.

---

## 0. TL;DR

The kompile matrix-store uses pure-Java nested `ConcurrentHashMap` adjacency (COO-like) for
writes and builds dense `INDArray [n×n]` or re-sorts COO to CSR on every training read.
nd4j now ships a mature `SparseNDArray` class (CSR/COO/CSC/BSR) with native ops (SpMV,
SpMM, SpGEMM, edge-gather, segment-max, row-softmax, CSR subgraph extract). Full
replacement of the write path has three hard blockers (see §3). The 3-phase plan keeps the
mutable Java write path and integrates nd4j sparse at the **read boundary only**, in order
of impact:

- **Phase A (SHIPPED):** per-edge-type CSR cache at the training boundary — eliminates
  O(nnz·log nnz) stable sort on every `GraphToSameDiffDataset.buildRelationCsr` call.
- **Phase B:** replace `getCombinedAdjacencyMatrix()` dense allocation with
  `SparseNDArray.mv()` SpMV for PageRank / HITS.
- **Phase C (deferred):** persist CSR blobs to the vector store + wire
  `CsrSubgraphExtract` for LOD task #30.

---

## 1. Current AdjacencyMatrixGraph Representation

**File:** `kompile-app/kompile-data/kompile-graphs/kompile-knowledge-graph/src/main/java/
ai/kompile/knowledgegraph/matrix/model/AdjacencyMatrixGraph.java`

### 1.1 Write-side (in-memory, mutation-heavy during crawl)

| Structure | Java type | Role |
|---|---|---|
| `adjacencyData` | `Map<String, Map<Integer, Map<Integer, Float>>>` | COO forward: edgeType → srcIdx → tgtIdx → weight |
| `reverseIndex` | `Map<String, Map<Integer, Set<Integer>>>` | In-degree index: edgeType → tgtIdx → Set\<srcIdx\> |
| `relationTypeData` | `Map<String, Map<Integer, Map<Integer, String>>>` | Semantic relation type per directed edge |
| `edgeMetaData` | `Map<String, Map<Integer, Map<Integer, EdgeMeta>>>` | Per-edge confidence / description (M-7 feature) |
| `edgeCountByType` | `Map<String, AtomicInteger>` | O(1) edge count per type |

All maps are `ConcurrentHashMap` for thread-safety matching the original `putScalar` /
`getDouble` synchronisation model.  The M-7 `EdgeMeta` write-back feature (added
`AdjacencyMatrixGraph.java:363–405`) is **complete and not touched by this work**.

### 1.2 Read-side (on-demand)

| Method | Cost | Shape |
|---|---|---|
| `getCombinedAdjacencyMatrix()` L603 | O(nnz) dense fill, allocates `[n×n]` INDArray native memory | `[nodeCount, nodeCount]` |
| `getSparseEdges(edgeType)` L651 | O(nnz for type) Java scan, no INDArray | COO `SparseEdgeData` |
| `getNodeCount()` / `getEdgeCountByType()` | O(1) | — |

### 1.3 Memory profile (FP&A crawl, ~4 345 nodes / 15 172 edges, 7 edge types)

- **Before sparse refactor:** 7 × `[9000×9000]` FLOAT = ~2.3 GB VRAM.
- **After sparse refactor (current):** 15 172 × ~100 B Java overhead ≈ **1.5 MB heap**.
- **Training read cost (current):** each `GraphToSameDiffDataset.buildRelationCsr` call
  stable-sorts the COO for one edge type (`O(nnz·log nnz)`) and allocates three new
  INDArrays.  Called once per edge type per `build()` invocation — recomputed every call.

---

## 2. nd4j SparseNDArray Capabilities

**STEP 0 — jar check (verified 2026-07-01):**
```
jar tf ~/.m2/repository/org/eclipse/deeplearning4j/nd4j-api/1.0.0-SNAPSHOT/nd4j-api-1.0.0-SNAPSHOT.jar \
  | grep -E 'SparseNDArray|ops/impl/sparse'
```
Result: `org/nd4j/linalg/api/ndarray/SparseNDArray.class` + 47 sparse op classes including
`CooToCsr`, `CsrSpmv`, `CsrSpmm`, `CsrSpgemm`, `CsrSubgraphExtract`, `CsrEdgeGather`,
`CsrEdgeAggregate`, `CsrRowSoftmax`, `CsrSegmentMax`, `CsrToDense`, `DenseToCsr`.
**SparseNDArray and all relevant ops are present in the installed jar.**

**Key classes:**
- `org.nd4j.linalg.api.ndarray.SparseNDArray` — thin container for CSR/COO/CSC/BSR
  component arrays; not an `INDArray` subtype.
- `org.nd4j.linalg.api.ndarray.SparseFormat` — enum: CSR, COO, CSC, BSR.
- `Nd4j.sparseFromEdges(INDArray src, INDArray dst, INDArray weights, long rows, long cols)`
  → COO `SparseNDArray`; `src`/`dst` are cast to INT64 internally.

**Constructor verified:**
```java
// CSR from pre-built components:
new SparseNDArray(INDArray values,       // [nnz] FLOAT
                  INDArray colIdx,       // [nnz] INT32
                  INDArray rowPtr,       // [rows+1] INT32
                  long[] shape,          // {rows, cols}
                  SparseFormat.CSR)
```
`SparseNDArray.java:140` — validated against source at
`deeplearning4j/nd4j/nd4j-backends/nd4j-api-parent/nd4j-api/src/main/java/
org/nd4j/linalg/api/ndarray/SparseNDArray.java`.

**COO → CSR conversion (`toCsr()`, `SparseNDArray.java:455`):**
```java
CooToCsr op = new CooToCsr(indices, values, rows(), cols());
INDArray[] results = Nd4j.exec(op);
// results[0]=values, results[1]=colIdx, results[2]=rowPtr
return new SparseNDArray(results[0], results[1], results[2], shape, SparseFormat.CSR);
```

**Op inventory relevant to kompile:**

| Op / Method | Purpose | Phase |
|---|---|---|
| `SparseNDArray.toCsr()` | COO → CSR | A (used in cache build) |
| `SparseNDArray.toDense()` | CSR → dense INDArray | Testing |
| `SparseNDArray.mv(x)` | SpMV: A·x, used for PageRank/HITS | B |
| `SparseNDArray.mmul(B)` | SpMM: A·B (dense) | B |
| `SparseNDArray.normalizeRow()` | D⁻¹A, random-walk normalisation | B |
| `SparseNDArray.normalizeSymmetric()` | D̃^{-1/2}(A+I)D̃^{-1/2} — GCN | C |
| `CsrSubgraphExtract` op | Extract k-hop subgraph by row mask | C |
| `CsrEdgeGather` / `CsrEdgeAggregate` | MPNN E-step / N-step | future |
| `CsrRowSoftmax` | GAT edge-attention normalisation | future |
| `CsrSegmentMax` | GraphSAGE-max aggregation | future |

**Maturity assessment:** ops are native-backed, gradient-checked (backward ops present:
`CooToCsrBp`, `CsrSpmmBp`, `CsrSpmvBp`, `CsrSubgraphExtractBp`), and are callable from
SameDiff autograd graphs.  The `SparseNDArray` wrapper is a pure-Java thin container
(no finalizer, no AutoCloseable) — component INDArrays must be managed/closed by the caller.

---

## 3. Blockers to Full CSR-Native Replacement

Three structural issues prevent replacing the Java write path with a mutable native CSR
today.  They do **not** affect the read-side cache (Phase A).

### Blocker 1 — CSR insert is O(N + nnz) (crawl is write-heavy)

Inserting one edge into a CSR array requires shifting the suffix of `colIdx`/`values` and
incrementing all `rowPtr[src+1..n]` entries.  The crawl adds ~48 000 edges per FP&A run
across ~4 000 nodes; each insert is O(n + nnz) vs the current O(1) `ConcurrentHashMap.put`.
Native CSR is fundamentally a **read-optimised** format; the Java COO is the right
**write-side** structure.

### Blocker 2 — No native parallel sideband for `relationTypeData` / `EdgeMeta`

The M-7 `EdgeMeta` (confidence, bidirectional, description, metadata map) and semantic
`relationType` are Java-typed per-edge fields with no nd4j native equivalent.  Replacing
the adjacency with a SparseNDArray would require keeping separate parallel Java maps for
these fields anyway, eroding the simplification benefit.

### Blocker 3 — Subprocess serialization boundary

The adjacency is materialised from the vector store on startup and must survive the JDK-proxy
subprocess boundary (§graph-matrix-subprocess memory note).  SparseNDArray's component
INDArrays are off-heap native buffers; the current vector-store serialization path uses the
`SparseEdgeData` Java type as its wire format (`VectorStoreMatrixGraphStore`).  A native CSR
persistence layer would require a new serialization path and subprocess-proxy client stubs for
the three-array representation — significant scope.

---

## 4. Three-Phase Hybrid Plan

### Phase A — Per-edge-type CSR Cache at the Read/Training Boundary (SHIPPED)

**Goal:** eliminate repeated O(nnz·log nnz) stable sorts in `GraphToSameDiffDataset`.

**Implementation (this PR):**
- `AdjacencyMatrixGraph` gains a `Map<String, SparseNDArray> csrCache`
  (`ConcurrentHashMap`) and a `volatile int csrCacheNodeCount` sentinel.
- `getCsrForEdgeType(edgeType)` builds CSR on miss via
  `Nd4j.sparseFromEdges(...).toCsr()` and returns the cached entry on hit.
- Invalidation hooks added to **every adjacency mutation site**:

  | Mutator | Hook |
  |---|---|
  | `addSparseEdge(type, ...)` L413 | `evictCsrCache(type)` (per-type) |
  | `removeSparseEdge(type, ...)` L447 | `evictCsrCache(type)` (per-type) |
  | `removeNode(nodeId)` L855 | `csrCache.clear()` (all types, direct adjacency manipulation) |
  | `close()` L980 | `csrCache.clear()` + `csrCacheNodeCount = 0` |

  `mergeEdgeMetadata` only touches `edgeMetaData`, not `adjacencyData` — no CSR eviction.

- `GraphToSameDiffDataset.buildRelationCsr` uses `getCsrForEdgeType` first; falls back
  to the existing inline `cooToCsr` if `csr.rows() != n` (concurrent mutation guard).
- Unit test `CsrCacheTest` (5 tests): correctness (toDense match), cache-hit identity,
  per-type eviction isolation, removeNode full flush.

**Files changed:**
- `…/matrix/model/AdjacencyMatrixGraph.java` — csrCache field, getCsrForEdgeType,
  buildCsrForType, evictCsrCache, mutation hooks.
- `…/matrix/gnn/GraphToSameDiffDataset.java` — buildRelationCsr uses cache.
- `…/matrix/model/CsrCacheTest.java` — new test class (5 tests).

**Expected speedup:** first `build()` per fact-sheet graph still pays the sort cost (cache
cold); subsequent `build()` calls on the same un-mutated graph are zero-sort — the dominant
pattern during batch GNN/KGE training epochs.

### Phase B — SpMV/SpMM for PageRank / HITS (not yet started)

**Goal:** replace `getCombinedAdjacencyMatrix()` dense allocation with SpMV iterations.

**Current path:**
```java
// MatrixGraphAlgorithms.computePageRank
INDArray adj = graph.getCombinedAdjacencyMatrix();   // allocates [n×n] native
// power-iteration loop using adj.mmul(scores)
```

**Proposed path (Phase B):**
1. Add `getCombinedCsr()` to `AdjacencyMatrixGraph` — sums per-type CSRs via
   `SparseNDArray.add()` (or accumulate during cache build).
2. Replace `adj.mmul(scores)` with `combinedCsr.mv(scores)` — SpMV is O(nnz) vs
   O(n²) dense.
3. Row-normalize via `combinedCsr.normalizeRow()` instead of manual `INDArray.div`.

**Prerequisite:** measure `getCombinedAdjacencyMatrix()` allocation cost in production
(suspicion: n < 5 000 so the bottleneck is actually the normalisation loop, not the
allocation itself).  Profile before committing to Phase B.

**Files to change:** `MatrixGraphAlgorithms.java`, `AdjacencyMatrixGraph.java`.

### Phase C — Persist CSR Blobs + CsrSubgraphExtract for LOD (deferred)

**Goal:** persist the per-type CSR to the vector store so the cache survives restarts;
use `CsrSubgraphExtract` for server-side LOD (task #30, top-K-centrality seed + 1-hop
expand-on-click).

**Blockers for C:**
- Serialization format: `SparseNDArray` component arrays are off-heap; need
  `FlatBuffers` or a byte-array envelope in the vector-store adjacency document.
- Subprocess proxy: the matrix-subprocess client stubs need new CSR-oriented RPC methods.
- `CsrSubgraphExtract` output shape depends on a row-mask INDArray — needs a REST API
  design for the LOD endpoint.

**Deferred until** LOD task #30 is prioritised.

---

## 5. Gotchas and Invariants

- **`SparseNDArray` is not `AutoCloseable`** — its component INDArrays (rowPtr, colIdx,
  values) are off-heap native buffers managed by the ND4J workspace / GC finalizer chain.
  The cache holds strong references; `close()` / `csrCache.clear()` drops them so GC can
  reclaim.  Do not call `rowPtr.close()` manually — the array may be shared.
- **`rowPtr` length is `n + 1`**, not `n`.  If `getNodeCount()` grows (new nodes added
  without new edges for a cached type) the cached CSR is stale — detected by
  `csrCacheNodeCount != currentN` and the cache is flushed before the next build.
- **COO → CSR via `Nd4j.sparseFromEdges(...).toCsr()`** coalesces duplicate
  `(src, dst)` pairs by summing their weights (native `coo_to_csr` behaviour).  The Java
  write path uses `targets.put(tgtIdx, weight)` (last-write-wins), so duplicates cannot
  exist in `adjacencyData`.  The behaviours are equivalent for the kompile case.
- **INT32 vs INT64 indices:** `Nd4j.sparseFromEdges` casts src/dst to INT64 internally
  for the COO `indices` array; `CooToCsr` outputs INT32 `colIdx` and `rowPtr`.
  `GraphToSameDiffDataset.cooToCsr` also produces INT32 via `Nd4j.createFromArray(int[])`.
  Phase-A cache output is therefore type-compatible with the existing `CsrArrays` usage.
- **`mergeEdgeMetadata` does NOT evict the CSR cache** — it only modifies `edgeMetaData`,
  which has no representation in the CSR structure.  Evicting would be wrong: a neural
  overlay publishing a link-prediction score should not force a CSR rebuild.
- **Thread safety:** the `csrCache` `ConcurrentHashMap` is safe for concurrent reads but
  `csrCache.clear()` + `csrCacheNodeCount = currentN` are not atomic.  A concurrent reader
  between the two writes will see an evicted cache and trigger a redundant rebuild on the
  next call — safe (no stale data), just wasteful.  Locking here is not worth the
  contention given that `removeNode` / `close` are rare operations.
