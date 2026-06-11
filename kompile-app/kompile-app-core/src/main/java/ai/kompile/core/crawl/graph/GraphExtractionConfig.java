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
}
