# Graph-integrity + batching audit (2026-07-05)

Prioritized findings from the dogfood audit. Fix worst-first. Reference impl for SameDiff+Adam:
`RotatELearner.applyAdam()` / `SameDiffEmbeddingTrainer` (ND4J `GradientUpdater<Adam>` fused kernel).

## Area 1 — scalar INDArray access (JNI overhead) → batch
- **P0 RotatEModel** (knowledge-graph .../embedding/impl/RotatEModel.java): `predictTails` 820-826, `predictHeads` 858-864, `predictRelations` 889-900 (trig per row!), `findSimilarEntities` 921-931, `findSimilarRelations` 953-962 — `getRow(i)` per entity/relation. Fix: broadcast sub/pow/sum(1); `Transforms.cos/sin` on full `[R×d]` once; cosine via `entityReal.mmul(target.T)`.
- **P0 TransEModel** (same pkg): `predictTails` 662-666, `predictHeads` 690-695, `predictRelations` 718-723, `findSimilar*` 742-748/768-775. Fix: `entityEmbeddings.subRowVector(expected).norm2(1).toFloatVector()`; cosine via mmul.
- **P1 MatrixGraphAlgorithms**: `findConnectedComponents` ~735 `getDouble(current,j)` O(n²); `shortestPathDistances` ~786; `betweennessCentrality` ~944 (O(n²)×500 = 25M JNI). Fix: `getRow(current).toDoubleVector()` once per step, or BFS over CSR.
- **P1 VectorStoreMatrixGraphStore**: `flushNodeEmbeddings` 881-885 `getFloat(c)` per dim (5000×768≈3.84M); `restoreNodeEmbeddings` 1270-1273 `putScalar` per dim. Fix: `getRow(idx).toFloatVector()` / build `float[dim]` + `putRow`.
- **P1 AdjacencyMatrixGraph.getCombinedAdjacencyMatrix** 673-677: `getFloat`+`putScalar` per edge. Fix: HashMap<Long,Float> accumulate + one `Nd4j.create(float[],shape)`. (NOTE: `getCombinedCsr()` already does this pattern — the dense one should just be dropped where possible.)
- **P2 MatrixGraphAlgorithms result-export** getDouble loops: dangling ~184-188, PR result ~207-209, HITS result ~555-560, cosine-norm ~620-624 (`diviColumnVector`!), degree ~829-830. Fix: `toDoubleVector()` once; vectorized `diviColumnVector`.
- **P2 TensorHlMrfInference** 176 `getDouble(i,0)` loop; **SameDiffPslWeightGradient** 252-254, **SameDiffMebnStrengthLearner** 260-262 gradient extraction. Fix: `flat.toDoubleVector()`.
- P3 OCR (CRNNRecognizer/DBNetDetector) — low traffic; one toFloatVector each.

## Area 2 — KG models → model-staging
- RotatE/TransE: WIRED (KGEmbeddingJobService.writeKgeArtifact → ModelTrainedEvent("kge") → ModelDeploymentHook → GraphScopedDeployService). No gap.
- **Adam KGE (SameDiffKgeModel): flag-gated OFF — `useSameDiffKge()` defaults false.** Dead unless enabled.
- **PSL rule weights: BROKEN on the live path.** `ModelTrainedEvent("psl")` only published when `luceneGroundingFactory == null`; the default wires `DualStoreWeightStore` with a LuceneGroundingFactory → staging event SUPPRESSED → PSL weights never staged.
- **MEBN: partial.** `MebnWeightPersistenceAdapter.persist` writes JSON every cascade but `ModelTrainedEvent("mebn")` only on cascade 1 + every N (IncrementalReasoningOrchestrator ~1574). No `.sdz`, only JSON; SameDiff graph discarded per gradient.
- GNN: N/A (fixed heuristic, no params).

## Area 3 — sparse (dense [n×n] where CSR/adjacency-list belongs)
- **Rank1 betweennessCentrality: 500× dense [n×n]/request = ~37.5GB alloc @4345 nodes. No guard.** Fix: BFS over `getCombinedCsr()` adjacency lists (no INDArray).
- **Rank2 AdjacencyView.toAdjacencyMatrix** (graph-algorithms .../adjacency/AdjacencyView.java:120) `Nd4j.zeros(n,n)` every tool call, NO guard. Callers: PageRank/DegreeCentrality/WCC via GraphAlgorithmService. Fix: route to CSR sparse path or add n>2000 guard.
- Rank3 shortestPathDistances ~771, Rank4 findConnectedComponents ~704, Rank5 degreeCentrality ~823 (dense just for row sums → `getCombinedCsr().mv(ones)`).
- Rank6 computeSimilarityMatrix 606-628: dead code dense [n×n]; if wired use ANN/top-k.
- **Anserini: NOT a dense offender** (Lucene inverted index + HNSW KnnFloatVectorField). Correctly not CSR. No fix.

## Area 4 — SameDiff MEBN/PSL
- **MEBN (SameDiffMebnStrengthLearner):** SameDiff+batched+autodiff YES; **hand-rolled projected SGD (no Adam).** Fix: ND4J `GradientUpdater<Adam>` + project to [0,1], per RotatELearner.applyAdam 456-465.
- **PSL ≥1000 rules (SameDiffPslWeightGradient):** SameDiff+batched+autodiff YES; **no Adam + graph rebuilt every epoch (`sd.constant` per epoch).** Fix: Adam updater + `sd.placeHolder()` for distance arrays (reuse graph, like SameDiffEmbeddingTrainer).
- **PSL <1000 + Pseudolikelihood (PslRuleGradient/PseudolikelihoodLearner):** scalar Java loops. Fix: route through SameDiffPslWeightGradient (lower threshold) or vectorize scatter-add.
- PSL MAP (TensorHlMrfInference): analytic ND4J matrix ops — correct/intentional. SameDiffSgd stub deferred (intentional).
- Reference (RotatELearner/SameDiffEmbeddingTrainer): SameDiff+batched+autodiff+Adam fused — the target pattern.
