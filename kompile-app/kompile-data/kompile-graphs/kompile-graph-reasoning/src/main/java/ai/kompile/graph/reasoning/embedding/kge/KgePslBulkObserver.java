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
package ai.kompile.graph.reasoning.embedding.kge;

import ai.kompile.graph.reasoning.psl.PslProgram;

import java.util.Collection;
import java.util.Objects;

/**
 * Bulk KGE-to-PSL bridge: scores all candidate triples up front and registers them as
 * {@linkplain PslProgram#observe observed} atoms in a {@link PslProgram}.
 *
 * <h3>Why bulk vs per-grounding?</h3>
 * <p>The per-grounding path ({@link KgeTripleScoreFunction}) is simple and avoids
 * materializing a full triple table, but it calls {@link KgeTripleScorer#scoreTriple}
 * once per candidate tuple during grounding.  When the scorer requires IPC (subprocess
 * or network call) this incurs {@code O(|entities|² × |relations|)} round-trips, which
 * is typically unacceptable at scale.</p>
 *
 * <p>This class pre-scores all candidate triples in a single pass and stores the results
 * as observed PSL atoms.  Rule grounding then reads from the in-memory atom table with
 * no further scorer calls.  The tradeoff is memory: the atom table can grow to
 * {@code O(|entities|² × |relations|)} entries before threshold filtering.</p>
 *
 * <h3>Threshold filtering</h3>
 * <p>Only triples whose plausibility meets or exceeds {@code threshold} are registered.
 * Triples below the threshold are silently skipped — they match the open-world default
 * (unobserved atom → 0.0) so inference is unaffected.</p>
 *
 * @see KgeTripleScorer
 * @see KgeTripleScoreFunction
 */
public final class KgePslBulkObserver {

    private KgePslBulkObserver() {}

    /**
     * Score all {@code (head, relationType, tail)} combinations for the given entity and
     * relation sets, and register any triple at or above {@code threshold} as an observed
     * atom in {@code program}.
     *
     * <p>Self-pairs ({@code head == tail}) are included — the scorer may assign them a
     * plausibility score and callers may need reflexive evidence.  Filter them out in the
     * PSL rule body with a {@code A != B} guard if they are not meaningful.</p>
     *
     * @param program       the PSL program to inject evidence into; modified in-place
     * @param scorer        the KGE scorer to call for each triple
     * @param entityIds     the set of entity ids to form the Cartesian head × tail product
     * @param relationTypes the set of relation type strings to enumerate
     * @param predicateName PSL predicate name for the observed atoms (e.g. {@code "TripleScore"})
     * @param threshold     minimum plausibility in {@code [0, 1]} to register an atom
     * @return the number of PSL atoms added (= triples whose score ≥ threshold)
     * @throws IllegalArgumentException if {@code threshold} is outside {@code [0, 1]}
     */
    public static int observeCandidates(PslProgram program,
                                        KgeTripleScorer scorer,
                                        Collection<String> entityIds,
                                        Collection<String> relationTypes,
                                        String predicateName,
                                        double threshold) {
        Objects.requireNonNull(program, "program must not be null");
        Objects.requireNonNull(scorer, "scorer must not be null");
        Objects.requireNonNull(entityIds, "entityIds must not be null");
        Objects.requireNonNull(relationTypes, "relationTypes must not be null");
        Objects.requireNonNull(predicateName, "predicateName must not be null");
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in [0, 1], got " + threshold);
        }

        int added = 0;
        for (String head : entityIds) {
            for (String rel : relationTypes) {
                for (String tail : entityIds) {
                    if (!scorer.knows(head, rel, tail)) continue;
                    double score = scorer.scoreTriple(head, rel, tail);
                    if (score >= threshold) {
                        program.observe(predicateName, score, head, rel, tail);
                        added++;
                    }
                }
            }
        }
        return added;
    }

    /**
     * Convenience overload: observe with a default threshold of {@code 0.0} (register all
     * known triples, including low-plausibility ones).
     *
     * @param program       the PSL program to inject evidence into
     * @param scorer        the KGE scorer
     * @param entityIds     entity id candidates
     * @param relationTypes relation type candidates
     * @param predicateName PSL predicate name
     * @return the number of atoms added
     */
    public static int observeAll(PslProgram program,
                                 KgeTripleScorer scorer,
                                 Collection<String> entityIds,
                                 Collection<String> relationTypes,
                                 String predicateName) {
        return observeCandidates(program, scorer, entityIds, relationTypes, predicateName, 0.0);
    }
}
