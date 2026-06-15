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

package ai.kompile.app.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for Contextual RAG (Retrieval-Augmented Generation).
 *
 * <p>Based on the Contextual Retrieval approach from Anthropic's research,
 * this configuration enables LLM-based chunk enrichment where each chunk
 * is augmented with contextual information explaining where it fits within
 * the source document.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li><b>Chunk Contextualization</b>: LLM generates descriptions explaining each chunk's context</li>
 *   <li><b>Source Attribution</b>: Enhanced metadata tracking for citations</li>
 *   <li><b>Document Summary</b>: Optional document-level summaries for context</li>
 * </ul>
 *
 * <p>Reference: <a href="https://github.com/Abhay-404/Eternal-Contextual-RAG">Eternal-Contextual-RAG</a></p>
 *
 * <p>Persisted to ~/.kompile/config/contextual-rag-config.json</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContextualRagConfig {

    // ═══════════════════════════════════════════════════════════════════════════
    // CHUNK CONTEXTUALIZATION SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Enable contextual chunk enrichment.
     * When enabled, each chunk is processed by an LLM to add contextual descriptions.
     * This significantly improves retrieval quality but increases indexing time and cost.
     */
    private Boolean enabled;

    /**
     * The LLM provider to use for chunk contextualization.
     * Uses "default" for the active provider, or any registered provider ID.
     */
    private String llmProvider;

    /**
     * The specific model to use for contextualization.
     * If null, uses the provider's default model.
     */
    private String llmModel;

    /**
     * Temperature for LLM generation (0.0 - 1.0).
     * Lower values produce more deterministic output.
     * Recommended: 0.0 - 0.3 for consistent contextualization.
     */
    private Double temperature;

    /**
     * Maximum tokens for context generation.
     * Controls the length of generated contextual descriptions.
     */
    private Integer maxContextTokens;

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Custom prompt template for chunk contextualization.
     * Placeholders: {document_title}, {document_summary}, {chunk_text}, {chunk_index}, {total_chunks}
     * If null, uses the default prompt.
     */
    private String contextPromptTemplate;

    /**
     * Whether to include document summary in the context prompt.
     * Generates a summary of the entire document to provide broader context.
     */
    private Boolean includeDocumentSummary;

    /**
     * Maximum length of document summary in tokens.
     */
    private Integer documentSummaryMaxTokens;

    /**
     * Whether to include surrounding chunks for additional context.
     */
    private Boolean includeSurroundingChunks;

    /**
     * Number of chunks before/after to include for context (if includeSurroundingChunks is true).
     */
    private Integer surroundingChunksWindow;

    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE ATTRIBUTION SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Enable enhanced source attribution.
     * Adds detailed source information to each chunk for citations.
     */
    private Boolean sourceAttributionEnabled;

    /**
     * Format for citation references.
     * Options: filename, path, url, custom
     */
    private String citationFormat;

    /**
     * Custom citation template (when citationFormat is 'custom').
     * Placeholders: {filename}, {path}, {url}, {page}, {chunk_index}
     */
    private String customCitationTemplate;

    /**
     * Whether to include page numbers in citations (when available).
     */
    private Boolean includePageNumbers;

    // ═══════════════════════════════════════════════════════════════════════════
    // BATCH PROCESSING SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Batch size for LLM contextualization calls.
     * Larger batches may be more efficient but use more memory.
     */
    private Integer batchSize;

    /**
     * Maximum concurrent LLM requests.
     * Controls parallelism during chunk enrichment.
     */
    private Integer maxConcurrentRequests;

    /**
     * Timeout for individual LLM requests in seconds.
     */
    private Integer requestTimeoutSeconds;

    /**
     * Number of retries for failed LLM requests.
     */
    private Integer maxRetries;

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHING SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Enable caching of contextualized chunks.
     * Avoids re-processing identical chunks on re-indexing.
     */
    private Boolean cachingEnabled;

    /**
     * Cache directory path.
     * If null, uses ~/.kompile/cache/contextual-chunks/
     */
    private String cachePath;

    /**
     * Cache TTL in days. Set to 0 for no expiration.
     */
    private Integer cacheTtlDays;

    // ═══════════════════════════════════════════════════════════════════════════
    // FALLBACK SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Whether to fall back to original chunk if contextualization fails.
     * If false, indexing will fail on LLM errors.
     */
    private Boolean fallbackOnError;

    /**
     * Minimum confidence score for web search fallback (0.0 - 1.0).
     * If retrieval confidence is below this threshold, trigger web search.
     * Set to 0 to disable web fallback.
     */
    private Double webSearchFallbackThreshold;

    /**
     * Creates default configuration.
     */
    public static ContextualRagConfig defaults() {
        return ContextualRagConfig.builder()
                // Chunk contextualization - disabled by default (opt-in feature)
                .enabled(false)
                .llmProvider("default")
                .llmModel(null)
                .temperature(0.1)
                .maxContextTokens(150)
                // Prompt configuration
                .contextPromptTemplate(null) // Uses default prompt
                .includeDocumentSummary(true)
                .documentSummaryMaxTokens(500)
                .includeSurroundingChunks(false)
                .surroundingChunksWindow(1)
                // Source attribution
                .sourceAttributionEnabled(true)
                .citationFormat("filename")
                .customCitationTemplate(null)
                .includePageNumbers(true)
                // Batch processing
                .batchSize(10)
                .maxConcurrentRequests(5)
                .requestTimeoutSeconds(30)
                .maxRetries(3)
                // Caching
                .cachingEnabled(true)
                .cachePath(null) // Uses default path
                .cacheTtlDays(30)
                // Fallback
                .fallbackOnError(true)
                .webSearchFallbackThreshold(0.0) // Disabled by default
                .build();
    }

    /**
     * Default prompt template for chunk contextualization.
     * Based on the Contextual Retrieval approach.
     */
    public static String getDefaultPromptTemplate() {
        return """
            <document>
            {document_summary}
            </document>

            Here is the chunk we want to situate within the whole document:
            <chunk>
            {chunk_text}
            </chunk>

            This is chunk {chunk_index} of {total_chunks} from the document "{document_title}".

            Please give a short, succinct context to situate this chunk within the overall document
            for the purposes of improving search retrieval of the chunk.
            Answer only with the succinct context and nothing else.
            """;
    }

    /**
     * Merges non-null values from another config into this one.
     */
    public ContextualRagConfig merge(ContextualRagConfig other) {
        if (other == null) {
            return this;
        }
        return ContextualRagConfig.builder()
                // Chunk contextualization
                .enabled(other.enabled != null ? other.enabled : this.enabled)
                .llmProvider(other.llmProvider != null ? other.llmProvider : this.llmProvider)
                .llmModel(other.llmModel != null ? other.llmModel : this.llmModel)
                .temperature(other.temperature != null ? other.temperature : this.temperature)
                .maxContextTokens(other.maxContextTokens != null ? other.maxContextTokens : this.maxContextTokens)
                // Prompt configuration
                .contextPromptTemplate(other.contextPromptTemplate != null ? other.contextPromptTemplate : this.contextPromptTemplate)
                .includeDocumentSummary(other.includeDocumentSummary != null ? other.includeDocumentSummary : this.includeDocumentSummary)
                .documentSummaryMaxTokens(other.documentSummaryMaxTokens != null ? other.documentSummaryMaxTokens : this.documentSummaryMaxTokens)
                .includeSurroundingChunks(other.includeSurroundingChunks != null ? other.includeSurroundingChunks : this.includeSurroundingChunks)
                .surroundingChunksWindow(other.surroundingChunksWindow != null ? other.surroundingChunksWindow : this.surroundingChunksWindow)
                // Source attribution
                .sourceAttributionEnabled(other.sourceAttributionEnabled != null ? other.sourceAttributionEnabled : this.sourceAttributionEnabled)
                .citationFormat(other.citationFormat != null ? other.citationFormat : this.citationFormat)
                .customCitationTemplate(other.customCitationTemplate != null ? other.customCitationTemplate : this.customCitationTemplate)
                .includePageNumbers(other.includePageNumbers != null ? other.includePageNumbers : this.includePageNumbers)
                // Batch processing
                .batchSize(other.batchSize != null ? other.batchSize : this.batchSize)
                .maxConcurrentRequests(other.maxConcurrentRequests != null ? other.maxConcurrentRequests : this.maxConcurrentRequests)
                .requestTimeoutSeconds(other.requestTimeoutSeconds != null ? other.requestTimeoutSeconds : this.requestTimeoutSeconds)
                .maxRetries(other.maxRetries != null ? other.maxRetries : this.maxRetries)
                // Caching
                .cachingEnabled(other.cachingEnabled != null ? other.cachingEnabled : this.cachingEnabled)
                .cachePath(other.cachePath != null ? other.cachePath : this.cachePath)
                .cacheTtlDays(other.cacheTtlDays != null ? other.cacheTtlDays : this.cacheTtlDays)
                // Fallback
                .fallbackOnError(other.fallbackOnError != null ? other.fallbackOnError : this.fallbackOnError)
                .webSearchFallbackThreshold(other.webSearchFallbackThreshold != null ? other.webSearchFallbackThreshold : this.webSearchFallbackThreshold)
                .build();
    }
}
