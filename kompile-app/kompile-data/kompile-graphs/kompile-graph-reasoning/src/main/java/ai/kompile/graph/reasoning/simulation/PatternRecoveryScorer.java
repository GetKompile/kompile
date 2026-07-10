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
package ai.kompile.graph.reasoning.simulation;

import ai.kompile.graph.reasoning.simulation.GroundTruthManifest.ExpectedEdge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic scoring of what the reasoning stack recovered against what a scenario planted —
 * no LLM judging. The runner translates store state back into scenario-key space (via its
 * scenario-key → node-id map) and this class does pure set math:
 *
 * <ul>
 *   <li><b>inferred-edges</b> / <b>causal-links</b> — triple matching (normalized, symmetric-aware)
 *       → precision/recall/F1 per family.</li>
 *   <li><b>resolutions</b> — pairwise cluster comparison of merge groups vs planted duplicate
 *       sets (standard pairwise P/R, so partial merges earn partial recall and false merges cost
 *       precision).</li>
 *   <li><b>communities</b> — pairwise co-membership over the planted community universe.</li>
 *   <li><b>calibration</b> — reliability buckets + expected calibration error (ECE) over the
 *       recovered edges' confidences, per the grounding-evaluation design.</li>
 * </ul>
 *
 * <p>Fairness rules: recovered edges matching a planted NOISE edge are ignored (the noise was
 * fed in, echoing it is the extractor pipeline's problem, not the reasoner's); recovered edges
 * whose relation type belongs to no scored family are reported as {@code unanticipatedEdges}
 * but not counted as false positives (engines legitimately derive things a scenario didn't
 * anticipate, e.g. ontology closure in rule-world); recovering a
 * {@link GroundTruthManifest#forbiddenCausalLinks() forbidden} causal link IS a counted error.</p>
 */
public final class PatternRecoveryScorer {

    public static final String FAMILY_INFERRED_EDGES = "inferred-edges";
    public static final String FAMILY_RESOLUTIONS = "resolutions";
    public static final String FAMILY_COMMUNITIES = "communities";
    public static final String FAMILY_CAUSAL_LINKS = "causal-links";

    private PatternRecoveryScorer() {
    }

    /** One recovered relation, already mapped back into scenario-key space. */
    public record RecoveredEdge(String sourceKey, String relationType, String targetKey, double confidence) {}

    /**
     * Store state after reasoning, mapped into scenario-key space by the runner.
     *
     * @param inferredEdges   INFERRED-provenance edges between scenario entities
     * @param resolvedGroups  merge / RESOLVES_TO clusters (each set = keys the store now treats
     *                        as one entity); singletons are meaningless and may be omitted
     * @param communityByNode community assignment per scenario key (nodes without an assignment
     *                        simply absent)
     */
    public record RecoveredState(
            List<RecoveredEdge> inferredEdges,
            List<Set<String>> resolvedGroups,
            Map<String, String> communityByNode) {

        public RecoveredState {
            inferredEdges = (inferredEdges == null) ? List.of() : List.copyOf(inferredEdges);
            resolvedGroups = (resolvedGroups == null) ? List.of() : List.copyOf(resolvedGroups);
            communityByNode = (communityByNode == null) ? Map.of() : Map.copyOf(communityByNode);
        }

        public static RecoveredState empty() {
            return new RecoveredState(List.of(), List.of(), Map.of());
        }
    }

    /** Precision/recall for one pattern family. Families with no planted truth are omitted. */
    public record FamilyScore(String family, int truthCount, int recoveredCount, int truePositives,
                              double precision, double recall, double f1) {}

    /** One reliability bucket: mean confidence vs empirical accuracy of recovered edges. */
    public record CalibrationBucket(double lo, double hi, int count, double meanConfidence, double accuracy) {}

    /**
     * Full score for one run.
     *
     * @param families            per-family precision/recall (only families the scenario planted)
     * @param macroF1             mean F1 across scored families
     * @param calibration         reliability buckets over recovered edge confidences
     * @param ece                 expected calibration error (0 = perfectly calibrated)
     * @param forbiddenViolations recovered causal links the manifest explicitly forbids
     * @param hallucinatedEdges   recovered edges in a scored family that match no planted truth
     * @param unanticipatedEdges  recovered edges outside every scored family (informational)
     */
    public record ScoreReport(List<FamilyScore> families, double macroF1,
                              List<CalibrationBucket> calibration, double ece,
                              int forbiddenViolations,
                              List<RecoveredEdge> hallucinatedEdges,
                              List<RecoveredEdge> unanticipatedEdges) {

        public static ScoreReport empty() {
            return new ScoreReport(List.of(), 0.0, List.of(), 0.0, 0, List.of(), List.of());
        }
    }

    /** Score the recovered state against the manifest. */
    public static ScoreReport score(GroundTruthManifest truth, RecoveredState recovered) {
        List<FamilyScore> families = new ArrayList<>();
        List<RecoveredEdge> hallucinated = new ArrayList<>();
        List<RecoveredEdge> unanticipated = new ArrayList<>();
        List<double[]> confidenceAndCorrect = new ArrayList<>();   // [confidence, correct?1:0]

        // Dedup recovered edges by normalized triple, keeping the highest confidence.
        Map<String, RecoveredEdge> recoveredByKey = new LinkedHashMap<>();
        for (RecoveredEdge e : recovered.inferredEdges()) {
            String key = GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey());
            if (truth.corruptedEdgeKeys().contains(key)) continue;   // echoed planted noise: ignore
            RecoveredEdge prior = recoveredByKey.get(key);
            if (prior == null || e.confidence() > prior.confidence()) {
                recoveredByKey.put(key, e);
            }
        }

        // ── Edge families ────────────────────────────────────────────────────────────────
        Set<String> claimed = new HashSet<>();
        scoreEdgeFamily(FAMILY_INFERRED_EDGES, truth.expectedInferredEdges(), recoveredByKey,
                claimed, families, hallucinated, confidenceAndCorrect);
        scoreEdgeFamily(FAMILY_CAUSAL_LINKS, truth.expectedCausalLinks(), recoveredByKey,
                claimed, families, hallucinated, confidenceAndCorrect);

        int forbiddenViolations = 0;
        Set<String> forbiddenKeys = expandKeys(truth.forbiddenCausalLinks());
        for (Map.Entry<String, RecoveredEdge> entry : recoveredByKey.entrySet()) {
            if (forbiddenKeys.contains(entry.getKey())) {
                forbiddenViolations++;
                claimed.add(entry.getKey());
            }
        }

        // Anything recovered that no family (or forbidden list) claimed is unanticipated.
        for (Map.Entry<String, RecoveredEdge> entry : recoveredByKey.entrySet()) {
            if (!claimed.contains(entry.getKey())) {
                unanticipated.add(entry.getValue());
            }
        }

        // ── Resolutions (pairwise cluster comparison) ───────────────────────────────────
        if (!truth.duplicateSets().isEmpty()) {
            Set<String> truthPairs = pairsOf(truth.duplicateSets());
            Set<String> recoveredPairs = pairsOf(recovered.resolvedGroups());
            families.add(prf(FAMILY_RESOLUTIONS, truthPairs, recoveredPairs));
        }

        // ── Communities (pairwise co-membership over the planted universe) ──────────────
        if (!truth.communities().isEmpty()) {
            Set<String> universe = new HashSet<>();
            truth.communities().forEach(c -> c.forEach(k -> universe.add(GroundTruthManifest.normalize(k))));
            Set<String> truthPairs = pairsOf(truth.communities());
            Set<String> recoveredPairs = new HashSet<>();
            List<String> members = new ArrayList<>();
            Map<String, String> assign = new HashMap<>();
            for (Map.Entry<String, String> e : recovered.communityByNode().entrySet()) {
                String k = GroundTruthManifest.normalize(e.getKey());
                if (universe.contains(k) && e.getValue() != null) {
                    members.add(k);
                    assign.put(k, e.getValue());
                }
            }
            members.sort(String::compareTo);
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    if (assign.get(members.get(i)).equals(assign.get(members.get(j)))) {
                        recoveredPairs.add(pairKey(members.get(i), members.get(j)));
                    }
                }
            }
            families.add(prf(FAMILY_COMMUNITIES, truthPairs, recoveredPairs));
        }

        // ── Calibration over recovered edges in scored families ─────────────────────────
        List<CalibrationBucket> buckets = new ArrayList<>();
        double ece = 0.0;
        if (!confidenceAndCorrect.isEmpty()) {
            int n = confidenceAndCorrect.size();
            for (int b = 0; b < 10; b++) {
                double lo = b / 10.0, hi = (b + 1) / 10.0;
                int count = 0;
                double confSum = 0, correctSum = 0;
                for (double[] cc : confidenceAndCorrect) {
                    boolean inBucket = cc[0] >= lo && (b == 9 ? cc[0] <= hi : cc[0] < hi);
                    if (inBucket) {
                        count++;
                        confSum += cc[0];
                        correctSum += cc[1];
                    }
                }
                if (count > 0) {
                    double meanConf = confSum / count;
                    double acc = correctSum / count;
                    buckets.add(new CalibrationBucket(lo, hi, count, meanConf, acc));
                    ece += (count / (double) n) * Math.abs(acc - meanConf);
                }
            }
        }

        double macroF1 = families.isEmpty() ? 0.0
                : families.stream().mapToDouble(FamilyScore::f1).average().orElse(0.0);

        return new ScoreReport(families, macroF1, buckets, ece,
                forbiddenViolations, hallucinated, unanticipated);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────────────

    private static void scoreEdgeFamily(String family, List<ExpectedEdge> truthEdges,
                                        Map<String, RecoveredEdge> recoveredByKey,
                                        Set<String> claimed, List<FamilyScore> families,
                                        List<RecoveredEdge> hallucinated,
                                        List<double[]> confidenceAndCorrect) {
        if (truthEdges.isEmpty()) return;

        Set<String> relationTypes = new HashSet<>();
        truthEdges.forEach(t -> relationTypes.add(GroundTruthManifest.normalize(t.relationType())));

        int tp = 0;
        Set<String> matchedRecoveredKeys = new HashSet<>();
        for (ExpectedEdge t : truthEdges) {
            String forward = GroundTruthManifest.edgeKey(t.sourceKey(), t.relationType(), t.targetKey());
            String reverse = GroundTruthManifest.edgeKey(t.targetKey(), t.relationType(), t.sourceKey());
            String hit = recoveredByKey.containsKey(forward) ? forward
                    : (t.symmetric() && recoveredByKey.containsKey(reverse)) ? reverse : null;
            if (hit != null) {
                tp++;
                matchedRecoveredKeys.add(hit);
            }
        }

        int familyRecovered = 0;
        for (Map.Entry<String, RecoveredEdge> entry : recoveredByKey.entrySet()) {
            RecoveredEdge e = entry.getValue();
            if (!relationTypes.contains(GroundTruthManifest.normalize(e.relationType()))) continue;
            familyRecovered++;
            claimed.add(entry.getKey());
            boolean correct = matchedRecoveredKeys.contains(entry.getKey());
            confidenceAndCorrect.add(new double[]{e.confidence(), correct ? 1.0 : 0.0});
            if (!correct) {
                hallucinated.add(e);
            }
        }

        families.add(score(family, truthEdges.size(), familyRecovered, tp));
    }

    private static FamilyScore prf(String family, Set<String> truthPairs, Set<String> recoveredPairs) {
        int tp = 0;
        for (String p : recoveredPairs) {
            if (truthPairs.contains(p)) tp++;
        }
        return score(family, truthPairs.size(), recoveredPairs.size(), tp);
    }

    private static FamilyScore score(String family, int truthCount, int recoveredCount, int tp) {
        double precision = recoveredCount == 0 ? 0.0 : tp / (double) recoveredCount;
        double recall = truthCount == 0 ? 0.0 : tp / (double) truthCount;
        double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        return new FamilyScore(family, truthCount, recoveredCount, tp, precision, recall, f1);
    }

    private static Set<String> expandKeys(List<ExpectedEdge> edges) {
        Set<String> keys = new HashSet<>();
        for (ExpectedEdge t : edges) {
            keys.add(GroundTruthManifest.edgeKey(t.sourceKey(), t.relationType(), t.targetKey()));
            if (t.symmetric()) {
                keys.add(GroundTruthManifest.edgeKey(t.targetKey(), t.relationType(), t.sourceKey()));
            }
        }
        return keys;
    }

    /** All unordered within-group pairs across the given groups, as normalized keys. */
    private static Set<String> pairsOf(List<Set<String>> groups) {
        Set<String> pairs = new HashSet<>();
        for (Set<String> group : groups) {
            List<String> sorted = new ArrayList<>();
            group.forEach(k -> sorted.add(GroundTruthManifest.normalize(k)));
            sorted.sort(String::compareTo);
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    pairs.add(pairKey(sorted.get(i), sorted.get(j)));
                }
            }
        }
        return pairs;
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "~" + b : b + "~" + a;
    }
}
