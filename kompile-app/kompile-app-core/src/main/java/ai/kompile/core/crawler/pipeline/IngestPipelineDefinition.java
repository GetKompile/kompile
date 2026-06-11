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

package ai.kompile.core.crawler.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Defines how a specific class of documents should be processed during crawl ingestion.
 *
 * <p>Each pipeline definition specifies the loader, chunker, embedding model,
 * and additional processing options. A single crawl job can have multiple
 * pipeline definitions, with a {@link ContentRouteRule} determining which
 * pipeline handles each discovered item.</p>
 *
 * <h3>Example pipeline definitions for a web crawl:</h3>
 * <ul>
 *   <li><b>"html-text"</b> — Web/HTML loader → recursive-character chunker → default embeddings</li>
 *   <li><b>"pdf-vlm"</b> — PDF+VLM loader → table-aware chunker → default embeddings</li>
 *   <li><b>"image-vlm"</b> — VLM image loader → no chunking → CLIP embeddings</li>
 *   <li><b>"code"</b> — Tika loader → code-aware chunker → code embeddings</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestPipelineDefinition {

    /** Unique ID for this pipeline definition within a crawl config */
    private String pipelineId;

    /** Human-readable name */
    private String displayName;

    /** The pipeline type — categorizes the kind of processing */
    @Builder.Default
    private PipelineType pipelineType = PipelineType.STANDARD_TEXT;

    // ---- Processing components (null = use defaults) ----

    /** Name of the DocumentLoader to use (null = auto-detect from content type) */
    private String loaderName;

    /** Name of the TextChunker to use (null = auto-detect / use default) */
    private String chunkerName;

    /** Name or ID of the EmbeddingModel to use (null = use default) */
    private String embeddingModelName;

    /**
     * Explicit language code for this pipeline (ISO 639-1, e.g. "en", "de").
     * When set, overrides auto-detection during ingest and is passed to
     * the chunker and embedding model selection logic.
     * When null, language is taken from the CrawlItem or auto-detected from content.
     */
    private String language;

    // ---- Chunking options ----

    /** Chunk size in characters/tokens (null = use chunker default) */
    private Integer chunkSize;

    /** Chunk overlap in characters/tokens (null = use chunker default) */
    private Integer chunkOverlap;

    // ---- Pipeline behavior ----

    /** Whether to skip embedding and only index to the keyword store */
    @Builder.Default
    private boolean keywordOnly = false;

    /** Whether to enable OCR/VLM processing for visual documents */
    @Builder.Default
    private boolean enableVlm = false;

    /** Whether to enable graph extraction for this pipeline */
    @Builder.Default
    private boolean enableGraphExtraction = false;

    /** Processing mode: INPROCESS, SUBPROCESS, or AUTO */
    private String processingMode;

    /** Target collection name for documents processed by this pipeline (null = use crawl default) */
    private String collectionName;

    /**
     * Additional processing options passed to the pipeline.
     * These are forwarded as chunker options, loader options, etc.
     * <p>
     * Common keys:
     * <ul>
     *   <li>{@code vlmModel} — VLM model ID for OCR pipelines</li>
     *   <li>{@code ocrLanguage} — OCR language hint</li>
     *   <li>{@code preserveTables} — whether to preserve table structure</li>
     *   <li>{@code maxPages} — max pages to process per document</li>
     *   <li>{@code codeLanguage} — programming language hint for code chunkers</li>
     *   <li>{@code language} — natural language hint (ISO 639-1) for chunking and embedding</li>
     * </ul>
     */
    @Builder.Default
    private Map<String, Object> options = new HashMap<>();

    /**
     * Standard pipeline types. Custom types can use {@link PipelineType#CUSTOM}
     * with a type name in {@link #options} under key "customPipelineType".
     */
    public enum PipelineType {
        /** Standard text extraction → chunking → embedding */
        STANDARD_TEXT,
        /** Vision Language Model processing for images and scanned documents */
        VLM,
        /** OCR + text extraction for scanned documents */
        OCR,
        /** Code-aware processing with AST-based chunking */
        CODE,
        /** Table-aware processing that preserves tabular structure */
        TABLE_AWARE,
        /** Keyword-only indexing, no embeddings */
        KEYWORD_ONLY,
        /** Custom pipeline type — specify details in options */
        CUSTOM
    }
}
