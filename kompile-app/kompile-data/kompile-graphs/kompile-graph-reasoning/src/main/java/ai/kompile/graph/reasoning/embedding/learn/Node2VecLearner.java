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
package ai.kompile.graph.reasoning.embedding.learn;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * node2vec learner — implements {@link EmbeddingLearner} using biased 2nd-order random walks
 * followed by skip-gram-with-negative-sampling (SGNS) training on a
 * {@link SameDiffEmbeddingTrainer} (ND4J SameDiff autodiff backend).
 *
 * <h3>Algorithm summary</h3>
 * <ol>
 *   <li><b>Build adjacency.</b> The graph is treated as undirected: for each entity, neighbors are
 *       the union of {@link ReasoningGraph#outgoing(String) outgoing} and
 *       {@link ReasoningGraph#incoming(String) incoming} relation endpoints.</li>
 *   <li><b>Build entity index.</b> A stable insertion-order mapping {@code entityId → int} is
 *       used throughout (for the trainer and for negative-sampling draws).</li>
 *   <li><b>Build negative-sampling table.</b> An {@link AliasTable} over
 *       {@code degree(v)^0.75} provides O(1) draws from the noise distribution, matching
 *       word2vec and DeepWalk conventions. Plain Java — data prep is unchanged.</li>
 *   <li><b>Construct {@link SameDiffEmbeddingTrainer}.</b> The trainer holds the two trainable
 *       ND4J matrices ({@code entityW}, {@code contextW}) and the static SameDiff SGNS graph.
 *       It is seeded from {@link EmbeddingConfig#seed()} so initialisation is deterministic.</li>
 *   <li><b>For each epoch, for each node:</b>
 *       <ul>
 *         <li>Generate {@code walksPerNode} walks of length {@code walkLength} via
 *             {@link Node2VecWalk}. At {@code p = q = 1} this is uniform DeepWalk.</li>
 *         <li>Slide a window of radius {@code windowSize} over each walk to extract positive
 *             (center, context) pairs.</li>
 *         <li>Sample {@code negSamples} negatives from the alias table and call
 *             {@link SameDiffEmbeddingTrainer#fitPair} for each pair.</li>
 *       </ul>
 *   </li>
 *   <li><b>Return {@link EmbeddingTable}.</b> Built from the trainer's final entity matrix;
 *       the context matrix is not exposed downstream.</li>
 * </ol>
 *
 * <h3>Plain-Java data preparation</h3>
 * <p>{@link AliasTable}, {@link Node2VecWalk}, and the walk/sliding-window/negative-sampling
 * logic are <em>unchanged</em> pure Java — they are data preparation, not neural-net work.
 * Only the gradient update has moved to SameDiff.</p>
 *
 * <h3>Reproducibility</h3>
 * <p>A single {@link Random} instance seeded from {@link EmbeddingConfig#seed()} + 1 is advanced
 * in strict order through: walk generation → negative sampling. The trainer is separately seeded
 * from {@link EmbeddingConfig#seed()} for matrix initialisation. Given the same
 * {@code (graph, config)}, two runs produce structurally equivalent embeddings — cluster
 * separation is preserved. Bit-exact element-wise equality across runs is also preserved within
 * SameDiff (single-threaded, deterministic ND4J ops), but note that the vectors will differ from
 * the old hand-rolled trainer due to different floating-point reduction order in SameDiff ops.
 * See {@link SameDiffEmbeddingTrainer} for details.</p>
 *
 * <h3>DeepWalk special case</h3>
 * <p>When {@code config.p() == 1.0 && config.q() == 1.0} the node2vec walk degenerates to a
 * uniform random walk identical to DeepWalk. No code-path branching is needed; {@link Node2VecWalk}
 * detects this internally.</p>
 *
 * <p>References:
 * <ul>
 *   <li>Grover, Leskovec (2016). node2vec. KDD 2016. https://arxiv.org/abs/1607.00653</li>
 *   <li>Perozzi et al. (2014). DeepWalk. KDD 2014. https://arxiv.org/abs/1403.6652</li>
 *   <li>Mikolov et al. (2013). Distributed Representations of Words and Phrases. NeurIPS 2013.
 *       https://arxiv.org/abs/1310.4546</li>
 * </ul>
 * </p>
 */
public final class Node2VecLearner implements EmbeddingLearner {

    /**
     * {@inheritDoc}
     *
     * <p>Training uses SameDiff autodiff for the SGNS gradient update. The walk generation,
     * sliding-window pair extraction, and degree^0.75 negative sampling remain in plain Java.</p>
     */
    @Override
    public EmbeddingTable learn(ReasoningGraph graph, EmbeddingConfig config) {
        // ─── 1. Collect entities in stable insertion order ────────────────────────
        List<String> entityIds = new ArrayList<>(graph.entityCount());
        for (GraphEntity e : graph.entities()) {
            entityIds.add(e.id());
        }
        if (entityIds.isEmpty()) {
            throw new IllegalArgumentException("graph has no entities");
        }

        // ─── 2. Build entity index (entityId → int row) ───────────────────────
        Map<String, Integer> entityIndex = new LinkedHashMap<>(entityIds.size() * 2);
        for (int i = 0; i < entityIds.size(); i++) {
            entityIndex.put(entityIds.get(i), i);
        }

        // ─── 3. Build undirected adjacency map ────────────────────────────────────
        Map<String, List<String>> adjacency = buildAdjacency(graph, entityIds);

        // ─── 4. Build negative-sampling alias table (degree^0.75) ─────────────────
        double[] degreeWeights = new double[entityIds.size()];
        for (int i = 0; i < entityIds.size(); i++) {
            int deg = adjacency.getOrDefault(entityIds.get(i), List.of()).size();
            degreeWeights[i] = Math.pow(Math.max(deg, 1), 0.75);
        }
        AliasTable negSampler = AliasTable.build(degreeWeights);

        // ─── 5. Construct the SameDiff trainer (seeds ND4J + matrix init) ────────
        try (SameDiffEmbeddingTrainer trainer = new SameDiffEmbeddingTrainer(
                entityIds,
                config.dim(),
                config.negSamples(),
                config.learningRate(),
                config.seed())) {

        // ─── 6. Build the walk generator ─────────────────────────────────────────
        Node2VecWalk walker = new Node2VecWalk(graph, adjacency, config.p(), config.q());

        // ─── 7. RNG for walks + negative sampling (derived seed avoids overlap with trainer) ─
        // Using seed+1 matches the original Node2VecLearner convention.
        Random rng = new Random(config.seed() + 1L);

        // ─── 8. Training loop ─────────────────────────────────────────────────────
        int[] negIdxBuf = new int[config.negSamples()];

        for (int epoch = 0; epoch < config.epochs(); epoch++) {
            // Shuffle starting order per epoch (same RNG stream as original).
            List<String> shuffled = new ArrayList<>(entityIds);
            Collections.shuffle(shuffled, rng);

            for (String startId : shuffled) {
                for (int w = 0; w < config.walksPerNode(); w++) {
                    List<String> walkNodes = walker.walk(startId, config.walkLength(), rng);

                    // Slide window over the walk
                    int walkLen = walkNodes.size();
                    for (int pos = 0; pos < walkLen; pos++) {
                        String center = walkNodes.get(pos);
                        Integer centerIdx = entityIndex.get(center);
                        if (centerIdx == null) {
                            continue;
                        }

                        int start = Math.max(0, pos - config.windowSize());
                        int end   = Math.min(walkLen - 1, pos + config.windowSize());

                        for (int ctx = start; ctx <= end; ctx++) {
                            if (ctx == pos) {
                                continue;
                            }
                            String context = walkNodes.get(ctx);
                            Integer contextIdx = entityIndex.get(context);
                            if (contextIdx == null) {
                                continue;
                            }

                            // Sample K negatives from degree^0.75 distribution.
                            for (int ki = 0; ki < negIdxBuf.length; ki++) {
                                negIdxBuf[ki] = negSampler.sample(rng);
                            }

                            // Queue the pair; the trainer auto-flushes every batchSize pairs.
                            trainer.queuePair(centerIdx, contextIdx, negIdxBuf);
                        }
                    }
                }
            }

            // Flush any remaining partial batch at epoch end.
            trainer.flushBatch();
        }

        // ─── 9. Build EmbeddingTable from the trained SameDiff matrices ───────────
        // EmbeddingTable copies the entity matrix before try-with-resources closes the trainer.
        return new EmbeddingTable(entityIds, trainer);
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────────

    /**
     * Build the undirected neighbor lists. Both directions of a directed relation contribute a
     * neighbor entry: source sees target and target sees source. Duplicate neighbor entries
     * (from parallel edges) are preserved — they naturally increase transition probability,
     * which is the correct behavior for weighted graphs.
     */
    private Map<String, List<String>> buildAdjacency(ReasoningGraph graph, List<String> entityIds) {
        Map<String, List<String>> adj = new LinkedHashMap<>(entityIds.size() * 2);
        for (String id : entityIds) {
            adj.put(id, new ArrayList<>());
        }
        for (GraphRelation rel : graph.relations()) {
            String src = rel.sourceId();
            String tgt = rel.targetId();
            if (adj.containsKey(src)) {
                adj.get(src).add(tgt);
            }
            if (adj.containsKey(tgt) && !tgt.equals(src)) {
                adj.get(tgt).add(src);
            }
        }
        return adj;
    }
}
