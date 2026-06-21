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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and runs a collective entity resolution PSL program using the {@code SameAs}
 * predicate (Pujara et al. 2013 KGI, §4; Bhattacharya & Getoor 2007).
 *
 * <h3>Background</h3>
 * <p>In knowledge graph identification (KGI), multiple mention candidates (extracted from
 * text or multiple crawl runs) may refer to the same underlying real-world entity. Collective
 * entity resolution jointly reasons over all candidates by encoding:
 * <ol>
 *   <li><b>Similarity</b>: observed string/embedding similarity {@code Sim(X, Y)} raises
 *       the probability that {@code SameAs(X, Y)} holds.</li>
 *   <li><b>Reflexivity</b>: every entity is co-referent with itself.</li>
 *   <li><b>Symmetry</b>: {@code SameAs(X, Y) → SameAs(Y, X)}.</li>
 *   <li><b>Transitivity</b>: {@code SameAs(X, Y) & SameAs(Y, Z) → SameAs(X, Z)}.</li>
 *   <li><b>Functional constraint</b>: (optional) each mention resolves to exactly one
 *       canonical entity (hard uniqueness constraint).</li>
 * </ol>
 *
 * <h3>Predicates</h3>
 * <ul>
 *   <li>{@code Sim(X, Y)} — observed similarity score in [0, 1] (provided by the caller).</li>
 *   <li>{@code SameAs(X, Y)} — target predicate: posterior probability that X and Y
 *       co-refer to the same entity.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SameAsCollectiveResolution er = new SameAsCollectiveResolution();
 * er.addSimilarity("mention_alice_1", "mention_alice_2", 0.95);
 * er.addSimilarity("mention_alice_1", "mention_bob_1",   0.12);
 * er.addCandidate("mention_alice_1");
 * er.addCandidate("mention_alice_2");
 * er.addCandidate("mention_bob_1");
 * SameAsCollectiveResolution.Result result = er.resolve();
 * double p = result.sameAs("mention_alice_1", "mention_alice_2");  // ≈ high
 * }</pre>
 */
public final class SameAsCollectiveResolution {

    // ─── Predicates ──────────────────────────────────────────────────────────────

    /** Observed similarity predicate: {@code Sim(X, Y)}. */
    public static final String SIM = "Sim";

    /** Target co-reference predicate: {@code SameAs(X, Y)}. */
    public static final String SAME_AS = "SameAs";

    // ─── Configuration ───────────────────────────────────────────────────────────

    /** Weight of the similarity → SameAs rule (higher = similarity drives co-reference more). */
    private double simWeight = 3.0;

    /** Weight of the symmetry rule. */
    private double symmetryWeight = 1.0;

    /** Weight of the transitivity rule. */
    private double transitivityWeight = 2.0;

    /** Weight of the negative prior (push SameAs toward 0 by default). */
    private double negativePriorWeight = 1.0;

    /** Whether to add a hard functional constraint (each mention → at most one canonical entity). */
    private boolean enforceFunctional = false;

    // ─── State ───────────────────────────────────────────────────────────────────

    /** Candidate entity mention IDs. */
    private final List<String> candidates = new ArrayList<>();

    /** Observed pairwise similarities (atomKey → similarity value in [0, 1]). */
    private final Map<String, Double> similarities = new LinkedHashMap<>();

    // ─── Builder methods ─────────────────────────────────────────────────────────

    public SameAsCollectiveResolution simWeight(double w) { this.simWeight = w; return this; }
    public SameAsCollectiveResolution symmetryWeight(double w) { this.symmetryWeight = w; return this; }
    public SameAsCollectiveResolution transitivityWeight(double w) { this.transitivityWeight = w; return this; }
    public SameAsCollectiveResolution negativePriorWeight(double w) { this.negativePriorWeight = w; return this; }
    public SameAsCollectiveResolution enforceFunctional(boolean b) { this.enforceFunctional = b; return this; }

    /**
     * Add a candidate entity mention.
     *
     * @param id the mention identifier (e.g. an extracted entity span ID)
     * @return this builder for chaining
     */
    public SameAsCollectiveResolution addCandidate(String id) {
        if (!candidates.contains(id)) candidates.add(id);
        return this;
    }

    /**
     * Record an observed similarity between two mentions.
     *
     * <p>Similarities are symmetric: calling {@code addSimilarity(A, B, s)} records both
     * {@code Sim(A, B)} and {@code Sim(B, A)}.</p>
     *
     * @param idA  first mention ID
     * @param idB  second mention ID
     * @param sim  similarity score in [0, 1]
     * @return this builder for chaining
     */
    public SameAsCollectiveResolution addSimilarity(String idA, String idB, double sim) {
        addCandidate(idA);
        addCandidate(idB);
        sim = Math.max(0.0, Math.min(1.0, sim));
        String keyAB = SIM + "(" + idA + ", " + idB + ")";
        String keyBA = SIM + "(" + idB + ", " + idA + ")";
        similarities.put(keyAB, sim);
        similarities.put(keyBA, sim);
        return this;
    }

    // ─── Resolution ──────────────────────────────────────────────────────────────

    /**
     * Run the collective entity resolution PSL program and return the result.
     *
     * @return the resolution result containing the {@code SameAs} posterior probabilities
     */
    public Result resolve() {
        PslProgram program = buildProgram();
        HlMrfMapInference.Result mapResult = HlMrfMapInference.solve(program);
        return new Result(mapResult.values(), candidates);
    }

    /**
     * Build the PSL program for this resolution task.
     *
     * @return the populated PSL program
     */
    public PslProgram buildProgram() {
        PslProgram program = new PslProgram();

        // Add all observed Sim atoms.
        for (Map.Entry<String, Double> entry : similarities.entrySet()) {
            PslAtom simAtom = PslAtom.parse(entry.getKey());
            program.observe(simAtom, entry.getValue());
        }

        // Declare all SameAs(X, X) as reflexively observed (truth = 1.0).
        for (String id : candidates) {
            program.observe(SIM + "(" + id + ", " + id + ")", 1.0);
            program.observe(PslAtom.ground(SAME_AS, id, id), 1.0);
        }

        // Declare all SameAs(X, Y) target atoms for X ≠ Y.
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = 0; j < candidates.size(); j++) {
                if (i != j) {
                    program.target(PslAtom.ground(SAME_AS, candidates.get(i), candidates.get(j)));
                }
            }
        }

        // Rule 1: Similarity → SameAs (soft)
        // w: Sim(X, Y) -> SameAs(X, Y) ^2
        program.addRule(PslRule.weighted(simWeight, true,
                List.of(PslAtom.of(SIM, false, Term.var("X"), Term.var("Y"))),
                List.of(PslAtom.of(SAME_AS, false, Term.var("X"), Term.var("Y")))));

        // Rule 2: Symmetry — SameAs(X, Y) -> SameAs(Y, X)  (soft, high weight)
        // w: SameAs(X, Y) -> SameAs(Y, X) ^2
        program.addRule(PslRule.weighted(symmetryWeight, true,
                List.of(PslAtom.of(SAME_AS, false, Term.var("X"), Term.var("Y"))),
                List.of(PslAtom.of(SAME_AS, false, Term.var("Y"), Term.var("X")))));

        // Rule 3: Transitivity — SameAs(X, Y) & SameAs(Y, Z) -> SameAs(X, Z) ^2   (soft)
        // with (X != Z)
        List<String[]> transitivityDistinct = new ArrayList<>();
        transitivityDistinct.add(new String[]{"X", "Z"});
        program.addRule(new PslRule(transitivityWeight, false, true,
                List.of(PslAtom.of(SAME_AS, false, Term.var("X"), Term.var("Y")),
                        PslAtom.of(SAME_AS, false, Term.var("Y"), Term.var("Z"))),
                List.of(PslAtom.of(SAME_AS, false, Term.var("X"), Term.var("Z"))),
                transitivityDistinct));

        // Rule 4: Negative prior — push SameAs toward 0 by default
        // w: ~SameAs(X, Y) ^2   (i.e., head = ~SameAs, body empty: penalise truth)
        program.addRule(PslRule.weighted(negativePriorWeight, true,
                List.of(),
                List.of(PslAtom.of(SAME_AS, true, Term.var("X"), Term.var("Y")))));

        // Rule 5 (optional): Functional constraint — SameAs is unique per mention
        // SameAs(X, Y) & SameAs(X, Z) & (Y != Z) -> .   (hard)
        if (enforceFunctional) {
            List<String[]> funcConstraintDistinct = new ArrayList<>();
            funcConstraintDistinct.add(new String[]{"Y", "Z"});
            program.addRule(new PslRule(Double.POSITIVE_INFINITY, true, true,
                    List.of(PslAtom.of(SAME_AS, false, Term.var("X"), Term.var("Y")),
                            PslAtom.of(SAME_AS, false, Term.var("X"), Term.var("Z"))),
                    List.of(),
                    funcConstraintDistinct));
        }

        return program;
    }

    // ─── Result ──────────────────────────────────────────────────────────────────

    /**
     * Result of a collective entity resolution run.
     */
    public static final class Result {
        private final Map<String, Double> values;
        private final List<String> candidates;

        Result(Map<String, Double> values, List<String> candidates) {
            this.values = values;
            this.candidates = candidates;
        }

        /**
         * Posterior probability that {@code idA} and {@code idB} co-refer to the same entity.
         *
         * @param idA first mention ID
         * @param idB second mention ID
         * @return posterior in [0, 1]; returns 1.0 for {@code idA == idB}; 0.0 if unknown
         */
        public double sameAs(String idA, String idB) {
            if (idA.equals(idB)) return 1.0;
            String key = SAME_AS + "(" + idA + ", " + idB + ")";
            return values.getOrDefault(key, 0.0);
        }

        /**
         * Return the candidate with the highest {@code SameAs} score relative to {@code id}
         * (excluding {@code id} itself). Returns null if there are no other candidates.
         *
         * @param id the query mention
         * @return the most likely co-referent, or null
         */
        public String bestMatch(String id) {
            String best = null;
            double bestScore = -1.0;
            for (String c : candidates) {
                if (c.equals(id)) continue;
                double s = sameAs(id, c);
                if (s > bestScore) { bestScore = s; best = c; }
            }
            return best;
        }

        /**
         * All inferred {@code SameAs} values as an unmodifiable map.
         *
         * @return raw values map (includes all atoms, not just SameAs)
         */
        public Map<String, Double> values() {
            return java.util.Collections.unmodifiableMap(values);
        }

        /**
         * All candidate mention IDs.
         *
         * @return unmodifiable list of candidates
         */
        public List<String> candidates() {
            return java.util.Collections.unmodifiableList(candidates);
        }
    }
}
