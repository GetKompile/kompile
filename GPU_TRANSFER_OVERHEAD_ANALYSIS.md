# GPU Transfer Overhead Analysis — Kompile Crawl/Graph/Embedding Stack

**Date:** 2026-06-19  
**Scope:** Static analysis of CUDA (ND4J/SameDiff/ONNX) codepaths in `kompile-app`  
**Reference crawl:** ~9,000 nodes / ~17,000 edges  
**GPU bandwidth baseline (estimate):** PCIe 4 x16 ≈ 32 GB/s H2D+D2H; on-die GPU memory bandwidth ≈ 600–900 GB/s

---

## Background: The Transfer-Overhead Rule of Thumb

A GPU op is worth the round-trip cost when its **operational intensity** (FLOPs per byte moved across PCIe) exceeds roughly the ratio of GPU FLOP/s to PCIe bandwidth. On a modern CUDA GPU:

- Peak FP32 TFLOPS / PCIe bandwidth ≈ ~10–25 TFLOPS / 32 GB/s ≈ **300–800 FLOPs per byte**
- The break-even operational intensity is therefore **~300–800 FLOPs/byte moved across PCIe**
- For very small tensors (< 1 KB), even the kernel-launch overhead (~5–50 µs) dominates

Below each codepath is classified using these thresholds.

---

## Codepath 1 — AdjacencyMatrixGraph / MatrixKnowledgeGraphService

### Storage Representation

`AdjacencyMatrixGraph` allocates one dense `FLOAT` `[currentCapacity × currentCapacity]` INDArray **per edge type**. The default initial capacity is `DEFAULT_INITIAL_CAPACITY = 1024`, growing by factor 1.5 on overflow.

**Edge types in use** (from `MatrixKnowledgeGraphService`):
- HIERARCHICAL
- EMBEDDING_SIMILARITY
- SHARED_ENTITY
- USER_DEFINED
- CITATION
- TEMPORAL
- CROSS_SOURCE

That is **7 possible edge-type matrices**.

### Size Estimates at 9,000 Nodes

| Scenario | Capacity | Bytes/matrix | Matrices | Total VRAM |
|---|---|---|---|---|
| Initial alloc (1024) | 1,024 | 4 MB | 7 | **28 MB** |
| After expansion (≥ 9,000 nodes → capacity 13,122) | 13,122 | **~688 MB** | 7 | **~4.8 GB** |
| If only 1–2 edge types populated | 13,122 | ~688 MB | 2 | **~1.4 GB** |

> **ESTIMATE NOTE:** The capacity expansion sequence is: 1024 → 1536 → 2304 → 3456 → 5184 → 7776 → 11664 → 17496. With 9,000 nodes the matrix will be at capacity 11,664 (single step before 17,496), yielding `11664² × 4 = ~544 MB` per matrix, **~3.8 GB for 7 matrices**.

**Key insight:** The graph is ~0.02% dense (17,000 edges / 9,000² possible edges = 0.00021). A 544 MB dense float matrix stores 134 million potential edge weights, of which only ~17,000 are non-zero. This is an **extreme VRAM waste** ratio of roughly 8,000:1.

### Per-Op GPU Analysis

#### 1a. `getNeighbors(nodeId, edgeType)`
- **Op:** Fetch one row `[1 × nodeCount]`, then `toFloatVector()` on the active slice `[1 × 9000]`
- **Data moved (D2H):** 9,000 × 4 bytes = **36 KB**
- **Compute:** Sequential scan of 9,000 floats for non-zero entries — trivially zero FLOPs (just comparison + conditional copy)
- **Operational intensity:** ~0 (pure memory read, no multiply-accumulate)
- **Transfer time (estimate):** 36 KB / 32 GB/s ≈ **1 µs** (dominated by kernel launch + synchronization overhead ~5–50 µs)
- **Verdict:** TRANSFER-BOUND. This is a pure row-gather with no compute. CPU would simply pointer-walk a HashMap neighbor list at zero transfer cost. The row is pulled from device into a Java float[] and then scanned. No GPU benefit whatsoever.

#### 1b. `getEdgeCount()` / `countEdgesByType()`
- **Op:** `subMatrix.gt(0).castTo(LONG).sumNumber()` on an `[N × N]` submatrix
- **Data moved:** N=9,000 submatrix: 81M floats × 4 bytes = **324 MB** (D2H for the reduction result is just 8 bytes; the computation is on-device)
- **On-device compute:** 81M comparisons + 81M int adds for sum = ~162 MFLOPs
- **Operational intensity (on-device):** 162 MFLOPs / 324 MB = **0.5 FLOPs/byte** (memory-bandwidth bound even on-device)
- **Verdict for GPU vs CPU:** The reduction runs on-device so no large PCIe transfer. BUT: this is a global reduction over a 324 MB mostly-zero matrix. A CPU counting non-zeros in a sparse adjacency list would take microseconds. The GPU reduction is justified only if called rarely (e.g., statistics endpoint) — and indeed it is (called in `getGraphStatistics()`). For this specific op, GPU is neutral to mildly beneficial. The sparse-matrix argument dominates the whole representation question.

#### 1c. `getNodeDegree(nodeId, edgeType, outgoing)`
- **Op:** `row.gt(0).castTo(INT).sumNumber()` on `[1 × nodeCount]`
- **Data moved (D2H for result):** 4 bytes; on-device over 9,000 float vector
- **Compute:** 9,000 comparisons + sum → trivial
- **Verdict:** TRANSFER-BOUND / KERNEL-LAUNCH-OVERHEAD-BOUND. The kernel launch for a 9,000-element reduction takes more time than just counting non-zeros in a per-node adjacency list. **CPU wins here.**

#### 1d. `getCombinedAdjacencyMatrix()`
- **Op:** Sum all 7 adjacency matrices element-wise
- **Data:** 7 × 544 MB = 3.8 GB on-device; result is 544 MB
- **Compute:** 7 × N² element-wise adds = 7 × 81M FLOPs = ~567 MFLOPs
- **Operational intensity:** 567 MFLOPs / 3.8 GB = **0.15 FLOPs/byte** (heavily memory-bandwidth bound even on-device)
- **Called by:** PageRank, HITS, shortestPath, degreeCentrality (via `MatrixGraphAlgorithms`)
- **Verdict:** IF staying with the dense representation, on-device is the only viable option (3.8 GB PCIe transfer would be catastrophic). But see representation critique below.

#### 1e. PageRank (`MatrixGraphAlgorithms.pageRank`)
- **Op:** Power iteration, 100 iterations max. Per iteration: matrix-vector multiply `[N × N] × [N × 1]`
- **N = 9,000:** `[9000 × 9000] × [9000 × 1]`
- **FLOPs per iteration:** 2 × 9,000² = **162 MFLOPs**
- **Total FLOPs (100 iters):** ~16.2 GFLOPs
- **Data size for matmul:** matrix = 544 MB (stays resident on GPU), pr vector = 36 KB
- **GPU throughput for 162 MFLOPs matmul at 10 TFLOPS peak:** ~0.016 ms/iter → 1.6 ms total
- **BUT:** 81M × 4 bytes must be read from VRAM each iteration — at 600 GB/s bandwidth: 0.54 ms/iter → **54 ms total for memory bandwidth alone**
- **Verdict:** The matmul is **memory-bandwidth bound** on-device (Roofline: ~81 MB per iter, ratio = 2 FLOPs/byte — far below CUDA's roofline threshold of ~200 FLOPs/byte). However, the matrix stays resident on GPU so there's no PCIe transfer during iteration. The 100-iteration PageRank on a 9,000-node graph can be done entirely on-device. **GPU-JUSTIFIED only if the matrix is already resident and not transferred back and forth.** If matrix must be moved to device just for PageRank, the 544 MB × PCIe transfer = ~17 ms one-way, which is comparable to the actual compute time — neutral.

#### 1f. HITS algorithm
- **Op:** Two matrix-vector products per iteration (A × h and Aᵀ × a)
- Same analysis as PageRank — **GPU-JUSTIFIED if matrix resident.**

#### 1g. `findMostSimilarNodes` in `MatrixGraphAlgorithms`
- **Op:** Per-node dot product of query embedding (768-dim) against all N node embeddings
- **Data (D2H per call):** calls `norm2Number().doubleValue()` per node — each is a separate scalar D2H copy, **N = 9,000 round-trips**
- **This is severely broken for GPU:** 9,000 separate `norm2Number()` calls each synchronize device→host. At 5–50 µs per sync: **45 ms – 450 ms** just in synchronization overhead, compared to ~1 ms for a vectorized batch norm2 over all embeddings.
- **Verdict:** TRANSFER-BOUND by design flaw. The correct approach is batched `matmul(query, embeddings.T)` then a single D2H copy of the similarity vector.

---

### Representation Verdict: Dense N² Matrix Is Wrong for This Graph

At 9,000 nodes / 17,000 edges (density 0.021%), a dense float matrix wastes ~8,000× memory and VRAM. For a sparse graph:

- **CSR (Compressed Sparse Row):** stores only 17,000 non-zero values + 9,001 row pointers = **~68 KB** vs 544 MB — a **~8,000× reduction**
- **Adjacency list (Java Map<String, List<String>>):** zero GPU memory, O(degree) neighbor lookup
- All graph algorithm traversals (BFS, PageRank on sparse graphs) are faster with CSR on CPU than dense matmul on GPU because the data volume is ~8,000× smaller

**For 9,000 nodes with 17,000 edges:** No GPU benefit from the dense matrix. The memory footprint alone (3.8 GB for 7 matrices) is a serious VRAM hazard when the embedding model and LLM are also using GPU memory.

---

## Codepath 2 — bge-base-en-v1.5 Embedding Model (AnseriniEmbeddingModelImpl)

### Architecture Note

`AnseriniEmbeddingModelImpl` runs **entirely in a subprocess** — it does NOT do any ND4J/ONNX compute in the main JVM. The main process communicates via IPC (subprocess launcher). This means the "GPU vs CPU" question applies to the *subprocess*, not the main JVM. There is no PCIe H2D/D2H cost within the main process for embeddings; the only overhead is subprocess IPC latency.

### Model Parameters

- **Model:** bge-base-en-v1.5
- **Size:** ~110M parameters (BERT-base architecture)
- **Embedding output dimension:** 768
- **Max sequence length:** 512 tokens (typical use 64–256 tokens for document chunks)

### Batch Sizes

From `AnseriniEmbeddingConfiguration`: `baseOptimalBatchSize = 32`, `baseMaxBatchSize = 64`  
From `AnseriniEmbeddingModelImpl`: `DEFAULT_OPTIMAL_BATCH = 32`, `DEFAULT_MAX_BATCH = 64`  
From `AnseriniIndexerServiceImpl.calculateAdaptiveEmbeddingBatchSize()`:
- 16 GB+ JVM heap → **128 docs/batch**
- 8 GB+ → **64 docs/batch**
- 4 GB+ → **48 docs/batch**
- < 4 GB → **32 docs/batch**  
From `AppIndexConfig`: `embeddingTargetBatchSize = 64`

### H2D/D2H Transfer Estimate per Batch

**Input (H2D):**
- Token IDs: batch × seq_len × 4 bytes (int32) = 64 × 512 × 4 = **128 KB**
- Attention mask: same = 128 KB
- Total H2D per batch: **~256 KB**

**Output (D2H):**
- Embeddings: 64 × 768 × 4 = **196 KB**

**Total PCIe per batch: ~452 KB**

**Transfer time at 32 GB/s:** 452 KB / 32 GB/s = **~14 µs**

### Compute (FLOPs) per Batch

A BERT-base forward pass with 12 layers, 768 hidden, 12 attention heads:
- Per-layer self-attention: 4 × batch × seq_len × hidden² = 4 × 64 × 512 × 768² ≈ **77 GFLOPs/layer**
- 12 layers → **~924 GFLOPs** per batch

**At 10 TFLOPS GPU throughput:** 924 GFLOPs / 10 TFLOPS = **~92 ms per batch**

**Operational intensity:** 924 GFLOPs / 452 KB = **~2,044,000 FLOPs/byte** — this is **~7,000× above the GPU break-even threshold**

### CPU Alternative Estimate

On a modern CPU (e.g., 8-core at ~100 GFLOPS INT8-quantized, ~50 GFLOPS FP32 AVX):
- 924 GFLOPs / 50 GFLOPS = **~18 seconds per batch on CPU FP32**
- With INT8 quantization + ONNX Runtime: ~2–4× faster = **~5–9 seconds per batch**

At 32 docs/batch with ~9,000 chunks to embed: 9,000 / 32 = 281 batches. On CPU: 281 × 8s = **~38 minutes**. On GPU: 281 × 92ms = **~26 seconds**.

**Operational intensity conclusion:** bge-base-en-v1.5 is **the most clearly GPU-justified workload in the entire stack** by an enormous margin (2M FLOPs/byte vs the 300–800 FLOPs/byte threshold). The transfer overhead is negligible (14 µs) compared to the 92 ms compute time (0.015% overhead).

### Subprocess IPC Overhead

The actual bottleneck is **subprocess IPC latency** (Java pipe or socket): typically 1–10 ms per batch round-trip. At 281 batches this adds 0.3–2.8 seconds — still negligible vs the compute savings.

**Verdict for embedding model: STRONGLY GPU-JUSTIFIED. Do not move to CPU.** The embedding model is the one workload that genuinely overcomes transfer overhead by orders of magnitude. Moving it to CPU would increase crawl time by ~80× (from seconds to tens of minutes for a 9,000-chunk corpus).

---

## Codepath 3 — KG Embeddings: TransE / RotatE

### Model Scale

From `KGEmbeddingConfig`:
- `embeddingDim = 100` (default for TransE), `100` real + `100` imaginary for RotatE
- `batchSize = 1024` (TransE), `512` (RotatE)
- Entities: ~9,000 (one per graph node + relation types); Relations: ~7 (edge types)

### Tensor Sizes

- **Entity embedding matrix:** 9,000 × 100 × 4 bytes = **3.6 MB** (both real and imag for RotatE: 7.2 MB)
- **Relation embedding matrix:** 7 × 100 × 4 bytes = **2.8 KB** (negligible)

### Training Loop Analysis

**Critical observation from `TransEModel.trainBatch()`:** The training loop is implemented as a **scalar row-by-row loop**:

```java
for (int i = 0; i < positives.size(); i++) {
    INDArray h = entityEmbeddings.getRow(hIdx).dup();  // row gather → D2H per triple
    INDArray r = relationEmbeddings.getRow(rIdx).dup();
    INDArray t = entityEmbeddings.getRow(tIdx).dup();
    double posScore = scoreVectors(h, r, t);  // norm2Number() → D2H scalar
    // ... per-negative loop ...
    diff.norm2Number().doubleValue();  // another D2H scalar per positive
}
```

**Per triple (with 1 negative sample):** 5 D2H scalar reads (`norm2Number()`) + 3 row gathers of 100 floats each = **~1,530 bytes transferred + ~15 D2H synchronization events**

**At 5–50 µs per sync:** 15 syncs × 25 µs avg = **375 µs overhead per triple**, vs ~0.1 µs of actual compute (vector ops on 100-dim vectors).

**Verdict: SEVERELY TRANSFER-BOUND due to per-element GPU use.** The training is a tight scalar loop where every `norm2Number()` call forces a device→host synchronization on 100-element vectors. The GPU kernel launch overhead alone (5–50 µs) dwarfs the 100-element FP32 add/norm compute (~0.01 µs on CPU).

**For a batch of 1024 triples:** 1024 × 375 µs = **384 ms GPU overhead** vs ~1 ms on CPU (pure float arrays, no JNI, no synchronization).

The TransE/RotatE implementation is the worst example of GPU misuse in the codebase: tiny vectors, per-scalar synchronization, a loop that prevents any batching at the GPU level. This is running *slower* than CPU, not faster.

### Prediction / Inference (scoreTriple, predictTails)

From `TransEModel.predictTails()`:
```java
INDArray expected = h.add(r);
for (int i = 0; i < entityEmbeddings.rows(); i++) {
    INDArray t = entityEmbeddings.getRow(i)  // D2H per row
    ...
}
```

Same problem: 9,000 separate row fetches of 100 floats = 9,000 × ~25 µs sync overhead = **225 ms** for what should be a single 3.6 MB matrix broadcast + vector subtraction taking **~6 µs on GPU** if vectorized correctly.

**Verdict: TRANSFER-BOUND. Should run entirely on CPU for the current scalar-loop implementation, OR be refactored to batched matrix ops.**

---

## Ranked Summary

### Rank 1 — CLEARLY GPU-JUSTIFIED: bge-base-en-v1.5 Embedding Inference

- **Operational intensity:** ~2,000,000 FLOPs/byte (7,000× above break-even)
- **Transfer overhead fraction:** < 0.02% of compute time
- **GPU speedup over CPU:** ~80× for FP32, ~20× for INT8-quantized CPU
- **Recommendation:** Keep on CUDA. Do not move to CPU. This is the only workload where GPU provides decisive value.

### Rank 2 — GPU-NEUTRAL/CONDITIONALLY JUSTIFIED: PageRank / HITS on Resident Matrix

- **Operational intensity (on-device matmul):** ~2 FLOPs/byte (memory-bandwidth bound on-device, but no PCIe transfer during iteration)
- **Condition:** Only justified if the matrix is already resident on GPU from a prior op. If the matrix must be transferred from CPU to GPU just for PageRank, it is neutral.
- **Better recommendation:** Since the whole dense-matrix representation should be replaced with sparse (see below), PageRank on a CSR matrix on CPU (using ND4J-native sparse ops or even a pure Java sparse PageRank) would be faster at this graph density.

### Rank 3 — TRANSFER-BOUND: `getEdgeCount()` / `countEdgesByType()` on N×N Submatrix

- **On-device reduction over 324 MB matrix** — no PCIe transfer, but processing 8,000× more data than necessary due to sparse-matrix-stored-dense
- **Recommendation:** Replace with sparse edge count (just `adjacencyList.size()` or maintain a counter on insert). Zero GPU ops needed.

### Rank 4 — TRANSFER-BOUND: `getNeighbors()` / `getNodeDegree()` Row Operations

- **36 KB row fetch → CPU scan** — no compute, pure memory read
- **Kernel launch overhead dominates** (~5–50 µs vs ~1 µs of useful work)
- **Recommendation:** Keep a Java `HashMap<String, List<NeighborEntry>>` adjacency list alongside (or instead of) the ND4J matrix for per-node operations. Zero GPU ops needed.

### Rank 5 — SEVERELY TRANSFER-BOUND: TransE/RotatE Training Loop

- **Per-triple D2H scalar synchronization dominates** (~375 µs overhead per triple on GPU vs ~0.001 µs on CPU)
- **GPU is making this 375× slower than CPU**
- **Recommendation (option A):** Run entirely on CPU — the model is tiny (3.6 MB) and 100-dim ops on float[] are trivial.
- **Recommendation (option B):** Refactor to true batched GPU ops: gather all positive+negative entity/relation rows as a mini-batch `[B × 100]` matrix, compute all scores in one vectorized pass, update all rows in one scatter.

### Rank 6 — TRANSFER-BOUND + BROKEN: `findMostSimilarNodes` per-node Loop

- **9,000 separate `norm2Number()` D2H syncs** = 45–450 ms overhead for a query that should take < 1 ms
- **Recommendation:** Replace with single `embeddings.mmul(query.reshape(768, 1))` → D2H of 9,000 × 4 bytes = 36 KB result. One kernel launch, one PCIe transfer.

---

## Critical Structural Issue: Dense N² Adjacency Matrix

**The most impactful finding is the representation itself.** At 9,000 nodes / 17,000 edges:

| Metric | Dense Float32 | Sparse (CSR/Adjacency List) |
|---|---|---|
| Memory per edge-type matrix | ~544 MB | ~136 KB (17,000 entries × 8 bytes) |
| Total VRAM for 7 edge types | **~3.8 GB** | **~1 MB** |
| Wasted storage ratio | 8,000:1 | 1:1 |
| getNeighbors() complexity | O(N) scan | O(degree) |
| GPU value | None for sparse ops | N/A (stays on CPU) |

The dense representation is consuming ~3.8 GB of VRAM (or heap if on CPU backend) to store 17,000 non-zero values. This VRAM competes directly with the embedding model and LLM inference.

**Recommendation:** Replace `Map<String, INDArray>` edge-type matrices with `Map<String, Map<String, Map<String, Double>>>` adjacency lists (or a proper CSR structure). Retain ND4J only for the few cases where true matrix operations are needed (PageRank, HITS) — and for those, build the compact `[actualNodeCount × actualNodeCount]` matrix on demand from the sparse representation.

---

## Concrete Recommendations (Priority Order)

1. **Embedding model: KEEP ON GPU.** bge-base-en-v1.5 is 2M FLOPs/byte — overwhelmingly GPU-justified. Moving it to CPU would cost 20–80× throughput.

2. **Replace dense adjacency matrices with sparse adjacency lists.** The 3.8 GB dense VRAM allocation for a 0.02%-dense graph is an architectural error. Saves 3–4 GB VRAM, eliminates the O(N²) getNeighbors scan, and makes all per-node operations O(degree) on CPU with zero GPU involvement.

3. **Move TransE/RotatE training to CPU.** The per-triple scalar loop design is 375× slower on GPU than CPU due to D2H synchronization overhead. Operate on pure Java `float[]` arrays (or use ND4J CPU backend explicitly for these tiny tensors). If GPU training is desired in the future, restructure to proper batched mini-batch ops.

4. **Fix `findMostSimilarNodes` to use batched matmul** instead of per-node loop. One `embeddings.mmul(query.reshape(dim,1))` call is correct; 9,000 separate `norm2Number()` calls are not.

5. **Make PageRank/HITS operate on a compact on-demand matrix** built from the sparse adjacency list (only actual node count × actual node count, not capacity × capacity). This brings the PageRank matrix from ~544 MB to `9,000² × 4 = 324 MB` (or smaller if a per-factsheet subgraph is used).
