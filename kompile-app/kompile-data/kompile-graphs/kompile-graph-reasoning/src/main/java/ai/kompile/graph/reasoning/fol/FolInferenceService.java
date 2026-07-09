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
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.bayesian.NoisyOrCpt;
import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslAtom;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.graph.reasoning.psl.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point for first-order-logic (FOL) inference over a {@link ReasoningGraph}.
 *
 * <h3>Design</h3>
 * <p>Given a {@link ReasoningGraph} and a {@link FolRuleSet}, this service:</p>
 * <ol>
 *   <li>Wraps the graph in a {@link ReasoningGraphKnowledgeBase}.</li>
 *   <li>Builds a {@link PslProgram} — populates {@code State(N)} targets and
 *       {@code Link(X,Y)} / {@code Type(N)} / {@code Prior(N)} observed atoms
 *       directly from the graph (reusing {@link GraphPslProgramBuilder} logic).</li>
 *   <li>Translates each {@link FolRule} into a {@link PslRule}: the antecedent and
 *       consequent {@link ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint}s are
 *       <em>grounded</em> against all entity pairs (or all typed entities when
 *       {@link FolRule#entityTypeScope()} is set) to emit concrete PSL body/head atoms.
 *       The rule's {@link FolRule#weight()} becomes the PSL rule weight.</li>
 *   <li>Runs {@link HlMrfMapInference#solve(PslProgram)} for MAP soft-truth inference.</li>
 *   <li>Translates the result back: PSL constants ({@code n0, n1, ...}) → entity ids.</li>
 * </ol>
 *
 * <h3>FOL → PSL translation strategy</h3>
 * <p>PSL rules operate on named predicates over constants.  The {@link FolRule} antecedent
 * and consequent are FOL constraints over a {@link KnowledgeBase}.  The translation
 * evaluates each constraint for every pair / triple of entities and emits a <em>grounded</em>
 * PSL rule of the form:</p>
 * <pre>
 *   weight: Sat_antecedent(n0, n1) -> Sat_consequent(n0, n1) ^2
 * </pre>
 * <p>where each {@code Sat_X} atom is a virtual "satisfaction" atom that is observed TRUE
 * (1.0) if the corresponding {@link ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint}
 * evaluates to true for those entity ids, and FALSE (0.0) otherwise.  This faithfully encodes
 * the hard constraint in soft-truth form.</p>
 *
 * <p>This is infra-free: no Spring, no JPA, no external dependencies beyond those already in
 * the module ({@code psl/}, {@code model/}, {@code mebn/logic/}).</p>
 */
public final class FolInferenceService {

    private static final Logger log = LoggerFactory.getLogger(FolInferenceService.class);

    /** Virtual PSL predicate representing rule antecedent satisfaction for a grounding. */
    static final String ANTECEDENT = "Ante";
    /** Virtual PSL predicate representing rule consequent satisfaction for a grounding. */
    static final String CONSEQUENT = "Cons";

    /**
     * Default maximum number of entity pairs to ground per rule application.
     *
     * <p>Pair-wise grounding is O(N²) in the number of entities. This cap prevents
     * combinatorial explosion on large graphs. When the cap is reached, a WARN is logged
     * and {@link FolInferenceResult#groundingTruncated()} returns {@code true}.</p>
     */
    public static final int DEFAULT_MAX_PAIRS_PER_RULE = 10_000;

    private final int maxPairsPerRule;

    /** Construct with the default pair cap ({@value #DEFAULT_MAX_PAIRS_PER_RULE}). */
    public FolInferenceService() {
        this(DEFAULT_MAX_PAIRS_PER_RULE);
    }

    /**
     * Construct with an explicit per-rule entity-pair cap.
     *
     * @param maxPairsPerRule maximum entity pairs to ground per rule; must be ≥ 1
     */
    public FolInferenceService(int maxPairsPerRule) {
        if (maxPairsPerRule < 1) {
            throw new IllegalArgumentException("maxPairsPerRule must be ≥ 1, got: " + maxPairsPerRule);
        }
        this.maxPairsPerRule = maxPairsPerRule;
    }

    // ─── Entry points ───────────────────────────────────────────────────────────

    /**
     * Run FOL inference: ground the weighted rules over the graph and return per-entity
     * soft-truth likelihoods.
     *
     * @param graph   the graph to reason over
     * @param ruleSet the weighted FOL program to apply
     * @return inference result with entity likelihoods in [0,1]
     */
    public FolInferenceResult infer(ReasoningGraph graph, FolRuleSet ruleSet) {
        long t0 = System.currentTimeMillis();
        log.info("FOL inference: graph={} entities, {} relations, ruleSet={}",
                graph.entityCount(), graph.relationCount(), ruleSet.name());

        PslRun run = runPsl(graph, ruleSet);

        // Translate PSL constants back to entity ids — State(n0) atoms give entity soft truths.
        Map<String, String> constantToEntityId = run.builder().constantToEntityId();
        Map<String, Double> entityLikelihoods = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : run.result().values().entrySet()) {
            String atomKey = e.getKey();
            if (atomKey.startsWith(GraphPslProgramBuilder.STATE + "(") && atomKey.endsWith(")")) {
                String constant = atomKey.substring(
                        GraphPslProgramBuilder.STATE.length() + 1, atomKey.length() - 1);
                String entityId = constantToEntityId.get(constant);
                if (entityId != null) {
                    entityLikelihoods.put(entityId, e.getValue());
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.info("FOL inference complete: {} entity likelihoods, converged={}, {}ms",
                entityLikelihoods.size(), run.result().converged(), elapsed);

        return new FolInferenceResult(
                entityLikelihoods, run.result(),
                ruleSet.name(), graph.entityCount(), graph.relationCount(), elapsed,
                run.groundingTruncated(), run.pairsConsidered());
    }

    /**
     * Run PSL inference and return its conclusions as probabilistic {@link InferredFact}s — one per
     * target atom, carrying the posterior soft-truth as the fact value/confidence and the activated
     * rules as provenance. This is the "facts come out at the end" surface: an inference run emits a
     * set of (atom, probability) facts ready to persist and materialize back into the graph.
     *
     * <p>Unlike the {@link #inferFacts(PslProgram)} overload, this path feeds the builder's
     * constant↔entity-id and constant↔label maps into {@link EntailmentEngine#entailFromPslResult}
     * so that synthetic PSL constants ({@code n0}, {@code n1}, …) are translated to human-readable
     * entity ids and labels in the returned facts.</p>
     */
    public List<InferredFact> inferFacts(ReasoningGraph graph, FolRuleSet ruleSet) {
        PslRun run = buildPslRun(graph, ruleSet);
        HlMrfMapInference.Result result = HlMrfMapInference.solve(run.program());
        String runId = UUID.randomUUID().toString();
        List<EntailmentRecord> records = EntailmentEngine.entailFromPslResult(
                run.program(), result, null, runId,
                run.builder().constantToEntityId(),
                run.builder().constantToLabel());
        List<InferredFact> facts = new ArrayList<>(records.size());
        for (EntailmentRecord record : records) {
            facts.add(InferredFact.fromEntailment(record, 1L));
        }
        log.info("FOL inference emitted {} probabilistic facts (run {})", facts.size(), runId);
        return facts;
    }

    /**
     * Emit probabilistic facts from an already-built PSL program — e.g. a learned-weight program from
     * {@code PslWeightLearningService.learnAndApply}, so the emitted facts carry the learned weights in
     * their provenance (readable via {@link InferredFact#ruleWeights()}).
     */
    public List<InferredFact> inferFacts(PslProgram program) {
        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
        String runId = UUID.randomUUID().toString();
        List<EntailmentRecord> records = EntailmentEngine.entailFromPslResult(program, result, null, runId);
        List<InferredFact> facts = new ArrayList<>(records.size());
        for (EntailmentRecord record : records) {
            facts.add(InferredFact.fromEntailment(record, 1L));
        }
        log.info("FOL inference emitted {} probabilistic facts (run {})", facts.size(), runId);
        return facts;
    }

    /**
     * As {@link #inferFacts(ReasoningGraph, FolRuleSet)} but also persists each fact into
     * {@code store}, which assigns the canonical monotonic version per atom key.
     */
    public List<InferredFact> inferFacts(ReasoningGraph graph, FolRuleSet ruleSet, InferredFactStore store) {
        List<InferredFact> facts = inferFacts(graph, ruleSet);
        for (InferredFact fact : facts) {
            store.store(fact);
        }
        return facts;
    }

    /**
     * Build the grounded PSL program from a graph + rules <em>without</em> running inference — e.g.
     * so weight learning can fit rule weights against labeled ground truth before inference.
     */
    public PslProgram buildProgram(ReasoningGraph graph, FolRuleSet ruleSet) {
        return buildPslRun(graph, ruleSet).program();
    }

    /** Build the grounded program + the constant↔id builder; does not solve. */
    private PslRun buildPslRun(ReasoningGraph graph, FolRuleSet ruleSet) {
        ReasoningGraphKnowledgeBase kb = new ReasoningGraphKnowledgeBase(graph);

        // Step 1: base PSL program (State targets + Link/Prior observations).
        GraphPslProgramBuilder baseBuilder = new GraphPslProgramBuilder()
                .includeDefaultRules(false); // we supply our own rules from the FolRuleSet
        PslProgram program = baseBuilder.build(graph);
        // Per-entity type atoms so type-scoped rules can ground.
        addTypeAtoms(program, graph, baseBuilder.entityIdToConstant());

        // Step 2: translate each FolRule into grounded PSL rules.
        boolean[] truncated = {false};
        int[] totalPairs = {0};
        for (FolRule rule : ruleSet.rules()) {
            int[] ruleResult = translateRule(rule, graph, kb, program, baseBuilder.entityIdToConstant());
            totalPairs[0] += ruleResult[0];
            if (ruleResult[1] > 0) truncated[0] = true;
        }
        // Fallback: empty rule set → default propagation.
        if (ruleSet.isEmpty()) {
            addDefaultPropagationRules(program);
        }
        return new PslRun(program, baseBuilder, null, truncated[0], totalPairs[0]);
    }

    /** Build the grounded PSL program from the graph + rules and run MAP inference once. */
    private PslRun runPsl(ReasoningGraph graph, FolRuleSet ruleSet) {
        PslRun built = buildPslRun(graph, ruleSet);
        return new PslRun(built.program(), built.builder(),
                HlMrfMapInference.solve(built.program()),
                built.groundingTruncated(), built.pairsConsidered());
    }

    /**
     * One PSL run: the grounded program, the builder (for id↔constant maps), the MAP result,
     * and truncation metadata from pair-wise grounding.
     */
    private record PslRun(PslProgram program, GraphPslProgramBuilder builder,
                          HlMrfMapInference.Result result,
                          boolean groundingTruncated, int pairsConsidered) {
    }

    // ─── Rule translation ────────────────────────────────────────────────────────

    /**
     * Translate a single {@link FolRule} into grounded PSL rules added to {@code program}.
     *
     * <p>For each ordered pair (or single entity when unary) of entities that satisfy the
     * entity-type scope, evaluate the antecedent and consequent constraints and emit:
     * <ul>
     *   <li>Observed {@code Ante_ruleName(nX,nY)} = 1 if antecedent holds, 0 if not.</li>
     *   <li>Observed {@code Cons_ruleName(nX,nY)} = 1 if consequent holds, 0 if not.</li>
     *   <li>A PSL rule: {@code w: Ante_ruleName(nX,nY) -> Cons_ruleName(nX,nY) ^{1|2}}</li>
     *   <li>Additionally, link the State of entities mentioned in the consequent to the
     *       consequent atom so the PSL energy flows back to the target atoms.</li>
     * </ul>
     *
     * <p>This explicit grounding mirrors what PSL's own grounding engine does — but since we
     * already have the graph in memory and the constraints are evaluated against a live KB,
     * we can do it directly without a separate ASP-style grounding step.</p>
     *
     * @return int[2]: [0] = groundings emitted, [1] = 1 if cap was hit (truncated), else 0
     */
    private int[] translateRule(FolRule rule, ReasoningGraph graph, ReasoningGraphKnowledgeBase kb,
                                 PslProgram program, Map<String, String> entityIdToConstant) {

        String antePrefix = ANTECEDENT + "_" + sanitize(rule.name());
        String consPrefix = CONSEQUENT + "_" + sanitize(rule.name());

        List<GraphEntity> scopedEntities = scopedEntities(rule, graph, kb);

        // Pair-wise grounding over the scoped entity set (X and Y bindings)
        // For unary rules (e.g. priors), we also iterate singletons (X = Y)
        PairBuildResult pairResult = buildPairs(scopedEntities);
        List<EntityPair> pairs = pairResult.pairs();
        boolean truncated = pairResult.truncated();

        if (truncated) {
            log.warn("FOL inference: rule '{}' grounding truncated at {} pairs — "
                    + "graph has {} entities ({}² = {} potential pairs). "
                    + "Some entity pairs were not grounded; increase maxPairsPerRule to cover them.",
                    rule.name(), maxPairsPerRule, scopedEntities.size(),
                    scopedEntities.size(), (long) scopedEntities.size() * scopedEntities.size());
        }

        int groundedCount = 0;
        for (EntityPair pair : pairs) {
            Map<String, String> bindings = new LinkedHashMap<>();
            // Bind canonical variable names: X → first entity, Y → second entity (or same for unary)
            bindings.put("X", pair.x().id());
            bindings.put("Y", pair.y().id());
            // Also bind single-entity variables for unary constraints
            bindings.put("N", pair.x().id());
            bindings.put("E", pair.x().id());

            String cx = entityIdToConstant.get(pair.x().id());
            String cy = entityIdToConstant.get(pair.y().id());
            if (cx == null || cy == null) continue;

            // Evaluate antecedent (if present)
            double anteValue = 1.0; // unconditional rules always fire
            if (rule.hasAntecedent()) {
                anteValue = rule.antecedent().evaluate(kb, bindings) ? 1.0 : 0.0;
            }

            // Evaluate consequent
            double consValue = rule.consequent().evaluate(kb, bindings) ? 1.0 : 0.0;

            // Observe both atoms
            observeAtom(program, antePrefix, anteValue, cx, cy);
            observeAtom(program, consPrefix, consValue, cx, cy);

            // Emit the PSL implication rule for this specific grounding
            List<PslAtom> body = List.of(groundAtom(antePrefix, cx, cy, false));
            List<PslAtom> head = List.of(groundAtom(consPrefix, cx, cy, false));
            program.addRule(PslRule.weighted(rule.weight(), rule.squared(), body, head));

            double supportWeight = finiteSupportWeight(rule.weight());

            // Connect consequent soft-truth to the entity's State target:
            // "if the consequent holds for entity cx, push State(cx) up"
            List<PslAtom> consBody = List.of(groundAtom(consPrefix, cx, cy, false));
            List<PslAtom> stateHead = List.of(PslAtom.ground(GraphPslProgramBuilder.STATE, cx));
            program.addRule(PslRule.weighted(supportWeight * 0.5, rule.squared(), consBody, stateHead));

            // And the abductive direction: if State(cx) is high, soften the cons obligation
            List<PslAtom> stateBody = List.of(PslAtom.ground(GraphPslProgramBuilder.STATE, cx));
            List<PslAtom> consHead2 = List.of(groundAtom(consPrefix, cx, cy, false));
            program.addRule(PslRule.weighted(supportWeight * 0.25, rule.squared(), stateBody, consHead2));

            groundedCount++;
        }

        log.debug("Rule '{}': {} groundings", rule.name(), groundedCount);
        return new int[]{groundedCount, truncated ? 1 : 0};
    }

    private static double finiteSupportWeight(double ruleWeight) {
        return ruleWeight == Double.POSITIVE_INFINITY
                ? HlMrfMapInference.DEFAULT_HARD_WEIGHT
                : ruleWeight;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Entities in scope for a rule (all if no type scope, filtered if scoped).
     *
     * <p>When a type scope is set, delegates to
     * {@link ReasoningGraphKnowledgeBase#getEntitiesOfTypeObjects(String)} which uses the
     * memoized type-index built once per KB instance — O(1) index lookup instead of an
     * O(n) linear scan that rebuilds {@code typeMemberships()} for every entity per rule.</p>
     */
    private List<GraphEntity> scopedEntities(FolRule rule, ReasoningGraph graph,
                                              ReasoningGraphKnowledgeBase kb) {
        String scope = rule.entityTypeScope();
        if (scope == null) {
            return new ArrayList<>(graph.entities());
        }
        // Fast path: use the memoized type→entity index on the KB (built once per run).
        return kb.getEntitiesOfTypeObjects(scope);
    }

    /**
     * Build entity pairs: for each entity as X, pair with each other entity as Y
     * (including X=Y for unary/self-referential rules).
     * Caps at {@link #maxPairsPerRule} pairs to avoid explosion on large graphs.
     *
     * @param entities the scoped entity set to pair
     * @return pair list plus a truncation flag
     */
    private PairBuildResult buildPairs(List<GraphEntity> entities) {
        List<EntityPair> pairs = new ArrayList<>();
        boolean truncated = false;
        outer:
        for (GraphEntity x : entities) {
            for (GraphEntity y : entities) {
                pairs.add(new EntityPair(x, y));
                if (pairs.size() >= maxPairsPerRule) {
                    truncated = true;
                    break outer;
                }
            }
        }
        return new PairBuildResult(pairs, truncated);
    }

    private record EntityPair(GraphEntity x, GraphEntity y) {}

    private record PairBuildResult(List<EntityPair> pairs, boolean truncated) {}

    /** Add {@code Type_TYPENAME(nX)} observed atoms for every entity. */
    private void addTypeAtoms(PslProgram program, ReasoningGraph graph,
                               Map<String, String> entityIdToConstant) {
        for (GraphEntity e : graph.entities()) {
            String c = entityIdToConstant.get(e.id());
            if (c == null) continue;
            for (String type : e.typeMemberships()) {
                if (type == null || type.isEmpty()) continue;
                String pred = "Type_" + sanitize(type);
                program.observe(pred, 1.0, c);
            }
        }
    }

    /** Default PSL rules used when the caller supplies an empty rule set. */
    private void addDefaultPropagationRules(PslProgram program) {
        double pw = 2.0;
        String S = GraphPslProgramBuilder.STATE;
        String L = GraphPslProgramBuilder.LINK;
        String P = GraphPslProgramBuilder.PRIOR;
        program.addRule(PslRule.parse(pw + ": " + S + "(X) & " + L + "(X, Y) -> " + S + "(Y) ^2"));
        program.addRule(PslRule.parse(pw + ": " + S + "(Y) & " + L + "(X, Y) -> " + S + "(X) ^2"));
        program.addRule(PslRule.parse("1.0: " + P + "(N) -> " + S + "(N) ^2"));
        program.addRule(PslRule.parse("1.0: " + S + "(N) -> " + P + "(N) ^2"));
    }

    /** Emit a PSL observe call for a unary or binary virtual atom. */
    private void observeAtom(PslProgram program, String predicate, double value,
                               String c1, String c2) {
        if (c1.equals(c2)) {
            program.observe(predicate, value, c1);
        } else {
            program.observe(predicate, value, c1, c2);
        }
    }

    /** Build a ground PSL atom for a unary or binary predicate. */
    private PslAtom groundAtom(String predicate, String c1, String c2, boolean negated) {
        if (c1.equals(c2)) {
            return PslAtom.of(predicate, negated, Term.con(c1));
        } else {
            return PslAtom.of(predicate, negated, Term.con(c1), Term.con(c2));
        }
    }

    /** Sanitize a rule name to be a valid PSL predicate fragment (alphanumeric/underscore). */
    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9]", "_");
    }
}
