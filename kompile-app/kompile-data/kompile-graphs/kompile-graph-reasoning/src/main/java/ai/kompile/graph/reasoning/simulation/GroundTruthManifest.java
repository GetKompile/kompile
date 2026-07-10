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

import java.util.List;
import java.util.Set;

/**
 * The patterns a {@link GraphScenario} deliberately planted — what the reasoning stack SHOULD
 * learn from the observed ticks. {@link PatternRecoveryScorer} compares the post-reasoning store
 * state against this manifest to produce per-family precision/recall and calibration.
 *
 * <p>All entity references use {@link ScenarioNode#key()} (scenario-local ids), never store node
 * ids: the manifest is created before the store exists and must survive re-runs.</p>
 *
 * @param expectedInferredEdges relations that are entailed by the planted structure but withheld
 *                              from the observed ticks (e.g. held-out rule heads, containment
 *                              closure). Recovery = an INFERRED edge materialized for the triple
 * @param duplicateSets         groups of scenario keys that denote the SAME real-world entity
 *                              under different surface forms; recovery = entity resolution merges
 *                              them / links them with RESOLVES_TO
 * @param communities           the planted community partition (each set = one community's
 *                              member keys); recovery = community detection co-assigns members
 * @param expectedCausalLinks   the true causal DAG edges for temporal scenarios
 * @param forbiddenCausalLinks  correlated-but-NOT-causal pairs (e.g. two effects of a common
 *                              confounder); inferring these is a scored error
 * @param generatingRules       the hidden weighted rules the observations were sampled from
 *                              (rule-world); lets the UI show "the pattern that was planted" and
 *                              future weight-recovery scoring compare learned vs generating weights
 * @param corruptedEdgeKeys     normalized {@code source|REL|target} keys of deliberately planted
 *                              noise edges; the maintenance/TMS side should suppress or retract
 *                              these, and they never count as hallucinations of the reasoner
 */
public record GroundTruthManifest(
        List<ExpectedEdge> expectedInferredEdges,
        List<Set<String>> duplicateSets,
        List<Set<String>> communities,
        List<ExpectedEdge> expectedCausalLinks,
        List<ExpectedEdge> forbiddenCausalLinks,
        List<GeneratingRule> generatingRules,
        Set<String> corruptedEdgeKeys) {

    public GroundTruthManifest {
        expectedInferredEdges = (expectedInferredEdges == null) ? List.of() : List.copyOf(expectedInferredEdges);
        duplicateSets = (duplicateSets == null) ? List.of() : List.copyOf(duplicateSets);
        communities = (communities == null) ? List.of() : List.copyOf(communities);
        expectedCausalLinks = (expectedCausalLinks == null) ? List.of() : List.copyOf(expectedCausalLinks);
        forbiddenCausalLinks = (forbiddenCausalLinks == null) ? List.of() : List.copyOf(forbiddenCausalLinks);
        generatingRules = (generatingRules == null) ? List.of() : List.copyOf(generatingRules);
        corruptedEdgeKeys = (corruptedEdgeKeys == null) ? Set.of() : Set.copyOf(corruptedEdgeKeys);
    }

    /** Manifest with no planted truth (real-corpus / fork modes score via LLM evaluators instead). */
    public static GroundTruthManifest empty() {
        return new GroundTruthManifest(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of());
    }

    /**
     * A single expected (or forbidden) relation triple in scenario-key space.
     *
     * @param sourceKey    subject scenario key
     * @param relationType UPPER_SNAKE relation label, matched case/underscore-insensitively
     * @param targetKey    object scenario key
     * @param why          human-readable derivation reason shown in the UI (e.g.
     *                     {@code "R1: WORKS_FOR ∧ LOCATED_IN → BASED_IN"})
     * @param symmetric    when true, a recovered edge in either direction counts as a match
     */
    public record ExpectedEdge(String sourceKey, String relationType, String targetKey,
                               String why, boolean symmetric) {
        public ExpectedEdge(String sourceKey, String relationType, String targetKey, String why) {
            this(sourceKey, relationType, targetKey, why, false);
        }
    }

    /**
     * A hidden rule observations were sampled from.
     *
     * @param rule   display form, e.g. {@code "WORKS_FOR(p,o) & LOCATED_IN(o,c) -> BASED_IN(p,c)"}
     * @param weight the generating weight in [0,1] the sampler honored
     */
    public record GeneratingRule(String rule, double weight) {}

    /** Normalized key for an edge triple, used for corrupted-edge bookkeeping and matching. */
    public static String edgeKey(String sourceKey, String relationType, String targetKey) {
        return normalize(sourceKey) + "|" + normalize(relationType) + "|" + normalize(targetKey);
    }

    /** Lowercase-alphanumeric normalization so labels survive predicate-name round-trips. */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
