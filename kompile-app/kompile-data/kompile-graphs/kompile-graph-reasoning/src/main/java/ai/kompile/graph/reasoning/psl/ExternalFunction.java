/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

/**
 * An external (user-defined) function predicate for use in PSL rule bodies.
 *
 * <p>External functions allow rule bodies to reference computed values — for example,
 * string-similarity or embedding-cosine between two entities — without pre-materialising
 * a table of all pairwise values. The value returned must be in {@code [0, 1]} and is
 * treated as a soft-truth observation at grounding time.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   // Register a Jaccard similarity function
 *   PslProgram prog = new PslProgram()
 *       .registerFunction("TagSim", (a, b) -> jaccard(tags(a), tags(b)))
 *       .addRule("1.0: TagSim(A, B) & HasEntity(A) -> SameAs(A, B) ^2");
 * </pre>
 *
 * <p>The function is called at grounding time for every candidate constant tuple
 * produced by the other bound variables in the rule body. Tuples where the function
 * returns {@code 0.0} (or below a configurable threshold) are skipped — they contribute
 * no evidence.</p>
 *
 * <p><b>Thread-safety</b>: implementations must be safe for concurrent evaluation (the
 * grounding engine may call {@code evaluate} from multiple threads when parallelism is
 * added in future).</p>
 */
@FunctionalInterface
public interface ExternalFunction {

    /**
     * Evaluate the function for the given ground constant arguments.
     *
     * @param args the string constant values bound to each argument position
     * @return a soft-truth value in {@code [0, 1]};
     *         returning {@code 0.0} suppresses the atom (treated as absent evidence)
     */
    double evaluate(String... args);
}
