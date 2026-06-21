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
 * Hyper-parameters for a RotatE knowledge-graph embedding training run.
 *
 * <h3>Model overview</h3>
 * <p>RotatE (Sun et al., 2019) represents each entity as a complex vector
 * {@code h = h_re + i·h_im} (two real matrices {@code [numEntities, dim]}) and each relation
 * as a phase vector {@code θ ∈ R^dim}, acting as a unit-modulus rotation
 * {@code e^{iθ} = cosθ + i·sinθ} on the complex unit circle. The RotatE score for a triple
 * {@code (h, r, t)} is the negated L2 distance after rotating {@code h} by {@code r}:
 * {@code score = −||h ∘ r − t||}, where lower distance means more plausible.</p>
 *
 * <h3>Initialisation conventions (documented here, not in the trainer)</h3>
 * <ul>
 *   <li>Relation phases initialised uniformly in {@code (−π, π]}; initialised once at
 *       construction and NOT re-normalised during training (phases are unconstrained scalars).</li>
 *   <li>Entity real/imaginary parts initialised uniformly in {@code (−initRange, +initRange)};
 *       entity moduli are NOT constrained to 1 during training (modulus carries information
 *       per RotatE paper §4.1).</li>
 * </ul>
 *
 * <h3>Loss function</h3>
 * <p>Self-adversarial negative sampling loss (RotatE paper Eq. 4) with <em>uniform</em>
 * negative weights ({@code p_i = 1/K}). The weighted variant
 * ({@code p_i = softmax(α·(γ − d_i))}) is intentionally omitted to keep the SameDiff graph
 * simple and differentiable without a stop-gradient on the weights; the uniform approximation
 * is sufficient for the structural (true > corrupt) correctness gate on small KGs:</p>
 * <pre>
 *   L = −log σ(γ − d_pos) − (1/K) Σ_i log σ(d_neg_i − γ)
 * </pre>
 *
 * @param dim           embedding dimension {@code d} (per part; total complex dim = 2·d)
 * @param margin        fixed margin γ ≥ 0; typical values 3–24 for standard benchmarks;
 *                      use 1–6 for small test KGs
 * @param negSamples    number of negative samples {@code K} per positive triple
 * @param epochs        full passes over all training triples
 * @param learningRate  SGD step size
 * @param initRange     entity embedding init range — each component drawn from
 *                      {@code (−initRange, +initRange)}
 * @param seed          RNG seed for deterministic initialisation and negative sampling
 * @param batchSize     triples per SameDiff forward/backward pass; use a power-of-2 ≥ 1
 *
 * @see RotatELearner
 */
public record RotatEConfig(
        int    dim,
        double margin,
        int    negSamples,
        int    epochs,
        double learningRate,
        double initRange,
        long   seed,
        int    batchSize) {

    /**
     * Sensible defaults for small-to-medium knowledge graphs.
     * Margin 6 and dim 32 give clear signal on graphs with 5–50 entities;
     * increase dim and epochs for production-scale KGs.
     */
    public static RotatEConfig defaults() {
        return new RotatEConfig(
                32,    // dim
                6.0,   // margin γ
                5,     // negSamples K
                5,     // epochs
                0.01,  // learningRate
                0.1,   // initRange
                1234L, // seed
                32     // batchSize
        );
    }

    /** Lightweight config for unit tests: fewer epochs, smaller dim, stable margin. */
    public static RotatEConfig forTest() {
        return new RotatEConfig(
                16,    // dim
                4.0,   // margin γ
                5,     // negSamples K
                30,    // epochs — enough for true>corrupt gate on toy KGs
                0.02,  // learningRate
                0.1,   // initRange
                42L,   // seed
                8      // batchSize
        );
    }
}
