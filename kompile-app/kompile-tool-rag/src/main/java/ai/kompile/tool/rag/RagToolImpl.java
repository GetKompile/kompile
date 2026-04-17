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

package ai.kompile.tool.rag;

import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.core.mcp.optimization.ResultReferenceCache;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.structured.MultiVectorDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG Tool implementation that supports multi-vector retrieval.
 *
 * <p>For table documents, this tool extracts the full table content from metadata
 * rather than using the summary text that was used for embedding. This provides
 * the LLM with complete table data for answering questions.</p>
 */
@Component
public class RagToolImpl {

    private static final Logger logger = LoggerFactory.getLogger(RagToolImpl.class);

    /** Metadata key for full table content */
    private static final String FULL_TABLE_CONTENT_KEY = "full_table_content";

    /** Metadata key for content type */
    private static final String CONTENT_TYPE_KEY = "content_type";

    private DocumentRetriever documentRetriever;
    private final ObjectMapper objectMapper;
    private final McpOptimizationConfigProvider optimizationProvider;
    private final ResultReferenceCache resultCache;

    @Value("${kompile.retrieval.useFullTableContent:true}")
    private boolean useFullTableContent;

    /**
     * Input record for RAG queries.
     */
    public record RagQueryInput(String query, Integer maxResults) {}

    @Autowired
    public RagToolImpl(List<DocumentRetriever> documentRetriever,
                       ObjectMapper objectMapper,
                       @Autowired(required = false) McpOptimizationConfigProvider optimizationProvider,
                       @Autowired(required = false) ResultReferenceCache resultCache) {
        if (documentRetriever.size() > 1) {
            for (DocumentRetriever retriever : documentRetriever) {
                if (retriever instanceof NoOpDocumentRetrieverImpl) {
                    continue;
                } else {
                    this.documentRetriever = retriever;
                    break;
                }
            }
        } else {
            this.documentRetriever = documentRetriever.get(0);
        }
        this.objectMapper = objectMapper;
        this.optimizationProvider = optimizationProvider != null
                ? optimizationProvider
                : McpOptimizationConfigProvider.ofDefaults();
        this.resultCache = resultCache;
        logger.debug("RagToolImpl constructed with DocumentRetriever: {}", documentRetriever.getClass().getSimpleName());
    }

    /**
     * Executes a RAG query with multi-vector retrieval support.
     *
     * <p>This method retrieves documents and, for table documents, extracts the full
     * table content from metadata to provide complete data to the LLM.</p>
     *
     * @param input The query input containing the search query and optional max results
     * @return Map containing query, status, retrieved documents, and metadata
     */
    @Tool(name = "rag_query",
            description = "Queries the document corpus using the configured retriever and returns relevant information snippets. " +
                         "For table data, returns full table content for accurate answers. " +
                         "Long documents are truncated and a result_id is returned; call fetch_result to retrieve full content.")
    public Map<String, Object> executeRagQuery(RagQueryInput input) {
        logger.info("RagTool: Executing RAG Query with input: {}", input);

        if (input.query() == null || input.query().trim().isEmpty()) {
            logger.warn("RagTool: Query is empty.");
            return Map.of("error", "Query cannot be empty.", "query", input.query(), "retrieved_documents", Collections.emptyList());
        }

        McpOptimizationConfig cfg = optimizationProvider.getConfiguration();
        boolean optimizationEnabled = Boolean.TRUE.equals(cfg.getEnabled());
        int maxDocsCeiling = optimizationEnabled && cfg.getRagMaxDocs() != null && cfg.getRagMaxDocs() > 0
                ? cfg.getRagMaxDocs()
                : 10;
        int defaultDocs = Math.min(3, maxDocsCeiling);
        int maxDocs = (input.maxResults() != null && input.maxResults() > 0)
                ? Math.min(input.maxResults(), maxDocsCeiling)
                : defaultDocs;

        try {
            // Use retrieveWithDetails to get full document metadata
            List<RetrievedDoc> retrievedDocs = documentRetriever.retrieveWithDetails(input.query(), maxDocs);

            if (retrievedDocs.isEmpty()) {
                logger.warn("RagTool: No documents found for query: {}", input.query());
                return Map.of("query", input.query(),
                             "status", "No relevant documents found.",
                             "retrieved_documents", Collections.emptyList(),
                             "metadata", Collections.emptyList());
            }

            // Extract content with multi-vector support (full table content when available)
            List<String> documentContents = new ArrayList<>();
            List<String> fullDocumentContents = new ArrayList<>();
            List<Map<String, Object>> documentMetadata = new ArrayList<>();
            int tableCount = 0;
            int textCount = 0;
            int truncatedCount = 0;
            int maxContentChars = optimizationEnabled && cfg.getRagMaxContentChars() != null
                    ? cfg.getRagMaxContentChars()
                    : -1;

            for (RetrievedDoc doc : retrievedDocs) {
                String content = extractFullContent(doc);
                fullDocumentContents.add(content);

                boolean docTruncated = false;
                if (maxContentChars > 0 && content != null && content.length() > maxContentChars) {
                    content = content.substring(0, maxContentChars)
                            + "…[truncated, full content available via fetch_result]";
                    docTruncated = true;
                    truncatedCount++;
                }
                documentContents.add(content);

                // Build metadata for the response
                Map<String, Object> meta = new HashMap<>();
                String contentType = getContentType(doc);
                meta.put("content_type", contentType);
                meta.put("id", doc.getId());
                if (docTruncated) {
                    meta.put("truncated", true);
                }

                if (doc.getScore() != null) {
                    meta.put("score", doc.getScore());
                }

                // Include source attribution if available
                doc.getSourceFilename().ifPresent(fn -> meta.put("source_filename", fn));
                doc.getPageNumber().ifPresent(pn -> meta.put("page_number", pn));

                // Include table-specific metadata
                if ("table".equals(contentType)) {
                    tableCount++;
                    copyTableMetadata(doc.getMetadata(), meta);
                } else {
                    textCount++;
                }

                documentMetadata.add(meta);
            }

            logger.info("RagTool: Successfully retrieved {} documents ({} tables, {} text, {} truncated) for query: {}",
                       retrievedDocs.size(), tableCount, textCount, truncatedCount, input.query());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", input.query());
            result.put("status", "Successfully retrieved documents.");
            result.put("retrieved_documents", documentContents);
            result.put("metadata", documentMetadata);
            result.put("table_count", tableCount);
            result.put("text_count", textCount);

            if (truncatedCount > 0 && resultCache != null && optimizationEnabled) {
                Map<String, Object> fullPayload = new LinkedHashMap<>();
                fullPayload.put("query", input.query());
                fullPayload.put("retrieved_documents", fullDocumentContents);
                fullPayload.put("metadata", documentMetadata);
                String resultId = resultCache.store(fullPayload);
                result.put("result_id", resultId);
                result.put("truncated_count", truncatedCount);
            }

            return result;

        } catch (Exception e) {
            logger.error("RagTool: Error during document retrieval for query [{}]: {}", input.query(), e.getMessage(), e);
            return Map.of("query", input.query(),
                         "error", "Failed during document retrieval: " + e.getMessage(),
                         "retrieved_documents", Collections.emptyList(),
                         "metadata", Collections.emptyList());
        }
    }

    /**
     * Extracts the full content from a retrieved document.
     *
     * <p>For table documents (content_type="table"), this returns the full table content
     * from metadata if available and useFullTableContent is enabled. This ensures the LLM
     * receives complete table data rather than just the summary used for embedding.</p>
     *
     * @param doc The retrieved document
     * @return Full content for LLM context
     */
    private String extractFullContent(RetrievedDoc doc) {
        if (!useFullTableContent) {
            return doc.getText();
        }

        // Use MultiVectorDocument helper if available, otherwise check directly
        String fullContent = MultiVectorDocument.extractFullContent(doc);
        if (fullContent != null) {
            return fullContent;
        }

        // Fallback: check metadata directly
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata != null) {
            Object fullTableContent = metadata.get(FULL_TABLE_CONTENT_KEY);
            if (fullTableContent instanceof String && !((String) fullTableContent).isEmpty()) {
                logger.debug("Using full table content from metadata for document: {}", doc.getId());
                return (String) fullTableContent;
            }
        }

        return doc.getText();
    }

    /**
     * Gets the content type from document metadata.
     */
    private String getContentType(RetrievedDoc doc) {
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata != null) {
            Object contentType = metadata.get(CONTENT_TYPE_KEY);
            if (contentType instanceof String) {
                return (String) contentType;
            }
        }
        return "text";
    }

    /**
     * Copies table-specific metadata to the response metadata map.
     */
    private void copyTableMetadata(Map<String, Object> source, Map<String, Object> target) {
        if (source == null) return;

        // Copy table-specific fields
        String[] tableFields = {"table_index", "table_row_count", "table_column_count", "table_headers", "table_type"};
        for (String field : tableFields) {
            if (source.containsKey(field)) {
                target.put(field, source.get(field));
            }
        }
    }
}