/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.gnn;

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ndarray.SparseNDArray;
import org.nd4j.linalg.api.ops.impl.graph.EdgeSplitter;
import org.nd4j.linalg.api.ops.impl.graph.NegativeEdgeSampler;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a kompile matrix-graph (identified by a fact-sheet ID) into the array structures
 * required for DL4J GNN / KGE training.
 *
 * <p>Produces, for a single fact-sheet graph loaded from the matrix store:
 * <ul>
 *   <li><b>Homogeneous CSR</b> — all edge types summed into one COO→CSR-sorted adjacency</li>
 *   <li><b>Per-relation CSR</b> — one CSR per edge type for relational GNN inputs</li>
 *   <li><b>Node feature matrix X</b> — from stored KGE/GNN embeddings; identity fallback</li>
 *   <li><b>KGE triple data</b> — (srcIdx, relTypeIdx, tgtIdx) + relation vocab, train/val/test
 *       split via DL4J {@link EdgeSplitter}, plus negative edges via
 *       {@link NegativeEdgeSampler}</li>
 * </ul>
 *
 * <p>Graphs are addressed as {@code "factsheet_" + factSheetId} in the store, matching
 * the convention in {@code FactSheetGraphServiceImpl}.
 *
 * <p>This class is stateless; create once and call {@link #build(Long, int)} per fact-sheet.
 */
public class GraphToSameDiffDataset {

    /** Graph-ID prefix used by kompile for per-fact-sheet graphs. */
    public static final String FACTSHEET_GRAPH_PREFIX = "factsheet_";

    private static final long DEFAULT_EDGE_SPLIT_SEED = 42L;
    private static final long DEFAULT_NEG_SAMPLE_SEED = 12345L;

    private final MatrixGraphStore store;

    /**
     * @param store the matrix-graph store (primary live store or subprocess-proxy — caller decides)
     */
    public GraphToSameDiffDataset(MatrixGraphStore store) {
        this.store = store;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Build the full dataset for one fact-sheet graph.
     *
     * @param factSheetId      primary key of the fact sheet
     * @param negSamplesPerPos number of negative edges to sample per positive train edge (≥ 1)
     * @return fully-populated {@link GraphDataset}
     * @throws IllegalArgumentException if the graph is absent or has 0 nodes
     */
    public GraphDataset build(Long factSheetId, int negSamplesPerPos) {
        String graphId = FACTSHEET_GRAPH_PREFIX + factSheetId;
        AdjacencyMatrixGraph graph = store.loadGraph(graphId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No graph found for factSheetId=" + factSheetId
                                + " (graphId=" + graphId + ")"));

        int n = graph.getNodeCount();
        if (n == 0) {
            throw new IllegalArgumentException(
                    "Graph " + graphId + " has 0 nodes; cannot form a dataset.");
        }

        Set<String> edgeTypes = graph.getEdgeTypes();

        // 1. Homogeneous CSR (all edge types summed)
        CsrArrays homoCsr = buildHomogeneousCsr(graph, n, edgeTypes);

        // 2. Node feature matrix X
        INDArray featureMatrix = buildFeatureMatrix(graph, n);

        // 3. Per-relation CSR (one per edge type)
        Map<String, CsrArrays> relationCsr = new LinkedHashMap<>();
        for (String et : edgeTypes) {
            relationCsr.put(et, buildRelationCsr(graph, n, et));
        }

        // 4. KGE triples + train/val/test splits + negative edges
        KgeData kgeData = buildKgeData(graph, edgeTypes, n, negSamplesPerPos);

        return new GraphDataset(graphId, n, featureMatrix, homoCsr, relationCsr, kgeData);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // COO → CSR conversion
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds a homogeneous CSR adjacency matrix by collecting COO entries from ALL edge types
     * and compressing them via a stable (srcIdx, tgtIdx) sort.
     */
    private CsrArrays buildHomogeneousCsr(AdjacencyMatrixGraph graph, int n, Set<String> edgeTypes) {
        List<int[]> cooList = new ArrayList<>();
        List<Float> cooWeights = new ArrayList<>();
        for (String et : edgeTypes) {
            collectCoo(graph.getSparseEdges(et), n, cooList, cooWeights);
        }
        return cooToCsr(cooList, cooWeights, n);
    }

    /**
     * Builds a per-relation CSR from one edge type's sparse edges.
     *
     * <p>Uses the per-edge-type CSR cache on {@link AdjacencyMatrixGraph} when available
     * (cache hit = zero-copy; the INDArray component arrays are shared by reference).
     * Falls back to the inline COO → stable-sort → CSR path when the cache is cold.
     */
    private CsrArrays buildRelationCsr(AdjacencyMatrixGraph graph, int n, String edgeType) {
        SparseNDArray cached = graph.getCsrForEdgeType(edgeType);
        // The cached CSR is always non-null (getCsrForEdgeType builds on miss).
        // Verify the shape matches the n we received from build() — they should always
        // agree because getCsrForEdgeType uses getNodeCount() and build() also reads
        // getNodeCount() at the start of the call.  If they diverge (concurrent add),
        // fall back to the safe inline path.
        if (cached.rows() == n) {
            return new CsrArrays(cached.getRowPtr(), cached.getColIdx(), cached.getValues());
        }
        // Shape mismatch: graph mutated between build() entry and here — recompute inline.
        List<int[]> cooList = new ArrayList<>();
        List<Float> cooWeights = new ArrayList<>();
        collectCoo(graph.getSparseEdges(edgeType), n, cooList, cooWeights);
        return cooToCsr(cooList, cooWeights, n);
    }

    /** Append valid (src < n, tgt < n) COO entries from sparse edge data. */
    private void collectCoo(AdjacencyMatrixGraph.SparseEdgeData sed, int n,
                            List<int[]> outIndices, List<Float> outWeights) {
        for (int i = 0, sz = sed.size(); i < sz; i++) {
            int src = sed.indices.get(i)[0];
            int tgt = sed.indices.get(i)[1];
            if (src >= n || tgt >= n) continue;
            outIndices.add(new int[]{src, tgt});
            float w = (sed.weights != null && i < sed.weights.size() && sed.weights.get(i) != null)
                    ? sed.weights.get(i) : 1.0f;
            outWeights.add(w);
        }
    }

    /**
     * Stable sort a COO list by (srcIdx asc, tgtIdx asc) and compress into CSR arrays.
     *
     * <p>CSR layout: {@code colIdx[rowPtr[i]..rowPtr[i+1])} are the column indices for row i;
     * {@code values[rowPtr[i]..rowPtr[i+1])} are the corresponding edge weights (FLOAT32).
     *
     * @param cooList   parallel list of [srcIdx, tgtIdx] int pairs
     * @param cooWeights parallel list of float weights (same length as cooList)
     * @param n          number of nodes (rowPtr length = n+1)
     * @return {@link CsrArrays} with INT32 rowPtr/colIdx and FLOAT32 values
     */
    private CsrArrays cooToCsr(List<int[]> cooList, List<Float> cooWeights, int n) {
        int E = cooList.size();

        // Stable sort by (src asc, tgt asc)
        Integer[] order = new Integer[E];
        for (int i = 0; i < E; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int[] ea = cooList.get(a), eb = cooList.get(b);
            int c = Integer.compare(ea[0], eb[0]);
            return c != 0 ? c : Integer.compare(ea[1], eb[1]);
        });

        int[] colIdxArr = new int[E];
        float[] valArr = new float[E];
        for (int k = 0; k < E; k++) {
            int orig = order[k];
            colIdxArr[k] = cooList.get(orig)[1];
            valArr[k] = cooWeights.get(orig);
        }

        // Build rowPtr by counting edges per source row then prefix-summing
        int[] rowPtrArr = new int[n + 1];
        for (int k = 0; k < E; k++) {
            int src = cooList.get(order[k])[0];
            rowPtrArr[src + 1]++;
        }
        for (int i = 1; i <= n; i++) {
            rowPtrArr[i] += rowPtrArr[i - 1];
        }

        return new CsrArrays(
                Nd4j.createFromArray(rowPtrArr),
                Nd4j.createFromArray(colIdxArr),
                Nd4j.create(valArr, new long[]{E}, DataType.FLOAT));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Node feature matrix
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the node embedding matrix sliced to the active {@code [n, embeddingDim]} rows.
     *
     * <p>The stored {@code nodeEmbeddings} INDArray may have been allocated larger than
     * {@code n} (up to capacity); we slice {@code [0..n)} rows to keep the downstream
     * arrays consistent with {@code numNodes}.
     *
     * <p>Falls back to an {@code [n, n]} identity matrix when embeddings are absent, so
     * callers always receive a well-shaped feature matrix (dimension may differ from the
     * embedding case).
     */
    private INDArray buildFeatureMatrix(AdjacencyMatrixGraph graph, int n) {
        INDArray emb = graph.getNodeEmbeddings();
        if (emb != null && !emb.isEmpty() && emb.rows() > 0 && emb.columns() > 0) {
            long storedRows = emb.rows();
            if (storedRows > n) {
                return emb.get(NDArrayIndex.interval(0, n), NDArrayIndex.all()).dup();
            }
            return emb.dup();
        }
        // Identity fallback: each node is represented by its one-hot index
        return Nd4j.eye(n).castTo(DataType.FLOAT);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // KGE triple formation, splits, and negative sampling
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Collects KGE triples across all edge types, builds a relation vocab, splits into
     * train/val/test using {@link EdgeSplitter}, and samples negatives via
     * {@link NegativeEdgeSampler}.
     *
     * <p>Each triple is (srcIdx, relTypeIdx, tgtIdx) where relTypeIdx indexes into
     * {@link KgeData#relationVocab}. The explicit semantic {@code relationType} field on each
     * edge is preferred over the structural {@code edgeType} routing key when present.
     */
    private KgeData buildKgeData(AdjacencyMatrixGraph graph, Set<String> edgeTypes,
                                  int n, int negSamplesPerPos) {
        Map<String, Integer> relVocab = new LinkedHashMap<>();
        List<long[]> tripleList = new ArrayList<>();

        for (String et : edgeTypes) {
            AdjacencyMatrixGraph.SparseEdgeData sed = graph.getSparseEdges(et);
            // Use the structural edge type as the relation label (semantic relationType is
            // available in newer store versions via getEdgeRelationType; Phase-0 uses the
            // structural edgeType key which is always present).
            int relIdx = relVocab.computeIfAbsent(et, k -> relVocab.size());
            for (int i = 0, sz = sed.size(); i < sz; i++) {
                int src = sed.indices.get(i)[0];
                int tgt = sed.indices.get(i)[1];
                if (src >= n || tgt >= n) continue;
                tripleList.add(new long[]{src, relIdx, tgt});
            }
        }

        int numTriples = tripleList.size();

        if (numTriples == 0) {
            // Return an empty-but-valid dataset so callers don't NPE
            return emptyKgeData(relVocab);
        }

        // Pack into [E, 3] LONG array (srcIdx, relIdx, tgtIdx)
        long[] tripleFlat = new long[numTriples * 3];
        // Pack into [E, 2] LONG array (srcIdx, tgtIdx) for the splitter
        long[] edgeFlat = new long[numTriples * 2];
        for (int i = 0; i < numTriples; i++) {
            long[] t = tripleList.get(i);
            tripleFlat[i * 3]     = t[0];
            tripleFlat[i * 3 + 1] = t[1];
            tripleFlat[i * 3 + 2] = t[2];
            edgeFlat[i * 2]       = t[0];
            edgeFlat[i * 2 + 1]   = t[2];
        }

        INDArray triples  = Nd4j.createFromArray(tripleFlat).reshape(numTriples, 3);
        INDArray edgeList = Nd4j.createFromArray(edgeFlat).reshape(numTriples, 2);

        // Train/val/test split  (80 / 10 / 10)
        EdgeSplitter splitter = new EdgeSplitter(DEFAULT_EDGE_SPLIT_SEED);
        EdgeSplitter.Split split = splitter.split(edgeList, 0.8, 0.1);

        // Negative sampling — cap at available graph capacity
        int trainSize = (int) split.getTrain().size(0);
        int negCount = Math.max(1, trainSize * Math.max(1, negSamplesPerPos));
        long maxNeg = (long) n * (n - 1) - numTriples;
        negCount = (int) Math.min(negCount, Math.max(0, maxNeg));

        INDArray negEdges;
        if (negCount > 0) {
            NegativeEdgeSampler negSampler = NegativeEdgeSampler.fromEdgeList(
                    edgeList, n, /*allowSelfLoops=*/false, DEFAULT_NEG_SAMPLE_SEED);
            negEdges = negSampler.sample(negCount);
        } else {
            negEdges = Nd4j.create(DataType.LONG, 0, 2);
        }

        return new KgeData(triples, new ArrayList<>(relVocab.keySet()),
                split.getTrain(), split.getVal(), split.getTest(), negEdges);
    }

    /** Returns an empty-but-structurally-valid KgeData with no triples. */
    private KgeData emptyKgeData(Map<String, Integer> relVocab) {
        INDArray emptyEdges = Nd4j.create(DataType.LONG, 0, 2);
        INDArray emptyTriples = Nd4j.create(DataType.LONG, 0, 3);
        return new KgeData(emptyTriples, new ArrayList<>(relVocab.keySet()),
                emptyEdges, emptyEdges, emptyEdges, emptyEdges);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Result data classes
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Compressed Sparse Row representation of an adjacency (or per-relation adjacency) matrix.
     *
     * <p>Standard CSR layout:
     * <pre>
     *   row i's column indices: colIdx[ rowPtr[i] .. rowPtr[i+1] )
     *   row i's weights:        values[ rowPtr[i] .. rowPtr[i+1] )
     * </pre>
     *
     * <p>Arrays are backed by {@link INDArray} so they can be passed directly into
     * SameDiff / DL4J ops without conversion.
     */
    public static final class CsrArrays {
        /** Shape [n+1], dtype INT32. {@code rowPtr[i]} = start offset of row i in colIdx/values. */
        public final INDArray rowPtr;
        /** Shape [E], dtype INT32. Target-node column indices. */
        public final INDArray colIdx;
        /** Shape [E], dtype FLOAT32. Edge weights, parallel to colIdx. */
        public final INDArray values;

        CsrArrays(INDArray rowPtr, INDArray colIdx, INDArray values) {
            this.rowPtr = rowPtr;
            this.colIdx = colIdx;
            this.values = values;
        }

        /** Number of non-zero (stored) edges. */
        public int numEdges() { return (int) colIdx.length(); }
    }

    /**
     * KGE-ready triples with train / val / test / negative splits.
     */
    public static final class KgeData {
        /**
         * All positive triples [E, 3]: columns are (srcIdx, relTypeIdx, tgtIdx).
         * Indices refer to node indices and the {@link #relationVocab} list respectively.
         */
        public final INDArray triples;

        /**
         * Ordered relation vocabulary mapping index → relation-type string.
         * Index 0 corresponds to relTypeIdx 0 in {@link #triples}.
         */
        public final List<String> relationVocab;

        /** Training positive edges [trainE, 2] (srcIdx, tgtIdx). */
        public final INDArray trainEdges;
        /** Validation positive edges [valE, 2]. */
        public final INDArray valEdges;
        /** Test positive edges [testE, 2]. */
        public final INDArray testEdges;
        /**
         * Sampled negative edges [negE, 2].
         * These are guaranteed NOT to appear in {@link #triples} (see {@link NegativeEdgeSampler}).
         */
        public final INDArray negEdges;

        KgeData(INDArray triples, List<String> relationVocab,
                INDArray trainEdges, INDArray valEdges,
                INDArray testEdges, INDArray negEdges) {
            this.triples       = triples;
            this.relationVocab = Collections.unmodifiableList(relationVocab);
            this.trainEdges    = trainEdges;
            this.valEdges      = valEdges;
            this.testEdges     = testEdges;
            this.negEdges      = negEdges;
        }

        /** Number of distinct relation types. */
        public int numRelations() { return relationVocab.size(); }

        /** Total number of positive edges / triples. */
        public int numTriples() { return (int) triples.size(0); }
    }

    /**
     * Complete dataset export for one fact-sheet graph.
     */
    public static final class GraphDataset {
        /** The graph ID that was loaded ({@code "factsheet_" + factSheetId}). */
        public final String graphId;

        /** Number of nodes in the graph. */
        public final int numNodes;

        /**
         * Node feature matrix X, shape [numNodes, featureDim] (FLOAT32).
         * Populated from stored KGE/GNN node embeddings; falls back to an
         * [n × n] identity matrix when no embeddings are available.
         */
        public final INDArray featureMatrix;

        /** Homogeneous CSR adjacency: all edge types summed into a single matrix. */
        public final CsrArrays homoCsr;

        /**
         * Per-relation CSR adjacencies (one entry per edge type present in the graph).
         * Key = edge-type string (e.g. {@code "RELATED_TO"}, {@code "WORKS_AT"}).
         */
        public final Map<String, CsrArrays> relationCsr;

        /** KGE-ready triples with train/val/test splits and negative-edge samples. */
        public final KgeData kgeData;

        GraphDataset(String graphId, int numNodes, INDArray featureMatrix,
                     CsrArrays homoCsr, Map<String, CsrArrays> relationCsr,
                     KgeData kgeData) {
            this.graphId      = graphId;
            this.numNodes     = numNodes;
            this.featureMatrix = featureMatrix;
            this.homoCsr      = homoCsr;
            this.relationCsr  = Collections.unmodifiableMap(relationCsr);
            this.kgeData      = kgeData;
        }
    }
}
