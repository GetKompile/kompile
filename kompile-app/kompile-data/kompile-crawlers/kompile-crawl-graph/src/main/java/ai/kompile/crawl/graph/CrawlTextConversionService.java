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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Text conversion and document-copy utilities for the unified crawl pipeline.
 *
 * <p>Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 * Handles:</p>
 * <ul>
 *   <li>Converting raw loaded documents to normalized plain text
 *       ({@link #convertDocumentText})</li>
 *   <li>Producing slim document copies for background graph extractors
 *       ({@link #copyDocumentsForBackgroundGraph})</li>
 * </ul>
 */
@Component
class CrawlTextConversionService {

    private static final Logger log = LoggerFactory.getLogger(CrawlTextConversionService.class);

    /**
     * Max text length retained in background graph copies. Rule-based extractors
     * only need metadata and a small text prefix for heuristic matching; keeping
     * full text wastes heap proportional to total corpus size.
     */
    private static final int BACKGROUND_GRAPH_TEXT_LIMIT = 500;

    /** Keys needed by background graph extractors — everything else is dropped to save heap. */
    private static final String[] BACKGROUND_GRAPH_META_KEYS = {
            GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE,
            GraphConstants.META_CONTENT_TYPE, GraphConstants.META_FILE_NAME,
            GraphConstants.META_LOADER, GraphConstants.META_SOURCE_TYPE,
            GraphConstants.META_VLM_PROCESSED,
            // META_DOCUMENT_TYPE ("documentType") must be included so TikaGenericGraphExtractor
            // resolveEntityType() returns CSV_DOCUMENT/TSV_DOCUMENT/MARKDOWN_DOCUMENT/etc.
            // instead of the generic ENTITY_DOCUMENT fallback.  The legacy "document_type" key
            // is a dead alias — nothing sets it — replaced here with the correct constant.
            GraphConstants.META_DOCUMENT_TYPE,
            // Table metadata keys: required for the rule-based GRAPH_PREP extractor to create
            // proper TABLE, COLUMN, and cell-graph entities from CSV/TSV/Markdown table documents.
            GraphConstants.META_TABLE_ROW_COUNT, GraphConstants.META_TABLE_COLUMN_COUNT,
            GraphConstants.META_TABLE_HEADERS, GraphConstants.META_TABLE_GRAPH
    };

    // ── Dependencies ────────────────────────────────────────────────────────

    private final PipelineStepTracker pipelineStepTracker;
    private final CrawlDocumentTracker documentTracker;

    CrawlTextConversionService(PipelineStepTracker pipelineStepTracker,
                               CrawlDocumentTracker documentTracker) {
        this.pipelineStepTracker = pipelineStepTracker;
        this.documentTracker = documentTracker;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Converts documents to normalized plain text, with VLM-aware lighter-touch
     * normalization for documents processed by visual language models.
     *
     * @param documents source documents
     * @param job       the running crawl job (for progress recording)
     * @return normalized, non-blank documents (blanks are dropped)
     */
    List<Document> convertDocumentText(List<Document> documents, UnifiedCrawlJob job) {
        if (documents == null || documents.isEmpty()) return List.of();

        // Text normalization is pure CPU regex work with no shared state.
        // Use parallelStream for batches >= 10 — regex normalization is CPU-heavy
        // enough that fork-join overhead is amortized even at small batch sizes.
        if (documents.size() >= 10) {
            AtomicInteger counter = new AtomicInteger(0);
            List<Document> converted = documents.parallelStream()
                    .map(doc -> {
                        int idx = counter.incrementAndGet();
                        if (idx % 100 == 0) {
                            documentTracker.recordEvent(job, "CONVERTING", "INFO",
                                    "Converted " + idx + "/" + documents.size() + " document(s)", null);
                        }
                        String text = doc.getText();
                        if (text == null) return null;
                        boolean isVlmContent = doc.getMetadata() != null
                                && Boolean.TRUE.equals(doc.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
                        String plainText = isVlmContent
                                ? CrawlTextNormalizer.normalizeStructuredText(text)
                                : CrawlTextNormalizer.normalizeText(text);
                        if (plainText.isBlank()) return null;
                        Document convertedDoc = new Document(plainText);
                        convertedDoc.getMetadata().putAll(doc.getMetadata());
                        convertedDoc.getMetadata().put("converted", true);
                        return convertedDoc;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            documentTracker.recordEvent(job, "CONVERTING", "INFO",
                    "Text conversion complete", converted.size() + " document(s)");
            return converted;
        }

        // Sequential path for small batches (< 10 docs)
        List<Document> converted = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            if (i % 25 == 0) {
                documentTracker.recordEvent(job, "CONVERTING", "INFO",
                        "Converted " + i + "/" + documents.size() + " document(s)", null);
            }
            String text = doc.getText();
            if (text == null) continue;

            boolean isVlmContent = doc.getMetadata() != null
                    && Boolean.TRUE.equals(doc.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
            String plainText = isVlmContent
                    ? CrawlTextNormalizer.normalizeStructuredText(text)
                    : CrawlTextNormalizer.normalizeText(text);

            if (plainText.isBlank()) continue;

            Document convertedDoc = new Document(plainText);
            convertedDoc.getMetadata().putAll(doc.getMetadata());
            convertedDoc.getMetadata().put("converted", true);
            converted.add(convertedDoc);
        }
        documentTracker.recordEvent(job, "CONVERTING", "INFO",
                "Text conversion complete", converted.size() + " document(s)");
        return converted;
    }

    /**
     * Produces slim document copies for background graph extractors.
     *
     * <p>Rule-based extractors only need metadata and a small text prefix for
     * heuristic matching. Keeping full text wastes heap proportional to total
     * corpus size.</p>
     *
     * @param documents source documents
     * @return slim copies (text truncated to {@value #BACKGROUND_GRAPH_TEXT_LIMIT} chars,
     *         only routing/identification metadata keys retained)
     */
    List<Document> copyDocumentsForBackgroundGraph(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> copy = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            if (doc == null) {
                continue;
            }
            // Slim metadata copy — only routing/identification keys, not full maps
            // with formula graph JSON, table graphs, etc. that can be kilobytes each
            Map<String, Object> srcMeta = doc.getMetadata();
            Map<String, Object> metadata = new LinkedHashMap<>(BACKGROUND_GRAPH_META_KEYS.length);
            if (srcMeta != null) {
                for (String key : BACKGROUND_GRAPH_META_KEYS) {
                    Object val = srcMeta.get(key);
                    if (val != null) {
                        metadata.put(key, val);
                    }
                }
                // EmailGraphExtractor (a background extractor) keys off the full email.*/gmail.*
                // namespace — sender, recipients, subject, message-id, in-reply-to, references,
                // attachment names — to build SENT_BY/SENT_TO/CC_TO/HAS_ATTACHMENT/REPLIED_TO edges.
                // These are small scalar headers, so retaining the namespace on the slim copy keeps
                // email graph extraction working without the heavy maps the slimming targets.
                for (Map.Entry<String, Object> e : srcMeta.entrySet()) {
                    String key = e.getKey();
                    if (e.getValue() != null && key != null
                            && (key.startsWith("email.") || key.startsWith("gmail."))) {
                        metadata.put(key, e.getValue());
                    }
                }
            }
            String text = doc.getText();
            if (text != null && text.length() > BACKGROUND_GRAPH_TEXT_LIMIT) {
                text = text.substring(0, BACKGROUND_GRAPH_TEXT_LIMIT);
            }
            copy.add(new Document(text != null ? text : "", metadata));
        }
        return copy;
    }
}
