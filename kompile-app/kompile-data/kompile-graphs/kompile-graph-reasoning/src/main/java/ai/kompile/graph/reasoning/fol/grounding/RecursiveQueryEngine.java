/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.semiring.Semiring;
import ai.kompile.graph.reasoning.psl.PslAtom;
import ai.kompile.graph.reasoning.psl.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Semi-naive fixpoint evaluator for recursive Datalog rules over a knowledge base.
 *
 * <h2>What this engine does</h2>
 * <p>This is the <em>Tier-1 crisp grounding layer</em> that runs before PSL MAP inference.
 * Recursive rules — rules whose head predicate appears in the rule body — must be routed
 * here instead of through {@link ai.kompile.graph.reasoning.fol.FolInferenceService},
 * which caps grounding at 10,000 pairs and performs only a single O(N²) pass.  The pair
 * cap means that a rule such as:</p>
 * <pre>
 *   path(X,Z) :- path(X,Y), edge(Y,Z)
 * </pre>
 * <p>can never reach a fixpoint under {@code FolInferenceService}: the first pass emits
 * one-hop paths, but the newly derived paths are never fed back as input to the rule.</p>
 *
 * <h2>Algorithm: semi-naive bottom-up evaluation</h2>
 * <p>For each IDB (intensional / derived) predicate {@code p}, the engine maintains:</p>
 * <ul>
 *   <li>{@code idb[p]} — all facts accumulated through the current round</li>
 *   <li>{@code delta[p]} — facts newly derived in the <em>current</em> round</li>
 * </ul>
 * <p>For every rule body with {@code k} IDB atoms, {@code k} delta-rewritten variants are
 * generated.  In each variant exactly one body atom consults {@code delta} (new facts);
 * all others consult the full {@code idb} from the prior round.  This guarantees every
 * firing uses at least one new fact, eliminating the old-with-old redundant joins that
 * make naive evaluation O(N² × R) per round.  The loop terminates when all deltas
 * are empty (fixpoint).</p>
 *
 * <h2>Stratified negation</h2>
 * <p>Negated body atoms ({@code !p(X,Y)}) are supported for predicates in lower strata
 * (strata are determined by SCC analysis of the predicate dependency graph using Tarjan's
 * algorithm).  A program is stratifiable if no SCC contains a negative edge; if it is not
 * stratifiable the engine logs a warning and treats atoms in the offending cycle as
 * {@code undefined} (confidence = 0.5).</p>
 *
 * <h2>Two-tier split with PSL</h2>
 * <p>This engine operates on <em>crisp</em> facts (confidence = 1.0).  Applying PSL
 * Łukasiewicz soft-truth semantics recursively is ill-defined — the fixpoint
 * {@code p(X,Z) = max(0, p(X,Y) + p(Y,Z) − 1)} does not converge to a unique solution
 * in general and does not correspond to path reachability (see Bach et al., JMLR 2017).
 * The correct architecture (following the PSL reference literature) is:</p>
 * <ol>
 *   <li><strong>Tier 1 (this engine)</strong>: crisp recursive grounding to fixpoint,
 *       writing derived facts with {@code confidence = 1.0} into the caller's fact set.</li>
 *   <li><strong>Tier 2 (PSL)</strong>: MAP inference over the fully grounded program,
 *       treating the derived facts as observed atoms.  Soft-truth scores for open-world
 *       atoms are then computed by {@link ai.kompile.graph.reasoning.psl.HlMrfMapInference}.</li>
 * </ol>
 *
 * <h2>Infra-free</h2>
 * <p>No Spring, no JPA, no external dependencies beyond those already present in
 * {@code kompile-graph-reasoning}.</p>
 *
 * @see ConjunctiveQueryEngine
 * @see JoinKernel
 */
public final class RecursiveQueryEngine {

    private static final Logger log = LoggerFactory.getLogger(RecursiveQueryEngine.class);

    /** Default maximum number of fixpoint iterations before early termination. */
    public static final int DEFAULT_MAX_ROUNDS = 10_000;

    /** Default maximum number of derived facts before early termination. */
    public static final int DEFAULT_MAX_DERIVED_FACTS = 500_000;

    /**
     * Default maximum number of distinct derivations kept per derived atom.
     *
     * <p>When the same ground atom is derivable by more than this many distinct rule+parents
     * combinations, additional derivations are counted but dropped to bound memory at the
     * 500k-fact scale.</p>
     */
    public static final int DEFAULT_MAX_DERIVATIONS_PER_ATOM = 4;

    private RecursiveQueryEngine() {}

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * A body or head atom in a Datalog rule.
     *
     * <p>Arguments may be variables (strings starting with {@code "?"}) or ground
     * constants.  Negated body atoms are expressed by setting {@link #negated()} to
     * {@code true}; the engine enforces the Datalog <em>safety</em> condition that
     * every variable in a negated literal must also appear in a positive literal in
     * the same rule body.</p>
     *
     * @param predicate the predicate name
     * @param args      argument list (variables or constants)
     * @param negated   whether this atom is negated ({@code ¬p})
     */
    public record RuleAtom(String predicate, List<String> args, boolean negated) {

        public RuleAtom {
            java.util.Objects.requireNonNull(predicate, "predicate must not be null");
            args = (args == null) ? List.of() : List.copyOf(args);
        }

        /** Convenience factory for a positive atom. */
        public static RuleAtom pos(String predicate, String... args) {
            return new RuleAtom(predicate, Arrays.asList(args), false);
        }

        /** Convenience factory for a negated atom. */
        public static RuleAtom neg(String predicate, String... args) {
            return new RuleAtom(predicate, Arrays.asList(args), true);
        }
    }

    /**
     * A Datalog rule: a head predicate with its argument list, and a body conjunction.
     *
     * <p>A rule is <em>recursive</em> if the head predicate appears in any positive
     * body atom.  Mutual recursion (two predicates each appearing in the other's body)
     * is supported.</p>
     *
     * @param headPredicate the predicate name derived by this rule
     * @param headArgs      argument list for the head (must be variables only; a head
     *                      with a constant argument would produce a fixed-arity fact
     *                      independently of the body — this is a degenerate case not
     *                      validated here)
     * @param body          the body conjuncts (positive or negated atoms)
     */
    public record DatalogRule(String headPredicate, List<String> headArgs, List<RuleAtom> body) {

        public DatalogRule {
            java.util.Objects.requireNonNull(headPredicate, "headPredicate must not be null");
            headArgs = (headArgs == null) ? List.of() : List.copyOf(headArgs);
            body = (body == null) ? List.of() : List.copyOf(body);
        }

        /**
         * Whether this rule is recursive: the head predicate appears positively in the body.
         *
         * @return {@code true} if the head predicate appears in a positive body atom
         */
        public boolean isRecursive() {
            for (RuleAtom atom : body) {
                if (!atom.negated() && atom.predicate().equals(headPredicate)) return true;
            }
            return false;
        }

        /**
         * Whether this rule references a given predicate positively in the body.
         *
         * @param predicate the predicate to check
         * @return {@code true} if any positive body atom has this predicate
         */
        public boolean bodyRefersPositive(String predicate) {
            for (RuleAtom atom : body) {
                if (!atom.negated() && atom.predicate().equals(predicate)) return true;
            }
            return false;
        }

        /**
         * Whether this rule references a given predicate negatively in the body.
         *
         * @param predicate the predicate to check
         * @return {@code true} if any negated body atom has this predicate
         */
        public boolean bodyRefersNegative(String predicate) {
            for (RuleAtom atom : body) {
                if (atom.negated() && atom.predicate().equals(predicate)) return true;
            }
            return false;
        }
    }

    /**
     * Provider of base-relation tuples (EDB).
     *
     * <p>Implementations may delegate to an
     * {@link ai.kompile.graph.reasoning.fol.InferredFactStore},
     * a {@link ai.kompile.graph.reasoning.fol.FactStore}, a
     * {@link ai.kompile.graph.reasoning.model.ReasoningGraph} adjacency list, or any
     * other storage-agnostic source.  The engine is deliberately storage-agnostic: it
     * only calls {@code tuplesFor} to enumerate candidate ground tuples.</p>
     */
    @FunctionalInterface
    public interface EdbProvider {
        /**
         * Return all ground tuples for the given predicate as lists of constant strings.
         *
         * @param predicate the predicate name to look up
         * @return the ground tuples; empty list if no facts exist for this predicate
         */
        List<List<String>> tuplesFor(String predicate);
    }

    /**
     * One provenance record for a derived ground atom: which rule fired and which
     * ground body-atom keys (the EDB/IDB parents in this particular binding) supported it.
     *
     * @param ruleDisplay    the Datalog rule's head predicate used as a display name
     *                       (format: {@code "headPredicate :- body1, body2, ..."})
     * @param parentAtomKeys ground atom keys of the body atoms that matched in this firing
     */
    public record Derivation(String ruleDisplay, List<String> parentAtomKeys) {

        public Derivation {
            java.util.Objects.requireNonNull(ruleDisplay, "ruleDisplay must not be null");
            parentAtomKeys = (parentAtomKeys == null) ? List.of() : List.copyOf(parentAtomKeys);
        }
    }

    /**
     * The result of a fixpoint evaluation.
     *
     * @param derivedFacts      derived ground tuples partitioned by predicate (IDB)
     * @param roundsCompleted   number of fixpoint iterations completed
     * @param isComplete        {@code false} if a termination guard was hit before
     *                          the natural fixpoint; {@code true} otherwise
     * @param terminationReason human-readable reason for termination (empty if complete)
     * @param derivationIndex   per-atom-key list of {@link Derivation}s (capped at
     *                          {@link RecursiveQueryEngine#DEFAULT_MAX_DERIVATIONS_PER_ATOM})
     * @param derivationDropped number of derivations that were counted but dropped because
     *                          the per-atom cap was reached
     */
    public record FixpointResult(
            Map<String, Set<List<String>>> derivedFacts,
            int roundsCompleted,
            boolean isComplete,
            String terminationReason,
            Map<String, List<Derivation>> derivationIndex,
            int derivationDropped) {

        public FixpointResult {
            derivedFacts = Collections.unmodifiableMap(new LinkedHashMap<>(derivedFacts));
            derivationIndex = Collections.unmodifiableMap(new LinkedHashMap<>(derivationIndex));
        }

        /**
         * Return all known {@link Derivation}s for the given atom key (EDB atoms return an
         * empty list — they have no derivation because they are base facts).
         *
         * @param atomKey the canonical atom key (e.g. {@code "reachable(a, c)"})
         * @return unmodifiable list of derivations, empty if the atom is EDB or unknown
         */
        public List<Derivation> derivations(String atomKey) {
            List<Derivation> result = derivationIndex.get(atomKey);
            return (result == null) ? List.of() : result;
        }

        /**
         * Materialise the derived facts as {@link InferredFact} objects with
         * {@code confidence = 1.0} and the given {@code runId}, ready for merging into
         * an {@link ai.kompile.graph.reasoning.fol.InferredFactStore}.
         *
         * <p>Each fact's {@link InferredFact#supportingFactKeys()} is populated from the
         * first {@link Derivation}'s parent atom keys (if any).
         * {@link InferredFact#supportingRuleIds()} contains all distinct rule display strings
         * across all kept derivations for that atom (first = primary firing).</p>
         *
         * @param runId the inference run identifier
         * @return list of inferred facts (order unspecified within a predicate)
         */
        public List<InferredFact> toInferredFacts(String runId) {
            List<InferredFact> out = new ArrayList<>();
            long version = 0L;
            Instant now = Instant.now();
            for (Map.Entry<String, Set<List<String>>> entry : derivedFacts.entrySet()) {
                String pred = entry.getKey();
                for (List<String> tuple : entry.getValue()) {
                    String atomKey = buildAtomKey(pred, tuple);
                    List<Derivation> derivs = derivationIndex.getOrDefault(atomKey, List.of());

                    // supportingFactKeys: parents from the first (primary) derivation
                    List<String> supportingFactKeys = derivs.isEmpty()
                            ? List.of()
                            : derivs.get(0).parentAtomKeys();

                    // supportingRuleIds: all distinct rule displays across kept derivations
                    List<String> supportingRuleIds = derivs.stream()
                            .map(Derivation::ruleDisplay)
                            .distinct()
                            .collect(Collectors.toList());

                    out.add(new InferredFact(atomKey, 1.0, 1.0,
                            supportingFactKeys, supportingRuleIds, runId, version++, now));
                }
            }
            return out;
        }

        /** Build an atom key string from predicate name and argument tuple. */
        private static String buildAtomKey(String pred, List<String> args) {
            if (args.isEmpty()) return pred;
            return pred + "(" + String.join(", ", args) + ")";
        }
    }

    // ─── Evaluate ────────────────────────────────────────────────────────────────

    /**
     * Evaluate a set of Datalog rules to fixpoint using semi-naive bottom-up evaluation.
     *
     * <p>The algorithm:</p>
     * <ol>
     *   <li>Identify IDB predicates (those that appear as rule heads).</li>
     *   <li>Stratify the rule set using Tarjan SCC on the predicate dependency graph.</li>
     *   <li>For each stratum (in topological order), run the semi-naive fixpoint loop
     *       over the rules in that stratum, seeding from EDB and lower strata.</li>
     * </ol>
     *
     * <p>Negated body atoms are evaluated with negation-as-failure (closed-world
     * assumption): the atom fails if the ground instance is in the current IDB or EDB.</p>
     *
     * @param rules                  the Datalog rule set
     * @param edb                    provider of base (EDB) tuples
     * @param maxRounds              maximum fixpoint rounds (termination guard)
     * @param maxFacts               maximum total derived facts (termination guard)
     * @param maxDerivationsPerAtom  maximum number of distinct derivations to keep per derived
     *                               atom (additional ones are counted but dropped);
     *                               use {@link #DEFAULT_MAX_DERIVATIONS_PER_ATOM} as the default
     * @return the derived facts at fixpoint, plus metadata
     */
    public static FixpointResult evaluate(List<DatalogRule> rules,
                                          EdbProvider edb,
                                          int maxRounds,
                                          int maxFacts,
                                          int maxDerivationsPerAtom) {
        java.util.Objects.requireNonNull(rules, "rules must not be null");
        java.util.Objects.requireNonNull(edb, "edb must not be null");
        if (rules.isEmpty()) {
            return new FixpointResult(Map.of(), 0, true, "", Map.of(), 0);
        }

        // Validate negation safety for all rules
        for (DatalogRule rule : rules) {
            validateSafety(rule);
        }

        // Identify all IDB predicates (any predicate that is the head of some rule)
        Set<String> idbPredicates = new LinkedHashSet<>();
        for (DatalogRule rule : rules) {
            idbPredicates.add(rule.headPredicate());
        }

        // Build predicate dependency graph and compute strata via Tarjan SCC
        List<List<DatalogRule>> strata = stratify(rules, idbPredicates);

        // Accumulated IDB across strata (stratum 0's derived facts become EDB for stratum 1, etc.)
        Map<String, Set<List<String>>> globalIdb = new LinkedHashMap<>();
        for (String p : idbPredicates) {
            globalIdb.put(p, new LinkedHashSet<>());
        }

        // Global derivation index: atomKey → list of Derivations (capped per atom)
        Map<String, List<Derivation>> globalDerivations = new LinkedHashMap<>();
        int[] droppedDerivations = {0};

        int totalRounds = 0;
        int totalFacts = 0;
        String terminationReason = "";
        boolean isComplete = true;

        for (List<DatalogRule> stratum : strata) {
            if (stratum.isEmpty()) continue;

            // Build a combined EDB provider: original EDB + lower-stratum IDB already accumulated
            final Map<String, Set<List<String>>> idbSnapshot = new LinkedHashMap<>(globalIdb);
            EdbProvider combinedEdb = pred -> {
                // Check accumulated IDB first (lower strata already evaluated)
                Set<List<String>> fromIdb = idbSnapshot.get(pred);
                if (fromIdb != null && !fromIdb.isEmpty()) {
                    return new ArrayList<>(fromIdb);
                }
                return edb.tuplesFor(pred);
            };

            StratumResult result = evaluateStratum(stratum, idbPredicates, combinedEdb,
                    globalIdb, globalDerivations, droppedDerivations,
                    maxDerivationsPerAtom, maxRounds - totalRounds, maxFacts - totalFacts);

            totalRounds += result.rounds;
            totalFacts += result.newFacts;
            if (!result.complete) {
                isComplete = false;
                terminationReason = result.reason;
                break;
            }
        }

        return new FixpointResult(new LinkedHashMap<>(globalIdb),
                totalRounds, isComplete, terminationReason,
                globalDerivations, droppedDerivations[0]);
    }

    /**
     * Convenience overload using the default bounds and default derivation cap.
     *
     * @param rules     the Datalog rule set
     * @param edb       provider of base (EDB) tuples
     * @param maxRounds maximum fixpoint rounds (termination guard)
     * @param maxFacts  maximum total derived facts (termination guard)
     * @return the derived facts at fixpoint
     */
    public static FixpointResult evaluate(List<DatalogRule> rules,
                                          EdbProvider edb,
                                          int maxRounds,
                                          int maxFacts) {
        return evaluate(rules, edb, maxRounds, maxFacts, DEFAULT_MAX_DERIVATIONS_PER_ATOM);
    }

    /**
     * Convenience overload using the default bounds.
     *
     * @param rules the Datalog rule set
     * @param edb   provider of base (EDB) tuples
     * @return the derived facts at fixpoint
     */
    public static FixpointResult evaluate(List<DatalogRule> rules, EdbProvider edb) {
        return evaluate(rules, edb, DEFAULT_MAX_ROUNDS, DEFAULT_MAX_DERIVED_FACTS,
                DEFAULT_MAX_DERIVATIONS_PER_ATOM);
    }

    // ─── Annotated evaluation (semiring provenance) ──────────────────────────────

    /**
     * Maximum fixpoint rounds for the annotation propagation pass (separate from the
     * crisp-fixpoint round cap).  Used by the Viterbi semiring where annotations may
     * require multiple passes over cyclic derivation paths to converge.
     */
    public static final int DEFAULT_ANNOTATION_MAX_ROUNDS = 200;

    /**
     * Convergence threshold for the Viterbi (and any continuous) semiring: stop iterating
     * annotations when the largest change in any Double annotation falls below this value.
     */
    public static final double ANNOTATION_CONVERGENCE_EPS = 1e-12;

    /**
     * Evaluate a Datalog rule set to fixpoint using the standard semi-naive algorithm,
     * then compute semiring annotations for every derived (IDB) atom by iterating the
     * recorded derivation graph to a fixpoint over the given {@code semiring}.
     *
     * <h2>Algorithm for the annotation pass</h2>
     * <p>After the crisp fixpoint:</p>
     * <ol>
     *   <li>Each EDB atom key that appears as a parent in any recorded derivation is given
     *       an initial annotation from {@code edbAnnotator(atomKey)}.</li>
     *   <li>Each IDB atom starts at {@code semiring.zero()}.</li>
     *   <li>For each IDB atom {@code h} with recorded derivations
     *       {@code {(rule_i, [p_{i,1}, …, p_{i,n}])}}, compute:
     *       <pre>
     *         ann(h) = ⊕_i  (ann(p_{i,1}) ⊗ ann(p_{i,2}) ⊗ … ⊗ ann(p_{i,n}))
     *       </pre>
     *       where ⊕ is {@link Semiring#plus} and ⊗ is {@link Semiring#times}.
     *   </li>
     *   <li>Repeat until annotations stop changing (absorptive semirings) or until the
     *       change is below {@link #ANNOTATION_CONVERGENCE_EPS} (for Double annotations)
     *       or until {@link #DEFAULT_ANNOTATION_MAX_ROUNDS} is reached.</li>
     * </ol>
     *
     * <h2>Important caveats</h2>
     * <ul>
     *   <li><strong>Derivation cap</strong>: only the derivations recorded in
     *       {@link FixpointResult#derivationIndex()} are used (capped at
     *       {@code maxDerivationsPerAtom}).  For {@link ai.kompile.graph.reasoning.fol.semiring.CountingSemiring}
     *       the annotation is a lower bound; for {@link ai.kompile.graph.reasoning.fol.semiring.TopKProofsSemiring}
     *       raise {@code maxDerivationsPerAtom} to match {@code k}.</li>
     *   <li><strong>Cycles</strong>: the engine terminates for absorptive semirings by
     *       monotone convergence.  For the Viterbi semiring it terminates when no
     *       annotation improves by more than {@link #ANNOTATION_CONVERGENCE_EPS}.  For
     *       truly cyclic derivation graphs under non-absorptive semirings (e.g. counting),
     *       the round cap limits computation.</li>
     * </ul>
     *
     * @param <K>          the annotation type
     * @param rules        the Datalog rule set
     * @param edb          provider of base (EDB) tuples
     * @param semiring     the semiring to use for annotation propagation
     * @param edbAnnotator maps a ground EDB atom key (e.g. {@code "edge(a, b)"}) to its
     *                     initial annotation; called for every EDB key that appears as a
     *                     parent in a recorded derivation; return {@code semiring.one()} for
     *                     uniform weight (counting), or the atom's truth value converted to
     *                     {@code Double} for Viterbi; must not return null
     * @return an {@link AnnotatedResult} wrapping the crisp fixpoint plus the annotation map
     */
    public static <K> AnnotatedResult<K> evaluateAnnotated(List<DatalogRule> rules,
                                                             EdbProvider edb,
                                                             Semiring<K> semiring,
                                                             Function<String, K> edbAnnotator) {
        return evaluateAnnotated(rules, edb, semiring, edbAnnotator,
                DEFAULT_MAX_ROUNDS, DEFAULT_MAX_DERIVED_FACTS, DEFAULT_MAX_DERIVATIONS_PER_ATOM);
    }

    /**
     * Full-parameter version of {@link #evaluateAnnotated} with explicit bounds.
     *
     * @param <K>                    the annotation type
     * @param rules                  the Datalog rule set
     * @param edb                    provider of base (EDB) tuples
     * @param semiring               the semiring for annotation propagation
     * @param edbAnnotator           initial annotation for EDB atom keys
     * @param maxRounds              crisp fixpoint round cap
     * @param maxFacts               crisp fixpoint fact cap
     * @param maxDerivationsPerAtom  derivation cap per atom (recommend ≥ k for TopK semiring)
     * @return the annotated result
     */
    public static <K> AnnotatedResult<K> evaluateAnnotated(List<DatalogRule> rules,
                                                             EdbProvider edb,
                                                             Semiring<K> semiring,
                                                             Function<String, K> edbAnnotator,
                                                             int maxRounds,
                                                             int maxFacts,
                                                             int maxDerivationsPerAtom) {
        java.util.Objects.requireNonNull(semiring, "semiring must not be null");
        java.util.Objects.requireNonNull(edbAnnotator, "edbAnnotator must not be null");

        // Step 1: Run crisp fixpoint to get derived facts + derivation index
        FixpointResult fixpoint = evaluate(rules, edb, maxRounds, maxFacts, maxDerivationsPerAtom);

        // Step 2: Propagate semiring annotations over the derivation graph
        Map<String, K> annotations = propagateAnnotations(fixpoint, semiring, edbAnnotator);

        return new AnnotatedResult<>(fixpoint, annotations, semiring.zero());
    }

    /**
     * Propagate semiring annotations over the derivation graph recorded in the fixpoint result.
     *
     * <p>Algorithm: for each IDB atom, its annotation is the {@code plus} of the {@code times}
     * of its body-parent annotations across all recorded derivations.  Iterate until convergence.</p>
     */
    private static <K> Map<String, K> propagateAnnotations(FixpointResult fixpoint,
                                                             Semiring<K> semiring,
                                                             Function<String, K> edbAnnotator) {
        Map<String, K> ann = new LinkedHashMap<>();

        // Collect all atom keys that appear anywhere in derivation index (IDB heads + EDB parents)
        Set<String> idbKeys = new LinkedHashSet<>(fixpoint.derivationIndex().keySet());

        // Collect all EDB parent keys referenced in derivations
        Set<String> edbParentKeys = new LinkedHashSet<>();
        for (Map.Entry<String, List<Derivation>> entry : fixpoint.derivationIndex().entrySet()) {
            for (Derivation d : entry.getValue()) {
                edbParentKeys.addAll(d.parentAtomKeys());
            }
        }

        // Seed: EDB parents get their annotation from edbAnnotator
        for (String edbKey : edbParentKeys) {
            if (!idbKeys.contains(edbKey)) {
                // Pure EDB atom — annotate from caller-supplied function
                ann.put(edbKey, edbAnnotator.apply(edbKey));
            }
        }

        // IDB atoms start at zero; also seed any that are EDB parents too
        for (String idbKey : idbKeys) {
            // An atom that is BOTH an IDB head AND appears as a parent in its own derivation
            // (self-supporting cycle) starts at zero; an IDB atom that is ALSO in edbParentKeys
            // (e.g. same predicate in base facts + recursive rule) needs its EDB contribution too.
            ann.put(idbKey, semiring.zero());
        }

        // Fixpoint iteration over annotations.
        // Each round: re-derive every IDB annotation from scratch (zero + fold derivations).
        // This avoids double-counting for non-absorptive semirings (e.g. counting) and
        // correctly handles ordering dependencies between IDB atoms that feed each other.
        boolean changed = true;
        int round = 0;
        while (changed && round < DEFAULT_ANNOTATION_MAX_ROUNDS) {
            changed = false;
            // Snapshot of annotations at the START of this round (used as parent values
            // so that each round is a consistent pass over last round's stable state).
            Map<String, K> prevAnn = new LinkedHashMap<>(ann);

            for (Map.Entry<String, List<Derivation>> entry : fixpoint.derivationIndex().entrySet()) {
                String headKey = entry.getKey();
                List<Derivation> derivations = entry.getValue();
                if (derivations.isEmpty()) continue;

                // Re-accumulate from zero each round so we don't double-count
                K newAnn = semiring.zero();
                for (Derivation d : derivations) {
                    // Product of parent annotations along this derivation (from prevAnn snapshot)
                    K derivAnn = semiring.one();
                    for (String parentKey : d.parentAtomKeys()) {
                        K parentAnn = prevAnn.get(parentKey);
                        if (parentAnn == null) {
                            // Parent not in snapshot — use edbAnnotator as fallback (EDB atom)
                            parentAnn = edbAnnotator.apply(parentKey);
                            // Store into ann for future rounds
                            ann.put(parentKey, parentAnn);
                            prevAnn.put(parentKey, parentAnn);
                        }
                        derivAnn = semiring.times(derivAnn, parentAnn);
                    }
                    newAnn = semiring.plus(newAnn, derivAnn);
                }

                // Compare with previous annotation to detect convergence
                K prevHeadAnn = prevAnn.getOrDefault(headKey, semiring.zero());
                if (!newAnn.equals(prevHeadAnn)) {
                    ann.put(headKey, newAnn);
                    // For Double (Viterbi) annotations, only mark changed if delta > eps
                    if (newAnn instanceof Double dn && prevHeadAnn instanceof Double dc) {
                        if (Math.abs(dn - dc) > ANNOTATION_CONVERGENCE_EPS) {
                            changed = true;
                        }
                    } else {
                        changed = true;
                    }
                }
            }
            round++;
        }

        if (round >= DEFAULT_ANNOTATION_MAX_ROUNDS && changed) {
            log.warn("RecursiveQueryEngine.propagateAnnotations: annotation fixpoint did not "
                    + "converge after {} rounds — returning best approximation", DEFAULT_ANNOTATION_MAX_ROUNDS);
        }

        return ann;
    }

    // ─── Stratum evaluation ──────────────────────────────────────────────────────

    private record StratumResult(int rounds, int newFacts, boolean complete, String reason) {}

    /** Build the rule display string used as a derivation identifier for a rule. */
    private static String ruleDisplay(DatalogRule rule) {
        StringBuilder sb = new StringBuilder(rule.headPredicate());
        sb.append('(');
        sb.append(String.join(", ", rule.headArgs()));
        sb.append(") :- ");
        List<String> bodyParts = new ArrayList<>();
        for (RuleAtom a : rule.body()) {
            StringBuilder part = new StringBuilder();
            if (a.negated()) part.append('!');
            part.append(a.predicate()).append('(').append(String.join(", ", a.args())).append(')');
            bodyParts.add(part.toString());
        }
        sb.append(String.join(", ", bodyParts));
        return sb.toString();
    }

    /**
     * Record a derivation for the given derived atom key if the cap allows and the exact
     * (ruleDisplay, parentAtomKeys) pair has not already been recorded; otherwise increment
     * the dropped counter.
     *
     * <p>Deduplicating by (ruleDisplay, parents) prevents the semi-naive fixpoint from
     * recording the same derivation twice when an atom is re-encountered in a later round
     * (e.g. path(a,c) derived in the seed phase and again in the first delta round via the
     * same rule firing on the same parent atoms). The diamond test still records two
     * <em>distinct</em> (ruleDisplay, parents) derivations because the parent atom keys
     * differ (via-b vs. via-c).</p>
     */
    private static void recordDerivation(String atomKey,
                                          Derivation derivation,
                                          Map<String, List<Derivation>> derivationIndex,
                                          int[] droppedDerivations,
                                          int maxDerivationsPerAtom) {
        List<Derivation> existing = derivationIndex.computeIfAbsent(atomKey, k -> new ArrayList<>());
        // Deduplicate: skip if an identical (ruleDisplay, parentAtomKeys) pair is already recorded
        if (existing.contains(derivation)) {
            return;
        }
        if (existing.size() < maxDerivationsPerAtom) {
            existing.add(derivation);
        } else {
            droppedDerivations[0]++;
        }
    }

    /**
     * Run the semi-naive fixpoint loop for a single stratum.
     *
     * <p>Algorithm: seed IDB from the combinedEdb for IDB predicates appearing in this
     * stratum, then iterate delta-rewritten variants until all deltas are empty.</p>
     *
     * <p>During evaluation, for each newly derived ground atom, a {@link Derivation} is
     * recorded in {@code globalDerivations} (up to {@code maxDerivationsPerAtom} per atom).
     * Additional derivations are counted but dropped to bound memory.</p>
     */
    private static StratumResult evaluateStratum(List<DatalogRule> stratumRules,
                                                  Set<String> idbPredicates,
                                                  EdbProvider combinedEdb,
                                                  Map<String, Set<List<String>>> globalIdb,
                                                  Map<String, List<Derivation>> globalDerivations,
                                                  int[] droppedDerivations,
                                                  int maxDerivationsPerAtom,
                                                  int maxRounds,
                                                  int maxFacts) {
        // Collect IDB predicates in this stratum's heads.
        // IMPORTANT: only predicates HEADED in this stratum are "IDB" for the purpose of
        // the delta iteration.  Lower-stratum predicates (which also appear in globalIdb)
        // are treated as EDB here — they are fully evaluated and available via combinedEdb.
        Set<String> stratumIdb = new LinkedHashSet<>();
        for (DatalogRule r : stratumRules) {
            stratumIdb.add(r.headPredicate());
        }

        // delta[p]: new facts derived in the current round
        Map<String, Set<List<String>>> delta = new LinkedHashMap<>();
        for (String p : stratumIdb) {
            delta.put(p, new LinkedHashSet<>());
        }

        // idb[p]: full accumulated set through the current round (starts empty for this stratum)
        Map<String, Set<List<String>>> idb = new LinkedHashMap<>();
        for (String p : stratumIdb) {
            // Seed the IDB working set from prior-stratum IDB AND the EDB base facts for this
            // predicate. In standard Datalog a predicate may be BOTH extensional (base facts) and
            // intensional (a rule head) — e.g. recursive transitive closure
            // {@code ancestor(X,Z) :- ancestor(X,Y), ancestor(Y,Z)} over base ancestor facts.
            // Unioning the EDB base tuples here lets such same-predicate recursive rules fire.
            // For the distinct-IDB-head convention (e.g. {@code derived_<pred>}) the EDB has no
            // tuples for the head predicate, so this union is a no-op.
            Set<List<String>> seed = new LinkedHashSet<>(globalIdb.get(p));
            seed.addAll(combinedEdb.tuplesFor(p));
            idb.put(p, seed);
        }

        // Build negation facts from the GLOBAL idb (includes fully-evaluated lower strata)
        Set<String> seedNegFacts = buildNegationFacts(globalIdb, combinedEdb, idbPredicates);

        // --- Seed phase: derive initial facts from EDB (including lower-stratum IDB via combinedEdb).
        // Use stratumIdb (not global idbPredicates) so that lower-stratum predicates are treated
        // as EDB and matched via combinedEdb rather than skipped as "not yet derived" IDB.
        for (DatalogRule rule : stratumRules) {
            // Use matchBodyAllDerivations to capture ALL derivation routes per head tuple
            // (matchBodyWithParents only kept the first via putIfAbsent, losing alternative routes).
            Map<List<String>, List<List<String>>> derivedWithParents =
                    matchBodyAllDerivations(rule, stratumIdb, idb, null, -1, combinedEdb, seedNegFacts);
            Set<List<String>> existing = idb.getOrDefault(rule.headPredicate(), new LinkedHashSet<>());
            String display = ruleDisplay(rule);
            for (Map.Entry<List<String>, List<List<String>>> entry : derivedWithParents.entrySet()) {
                List<String> tuple = entry.getKey();
                String atomKey = buildAtomKey(rule.headPredicate(), tuple);
                if (!existing.contains(tuple)) {
                    delta.get(rule.headPredicate()).add(tuple);
                    existing.add(tuple);
                    // Record all derivation routes discovered in this seed-phase firing
                    for (List<String> parents : entry.getValue()) {
                        recordDerivation(atomKey, new Derivation(display, parents),
                                globalDerivations, droppedDerivations, maxDerivationsPerAtom);
                    }
                } else {
                    // Atom already known (from EDB seed or earlier rule in this stratum) —
                    // still record all additional derivation routes so multi-rule provenance is captured.
                    // recordDerivation deduplicates by (ruleDisplay, parents) so identical routes
                    // from fixpoint re-iterations are not counted twice.
                    for (List<String> parents : entry.getValue()) {
                        recordDerivation(atomKey, new Derivation(display, parents),
                                globalDerivations, droppedDerivations, maxDerivationsPerAtom);
                    }
                }
            }
            idb.put(rule.headPredicate(), existing);
        }

        int totalNewFacts = countFacts(delta);
        int round = 0;

        // --- Fixpoint iteration ---
        while (round < maxRounds) {
            if (allEmpty(delta)) break;

            Map<String, Set<List<String>>> newDelta = new LinkedHashMap<>();
            for (String p : stratumIdb) {
                newDelta.put(p, new LinkedHashSet<>());
            }

            Set<String> negFacts =
                    buildNegationFacts(globalIdb, combinedEdb, idbPredicates);

            for (DatalogRule rule : stratumRules) {
                int idbBodyCount = countPositiveIdbBodyAtoms(rule, stratumIdb);

                if (idbBodyCount == 0) {
                    // No current-stratum IDB atoms in body: already fully fired in seed phase
                    continue;
                }

                // Generate one delta-rewritten variant per positive stratum-IDB body atom
                for (int variantIdx = 0; variantIdx < rule.body().size(); variantIdx++) {
                    RuleAtom atom = rule.body().get(variantIdx);
                    if (atom.negated()) continue;
                    if (!stratumIdb.contains(atom.predicate())) continue;

                    Set<List<String>> deltaForAtom = delta.get(atom.predicate());
                    if (deltaForAtom == null || deltaForAtom.isEmpty()) continue;

                    Map<List<String>, List<List<String>>> variantFacts =
                            matchBodyAllDerivations(rule, stratumIdb,
                                    idb, delta, variantIdx, combinedEdb, negFacts);

                    String display = ruleDisplay(rule);
                    Set<List<String>> headIdb = idb.getOrDefault(rule.headPredicate(),
                            new LinkedHashSet<>());
                    for (Map.Entry<List<String>, List<List<String>>> entry : variantFacts.entrySet()) {
                        List<String> tuple = entry.getKey();
                        if (!headIdb.contains(tuple)) {
                            newDelta.get(rule.headPredicate()).add(tuple);
                            headIdb.add(tuple);
                            totalNewFacts++;
                            String atomKey = buildAtomKey(rule.headPredicate(), tuple);
                            for (List<String> parents : entry.getValue()) {
                                recordDerivation(atomKey, new Derivation(display, parents),
                                        globalDerivations, droppedDerivations, maxDerivationsPerAtom);
                            }
                            if (totalNewFacts >= maxFacts) {
                                String reason = "MAX_DERIVED_FACTS (" + maxFacts + ") reached";
                                log.warn("RecursiveQueryEngine: {} — returning partial result", reason);
                                mergeIntoGlobal(globalIdb, idb);
                                return new StratumResult(round + 1, totalNewFacts, false, reason);
                            }
                        } else {
                            // Already known atom — still record additional derivation routes
                            // (e.g. second rule fires; recordDerivation deduplicates same routes)
                            String atomKey = buildAtomKey(rule.headPredicate(), tuple);
                            for (List<String> parents : entry.getValue()) {
                                recordDerivation(atomKey, new Derivation(display, parents),
                                        globalDerivations, droppedDerivations, maxDerivationsPerAtom);
                            }
                        }
                    }
                    idb.put(rule.headPredicate(), headIdb);
                }
            }

            delta = newDelta;
            round++;
        }

        if (round >= maxRounds && !allEmpty(delta)) {
            String reason = "MAX_FIXPOINT_ROUNDS (" + maxRounds + ") reached";
            log.warn("RecursiveQueryEngine: {} — returning partial result", reason);
            mergeIntoGlobal(globalIdb, idb);
            return new StratumResult(round, totalNewFacts, false, reason);
        }

        mergeIntoGlobal(globalIdb, idb);
        return new StratumResult(round, totalNewFacts, true, "");
    }

    // ─── Body matching ───────────────────────────────────────────────────────────

    /**
     * Match a rule body and return a map from head tuple to the list of ground body-atom
     * keys (the provenance parents) for each successful binding.
     *
     * <p>When {@code deltaVariantIdx >= 0}, atom {@code deltaVariantIdx} uses the
     * {@code delta} set instead of the full {@code idb} set.  All other positive IDB
     * atoms use {@code idb}.</p>
     *
     * <p>The returned map uses the <em>last</em> parent-list seen for a given head tuple
     * when multiple bindings produce the same tuple — callers that need all derivations
     * per atom should call this once per delta-variant and record each firing separately.</p>
     *
     * @param rule           the rule to evaluate
     * @param idbPredicates  set of all IDB predicate names
     * @param idb            accumulated IDB facts per predicate
     * @param delta          current-round delta per predicate (may be null for seed phase)
     * @param deltaVariantIdx body-atom index that uses delta (-1 = no delta variant)
     * @param edb            EDB provider for non-IDB predicates
     * @param negFacts       set of fact-key strings for negation-as-failure checks
     * @return map: head tuple → list of ground body-atom keys that produced it
     */
    private static Map<List<String>, List<String>> matchBodyWithParents(
            DatalogRule rule,
            Set<String> idbPredicates,
            Map<String, Set<List<String>>> idb,
            Map<String, Set<List<String>>> delta,
            int deltaVariantIdx,
            EdbProvider edb,
            Set<String> negFacts) {
        // allDerivations: head-tuple → ALL distinct parent-key-lists that derive it
        // (was previously putIfAbsent = first-wins, which silently dropped alternative routes)
        Map<List<String>, List<List<String>>> allDerivations = new LinkedHashMap<>();
        matchBodyRec(rule.body(), 0, new LinkedHashMap<>(), new ArrayList<>(),
                rule, idbPredicates, idb, delta, deltaVariantIdx, edb, negFacts, allDerivations);
        // Collapse to head → first parents for backward-compatible callers;
        // callers that need all derivations can call matchBodyAllDerivations directly.
        Map<List<String>, List<String>> results = new LinkedHashMap<>();
        for (Map.Entry<List<String>, List<List<String>>> e : allDerivations.entrySet()) {
            if (!e.getValue().isEmpty()) {
                results.put(e.getKey(), e.getValue().get(0));
            }
        }
        return results;
    }

    /**
     * Like {@link #matchBodyWithParents} but returns ALL distinct derivations (parent-key-lists)
     * per head tuple, not just the first. Used when recording multi-route provenance.
     */
    private static Map<List<String>, List<List<String>>> matchBodyAllDerivations(
            DatalogRule rule,
            Set<String> idbPredicates,
            Map<String, Set<List<String>>> idb,
            Map<String, Set<List<String>>> delta,
            int deltaVariantIdx,
            EdbProvider edb,
            Set<String> negFacts) {
        Map<List<String>, List<List<String>>> results = new LinkedHashMap<>();
        matchBodyRec(rule.body(), 0, new LinkedHashMap<>(), new ArrayList<>(),
                rule, idbPredicates, idb, delta, deltaVariantIdx, edb, negFacts, results);
        return results;
    }

    /**
     * Recursive body-matching procedure.
     *
     * <p>For each body atom in order:
     * <ul>
     *   <li>Positive IDB atom at {@code deltaVariantIdx}: iterate {@code delta[pred]}</li>
     *   <li>Positive IDB atom (other): iterate {@code idb[pred]}</li>
     *   <li>Positive EDB atom: iterate {@code edb.tuplesFor(pred)}</li>
     *   <li>Negated atom: check negation-as-failure against all known facts</li>
     * </ul>
     *
     * <p>{@code parentKeysAccum} accumulates the ground atom keys of the positive body atoms
     * matched so far in the current binding path — negated atoms are not tracked as parents.</p>
     */
    private static void matchBodyRec(List<RuleAtom> body,
                                      int idx,
                                      Map<String, String> binding,
                                      List<String> parentKeysAccum,
                                      DatalogRule rule,
                                      Set<String> idbPredicates,
                                      Map<String, Set<List<String>>> idb,
                                      Map<String, Set<List<String>>> delta,
                                      int deltaVariantIdx,
                                      EdbProvider edb,
                                      Set<String> negFacts,
                                      Map<List<String>, List<List<String>>> out) {
        if (idx == body.size()) {
            // All atoms matched — extract head tuple from binding
            List<String> headTuple = applyBinding(rule.headArgs(), binding);
            if (headTuple != null) {
                // Accumulate ALL distinct derivations per head tuple (not first-wins).
                // Deduplication by exact parent-key-list is done here to avoid recording the same
                // route twice when the same binding fires in the seed phase and the delta loop.
                List<String> parents = List.copyOf(parentKeysAccum);
                out.computeIfAbsent(headTuple, k -> new ArrayList<>())
                   .add(parents);
            }
            return;
        }

        RuleAtom atom = body.get(idx);

        if (atom.negated()) {
            // Negation-as-failure: all variables must be bound
            List<String> groundArgs = applyBinding(atom.args(), binding);
            if (groundArgs == null) {
                // Unsafe: variables unbound in negated literal — skip this binding
                log.warn("RecursiveQueryEngine: unsafe negation in rule '{}' — "
                        + "variable unbound at negated atom {}", rule.headPredicate(), atom.predicate());
                return;
            }
            String factKey = buildAtomKey(atom.predicate(), groundArgs);
            // Check IDB-derived facts
            if (negFacts.contains(factKey)) return;
            // For EDB predicates (not in IDB), also check the EDB provider
            if (!idbPredicates.contains(atom.predicate())) {
                // Check if the ground tuple is in the EDB
                List<List<String>> edbTuples = edb.tuplesFor(atom.predicate());
                if (edbTuples != null && edbTuples.contains(groundArgs)) return;
            }
            // Fact not known → negation succeeds, continue (negated atoms are not parents)
            matchBodyRec(body, idx + 1, binding, parentKeysAccum, rule, idbPredicates,
                    idb, delta, deltaVariantIdx, edb, negFacts, out);
            return;
        }

        // Positive atom — determine candidate tuples
        List<List<String>> candidates;
        if (idbPredicates.contains(atom.predicate())) {
            Set<List<String>> source;
            if (idx == deltaVariantIdx && delta != null) {
                source = delta.getOrDefault(atom.predicate(), Set.of());
            } else {
                source = idb.getOrDefault(atom.predicate(), Set.of());
            }
            candidates = new ArrayList<>(source);
        } else {
            candidates = edb.tuplesFor(atom.predicate());
        }

        if (candidates == null || candidates.isEmpty()) return;

        // Build a PslAtom template for unification
        PslAtom template = buildTemplate(atom.predicate(), atom.args());

        for (List<String> tuple : candidates) {
            PslAtom ground = buildGround(atom.predicate(), tuple);
            Map<String, String> extended = JoinKernel.unify(template, ground, binding);
            if (extended == null) continue;
            // Extend parent key list with this atom's ground key
            String groundKey = buildAtomKey(atom.predicate(), tuple);
            List<String> newParents = new ArrayList<>(parentKeysAccum);
            newParents.add(groundKey);
            matchBodyRec(body, idx + 1, extended, newParents, rule, idbPredicates,
                    idb, delta, deltaVariantIdx, edb, negFacts, out);
        }
    }

    // ─── Stratification via Tarjan SCC ───────────────────────────────────────────

    /**
     * Partition the rule set into strata using Tarjan's SCC algorithm on the predicate
     * dependency graph.
     *
     * <p>Positive edges ({@code B → A}: B appears positively in a rule for A) constrain
     * {@code stratum(B) ≤ stratum(A)}.  Negative edges ({@code B →⁻ A}: {@code ¬B}
     * appears in a rule for A) require {@code stratum(B) &lt; stratum(A)}.  An SCC
     * containing a negative edge is not stratifiable; such predicates are treated as
     * {@code undefined} with a warning.</p>
     *
     * @param rules         all rules in the program
     * @param idbPredicates all IDB predicate names
     * @return list of strata, each a list of rules, in bottom-up order
     */
    private static List<List<DatalogRule>> stratify(List<DatalogRule> rules,
                                                     Set<String> idbPredicates) {
        // Collect all predicate names
        Set<String> allPreds = new LinkedHashSet<>(idbPredicates);
        for (DatalogRule r : rules) {
            for (RuleAtom a : r.body()) {
                allPreds.add(a.predicate());
            }
        }

        // Build adjacency lists: for each head predicate, list its positive/negative body deps
        // Edge: (bodyPred → headPred) because headPred depends on bodyPred
        Map<String, Set<String>> posEdges = new LinkedHashMap<>();   // bodyPred → heads that reference it positively
        Map<String, Set<String>> negEdges = new LinkedHashMap<>();   // bodyPred → heads that reference it negatively (¬)
        for (String p : allPreds) {
            posEdges.put(p, new LinkedHashSet<>());
            negEdges.put(p, new LinkedHashSet<>());
        }
        for (DatalogRule r : rules) {
            for (RuleAtom atom : r.body()) {
                if (atom.negated()) {
                    negEdges.computeIfAbsent(atom.predicate(), k -> new LinkedHashSet<>())
                            .add(r.headPredicate());
                } else {
                    posEdges.computeIfAbsent(atom.predicate(), k -> new LinkedHashSet<>())
                            .add(r.headPredicate());
                }
            }
        }

        // Tarjan SCC on all predicates (using positive + negative edges for reachability)
        List<String> predList = new ArrayList<>(allPreds);
        int n = predList.size();
        Map<String, Integer> index = new LinkedHashMap<>();
        Map<String, Integer> lowLink = new LinkedHashMap<>();
        Map<String, Boolean> onStack = new LinkedHashMap<>();
        for (String p : predList) {
            index.put(p, -1);
            lowLink.put(p, 0);
            onStack.put(p, false);
        }
        int[] counter = {0};
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        List<List<String>> sccs = new ArrayList<>();

        for (String p : predList) {
            if (index.get(p) < 0) {
                tarjanDfs(p, posEdges, negEdges, index, lowLink, onStack, stack, sccs, counter);
            }
        }

        // Tarjan emits SCCs in the order their DFS subtrees complete.  With edges directed
        // bodyPred → headPred (the "provides" direction), the FIRST SCC emitted is the
        // SINK (highest-stratum predicate — most derived, depends on everything below it).
        // We want to evaluate BOTTOM UP: lowest strata first.  Reversing the list
        // puts the base-level predicates first and derived predicates last.
        Collections.reverse(sccs);

        // Check for non-stratifiability: any SCC with an internal negative edge
        for (List<String> scc : sccs) {
            Set<String> sccSet = new HashSet<>(scc);
            for (String p : scc) {
                Set<String> negTargets = negEdges.getOrDefault(p, Set.of());
                for (String target : negTargets) {
                    if (sccSet.contains(target)) {
                        log.warn("RecursiveQueryEngine: program is not stratifiable — "
                                + "predicate '{}' has a negative dependency cycle through '{}'. "
                                + "Atoms in this SCC are treated as undefined (WFS).", p, target);
                    }
                }
            }
        }

        // Assign a stratum number to each predicate: index in the sccs list
        Map<String, Integer> stratumOf = new LinkedHashMap<>();
        for (int s = 0; s < sccs.size(); s++) {
            for (String p : sccs.get(s)) {
                stratumOf.put(p, s);
            }
        }

        // Determine the maximum stratum
        int maxStratum = 0;
        for (int s : stratumOf.values()) {
            if (s > maxStratum) maxStratum = s;
        }

        // Partition rules into strata by their head predicate's stratum
        List<List<DatalogRule>> stratifiedRules = new ArrayList<>();
        for (int s = 0; s <= maxStratum; s++) {
            stratifiedRules.add(new ArrayList<>());
        }
        for (DatalogRule r : rules) {
            int s = stratumOf.getOrDefault(r.headPredicate(), 0);
            stratifiedRules.get(s).add(r);
        }
        return stratifiedRules;
    }

    /**
     * Tarjan SCC DFS visit.
     */
    private static void tarjanDfs(String v,
                                   Map<String, Set<String>> posEdges,
                                   Map<String, Set<String>> negEdges,
                                   Map<String, Integer> index,
                                   Map<String, Integer> lowLink,
                                   Map<String, Boolean> onStack,
                                   java.util.Deque<String> stack,
                                   List<List<String>> sccs,
                                   int[] counter) {
        int idx = counter[0]++;
        index.put(v, idx);
        lowLink.put(v, idx);
        onStack.put(v, true);
        stack.push(v);

        // Combine positive and negative successors for reachability
        Set<String> successors = new LinkedHashSet<>();
        successors.addAll(posEdges.getOrDefault(v, Set.of()));
        successors.addAll(negEdges.getOrDefault(v, Set.of()));

        for (String w : successors) {
            if (!index.containsKey(w) || index.get(w) < 0) {
                tarjanDfs(w, posEdges, negEdges, index, lowLink, onStack, stack, sccs, counter);
                lowLink.put(v, Math.min(lowLink.get(v), lowLink.get(w)));
            } else if (Boolean.TRUE.equals(onStack.get(w))) {
                lowLink.put(v, Math.min(lowLink.get(v), index.get(w)));
            }
        }

        if (lowLink.get(v).equals(index.get(v))) {
            List<String> scc = new ArrayList<>();
            while (true) {
                String w = stack.pop();
                onStack.put(w, false);
                scc.add(w);
                if (w.equals(v)) break;
            }
            sccs.add(scc);
        }
    }

    // ─── Safety validation ───────────────────────────────────────────────────────

    /**
     * Validate the Datalog safety condition for a rule: every variable in a negated
     * literal must also appear in a positive literal in the same rule body.
     *
     * @param rule the rule to validate
     * @throws IllegalArgumentException if the rule is unsafe
     */
    private static void validateSafety(DatalogRule rule) {
        // Collect all variables bound in positive body atoms
        Set<String> positiveVars = new HashSet<>();
        for (RuleAtom atom : rule.body()) {
            if (!atom.negated()) {
                for (String arg : atom.args()) {
                    if (isVariable(arg)) positiveVars.add(canonicalVar(arg));
                }
            }
        }
        // Also add head args (they must be bound by positive body atoms — Datalog range restriction)
        for (String arg : rule.headArgs()) {
            if (isVariable(arg) && !positiveVars.contains(canonicalVar(arg))) {
                throw new IllegalArgumentException(
                        "Unsafe rule: head variable '" + arg + "' in rule '"
                                + rule.headPredicate() + "' is not bound by any positive body atom");
            }
        }
        // Check negated literals
        for (RuleAtom atom : rule.body()) {
            if (!atom.negated()) continue;
            for (String arg : atom.args()) {
                if (isVariable(arg) && !positiveVars.contains(canonicalVar(arg))) {
                    throw new IllegalArgumentException(
                            "Unsafe rule: variable '" + arg + "' in negated literal '"
                                    + atom.predicate() + "' in rule '" + rule.headPredicate()
                                    + "' is not bound by any positive body atom");
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Count total facts in a delta map. */
    private static int countFacts(Map<String, Set<List<String>>> delta) {
        int n = 0;
        for (Set<List<String>> s : delta.values()) n += s.size();
        return n;
    }

    /** Return true if all sets in the map are empty. */
    private static boolean allEmpty(Map<String, Set<List<String>>> delta) {
        for (Set<List<String>> s : delta.values()) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }

    /** Merge stratum-local IDB into the global IDB. */
    private static void mergeIntoGlobal(Map<String, Set<List<String>>> global,
                                         Map<String, Set<List<String>>> local) {
        for (Map.Entry<String, Set<List<String>>> entry : local.entrySet()) {
            global.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>())
                    .addAll(entry.getValue());
        }
    }

    /** Count positive body atoms that are IDB predicates. */
    private static int countPositiveIdbBodyAtoms(DatalogRule rule, Set<String> idbPredicates) {
        int count = 0;
        for (RuleAtom atom : rule.body()) {
            if (!atom.negated() && idbPredicates.contains(atom.predicate())) count++;
        }
        return count;
    }

    /**
     * Build the set of ground atom keys that should trigger negation-as-failure failure.
     * This includes all facts in the EDB (for EDB predicates) and the current IDB.
     */
    private static Set<String> buildNegationFacts(Map<String, Set<List<String>>> idb,
                                                   EdbProvider edb,
                                                   Set<String> idbPredicates) {
        // We don't enumerate all EDB predicates here because the EDB is open-ended.
        // Negation-as-failure for EDB predicates is checked inline in matchBodyRec.
        // This method returns the ground keys from IDB so that negated IDB predicates
        // can be checked efficiently.
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Set<List<String>>> entry : idb.entrySet()) {
            for (List<String> tuple : entry.getValue()) {
                keys.add(buildAtomKey(entry.getKey(), tuple));
            }
        }
        return keys;
    }

    /** Apply a variable binding to an argument list, returning a ground tuple, or null if any variable is unbound. */
    private static List<String> applyBinding(List<String> args, Map<String, String> binding) {
        List<String> result = new ArrayList<>(args.size());
        for (String arg : args) {
            if (isVariable(arg)) {
                String val = binding.get(canonicalVar(arg));
                if (val == null) return null;
                result.add(val);
            } else {
                result.add(arg);
            }
        }
        return result;
    }

    /** Build a ground atom key from predicate name and argument tuple. */
    private static String buildAtomKey(String pred, List<String> args) {
        if (args.isEmpty()) return pred;
        return pred + "(" + String.join(", ", args) + ")";
    }

    /**
     * Build a {@link PslAtom} template for unification from a rule atom's predicate and arg list.
     * Variables get {@code Term.var}; constants get {@code Term.con}.
     */
    private static PslAtom buildTemplate(String predicate, List<String> args) {
        List<Term> terms = new ArrayList<>(args.size());
        for (String a : args) {
            if (isVariable(a)) {
                terms.add(Term.var(canonicalVar(a)));
            } else {
                terms.add(Term.con(a));
            }
        }
        return new PslAtom(predicate, terms, false);
    }

    /**
     * Build a ground {@link PslAtom} from a predicate name and a ground tuple.
     */
    private static PslAtom buildGround(String predicate, List<String> tuple) {
        List<Term> terms = new ArrayList<>(tuple.size());
        for (String c : tuple) {
            terms.add(Term.con(c));
        }
        return new PslAtom(predicate, terms, false);
    }

    /** Whether an argument string denotes a variable (starts with {@code "?"}). */
    private static boolean isVariable(String arg) {
        return arg != null && arg.startsWith("?");
    }

    /** Strip the leading {@code "?"} from a variable name to get its canonical binding key. */
    private static String canonicalVar(String arg) {
        return (arg != null && arg.startsWith("?")) ? arg.substring(1) : arg;
    }
}
