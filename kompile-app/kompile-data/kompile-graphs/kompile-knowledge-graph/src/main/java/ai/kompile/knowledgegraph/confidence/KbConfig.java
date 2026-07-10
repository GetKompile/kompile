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
import java.util.Set;

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

    /**
     * When {@code true}, the crawl ENRICHMENT stage auto-builds and registers an
     * {@link ai.kompile.knowledgegraph.reasoning.MebnTheoryRegistrationService MTheory}
     * for the fact sheet immediately before MAP re-ground, so STEP 9 (SSBN weight
     * learning) fires inside the derivation cascade.
     *
     * <p><b>DEFAULTS FALSE</b>: SSBN grounding is memory-sensitive (O(k²) node-pair
     * expansion). Enable only when MEBN weight learning is explicitly desired and the
     * host has sufficient RAM / a {@link #derivationTimeBudgetMs} guard in place.
     * A normal crawl is byte-for-byte unchanged when this flag is {@code false}.</p>
     */
    private boolean mebnTheoryRegistrationOnCrawlEnabled = false;

    /**
     * Auto-register a bounded MTheory when the fact sheet deliberately binds an ontology.
     * This keeps MEBN off for unconstrained crawls while making it available for typed graphs.
     */
    private boolean mebnAutoEnableWhenOntologyBound = true;

    // ── Graph-neural crawl overlay ─────────────────────────────────────────────────
    /** Score retained crawl edges after derivation using graph-resident features and embeddings. */
    private boolean gnnScoringOnCrawlEnabled = true;
    /** Safety bound for graph-neural scoring; larger graphs skip the stage intact. */
    private int gnnMaxNodes = 10_000;
    /** Safety bound for retained edges presented to graph-neural scoring. */
    private int gnnMaxEdges = 100_000;
    /** Number of score metadata updates written in one store call. */
    private int gnnScoreBatchSize = 500;
    /** Contribution of a node's own features during one-hop aggregation. */
    private double gnnSelfWeight = 0.7;
    /** Contribution of the weighted neighbor mean during one-hop aggregation. */
    private double gnnNeighborWeight = 0.3;

    // ── Staging → serving auto-load bridge ─────────────────────────────────────────
    /** Poll staging for the active model and auto-load it into the serving subprocess. */
    private boolean servingAutoLoadEnabled = true;
    /** Poll interval for the staging auto-load bridge, in seconds. */
    private int servingAutoLoadPollIntervalSeconds = 15;

    // ── Graph simulator safety bounds ──────────────────────────────────────────────
    /** Maximum synthetic nodes hydrated by one simulator run. */
    private int simMaxNodesPerRun = 25_000;
    /** Maximum synthetic edges hydrated by one simulator run. */
    private int simMaxEdgesPerRun = 250_000;

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

    // ── Derivation / ENRICHMENT budget caps ──────────────────────────────────────
    /**
     * Wall-clock time budget (milliseconds) for the entire DERIVATION stage (MAP re-ground +
     * weight learning + bulk materialization) per fact sheet.  0 = no cap (original unbounded
     * behaviour).  When the deadline is exceeded the crawl continues with whatever was
     * persisted before the timeout.
     *
     * <p><b>Default 180 s (3 minutes)</b>: with {@code derivationMaxAtoms=5000} the MAP solve
     * completes in seconds on CPU; 3 minutes is a generous but not runaway bound.  The prior
     * 30-minute default was raised to compensate for a 50k-atom cap that was too slow —
     * reducing atoms to 5k makes 3 minutes more than sufficient and prevents the crawl from
     * hanging for half an hour on every ENRICHMENT run.  Set to 0 to remove the cap for
     * offline/research runs that intentionally use very large atom sets.</p>
     */
    private long derivationTimeBudgetMs = 0L;  // 0 = no timeout: never abandon derivation partway.
                                               // The previous 180s deadline silently truncated MAP
                                               // inference; the full solve runs to completion.

    /**
     * Wall-clock time budget (milliseconds) for the bulk materialization step (storeAll) within
     * the DERIVATION stage.  0 = no cap (materialization runs to completion or until the outer
     * {@link #derivationTimeBudgetMs} wall expires).
     *
     * <p>This cap protects against runaway I/O when the inferred-fact store is very slow.  It is
     * separate from the MAP-solve budget so a fast solve followed by a slow store still persists
     * as many facts as complete within the materialization window before the overall budget fires.</p>
     */
    private long derivationMaterializationTimeBudgetMs = 0L;  // no separate cap by default

    /**
     * Maximum number of PSL weight-learning epochs per DERIVATION call.  Overrides
     * {@link #pslMaxEpochs} specifically on the crawl / ENRICHMENT path so a long crawl
     * does not trigger the same epoch count as an offline full-batch fit.
     * 0 = inherit {@link #pslMaxEpochs} (original behaviour).
     */
    private int derivationPslMaxEpochs = 1;  // online-only on the crawl path

    /**
     * Maximum number of atoms to process per MAP re-ground on the DERIVATION path.
     * 0 = no cap (original unbounded behaviour).  When the fact store exceeds this,
     * the program is built from a random sample of this size, preventing multi-hour hangs
     * on extremely large graphs.
     *
     * <p><b>Default lowered from 50 000 → 5 000</b>: the HL-MRF MAP solve is O(atoms²) per
     * epoch; 50k atoms takes 15-30 minutes on CPU (hence the prior 30-min timeout still
     * expired with derived=0).  5k atoms completes in seconds while still capturing the most
     * frequent/impactful inferences.  Raise to 10k–20k once a GPU is available or after
     * confirming MAP solve time is acceptable.</p>
     */
    private int derivationMaxAtoms = 0;   // 0 = unlimited: project the WHOLE graph (nodes + edges).
                                          // A positive cap truncated to N atoms — and since nodes are
                                          // projected before edges, any cap < nodeCount starved edges
                                          // entirely (derivation saw 0 edges → derived nothing). Process
                                          // the full graph; bound memory by minibatching, not by dropping data.

    // ── Hybrid consensus training (HybridConsensusTrainer) ─────────────────────────
    /** How strongly the hybrid reasoner's ranked responses pull the joint-training targets, in [0,1]. */
    private double hybridConsensusWeight = 0.5;

    // ── Cross-doc name-resolution edge topology (computeNameBasedCrossDocEdges) ────
    /**
     * Topology for the cross-doc name-resolution SHARED_ENTITY edges.
     *
     * <p>When {@code true} (default) each name bucket is linked as a <b>star</b>: every
     * cross-document member is connected to a hub, which is {@code O(k)} edges that still place
     * all members in one connected component.  That connectivity is the entire documented purpose
     * of these edges (it stops the ComponentPruner treating cross-doc entity islands as singletons).</p>
     *
     * <p>When {@code false} the legacy <b>clique</b> links every pair — {@code O(k²)} edges.  For
     * generic structured values (an FP&amp;A spreadsheet value like "Revenue"/"Total"/"0" lands
     * hundreds of cells in one bucket → a single bucket emits {@code ~k²/2 ≈ 125 000} edges) this
     * was the dominant source of edge-count explosion (703k of 1.27M edges were SHARED_ENTITY).
     * The star preserves the identical connected component with linear edges and drops no data.</p>
     */
    private boolean crossDocStarTopology = true;

    // ── Evidence sufficiency gate ──────────────────────────────────────────────────
    /** When true, the retrieval sufficiency gate is active and will block answers with insufficient evidence. */
    private boolean evidenceSufficiencyEnabled = true;
    /** Minimum composite sufficiency score [0,1] required to proceed without retrieval expansion. */
    private double evidenceSufficiencyThreshold = 0.4;
    /** Minimum number of distinct sources required before sufficiency score reaches its upper bound. */
    private int evidenceSufficiencyMinSources = 2;

    // ── Corrective retrieval ───────────────────────────────────────────────────────
    /** When true, the corrective-retrieval loop re-fetches when the sufficiency gate fails. */
    private boolean correctiveRetrievalEnabled = true;

    // ── Grounded answer verification ──────────────────────────────────────────────
    /** When true, generated answers are verified against retrieved evidence before being returned. */
    private boolean answerVerificationEnabled = true;

    // ── Citation contract enforcement ─────────────────────────────────────────────
    /** When true, answers must cite sources; uncited claims trigger a post-processing attribution pass. */
    private boolean citationContractEnabled = true;

    // ── Reasoning-trail prompt injection ──────────────────────────────────────────
    /**
     * When true, the reasoning trail (evidence chain) is appended to the LLM prompt
     * during graph-reasoning retrieval so the model can cite its reasoning steps.
     */
    private boolean reasoningTrailInPromptEnabled = true;
    /**
     * Maximum number of trail lines to inject into the LLM prompt.
     * Keeps the prompt within token budget even for deep derivation chains.
     */
    private int reasoningTrailPromptMaxLines = 30;

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
        m.put("kbMebnTheoryRegistrationOnCrawlEnabled", mebnTheoryRegistrationOnCrawlEnabled);
        m.put("kbMebnAutoEnableWhenOntologyBound", mebnAutoEnableWhenOntologyBound);
        m.put("kbGnnScoringOnCrawlEnabled", gnnScoringOnCrawlEnabled);
        m.put("kbGnnMaxNodes", gnnMaxNodes);
        m.put("kbGnnMaxEdges", gnnMaxEdges);
        m.put("kbGnnScoreBatchSize", gnnScoreBatchSize);
        m.put("kbGnnSelfWeight", gnnSelfWeight);
        m.put("kbGnnNeighborWeight", gnnNeighborWeight);
        m.put("kbSimMaxNodesPerRun", simMaxNodesPerRun);
        m.put("kbSimMaxEdgesPerRun", simMaxEdgesPerRun);
        m.put("kbPrunePolicyMinBelief", prunePolicyMinBelief);
        m.put("kbPrunePolicyMaxUncertainty", prunePolicyMaxUncertainty);
        m.put("kbPrunePolicyMinExpectation", prunePolicyMinExpectation);
        m.put("kbPrunePolicyPruneSuppressedBand", prunePolicyPruneSuppressedBand);
        m.put("kbOntologyGuidedExtractionEnabled", ontologyGuidedExtractionEnabled);
        m.put("kbOntologyRuleWeight", ontologyRuleWeight);
        m.put("kbDerivationTimeBudgetMs", derivationTimeBudgetMs);
        m.put("kbDerivationMaterializationTimeBudgetMs", derivationMaterializationTimeBudgetMs);
        m.put("kbDerivationPslMaxEpochs", derivationPslMaxEpochs);
        m.put("kbDerivationMaxAtoms", derivationMaxAtoms);
        m.put("kbHybridConsensusWeight", hybridConsensusWeight);
        m.put("kbCrossDocStarTopology", crossDocStarTopology);
        m.put("kbRuleWeightEstablishedMean", ruleWeightEstablishedMean);
        m.put("kbRuleWeightHighMean", ruleWeightHighMean);
        m.put("kbRuleWeightProbableMean", ruleWeightProbableMean);
        m.put("kbRuleWeightSpeculativeMean", ruleWeightSpeculativeMean);
        m.put("kbPersonalEmailDomains", personalEmailDomains);
        m.put("kbReasoningTrailInPromptEnabled", reasoningTrailInPromptEnabled);
        m.put("kbReasoningTrailPromptMaxLines", reasoningTrailPromptMaxLines);
        m.put("kbEvidenceSufficiencyEnabled", evidenceSufficiencyEnabled);
        m.put("kbEvidenceSufficiencyThreshold", evidenceSufficiencyThreshold);
        m.put("kbEvidenceSufficiencyMinSources", evidenceSufficiencyMinSources);
        m.put("kbCorrectiveRetrievalEnabled", correctiveRetrievalEnabled);
        m.put("kbAnswerVerificationEnabled", answerVerificationEnabled);
        m.put("kbCitationContractEnabled", citationContractEnabled);
        return m;
    }

    /** The {@code kb*} keys this config owns, so a config push never clobbers other sections of a shared file. */
    public static Set<String> keys() {
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
        c.mebnTheoryRegistrationOnCrawlEnabled = bool(root, "kbMebnTheoryRegistrationOnCrawlEnabled", c.mebnTheoryRegistrationOnCrawlEnabled);
        c.mebnAutoEnableWhenOntologyBound = bool(root, "kbMebnAutoEnableWhenOntologyBound", c.mebnAutoEnableWhenOntologyBound);
        c.gnnScoringOnCrawlEnabled = bool(root, "kbGnnScoringOnCrawlEnabled", c.gnnScoringOnCrawlEnabled);
        c.gnnMaxNodes = intf(root, "kbGnnMaxNodes", c.gnnMaxNodes, 1, 10_000_000);
        c.gnnMaxEdges = intf(root, "kbGnnMaxEdges", c.gnnMaxEdges, 1, 100_000_000);
        c.gnnScoreBatchSize = intf(root, "kbGnnScoreBatchSize", c.gnnScoreBatchSize, 1, 100_000);
        c.gnnSelfWeight = dbl(root, "kbGnnSelfWeight", c.gnnSelfWeight, 0.0, 1.0);
        c.gnnNeighborWeight = dbl(root, "kbGnnNeighborWeight", c.gnnNeighborWeight, 0.0, 1.0);
        c.simMaxNodesPerRun = intf(root, "kbSimMaxNodesPerRun", c.simMaxNodesPerRun, 1, 10_000_000);
        c.simMaxEdgesPerRun = intf(root, "kbSimMaxEdgesPerRun", c.simMaxEdgesPerRun, 1, 100_000_000);
        c.prunePolicyMinBelief = dbl(root, "kbPrunePolicyMinBelief", c.prunePolicyMinBelief, 0.0, 1.0);
        c.prunePolicyMaxUncertainty = dbl(root, "kbPrunePolicyMaxUncertainty", c.prunePolicyMaxUncertainty, 0.0, 1.0);
        c.prunePolicyMinExpectation = dbl(root, "kbPrunePolicyMinExpectation", c.prunePolicyMinExpectation, 0.0, 1.0);
        c.prunePolicyPruneSuppressedBand = bool(root, "kbPrunePolicyPruneSuppressedBand", c.prunePolicyPruneSuppressedBand);
        c.ontologyGuidedExtractionEnabled = bool(root, "kbOntologyGuidedExtractionEnabled", c.ontologyGuidedExtractionEnabled);
        c.ontologyRuleWeight = dbl(root, "kbOntologyRuleWeight", c.ontologyRuleWeight, 0.0, 100.0);
        c.derivationTimeBudgetMs = lng(root, "kbDerivationTimeBudgetMs", c.derivationTimeBudgetMs, 0L, Long.MAX_VALUE);
        c.derivationMaterializationTimeBudgetMs = lng(root, "kbDerivationMaterializationTimeBudgetMs",
                c.derivationMaterializationTimeBudgetMs, 0L, Long.MAX_VALUE);
        c.derivationPslMaxEpochs = intf(root, "kbDerivationPslMaxEpochs", c.derivationPslMaxEpochs, 0, 100_000);
        c.derivationMaxAtoms = intf(root, "kbDerivationMaxAtoms", c.derivationMaxAtoms, 0, 10_000_000);
        c.hybridConsensusWeight = dbl(root, "kbHybridConsensusWeight", c.hybridConsensusWeight, 0.0, 1.0);
        c.crossDocStarTopology = bool(root, "kbCrossDocStarTopology", c.crossDocStarTopology);
        c.ruleWeightEstablishedMean = dbl(root, "kbRuleWeightEstablishedMean", c.ruleWeightEstablishedMean, 0.0, 100.0);
        c.ruleWeightHighMean = dbl(root, "kbRuleWeightHighMean", c.ruleWeightHighMean, 0.0, 100.0);
        c.ruleWeightProbableMean = dbl(root, "kbRuleWeightProbableMean", c.ruleWeightProbableMean, 0.0, 100.0);
        c.ruleWeightSpeculativeMean = dbl(root, "kbRuleWeightSpeculativeMean", c.ruleWeightSpeculativeMean, 0.0, 100.0);
        List<String> domains = strList(root, "kbPersonalEmailDomains");
        if (domains != null) {
            c.personalEmailDomains = domains;
        }
        c.reasoningTrailInPromptEnabled = bool(root, "kbReasoningTrailInPromptEnabled", c.reasoningTrailInPromptEnabled);
        c.reasoningTrailPromptMaxLines = intf(root, "kbReasoningTrailPromptMaxLines", c.reasoningTrailPromptMaxLines, 1, 10_000);
        c.evidenceSufficiencyEnabled = bool(root, "kbEvidenceSufficiencyEnabled", c.evidenceSufficiencyEnabled);
        c.evidenceSufficiencyThreshold = dbl(root, "kbEvidenceSufficiencyThreshold", c.evidenceSufficiencyThreshold, 0.0, 1.0);
        c.evidenceSufficiencyMinSources = intf(root, "kbEvidenceSufficiencyMinSources", c.evidenceSufficiencyMinSources, 1, 100);
        c.correctiveRetrievalEnabled = bool(root, "kbCorrectiveRetrievalEnabled", c.correctiveRetrievalEnabled);
        c.answerVerificationEnabled = bool(root, "kbAnswerVerificationEnabled", c.answerVerificationEnabled);
        c.citationContractEnabled = bool(root, "kbCitationContractEnabled", c.citationContractEnabled);
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
