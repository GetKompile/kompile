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

import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Routes documents by content type and manages document/snippet graph node registration.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Content-type routing (text, table, slide, image, etc.)</li>
 *   <li>PDF classification and routing (AUTO/FORCE_VLM/FORCE_TEXT)</li>
 *   <li>DOCUMENT and SNIPPET graph node registration</li>
 *   <li>Table promotion to graph nodes</li>
 *   <li>Formula/table graph JSON persistence</li>
 * </ul>
 */
@Component
class ContentTypeRouter {

    private static final Logger log = LoggerFactory.getLogger(ContentTypeRouter.class);

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private CrawlIndexTrackingCallback crawlIndexTrackingCallback;

    @Autowired(required = false)
    private ai.kompile.core.loaders.PdfContentClassifier pdfContentClassifier;

    @Autowired
    private GraphPersistenceHelper graphPersistenceHelper;

    @Autowired
    private CrawlDocumentTracker documentTracker;

    // ═══════════════════════════════════════════════════════════════════
    // CONTENT-TYPE ROUTING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Routes documents by content type, matching the logic in DocumentIngestService.
     * Registers DOCUMENT graph nodes, promotes tables to graph nodes, handles formula
     * graphs, and filters images/charts from the text pipeline.
     */
    List<Document> routeByContentType(UnifiedCrawlJob job, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return List.of();
        if (job != null && isCancelled(job)) {
            return List.of();
        }

        String jobId = job != null ? job.getJobId() : null;

        // Register DOCUMENT graph nodes for unique source files
        registerDocumentNodes(job, documents);

        List<Document> result = new ArrayList<>();
        // Collect DB-write tasks (persistGraphJson, promoteTableToGraphNode, persistFormulaGraph)
        // to execute in parallel after classification — these are I/O-bound and independent
        // of the result list construction
        List<Runnable> deferredDbWrites = new ArrayList<>();
        Long factSheetId = jobFactSheetId(job);
        String crawlSource = "crawl:" + jobId;
        int totalDocs = documents.size();
        // For large batches, reduce recordDocumentProgress frequency to avoid
        // per-document synchronized+Instant.now() overhead in pure classification
        boolean recordEveryDoc = totalDocs <= 50;

        for (int docIdx = 0; docIdx < totalDocs; docIdx++) {
            Document doc = documents.get(docIdx);
            if (job != null && isCancelled(job)) {
                break;
            }
            int routedCountBefore = result.size();
            Map<String, Object> meta = doc.getMetadata();
            String contentType = meta != null ? (String) meta.get(GraphConstants.META_CONTENT_TYPE) : null;
            if (recordEveryDoc || docIdx % 50 == 0) {
                documentTracker.recordDocumentProgress(job, doc, "ROUTING", "RUNNING", 0, 0, 0,
                        "Routing content type " + (contentType != null ? contentType : "text"),
                        null, null, false);
            }

            // Extract common graph JSON values once per document
            String tableGraphJson = meta != null && meta.get(GraphConstants.META_TABLE_GRAPH) instanceof String
                    ? (String) meta.get(GraphConstants.META_TABLE_GRAPH) : null;
            boolean hasTableGraphJson = tableGraphJson != null && !tableGraphJson.isBlank();
            String formulaGraphJson = meta != null && meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                    ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
            boolean hasFormulaGraphJson = formulaGraphJson != null && !formulaGraphJson.isBlank();
            // Pre-resolve source path once per document — used by persistGraphJson in multiple branches
            String sourcePath = hasTableGraphJson ? resolveSourcePath(meta) : null;

            if (contentType == null || "text".equals(contentType)) {
                result.add(doc);
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
            } else if ("table".equals(contentType)) {
                // Use full table content for better embeddings when available
                String fullContent = meta.get("full_table_content") instanceof String
                        ? (String) meta.get("full_table_content") : null;
                if (fullContent != null && !fullContent.isBlank()) {
                    result.add(new Document(fullContent, meta));
                } else {
                    result.add(doc);
                }
                // Exactly ONE TABLE node per table: when a cell graph exists, persistGraphJson owns it
                // (it carries rowCount/columnCount/headers/fullTableContent + the cell graph). Only fall
                // back to promote when there is no cell graph, so the two paths never both emit a node.
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                } else {
                    deferredDbWrites.add(() -> promoteTableToGraphNode(doc, job, factSheetId, crawlSource));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
            } else if ("vlm_document".equals(contentType)) {
                // VLM docs with tables — promote tables, pass through for text pipeline
                Integer tableCount = meta.get(GraphConstants.META_TABLE_COUNT) instanceof Integer
                        ? (Integer) meta.get(GraphConstants.META_TABLE_COUNT) : 0;
                // Cell graph present → persistGraphJson owns the single TABLE node; otherwise promote.
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                } else if (tableCount > 0) {
                    deferredDbWrites.add(() -> promoteTableToGraphNode(doc, job, factSheetId, crawlSource));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
                result.add(doc);
            } else if ("formula_graph".equals(contentType)) {
                deferredDbWrites.add(() -> promoteTableToGraphNode(doc, job, factSheetId, crawlSource));
                deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                result.add(doc);
            } else if ("slide".equals(contentType) || "presentation".equals(contentType)) {
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                } else {
                    deferredDbWrites.add(() -> promoteTableToGraphNode(doc, job, factSheetId, crawlSource));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
                result.add(doc);
            } else if ("spreadsheet".equals(contentType)) {
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                } else {
                    deferredDbWrites.add(() -> promoteTableToGraphNode(doc, job, factSheetId, crawlSource));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
                result.add(doc);
            } else if ("image".equals(contentType)) {
                log.debug("[Job {}] Routing image document to graph-only extraction", jobId);
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
            } else if ("audio".equals(contentType) || "video".equals(contentType)) {
                log.debug("[Job {}] Routing {} document to graph-only extraction", jobId, contentType);
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
            } else if ("chart".equals(contentType)) {
                log.debug("[Job {}] Routing chart document to graph-only extraction with TABLE promotion", jobId);
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                } else {
                    deferredDbWrites.add(() -> promoteTableToGraphNode(doc, job, factSheetId, crawlSource));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
            } else {
                if (hasTableGraphJson) {
                    final String tgj = tableGraphJson;
                    final String sp = sourcePath;
                    deferredDbWrites.add(() -> persistGraphJson(job, tgj, sp, GraphConstants.META_TABLE_GRAPH));
                }
                if (hasFormulaGraphJson) {
                    deferredDbWrites.add(() -> persistFormulaGraph(job, doc));
                }
                result.add(doc);
            }
            if (recordEveryDoc || docIdx % 50 == 0 || docIdx == totalDocs - 1) {
                documentTracker.recordDocumentProgress(job, doc, "ROUTING", "COMPLETED", 0, 0, 0,
                        "Routed to " + (result.size() > routedCountBefore ? "text pipeline" : "graph-only pipeline"),
                        null, null, false);
            }
        }

        // Execute deferred DB writes in parallel — these are independent I/O operations
        if (!deferredDbWrites.isEmpty()) {
            deferredDbWrites.parallelStream().forEach(task -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("[Job {}] Deferred DB write failed: {}", jobId, e.getMessage());
                }
            });
        }

        return result;
    }

    /** Resolve source path from document metadata (common pattern across content-type routing). */
    String resolveSourcePath(Map<String, Object> meta) {
        if (meta == null) return null;
        return meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROCESSING ROUTE CONFIG
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Resolve the effective processing route config for a crawl job.
     * Uses per-request config if provided, otherwise falls back to global default.
     */
    ProcessingRouteConfig resolveProcessingRouteConfig(UnifiedCrawlJob job) {
        ProcessingRouteConfig perJob = job.getRequest().getProcessingRoute();
        if (perJob != null) {
            return perJob;
        }
        // Return a sensible default when no config is set
        return ProcessingRouteConfig.builder()
                .pdfRoutingMode(ProcessingRouteConfig.PdfRoutingMode.AUTO)
                .extractTablesFromTextPdfs(true)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // DYNAMIC PDF CLASSIFICATION AND ROUTING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Classify PDF documents and route them to the appropriate processing pipeline.
     *
     * <p>In AUTO mode, each PDF is inspected for embedded images:</p>
     * <ul>
     *   <li><b>Text-only PDFs</b>: Kept with their existing content type (text);
     *       tables are preserved via Tabula extraction by the downstream loader.</li>
     *   <li><b>Image-based PDFs</b>: Tagged as {@code vlm_document} so the downstream
     *       content-type router sends them through VLM processing.</li>
     *   <li><b>Mixed PDFs</b>: Tagged as {@code vlm_document} because any image
     *       content requires VLM for accurate extraction.</li>
     * </ul>
     *
     * <p>Non-PDF documents pass through unchanged.</p>
     */
    List<Document> classifyAndRoutePdfs(UnifiedCrawlJob job, List<Document> documents,
                                        ProcessingRouteConfig routeConfig) {
        if (pdfContentClassifier == null) {
            log.debug("[Job {}] No PdfContentClassifier available, skipping PDF classification", job.getJobId());
            return documents;
        }

        ProcessingRouteConfig.PdfRoutingMode mode = routeConfig.getPdfRoutingMode();
        if (mode == ProcessingRouteConfig.PdfRoutingMode.DISABLED) {
            return documents;
        }

        documentTracker.recordEvent(job, "PDF_CLASSIFICATION", "INFO",
                "Starting PDF classification", "mode=" + mode);

        int textOnlyCount = 0;
        int vlmCount = 0;
        int mixedCount = 0;
        int nonPdfCount = 0;
        int classifiedCount = 0;

        for (Document doc : documents) {
            if (isCancelled(job)) break;

            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;

            // Only classify PDF documents
            String source = getStringMeta(meta, "source");
            String fileName = getStringMeta(meta, "fileName");
            String filePath = source != null ? source : fileName;

            if (filePath == null || !filePath.toLowerCase().endsWith(".pdf")) {
                nonPdfCount++;
                continue;
            }

            // FORCE modes skip classification
            if (mode == ProcessingRouteConfig.PdfRoutingMode.FORCE_VLM) {
                meta.put(GraphConstants.META_CONTENT_TYPE, "vlm_document");
                meta.put("pdf_route", "force_vlm");
                vlmCount++;
                classifiedCount++;
                continue;
            }
            if (mode == ProcessingRouteConfig.PdfRoutingMode.FORCE_TEXT) {
                // Leave as text (don't set vlm_document)
                meta.put("pdf_route", "force_text");
                textOnlyCount++;
                classifiedCount++;
                continue;
            }

            // AUTO mode: classify by inspecting page resources
            java.io.File pdfFile = new java.io.File(filePath);
            if (!pdfFile.exists()) {
                log.debug("[Job {}] PDF file not found for classification: {}", job.getJobId(), filePath);
                nonPdfCount++;
                continue;
            }

            try {
                ai.kompile.core.loaders.PdfClassificationResult result = pdfContentClassifier.classify(pdfFile);
                classifiedCount++;

                meta.put("pdf_classification", result.contentType().name());
                meta.put("pdf_classification_time_ms", result.classificationTimeMs());
                meta.put("pdf_page_count", result.pageCount());
                meta.put("pdf_image_pages", result.imagePagesCount());
                meta.put("pdf_text_chars", result.textCharCount());

                switch (result.contentType()) {
                    case TEXT_ONLY:
                        // Keep as text — standard extraction + Tabula will handle tables
                        meta.put("pdf_route", "text_extraction");
                        if (routeConfig.isExtractTablesFromTextPdfs()) {
                            meta.put("extract_tables", true);
                        }
                        textOnlyCount++;
                        break;

                    case IMAGE_BASED:
                        // Route to VLM pipeline
                        meta.put(GraphConstants.META_CONTENT_TYPE, "vlm_document");
                        meta.put("pdf_route", "vlm");
                        vlmCount++;
                        break;

                    case MIXED:
                        // Any image content means we need VLM for that document
                        meta.put(GraphConstants.META_CONTENT_TYPE, "vlm_document");
                        meta.put("pdf_route", "vlm_mixed");
                        meta.put("pdf_image_page_indices", result.imagePageIndices().toString());
                        mixedCount++;
                        break;

                    default:
                        // UNKNOWN — leave as-is, standard pipeline handles it
                        meta.put("pdf_route", "unknown_fallback");
                        textOnlyCount++;
                        break;
                }
            } catch (Exception e) {
                log.warn("[Job {}] PDF classification failed for {}: {}", job.getJobId(), filePath, e.getMessage());
                meta.put("pdf_route", "classification_failed");
                textOnlyCount++;
            }
        }

        log.info("[Job {}] PDF classification complete: {} classified, {} text-only, {} VLM, {} mixed, {} non-PDF",
                job.getJobId(), classifiedCount, textOnlyCount, vlmCount, mixedCount, nonPdfCount);
        documentTracker.recordEvent(job, "PDF_CLASSIFICATION", "INFO",
                "PDF classification complete",
                classifiedCount + " classified: " + textOnlyCount + " text-only, " + vlmCount + " VLM, " +
                        mixedCount + " mixed, " + nonPdfCount + " non-PDF");

        return documents;
    }

    String getStringMeta(Map<String, Object> meta, String key) {
        Object val = meta.get(key);
        return val instanceof String ? (String) val : null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // GRAPH NODE REGISTRATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Registers DOCUMENT graph nodes for unique source files, creating the
     * SOURCE → DOCUMENT hierarchy in the knowledge graph.
     */
    void registerDocumentNodes(UnifiedCrawlJob job, List<Document> documents) {
        if (knowledgeGraphService == null) return;

        String jobId = job != null ? job.getJobId() : null;

        // Phase 1: Pre-collect unique source paths with their first-seen document.
        // This avoids iterating all documents when only unique source paths need DB writes.
        Map<String, Document> uniqueBySourcePath = new LinkedHashMap<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                    ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                    : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
            if (sourcePath == null) {
                String fallbackName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                        ? (String) meta.get(GraphConstants.META_FILE_NAME) : null;
                if (fallbackName != null) {
                    sourcePath = "unnamed:" + fallbackName;
                }
            }
            if (sourcePath != null) {
                uniqueBySourcePath.putIfAbsent(sourcePath, doc);
            }
        }

        // Phase 2: Register only unique source paths
        Long factSheetId = jobFactSheetId(job);
        String crawlSource = "crawl:" + jobId;
        Set<String> registeredSources = new HashSet<>(uniqueBySourcePath.size());
        for (Map.Entry<String, Document> entry : uniqueBySourcePath.entrySet()) {
            String sourcePath = entry.getKey();
            Document doc = entry.getValue();
            Map<String, Object> meta = doc.getMetadata();

            try {
                String fileName = meta.get("source_filename") instanceof String
                        ? (String) meta.get("source_filename")
                        : meta.get(GraphConstants.META_FILE_NAME) instanceof String ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                String loaderName = meta.get("loader_name") instanceof String
                        ? (String) meta.get("loader_name")
                        : meta.get(GraphConstants.META_LOADER) instanceof String ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                String contentPreview = doc.getText() != null && doc.getText().length() > 200
                        ? doc.getText().substring(0, 200) + "..." : doc.getText();

                Map<String, Object> docMeta = new LinkedHashMap<>(meta);
                docMeta.put(GraphConstants.META_LOADER, loaderName);
                docMeta.put("taskId", jobId);
                if (meta.get("file_extension") instanceof String) {
                    docMeta.put("fileExtension", meta.get("file_extension"));
                }

                knowledgeGraphService.addDocument(
                        crawlSource, jobId, sourceType,
                        sourcePath, fileName, contentPreview, docMeta, factSheetId);
                registeredSources.add(sourcePath);
            } catch (Exception e) {
                log.debug("Failed to register DOCUMENT node for '{}': {}", sourcePath, e.getMessage());
            }
        }
        if (!registeredSources.isEmpty()) {
            log.info("[Job {}] Registered {} DOCUMENT graph nodes", jobId, registeredSources.size());
        }
    }

    /**
     * Creates SNIPPET graph nodes for each chunk with a CONTAINS edge back to
     * the parent DOCUMENT node, providing chunk→document provenance.
     */
    void registerSnippetNodes(UnifiedCrawlJob job, List<Document> chunkedDocuments) {
        if (knowledgeGraphService == null || chunkedDocuments == null) return;

        String jobId = job != null ? job.getJobId() : null;
        Long factSheetId = jobFactSheetId(job);
        String snippetPrefix = "chunk:" + jobId + ":";

        // Pre-cache parent DOCUMENT nodes by sourcePath to avoid N+1 DB queries.
        // Many chunks share the same source file, so this collapses thousands of
        // lookups down to one per unique source.
        Map<String, Optional<GraphNode>> parentDocCache = new HashMap<>();

        int snippetCount = 0;
        for (int i = 0; i < chunkedDocuments.size(); i++) {
            Document chunk = chunkedDocuments.get(i);
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;

            String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                    ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                    : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
            if (sourcePath == null) continue;

            try {
                // Cached parent doc lookup — one DB query per unique sourcePath
                Optional<GraphNode> parentDoc = parentDocCache.computeIfAbsent(sourcePath,
                        sp -> knowledgeGraphService.getNodeByExternalId(sp, NodeLevel.DOCUMENT, factSheetId));
                if (parentDoc.isEmpty()) continue;

                String snippetId = snippetPrefix + sourcePath + ":" + i;
                String content = chunk.getText();
                String preview = content != null && content.length() > 200
                        ? content.substring(0, 200) + "..." : content;

                knowledgeGraphService.createSnippetNode(parentDoc.get(), snippetId, preview, i);
                snippetCount++;

                // Mark this passage as graph-indexed in the cross-index tracker
                if (crawlIndexTrackingCallback != null) {
                    String chunkDocId = chunk.getId();
                    if (chunkDocId != null) {
                        crawlIndexTrackingCallback.markPassageGraphIndexed(chunkDocId, snippetId);
                    }
                }
            } catch (Exception e) {
                log.debug("[Job {}] Failed to register SNIPPET node for chunk {}: {}", jobId, i, e.getMessage());
            }
        }

        if (snippetCount > 0) {
            log.info("[Job {}] Registered {} SNIPPET graph nodes ({} unique parent docs cached)",
                    jobId, snippetCount, parentDocCache.size());

            // Update document-level graph status in cross-index tracker
            if (crawlIndexTrackingCallback != null) {
                markDocumentsGraphIndexedInCrossIndex(job, chunkedDocuments, snippetCount);
            }
        }
    }

    /**
     * Promotes a table document to a graph node with CONTAINS edge from parent DOCUMENT.
     */
    void promoteTableToGraphNode(Document doc, UnifiedCrawlJob job, Long factSheetId, String crawlSource) {
        if (knowledgeGraphService == null) return;
        String jobId = job != null ? job.getJobId() : null;
        try {
            Map<String, Object> meta = doc.getMetadata();
            String sheetName = meta.get(GraphConstants.META_SHEET_NAME) instanceof String ? (String) meta.get(GraphConstants.META_SHEET_NAME) : null;
            String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                    ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                    : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
            String structuralSection = meta.get("structural_section") instanceof String
                    ? (String) meta.get("structural_section") : null;

            // Resolve table title: prefer sheetName, then structural_section, then table_id,
            // then compose from page/index to avoid collisions when a PDF has multiple tables
            String tableTitle;
            if (sheetName != null) {
                tableTitle = sheetName;
            } else if (structuralSection != null) {
                tableTitle = structuralSection;
            } else {
                String tableId = meta.get("table_id") instanceof String ? (String) meta.get("table_id") : null;
                if (tableId != null) {
                    tableTitle = tableId;
                } else {
                    Object pageNum = meta.get("table_page_number");
                    Object tableIdx = meta.get("table_index");
                    if (pageNum != null || tableIdx != null) {
                        tableTitle = "Table"
                                + (tableIdx != null ? " " + tableIdx : "")
                                + (pageNum != null ? " (p" + pageNum + ")" : "");
                    } else {
                        tableTitle = "Table";
                    }
                }
            }
            String externalId = "table:" + (sourcePath != null ? sourcePath : jobId) + ":" + tableTitle;

            int rowCount = meta.get(GraphConstants.META_TABLE_ROW_COUNT) instanceof Number
                    ? ((Number) meta.get(GraphConstants.META_TABLE_ROW_COUNT)).intValue() : 0;
            int columnCount = meta.get(GraphConstants.META_TABLE_COLUMN_COUNT) instanceof Number
                    ? ((Number) meta.get(GraphConstants.META_TABLE_COLUMN_COUNT)).intValue() : 0;
            String headerStr = meta.get(GraphConstants.META_TABLE_HEADERS) instanceof String
                    ? (String) meta.get(GraphConstants.META_TABLE_HEADERS) : null;
            List<String> headers = headerStr != null
                    ? Arrays.asList(headerStr.split(",")) : List.of();
            String fullContent = meta.get("full_table_content") instanceof String
                    ? (String) meta.get("full_table_content") : null;
            String preview = fullContent != null && fullContent.length() > 500
                    ? fullContent.substring(0, 500) + "..." : fullContent;

            // Find or create parent DOCUMENT node (mirrors DIS behavior)
            String parentNodeId = null;
            if (sourcePath != null) {
                Optional<GraphNode> docNode = knowledgeGraphService.getNodeByExternalId(
                        sourcePath, NodeLevel.DOCUMENT, factSheetId);
                if (docNode.isEmpty()) {
                    // Create the DOCUMENT node if it doesn't exist yet — prevents
                    // table nodes from being silently dropped when timing causes
                    // the DOCUMENT node to not yet be committed.
                    String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                            ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                    String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                            ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "FILE";
                    String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                            ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                    Map<String, Object> docMeta = new LinkedHashMap<>();
                    docMeta.put(GraphConstants.META_LOADER, loaderName);
                    docMeta.put("jobId", jobId);
                    // addDocument returns the created GraphNode — no need for a second DB lookup
                    GraphNode createdDoc = knowledgeGraphService.addDocument(
                            crawlSource, jobId, sourceType, sourcePath, fileName, null, docMeta, factSheetId);
                    docNode = createdDoc != null ? Optional.of(createdDoc) : Optional.empty();
                }
                if (docNode.isPresent()) parentNodeId = docNode.get().getNodeId();
            }
            if (parentNodeId == null) {
                log.warn("[Job {}] No DOCUMENT node could be found or created for table '{}' (source: {}), skipping promotion",
                        jobId, tableTitle, sourcePath);
                return;
            }

            Map<String, Object> tableMeta = new LinkedHashMap<>();
            if (meta.get("table_index") instanceof Number) {
                tableMeta.put("tableIndex", ((Number) meta.get("table_index")).intValue());
            }
            if (meta.get("table_page_number") instanceof Number) {
                tableMeta.put("pageNumber", ((Number) meta.get("table_page_number")).intValue());
            }
            if (meta.get("table_extraction_method") instanceof String) {
                tableMeta.put("extractionMethod", (String) meta.get("table_extraction_method"));
            }
            if (meta.get("table_summary") instanceof String tableSummary && !tableSummary.isBlank()) {
                tableMeta.put("tableSummary", tableSummary);
            }
            // Store full table content in metadata so entity preview can render it
            if (fullContent != null) {
                tableMeta.put("fullTableContent", fullContent);
            }

            knowledgeGraphService.createTableNode(parentNodeId, externalId, tableTitle,
                    rowCount, columnCount, headers, preview, tableMeta);
            log.debug("[Job {}] Promoted table '{}' to graph node", jobId, tableTitle);
        } catch (Exception e) {
            log.warn("[Job {}] Failed to promote table to graph node: {}", jobId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FORMULA / TABLE GRAPH PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Persists an Excel formula dependency graph into the knowledge graph.
     * Creates SHEET (TABLE) and CELL (ENTITY) nodes with CONTAINS and
     * DEPENDS_ON / RANGE_INPUT / CROSS_SHEET_DEPENDS_ON edges.
     */
    @SuppressWarnings("unchecked")
    GraphPersistenceHelper.GraphPersistResult persistFormulaGraph(UnifiedCrawlJob job, Document doc) {
        if (knowledgeGraphService == null) return GraphPersistenceHelper.GraphPersistResult.empty();
        Map<String, Object> meta = doc.getMetadata();
        String graphJson = meta.get(GraphConstants.META_FORMULA_GRAPH) instanceof String
                ? (String) meta.get(GraphConstants.META_FORMULA_GRAPH) : null;
        if (graphJson == null || graphJson.isBlank()) return GraphPersistenceHelper.GraphPersistResult.empty();
        String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;
        return persistGraphJson(job, graphJson, sourcePath);
    }

    @SuppressWarnings("unchecked")
    GraphPersistenceHelper.GraphPersistResult persistGraphJson(UnifiedCrawlJob job, String graphJson, String sourcePath) {
        return persistGraphJson(job, graphJson, sourcePath, GraphConstants.META_FORMULA_GRAPH);
    }

    @SuppressWarnings("unchecked")
    GraphPersistenceHelper.GraphPersistResult persistGraphJson(UnifiedCrawlJob job, String graphJson, String sourcePath, String metadataKey) {
        if (knowledgeGraphService == null) return GraphPersistenceHelper.GraphPersistResult.empty();
        if (job != null && isCancelled(job)) {
            return GraphPersistenceHelper.GraphPersistResult.empty();
        }
        String jobId = job != null ? job.getJobId() : null;
        try {

            // Reuse static ObjectMapper — thread-safe for reads, avoids per-call allocation
            Map<String, Object> graph = GraphPersistenceHelper.EDGE_METADATA_MAPPER.readValue(graphJson, Map.class);

            List<Map<String, Object>> entities = (List<Map<String, Object>>) graph.get("entities");
            List<Map<String, Object>> relationships = (List<Map<String, Object>>) graph.get("relationships");

            // Resolve or create the parent DOCUMENT node — inherit its factSheetId for scoped persistence
            String parentNodeId = null;
            Long factSheetId = jobFactSheetId(job);
            String crawlSource = "crawl:" + jobId;
            if (sourcePath != null) {
                if (job != null && isCancelled(job)) {
                    return GraphPersistenceHelper.GraphPersistResult.empty();
                }
                Optional<GraphNode> docNode = knowledgeGraphService.getNodeByExternalId(
                        sourcePath, NodeLevel.DOCUMENT, factSheetId);
                if (docNode.isEmpty()) {
                    // Create DOCUMENT node if missing — prevents formula/table graph entities
                    // from being orphaned when the DOCUMENT node hasn't been committed yet
                    try {
                        Map<String, Object> docMeta = new LinkedHashMap<>();
                        docMeta.put("jobId", jobId);
                        // addDocument returns the created GraphNode — no need for a second DB lookup
                        GraphNode createdDoc = knowledgeGraphService.addDocument(
                                crawlSource, jobId, "FILE", sourcePath, sourcePath, null, docMeta, factSheetId);
                        docNode = createdDoc != null ? Optional.of(createdDoc) : Optional.empty();
                    } catch (Exception createEx) {
                        log.warn("[Job {}] Failed to create DOCUMENT node for source '{}': {}",
                                jobId, sourcePath, createEx.getMessage());
                    }
                }
                if (docNode.isPresent()) {
                    parentNodeId = docNode.get().getNodeId();
                    factSheetId = docNode.get().getFactSheetId();
                    // Store graph JSON on the DOCUMENT node so resolveExcelGraphJson can find it
                    try {
                        Map<String, Object> docMeta = new HashMap<>();
                        docMeta.put(metadataKey, graphJson);
                        knowledgeGraphService.updateNode(parentNodeId, null, null, docMeta);
                    } catch (Exception e) {
                        log.debug("[Job {}] Could not store {} on DOCUMENT node: {}", jobId, metadataKey, e.getMessage());
                    }
                }
            }

            Map<String, String> externalToNodeId = new HashMap<>();
            Map<String, String> sheetNameToTableNodeId = new HashMap<>();
            // Pre-compute the CONTAINS label — same constant for every entity in this loop
            String containsLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_CONTAINS);

            // Sort so SHEET/TABLE entities are processed before CELL entities
            if (entities != null) {
                entities.sort((a, b) -> {
                    String typeA = (String) a.getOrDefault("type", "CELL");
                    String typeB = (String) b.getOrDefault("type", "CELL");
                    boolean containerA = "SHEET".equals(typeA) || "TABLE".equals(typeA);
                    boolean containerB = "SHEET".equals(typeB) || "TABLE".equals(typeB);
                    if (containerA && !containerB) return -1;
                    if (!containerA && containerB) return 1;
                    return 0;
                });
            }

            if (entities != null) {
                for (Map<String, Object> entity : entities) {
                    if (job != null && isCancelled(job)) {
                        return GraphPersistenceHelper.GraphPersistResult.empty();
                    }
                    String externalId = (String) entity.get("id");
                    String title = (String) entity.get("title");
                    String type = (String) entity.getOrDefault("type", "CELL");
                    String description = (String) entity.get("description");
                    NodeLevel nodeLevel = GraphPersistenceHelper.nodeLevelForEntityType(type);

                    Map<String, Object> entityMeta = new HashMap<>();
                    // Carry the entity's own metadata onto the node FIRST so TABLE nodes keep their
                    // rowCount/columnCount/headers/fullTableContent (set by TableCellGraphBuilder) and
                    // actually render in the Tables tab; the keys below augment it.
                    if (entity.get("metadata") instanceof Map<?, ?> graphEntityMeta) {
                        for (Map.Entry<?, ?> e : graphEntityMeta.entrySet()) {
                            if (e.getKey() != null) {
                                entityMeta.put(String.valueOf(e.getKey()), e.getValue());
                            }
                        }
                    }
                    entityMeta.put(GraphConstants.META_SOURCE, jobId);
                    entityMeta.put("entity_subtype", type.toLowerCase());
                    if (sourcePath != null) entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                    // Extract cell reference from namespaced IDs (e.g., "wb:Budget.xlsx/cell:Sheet1!A1")
                    if (externalId != null) {
                        int cellIdx = externalId.indexOf("cell:");
                        if (cellIdx >= 0) {
                            entityMeta.put("cell_reference", externalId.substring(cellIdx + 5));
                        }
                    }

                    // Create or update node — factSheetId-scoped duplicate detection + update on re-ingest
                    GraphNode created = knowledgeGraphService.createNode(
                            nodeLevel, externalId, title, description, entityMeta, factSheetId);
                    externalToNodeId.put(externalId, created.getNodeId());
                    if (job != null) job.incrementEntityType(type);

                    // Link cells to their TABLE node; TABLE nodes to DOCUMENT
                    if (parentNodeId != null && nodeLevel == NodeLevel.ENTITY) {
                        String tableNodeId = findFormulaTableNode(externalId, title, sheetNameToTableNodeId);
                        String linkParent = tableNodeId != null ? tableNodeId : parentNodeId;
                        String containsDescription = graphPersistenceHelper.semanticRelationDescription(
                                (tableNodeId != null ? "Table" : "Document") + " contains " + type.toLowerCase() + " " + title,
                                containsLabel);
                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath, metadataKey,
                                linkParent, externalId, containsLabel, containsDescription, null,
                                Map.of("entityType", type, "metadataKey", metadataKey));
                        knowledgeGraphService.createEdgeWithMetadata(
                                linkParent, created.getNodeId(), EdgeType.CONTAINS, 1.0,
                                containsLabel, containsDescription, metaJson, EdgeProvenance.EXTRACTED, factSheetId);
                    } else if (parentNodeId != null && nodeLevel == NodeLevel.TABLE) {
                        if (title != null) {
                            sheetNameToTableNodeId.put(title.toLowerCase(), created.getNodeId());
                        }
                        String containsDescription = graphPersistenceHelper.semanticRelationDescription("Document contains sheet " + title, containsLabel);
                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath, metadataKey,
                                parentNodeId, externalId, containsLabel, containsDescription, null,
                                Map.of("entityType", type, "metadataKey", metadataKey));
                        knowledgeGraphService.createEdgeWithMetadata(
                                parentNodeId, created.getNodeId(), EdgeType.CONTAINS, 1.0,
                                containsLabel, containsDescription, metaJson, EdgeProvenance.EXTRACTED, factSheetId);
                    }
                }
            }

            // Create dependency edges between cells with full metadata
            if (relationships != null) {
                for (Map<String, Object> rel : relationships) {
                    if (job != null && isCancelled(job)) {
                        return GraphPersistenceHelper.GraphPersistResult.empty();
                    }
                    String sourceExtId = (String) rel.get("source");
                    String targetExtId = (String) rel.get("target");
                    String type = (String) rel.getOrDefault("type", "REFERENCES");
                    String relDescription = (String) rel.get("description");
                    String sourceNodeId = externalToNodeId.get(sourceExtId);
                    String targetNodeId = externalToNodeId.get(targetExtId);
                    if (sourceNodeId != null && targetNodeId != null) {
                        Map<String, Object> relMeta = (Map<String, Object>) rel.get("metadata");
                        String label = graphPersistenceHelper.semanticRelationLabel(type);
                        String description = graphPersistenceHelper.semanticRelationDescription(relDescription, label);
                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath, metadataKey,
                                sourceExtId, targetExtId, label, description,
                                graphPersistenceHelper.numberAsDouble(rel.get("confidence")), relMeta);
                        knowledgeGraphService.createEdgeWithMetadata(
                                sourceNodeId, targetNodeId, EdgeType.USER_DEFINED, 1.0,
                                label, description, metaJson, EdgeProvenance.EXTRACTED, factSheetId);
                        if (job != null) job.incrementRelationshipType(label);
                    }
                }
            }

            int entityCount = entities != null ? entities.size() : 0;
            int relCount = relationships != null ? relationships.size() : 0;

            // Update job counters so the UI shows formula graph progress
            if (job != null && !isCancelled(job)) {
                job.getEntitiesExtracted().addAndGet(entityCount);
                job.getRelationshipsExtracted().addAndGet(relCount);
                documentTracker.recordDocumentProgress(job,
                        sourcePath != null ? sourcePath : metadataKey + ":" + Integer.toHexString(graphJson.hashCode()),
                        documentTracker.documentFileName(Map.of(GraphConstants.META_SOURCE_PATH, sourcePath != null ? sourcePath : ""), sourcePath),
                        sourcePath,
                        null,
                        metadataKey,
                        null,
                        "STRUCTURAL_GRAPH",
                        "COMPLETED",
                        0,
                        entityCount,
                        relCount,
                        "Persisted " + metadataKey,
                        null,
                        List.of(metadataKey),
                        true);
            }

            log.info("[Job {}] Persisted formula graph: {} entities, {} relationships", jobId, entityCount, relCount);
            return new GraphPersistenceHelper.GraphPersistResult(entityCount, relCount);
        } catch (Exception e) {
            log.warn("[Job {}] Failed to persist formula graph: {}", jobId, e.getMessage());
            if (job != null && !isCancelled(job)) {
                documentTracker.recordDocumentProgress(job,
                        sourcePath != null ? sourcePath : metadataKey + ":" + Integer.toHexString(graphJson.hashCode()),
                        documentTracker.documentFileName(Map.of(GraphConstants.META_SOURCE_PATH, sourcePath != null ? sourcePath : ""), sourcePath),
                        sourcePath,
                        null,
                        metadataKey,
                        null,
                        "STRUCTURAL_GRAPH",
                        "FAILED",
                        0,
                        0,
                        0,
                        "Failed to persist " + metadataKey,
                        e.getMessage(),
                        List.of(metadataKey),
                        true);
            }
            return GraphPersistenceHelper.GraphPersistResult.empty();
        }
    }

    /**
     * Finds the TABLE node for a cell by parsing sheet name from externalId (e.g., "cell:Sheet1!A1").
     */
    String findFormulaTableNode(String externalId, String title, Map<String, String> sheetNameToTableNodeId) {
        if (sheetNameToTableNodeId.isEmpty()) return null;
        // Extract cell reference from namespaced IDs: "wb:X/cell:Sheet1!A1" or plain "cell:Sheet1!A1"
        String cellRef = null;
        if (externalId != null) {
            int cellIdx = externalId.indexOf("cell:");
            if (cellIdx >= 0) {
                cellRef = externalId.substring(cellIdx + 5);
            }
        }
        if (cellRef == null && title != null && title.contains("!")) {
            cellRef = title;
        }
        if (cellRef != null && cellRef.contains("!")) {
            String sheetName = cellRef.substring(0, cellRef.indexOf('!')).toLowerCase();
            return sheetNameToTableNodeId.get(sheetName);
        }
        // Fallback for generic table cell IDs: "tbl:ns/table:Name/cell:R0C2"
        if (externalId != null && externalId.contains("/table:") && externalId.contains("/cell:")) {
            int tblStart = externalId.indexOf("/table:") + 7;
            int cellStart = externalId.indexOf("/cell:", tblStart);
            if (cellStart > tblStart) {
                String tblName = externalId.substring(tblStart, cellStart).toLowerCase();
                return sheetNameToTableNodeId.get(tblName);
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CROSS-INDEX GRAPH TRACKING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Updates document-level graph index status after snippet registration completes.
     */
    private void markDocumentsGraphIndexedInCrossIndex(UnifiedCrawlJob job,
                                                       List<Document> chunkedDocuments,
                                                       int totalSnippets) {
        String jobId = job != null ? job.getJobId() : null;
        Long factSheetId = jobFactSheetId(job);
        if (factSheetId == null) return;

        try {
            // Count graph nodes per source
            Map<String, Integer> countBySource = new LinkedHashMap<>();
            for (Document chunk : chunkedDocuments) {
                Map<String, Object> meta = chunk.getMetadata();
                if (meta == null) continue;
                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                if (sourcePath != null) {
                    countBySource.merge(sourcePath, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : countBySource.entrySet()) {
                crawlIndexTrackingCallback.markDocumentGraphIndexed(
                        entry.getKey(), factSheetId, entry.getValue());
            }
        } catch (Exception e) {
            log.debug("[Job {}] Failed to update document graph status in cross-index: {}",
                    jobId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private boolean isCancelled(UnifiedCrawlJob job) {
        if (job == null) return false;
        if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
            job.setCompletedAt(Instant.now());
            return true;
        }
        return false;
    }

    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }
}
