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

package ai.kompile.core.crawl.graph;

import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for LLM-based graph extraction during a unified crawl.
 * Controls which entity types to extract, which LLM to use, and schema enforcement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphExtractionConfig {

    /**
     * Prompt-detail profiles for decomposed extraction. {@link #AUTO} chooses the richest profile
     * that fits the selected model's executable context window and prompt budget. Explicit values
     * are project/job overrides, but they may not exceed the executable model budget.
     */
    public enum DecomposedPromptTier {
        AUTO,
        COMPACT,
        STANDARD,
        RICH,
        EXPANDED
    }

    /**
     * Fact families requested from one extraction pass.
     *
     * <p>{@link #FULL_GRAPH} preserves the normal entity-and-relation crawl. {@link #ENTITIES_ONLY}
     * is a deliberate first pass for identity discovery: the model proposes entity records while
     * graph admission and identity resolution remain engine-owned, and no relation proposal is
     * accepted from that pass.</p>
     */
    public enum ExtractionTarget {
        FULL_GRAPH,
        ENTITIES_ONLY
    }

    /**
     * One deterministic mapping from a graph evidence rule id to an operational disposition.
     *
     * <p>String-valued disposition keeps this core configuration independent of graph-reasoning.
     * The crawl adapter validates and converts it when graph-policy admission is enabled.</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OperationalAdmissionRule {
        private String ruleId;
        private String disposition;
        private int priority;
        private String statement;
    }

    /**
     * Compatibility accessor for callers that still ask whether graph extraction is enabled.
     * Graph extraction is mandatory for unified crawls.
     */
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }

    @JsonSetter("enabled")
    public void setEnabled(boolean ignored) {
        // Legacy JSON may still contain this key. It no longer controls graph extraction.
    }

    /** Named schema preset ID to load the standardized graph schema from (e.g., "fpna-cpg-channel-v1"). */
    private String schemaPresetId;

    /**
     * Full standardized schema resolved from the preset or supplied by a project override.
     *
     * <p>This is the authoritative vocabulary and property contract for extracted entities and
     * relations. The legacy type-name lists below remain useful as optional focus subsets, but must
     * not replace or flatten this schema.</p>
     */
    private GraphSchema standardizedSchema;

    /** Entity types to focus extraction on (empty = use every standardized or discovered type) */
    @Builder.Default
    private List<String> entityTypes = new ArrayList<>();

    /** Relationship types to focus extraction on (empty = extract all) */
    @Builder.Default
    private List<String> relationshipTypes = new ArrayList<>();

    /** Which fact families this pass may propose. */
    @Builder.Default
    private ExtractionTarget extractionTarget = ExtractionTarget.FULL_GRAPH;

    /** LLM provider for extraction (e.g., "openai", "anthropic", "ollama", "default") */
    @Builder.Default
    private String llmProvider = "default";

    /** LLM model name (e.g., "gpt-4o", "claude-3-5-sonnet"); null = provider default */
    private String modelName;

    /** LLM temperature for extraction (lower = more deterministic) */
    @Builder.Default
    private double temperature = 0.0;

    /** Max tokens for LLM response */
    @Builder.Default
    private int maxTokens = 4096;

    /** Custom extraction prompt (null = use default prompt from GraphExtractionValidator) */
    private String customPrompt;

    /**
     * Per-project validation and prompt policy. Null is treated as
     * {@link GraphExtractionValidationPolicy#defaults()} for legacy configurations.
     */
    @Builder.Default
    private GraphExtractionValidationPolicy validationPolicy = GraphExtractionValidationPolicy.defaults();

    /** Schema enforcement mode */
    @Builder.Default
    private SchemaEnforcementMode schemaMode = SchemaEnforcementMode.LENIENT;

    /** Accept the legacy "schemaEnforcement" JSON key and map it to schemaMode */
    @JsonSetter("schemaEnforcement")
    public void setSchemaEnforcementFromString(String value) {
        if (value != null && !value.isBlank()) {
            try {
                this.schemaMode = SchemaEnforcementMode.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                this.schemaMode = SchemaEnforcementMode.LENIENT;
            }
        }
    }

    /** Whether to run entity resolution across all sources to merge duplicates */
    @Builder.Default
    private boolean entityResolution = true;

    /** Similarity threshold for crawler graph compaction/entity resolution. */
    @Builder.Default
    private double entityResolutionSimilarityThreshold = 0.85;

    /**
     * Whether crawler entity resolution should use embedding-based fuzzy matching.
     * String, alias, and attribute-based resolution still run if memory pressure
     * forces this to fall back during a crawl.
     */
    @Builder.Default
    private boolean entityResolutionUseEmbeddings = true;

    /** Cosine threshold for embedding-assisted entity resolution when enabled. */
    @Builder.Default
    private double entityResolutionEmbeddingThreshold = 0.88;

    /** Minimum confidence threshold for keeping extracted entities (0.0 - 1.0). */
    @Builder.Default
    private double minConfidence = 0.5;

    /**
     * Optional typed calibration for probabilistic graph-addition thresholds. When absent, all
     * effective values exactly mirror the legacy scalar fields above/below, including user overrides.
     */
    private GraphAdditionCalibration graphAdditionCalibration;

    /** Fully validated immutable calibration consumed by graph-addition stages. */
    @JsonIgnore
    public GraphAdditionCalibration.Resolved getResolvedGraphAdditionCalibration() {
        if (graphAdditionCalibration == null) {
            return GraphAdditionCalibration.legacy(
                    decomposedCandidateMinScore,
                    minConfidence,
                    minConfidence,
                    legacyStringIdentitySimilarity(),
                    legacyEmbeddingIdentitySimilarity());
        }
        return graphAdditionCalibration.resolve(
                decomposedCandidateMinScore,
                minConfidence,
                minConfidence,
                legacyStringIdentitySimilarity(),
                legacyEmbeddingIdentitySimilarity());
    }

    @JsonIgnore
    public double getEffectiveCandidateMinScore() {
        return getResolvedGraphAdditionCalibration().candidateMinScore();
    }

    @JsonIgnore
    public double getEffectiveExtractionMinConfidence() {
        return getResolvedGraphAdditionCalibration().extractionMinConfidence();
    }

    @JsonIgnore
    public double getEffectivePersistenceMinConfidence() {
        return getResolvedGraphAdditionCalibration().persistenceMinConfidence();
    }

    @JsonIgnore
    public double getEffectiveStringIdentitySimilarity() {
        return getResolvedGraphAdditionCalibration().stringIdentitySimilarity();
    }

    @JsonIgnore
    public double getEffectiveEmbeddingIdentitySimilarity() {
        return getResolvedGraphAdditionCalibration().embeddingIdentitySimilarity();
    }

    private double legacyStringIdentitySimilarity() {
        return entityResolutionSimilarityThreshold > 0.0
                ? Math.min(1.0, entityResolutionSimilarityThreshold) : 0.85;
    }

    private double legacyEmbeddingIdentitySimilarity() {
        return entityResolutionEmbeddingThreshold > 0.0
                ? Math.min(1.0, entityResolutionEmbeddingThreshold) : 0.88;
    }

    /**
     * Whitelist of provider prefixes allowed for extraction model selection
     * (e.g. {@code ["opencode"]} to restrict to opencode-discovered models).
     * Null or empty means all providers are allowed.
     *
     * <p>This field serves as both the per-project default (when set in the stored
     * {@code graph-extraction-config.json}) and as a per-crawl override when set on
     * the {@link ai.kompile.core.crawl.graph.UnifiedCrawlRequest#graphExtraction} field.
     * The consumer ({@code CliAgentModelService}) applies the default exclusion markers
     * ["claude","codex"] unless {@link #extractionModelExcludeMarkers} is explicitly set.</p>
     */
    private List<String> extractionModelProviderAllow;

    /**
     * Substrings that disqualify a model id from extraction selection (case-insensitive match).
     * Null means the consumer's default (["claude","codex"]) is applied.
     * Setting this to an explicit list completely replaces the default — include "claude" and
     * "codex" in any custom list to preserve the paid-model mandate.
     *
     * <p>Like {@link #extractionModelProviderAllow}, this field is effective both as a
     * per-project default and as a per-crawl override.</p>
     */
    private List<String> extractionModelExcludeMarkers;

    /**
     * Explicit model-id allow-list for extraction.  When non-null and non-empty, model selection
     * uses exactly these ids (intersected with discovered ids and health state), ignoring
     * {@link #extractionModelProviderAllow}.  Null or empty means no explicit pin — providerAllow
     * governs which discovered models are candidates.
     *
     * <p>Example: {@code ["opencode/deepseek-v4-pro", "opencode/deepseek-v4-flash"]} pins the
     * crawl to exactly those two models (in order) regardless of what the agent discovers.</p>
     */
    private List<String> extractionModelAllow;

    /**
     * Soft ordered preference list for extraction model selection.  Unlike
     * {@link #extractionModelAllow}, this does not restrict candidates: discovered models in this
     * list are tried first when healthy, then all remaining provider-allowed candidates stay in the
     * fallback rotation.  Null means the model service's default priority applies; an explicit empty
     * list disables soft prioritization.
     */
    private List<String> extractionModelPriority;

    /**
     * Per-job / per-project override: allow the PAID last-resort model tier (e.g. a small amount of
     * Claude haiku) for extraction fallback, reached ONLY after the free opencode rotation is
     * exhausted. {@code null} = inherit the global {@code model-fallback-config.json} default (off).
     * Effective both as a per-project default and a per-crawl override, exactly like the policy
     * fields above — so "can this project spend a little paid budget" is configurable per project/job.
     */
    private Boolean extractionPaidFallbackEnabled;

    /**
     * Per-job / per-project override: maximum paid-model calls allowed for a single crawl (caps the
     * paid last-resort tier so a crawl can never drain a paid subscription). {@code null} = inherit
     * the global default.
     */
    private Integer extractionMaxPaidCallsPerCrawl;

    /**
     * Per-job / per-project override: per-call LLM timeout (seconds) for the extraction fallback
     * executor — lower it to rotate off a hung model faster, raise it for slow large-context models.
     * {@code null} = inherit the global default.
     */
    private Integer extractionPerCallTimeoutSeconds;

    /**
     * Fraction of the selected extraction model's context window to use as the per-call char
     * budget (0 &lt; fraction ≤ 1.0).  The remaining fraction is reserved for the prompt scaffold
     * and output tokens.  Null or ≤ 0 disables context-driven sizing and keeps the existing
     * static {@code graphExtractionTargetCharsPerBatch} behaviour.
     *
     * <p>Default (when null): {@code 0.5} is applied by the consumer.  A 1 M-token model at
     * 3.5 chars/token and fraction=0.5 yields ~1 750 000 chars per call.</p>
     */
    private Double extractionContextBudgetFraction;

    /**
     * Estimated chars per token used when converting a model's context window (in tokens) to a
     * char budget.  Null means the consumer default of {@code 3.5} is applied.
     *
     * <p>Increase to 4.0 for English-heavy corpora; reduce to 2.5 for mixed-language/code.</p>
     */
    private Double extractionCharsPerToken;

    // -----------------------------------------------------------------------------------------
    // Decomposed extraction (bounded per-pass model operations)
    // -----------------------------------------------------------------------------------------

    /**
     * Whether a chunk is extracted with one monolithic prompt ({@link ExtractionMode#SINGLE_PASS},
     * the historical behaviour) or as a sequence of bounded passes
     * ({@link ExtractionMode#DECOMPOSED}). Both modes produce the same schema-shaped result, so
     * everything downstream is unchanged.
     */
    @Builder.Default
    private ExtractionMode extractionMode = ExtractionMode.SINGLE_PASS;

    /** Accepts a loose/legacy string for {@link #extractionMode} without failing the whole config. */
    @JsonSetter("extractionMode")
    public void setExtractionModeFromString(String value) {
        if (value != null && !value.isBlank()) {
            try {
                this.extractionMode = ExtractionMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                this.extractionMode = ExtractionMode.SINGLE_PASS;
            }
        }
    }

    /**
     * Admission mode for candidate entities emitted by decomposed extraction.
     * LLM_ONLY preserves the historical path; SHADOW_COMPARE observes the graph branch while keeping
     * the staged model action authoritative; GRAPH_POLICY admits configured entity types only when a
     * deterministic operational rule returns ALLOW. The value is intentionally a string here so the
     * core extraction configuration does not depend on the graph-reasoning module.
     */
    @Builder.Default
    private String admissionMode = "LLM_ONLY";

    /** Accept a loose value while keeping older project configuration files loadable. */
    @JsonSetter("admissionMode")
    public void setAdmissionModeFromString(String value) {
        this.admissionMode = value == null || value.isBlank()
                ? "LLM_ONLY" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Entity types governed by GRAPH_POLICY. This scope must be explicit so supporting ontology,
     * status, policy, and evidence nodes remain available to the graph without being mistaken for
     * operational candidates. Use "*" only when every extracted entity is intentionally governed.
     */
    @Builder.Default
    private List<String> operationalAdmissionEntityTypes = new ArrayList<>();

    /**
     * Deterministic rule mappings consumed by GRAPH_POLICY. Rule ids must match the machine-readable
     * ids emitted by graph admission evidence; an in-scope candidate with no matching rule is REVIEW
     * and is not inserted.
     */
    @Builder.Default
    private List<OperationalAdmissionRule> operationalAdmissionRules = new ArrayList<>();

    /**
     * Optional upper bound on propositions taken from one chunk in the decomposed pipeline.
     *
     * <p>Zero or a negative value means unbounded: the model may surface every source-grounded
     * proposition that fits its serving output budget. A positive value is an explicit project-level
     * safety override; prompt tiers never impose a second hidden cap.</p>
     */
    @Builder.Default
    private int decomposedMaxPropositions = 0;

    /** Entity candidates the engine retrieves and offers the model per mention. */
    @Builder.Default
    private int decomposedEntityCandidateLimit = 8;

    /**
     * Ask the mention pass to resolve one fixed subject/object endpoint per call. This costs up to
     * two calls per proposition but removes endpoint enumeration and role repetition from the
     * model's task. Endpoint-local ballots are the production default because the combined contract
     * measurably loses identity decisions on small local models; callers may disable it only for a
     * controlled legacy ablation.
     */
    @Builder.Default
    private boolean decomposedSplitMentionsByEndpoint = true;

    /** Relation types offered to the model for one resolved endpoint pair. */
    @Builder.Default
    private int decomposedRelationCandidateLimit = 12;

    /** Existing claims offered to the model when matching new evidence. */
    @Builder.Default
    private int decomposedClaimCandidateLimit = 5;

    /**
     * Context-aware prompt profile for every decomposed pass. AUTO is resolved from the model's
     * effective serving context (after local KV/sequence-bucket limits), not from a model-name guess
     * or its theoretical architecture window.
     */
    @Builder.Default
    private DecomposedPromptTier decomposedPromptTier = DecomposedPromptTier.AUTO;

    /**
     * Optional hard input-prompt budget in tokens for decomposed passes. This is a project/job
     * override and must fit within the selected model's executable context after output and wrapper
     * reserves. Invalid values are rejected before dispatch; prompts are never silently truncated to
     * satisfy this setting.
     */
    private Integer decomposedPromptBudgetTokens;

    /** Accept a loose string while keeping older project configuration files loadable. */
    @JsonSetter("decomposedPromptTier")
    public void setDecomposedPromptTierFromString(String value) {
        if (value == null || value.isBlank()) {
            this.decomposedPromptTier = DecomposedPromptTier.AUTO;
            return;
        }
        try {
            this.decomposedPromptTier = DecomposedPromptTier.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            this.decomposedPromptTier = DecomposedPromptTier.AUTO;
        }
    }

    /**
     * Configurable boundary policy for proposition-sized model work. This is independent from the
     * crawl's document/retrieval chunker because those chunks serve a different context-preservation
     * purpose.
     */
    @Builder.Default
    private PropositionAtomizationConfig decomposedAtomization =
            PropositionAtomizationConfig.defaults();

    /**
     * Drop proposals whose evidence quote cannot be located in the chunk. Leaving this on is what
     * makes a fabricated quote fatal rather than merely unverified.
     */
    @Builder.Default
    private boolean decomposedRequireEvidenceSpans = true;

    /** Run the claim/evidence-matching pass when the in-run graph already holds candidate claims. */
    @Builder.Default
    private boolean decomposedClaimMatching = true;

    /**
     * Minimum name-similarity for an in-run entity to be offered as a resolution candidate.
     * Lower values widen recall (the model still has to pick); higher values keep the ballot short.
     */
    @Builder.Default
    private double decomposedCandidateMinScore = 0.55;

    /**
     * How the entity-partition pass groups subjects and looks for their evidence.
     *
     * <p>Never null: the pass runs on every crawl that has a graph to write into, so it always has
     * a policy, and a null here would only move the decision to whichever caller forgot to check.
     * The defaults reproduce the stock policies exactly.</p>
     */
    @Builder.Default
    private PartitionPassConfig partition = PartitionPassConfig.defaults();

    /** Null-safe atomization settings for legacy configuration files. */
    public PropositionAtomizationConfig getDecomposedAtomization() {
        return decomposedAtomization == null
                ? PropositionAtomizationConfig.defaults() : decomposedAtomization;
    }

    /** Null-safe tier for legacy JSON and programmatic callers. */
    public DecomposedPromptTier getDecomposedPromptTier() {
        return decomposedPromptTier == null ? DecomposedPromptTier.AUTO : decomposedPromptTier;
    }

    /** The partition settings, defaulted rather than null for a config deserialised without them. */
    public PartitionPassConfig getPartition() {
        return partition == null ? PartitionPassConfig.defaults() : partition;
    }
}
