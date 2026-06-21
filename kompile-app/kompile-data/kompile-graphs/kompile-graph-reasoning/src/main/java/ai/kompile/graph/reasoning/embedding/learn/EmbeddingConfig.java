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

/**
 * Hyper-parameters for a node-embedding training run (node2vec / DeepWalk).
 *
 * <p>All fields are immutable. Construct via the constructor directly or use {@link #defaults()}
 * for sensible starting values. Setting {@code p = q = 1.0} (the default) recovers uniform
 * DeepWalk walks.</p>
 *
 * <p>Parameter semantics:</p>
 * <ul>
 *   <li>{@link #dim} — embedding dimension {@code d}; each entity gets a {@code double[d]} vector.</li>
 *   <li>{@link #walkLength} — number of steps per random walk (including the start node).</li>
 *   <li>{@link #walksPerNode} — number of walks started from each node per epoch.</li>
 *   <li>{@link #windowSize} — skip-gram context window radius {@code w}; produces pairs in
 *       {@code [center-w, center+w]}.</li>
 *   <li>{@link #negSamples} — number of negative samples {@code K} per positive skip-gram pair.</li>
 *   <li>{@link #p} — node2vec return parameter (controls revisiting the previous node).</li>
 *   <li>{@link #q} — node2vec in-out parameter (controls BFS vs DFS exploration).</li>
 *   <li>{@link #epochs} — number of full passes over all walks (walk re-generation per epoch).</li>
 *   <li>{@link #learningRate} — initial SGD learning rate {@code lr}.</li>
 *   <li>{@link #seed} — RNG seed for fully deterministic, reproducible training.</li>
 * </ul>
 *
 * @param dim          embedding dimension
 * @param walkLength   steps per random walk
 * @param walksPerNode walks generated per node per epoch
 * @param windowSize   skip-gram context window radius
 * @param negSamples   negative samples per positive pair
 * @param p            node2vec return parameter (1.0 = neutral)
 * @param q            node2vec in-out parameter (1.0 = neutral / DeepWalk)
 * @param epochs       training epochs
 * @param learningRate SGD learning rate
 * @param seed         RNG seed for reproducibility
 */
public record EmbeddingConfig(
        int dim,
        int walkLength,
        int walksPerNode,
        int windowSize,
        int negSamples,
        double p,
        double q,
        int epochs,
        double learningRate,
        long seed) {

    /**
     * Sensible defaults suitable for small-to-medium graphs.
     * {@code p = q = 1.0} → uniform DeepWalk walks.
     */
    public static EmbeddingConfig defaults() {
        return new EmbeddingConfig(
                64,    // dim
                20,    // walkLength
                10,    // walksPerNode
                5,     // windowSize
                5,     // negSamples
                1.0,   // p — DeepWalk (uniform)
                1.0,   // q — DeepWalk (uniform)
                1,     // epochs
                0.025, // learningRate
                1234L  // seed
        );
    }
}
