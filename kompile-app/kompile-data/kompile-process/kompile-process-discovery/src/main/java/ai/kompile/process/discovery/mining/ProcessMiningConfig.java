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

package ai.kompile.process.discovery.mining;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Kompile-managed configuration for the process-mining / entailment pipeline.
 *
 * <p>Every tunable of the mining creation path lives here as a field with a default — there are
 * <b>no</b> {@code @Value} bindings and no hard-coded literals in the consuming services. Loaded
 * from {@code process-mining-config.json} under the kompile config directory and edited via the
 * web UI, exactly like {@code KbConfig}. {@link #from(JsonNode)} parses + clamps; {@link #toMap()}
 * serializes for the REST/UI surface.</p>
 */
@Getter
@Setter
public class ProcessMiningConfig {

    // ── Case notion ───────────────────────────────────────────────────────────────
    /**
     * Object-centric case notion: when non-blank, each entity of this type anchors one process
     * instance; blank = connected-component correlation.
     */
    private String anchorEntityType = "";

    /**
     * When node-only extraction yields no mineable business sequence, retry with observed relation
     * events projected as activities using ontology/type-resolution metadata.
     */
    private boolean relationEventFallbackEnabled = true;

    // ── Trace clustering ──────────────────────────────────────────────────────────
    /** Activity-set Jaccard ≥ this ⇒ traces belong to the same business process. */
    private double clusterJaccardThreshold = 0.2;
    /** Mine at most this many trace clusters (largest first) per fact sheet — never silently. */
    private int clusterMaxProcesses = 6;

    // ── Declare mining (feeds the entailment pass) ────────────────────────────────
    /** Declare-mining support floor. */
    private double declareMinSupport = 0.2;
    /** Declare-mining confidence floor. */
    private double declareMinConfidence = 0.66;

    // ── Entailment ────────────────────────────────────────────────────────────────
    /** Min entailed-precedence posterior to assert {@code precedes(...)} facts into the KB. */
    private double entailAssertThreshold = 0.7;
    /** Min entailed-precedence posterior for {@code PRECEDES} edge write-back onto the graph. */
    private double entailMaterializeThreshold = 0.7;
    /**
     * Temporal-vote recency half-life (days) relative to the log's newest dated event — recent
     * reversals outvote stale confirmations when a process drifts. {@code 0} disables decay.
     */
    private double recencyHalfLifeDays = 180.0;
    /**
     * Min temporally-labeled pairs before pseudolikelihood rule-weight learning replaces the
     * static structural weights. {@code 0} disables learning.
     */
    private int weightLearningMinLabels = 4;
    /** Pseudolikelihood epochs for the entailment program (deterministic, full batch). */
    private int weightLearningEpochs = 50;

    // ── Hybrid activation ─────────────────────────────────────────────────────────
    /**
     * Semantic blend weight for hybrid activation when an {@link ActivityEmbedder} is wired —
     * cosine-to-centroid contribution per engine. Engages with ≥2 embedded activity labels;
     * {@code 0} keeps activation purely structural.
     */
    private double hybridSemanticWeight = 0.4;

    // ── Observed-actor role resolution ────────────────────────────────────────────
    /**
     * An actor with no performer-labeled ({@code *_BY}) edge must touch at least this share of an
     * activity's instances to win its role binding (keeps one-off CC recipients from claiming
     * roles).
     */
    private double actorInvolvementMinShare = 0.5;

    // ── Decision mining (XOR guards from event attributes) ───────────────────────
    /**
     * Min separation accuracy a single-attribute decision stump must reach over an XOR's decision
     * cases before it becomes a mined guard on the branch's {@code conditionExpression}
     * (null-safe — a case without the attribute always passes). {@code 1.0} demands perfect
     * separation.
     */
    private double guardMinAccuracy = 0.9;

    // ── Embedding-based activity alias unification ────────────────────────────────
    /**
     * Distinct activity labels whose embedding cosine similarity reaches this threshold are
     * unified onto the more frequent label BEFORE mining ("Bill" → "Invoice"). Requires the
     * {@link ActivityEmbedder}; {@code > 1} disables (cosine cannot exceed 1).
     */
    private double aliasSimilarityThreshold = 0.95;

    // ── Process identity across mining generations ────────────────────────────────
    /**
     * A fresh suggestion whose activity set reaches this Jaccard overlap with a predecessor
     * (both sides alias-unified) is the SAME process at a later time: it inherits the
     * predecessor's {@code processKey} and gets a drift diff against it. Deliberately stricter
     * than {@link #clusterJaccardThreshold} — identity is a stronger claim than co-clustering.
     */
    private double identityJaccardThreshold = 0.5;

    /**
     * Within-log change-point detection needs at least this many dated traces on EACH side of a
     * candidate split before a "the process changed around <date>" claim is made. Raising it
     * demands more evidence; it can never be below 1.
     */
    private int changePointMinWindowCases = 3;

    // ── Conflicting source descriptions ───────────────────────────────────────────
    /**
     * A two-way ordering disagreement only counts as a CONFLICT when the losing direction holds
     * at least this share of the votes (and ≥2 votes) — below it, the temporal cross-exam's
     * silent-majority handling is the right tool. Conflicts surface BOTH sides with source
     * attribution plus an explicitly-GUESSED reconciliation.
     */
    private double conflictMinorityShare = 0.25;

    // ── OWL taxonomy roll-up (is-a closure consumption) ───────────────────────────
    /**
     * Min sibling activities sharing an OWL closure ancestor ({@code owlInferredTypes} node
     * metadata, materialized by OWL classification) before they roll up to the shared concept
     * ("Chianti"+"Malbec"+"Riesling" → "Wine"; leaf labels survive as the {@code category} event
     * attribute for guard mining). {@code < 2} disables; inert without closure metadata.
     */
    private int taxonomyMinSiblings = 2;

    public static ProcessMiningConfig defaults() {
        return new ProcessMiningConfig();
    }

    /** Serialize effective values keyed by the same {@code mining*} names {@link #from} reads. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("miningAnchorEntityType", anchorEntityType);
        m.put("miningRelationEventFallbackEnabled", relationEventFallbackEnabled);
        m.put("miningClusterJaccardThreshold", clusterJaccardThreshold);
        m.put("miningClusterMaxProcesses", clusterMaxProcesses);
        m.put("miningDeclareMinSupport", declareMinSupport);
        m.put("miningDeclareMinConfidence", declareMinConfidence);
        m.put("miningEntailAssertThreshold", entailAssertThreshold);
        m.put("miningEntailMaterializeThreshold", entailMaterializeThreshold);
        m.put("miningRecencyHalfLifeDays", recencyHalfLifeDays);
        m.put("miningWeightLearningMinLabels", weightLearningMinLabels);
        m.put("miningWeightLearningEpochs", weightLearningEpochs);
        m.put("miningHybridSemanticWeight", hybridSemanticWeight);
        m.put("miningActorInvolvementMinShare", actorInvolvementMinShare);
        m.put("miningGuardMinAccuracy", guardMinAccuracy);
        m.put("miningAliasSimilarityThreshold", aliasSimilarityThreshold);
        m.put("miningIdentityJaccardThreshold", identityJaccardThreshold);
        m.put("miningChangePointMinWindowCases", changePointMinWindowCases);
        m.put("miningConflictMinorityShare", conflictMinorityShare);
        m.put("miningTaxonomyMinSiblings", taxonomyMinSiblings);
        return m;
    }

    /** The {@code mining*} keys this config owns, so a config push never clobbers other sections. */
    public static Set<String> keys() {
        return defaults().toMap().keySet();
    }

    public static ProcessMiningConfig from(JsonNode root) {
        ProcessMiningConfig c = defaults();
        if (root == null) {
            return c;
        }
        if (root.has("miningAnchorEntityType") && root.get("miningAnchorEntityType").isTextual()) {
            c.anchorEntityType = root.get("miningAnchorEntityType").asText(c.anchorEntityType).trim();
        }
        c.relationEventFallbackEnabled = bool(root, "miningRelationEventFallbackEnabled",
                c.relationEventFallbackEnabled);
        c.clusterJaccardThreshold = dbl(root, "miningClusterJaccardThreshold", c.clusterJaccardThreshold, 0.0, 1.0);
        c.clusterMaxProcesses = intf(root, "miningClusterMaxProcesses", c.clusterMaxProcesses, 1, 100);
        c.declareMinSupport = dbl(root, "miningDeclareMinSupport", c.declareMinSupport, 0.0, 1.0);
        c.declareMinConfidence = dbl(root, "miningDeclareMinConfidence", c.declareMinConfidence, 0.0, 1.0);
        c.entailAssertThreshold = dbl(root, "miningEntailAssertThreshold", c.entailAssertThreshold, 0.0, 1.0);
        c.entailMaterializeThreshold = dbl(root, "miningEntailMaterializeThreshold", c.entailMaterializeThreshold, 0.0, 1.0);
        c.recencyHalfLifeDays = dbl(root, "miningRecencyHalfLifeDays", c.recencyHalfLifeDays, 0.0, 36_500.0);
        c.weightLearningMinLabels = intf(root, "miningWeightLearningMinLabels", c.weightLearningMinLabels, 0, 100_000);
        c.weightLearningEpochs = intf(root, "miningWeightLearningEpochs", c.weightLearningEpochs, 1, 100_000);
        c.hybridSemanticWeight = dbl(root, "miningHybridSemanticWeight", c.hybridSemanticWeight, 0.0, 1.0);
        c.actorInvolvementMinShare = dbl(root, "miningActorInvolvementMinShare", c.actorInvolvementMinShare, 0.0, 1.0);
        c.guardMinAccuracy = dbl(root, "miningGuardMinAccuracy", c.guardMinAccuracy, 0.5, 1.0);
        c.aliasSimilarityThreshold = dbl(root, "miningAliasSimilarityThreshold", c.aliasSimilarityThreshold, 0.5, 2.0);
        c.identityJaccardThreshold = dbl(root, "miningIdentityJaccardThreshold", c.identityJaccardThreshold, 0.1, 1.0);
        c.changePointMinWindowCases = intf(root, "miningChangePointMinWindowCases", c.changePointMinWindowCases, 1, 100_000);
        c.conflictMinorityShare = dbl(root, "miningConflictMinorityShare", c.conflictMinorityShare, 0.05, 0.5);
        c.taxonomyMinSiblings = intf(root, "miningTaxonomyMinSiblings", c.taxonomyMinSiblings, 0, 1000);
        return c;
    }

    // ── Parse helpers (clamped; mirror KbConfig) ──────────────────────────────────

    private static boolean bool(JsonNode root, String name, boolean fallback) {
        JsonNode n = root.get(name);
        if (n == null || !n.isBoolean()) {
            return fallback;
        }
        return n.asBoolean();
    }

    private static double dbl(JsonNode root, String name, double fallback, double min, double max) {
        JsonNode n = root.get(name);
        if (n == null || !n.isNumber()) {
            return fallback;
        }
        return Math.max(min, Math.min(max, n.asDouble()));
    }

    private static int intf(JsonNode root, String name, int fallback, int min, int max) {
        JsonNode n = root.get(name);
        if (n == null || !n.canConvertToInt()) {
            return fallback;
        }
        return Math.max(min, Math.min(max, n.asInt()));
    }
}
