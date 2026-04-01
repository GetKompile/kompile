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

/**
 * Concurrent content extraction framework for document processing pipelines.
 *
 * <h2>Overview</h2>
 * <p>This package provides a unified framework for extracting different types of
 * content from documents concurrently. Multiple extractor types can run in parallel
 * on the same document set, allowing for efficient processing of:</p>
 *
 * <ul>
 *   <li><b>Chunking</b> - Breaking documents into smaller pieces for embedding</li>
 *   <li><b>Entity Extraction</b> - Identifying named entities (people, places, organizations)</li>
 *   <li><b>Relationship Extraction</b> - Finding relationships between entities</li>
 *   <li><b>Concept Extraction</b> - Identifying key concepts and themes</li>
 *   <li><b>Table Extraction</b> - Extracting structured tables from documents</li>
 *   <li><b>Fact Extraction</b> - Extracting fact statements</li>
 *   <li><b>Code Block Extraction</b> - Finding and categorizing code snippets</li>
 *   <li><b>Citation Extraction</b> - Extracting references and citations</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Documents
 *     │
 *     ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │           ConcurrentExtractionOrchestrator                  │
 * │  ┌──────────────────┐  ┌──────────────────────────────────┐ │
 * │  │ Chunking Workers │  │ Structured Output Workers        │ │
 * │  │   (4-8 threads)  │  │ ┌────────────┐ ┌──────────────┐  │ │
 * │  │                  │  │ │Entity (2T) │ │Concept (2T)  │  │ │
 * │  │                  │  │ └────────────┘ └──────────────┘  │ │
 * │  │                  │  │ ┌────────────┐ ┌──────────────┐  │ │
 * │  │                  │  │ │Table (1T)  │ │ Fact (2T)    │  │ │
 * │  └────────┬─────────┘  │ └────────────┘ └──────────────┘  │ │
 * │           │            └───────────────┬──────────────────┘ │
 * │           └──────────────┬─────────────┘                    │
 * │                          ▼                                   │
 * │                    Result Merger                             │
 * └─────────────────────────────────────────────────────────────┘
 *     │
 *     ▼
 * Combined Results (chunks + structured items)
 * </pre>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link ai.kompile.app.core.extraction.ContentExtractor} -
 *       Interface for all content extractors</li>
 *   <li>{@link ai.kompile.app.core.extraction.ConcurrentExtractionOrchestrator} -
 *       Orchestrates concurrent execution of multiple extractors</li>
 *   <li>{@link ai.kompile.app.core.extraction.ExtractionResult} -
 *       Result of extraction operations (chunks and/or structured items)</li>
 *   <li>{@link ai.kompile.app.core.extraction.StructuredItem} -
 *       Unified container for extracted structured content</li>
 *   <li>{@link ai.kompile.app.core.extraction.ChunkingExtractor} -
 *       Adapter that wraps TextChunker as a ContentExtractor</li>
 *   <li>{@link ai.kompile.app.core.extraction.AbstractStructuredExtractor} -
 *       Base class for structured content extractors</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create orchestrator with configuration
 * ConcurrentExtractionOrchestrator orchestrator = new ConcurrentExtractionOrchestrator(
 *     ConcurrentExtractionOrchestrator.OrchestratorConfig.builder()
 *         .chunkingThreads(4)
 *         .entityExtractionThreads(2)
 *         .conceptExtractionThreads(2)
 *         .build()
 * );
 *
 * // Register extractors
 * orchestrator.registerExtractor(new ChunkingExtractor(chunker));
 * orchestrator.registerExtractor(new LLMEntityExtractor(llmInvoker));
 * orchestrator.registerExtractor(conceptExtractor);
 *
 * // Set progress callback
 * orchestrator.setProgressCallback(progress -> {
 *     System.out.printf("Extractor %s: %d%% complete%n",
 *         progress.extractorName(), progress.percentComplete());
 * });
 *
 * // Run concurrent extraction
 * CombinedExtractionResult result = orchestrator.extractAll(documents, options);
 *
 * // Use results
 * List<RetrievedDoc> chunks = result.chunks();
 * List<StructuredItem> entities = result.getEntities();
 * List<StructuredItem> concepts = result.getConcepts();
 * }</pre>
 *
 * <h2>Creating Custom Extractors</h2>
 * <p>To create a custom extractor, extend {@link ai.kompile.app.core.extraction.AbstractStructuredExtractor}:</p>
 * <pre>{@code
 * public class MyCustomExtractor extends AbstractStructuredExtractor {
 *     @Override
 *     public String getName() {
 *         return "my-custom-extractor";
 *     }
 *
 *     @Override
 *     public ExtractorType getType() {
 *         return ExtractorType.CUSTOM;
 *     }
 *
 *     @Override
 *     protected List<StructuredItem> doExtract(RetrievedDoc document, Map<String, Object> options) {
 *         // Extraction logic here
 *         return items;
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All extractors must be thread-safe for concurrent execution. The orchestrator
 * manages worker threads and ensures proper synchronization of results.</p>
 *
 * @see ai.kompile.app.core.extraction.ContentExtractor
 * @see ai.kompile.app.core.extraction.ConcurrentExtractionOrchestrator
 */
package ai.kompile.app.core.extraction;
