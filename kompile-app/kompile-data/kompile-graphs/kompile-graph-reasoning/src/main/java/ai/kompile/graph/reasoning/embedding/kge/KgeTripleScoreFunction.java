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

import ai.kompile.graph.reasoning.psl.ExternalFunction;
import ai.kompile.graph.reasoning.psl.PslProgram;

import java.util.Objects;

/**
 * PSL {@link ExternalFunction} adapter that wires a {@link KgeTripleScorer} into a
 * {@link PslProgram} for per-grounding triple scoring.
 *
 * <h3>Per-grounding vs bulk path</h3>
 * <p>This class enables the <em>per-grounding</em> path: the scorer is called once per
 * candidate {@code (head, relationType, tail)} constant tuple during rule grounding.
 * This is convenient when all triples are enumerable in-process (e.g. a stub or a
 * trained model loaded into memory), but it can be expensive when the scorer requires
 * IPC (subprocess / network call) because the grounding engine calls {@code evaluate}
 * once per candidate tuple.  In that case prefer the <em>bulk</em> path provided by
 * {@link KgePslBulkObserver}, which scores all candidate triples up front and registers
 * them as observed atoms so grounding touches no external I/O.</p>
 *
 * <h3>Argument convention</h3>
 * <p>The function expects exactly three arguments: {@code [headId, relationType, tailId]}.
 * Register it with the <em>ternary</em> PSL predicate name of your choice, e.g.:</p>
 * <pre>
 *   KgeTripleScoreFunction fn = new KgeTripleScoreFunction(scorer);
 *   program.registerFunction("TripleScore", fn)
 *          .addRule("5.0: TripleScore(H, R, T) &amp; HasRelation(H, R, T) -> Confirmed(H, R, T) ^2");
 * </pre>
 *
 * <h3>Zero-value suppression</h3>
 * <p>When the scorer returns {@code 0.0} (unknown or impossible triple) the PSL grounding
 * engine skips the atom — it contributes no evidence.  This is the standard open-world
 * behaviour for {@link ExternalFunction} predicates.</p>
 *
 * @see KgeTripleScorer
 * @see KgePslBulkObserver
 * @see PslProgram#registerFunction(String, ExternalFunction)
 */
public final class KgeTripleScoreFunction implements ExternalFunction {

    private static final int ARITY = 3;

    private final KgeTripleScorer scorer;

    /**
     * Construct the adapter backed by the given scorer.
     *
     * @param scorer the KGE scorer to delegate to; must not be null
     */
    public KgeTripleScoreFunction(KgeTripleScorer scorer) {
        this.scorer = Objects.requireNonNull(scorer, "scorer must not be null");
    }

    /**
     * Evaluate the KGE plausibility score for the ground triple {@code (args[0], args[1], args[2])}.
     *
     * @param args exactly three string constants: {@code [headId, relationType, tailId]}
     * @return plausibility in {@code [0, 1]}; {@code 0.0} suppresses the atom (open-world default)
     * @throws IllegalArgumentException if {@code args.length != 3}
     */
    @Override
    public double evaluate(String... args) {
        if (args.length != ARITY) {
            throw new IllegalArgumentException(
                    "KgeTripleScoreFunction requires exactly 3 args (headId, relationType, tailId), got "
                            + args.length);
        }
        return scorer.scoreTriple(args[0], args[1], args[2]);
    }

    /**
     * Convenience method: register this function into a {@link PslProgram} under the given
     * predicate name and return the program for chaining.
     *
     * <pre>
     *   program = fn.registerInto(program, "TripleScore");
     * </pre>
     *
     * @param program       the PSL program to register into; modified in-place and returned
     * @param predicateName the predicate name to register under (e.g. {@code "TripleScore"})
     * @return the same {@code program} instance (for method chaining)
     */
    public PslProgram registerInto(PslProgram program, String predicateName) {
        Objects.requireNonNull(program, "program must not be null");
        Objects.requireNonNull(predicateName, "predicateName must not be null");
        return program.registerFunction(predicateName, this);
    }
}
