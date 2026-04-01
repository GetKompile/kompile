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
package ai.kompile.knowledgegraph.builder.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Records the full details of an LLM extraction operation.
 * Provides complete transparency by storing prompts, responses, and results.
 */
@Entity
@Table(name = "extraction_logs", indexes = {
    @Index(name = "idx_el_job", columnList = "extraction_job_id"),
    @Index(name = "idx_el_chunk", columnList = "chunk_id"),
    @Index(name = "idx_el_document", columnList = "document_id"),
    @Index(name = "idx_el_created", columnList = "created_at"),
    @Index(name = "idx_el_success", columnList = "success")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionLogRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * The extraction job this log belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "extraction_job_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ExtractionJob job;

    /**
     * ID of the chunk being processed.
     */
    @Column(name = "chunk_id", length = 255)
    private String chunkId;

    /**
     * ID of the source document.
     */
    @Column(name = "document_id", length = 255)
    private String documentId;

    // ==================== Full LLM Interaction (Transparency) ====================

    /**
     * Complete prompt text sent to the LLM.
     * This is critical for transparency and debugging.
     */
    @Column(name = "prompt_text", nullable = false, columnDefinition = "TEXT")
    private String promptText;

    /**
     * Complete response text from the LLM.
     * Null if the request failed.
     */
    @Column(name = "response_text", columnDefinition = "TEXT")
    private String responseText;

    /**
     * Input chunk text that was processed.
     */
    @Column(name = "input_text", columnDefinition = "TEXT")
    private String inputText;

    // ==================== Parsed Results ====================

    /**
     * Parsed entities as JSON array.
     */
    @Column(name = "parsed_entities_json", columnDefinition = "TEXT")
    private String parsedEntitiesJson;

    /**
     * Parsed relationships as JSON array.
     */
    @Column(name = "parsed_relationships_json", columnDefinition = "TEXT")
    private String parsedRelationshipsJson;

    /**
     * Number of entities extracted from this chunk.
     */
    @Column(name = "entities_count")
    @Builder.Default
    private Integer entitiesCount = 0;

    /**
     * Number of relationships extracted from this chunk.
     */
    @Column(name = "relationships_count")
    @Builder.Default
    private Integer relationshipsCount = 0;

    // ==================== Model Details ====================

    /**
     * LLM provider used (e.g., "openai", "anthropic", "ollama").
     */
    @Column(name = "model_provider", length = 64)
    private String modelProvider;

    /**
     * Specific model name used (e.g., "gpt-4o", "claude-3-sonnet").
     */
    @Column(name = "model_name", length = 128)
    private String modelName;

    // ==================== Performance Metrics ====================

    /**
     * Processing latency in milliseconds.
     */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /**
     * Number of tokens in the prompt.
     */
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    /**
     * Number of tokens in the response.
     */
    @Column(name = "response_tokens")
    private Integer responseTokens;

    // ==================== Status ====================

    /**
     * Whether the extraction was successful.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean success = true;

    /**
     * Error message if extraction failed.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * When this extraction occurred.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Create a successful log record.
     */
    public static ExtractionLogRecord success(
            ExtractionJob job,
            String chunkId,
            String documentId,
            String inputText,
            String promptText,
            String responseText,
            String parsedEntitiesJson,
            String parsedRelationshipsJson,
            int entitiesCount,
            int relationshipsCount,
            String modelProvider,
            String modelName,
            long latencyMs,
            Integer promptTokens,
            Integer responseTokens) {
        return ExtractionLogRecord.builder()
                .job(job)
                .chunkId(chunkId)
                .documentId(documentId)
                .inputText(inputText)
                .promptText(promptText)
                .responseText(responseText)
                .parsedEntitiesJson(parsedEntitiesJson)
                .parsedRelationshipsJson(parsedRelationshipsJson)
                .entitiesCount(entitiesCount)
                .relationshipsCount(relationshipsCount)
                .modelProvider(modelProvider)
                .modelName(modelName)
                .latencyMs(latencyMs)
                .promptTokens(promptTokens)
                .responseTokens(responseTokens)
                .success(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Create a failed log record.
     */
    public static ExtractionLogRecord failure(
            ExtractionJob job,
            String chunkId,
            String documentId,
            String inputText,
            String promptText,
            String errorMessage,
            String modelProvider,
            String modelName,
            long latencyMs) {
        return ExtractionLogRecord.builder()
                .job(job)
                .chunkId(chunkId)
                .documentId(documentId)
                .inputText(inputText)
                .promptText(promptText)
                .errorMessage(errorMessage)
                .modelProvider(modelProvider)
                .modelName(modelName)
                .latencyMs(latencyMs)
                .success(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Returns total entities and relationships extracted.
     */
    public int getTotalExtracted() {
        return (entitiesCount != null ? entitiesCount : 0) +
               (relationshipsCount != null ? relationshipsCount : 0);
    }
}
