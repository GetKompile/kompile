# DL4J New Graph Features → Graph-Reasoning Integration (Design Sketch)

Status: **Draft / sketch** — 2026-07-01
Scope: integrate the new differentiable graph + GNN suite in `deeplearning4j`
(`../deeplearning4j`, commit `158b30e698` "differentiable graph-analysis + GNN-codegen
suite" — we **maintain** this repo, so DL4J-side changes, including new native ops, are
in-scope) into kompile's graph-reasoning library **alongside** MEBN and PSL, with real
**training**, **model staging** (SameDiff `.sdz`), **data formation** from the crawl graph,
and **crawl-pipeline** wiring.

Companion docs: `graph-embedding-learning-landscape.md`, `learning-loop-gap-analysis.md`,
`crawl-graph-reasoning-architecture.md`, `psl-mebn-knowledge-base-gaps.md`.

---

## 0. Thesis / TL;DR

DL4J now ships a **codegen'd, pure-SameDiff** graph suite: two namespaces `sd.gnn()`
(~27 message-passing layers) and `sd.graph()` (KGE scorers, differentiable diffusion,
self-supervised losses). It is autodiff-native, gradient-checked, and serializes to the
exact **`.sdz`** artifact kompile's model-staging already ingests.

Meanwhile kompile has **two disconnected KGE worlds** and **no GNN at all**:

1. `kompile-graph-reasoning/embedding/learn/*` — *real SameDiff* trainers
   (`SameDiffEmbeddingTrainer`, `Node2VecLearner`, `RotatELearner`+Adam) that are
   **never called by the crawl pipeline**.
2. `kompile-knowledge-graph/embedding/impl/*` — hand-rolled `TransEModel`/`RotatEModel`
   (manual `float[][]` / INDArray gradient math, written to dodge native-object OOM) —
   **this** is what actually runs in production via `KGEmbeddingJobService →
   KgeTrainingExecutor → LearningSubprocessLauncher`.

PSL/MEBN weight learning *is* already production SameDiff (`SameDiffPslWeightGradient`,
`SameDiffMebnStrengthLearner`). So autodiff-in-a-bounded-subprocess is a **solved,
deployed pattern** here — we are not inventing infrastructure, we are plugging a
better model family into rails that already exist.

**The integration is therefore mostly wiring, not greenfield.** Four moves:

- **M1 — Unify KGE on `sd.graph()` scorers.** One `SameDiffKgeModel implements
  KGEmbeddingModel` replaces both hand-rolled production models *and* the reasoning-lib
  `RotatELearner`, killing the two-worlds problem.
- **M2 — Add a GNN encoder** (`sd.gnn()`), a genuinely new capability: message-passing
  node embeddings that fuse text features + graph structure, trained per fact-sheet.
- **M3 — Close the loop into reasoning.** Feed learned triple scores / node embeddings
  into MEBN (`OpinionStore` + `Opinion.fromEmbeddingScore`, `PriorProvider`) and PSL
  (`PslProgram.registerFunction` triple scorer, `observe`) via seams that **already
  exist** in the reasoning lib.
- **M4 — Stage + serve as `.sdz`.** `saveShardedOptimized(.sdz)` → `POST
  /api/staging/graph/{proj}/{graph}/deploy type=kge` → `ManagedSubprocessLauncher` serving.

**Governing principle (decided 2026-07-01): prefer DL4J over hand-rolled, everywhere it
has an equivalent.** DL4J-backed is the go-forward primary for KGE scorers, the GNN
encoder, community detection, centrality, link-prediction eval, and negative sampling; the
hand-rolled kompile impls (`TransEModel`/`RotatEModel`, `Node2VecLearner`, the reasoning-lib
`community/` code) are demoted to fallback and retired once parity + no-OOM are validated.
Only PSL's HL-MRF and the MEBN engine stay hand-rolled (no DL4J equivalent). And because we
also maintain DL4J, capability gaps there (e.g. variable-size-graph batching, §7) are closed
*in DL4J* — with native ops where that's the right tool — not worked around in kompile.

Everything is **per fact-sheet** (`graphId = "factsheet_"+id`). That is not incidental —
it is the key that makes this tractable (see §7 batching).

---

## 1. What DL4J's new graph suite provides

Source: `../deeplearning4j`, commit `158b30e698` (branch checkpoint, 2026-06-29) +
`b4c7255d30`. Pure SameDiff compositions *today* (no new native ops yet — the batching work
in §7 will add native CPU+CUDA ops); Kotlin codegen DSL emits both a lazy `SDGraph`/`SDGNN`
and eager `NDGraph`/`NDGNN` form.

### 1.1 `sd.graph()` — differentiable graph analysis + KGE
`org.nd4j.autodiff.samediff.ops.SDGraph` (codegen from `codegen/.../ops/Graph.kt`)

- **KGE triple scorers** (all differentiable): `transE`, `transH`, `transET` (temporal),
  `distMult`, `complEx`, `rotatE`, `tuckER`, `convE`, `holE`; plus
  `marginRankingLoss(posScore, negScore, margin)`.
- **Differentiable diffusion / propagation**: `personalizedPageRank(aNorm, seed, alpha,
  iters)`, `correctAndSmooth(...)`, `katzIndex`, `simRank`, `labelPropagation(...)` (CSR).
- **Differentiable graph construction**: `cosineSimilarity`, `gaussianSimilarity`,
  `correlationMatrix`, `knnGraph`.
- **Link-prediction heuristics** (on adjacency): `commonNeighbors`, `adamicAdar`,
  `resourceAllocation`, `preferentialAttachment`, `jaccardTopology`.
- **Self-supervised losses**: `graceLoss` (InfoNCE), `dgiLoss` (Deep Graph Infomax),
  `bgrlLoss` (BGRL), `vgaeKlLoss` + `vgaeReparam` + `innerProductDecoder` (VGAE).
- **Pooling / readout**: `topKPool` (SAGPool), `sortPool` (DGCNN), `set2Set`,
  `clusteringCoefficient`.

### 1.2 `sd.gnn()` — message-passing layers
`org.nd4j.autodiff.samediff.ops.SDGNN` (codegen from `codegen/.../ops/GNN.kt`)

~27 layers, all gradcheck-verified CPU+CUDA: `gcnConv`, `gatConvHead`/`gatV2ConvHead`,
`sageMean`/`sageMax`/`sagePool` (GraphSAGE), `ginConv`, `chebConv`, `rgcnConv`,
`compGcnConv`, `pnaConv`, `gcniiConv`, `appnp`, `ggnn`, `graphTransformer`, `nnConv`
(MPNN/edge-conditioned), `rgatConvHead`, `hgtConvHead`, `han`, `temporalGcn`, plus
`jkNetConcat`/`jkNetMax`, `pairNorm`, `graphNorm`.

**Multi-relational** (`rgcnConv`, `compGcnConv`, `rgatConvHead`, `hgtConvHead`, `han`)
take **one CSR triple per relation type** — this maps 1:1 onto kompile's
`relationType`-keyed adjacency (`AdjacencyMatrixGraph.adjacencyData` is keyed by edge
type; `getEdgeTypes()` enumerates them).

### 1.3 Input format (critical)
Every op wants **CSR**: `aNormVals[nnz]` (float/double), `colIdx[nnz]` **INT32**,
`rowPtr[N+1]` **INT32**, plus node features `X[N,F]`. Helper:
`sd.sparse().denseToCsr(adj, threshold) → [values, colIdx, rowPtr]`.
kompile's store gives **COO** (`getSparseEdges().indices` = `List<int[2]>`), so we need a
small COO→CSR sort (or feed a dense adjacency into `denseToCsr`). See §4.1.

### 1.4 DL4J layer wrappers + non-differentiable utilities
- `SameDiffLayer` subclasses `GcnLayer`/`GatLayer`/`GinLayer`/`GraphSageLayer` in
  `deeplearning4j-nn` — DL4J-config style; call `setAdjacency(sparseNDArray)` before init.
- Deterministic pure-Java utilities (no autodiff, drop-in): `SpectralClustering`,
  `LouvainCommunityDetection`, `GraphCentrality` (Brandes betweenness + BFS closeness),
  `LinkPredictionEvaluation` (AUC/AP/Hits@K/MRR), `EdgeSplitter`, `NegativeEdgeSampler`,
  `GraphicalLasso`, `MutualInformationGraph`, `BgrlTargetUpdate`.

### 1.5 Training + serialization
- **Train**: standard SameDiff — `TrainingConfig.builder().updater(new Adam(..)).build()`
  + `sd.fit(dataset)`, or manual `sd.calculateGradients(...)`. Self-supervised losses let
  us train **without labels** (DGI/GRACE/BGRL over the crawl graph).
- **Export**: `sd.saveShardedOptimized(file, /*saveUpdaterState*/ false, outputVars)` →
  **`.sdz`** (ZIP of SDNB FlatBuffer shards; constant-folded / DCE'd / fused for
  inference; updater state dropped).
- **Load / infer**: `SameDiff.load(file, false)` (auto-detect) → `sd.outputSingle(
  placeholders, "score")`; `sd.outputDirect(...)` fast path for tight loops;
  `setDspShapesFrozen(true)` + `setDspCompilationMode(REDUCE_OVERHEAD)` for a warm server.
- **Import** (future): `OnnxFrameworkImporter` / `TensorflowFrameworkImporter` → SameDiff.

**Maturity caveat:** it's a *branch checkpoint* — ops + gradchecks are solid, but there is
**no end-to-end training example** and **no variable-size-graph mini-batching** (one graph
= one sample). Near term, per-fact-sheet full-graph transductive training sidesteps both
(§7); the durable fix is to add batching **in DL4J** (native block-diagonal / segment ops
wired into the DSP dynamic-shape + segments layer), which also produces the missing e2e
example — see §7.

---

## 2. Where kompile stands today

### 2.1 The two KGE worlds (the core problem)
| | Reasoning-lib learners | Production models |
|---|---|---|
| Path | `kompile-graph-reasoning/embedding/learn/` | `kompile-knowledge-graph/embedding/impl/` |
| Classes | `SameDiffEmbeddingTrainer` (SGNS), `Node2VecLearner`, `RotatELearner` | `TransEModel`, `RotatEModel` |
| Math | **real SameDiff autodiff** + Adam | **hand-rolled** `float[][]` / INDArray, manual gradients |
| Why hand-rolled | — | dodge ~38k tracked `OpaqueNDArray` per batch → 82 GB cap in ~70s |
| Persistence | `model.fb` (FlatBuffers) + `mapping.json` | `KGEmbeddingModel.saveEmbeddings()` → vector store |
| Wired to crawl? | **No** (no caller in crawl path) | **Yes** — `KGEmbeddingJobService → KgeTrainingExecutor → LearningSubprocessLauncher`, crawl KGE warm-start |

So "we don't do meaningful training/model deployment" resolves precisely: **the real
SameDiff trainers are unwired; the wired production trainers avoid SameDiff on purpose.**
DL4J's codegen scorers dissolve this — they are SameDiff but memory-disciplined, and they
run in the *same bounded subprocess* the production path already uses.

### 2.2 What IS production SameDiff already
- `learning/SameDiffPslWeightGradient` — PSL rule-weight gradient (structured perceptron),
  one `calculateGradients("w")` for all K weights; triggered ≥1000 ground rules.
- `learning/SameDiffMebnStrengthLearner` — MEBN edge-strength MSE fit + projected gradient.
- Both run via `ReasoningLearningExecutor → LearningSubprocessLauncher → LearningSubprocessMain`,
  bounded by `-Dorg.bytedeco.javacpp.maxphysicalbytes`. **This is the rail M1/M2 ride on.**

### 2.3 MEBN / PSL injection seams (already present)
The reasoning lib was built anticipating learned inputs. Highest-leverage seams:

- **PSL** `psl/PslProgram.registerFunction(name, ExternalFunction)` +
  `ExternalFunction.evaluate(String...) → [0,1]` — drop a trained triple scorer straight
  into rule bodies. `psl/PslProgram.observe(pred, value, args)` — bulk soft-truth.
  `embedding/EmbeddingPslEvidence.addSimilarityEvidence(program, table, "similar", thr)`
  already bridges an `EmbeddingTable` → PSL evidence (cosine today; KGE tomorrow).
- **MEBN** `confidence/OpinionStore.put(atomKey, Opinion.fromEmbeddingScore(score, unc))`
  → consumed by `prior/CascadePriorProvider` tier (b) → SSBN root priors, *without
  touching SSBNGenerator internals*. `prior/PriorProvider` SPI (fully learned prior),
  `mebn/MFrag.setLocalDistribution(BiFunction)` (learned CPT), `mebn/MFrag.setEdgeStrength`
  (KGE-initialized noisy-OR strengths).
- **Embedding plug-in** `embedding/learn/EmbeddingLearner.learn(ReasoningGraph,
  EmbeddingConfig) → EmbeddingTable` (its `learnInto()` default writes vectors back into
  `GraphEntity.embedding()`). `embedding/learn/LinkPredictor.scoreTriple(h, relType, t)`.

### 2.4 Model staging + serving (the deploy half — already built)
- `ModelType` **already** has `KGE("kge")`, `PSL("psl")`, `MEBN("mebn")`.
- **`.sdz`** is the primary staged artifact format (`SDZSerializer`) — identical to DL4J's.
- `GraphScopedDeployController`: `POST /api/staging/graph/{projectId}/{graphId}/deploy`
  `{modelId, type:"kge", artifactPath:".../model.sdz", embeddingDim}` →
  `GraphScopedDeployService.deploy/stage/activate/rollback/findActive/listVersions`
  (per-`(project,graph)` versioning + rollback).
- Serving = extend `ManagedSubprocessLauncher` (proven by `EmbeddingSubprocessLauncher`
  `SameDiff.load()` + `output(placeholderMap, tensor)` + `configureBatchSize`, and
  `ServingSubprocessLauncher` HTTP :8091). GPU preemption via
  `ModelLifecycleManager.ManagedService` (suspend/resume). Discovery:
  `RegistryService.findActiveForGraph(projectId, graphId, ModelType.KGE)`.

### 2.5 Crawl pipeline + graph store (the data half)
- Steps (`CrawlPipelineStepRegistry.ALL_STEPS`): LOADING → DISCOVERING → CONVERTING →
  PREPROCESSING → ROUTING → GRAPH_PREP → **CHUNKING** → GRAPH_EXTRACTION → SURFACING →
  ENTITY_RESOLUTION → EDGE_COMPUTATION → VECTOR_INDEXING (∥) → **ENRICHMENT**.
- `@Primary` store = `MatrixKnowledgeGraphService` → `VectorStoreMatrixGraphStore`
  (subprocess `:8094`, `-Xmx32g`, off-heap Lucene MMap); main JVM is a thin JDK-proxy.
- Graph model: `GraphNode` (`nodeId`, `NodeLevel`, `factSheetId`, `metadataJson`,
  `vectorId`, `kgEmbedding` BLOB, `kgEmbeddingAlgorithm/version`), `GraphEdge`
  (`edgeType`, `relationType`, `weight`, `confidence`, `EdgeProvenance`,
  `kgRelationEmbedding` BLOB). Live object `AdjacencyMatrixGraph` holds sparse
  per-edge-type adjacency + `nodeEmbeddings[N×D]`.
- **`EdgeProvenance.INFERRED`** is the standing convention for all derived signals (OWL,
  PSL, cross-doc) — GNN outputs use the same stamp.
- **ENRICHMENT** (step 13) is where `OwlReasoningService.classify()` already materializes
  inferred edges + `owlInferredTypes`. That is the exact hook pattern for a GNN pass.

---

## 3. Target architecture — the closed loop

```
CRAWL (kompile-crawl-graph)
  … → GRAPH_EXTRACTION → ENTITY_RESOLUTION → EDGE_COMPUTATION ─┐
                                                               │  (ENRICHMENT hook, step 13)
  ┌──────────────── TRAIN/REFRESH (LearningSubprocess, maxphysicalbytes-bounded) ─────────┐
  │  (A) DATA FORMATION           (B) SAMEDIFF TRAIN            (C) EXPORT                 │
  │  matrixGraphStore             sd.gnn().gcnConv(...)         sd.saveShardedOptimized(   │
  │   .loadGraph("factsheet_"+id) sd.graph().rotatE(...)          model.sdz, false, out)   │
  │   .getSparseEdges()→COO→CSR   loss: dgiLoss/marginRanking   (updater dropped,          │
  │   .getNodeEmbeddings()→X      TrainingConfig+sd.fit()        graph-optimized)          │
  └───────────────────────────────────────────────────────────────────┬───────────────────┘
                                                                        ▼
                                     MODEL STAGING (type=kge/gnn, graph-scoped, versioned)
                                     POST /api/staging/graph/{proj}/{graph}/deploy
                                                                        │
              ┌──────────────────────────────────────────────────────── ┴───────────────────┐
              ▼ BATCH mode (node embeddings, offline)          ONLINE mode (triple scoring) ▼
   Load .sdz once, run GNN over full graph,         Gnn/KgeServingSubprocess (ManagedSubprocess)
   write embeddings + link-preds back                SameDiff.load + outputDirect (DSP frozen)
              │                                                          │
              ▼                                                          ▼
   WRITE-BACK onto graph                              REASONING CONSUMERS (kompile-graph-reasoning)
   createEdgeWithMetadata(…, INFERRED)                 PSL:  program.registerFunction("TripleScore", kge)
   storeNodeKgEmbedding(…)                             MEBN: opinionStore.put(a, Opinion.fromEmbeddingScore)
   updateNodeKgeMetadataBatch(gnn.embedding/…)         HybridReasoner.rank(graph, queryEmbedding)
   metadataJson["owlInferredTypes"] (type preds)       LinkPredictor.scoreTriple(...)
```

Two serving modes on purpose:
- **Batch/offline** for node embeddings + link-prediction refresh at ENRICHMENT — no
  long-running server; load `.sdz`, run once over the (bounded) fact-sheet graph, write back.
- **Online/served** for KGE triple scoring called *per PSL grounding* — needs a warm
  `ManagedSubprocessLauncher` with `outputDirect` + frozen DSP shapes for low latency.

---

## 4. Integration points (detailed)

### 4.1 Data formation — crawl graph → CSR training tensors  *(new component)*
New `GraphToSameDiffDataset` (home: `kompile-knowledge-graph`, next to the matrix store, or
a small `kompile-graph-reasoning/data` package). Reads via the **low-level matrix seam**
(avoids per-edge IPC):

```java
AdjacencyMatrixGraph g = matrixGraphStore.loadGraph("factsheet_" + factSheetId).orElseThrow();
List<MatrixGraphNode> nodes = g.getAllNodes();          // matrixIndex = row/col id
INDArray X = g.getNodeEmbeddings();                      // [N × D] node features
for (String edgeType : g.getEdgeTypes()) {
    SparseEdgeData e = g.getSparseEdges(edgeType);       // COO: indices List<int[2]>, weights, relationTypes
    // COO → CSR: stable-sort by (src,tgt); build rowPtr[N+1], colIdx[nnz] INT32, values[nnz]
}
```

Outputs per fact-sheet:
- **Homogeneous GNN**: combined CSR (`getCombinedAdjacencyMatrix()` then
  `sd.sparse().denseToCsr` for small graphs, or a direct COO→CSR sort for large) + `X`.
- **Multi-relational** (`rgcnConv`/`compGcnConv`): one CSR triple per `edgeType`.
- **KGE triples**: `(srcMatrixIndex, relationType, tgtMatrixIndex)` straight from
  `SparseEdgeData.indices` + `relationTypes` (the relation vocabulary is the distinct
  `relationType` set). Use DL4J `NegativeEdgeSampler` + `EdgeSplitter` for train/valid/negs.

Enumerate all graphs with `matrixGraphStore.getLoadedGraphIds()` (fast cache; **not**
`listGraphs()`). Node feature options for `X`: existing `nodeEmbeddings` (text/BERT),
optionally concatenated with structural features (degree, `GraphCentrality`, one-hot
`NodeLevel`). Keep dtypes: CSR indices **INT32**, values/X float.

### 4.2 Training — SameDiff GNN/KGE in the bounded subprocess
Reuse `LearningSubprocessLauncher` / `LearningSubprocessMain` (already
`maxphysicalbytes`-bounded, already the KGE warm-start executor). Add trainer builders that
compose DL4J ops:

```java
// KGE (RotatE) — unifies the two worlds (M1)
SDVariable score  = sd.graph().rotatE(hRe, hIm, relPhase, tRe, tIm);
SDVariable loss   = sd.graph().marginRankingLoss(posScore, negScore, gamma);

// GNN encoder (2-layer GCN) — new capability (M2)
SDVariable h1     = sd.gnn().gcnConv(X,  W1, b1, aNormVals, colIdx, rowPtr, N, N, true);
SDVariable z      = sd.gnn().gcnConv(h1, W2, b2, aNormVals, colIdx, rowPtr, N, N, false);
// self-supervised (no labels needed): DGI over a corrupted graph
SDVariable ssl    = sd.graph().dgiLoss(z, zCorrupt, discW);
```

Train with `TrainingConfig` + `sd.fit()` (or manual `calculateGradients`). **Memory rules
(mandatory, per OOM history):** prefer **CSR/sparse** ops (`gcnConv`, `sageMean`) over
O(N²) dense ops (`graphTransformer` adjMask, `simRank`, `katzIndex`); wrap batch scope in
an ND4J `MemoryWorkspace`; cap batch via the existing minibatch planner; serialize KGE vs
embedding training with the existing heavy-memory `Semaphore(1)` (crawl already fires KGE
warm-start @Async alongside embedding — do not stack two native-heavy jobs).

Two new production seams, both thin:
- `SameDiffKgeModel implements KGEmbeddingModel` (`kompile-app-core/kgembedding`) — its
  `train(List<Triple>, cfg)` builds a `sd.graph().rotatE/transE` scorer. Drop-in for the
  existing `KGEmbeddingJobService` path; **retires `TransEModel`/`RotatEModel`**.
- `GnnEmbeddingLearner implements EmbeddingLearner` (`kompile-graph-reasoning/embedding/learn`)
  — its `learn(graph, cfg)` builds an `sd.gnn()` encoder; `learnInto()` writes back.
  **Replaces `Node2VecLearner`** as the default (structural+feature vs structural-only).

### 4.3 Serialization + staging
Trainer exports `sd.saveShardedOptimized(new File(dir,"model.sdz"), false, outputVars)`
(inference-optimized, updater dropped). Then:

```
POST /api/staging/graph/{projectId}/{graphId}/deploy
{ "modelId":"gnn-<factSheetId>", "type":"kge", "artifactPath":".../model.sdz",
  "embeddingDim":128, "description":"GCN encoder, DGI-trained, fs=<id>" }
```

`GraphScopedDeployService.deploy()` copies + registers `ModelType.KGE` + activates for the
scope; prior version auto-available for `rollback`. (Optionally add a `GNN` value to
`ModelType`, but `KGE` already carries embedding models — reuse it initially.) Sidecar the
`mapping.json` (nodeId↔matrixIndex↔relationType vocab) as staged metadata so the server can
translate ids.

### 4.4 Serving
- **Online KGE scorer** (for PSL): `KgeServingSubprocessLauncher extends
  ManagedSubprocessLauncher` (`getSubprocessId`/`getTypeLabel`/`getMainClass`/`getHeapMb`).
  Subprocess `SameDiff.load(sdz,false)` + `setDspShapesFrozen(true)` +
  `REDUCE_OVERHEAD`; request contract `score(headId, relationType, tailId) → double` via
  `outputDirect`. Register `ModelLifecycleManager.ManagedService` for GPU preemption.
- **Batch node-embedding pass** (for write-back): no server; the ENRICHMENT hook loads the
  `.sdz`, runs the encoder once over the fact-sheet graph, and writes results. Mirrors
  `OwlReasoningService.classify()`.

### 4.5 Reasoning integration — MEBN + PSL (M3)
| Learned signal | Seam | Call |
|---|---|---|
| KGE triple score → PSL rule body | `PslProgram.registerFunction` + `ExternalFunction` | `program.registerFunction("TripleScore", a -> kge.score(a[0],rel,a[1]))`; rule `5.0: TripleScore(X,Y) & KnownEntity(X) -> Relation(X,Y) ^2` |
| KGE score → MEBN root prior | `OpinionStore` + `Opinion.fromEmbeddingScore` | `opinionStore.put("isActive(e)", Opinion.fromEmbeddingScore(cal(score),0.3))` → `CascadePriorProvider` tier (b) |
| Node/relation embedding → PSL similarity evidence | `EmbeddingPslEvidence.addSimilarityEvidence` | feed a KGE/GNN-populated `EmbeddingTable` (already cosine-based) |
| GNN embedding → hybrid retrieval | `HybridReasoner.rank(graph, queryEmbedding)` | vectors land via `EmbeddingLearner.learnInto` |
| KGE relation embedding → MEBN edge strength init | `MFrag.setEdgeStrength` (then `SameDiffMebnStrengthLearner` fine-tunes) | project RotatE relation phase → [0,1] as a warm start |
| Learned rule weights | `WeightLearner` / `SameDiffPslWeightGradient` | GNN relation-encoder initializes `w_r`, existing SameDiff path fine-tunes |

Priority order (least friction first): (1) `registerFunction` KGE scorer, (2) `OpinionStore`
KGE→prior, (3) `GnnEmbeddingLearner` swap, (4) learned `PriorProvider`, (5) GNN-parameterized
PSL weights.

### 4.6 Write-back onto the graph (M3 cont.)
At the ENRICHMENT hook, following the `INFERRED` convention:
```java
// link predictions (new edges)
knowledgeGraphService.createEdgeWithMetadata(src, tgt, EdgeType.EMBEDDING_SIMILARITY,
    0.87, "gnn_predicted", "GNN link score=0.87",
    "{\"linkPredictionScore\":0.87}", EdgeProvenance.INFERRED, factSheetId);
// structural node embeddings
knowledgeGraphService.storeNodeKgEmbedding(nodeId, emb, KGEmbeddingAlgorithm.RotatE, ver, now);
// learned overlays (community / centrality / gnn vec) — no re-embed triggered
knowledgeGraphService.updateNodeKgeMetadataBatch(updates);  // {"gnn.embedding","gnn.community","gnn.centrality"}
```
GNN-predicted **types** → `metadataJson["owlInferredTypes"]` (same key OWL uses) →
`GraphOntologyBindingService.allowedEntityTypes(factSheetId)` refines the next extraction
re-prompt. This is a **learned-ontology feedback loop** that the current OWL-only path
cannot produce (structural derivation is has-a only; a GNN classifier gives is-a/type
predictions).

---

## 5. Unifying the two KGE worlds (M1 detail)

1. Add `SameDiffKgeModel implements KGEmbeddingModel` using `sd.graph().rotatE/transE` +
   Adam `TrainingConfig`, trained in `LearningSubprocessMain` with `MemoryWorkspace` +
   `maxphysicalbytes` (the discipline the hand-rolled models were invented to get — now
   free via bounded SameDiff).
2. Route `KgeTrainingExecutor` to it behind a config flag with **DL4J as the intended
   default** — the flag exists only so we can fall back. Gate the actual default-flip on
   parity vs `TransEModel`/`RotatEModel` on `LinkPredictionEvaluation` (Hits@K/MRR) **and** a
   no-OOM run under a full crawl, on a fixed fact-sheet.
3. Retire `TransEModel`, `RotatEModel` **and** the reasoning-lib `RotatELearner` once that
   validation holds — one differentiable scorer family, one code path, staged as `.sdz`.
   (Direction decided 2026-07-01: prefer DL4J; the hand-rolled models are fallback, not the
   destination.)
4. `SameDiffEmbeddingTrainer` (SGNS substrate) stays as the text/SGNS learner or is folded
   into the same builder; `EmbeddingTablePersistenceAdapter` gets a caller at last
   (currently wired but never invoked from crawl).

Payoff: kills a whole class of drift (two impls of the same algorithm), gives KGE a
*deployable* artifact (`.sdz` in staging with versioning/rollback) instead of raw vectors
in the vector store, and makes the OOM story a *config* (`maxphysicalbytes` + minibatch)
rather than a *rewrite*.

---

## 6. Differentiable graph analysis — the other half of `sd.graph()`

Beyond KGE/GNN, `sd.graph()` makes analyses that kompile does *heuristically* today
**trainable end-to-end**:

- `personalizedPageRank` (differentiable) — kompile already does PPR/PathRAG/sparse-PPR for
  retrieval; a differentiable PPR lets retrieval weights be *learned* against downstream
  reasoning objectives.
- `correctAndSmooth` / `labelPropagation` — semi-supervised **entity typing** over the
  crawl graph from a few labeled nodes → feeds `owlInferredTypes` (a cheap, LLM-free type
  predictor that complements OWL structural derivation).
- Non-diff `LouvainCommunityDetection` + `GraphCentrality` — drop-in for the reasoning
  lib's `community/` package and for `gnn.centrality` node overlays; `LinkPredictionEvaluation`
  is the evaluation harness for every model above; `EdgeSplitter`/`NegativeEdgeSampler`
  give the KGE warm-start proper negatives/splits it currently lacks.

These are low-risk early wins (pure utilities, no serving) and good smoke tests that the
DL4J graph jar links and runs inside the kompile subprocess.

---

## 7. Batching, memory, and the per-fact-sheet strategy

DL4J's graph suite has **no variable-size-graph mini-batching** (one graph = one sample),
and kompile has a **loud OOM history** (TransE 82 GB, EmbeddingSubprocessMain 8192-batch →
36 GB). Two horizons:

**Near term — per-fact-sheet, full-graph, transductive.** Each `graphId="factsheet_"+id` is
a bounded graph (observed ~4.3k nodes / 15k edges). One `.sdz` per fact-sheet graph, staged
graph-scoped — exactly what `GraphScopedDeployController` versions. The per-fact-sheet
segmentation work (killed global `DEFAULT_GRAPH_ID`) already gives us the bounded unit; GNN
training rides on it for free. Discipline: prefer CSR ops (dense `[N×N]` for N≈4.3k is
~75 MB, but `simRank`/`katzIndex`/`graphTransformer` are O(N²) — gate/cap); `maxphysicalbytes`
+ `MemoryWorkspace` per batch + heavy-memory `Semaphore(1)` serializing KGE vs embedding
(all already in the codebase).

**Durable fix — close batching IN DL4J (decided 2026-07-01).** We maintain DL4J, so the
missing capability is added at the framework level, not reinvented as a kompile-side sampler.
Variable-size batching is a *dynamic-shape* problem, and DL4J already has the native infra
for it: the DSP dynamic-shape-plan + `segments` lifecycle
(`libnd4j/include/graph/DspSegmentLifecycle.h`, `NativeDynamicShapePlan_segments.cpp`, both
under active edit). The work — **native (CPU+CUDA) ops** for disjoint-union / block-diagonal
batching (ragged per-graph CSR → combined CSR + `batch` segment vector), a fused segmented
message-passing kernel, `segment_softmax` for batched GAT, and segment pooling/readout —
plus backward ops, codegen + FlatBuffers registration, gradchecks, and a batched e2e training
example (which also fills the suite's missing example). Correctness bar: **batched forward ==
per-graph forward concatenation.** This buys kompile true graph-level tasks, inductive /
neighbor-sampled training on graphs that don't fit, and cross-graph batching — none of which
the transductive stopgap can do.

---

## 8. Phased plan

- **Phase 0 — Link + smoke.** Add the DL4J graph jar to the learning subprocess classpath;
  run `LouvainCommunityDetection` + `GraphCentrality` + `LinkPredictionEvaluation` over one
  exported fact-sheet graph. Proves data formation (§4.1) and that the jar loads in-subprocess.
- **Phase 1 — Data formation.** `GraphToSameDiffDataset` (COO→CSR, `X`, triples, negs/splits).
  Unit-test round-trip vs `getSparseEdges`.
- **Phase 2 — M1 KGE unify.** `SameDiffKgeModel implements KGEmbeddingModel`; parity vs
  `RotatEModel` on Hits@K/MRR; flag-flip; export `.sdz`.
- **Phase 3 — Stage + serve.** Deploy `.sdz` via `GraphScopedDeployController`; stand up
  `KgeServingSubprocessLauncher`; wire `LinkPredictor.scoreTriple` → served model.
- **Phase 4 — M3 reasoning.** `PslProgram.registerFunction("TripleScore", kge)` +
  `OpinionStore` KGE→MEBN prior; measure reasoning quality delta on a fixed fact-sheet.
- **Phase 5 — M2 GNN + write-back.** `GnnEmbeddingLearner`; ENRICHMENT-hook batch pass;
  `createEdgeWithMetadata(INFERRED)` link preds + `gnn.*` node overlays + `owlInferredTypes`.
- **Phase 6 — Differentiable analysis.** correct-and-smooth entity typing; learned PPR
  retrieval weighting (optional / research).

Each phase is independently shippable and testable; Phases 0–3 are low-risk plumbing,
4–6 are where reasoning quality moves.

---

## 9. Risks / open questions

- **Branch-checkpoint maturity.** `158b30e698` is WIP; gradchecks pass but there is no
  end-to-end training example and array-input ops (RGCN/CompGCN/HAN varargs) are noted as
  active codegen work. Mitigation: start with `gcnConv`/`sageMean`/`rotatE` (simplest,
  best-tested); pin the DL4J commit; add our own e2e training test as the first artifact.
- **OOM regression.** The whole reason production KGE is hand-rolled. Mitigation is §7 —
  but this must be *validated under load* (full domain-planning crawl) before retiring the hand-rolled
  models, not just unit-tested.
- **CSR construction cost.** COO→CSR sort per fact-sheet per training run; cache the CSR
  alongside the graph or rebuild only on graph mutation.
- **Serving latency for PSL.** `registerFunction` is called *per grounding* — a naive
  per-call IPC to the serving subprocess will dominate. Batch-score candidate triples up
  front (`observe` bulk), or co-locate the scorer in the reasoning subprocess.
- **id/vocab translation.** nodeId↔matrixIndex↔relation-vocab must be staged with the model
  (`mapping.json`) and kept consistent across retrains; a graph mutation invalidates indices.
- **`ModelType.GNN` vs reuse `KGE`.** Reusing `KGE` ships faster; a dedicated `GNN` type is
  cleaner for registry filtering later. Start reused, split if needed.
- **Overlap with existing docs.** Reconcile with `graph-embedding-learning-landscape.md`
  and `learning-loop-gap-analysis.md` (a `.crawl-graph-reasoning-architecture.md.swp`
  suggests active editing nearby) — this doc is the *DL4J-new-features* slice; fold in.

---

## 10. Appendix — API cheat-sheet

**DL4J (`../deeplearning4j`)**
- Namespaces: `sd.gnn()` → `org.nd4j.autodiff.samediff.ops.SDGNN`; `sd.graph()` → `…ops.SDGraph`.
- CSR helper: `sd.sparse().denseToCsr(adj, threshold)`.
- Export: `SameDiff.saveShardedOptimized(File, boolean saveUpdater, List<String> outputs)` → `.sdz`.
- Load/infer: `SameDiff.load(File, boolean)`, `sd.outputSingle(Map, String)`, `sd.outputDirect(...)`,
  `setDspShapesFrozen(true)`, `setDspCompilationMode(REDUCE_OVERHEAD)`.
- Utils: `SpectralClustering`, `LouvainCommunityDetection`, `GraphCentrality`,
  `LinkPredictionEvaluation`, `EdgeSplitter`, `NegativeEdgeSampler`
  (`org.nd4j.linalg.api.ops.impl.graph.*`).
- Layers: `GcnLayer`/`GatLayer`/`GinLayer`/`GraphSageLayer` (`deeplearning4j-nn`).

**kompile — data formation / graph**
- `matrixGraphStore.loadGraph("factsheet_"+id)`, `.getLoadedGraphIds()`.
- `AdjacencyMatrixGraph.getAllNodes()` (matrixIndex), `.getEdgeTypes()`,
  `.getSparseEdges(edgeType)` (COO), `.getNodeEmbeddings()` ([N×D]),
  `.getCombinedAdjacencyMatrix()`.
- Write: `knowledgeGraphService.createEdgeWithMetadata(..., EdgeProvenance.INFERRED, fsId)`,
  `.storeNodeKgEmbedding(...)`, `.updateNodeKgeMetadataBatch(...)`, `.applyNodeEmbeddings(map)`.
- Ontology feedback: `GraphOntologyBindingService.allowedEntityTypes(fsId)`,
  `metadataJson["owlInferredTypes"]`.

**kompile — training / staging / serving**
- Prod KGE seam: `KGEmbeddingModel` (`kompile-app-core/kgembedding`), `KGEmbeddingJobService`,
  `KgeTrainingExecutor`, `LearningSubprocessLauncher`/`LearningSubprocessMain`.
- Reasoning-lib seam: `embedding/learn/EmbeddingLearner`, `SameDiffEmbeddingTrainer`,
  `embedding/learn/LinkPredictor.scoreTriple`.
- Staging: `ModelType.KGE`, `SDZSerializer`, `GraphScopedDeployController`
  (`POST /api/staging/graph/{proj}/{graph}/deploy`), `GraphScopedDeployService`,
  `RegistryService.findActiveForGraph`.
- Serving: `ManagedSubprocessLauncher` (+ `EmbeddingSubprocessLauncher` /
  `ServingSubprocessLauncher` patterns), `ModelLifecycleManager.ManagedService`.

**kompile — reasoning (MEBN / PSL)**
- PSL: `PslProgram.registerFunction`, `ExternalFunction`, `PslProgram.observe`,
  `EmbeddingPslEvidence.addSimilarityEvidence`, `WeightLearner`, `SameDiffPslWeightGradient`.
- MEBN: `OpinionStore.put` + `Opinion.fromEmbeddingScore`, `prior/PriorProvider`,
  `prior/CascadePriorProvider`, `MFrag.setLocalDistribution`, `MFrag.setEdgeStrength`,
  `SameDiffMebnStrengthLearner`, `SSBNGenerator.priorProvider`.
