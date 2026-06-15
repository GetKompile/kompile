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

package ai.kompile.app.tools;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.retrievers.DocumentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for index and retrieval operations.
 * Exposes functionality to search, query, and manage the vector index.
 */
@Component
public class IndexOperationsTool {

    private static final Logger logger = LoggerFactory.getLogger(IndexOperationsTool.class);

    private final VectorStore vectorStore;
    private final List<DocumentRetriever> documentRetrievers;

    @Autowired
    public IndexOperationsTool(
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) List<DocumentRetriever> documentRetrievers) {
        this.vectorStore = vectorStore;
        this.documentRetrievers = documentRetrievers != null ? documentRetrievers : Collections.emptyList();
        logger.info("IndexOperationsTool initialized with vectorStore={}, retrievers={}",
                vectorStore != null ? vectorStore.getClass().getSimpleName() : "none",
                this.documentRetrievers.size());
    }

    // Input records for tools
    public record SearchIndexInput(String query, Integer topK, Double similarityThreshold) {}
    public record GetIndexStatsInput() {}
    public record SimilaritySearchInput(String query, Integer maxResults) {}
    public record GetIndexConfigInput() {}

    /**
     * Searches the vector index for similar documents.
     */
    @Tool(name = "search_index",
            description = "Searches the vector index for documents similar to the query. Returns up to topK results (default 5, max 50). Optionally specify similarityThreshold (0.0-1.0) to filter by minimum similarity score.")
    public Map<String, Object> searchIndex(SearchIndexInput input) {
        logger.info("Searching index with query: '{}', topK: {}, threshold: {}",
                truncateForLog(input.query()), input.topK(), input.similarityThreshold());

        if (input.query() == null || input.query().trim().isEmpty()) {
            return Map.of("status", "error", "error", "Query cannot be empty");
        }

        if (vectorStore == null) {
            return Map.of("status", "error", "error", "Vector store not available");
        }

        try {
            int topK = input.topK() != null && input.topK() > 0 ? Math.min(input.topK(), 50) : 5;

            // Perform similarity search
            List<Document> results = vectorStore.similaritySearch(input.query(), topK);

            List<Map<String, Object>> documents = new ArrayList<>();
            for (Document doc : results) {
                Map<String, Object> docInfo = new LinkedHashMap<>();
                docInfo.put("id", doc.getId());

                // Truncate content for response
                String content = doc.getText();
                if (content != null) {
                    docInfo.put("content", content.length() > 500 ? content.substring(0, 500) + "..." : content);
                    docInfo.put("contentLength", content.length());
                }

                // Include metadata
                if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
                    docInfo.put("metadata", doc.getMetadata());
                }

                documents.add(docInfo);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("query", input.query());
            result.put("topK", topK);
            result.put("resultCount", documents.size());
            result.put("documents", documents);

            return result;

        } catch (Exception e) {
            logger.error("Error searching index: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Search failed: " + e.getMessage());
        }
    }

    /**
     * Gets statistics about the vector index.
     */
    @Tool(name = "get_index_stats",
            description = "Gets statistics about the vector index including document count, index type, and configuration.")
    public Map<String, Object> getIndexStats(GetIndexStatsInput input) {
        logger.info("Getting index statistics");

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("timestamp", new Date().toString());

            if (vectorStore != null) {
                result.put("vectorStoreAvailable", true);
                result.put("vectorStoreType", vectorStore.getClass().getSimpleName());
                result.put("vectorStoreClass", vectorStore.getClass().getName());

                // Try to get count via reflection if available
                try {
                    var countMethod = vectorStore.getClass().getMethod("getDocumentCount");
                    long count = (Long) countMethod.invoke(vectorStore);
                    result.put("documentCount", count);
                    result.put("isEmpty", count == 0);
                } catch (NoSuchMethodException e1) {
                    try {
                        var countMethod = vectorStore.getClass().getMethod("count");
                        long count = (Long) countMethod.invoke(vectorStore);
                        result.put("documentCount", count);
                        result.put("isEmpty", count == 0);
                    } catch (NoSuchMethodException e2) {
                        result.put("documentCount", "not supported");
                    } catch (Exception e) {
                        result.put("documentCount", "unavailable");
                        result.put("countError", e.getMessage());
                    }
                } catch (Exception e) {
                    result.put("documentCount", "unavailable");
                    result.put("countError", e.getMessage());
                }
            } else {
                result.put("vectorStoreAvailable", false);
                result.put("message", "No vector store configured");
            }

            // Retriever info
            if (!documentRetrievers.isEmpty()) {
                List<String> retrieverNames = new ArrayList<>();
                for (DocumentRetriever retriever : documentRetrievers) {
                    retrieverNames.add(retriever.getClass().getSimpleName());
                }
                result.put("availableRetrievers", retrieverNames);
                result.put("retrieverCount", retrieverNames.size());
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting index stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get stats: " + e.getMessage());
        }
    }

    /**
     * Performs a similarity search using the document retriever.
     */
    @Tool(name = "similarity_search",
            description = "Performs a similarity search for documents matching the query. Uses the configured document retriever. Returns up to maxResults documents (default 5, max 20).")
    public Map<String, Object> similaritySearch(SimilaritySearchInput input) {
        logger.info("Performing similarity search for: '{}', maxResults: {}",
                truncateForLog(input.query()), input.maxResults());

        if (input.query() == null || input.query().trim().isEmpty()) {
            return Map.of("status", "error", "error", "Query cannot be empty");
        }

        if (documentRetrievers.isEmpty()) {
            return Map.of("status", "error", "error", "No document retrievers available");
        }

        try {
            int maxResults = input.maxResults() != null && input.maxResults() > 0 ? Math.min(input.maxResults(), 20) : 5;

            // Use first non-noop retriever
            DocumentRetriever retriever = null;
            for (DocumentRetriever r : documentRetrievers) {
                if (!r.getClass().getSimpleName().contains("NoOp")) {
                    retriever = r;
                    break;
                }
            }

            if (retriever == null) {
                retriever = documentRetrievers.get(0);
            }

            List<String> retrievedDocs = retriever.retrieve(input.query(), maxResults);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("query", input.query());
            result.put("maxResults", maxResults);
            result.put("retrieverUsed", retriever.getClass().getSimpleName());
            result.put("resultCount", retrievedDocs.size());

            // Format results
            List<Map<String, Object>> documents = new ArrayList<>();
            for (int i = 0; i < retrievedDocs.size(); i++) {
                String doc = retrievedDocs.get(i);
                Map<String, Object> docInfo = new LinkedHashMap<>();
                docInfo.put("rank", i + 1);
                docInfo.put("content", doc.length() > 1000 ? doc.substring(0, 1000) + "..." : doc);
                docInfo.put("contentLength", doc.length());
                documents.add(docInfo);
            }
            result.put("documents", documents);

            return result;

        } catch (Exception e) {
            logger.error("Error in similarity search: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Search failed: " + e.getMessage());
        }
    }

    /**
     * Gets the current index configuration.
     */
    @Tool(name = "get_index_config",
            description = "Gets the current vector index configuration including similarity function, HNSW parameters, and storage details.")
    public Map<String, Object> getIndexConfig(GetIndexConfigInput input) {
        logger.info("Getting index configuration");

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            if (vectorStore != null) {
                result.put("vectorStoreType", vectorStore.getClass().getSimpleName());
                result.put("vectorStoreClass", vectorStore.getClass().getName());

                // Try to extract configuration via reflection or interface methods
                // This provides basic info - specific implementations may expose more
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("implementationType", vectorStore.getClass().getSimpleName());

                // Check for common configuration methods — probe via reflection; absence is expected
                try {
                    var method = vectorStore.getClass().getMethod("getIndexPath");
                    config.put("indexPath", method.invoke(vectorStore));
                } catch (NoSuchMethodException e) {
                    logger.debug("VectorStore {} does not expose getIndexPath()", vectorStore.getClass().getSimpleName());
                }

                try {
                    var method = vectorStore.getClass().getMethod("getSimilarityFunction");
                    config.put("similarityFunction", method.invoke(vectorStore));
                } catch (NoSuchMethodException e) {
                    logger.debug("VectorStore {} does not expose getSimilarityFunction()", vectorStore.getClass().getSimpleName());
                }

                try {
                    var method = vectorStore.getClass().getMethod("getDimensions");
                    config.put("dimensions", method.invoke(vectorStore));
                } catch (NoSuchMethodException e) {
                    logger.debug("VectorStore {} does not expose getDimensions()", vectorStore.getClass().getSimpleName());
                }

                result.put("configuration", config);
            } else {
                result.put("vectorStoreConfigured", false);
                result.put("message", "No vector store configured");
            }

            // Retriever configuration
            if (!documentRetrievers.isEmpty()) {
                List<Map<String, String>> retrieverConfigs = new ArrayList<>();
                for (DocumentRetriever retriever : documentRetrievers) {
                    Map<String, String> rc = new LinkedHashMap<>();
                    rc.put("name", retriever.getClass().getSimpleName());
                    rc.put("class", retriever.getClass().getName());
                    retrieverConfigs.add(rc);
                }
                result.put("retrievers", retrieverConfigs);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting index config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get config: " + e.getMessage());
        }
    }

    // Helper methods
    private String truncateForLog(String text) {
        if (text == null) return "null";
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
