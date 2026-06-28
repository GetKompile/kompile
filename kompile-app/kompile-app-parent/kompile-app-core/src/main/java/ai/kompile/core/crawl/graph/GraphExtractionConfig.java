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

import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
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

    /** Whether graph extraction is enabled (default: true) */
    @Builder.Default
    private boolean enabled = true;

    /** Named schema preset ID to load entity/relationship types from (e.g., "fpna-cpg-channel-v1").
     *  When set, the server resolves the preset and populates entityTypes/relationshipTypes. */
    private String schemaPresetId;

    /** Entity types to focus extraction on (empty = extract all discovered types) */
    @Builder.Default
    private List<String> entityTypes = new ArrayList<>();

    /** Relationship types to focus extraction on (empty = extract all) */
    @Builder.Default
    private List<String> relationshipTypes = new ArrayList<>();

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

    /** Minimum confidence threshold for keeping extracted entities (0.0 - 1.0) */
    @Builder.Default
    private double minConfidence = 0.5;

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
}
