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

import ai.kompile.app.web.controllers.RagTestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for RAG testing functionality.
 * Exposes embedding testing, vector search, hybrid search, reranking, and graph RAG queries.
 */
@Component
public class RagTestTool {

    private static final Logger logger = LoggerFactory.getLogger(RagTestTool.class);

    private final RagTestController ragTestController;

    @Autowired
    public RagTestTool(@Autowired(required = false) RagTestController ragTestController) {
        this.ragTestController = ragTestController;
    }

    public record GetRagTestStatusInput() {}
    public record TestEmbedInput(String text) {}
    public record TestRagQueryInput(String query, Integer maxResults, Double threshold, Boolean includeKeyword, Boolean includeSemantic) {}
    public record HybridSearchInput(String query, Integer maxResults, Double threshold) {}
    public record GetRerankerTypesInput() {}
    public record QueryWithRerankingInput(
            String query, Integer maxResults, Double threshold, String rerankerType,
            Integer fbDocs, Integer fbTerms, Integer topK, Float originalQueryWeight,
            Boolean filterTerms, Boolean outputQuery, Float k1, Float b, Float newTermWeight,
            Float alpha, Float beta, Float gamma, Boolean useNegative, Integer r, Integer n,
            Float axiomBeta, Boolean deterministic, Long seed, String crossEncoderModel) {}
    public record GetCrossEncoderModelsInput() {}
    public record DownloadCrossEncoderModelInput(String modelId) {}
    public record GetCrossEncoderModelInfoInput(String modelId) {}
    public record DeleteCrossEncoderModelInput(String modelId) {}
    public record TestGraphRagQueryInput(String query, String searchType, Integer maxResults, String conversationId) {}
    public record GetGraphRagInfoInput() {}

    @Tool(name = "get_rag_test_status",
            description = "Gets the RAG test service status including available components (embeddings, vector store, keyword search, rerankers).")
    public Map<String, Object> getRagTestStatus(GetRagTestStatusInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            ResponseEntity<?> response = ragTestController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting RAG test status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "test_text_embedding",
            description = "Tests embedding generation for a text string. Returns embedding vector, dimensions, and timing.")
    public Map<String, Object> testEmbed(TestEmbedInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            if (input.text() == null) return Map.of("status", "error", "error", "Text is required");
            ResponseEntity<?> response = ragTestController.testEmbed(input.text());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error testing embedding: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "test_rag_query",
            description = "Tests a RAG query with configurable keyword and semantic search. Returns matched documents with scores.")
    public Map<String, Object> testQuery(TestRagQueryInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            if (input.query() == null) return Map.of("status", "error", "error", "Query is required");
            int maxResults = input.maxResults() != null ? input.maxResults() : 5;
            double threshold = input.threshold() != null ? input.threshold() : 0.0;
            boolean keyword = input.includeKeyword() != null ? input.includeKeyword() : true;
            boolean semantic = input.includeSemantic() != null ? input.includeSemantic() : true;
            ResponseEntity<?> response = ragTestController.testQuery(input.query(), maxResults, threshold, keyword, semantic);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error testing RAG query: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "test_hybrid_search",
            description = "Tests hybrid search combining keyword and semantic search with fusion scoring.")
    public Map<String, Object> hybridSearch(HybridSearchInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            if (input.query() == null) return Map.of("status", "error", "error", "Query is required");
            int maxResults = input.maxResults() != null ? input.maxResults() : 10;
            double threshold = input.threshold() != null ? input.threshold() : 0.0;
            ResponseEntity<?> response = ragTestController.hybridSearch(input.query(), maxResults, threshold);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error in hybrid search: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_reranker_types",
            description = "Gets available reranker types for search result reranking.")
    public Map<String, Object> getRerankers(GetRerankerTypesInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            ResponseEntity<?> response = ragTestController.getRerankers();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting rerankers: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "test_query_with_reranking",
            description = "Tests a RAG query with reranking. Supports reranker types: none, bm25_rm3, bm25_rocchio, bm25_axiom, cross_encoder. Configurable parameters for each reranker type.")
    public Map<String, Object> queryWithReranking(QueryWithRerankingInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            if (input.query() == null) return Map.of("status", "error", "error", "Query is required");
            int maxResults = input.maxResults() != null ? input.maxResults() : 10;
            double threshold = input.threshold() != null ? input.threshold() : 0.0;
            String rerankerType = input.rerankerType() != null ? input.rerankerType() : "none";
            int fbDocs = input.fbDocs() != null ? input.fbDocs() : 10;
            int fbTerms = input.fbTerms() != null ? input.fbTerms() : 10;
            int topK = input.topK() != null ? input.topK() : -1;
            float originalQueryWeight = input.originalQueryWeight() != null ? input.originalQueryWeight() : 0.5f;
            boolean filterTerms = input.filterTerms() != null ? input.filterTerms() : true;
            boolean outputQuery = input.outputQuery() != null ? input.outputQuery() : false;
            float k1 = input.k1() != null ? input.k1() : 0.9f;
            float b = input.b() != null ? input.b() : 0.4f;
            float newTermWeight = input.newTermWeight() != null ? input.newTermWeight() : 0.2f;
            float alpha = input.alpha() != null ? input.alpha() : 1.0f;
            float beta = input.beta() != null ? input.beta() : 0.75f;
            float gamma = input.gamma() != null ? input.gamma() : 0.15f;
            boolean useNegative = input.useNegative() != null ? input.useNegative() : false;
            int r = input.r() != null ? input.r() : 20;
            int n = input.n() != null ? input.n() : 30;
            float axiomBeta = input.axiomBeta() != null ? input.axiomBeta() : 0.4f;
            boolean deterministic = input.deterministic() != null ? input.deterministic() : true;
            long seed = input.seed() != null ? input.seed() : 42L;
            String crossEncoderModel = input.crossEncoderModel() != null ? input.crossEncoderModel() : "";
            ResponseEntity<?> response = ragTestController.queryWithReranking(
                    input.query(), maxResults, threshold, rerankerType,
                    fbDocs, fbTerms, topK, originalQueryWeight, filterTerms, outputQuery,
                    k1, b, newTermWeight, alpha, beta, gamma, useNegative,
                    r, n, axiomBeta, deterministic, seed, crossEncoderModel);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error in query with reranking: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_cross_encoder_models",
            description = "Gets available cross-encoder models for reranking.")
    public Map<String, Object> getCrossEncoderModels(GetCrossEncoderModelsInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            ResponseEntity<?> response = ragTestController.getCrossEncoderModels();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting cross-encoder models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "download_cross_encoder_model",
            description = "Downloads a cross-encoder model by its model ID for use in reranking.")
    public Map<String, Object> downloadCrossEncoderModel(DownloadCrossEncoderModelInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "Model ID is required");
            ResponseEntity<?> response = ragTestController.downloadCrossEncoderModel(input.modelId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error downloading cross-encoder model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_cross_encoder_model_info",
            description = "Gets information about a specific cross-encoder model.")
    public Map<String, Object> getCrossEncoderModelInfo(GetCrossEncoderModelInfoInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "Model ID is required");
            ResponseEntity<?> response = ragTestController.getCrossEncoderModelInfo(input.modelId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting cross-encoder model info: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_cross_encoder_model",
            description = "Deletes a cached cross-encoder model by its model ID.")
    public Map<String, Object> deleteCrossEncoderModel(DeleteCrossEncoderModelInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "Model ID is required");
            ResponseEntity<?> response = ragTestController.deleteCrossEncoderModel(input.modelId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting cross-encoder model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "test_graph_rag_query",
            description = "Tests a Graph RAG query. Search types: LOCAL (community-based), GLOBAL (full graph). Returns structured graph results.")
    public Map<String, Object> testGraphRagQuery(TestGraphRagQueryInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            if (input.query() == null) return Map.of("status", "error", "error", "Query is required");
            String searchType = input.searchType() != null ? input.searchType() : "LOCAL";
            int maxResults = input.maxResults() != null ? input.maxResults() : 5;
            String conversationId = input.conversationId() != null ? input.conversationId() : "test";
            ResponseEntity<?> response = ragTestController.testGraphRagQuery(input.query(), searchType, maxResults, conversationId);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error in graph RAG query: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_graph_rag_info",
            description = "Gets Graph RAG status and statistics including entity/relationship counts and community info.")
    public Map<String, Object> getGraphRagInfo(GetGraphRagInfoInput input) {
        try {
            if (ragTestController == null) return Map.of("status", "error", "error", "RAG test controller not available");
            ResponseEntity<?> response = ragTestController.getGraphRagInfo();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting graph RAG info: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
