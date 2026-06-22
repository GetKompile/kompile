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
package ai.kompile.knowledgegraph.confidence;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kompile-managed configuration for the knowledge-base confidence / evidence / learning model.
 *
 * <p>Every tunable for the confidence-evidence model lives here as a field with a default — there
 * are <b>no</b> {@code @Value} bindings and no hard-coded literals buried in the consuming services.
 * Loaded from {@code kb-confidence-config.json} under the kompile config directory and edited via
 * the web UI, exactly like {@code CrawlRuntimeConfig}. {@link #from(JsonNode)} parses + clamps;
 * {@link #toMap()} serializes for the REST/UI surface.</p>
 */
@Getter
@Setter
public class KbConfig {

    // ── PSL weight learner (StructuredPerceptronLearner) ──────────────────────────
    /** Gradient-ascent learning rate. */
    private double pslLearningRate = 0.1;
    /** Convergence threshold on max per-epoch weight change. */
    private double pslTolerance = 1e-4;
    /** Mini-batch size of ground rules per epoch; 0 = full-batch. */
    private int pslBatchSize = 0;
    /** RNG seed for reproducible mini-batch subsampling. */
    private long pslSeed = 1234L;
    /** Max epochs per learn() call. */
    private int pslMaxEpochs = 50;
    /** MAP (Gaussian-prior / L2) regularization strength lambda; 0 = pure MLE. */
    private double pslWeightPriorStrength = 0.1;
    /** Prior mean each rule weight is shrunk toward under MAP regularization. */
    private double pslWeightPriorMean = 0.1;
    /** Default PSL soft-propagation rule weight for a new fact sheet (cold start). */
    private double pslDefaultRuleWeight = 0.8;

    /** When true, PSL/MEBN weight learning runs after each cascade. */
    private boolean learningEnabled = true;

    // ── Evidence / Beta priors, per basis (Pillar 1 + 2) ──────────────────────────
    /** Beta prior strength W for CORROBORATIVE/INFERRED facts (the slow-climb default). */
    private double evidencePriorStrength = 2.0;
    /** Beta prior strength W for STRUCTURAL facts (near-certain from one observation). */
    private double structuralPriorStrength = 0.1;
    /** Beta prior strength W for ASSERTED facts (0 = certainty by construction). */
    private double assertedPriorStrength = 0.0;

    // ── Source trust (Pillar 5 tier-1) ────────────────────────────────────────────
    private double trustEmailFrom = 0.95;
    private double trustEmailToCc = 0.90;
    private double trustStructuredUpload = 0.85;
    private double trustPdfOffice = 0.70;
    private double trustEmailBody = 0.65;
    private double trustLlmExtraction = 0.60;
    private double trustWebScrape = 0.45;
    private double trustDefault = 0.50;

    // ── Structural assertion strengths (Pillar 2 / 4) ─────────────────────────────
    /** Positive-evidence strength for the person_belongs_to_org assertion from an email domain. */
    private double belongsToOrgStrength = 0.25;

    // ── MEBN ──────────────────────────────────────────────────────────────────────
    /** Run MEBN finite-difference weight learning once every N cascades. */
    private int mebnLearningInterval = 10;

    // ── Prior-based Opinion prune (Pillar 6 — P6 in PruneCompactOrchestrator) ──────
    /** P6: prune edges whose subjective-logic belief is below this. */
    private double prunePolicyMinBelief = 0.10;
    /** P6: prune edges whose uncertainty exceeds this (no evidence base yet). */
    private double prunePolicyMaxUncertainty = 0.80;
    /** P6: prune edges whose projected expectation is below this. */
    private double prunePolicyMinExpectation = 0.15;
    /** P6: always prune SUPPRESSED-band edges. */
    private boolean prunePolicyPruneSuppressedBand = true;

    // ── Band-aware MAP regularization prior means (Pillar 1 — computePerRulePriorMeans) ──
    /**
     * MAP prior mean for rules whose supporting atoms are in the ESTABLISHED band (value ≥ 0.85).
     * ESTABLISHED rules deserve a high prior mean so they are not shrunk toward the smaller
     * means used for speculative rules during MAP regularization.
     */
    private double ruleWeightEstablishedMean = 0.9;
    /**
     * MAP prior mean for rules whose supporting atoms are in the HIGH band (0.65 ≤ value < 0.85).
     */
    private double ruleWeightHighMean = 0.7;
    /**
     * MAP prior mean for rules whose supporting atoms are in the PROBABLE band (0.35 ≤ value < 0.65).
     */
    private double ruleWeightProbableMean = 0.4;
    /**
     * MAP prior mean for rules whose supporting atoms are in the SPECULATIVE or SUPPRESSED band
     * (value < 0.35). Kept low so weak-evidence rules are pulled toward near-zero weight.
     */
    private double ruleWeightSpeculativeMean = 0.15;

    // ── Ontology tie-in (crawl→graph enrichment governed by a bound OntologySchema) ──
    /** P1: when true, a bound ontology constrains LLM/Tika extraction to its entity/relationship types. */
    private boolean ontologyGuidedExtractionEnabled = true;
    /** P3: soft PSL rule weight for DOMAIN/RANGE rules compiled from a bound ontology. */
    private double ontologyRuleWeight = 0.8;

    // ── Personal / free email providers (belongs_to_org exclusion list) ───────────
    private List<String> personalEmailDomains = new ArrayList<>(List.of(
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com",
            "msn.com", "yahoo.com", "ymail.com", "rocketmail.com", "icloud.com",
            "me.com", "mac.com", "proton.me", "protonmail.com", "pm.me", "aol.com",
            "gmx.com", "gmx.net", "zoho.com", "mail.com", "yandex.com", "yandex.ru",
            "fastmail.com", "hey.com", "tutanota.com", "tuta.io", "qq.com", "163.com"));

    public static KbConfig defaults() {
        return new KbConfig();
    }

    /** Serialize effective values keyed by the same {@code kb*} names {@link #from} reads. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kbPslLearningRate", pslLearningRate);
        m.put("kbPslTolerance", pslTolerance);
        m.put("kbPslBatchSize", pslBatchSize);
        m.put("kbPslSeed", pslSeed);
        m.put("kbPslMaxEpochs", pslMaxEpochs);
        m.put("kbPslWeightPriorStrength", pslWeightPriorStrength);
        m.put("kbPslWeightPriorMean", pslWeightPriorMean);
        m.put("kbPslDefaultRuleWeight", pslDefaultRuleWeight);
        m.put("kbLearningEnabled", learningEnabled);
        m.put("kbEvidencePriorStrength", evidencePriorStrength);
        m.put("kbStructuralPriorStrength", structuralPriorStrength);
        m.put("kbAssertedPriorStrength", assertedPriorStrength);
        m.put("kbTrustEmailFrom", trustEmailFrom);
        m.put("kbTrustEmailToCc", trustEmailToCc);
        m.put("kbTrustStructuredUpload", trustStructuredUpload);
        m.put("kbTrustPdfOffice", trustPdfOffice);
        m.put("kbTrustEmailBody", trustEmailBody);
        m.put("kbTrustLlmExtraction", trustLlmExtraction);
        m.put("kbTrustWebScrape", trustWebScrape);
        m.put("kbTrustDefault", trustDefault);
        m.put("kbBelongsToOrgStrength", belongsToOrgStrength);
        m.put("kbMebnLearningInterval", mebnLearningInterval);
        m.put("kbPrunePolicyMinBelief", prunePolicyMinBelief);
        m.put("kbPrunePolicyMaxUncertainty", prunePolicyMaxUncertainty);
        m.put("kbPrunePolicyMinExpectation", prunePolicyMinExpectation);
        m.put("kbPrunePolicyPruneSuppressedBand", prunePolicyPruneSuppressedBand);
        m.put("kbOntologyGuidedExtractionEnabled", ontologyGuidedExtractionEnabled);
        m.put("kbOntologyRuleWeight", ontologyRuleWeight);
        m.put("kbRuleWeightEstablishedMean", ruleWeightEstablishedMean);
        m.put("kbRuleWeightHighMean", ruleWeightHighMean);
        m.put("kbRuleWeightProbableMean", ruleWeightProbableMean);
        m.put("kbRuleWeightSpeculativeMean", ruleWeightSpeculativeMean);
        m.put("kbPersonalEmailDomains", personalEmailDomains);
        return m;
    }

    /** The {@code kb*} keys this config owns, so a config push never clobbers other sections of a shared file. */
    public static java.util.Set<String> keys() {
        return defaults().toMap().keySet();
    }

    public static KbConfig from(JsonNode root) {
        KbConfig c = defaults();
        if (root == null) {
            return c;
        }
        c.pslLearningRate = dbl(root, "kbPslLearningRate", c.pslLearningRate, 1e-6, 10.0);
        c.pslTolerance = dbl(root, "kbPslTolerance", c.pslTolerance, 1e-12, 1.0);
        c.pslBatchSize = intf(root, "kbPslBatchSize", c.pslBatchSize, 0, 1_000_000);
        c.pslSeed = lng(root, "kbPslSeed", c.pslSeed, Long.MIN_VALUE, Long.MAX_VALUE);
        c.pslMaxEpochs = intf(root, "kbPslMaxEpochs", c.pslMaxEpochs, 1, 100_000);
        c.pslWeightPriorStrength = dbl(root, "kbPslWeightPriorStrength", c.pslWeightPriorStrength, 0.0, 1000.0);
        c.pslWeightPriorMean = dbl(root, "kbPslWeightPriorMean", c.pslWeightPriorMean, 0.0, 100.0);
        c.pslDefaultRuleWeight = dbl(root, "kbPslDefaultRuleWeight", c.pslDefaultRuleWeight, 0.0, 100.0);
        c.learningEnabled = bool(root, "kbLearningEnabled", c.learningEnabled);
        c.evidencePriorStrength = dbl(root, "kbEvidencePriorStrength", c.evidencePriorStrength, 1e-6, 1000.0);
        c.structuralPriorStrength = dbl(root, "kbStructuralPriorStrength", c.structuralPriorStrength, 1e-6, 1000.0);
        c.assertedPriorStrength = dbl(root, "kbAssertedPriorStrength", c.assertedPriorStrength, 0.0, 1000.0);
        c.trustEmailFrom = dbl(root, "kbTrustEmailFrom", c.trustEmailFrom, 0.0, 1.0);
        c.trustEmailToCc = dbl(root, "kbTrustEmailToCc", c.trustEmailToCc, 0.0, 1.0);
        c.trustStructuredUpload = dbl(root, "kbTrustStructuredUpload", c.trustStructuredUpload, 0.0, 1.0);
        c.trustPdfOffice = dbl(root, "kbTrustPdfOffice", c.trustPdfOffice, 0.0, 1.0);
        c.trustEmailBody = dbl(root, "kbTrustEmailBody", c.trustEmailBody, 0.0, 1.0);
        c.trustLlmExtraction = dbl(root, "kbTrustLlmExtraction", c.trustLlmExtraction, 0.0, 1.0);
        c.trustWebScrape = dbl(root, "kbTrustWebScrape", c.trustWebScrape, 0.0, 1.0);
        c.trustDefault = dbl(root, "kbTrustDefault", c.trustDefault, 0.0, 1.0);
        c.belongsToOrgStrength = dbl(root, "kbBelongsToOrgStrength", c.belongsToOrgStrength, 0.0, 1000.0);
        c.mebnLearningInterval = intf(root, "kbMebnLearningInterval", c.mebnLearningInterval, 1, 100_000);
        c.prunePolicyMinBelief = dbl(root, "kbPrunePolicyMinBelief", c.prunePolicyMinBelief, 0.0, 1.0);
        c.prunePolicyMaxUncertainty = dbl(root, "kbPrunePolicyMaxUncertainty", c.prunePolicyMaxUncertainty, 0.0, 1.0);
        c.prunePolicyMinExpectation = dbl(root, "kbPrunePolicyMinExpectation", c.prunePolicyMinExpectation, 0.0, 1.0);
        c.prunePolicyPruneSuppressedBand = bool(root, "kbPrunePolicyPruneSuppressedBand", c.prunePolicyPruneSuppressedBand);
        c.ontologyGuidedExtractionEnabled = bool(root, "kbOntologyGuidedExtractionEnabled", c.ontologyGuidedExtractionEnabled);
        c.ontologyRuleWeight = dbl(root, "kbOntologyRuleWeight", c.ontologyRuleWeight, 0.0, 100.0);
        c.ruleWeightEstablishedMean = dbl(root, "kbRuleWeightEstablishedMean", c.ruleWeightEstablishedMean, 0.0, 100.0);
        c.ruleWeightHighMean = dbl(root, "kbRuleWeightHighMean", c.ruleWeightHighMean, 0.0, 100.0);
        c.ruleWeightProbableMean = dbl(root, "kbRuleWeightProbableMean", c.ruleWeightProbableMean, 0.0, 100.0);
        c.ruleWeightSpeculativeMean = dbl(root, "kbRuleWeightSpeculativeMean", c.ruleWeightSpeculativeMean, 0.0, 100.0);
        List<String> domains = strList(root, "kbPersonalEmailDomains");
        if (domains != null) {
            c.personalEmailDomains = domains;
        }
        return c;
    }

    // ── Parse helpers (clamped; mirror CrawlRuntimeConfig) ────────────────────────

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

    private static long lng(JsonNode root, String name, long fallback, long min, long max) {
        JsonNode n = root.get(name);
        if (n == null || !n.canConvertToLong()) {
            return fallback;
        }
        return Math.max(min, Math.min(max, n.asLong()));
    }

    private static boolean bool(JsonNode root, String name, boolean fallback) {
        JsonNode n = root.get(name);
        return (n != null && n.isBoolean()) ? n.asBoolean() : fallback;
    }

    private static List<String> strList(JsonNode root, String name) {
        JsonNode n = root.get(name);
        if (n == null || !n.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode e : n) {
            if (e != null && e.isTextual() && !e.asText().isBlank()) {
                out.add(e.asText().trim().toLowerCase());
            }
        }
        return out;
    }
}
