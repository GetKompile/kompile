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
package ai.kompile.graph.reasoning.fol.semiring;

/**
 * The Viterbi (tropical max-product) semiring: {@code (Double, max, ×, 0.0, 1.0)}.
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>{@link #plus(Double, Double)} = {@code Math.max(a, b)} — across two proof paths, keep
 *       the one with the higher confidence.</li>
 *   <li>{@link #times(Double, Double)} = {@code a * b} — along one proof chain, multiply
 *       per-step confidences (product of the rule-chain's soft-truth values).</li>
 *   <li>{@link #zero()} = {@code 0.0} (additive identity: no derivation = 0 confidence).</li>
 *   <li>{@link #one()} = {@code 1.0} (multiplicative identity: axiom has full confidence).</li>
 * </ul>
 *
 * <p>For a chain {@code a→b (0.8), b→c (0.5)}, the Viterbi annotation of the derived
 * fact {@code path(a,c)} is {@code 0.8 × 0.5 = 0.4}.  If a direct edge {@code a→c (0.6)}
 * also exists, the annotation becomes {@code max(0.4, 0.6) = 0.6}.</p>
 *
 * <p>This semiring is <em>absorptive</em> ({@code max(a, a) = a}), so the annotation
 * fixpoint terminates in a finite number of rounds over any finite derivation graph.</p>
 *
 * <p>This semiring is used by {@link ai.kompile.graph.reasoning.fol.materialization.ForwardChainingMaterializer}
 * to assign soft confidence to derived {@link ai.kompile.graph.reasoning.fol.InferredFact}s
 * instead of the hard-coded {@code 1.0}.</p>
 *
 * @see Semiring
 */
public final class ViterbiSemiring implements Semiring<Double> {

    /** Singleton instance — stateless, safe for concurrent use. */
    public static final ViterbiSemiring INSTANCE = new ViterbiSemiring();

    private ViterbiSemiring() {}

    @Override
    public Double zero() {
        return 0.0;
    }

    @Override
    public Double one() {
        return 1.0;
    }

    /**
     * Best-derivation combination: keep the higher confidence across two proof paths.
     *
     * @param a confidence via the first derivation path
     * @param b confidence via the second derivation path
     * @return {@code Math.max(a, b)}
     */
    @Override
    public Double plus(Double a, Double b) {
        return Math.max(a, b);
    }

    /**
     * Chain combination: product of confidences along a single derivation chain.
     *
     * @param a confidence of the running product so far
     * @param b confidence of the next step
     * @return {@code a * b}
     */
    @Override
    public Double times(Double a, Double b) {
        return a * b;
    }

    /**
     * {@code max} is idempotent: {@code max(a, a) = a}.
     *
     * @return {@code true}
     */
    @Override
    public boolean isAbsorptive() {
        return true;
    }

    @Override
    public String toString() {
        return "ViterbiSemiring(max, ×, 0.0, 1.0)";
    }
}
