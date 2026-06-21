# Graph / Node Embedding Learning — Landscape & Design Plan

*Target library:* `kompile-graph-reasoning` (infra-free: `nd4j-api` + `jackson-annotations` + `slf4j` + `lombok` only — no Spring, no Python/DGL/PyG).

*Date:* 2026-06-21

---

## 1. Purpose of This Document

The library already has:
- **Logical weight learning** (`learning/`) — PSL structured-perceptron & pseudo-likelihood learners sharing `ProjectedGradientOptimizer` and `PslRuleGradient`.
- **Dense ND4J inference** (`psl/TensorHlMrfInference`) — Łukasiewicz soft-truth MAP via dense matmul + Armijo line-search.
- **Sparse mini-batch inference** (`psl/SgdHlMrfInference`) — same semantics, no ND4J required.
- **Embedding utilities** (`embedding/Embeddings`) — cosine/dot/euclidean/normalize/mostSimilar over `double[]` stored in `GraphEntity.embedding()`.
- **Hybrid blending** (`hybrid/HybridReasoner`) — structural score (PSL/Bayesian) blended 0.6/0.4 with cosine similarity to a query vector.

What is **missing** is the *learning* of those `double[]` embeddings from graph structure alone, i.e., the training loop that goes from a `ReasoningGraph` to per-entity embedding vectors. The wider application already produces Knowledge-Graph Embeddings (TransE/RotatE) in `KGEmbeddingStorageService`, but that is Spring/JPA-coupled and cannot live in this infra-free library. This document surveys the embedding-learning landscape and specifies what to build next.

---

## 2. Technique Comparison Table

| Technique | Family | Core Objective | Input Required | Supervised? | Sparse or Dense? | ND4J Fit | New Heavy Deps? |
|-----------|--------|---------------|----------------|-------------|-------------------|----------|-----------------|
| **DeepWalk** | Random-walk + skip-gram | Skip-gram log-likelihood over truncated uniform random walks | Adjacency list | No (self-supervised) | Sparse walk gen + sparse SGD updates | Low (no matmul; dot-product per example) | None |
| **node2vec** | Random-walk + skip-gram | Same skip-gram; biased 2nd-order Markov walks with p/q | Adjacency list + (p, q) params | No | Sparse walk gen + per-edge alias tables + sparse SGD | Low | None |
| **LINE** | Proximity-based | 1st-order: σ(u·v); 2nd-order: softmax over edge-sampled contexts | Adjacency list (directed/weighted OK) | No | Sparse edge sampling + alias table | Low | None |
| **NetMF** | Matrix factorization | SVD of shifted PMI matrix (≈ log vol/b · Σ(D⁻¹A)^r) | Full adjacency matrix (sparse) | No | Sparse eigdecomp + dense SVD | Medium (ND4J SVD usable) | None (if sparse eigdecomp hand-rolled or replaced by power iteration) |
| **metapath2vec** | Random-walk + skip-gram | Type-conditioned skip-gram over metapath-guided walks | Adjacency list + node-type labels + metapath schema | No | Sparse | Low | None |
| **TransE** | Translational KGE | Margin-ranking: max(0, γ + d(h+r,t) − d(h'+r,t')) | Entity/relation triple list | No | Sparse SGD over triple mini-batches | Low (dot/L2 per triple) | None |
| **DistMult** | Bilinear KGE | Log-likelihood: Σ h·r·t for positive triples, BPR or NS for negatives | Entity/relation triple list | No | Sparse SGD | Low | None |
| **ComplEx** | Complex-valued KGE | Re(⟨h, r, conj(t)⟩) — complex dot product | Entity/relation triple list | No | Sparse SGD (2× params per embedding) | Low | None |
| **RotatE** | Rotation KGE | ‖h ∘ r − t‖ in complex plane; self-adversarial NS | Entity/relation triple list | No | Sparse SGD | Low (complex arithmetic only) | None |
| **GCN** | Message-passing GNN | Semi-supervised CE (supervised) or link-prediction / DGI (unsupervised) | Full adjacency (D⁻½AD⁻½) + optional node features | Optional | Dense matmul (A·H·W) per layer | High (ND4J matmul is the bottleneck) | None (matmul is nd4j) |
| **GraphSAGE** | Message-passing GNN (inductive) | NS link-prediction loss over sampled neighborhoods | Adjacency list + optional node features | Optional | Sparse neighborhood sampling + dense aggregation | Medium–High | None |
| **GAT** | Attention GNN | Same as GCN/GraphSAGE; attention coeff α = softmax(LeakyReLU(a·[Wh‖Wh'])) | Adjacency list + optional node features | Optional | Sparse neighbor iteration + dense head-concat | Medium–High | None |

**Supervised? column:** "No" = self-supervised / contrastive — the graph structure itself provides the training signal. No external labels needed.

---

## 3. Technique Deep-Dives

### 3.1 DeepWalk
**Paper:** Perozzi, Al-Rfou, Skiena. "DeepWalk: Online Learning of Social Representations." KDD 2014. https://arxiv.org/abs/1403.6652

**Objective:** Maximize log P(v_{i−w},...,v_{i+w} | Φ(v_i)) over all walk positions, where Φ: V → ℝ^d is the embedding table. Equivalent to maximizing:

    max Σ_{u∈V} Σ_{c∈walk_context(u)} log σ(Φ(u)·Φ(c)) + K · E_{v'~P_n}[log σ(−Φ(u)·Φ(v'))]

**Training mechanics:**
- Generate γ random walks of length l per node (paper: γ=80, l=40, window w=10).
- Each walk is treated as a "sentence"; pairs (center, context) become skip-gram training examples.
- **Negative sampling** (K=5–25): for each positive pair, draw K negative nodes from the unigram distribution raised to the 3/4 power. Update: `embedding[u] += lr · (1 − σ(u·c)) · c` for positive, `embedding[u] -= lr · σ(u·neg) · neg` for each negative.
- **Hierarchical softmax** (original paper): Huffman tree over node degree distribution; O(log|V|) parameters updated per example instead of O(|V|). Negative sampling is simpler to implement and performs similarly.

**Data structures:** Adjacency list for walk generation (O(V+E)); alias table for O(1) negative sampling; embedding matrix E ∈ ℝ^{V×d} and context matrix E' ∈ ℝ^{V×d}.

**Compute profile:** Walk generation is the first bottleneck (fully parallelizable per node). The SGD update is a handful of dot products and adds per example — no matrix multiplication. GPU adds overhead for single-example updates; batching 256–1024 examples into mini-batches makes ND4J matmul profitable.

**Fits infra-free lib:** Yes. Walks are plain Java. SGD with negative sampling is 3–4 lines reusing `ProjectedGradientOptimizer.step()` on each embedding slice (or a simpler raw `double[]` update).

---

### 3.2 node2vec
**Paper:** Grover, Leskovec. "node2vec: Scalable Feature Learning for Networks." KDD 2016. https://arxiv.org/abs/1607.00653

**Difference from DeepWalk:** Only the walk sampling policy changes. At each step t (currently at v, previously at t), the transition probability to neighbor x is proportional to:
- `1/p` if x == t (return to previous node)
- `1`   if d(t, x) = 1 (x is a common neighbor of t and v)
- `1/q` if d(t, x) = 2 (x is farther from t)

p < 1 → DFS-like exploration of distant structure; q < 1 → DFS; q > 1 → BFS / local homophily. All else (skip-gram objective, negative sampling) is identical to DeepWalk.

**Extra data structure:** For each directed edge (u→v), precompute an alias table over v's neighbors weighted by the three-case α_pq. Memory: O(|E| · avg_degree). This preprocessing dominates for dense graphs.

**When to use over DeepWalk:** When the graph mixes community structure (homophily, q > 1) and structural equivalence (same topological role, p/q < 1) and you want a hyperparameter to control that. For typical knowledge graphs or heterogeneous entity graphs, the default p=q=1 (= DeepWalk) is a fine starting point.

**Fits infra-free lib:** Yes. Additional code is the alias-table precomputation for each edge.

---

### 3.3 LINE
**Paper:** Tang, Qu, Wang, Zhang, Yan, Mei. "LINE: Large-scale Information Network Embedding." WWW 2015. https://arxiv.org/abs/1503.03578

**Objectives (trained separately):**

    O_1 = -Σ_{(i,j)∈E} w_{ij} · log σ(u_i · u_j)        [1st order, undirected only]
    O_2 = -Σ_{(i,j)∈E} w_{ij} · log p_2(v_j | v_i)       [2nd order, directed OK]
    p_2(v_j|v_i) = exp(u_j'·u_i) / Σ_k exp(u_k'·u_i)     [approximated by NS]

Final embedding: concatenate L2-normalized 1st-order + 2nd-order vectors → 2d dimensions.

**Key advantage over DeepWalk:** Native support for directed and weighted graphs without modification. Each training step samples one edge proportional to its weight (alias table), then draws negative nodes proportional to degree^(3/4).

**Fits infra-free lib:** Yes. The edge-weight alias table + negative sampling is straightforward Java. Two embedding matrices per order.

---

### 3.4 NetMF
**Paper:** Qiu, Dong, Ma, Li, Wang, Tang. "Network Embedding as Matrix Factorization: Unifying DeepWalk, LINE, PTE, and node2vec." WSDM 2018. https://arxiv.org/abs/1710.02971

**Core result:** DeepWalk with window T, b negative samples implicitly factorizes:

    M = log( vol(G)/bT · Σ_{r=1}^{T} (D⁻¹A)^r ) − log b

where vol(G) = Σ_{ij} A_{ij}. Take M_+ = max(M, 0) then SVD: U, Σ, V^T = SVD(M_+), return U_d · Σ_d^{1/2} as embeddings. No training loop needed after constructing M.

**Practical scaling:** Requires storing the adjacency matrix (sparse OK) and top-k eigenpairs via power iteration / Lanczos (O(k·|V|·avg_degree) per iteration). Practical for graphs up to ~1M nodes; hits memory limits beyond that.

**ND4J fit:** Moderate. ND4J has LAPACK-backed SVD (`Nd4j.linalg().svd()`). The Lanczos/power iteration for sparse eigenpairs must be hand-coded or approximated via repeated sparse matrix-vector products (feasible with ND4J sparse operations or a simple plain-Java power iteration loop).

**When to use:** When you want a batch, non-iterative baseline that is theoretically grounded. Good for small-to-medium graphs where you can afford one SVD. Faster than running 80 × |V| random walks + skip-gram SGD for small graphs.

---

### 3.5 metapath2vec
**Paper:** Dong, Chawla, Swami. "metapath2vec: Scalable Representation Learning for Heterogeneous Networks." KDD 2017. https://dl.acm.org/doi/10.1145/3097983.3098036

**Key addition:** Walks are guided by a metapath schema (e.g., Entity→Relation→Entity→Relation→Entity) so they alternate node types. The skip-gram softmax is conditioned on node type in the `++` variant — negative nodes drawn only from the same type as the context node, avoiding dominant-type bias.

**Relevance here:** The `ReasoningGraph` is inherently heterogeneous (`GraphEntity.type()` carries the node type, e.g., "DOCUMENT", "Person", "Activity"). metapath2vec is directly applicable and adds no algorithmic complexity beyond DeepWalk if the metapath is set to a single alternating pattern.

**Fits infra-free lib:** Yes. Walk generation filters neighbors by type; per-type alias tables add ~|types| extra alias structures.

---

### 3.6 TransE
**Paper:** Bordes, Usunier, Garcia-Duran, Weston, Yakhnenko. "Translating Embeddings for Modeling Multi-relational Data." NeurIPS 2013. No arXiv preprint; PDF: https://papers.nips.cc/paper_files/paper/2013/file/1cecc7a77928ca8133fa24680a88d2f9-Paper.pdf Abstract: https://proceedings.neurips.cc/paper/2013/hash/1cecc7a77928ca8133fa24680a88d2f9-Abstract.html

**Objective:** Margin-ranking hinge loss over triples (h, r, t):

    L = Σ_{(h,r,t)∈S} Σ_{(h',r,t')∈S'} max(0, γ + d(h+r,t) − d(h'+r,t'))

where d is L1 or L2 distance, γ is the margin (paper: γ=1–2; L1 norm wins empirically), and S' is the set of corrupted triples (replace h or t with a random entity). Entities embedded in ℝ^d constrained to ‖e‖≤1 (reprojected each mini-batch). **Relation embeddings are L2-normalized at initialization only**, not after each step — a common reimplementation bug.

**Training:** Mini-batch SGD, batch size ~120–512 triples. Each step corrupts the head or tail (50/50) to get one negative; reject corruptions that happen to be true triples. Update embeddings for the four involved entities/relation; reproject entity rows to unit ball. Paper hyperparams: dim=20 (WN18, margin=2), dim=50 (FB15k, margin=0.5), 1000 epochs.

**Limitation:** Cannot model symmetric relations (h+r≈t forces r≈0 when h=t). DistMult/ComplEx/RotatE address this.

**Fits infra-free lib:** Yes. The application's `KGEmbeddingStorageService` already produces TransE/RotatE embeddings but is Spring-coupled. An in-lib TransE trainer gives the same embeddings without Spring.

---

### 3.7 DistMult
**Paper:** Yang, Yih, He, Gao, Deng. "Embedding Entities and Relations for Learning and Inference in Knowledge Bases." ICLR 2015. https://arxiv.org/abs/1412.6575

**Scoring:** f(h, r, t) = ⟨h, r, t⟩ = Σ_i h_i · r_i · t_i (trilinear product; relations are diagonal matrices). Implementation: `(head ⊙ rel ⊙ tail).sum()` — three element-wise multiplies then sum. O(d) per triple.

**Loss and optimizer:** Margin-ranking loss (margin=1) optimized with **AdaGrad** (initial lr=0.1, L2 regularization λ=0.0001). Note: the paper's original results were under-optimized; Kadlec et al. 2017 (https://arxiv.org/abs/1705.10744) showed a tuned DistMult reaches MRR ~0.798 on FB15k.

**Limitation:** `score(h,r,t) = score(t,r,h)` is an **algebraic identity** — real multiplication commutes, so no amount of training can break this symmetry. Cannot model antisymmetric or inverse relations regardless of training. Good baseline for undirected or symmetric-relation-dominated graphs.

---

### 3.8 ComplEx
**Paper:** Trouillon, Welbl, Riedel, Gaussier, Bouchard. "Complex Embeddings for Simple Link Prediction." ICML 2016. https://arxiv.org/abs/1606.06357 PMLR: http://proceedings.mlr.press/v48/trouillon16.html

**Scoring:** Re(⟨h, r, conj(t)⟩) = Re(Σ_k w_{r,k} · e_{s,k} · ē_{o,k}). Decomposes to 4 real dot products:

    φ = ⟨Re(r), Re(h), Re(t)⟩ + ⟨Re(r), Im(h), Im(t)⟩
      + ⟨Im(r), Re(h), Im(t)⟩ − ⟨Im(r), Im(h), Re(t)⟩

Breaks symmetry: score(h,r,t) ≠ score(t,r,h) because complex conjugation is not commutative with multiplication. Optimized via logistic loss (log-sigmoid) with L2 regularization and AdaGrad (λ=0.01, best dim=150–200 on FB15k).

**Fits infra-free lib:** Yes. Store embeddings as `double[E][2*d]` (first d columns = real, last d = imaginary). Complex arithmetic is 4 dot products of length d — no Java complex type needed.

---

### 3.9 RotatE
**Paper:** Sun, Dou, Li, Tang. "RotatE: Knowledge Graph Embedding by Relational Rotation in Complex Space." ICLR 2019. https://arxiv.org/abs/1902.10197 OpenReview: https://openreview.net/forum?id=HkgEQnRqYQ Code: https://github.com/DeepGraphLearning/KnowledgeGraphEmbedding

**Scoring:** d(h, r, t) = ‖h ∘ r − t‖ where h, r, t ∈ ℂ^d and each relation component |r_i| = 1 (r_i = e^{iθ_i}, a pure rotation). Relations store only the **phase angles** θ_i (d real numbers); unit-modulus is automatic — no normalization step needed. Entities store both Re and Im parts (2d real numbers).

**Real decomposition for Java:**

    re_score_d = re_h * cos(θ_d) - im_h * sin(θ_d) - re_t
    im_score_d = re_h * sin(θ_d) + im_h * cos(θ_d) - im_t
    distance = Σ_d sqrt(re_score_d² + im_score_d²)

**Self-adversarial negative sampling:** Weight negatives proportional to their current score (stop-gradient): p(h', r, t') ∝ softmax(α · f(h', r, t')). No actual resampling needed — score all n negatives in batch, weight them in the loss. Typical hyperparams: dim=500–1000, batch=512–2048, negatives=128–1024, temperature α=0.5–1.0, margin γ=6–24.

**Expressivity:** Models all four relational patterns — symmetry, antisymmetry, inversion, and composition — that TransE/DistMult/ComplEx cannot all handle simultaneously (canonical comparison table in the paper's Table 1).

**Fits infra-free lib:** Yes. All arithmetic is `double[]` manipulation. The application's existing RotatE embeddings confirm correctness; this trainer would produce equivalent embeddings in-library.

---

### 3.10 GCN (Graph Convolutional Network)
**Paper:** Kipf, Welling. "Semi-Supervised Classification with Graph Convolutional Networks." ICLR 2017. https://arxiv.org/abs/1609.02907 OpenReview: https://openreview.net/forum?id=SJU4ayYgl

**Layer propagation:** H^(l+1) = σ( Â H^l W^l ) where Â = D̃^{-½} Ã D̃^{-½}, Ã = A + I (self-loops), D̃_{ii} = Σ_j Ã_{ij}. Â is **precomputed once** and stored as sparse CSR — never materialized as dense N×N.

**Unsupervised variants:**
- **Graph Autoencoder (GAE/VGAE):** Kipf & Welling 2016, https://arxiv.org/abs/1611.07308. Encoder: Z = GCN(X,A); decoder: Â_reconstructed = σ(ZZ^T); loss = BCE against sampled edges. No labels needed.
- **DGI** (Veličković et al. 2019, https://arxiv.org/abs/1809.10341): maximize mutual information between node embeddings and graph-level summary.

**Compute profile:** Two operations per layer: (1) SpMM: Â × H — sparse N×N times dense N×d, **dominates 60–94% of runtime** on real graphs (verified, arXiv:2502.16949); (2) GEMM: (ÂH) × W — dense N×d times d×d'. Store Â as CSR (`int[] rowPtr`, `int[] colIdx`, `float[] vals`). **Never store dense N×N**: at N=100K, that is 40GB at float32.

**Fits infra-free lib:** Technically yes, but heavier than walk-based methods. SpMM must be implemented as a CSR inner-loop (plain Java) or via ND4J sparse ops; GEMM via `Nd4j.matmul`. Full-graph training only (mini-batching requires GraphSAGE). Practical for N up to ~10K nodes on CPU, ~100K on GPU.

---

### 3.11 GraphSAGE
**Paper:** Hamilton, Ying, Leskovec. "Inductive Representation Learning on Large Graphs." NeurIPS 2017. https://arxiv.org/abs/1706.02216 NeurIPS proceedings: https://proceedings.neurips.cc/paper_files/paper/2017/hash/5dd9db5e033da9c6fb5ba83c7a7ebea9-Abstract.html Code: https://github.com/williamleif/GraphSAGE

**Key innovation:** Sample a fixed-size neighborhood per node per layer (paper: S₁=25 neighbors 1-hop, S₂=10 neighbors 2-hop). Aggregate sampled neighbors (mean, LSTM, or max-pool), **concatenate** with own embedding, apply W × concat + activation. The concatenation — not replacement — is the key difference from GCN. Batch complexity is O(∏ S_k) per seed node, independent of total graph size.

**Forward pass (Algorithm 1):** h_v^k = σ(W^k · CONCAT(h_v^{k-1}, AGG({h_u^{k-1}, u∈sampled_N(v)}))), then L2-normalize.

**Unsupervised loss:** Same negative-sampling objective as DeepWalk: maximize σ(z_u^T z_v) for co-occurrence pairs (from short random walk), minimize for Q random negative nodes.

**Inductive:** Parameters are weight matrices W^k, not node lookup tables. Inference on new nodes: sample their neighbors, run the trained aggregators. Zero retraining needed.

**Fits infra-free lib:** Yes — most naturally of all GNNs. Neighborhood sampling is Java HashMap/adjacency-list lookup; aggregation is a small dense matmul (batch of S_k neighbors × d). ND4J matmul is used for the per-layer W matrices, not a global N×N operation. Key data structure: `HashMap<String, int[]>` (entityId → neighbor indices).

---

### 3.12 GAT (Graph Attention Network)
**Paper:** Veličković, Cucurull, Casanova, Romero, Liò, Bengio. "Graph Attention Networks." ICLR 2018. https://arxiv.org/abs/1710.10903 OpenReview: https://openreview.net/forum?id=rJXMpikCZ Code: https://github.com/PetarV-/GAT

**Attention coefficients:**

    e_ij = LeakyReLU( a^T [Wh_i ‖ Wh_j] )     (LeakyReLU slope = 0.2)
    α_ij = exp(e_ij) / Σ_{k∈N(i)} exp(e_ik)

**Multi-head (K heads):** Concatenate for hidden layers, average for final layer. GATv1 hidden-layer output dim = K × F'.

**Important implementation note — prefer GATv2:** The original GAT has a static attention defect: `e_ij = a^T · LeakyReLU(...)` where the nonlinearity is applied before the projection over a, making the ranking of neighbor j's attention independent of which h_i is querying. GATv2 (Brody, Alon & Yahav, ICLR 2022, https://arxiv.org/abs/2105.14491) fixes this by moving the nonlinearity inside: `e_ij = a^T LeakyReLU(W · [h_i ‖ h_j])`. Same parameter count, strictly more expressive, outperforms GAT on all benchmarks.

**Unsupervised variant:** GATE (Graph Attention Auto-Encoders), https://arxiv.org/abs/1905.10715 — GAT encoder + topology/attribute reconstruction decoder. No labels required.

**Fits infra-free lib:** Yes for small graphs; same profile as GraphSAGE (sparse edge iteration + small dense matmuls per node). Use edge-indexed attention (`float[] alpha` indexed by edge, not dense N×N). More parameters than GraphSAGE but only marginally — the a ∈ ℝ^{2F'} vector adds 2KF' params per layer. Lowest implementation priority after walk-based and translational methods.

---

### 3.13 KGE Expressivity and Benchmark Reference

**Relational pattern expressivity** (canonical table from RotatE, Table 1):

| Model | Symmetric | Antisymmetric | Inversion | Composition |
|-------|-----------|---------------|-----------|-------------|
| TransE | No | Yes | Yes | Yes |
| DistMult | Yes | **No** (algebraic identity) | **No** | **No** |
| ComplEx | Yes | Yes | Yes | **No** |
| RotatE | Yes | Yes | Yes | Yes |

DistMult's symmetry is not a training failure — `score(h,r,t) = score(t,r,h)` holds by algebra regardless of what weights are learned.

**Benchmark results (FB15k-237 and WN18RR, filtered MRR):**

| Model | FB15k-237 MRR | FB15k-237 H@10 | WN18RR MRR | WN18RR H@10 |
|-------|---------------|-----------------|------------|-------------|
| TransE | 0.313 | 0.497 | 0.227 | 0.526 |
| DistMult | 0.343 | 0.531 | 0.452 | 0.531 |
| ComplEx | 0.348 | 0.534 | 0.477 | 0.543 |
| RotatE | 0.338 | 0.533 | 0.475 | 0.574 |

**Dataset warning:** Original FB15k and WN18 are tainted benchmarks — ~81% of FB15k test triples are inverses of training triples and trivially solvable. Always use FB15k-237 (Toutanova & Chen 2015) and WN18RR (Dettmers et al. 2018) for meaningful evaluation.

---

## 4. "Fits the Infra-Free Lib" Analysis

The **infra-free constraint** eliminates:
- Python/DGL/PyG
- Spring / JPA
- Any new JAR not already on the classpath (`nd4j-api`, `jackson-annotations`, `slf4j`, `lombok`)

Under that constraint, **all techniques are implementable**, but they fall into three tiers by implementation effort and compute requirement:

### Tier 1 — Pure Java, No ND4J Required, Light (implement first)
These need only adjacency-list traversal + `double[]` arithmetic:
- **DeepWalk**: random walk generator + negative sampling skip-gram SGD. ~300 lines.
- **node2vec**: extend DeepWalk with p/q alias-table biased walks. ~150 additional lines.
- **LINE**: replace walk generation with direct edge sampling; two separate objectives. ~250 lines.
- **TransE**: triple-list margin-ranking SGD with entity/relation `double[]` tables. ~200 lines.
- **DistMult** / **ComplEx** / **RotatE**: swap the scoring function; reuse the negative-sampling loop and optimizer. ~50–100 additional lines each once TransE base exists.
- **metapath2vec**: extend DeepWalk walk generator to filter by `GraphEntity.type()`. ~100 additional lines.

### Tier 2 — ND4J Dense Path, Feasible (implement after Tier 1)
These benefit from or require ND4J matmul but need no new deps:
- **NetMF**: power iteration (plain Java) + ND4J SVD (`Nd4j.linalg().svd()`). Batch, offline operation.
- **GraphSAGE (unsupervised)**: sparse neighborhood sampling in Java + small ND4J matmuls per node per layer.
- **GCN (unsupervised)**: sparse-dense matmul at graph scale — `TensorHlMrfInference` pattern extended to multi-layer propagation.

### Tier 3 — ND4J, Higher Complexity (defer)
- **GAT**: attention weights, multi-head concatenation, more hyperparameters. Correct but not the best first choice.

**Verification of the hypothesis stated in the task brief:**
- DeepWalk/node2vec = random walks in plain Java + skip-gram/negative-sampling trainer reusing existing `ProjectedGradientOptimizer` → **confirmed lightest**.
- TransE training = margin-ranking SGD similarly light → **confirmed**.
- GCN/GraphSAGE = need dense ND4J matmuls + sparse adjacency, heavier but feasible via `TensorHlMrfInference`-style ND4J path → **confirmed**.

---

## 5. Recommended Phased Plan

### Phase 1 — DeepWalk + Negative Sampling (the keystone)

**What to build:**
```
embedding/learn/
  EmbeddingLearner.java          (interface: learn(ReasoningGraph, EmbeddingConfig) → void; writes embedding into GraphEntity via MutableReasoningGraph)
  EmbeddingConfig.java           (record: int dim, int walkLength, int walksPerNode, int windowSize, int negSamples, long seed)
  EmbeddingTable.java            (double[] indexed by entityIndex; maps entity id→index; separate context table)
  AliasTable.java                (O(1) discrete sampling: build(double[] probs) + sample(Random))
  NegativeSamplingSkipGram.java  (the core update: one positive + K negatives → gradient update on embedding + context rows)
  DeepWalkLearner.java           (implements EmbeddingLearner; generates uniform walks; calls NegativeSamplingSkipGram)
```

**What it reuses:**
- `ProjectedGradientOptimizer` is NOT needed here — the skip-gram update is a standalone SGD step (embedding rows update directly). We can extract the `lr · gradient` update from `ProjectedGradientOptimizer.step()` or simply do it inline. However, the `AliasTable` and `EmbeddingTable` become shared infrastructure for all Tier-1 methods.
- `GraphEntity.embedding()` / `MutableReasoningGraph` for writing learned embeddings back.
- `Embeddings.cosine()` for evaluation (mostSimilar at the end of training to verify).

**Validation:** After training, `Embeddings.mostSimilar(graph, graph.entity("X").embedding(), 5)` should return structurally similar entities.

---

### Phase 2 — TransE (and DistMult/RotatE variants)

**What to build:**
```
embedding/learn/
  TripleLearner.java             (interface: learn(List<Triple>, EmbeddingConfig) → EmbeddingTable for entities + relations)
  Triple.java                    (record: String headId, String relationType, String tailId)
  TransELearner.java             (margin-ranking SGD; projects entity embeddings to unit ball after each step)
  DistMultLearner.java           (bilinear scoring; BPR or NS loss)
  RotatELearner.java             (complex-plane rotation; self-adversarial NS; re-uses AliasTable for weighted negative sampling)
```

**What it reuses:**
- `AliasTable` from Phase 1 (for negative sampling from entity distribution).
- `GraphRelation.type()` to enumerate relation types.
- Existing `HybridReasoner` can immediately consume the relation embeddings as a link-prediction score.

**Note on relation to the wider app:** The app's `KGEmbeddingStorageService` does TransE/RotatE — this in-lib trainer produces the same embeddings without Spring. Embeddings can be serialized as `double[]` and fed back via `GraphEntity.embedding()`.

---

### Phase 3 — node2vec + metapath2vec

**What to build:**
```
embedding/learn/
  Node2VecLearner.java           (extends DeepWalkLearner; overrides walk generation with p/q biased transitions)
  BiasedWalkSampler.java         (per-edge alias tables keyed by (sourceId, predecessorId); lazy computed)
  MetapathWalkSampler.java       (filters neighbor candidates by expected next node type per metapath step)
  MetapathSpec.java              (record: List<String> typeSequence; isCompatible(GraphEntity from, GraphEntity to, int step))
  Metapath2VecLearner.java       (wraps DeepWalkLearner with MetapathWalkSampler + per-type alias tables for NS)
```

**What it reuses:**
- `DeepWalkLearner` — subclassed or composed.
- `AliasTable` — one per node type for metapath2vec++ type-conditioned negative sampling.
- `GraphEntity.type()` — the existing type field drives the metapath filtering.

---

### Phase 4 — NetMF (Batch Analytic Baseline)

**What to build:**
```
embedding/learn/
  NetMFLearner.java              (EmbeddingLearner; build sparse adjacency; power-iterate top-k eigenpairs; log-shift; ND4J SVD)
  SparseAdj.java                 (int[][] colIndices + double[][] values; row-indexed CSR for N×N; matVec product)
  PowerIterator.java             (k-step Lanczos / block power iteration for top-r eigenpairs; uses SparseAdj.matVec)
```

**What it reuses:**
- `Nd4j.linalg().svd()` for the final truncated SVD step.
- ND4J `INDArray` for the dense r×N eigenvector matrix (same as `TensorHlMrfInference`'s dense-matrix approach).

---

### Phase 5 — GraphSAGE (Inductive, Unsupervised)

**What to build:**
```
embedding/learn/
  SageAggregator.java            (interface: aggregate(INDArray neighborEmbeddings) → INDArray; implementations: MeanAggregator, PoolAggregator)
  GraphSageLearner.java          (EmbeddingLearner; L layers; per-layer weight matrix INDArray W; neighborhood sampling; NS loss)
  NeighborhoodSampler.java       (for each entity, sample min(fanout, degree) neighbors uniformly; returns List<GraphEntity>)
```

**What it reuses:**
- `Nd4j.mmul()` for per-layer W×H_sampled — same pattern as `TensorHlMrfInference`.
- `AliasTable` for uniform neighborhood sampling.
- `ProjectedGradientOptimizer` for layer weight updates (the W matrices are the parameters, projected to nonNegative or unconstrained).

---

## 6. Integration: Learned Embeddings + Learned Weights

The existing `HybridReasoner` is the immediate consumer of learned embeddings. The blend is already wired; phases add new embedding sources:

```
HybridReasoner.rank(graph, queryEmbedding)
    = α · structuralScore(PSL/Bayesian)   [learned weights from WeightLearner]
    + β · cosine(entity.embedding(), queryEmbedding)   [learned embedding from EmbeddingLearner]
```

### 6.1 Embeddings as PSL Evidence

PSL rules can reference a numeric predicate. After Phase 1 (DeepWalk), an entity's embedding can be used to compute a similarity atom value fed into PSL:

```
SimilarEntity(X, Y) :- [0,1] value from Embeddings.cosine(X.embedding, Y.embedding)
weight: SimilarEntity(X, Y) ∧ HasProperty(X, Z) → HasProperty(Y, Z).
```

This creates a feedback loop: embedding similarity informs PSL rule firing; PSL soft-truth assignments can be fed back as supervision signal for a next-round embedding re-training (analogous to PSL's "data programming" use case).

### 6.2 Embeddings as MEBN Evidence

MEBN edge strength `p(child | parent)` can incorporate a similarity prior derived from embedding cosine distance. A `MebnWeightLearner` could take a `TransELearner` result and initialize MEBN edge weights from relation-embedding norms.

### 6.3 Link Prediction

The standard use case: given (h, r, ?), score all candidate tails by `d(h+r, t)` (TransE) or `cosine(h, t)` (DeepWalk) and return top-K. This directly extends `Embeddings.mostSimilar()` — which already does top-K cosine — to also support translational scoring.

### 6.4 Embedding → Weight Warm-Start

After TransE training, the relation embedding `r` encodes the "translation direction" for that relation type. This can seed PSL rule weights: rules whose body-to-head semantic direction aligns with `r` get a higher initial weight, reducing weight learning epochs.

---

## 7. Proposed Package / Class Layout

```
ai.kompile.graph.reasoning.
├── embedding/
│   ├── Embeddings.java                     (EXISTING — utilities)
│   └── learn/                              (NEW)
│       ├── EmbeddingLearner.java           (interface)
│       ├── EmbeddingConfig.java            (record)
│       ├── EmbeddingTable.java             (entity/context matrices)
│       ├── AliasTable.java                 (O(1) discrete sampler)
│       ├── NegativeSamplingSkipGram.java   (core update loop)
│       ├── DeepWalkLearner.java            (Phase 1)
│       ├── TripleLearner.java              (interface, Phase 2)
│       ├── Triple.java                     (record, Phase 2)
│       ├── TransELearner.java              (Phase 2)
│       ├── DistMultLearner.java            (Phase 2)
│       ├── RotatELearner.java              (Phase 2)
│       ├── Node2VecLearner.java            (Phase 3)
│       ├── BiasedWalkSampler.java          (Phase 3)
│       ├── MetapathSpec.java               (Phase 3)
│       ├── MetapathWalkSampler.java        (Phase 3)
│       ├── Metapath2VecLearner.java        (Phase 3)
│       ├── NetMFLearner.java               (Phase 4)
│       ├── SparseAdj.java                  (Phase 4)
│       ├── PowerIterator.java              (Phase 4)
│       ├── SageAggregator.java             (Phase 5)
│       ├── NeighborhoodSampler.java        (Phase 5)
│       └── GraphSageLearner.java           (Phase 5)
└── hybrid/
    └── HybridReasoner.java                 (EXISTING — consumes embeddings)
```

---

## 8. Top 3 Citations

1. **DeepWalk (the Phase 1 foundation):** Perozzi, Al-Rfou, Skiena. *DeepWalk: Online Learning of Social Representations.* KDD 2014. https://arxiv.org/abs/1403.6652
   - Foundation for all random-walk methods; skip-gram + negative sampling is the reusable training core.

2. **node2vec (p/q generalization):** Grover, Leskovec. *node2vec: Scalable Feature Learning for Networks.* KDD 2016. https://arxiv.org/abs/1607.00653
   - The p/q biased walk adds ~150 lines on top of DeepWalk; immediately applicable to the heterogeneous `ReasoningGraph`.

3. **NetMF (theoretical unification):** Qiu, Dong, Ma, Li, Wang, Tang. *Network Embedding as Matrix Factorization.* WSDM 2018. https://arxiv.org/abs/1710.02971
   - Proves DeepWalk/LINE/node2vec are all special cases of a single PMI matrix factorization, giving a batch analytic baseline and the theoretical grounding for why negative sampling works.

**Additional KGE citation:** Bordes et al. *Translating Embeddings for Modeling Multi-relational Data.* NeurIPS 2013. https://proceedings.neurips.cc/paper/2013/hash/1cecc7a77928ca8133fa24680a88d2f9-Abstract.html
- TransE is the lowest-complexity relational embedding method; DistMult/ComplEx/RotatE are incremental changes to the scoring function that reuse the same training loop.

**Additional GNN citation:** Hamilton, Ying, Leskovec. *Inductive Representation Learning on Large Graphs.* NeurIPS 2017. https://arxiv.org/abs/1706.02216
- GraphSAGE is the most infra-free GNN: no global N×N matmul, sample-and-aggregate per node, works with the existing ND4J dense tier.

---

## 9. Recommendation Summary

**First technique to implement: DeepWalk with negative sampling** (Phase 1).

**Why it fits infra-free best:**
- Zero new dependencies: adjacency-list walks in plain Java + `double[]` skip-gram SGD.
- Produces immediately useful `double[]` embeddings that `GraphEntity.embedding()` and `HybridReasoner` already consume.
- The alias table (`AliasTable`) and embedding matrix (`EmbeddingTable`) become reusable infrastructure for every subsequent technique (Phases 2–5 all use the same negative sampling machinery).
- Estimated implementation: ~400 lines of pure Java across 5 new classes; no Maven dependency changes.
- Verified against: the walk generation loop is O(V · walksPerNode · walkLength) = parallelizable; the skip-gram update is 6 floating-point operations per example; both are measurably faster in plain Java than they would be with per-example ND4J tensor overhead (ND4J matmul advantage kicks in only at batch sizes ≥ 256).

**Immediate follow-on (Phase 2):** TransE, because it covers the KGE case (typed relations `GraphRelation.type()`) with the same negative-sampling loop, and because the application already uses TransE/RotatE — in-lib training removes the Spring dependency for that capability.

**Defer GCN/GAT** until Phases 4–5. They are correct but require global adjacency normalization and multi-layer matmul that adds complexity not needed to serve the immediate use case (ranking via `HybridReasoner` with learned embeddings).
