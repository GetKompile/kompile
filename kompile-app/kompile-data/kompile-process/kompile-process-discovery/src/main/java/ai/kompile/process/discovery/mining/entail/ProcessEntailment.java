/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.entail;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.fol.EntailmentEngine;
import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.learning.PseudolikelihoodLearner;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.process.discovery.mining.causal.DependencyMeasures;
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.log.ActivityIntervals;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The process-entailment core: compiles what discovery observed (directly-follows statistics) and
 * what the declarative miner induced (Declare constraints) into one weighted PSL program over a
 * {@code Precedes} relation, and lets the project's HL-MRF engine settle it — including orderings
 * that were never directly observed (transitive entailment), the suppression of cycles
 * (antisymmetry) and of orderings between activities that never co-exist, all cross-examined
 * against the event log's valid time.
 *
 * <p>Rule weights are structural reliabilities; the per-pair evidence strength travels through the
 * observed atom values (directly-follows dependency, Declare confidence), each carrying the
 * subjective-logic {@link Opinion} reconstructed from its evidence counts. Inference runs through
 * {@link EntailmentEngine#entailFromPsl} so every conclusion arrives with its supporting facts and
 * activated ground rules — provenance, not just a number.
 *
 * <p>Pure and static like the rest of the mining package: no Spring, no store access, fully
 * unit-testable on hand-built logs.
 */
public final class ProcessEntailment {

    private static final Logger log = LoggerFactory.getLogger(ProcessEntailment.class);

    /**
     * Above this many activities the O(n³) transitive grounding is skipped (the result's
     * {@code transitivityApplied} says so — never a silent cap). 40³ ≈ 64k ground rules is
     * comfortably within what the ADMM/HL-MRF solver handles inline during discovery.
     */
    public static final int MAX_TRANSITIVITY_ACTIVITIES = 40;

    /** Min temporally-labeled pairs before rule-weight learning replaces the static weights. */
    public static final int MIN_WEIGHT_LEARNING_LABELS = 4;
    /** Pseudolikelihood epochs — small program, converges fast; deterministic (fixed seed). */
    static final int WEIGHT_LEARNING_EPOCHS = 50;

    private static final String PRECEDES = "Precedes";
    private static final String DF = "Df";
    private static final String RESP = "Resp";
    private static final String PREC = "Prec";
    private static final String CHAIN = "Chain";
    private static final String NCE = "Nce";

    private static final String CONTROLS = "Controls";
    private static final String VALIDATES = "Validates";
    private static final String APPROVED_BY = "ApprovedBy";
    private static final String REQUIRES_APPROVAL = "RequiresApproval";
    private static final String ESCALATES_TO = "EscalatesTo";
    private static final String ROUTED_BY = "RoutedBy";
    private static final String ACTION = "Action";
    private static final String HAS_THRESHOLD = "HasThreshold";
    private static final String APPROVAL_ACTION = "ApprovalAction";
    private static final String ESCALATION_ACTION = "EscalationAction";
    private static final String ROUTE_ACTION = "RouteAction";

    /** Matches the synthetic ground constants ({@code n0}, {@code n1}, …) for label back-translation. */
    private static final Pattern CONSTANT_TOKEN = Pattern.compile("\\bn\\d+\\b");

    private ProcessEntailment() {
    }

    /**
     * Default temporal-evidence half-life: a trace's vote in the valid-time cross-examination
     * decays to half weight when it is this many days older than the log's newest dated event.
     * Business processes drift over months; recent reversals should outvote stale confirmations.
     */
    public static final double DEFAULT_RECENCY_HALF_LIFE_DAYS = 180.0;

    /** Entail with the default temporal recency half-life ({@value #DEFAULT_RECENCY_HALF_LIFE_DAYS} days). */
    public static ProcessEntailmentResult entail(EventLog eventLog,
                                                 DirectlyFollowsGraph dfg,
                                                 List<DeclareConstraint> constraints) {
        return entail(eventLog, dfg, constraints, DEFAULT_RECENCY_HALF_LIFE_DAYS);
    }

    /** Entail with the default weight-learning floors ({@link #MIN_WEIGHT_LEARNING_LABELS}, {@link #WEIGHT_LEARNING_EPOCHS}). */
    public static ProcessEntailmentResult entail(EventLog eventLog,
                                                 DirectlyFollowsGraph dfg,
                                                 List<DeclareConstraint> constraints,
                                                 double recencyHalfLifeDays) {
        return entail(eventLog, dfg, constraints, recencyHalfLifeDays,
                MIN_WEIGHT_LEARNING_LABELS, WEIGHT_LEARNING_EPOCHS);
    }

    /** Entail with caller-supplied semantic atoms instead of auto-extracting them from event attributes. */
    public static ProcessEntailmentResult entail(EventLog eventLog,
                                                 DirectlyFollowsGraph dfg,
                                                 List<DeclareConstraint> constraints,
                                                 List<ProcessSemanticAtomExtractor.SemanticAtom> semanticAtoms) {
        return entail(eventLog, dfg, constraints, DEFAULT_RECENCY_HALF_LIFE_DAYS,
                MIN_WEIGHT_LEARNING_LABELS, WEIGHT_LEARNING_EPOCHS, semanticAtoms);
    }

    /**
     * Entail the precedence relation of a discovered process.
     *
     * @param eventLog            the event log discovery ran on (temporal cross-examination + trace count)
     * @param dfg                 the directly-follows graph built from that log
     * @param constraints         mined Declare constraints (may be empty — entailment then runs on the
     *                            directly-follows evidence alone)
     * @param recencyHalfLifeDays temporal votes decay to half weight per this many days of age
     *                            relative to the log's newest dated event ({@code <= 0} disables
     *                            decay — every dated trace votes with weight 1). Age is measured
     *                            inside the log, never against the wall clock, so re-running on the
     *                            same log is deterministic.
     * @param weightLearningMinLabels min temporally-labeled pairs before pseudolikelihood learning
     *                                replaces the static rule weights ({@code 0} disables learning)
     * @param weightLearningEpochs    pseudolikelihood epochs (deterministic, full batch)
     * @return the settled precedence relation with provenance; empty when there is nothing to entail
     */
    public static ProcessEntailmentResult entail(EventLog eventLog,
                                                 DirectlyFollowsGraph dfg,
                                                 List<DeclareConstraint> constraints,
                                                 double recencyHalfLifeDays,
                                                 int weightLearningMinLabels,
                                                 int weightLearningEpochs) {
        return entail(eventLog, dfg, constraints, recencyHalfLifeDays,
                weightLearningMinLabels, weightLearningEpochs,
                ProcessSemanticAtomExtractor.extract(eventLog));
    }

    /**
     * Entail the precedence relation while also consuming graph-derived semantic process atoms.
     * Semantic atoms are reusable graph/crawler facts such as controls/validates,
     * requires-approval, action, threshold, routing, and escalation.
     */
    public static ProcessEntailmentResult entail(EventLog eventLog,
                                                 DirectlyFollowsGraph dfg,
                                                 List<DeclareConstraint> constraints,
                                                 double recencyHalfLifeDays,
                                                 int weightLearningMinLabels,
                                                 int weightLearningEpochs,
                                                 List<ProcessSemanticAtomExtractor.SemanticAtom> semanticAtoms) {
        List<DeclareConstraint> binary = (constraints == null) ? List.of()
                : constraints.stream().filter(c -> !c.isUnary()).toList();
        List<ProcessSemanticAtomExtractor.SemanticAtom> semantic = semanticAtoms == null
                ? ProcessSemanticAtomExtractor.extract(eventLog)
                : List.copyOf(semanticAtoms);

        // Activity universe: DFG activities ∪ constraint activities ∪ semantic-atom activities.
        LinkedHashSet<String> activitySet = new LinkedHashSet<>(dfg.activities());
        for (DeclareConstraint c : binary) {
            activitySet.add(c.activityA());
            activitySet.add(c.activityB());
        }
        for (ProcessSemanticAtomExtractor.SemanticAtom atom : semantic) {
            if (hasText(atom.activity())) {
                activitySet.add(atom.activity().trim());
            }
        }
        if (activitySet.size() < 2) {
            return ProcessEntailmentResult.empty();
        }
        List<String> activities = new ArrayList<>(activitySet);
        Map<String, String> toConst = new LinkedHashMap<>();
        Map<String, String> fromConst = new LinkedHashMap<>();
        for (String activity : activities) {
            constantFor(activity, toConst, fromConst);
        }

        // Directed order seeds: observed arcs + order-implying constraints. NCE pairs are targets
        // (so suppression is visible) but never seeds — co-absence must not extend reachability.
        LinkedHashSet<List<String>> orderSeeds = new LinkedHashSet<>();
        for (DirectlyFollowsGraph.Arc arc : dfg.arcs().keySet()) {
            orderSeeds.add(List.of(arc.from(), arc.to()));
        }
        LinkedHashSet<List<String>> ncePairs = new LinkedHashSet<>();
        for (DeclareConstraint c : binary) {
            switch (c.template()) {
                case RESPONSE, CHAIN_RESPONSE, PRECEDENCE ->
                        orderSeeds.add(List.of(c.activityA(), c.activityB()));
                case NOT_CO_EXISTENCE -> {
                    ncePairs.add(List.of(c.activityA(), c.activityB()));
                    ncePairs.add(List.of(c.activityB(), c.activityA()));
                }
                default -> {
                }
            }
        }
        SemanticEvidence semanticEvidence = semanticEvidence(semantic, activitySet, toConst, fromConst);
        orderSeeds.addAll(semanticEvidence.orderSeeds());
        if (orderSeeds.isEmpty() && ncePairs.isEmpty()) {
            return ProcessEntailmentResult.empty();
        }

        boolean transitivity = activities.size() <= MAX_TRANSITIVITY_ACTIVITIES;
        if (!transitivity) {
            log.info("Process entailment: {} activities > {} — transitive rule skipped",
                    activities.size(), MAX_TRANSITIVITY_ACTIVITIES);
        }

        // Targets: the reachability closure of the order seeds (what transitivity could conclude),
        // every seed's reverse (so antisymmetry has a visible effect), and the NCE pairs.
        LinkedHashSet<List<String>> targets = transitivity
                ? transitiveClosure(orderSeeds)
                : new LinkedHashSet<>(orderSeeds);
        for (List<String> seed : new ArrayList<>(targets)) {
            targets.add(List.of(seed.get(1), seed.get(0)));
        }
        targets.addAll(ncePairs);

        // Observed facts, each carrying the Opinion reconstructed from its evidence counts.
        FactStore facts = new FactStore();
        Instant now = Instant.now();
        for (DirectlyFollowsGraph.Arc arc : dfg.arcs().keySet()) {
            double strength = clamp01(DependencyMeasures.dependency(dfg, arc.from(), arc.to()));
            if (strength <= 0.0) {
                continue;
            }
            long ab = dfg.follows(arc.from(), arc.to());
            long ba = dfg.follows(arc.to(), arc.from());
            facts.assertFact(new Fact(
                    atom(DF, toConst.get(arc.from()), toConst.get(arc.to())),
                    strength, "dfg", now, false,
                    Opinion.fromBetaEvidence(ab, ba)));
        }
        int traceCount = Math.max(1, eventLog.size());
        for (DeclareConstraint c : binary) {
            String pred = switch (c.template()) {
                case RESPONSE -> RESP;
                case PRECEDENCE -> PREC;
                case CHAIN_RESPONSE -> CHAIN;
                case NOT_CO_EXISTENCE -> NCE;
                default -> null;
            };
            if (pred == null) {
                continue;
            }
            long satisfied = Math.round(c.support() * traceCount);
            long activated = c.confidence() > 0 ? Math.round(satisfied / c.confidence()) : 0;
            facts.assertFact(new Fact(
                    atom(pred, toConst.get(c.activityA()), toConst.get(c.activityB())),
                    clamp01(c.confidence()), "declare", now, false,
                    Opinion.fromBetaEvidence(satisfied, Math.max(0, activated - satisfied))));
        }
        for (SemanticFact fact : semanticEvidence.facts()) {
            double confidence = clamp01(fact.confidence());
            facts.assertFact(new Fact(
                    fact.atomKey(), confidence, "process-semantic", now, false,
                    Opinion.fromBayesianPosterior(confidence, 0.5)));
        }

        PslProgram program = new PslProgram();
        for (List<String> pair : targets) {
            program.target(PRECEDES, toConst.get(pair.get(0)), toConst.get(pair.get(1)));
        }
        List<String> rules = buildRules(transitivity);
        for (String rule : rules) {
            program.addRule(rule);
        }

        // Temporal-labeled rule-weight learning: the valid-time cross-examination is per-pair
        // ground truth this log actually contains — pairs with dated votes get a label of their
        // recency-weighted ordered share, and pseudolikelihood learning fits the structural rule
        // weights (Df/Resp/Prec/Chain reliability, antisymmetry, transitivity) to it. The static
        // weights remain the prior; learning only runs with enough labeled pairs to matter and
        // never fails the mine. Deterministic (fixed seed, full batch).
        LocalDateTime newestEvent = newestDatedEvent(eventLog);
        Map<String, Map<String, ActivityIntervals.Interval>> intervalsByCase =
                ActivityIntervals.ofLog(eventLog.traces());
        Map<List<String>, TemporalVotes> votesByPair = new LinkedHashMap<>();
        for (List<String> pair : targets) {
            votesByPair.put(pair, temporalEvidence(intervalsByCase, eventLog, pair.get(0), pair.get(1),
                    newestEvent, recencyHalfLifeDays));
        }
        Map<String, Double> temporalLabels = new LinkedHashMap<>();
        for (Map.Entry<List<String>, TemporalVotes> entry : votesByPair.entrySet()) {
            TemporalVotes v = entry.getValue();
            double weightedTotal = v.weightedOrdered() + v.weightedReversed();
            if (v.ordered() + v.reversed() > 0 && weightedTotal > 0) {
                temporalLabels.put(
                        atom(PRECEDES, toConst.get(entry.getKey().get(0)), toConst.get(entry.getKey().get(1))),
                        v.weightedOrdered() / weightedTotal);
            }
        }
        if (weightLearningMinLabels > 0 && temporalLabels.size() >= weightLearningMinLabels) {
            try {
                List<PslRule> learned =
                        new PseudolikelihoodLearner()
                                .learn(program, temporalLabels, weightLearningEpochs);
                program = program.withRules(learned);
                rules = learned.stream().map(Object::toString).toList();
                log.debug("Process entailment: learned {} rule weights from {} temporal labels",
                        learned.size(), temporalLabels.size());
            } catch (Exception e) {
                log.warn("Process entailment: weight learning failed ({}) — static weights stand",
                        e.getMessage());
            }
        }

        List<EntailmentRecord> records = EntailmentEngine.entailFromPsl(program, facts);
        Map<String, EntailmentRecord> byKey = new HashMap<>();
        for (EntailmentRecord r : records) {
            byKey.put(r.groundedRvOrAtomKey(), r);
        }
        String runId = records.isEmpty() ? UUID.randomUUID().toString() : records.get(0).inferenceRunId();

        List<ProcessEntailmentResult.EntailedPrecedence> out = new ArrayList<>();
        for (List<String> pair : targets) {
            String from = pair.get(0);
            String to = pair.get(1);
            String rawKey = atom(PRECEDES, toConst.get(from), toConst.get(to));
            EntailmentRecord rec = byKey.get(rawKey);
            if (rec == null) {
                continue;
            }
            TemporalVotes votes = votesByPair.get(pair);
            // Raw counts stay on the record for display ("temporal +4/−1 ~2"); the OPINION and the
            // verdicts use the recency-weighted sums, so a recent reversal outvotes stale
            // confirmations (the process changed) instead of being averaged away. Overlap votes
            // abstain from direction; when they outweigh both directions combined the pair is
            // CONCURRENT — assertable() then excludes it like a refuted one.
            Opinion temporalOpinion = (votes.ordered + votes.reversed) > 0
                    ? Opinion.fromBetaEvidence(votes.weightedOrdered, votes.weightedReversed)
                    : null;
            boolean refuted = votes.weightedReversed > votes.weightedOrdered;
            boolean concurrent = votes.weightedOverlapped
                    > (votes.weightedOrdered + votes.weightedReversed);
            out.add(new ProcessEntailmentResult.EntailedPrecedence(
                    from, to, rec.posterior(),
                    dfg.hasArc(from, to),
                    refuted, votes.ordered, votes.reversed, votes.overlapped,
                    concurrent && votes.overlapped > 0, temporalOpinion,
                    translate(rec.supportingFindingKeys(), fromConst),
                    translate(rec.activatedRules(), fromConst),
                    rawKey));
        }
        out.sort(Comparator.comparingDouble(ProcessEntailmentResult.EntailedPrecedence::posterior).reversed());

        log.info("Process entailment: {} activities, {} targets, {} rules → {} pairs ({} entailed-only, run={})",
                activities.size(), targets.size(), rules.size(), out.size(),
                out.stream().filter(p -> p.posterior() >= 0.5 && !p.observed()).count(), runId);
        return new ProcessEntailmentResult(out, rules, transitivity, runId);
    }

    /** The weighted-logic program: structural rule reliabilities; evidence strength rides the atoms. */
    private static List<String> buildRules(boolean transitivity) {
        List<String> rules = new ArrayList<>(List.of(
                "1.0: " + DF + "(A, B) -> " + PRECEDES + "(A, B) ^2",
                "1.0: " + RESP + "(A, B) -> " + PRECEDES + "(A, B) ^2",
                "1.0: " + PREC + "(A, B) -> " + PRECEDES + "(A, B) ^2",
                "1.2: " + CHAIN + "(A, B) -> " + PRECEDES + "(A, B) ^2",
                "1.5: " + PRECEDES + "(A, B) -> !" + PRECEDES + "(B, A) ^2",
                "1.0: " + NCE + "(A, B) -> !" + PRECEDES + "(A, B) ^2",
                "1.0: " + NCE + "(A, B) -> !" + PRECEDES + "(B, A) ^2",
                "0.9: " + CONTROLS + "(C, A) & " + VALIDATES + "(C, B) & (A != B) -> " + PRECEDES + "(A, B) ^2",
                "0.6: " + REQUIRES_APPROVAL + "(A, P) & " + APPROVAL_ACTION + "(B) & (A != B) -> " + PRECEDES + "(A, B) ^2",
                "0.5: " + HAS_THRESHOLD + "(A) & " + ESCALATION_ACTION + "(B) & (A != B) -> " + PRECEDES + "(A, B) ^2",
                "0.4: " + ROUTED_BY + "(A, P) & " + ROUTE_ACTION + "(B) & (A != B) -> " + PRECEDES + "(A, B) ^2"));
        if (transitivity) {
            rules.add("0.8: " + PRECEDES + "(A, B) & " + PRECEDES + "(B, C) & (A != C) -> "
                    + PRECEDES + "(A, C) ^2");
        }
        return rules;
    }

    /** All ordered pairs (x,y), x≠y, where y is reachable from x over the seed arcs (BFS per source). */
    private static LinkedHashSet<List<String>> transitiveClosure(Set<List<String>> seeds) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (List<String> seed : seeds) {
            adjacency.computeIfAbsent(seed.get(0), k -> new LinkedHashSet<>()).add(seed.get(1));
        }
        LinkedHashSet<List<String>> closure = new LinkedHashSet<>(seeds);
        for (String source : adjacency.keySet()) {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> frontier = new ArrayDeque<>(adjacency.get(source));
            while (!frontier.isEmpty()) {
                String next = frontier.poll();
                if (!visited.add(next)) {
                    continue;
                }
                if (!next.equals(source)) {
                    closure.add(List.of(source, next));
                }
                frontier.addAll(adjacency.getOrDefault(next, Set.of()));
            }
        }
        return closure;
    }

    private record SemanticEvidence(List<SemanticFact> facts, LinkedHashSet<List<String>> orderSeeds) {
    }

    private record SemanticFact(String atomKey, double confidence) {
    }

    private static SemanticEvidence semanticEvidence(List<ProcessSemanticAtomExtractor.SemanticAtom> atoms,
                                                     Set<String> activities,
                                                     Map<String, String> toConst,
                                                     Map<String, String> fromConst) {
        List<SemanticFact> facts = new ArrayList<>();
        LinkedHashSet<List<String>> seeds = new LinkedHashSet<>();
        Map<String, Set<String>> controlsByControl = new LinkedHashMap<>();
        Map<String, Set<String>> validationsByControl = new LinkedHashMap<>();
        Set<String> requiresApproval = new LinkedHashSet<>();
        Set<String> approvalActions = new LinkedHashSet<>();
        Set<String> thresholdActivities = new LinkedHashSet<>();
        Set<String> escalationActions = new LinkedHashSet<>();
        Set<String> routedActivities = new LinkedHashSet<>();
        Set<String> routeActions = new LinkedHashSet<>();

        for (String activity : activities) {
            if (looksLikeApproval(activity)) {
                approvalActions.add(activity);
            }
            if (looksLikeEscalation(activity)) {
                escalationActions.add(activity);
            }
            if (looksLikeRoute(activity)) {
                routeActions.add(activity);
            }
        }

        for (ProcessSemanticAtomExtractor.SemanticAtom atom : atoms) {
            if (atom == null || !hasText(atom.activity()) || !activities.contains(atom.activity().trim())) {
                continue;
            }
            String activity = atom.activity().trim();
            String activityConst = constantFor(activity, toConst, fromConst);
            double confidence = clamp01(atom.confidence());
            String type = normalizeType(atom.type());
            switch (type) {
                case "CONTROL" -> {
                    String control = atom.object();
                    String controlConst = constantFor(control, toConst, fromConst);
                    if (controlConst != null) {
                        facts.add(new SemanticFact(atom(CONTROLS, controlConst, activityConst), confidence));
                        controlsByControl.computeIfAbsent(control.trim(), k -> new LinkedHashSet<>()).add(activity);
                    }
                }
                case "VALIDATES" -> {
                    String control = atom.object();
                    String controlConst = constantFor(control, toConst, fromConst);
                    if (controlConst != null) {
                        facts.add(new SemanticFact(atom(VALIDATES, controlConst, activityConst), confidence));
                        validationsByControl.computeIfAbsent(control.trim(), k -> new LinkedHashSet<>()).add(activity);
                    }
                }
                case "APPROVED_BY" -> {
                    String approverConst = constantFor(atom.object(), toConst, fromConst);
                    if (approverConst != null) {
                        facts.add(new SemanticFact(atom(APPROVED_BY, activityConst, approverConst), confidence));
                    }
                    if (looksLikeApproval(activity)) {
                        approvalActions.add(activity);
                        facts.add(new SemanticFact(atom(APPROVAL_ACTION, activityConst), confidence));
                    }
                }
                case "REQUIRES_APPROVAL" -> {
                    String policyConst = constantFor(atom.object(), toConst, fromConst);
                    if (policyConst != null) {
                        facts.add(new SemanticFact(atom(REQUIRES_APPROVAL, activityConst, policyConst), confidence));
                        requiresApproval.add(activity);
                    }
                }
                case "ESCALATES_TO" -> {
                    String targetConst = constantFor(atom.object(), toConst, fromConst);
                    if (targetConst != null) {
                        facts.add(new SemanticFact(atom(ESCALATES_TO, activityConst, targetConst), confidence));
                    }
                    if (looksLikeEscalation(activity)) {
                        escalationActions.add(activity);
                        facts.add(new SemanticFact(atom(ESCALATION_ACTION, activityConst), confidence));
                    }
                }
                case "ROUTED_BY" -> {
                    String policyConst = constantFor(atom.object(), toConst, fromConst);
                    if (policyConst != null) {
                        facts.add(new SemanticFact(atom(ROUTED_BY, activityConst, policyConst), confidence));
                        routedActivities.add(activity);
                    }
                }
                case "ACTION" -> {
                    String actionConst = constantFor(atom.object(), toConst, fromConst);
                    if (actionConst != null) {
                        facts.add(new SemanticFact(atom(ACTION, activityConst, actionConst), confidence));
                    }
                    if (looksLikeApproval(atom.object()) || looksLikeApproval(activity)) {
                        approvalActions.add(activity);
                        facts.add(new SemanticFact(atom(APPROVAL_ACTION, activityConst), confidence));
                    }
                    if (looksLikeEscalation(atom.object()) || looksLikeEscalation(activity)) {
                        escalationActions.add(activity);
                        facts.add(new SemanticFact(atom(ESCALATION_ACTION, activityConst), confidence));
                    }
                    if (looksLikeRoute(atom.object()) || looksLikeRoute(activity)) {
                        routeActions.add(activity);
                        facts.add(new SemanticFact(atom(ROUTE_ACTION, activityConst), confidence));
                    }
                }
                case "THRESHOLD" -> {
                    thresholdActivities.add(activity);
                    facts.add(new SemanticFact(atom(HAS_THRESHOLD, activityConst), confidence));
                }
                case "ROLE" -> {
                    String roleConst = constantFor(atom.object(), toConst, fromConst);
                    if (roleConst != null) {
                        facts.add(new SemanticFact(atom("HasRole", activityConst, roleConst), confidence));
                    }
                }
                default -> {
                }
            }
        }

        for (Map.Entry<String, Set<String>> entry : controlsByControl.entrySet()) {
            Set<String> validations = validationsByControl.getOrDefault(entry.getKey(), Set.of());
            for (String controlled : entry.getValue()) {
                for (String validated : validations) {
                    addSeed(seeds, controlled, validated);
                }
            }
        }
        for (String activity : requiresApproval) {
            for (String approval : approvalActions) {
                addSeed(seeds, activity, approval);
            }
        }
        for (String activity : thresholdActivities) {
            for (String escalation : escalationActions) {
                addSeed(seeds, activity, escalation);
            }
        }
        for (String activity : routedActivities) {
            for (String route : routeActions) {
                addSeed(seeds, activity, route);
            }
        }
        return new SemanticEvidence(List.copyOf(facts), seeds);
    }

    private static void addSeed(LinkedHashSet<List<String>> seeds, String from, String to) {
        if (hasText(from) && hasText(to) && !from.equals(to)) {
            seeds.add(List.of(from, to));
        }
    }

    /** Raw per-trace vote counts plus their recency-weighted sums. */
    private record TemporalVotes(long ordered, long reversed, long overlapped,
                                 double weightedOrdered, double weightedReversed,
                                 double weightedOverlapped) {
    }

    /**
     * Valid-time cross-examination of one ordering over ACTIVITY INTERVALS
     * ({@link ActivityIntervals}: [earliest, latest] dated event per activity per trace, extended
     * by crawl-lifted end-time attributes). Allen classification per trace: BEFORE/MEETS →
     * ordered, inverses → reversed, any intersection (OVERLAPS/STARTS/DURING/FINISHES/EQUAL) →
     * overlap — evidence of concurrency, abstaining from direction. Traces not dating both
     * activities abstain entirely. Single-event activities degenerate to points, where this
     * reduces exactly to the prior first-occurrence semantics. Each vote accumulates with an
     * exponential recency weight — half weight per {@code halfLifeDays} of age relative to the
     * log's newest dated event; a trace's age is its own vote time (the later interval end).
     */
    private static TemporalVotes temporalEvidence(Map<String, Map<String, ActivityIntervals.Interval>> intervalsByCase,
                                                  EventLog eventLog, String a, String b,
                                                  LocalDateTime newestEvent, double halfLifeDays) {
        long ordered = 0;
        long reversed = 0;
        long overlapped = 0;
        double weightedOrdered = 0;
        double weightedReversed = 0;
        double weightedOverlapped = 0;
        for (Trace t : eventLog.traces()) {
            Map<String, ActivityIntervals.Interval> intervals =
                    intervalsByCase.getOrDefault(t.caseId(), Map.of());
            ActivityIntervals.Interval ia = intervals.get(a);
            ActivityIntervals.Interval ib = intervals.get(b);
            if (ia == null || ib == null) {
                continue;
            }
            LocalDateTime voteTime = ia.end().isAfter(ib.end()) ? ia.end() : ib.end();
            double weight = recencyWeight(voteTime, newestEvent, halfLifeDays);
            switch (ia.orderVs(ib)) {
                case ORDERED -> {
                    ordered++;
                    weightedOrdered += weight;
                }
                case REVERSED -> {
                    reversed++;
                    weightedReversed += weight;
                }
                case OVERLAP -> {
                    overlapped++;
                    weightedOverlapped += weight;
                }
                case UNKNOWN -> { /* abstain */ }
            }
        }
        return new TemporalVotes(ordered, reversed, overlapped,
                weightedOrdered, weightedReversed, weightedOverlapped);
    }

    /** Exponential decay: 0.5^(ageDays/halfLife); 1.0 when decay is disabled or times are missing. */
    private static double recencyWeight(LocalDateTime voteTime, LocalDateTime newestEvent,
                                        double halfLifeDays) {
        if (halfLifeDays <= 0 || voteTime == null || newestEvent == null
                || !voteTime.isBefore(newestEvent)) {
            return 1.0;
        }
        double ageDays = Duration.between(voteTime, newestEvent).toMinutes() / (60.0 * 24.0);
        return Math.pow(0.5, ageDays / halfLifeDays);
    }

    /** The log's most recent dated event — the deterministic reference point for recency decay. */
    private static LocalDateTime newestDatedEvent(EventLog eventLog) {
        LocalDateTime newest = null;
        for (Trace t : eventLog.traces()) {
            for (Event e : t.ordered()) {
                if (e.timestamp() != null && (newest == null || e.timestamp().isAfter(newest))) {
                    newest = e.timestamp();
                }
            }
        }
        return newest;
    }

    /** Inline the activity display labels over the synthetic constants in provenance strings. */
    private static List<String> translate(List<String> texts, Map<String, String> fromConst) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(texts.size());
        for (String s : texts) {
            Matcher m = CONSTANT_TOKEN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        fromConst.getOrDefault(m.group(), m.group())));
            }
            m.appendTail(sb);
            out.add(sb.toString());
        }
        return out;
    }

    private static String constantFor(String label, Map<String, String> toConst, Map<String, String> fromConst) {
        if (!hasText(label)) {
            return null;
        }
        String raw = label.trim();
        String key = ProcessAtoms.sanitize(raw).trim();
        if (key.isBlank()) {
            return null;
        }
        String existing = toConst.get(raw);
        if (existing != null) {
            return existing;
        }
        existing = toConst.get(key);
        if (existing != null) {
            toConst.put(raw, existing);
            return existing;
        }
        String c = "n" + fromConst.size(); // lowercase => ground constant; n<i> is the lib's constant convention
        toConst.put(raw, c);
        toConst.put(key, c);
        fromConst.put(c, key);
        return c;
    }

    private static String normalizeType(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static boolean looksLikeApproval(String value) {
        return containsNormalized(value, "approv", "sign off", "signoff", "authorize", "authorise");
    }

    private static boolean looksLikeEscalation(String value) {
        return containsNormalized(value, "escalat", "triage", "exception", "reject", "rework");
    }

    private static boolean looksLikeRoute(String value) {
        return containsNormalized(value, "route", "assign", "dispatch", "handoff", "hand off");
    }

    private static boolean containsNormalized(String value, String... fragments) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        for (String fragment : fragments) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Canonical atom key, matching {@code PslAtom.key()}: {@code Pred(a, b)}. */
    private static String atom(String predicate, String... args) {
        return predicate + "(" + String.join(", ", args) + ")";
    }

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}
