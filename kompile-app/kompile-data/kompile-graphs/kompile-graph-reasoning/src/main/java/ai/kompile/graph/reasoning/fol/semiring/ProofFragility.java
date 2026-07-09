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

import java.util.List;

/**
 * Utility for computing proof-fragility signals from a list of {@link Proof}s.
 *
 * <p>A fact supported by a single, high-scoring proof is "fragile" — if that one proof
 * is retracted, the fact loses all support.  A fact with two comparably-scored proofs
 * is "robust" — even if one proof fails, the other provides strong backup support.</p>
 *
 * <p>The <em>second-best ratio</em> ({@link #secondBestRatio}) is a simple fragility
 * signal: {@code score2 / score1} where {@code score1} is the best proof's score and
 * {@code score2} is the second-best.  A ratio near 1 indicates robustness (the top-2
 * proofs are nearly equally strong); a ratio near 0 indicates fragility (one dominant
 * proof, all others much weaker or absent).  Returns 0.0 when there is only one proof.</p>
 */
public final class ProofFragility {

    private ProofFragility() {}

    /**
     * Compute the second-best ratio: {@code score2 / score1}.
     *
     * <p>If {@code proofs} has fewer than 2 entries (or the best proof has score 0),
     * returns {@code 0.0}.</p>
     *
     * @param proofs the list of proofs, expected in descending score order
     *               (as returned by {@link ProofSet#proofs()} or
     *               {@link ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.AnnotatedResult#proofs})
     * @return the second-best ratio in [0, 1], or 0.0 if undefined
     */
    public static double secondBestRatio(List<Proof> proofs) {
        if (proofs == null || proofs.size() < 2) return 0.0;
        double score1 = proofs.get(0).score();
        if (score1 <= 0.0) return 0.0;
        double score2 = proofs.get(1).score();
        return score2 / score1;
    }

    /**
     * Whether a fact's proofs indicate robustness: the second-best ratio is at least
     * {@code minRatio}.
     *
     * <p>A ratio of 1.0 means the top-2 proofs have identical confidence (maximally robust).
     * A ratio of 0.0 means there is only one proof (maximally fragile).</p>
     *
     * @param proofs   list of proofs in descending score order
     * @param minRatio minimum acceptable ratio (e.g. 0.5 = second proof is at least half the strength of the best)
     * @return {@code true} if the second-best ratio ≥ {@code minRatio}
     */
    public static boolean isRobust(List<Proof> proofs, double minRatio) {
        return secondBestRatio(proofs) >= minRatio;
    }
}
